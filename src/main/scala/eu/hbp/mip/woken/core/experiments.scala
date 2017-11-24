/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.core

import java.time.OffsetDateTime
import java.util.UUID

import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSelection, LoggingFSM, Props }
import akka.pattern.ask
import akka.util
import akka.util.Timeout
import com.github.levkhomich.akka.tracing.ActorTracing
import eu.hbp.mip.woken.api.{ RequestProtocol, _ }
import eu.hbp.mip.woken.config.WokenConfig.defaultSettings.{ defaultDb, dockerImage, isPredictive }
import eu.hbp.mip.woken.core.model.JobResult
import eu.hbp.mip.woken.core.validation.{ KFoldCrossValidation, ValidationPoolManager }
import eu.hbp.mip.woken.dao.JobResultsDAL
import eu.hbp.mip.woken.messages.external.{ Algorithm, Validation => ApiValidation }
import eu.hbp.mip.woken.messages.validation._
import eu.hbp.mip.woken.meta.{ MetaDataProtocol, VariableMetaData }
import spray.http.StatusCodes
import spray.httpx.marshalling.ToResponseMarshaller
import spray.json.{ JsString, _ }

import scala.concurrent.{ Await, Future }
import scala.util.Random

/**
  * We use the companion object to hold all the messages that the ``ExperimentActor`` receives.
  */
object ExperimentActor {

  // Incoming messages
  case class Job(
      jobId: String,
                // TODO: does not seem to be used
      inputDb: Option[String],
      algorithms: Seq[Algorithm],
      validations: Seq[ApiValidation],
      parameters: Map[String, String]
  )

  case class Start(job: Job) extends RestMessage {
    import ApiJsonSupport._
    import spray.httpx.SprayJsonSupport._
    implicit val jobFormat: RootJsonFormat[Job] = jsonFormat5(Job.apply)
    override def marshaller: ToResponseMarshaller[Start] =
      ToResponseMarshaller.fromMarshaller(StatusCodes.OK)(jsonFormat1(Start))
  }

  case object Done

  // Output messages: JobResult containing the experiment PFA
  type Result = eu.hbp.mip.woken.core.model.JobResult
  val Result = eu.hbp.mip.woken.core.model.JobResult

  case class ErrorResponse(message: String) extends RestMessage {

    import DefaultJsonProtocol._
    import spray.httpx.SprayJsonSupport._

    override def marshaller: ToResponseMarshaller[ErrorResponse] =
      ToResponseMarshaller.fromMarshaller(StatusCodes.InternalServerError)(
        jsonFormat1(ErrorResponse)
      )
  }

  def props(chronosService: ActorRef,
            resultDatabase: JobResultsDAL,
            jobResultsFactory: JobResults.Factory): Props =
    Props(classOf[ExperimentActor],
      chronosService,
      resultDatabase,
      jobResultsFactory)

  import JobResult._

  implicit val resultFormat: JsonFormat[Result]                   = JobResult.jobResultFormat
  implicit val errorResponseFormat: RootJsonFormat[ErrorResponse] = jsonFormat1(ErrorResponse.apply)
}

/** FSM States and internal data */
object ExperimentStates {
  import ExperimentActor.Job

  // FSM States

  sealed trait State
  case object WaitForNewJob  extends State
  case object WaitForWorkers extends State
  case object Reduce         extends State

  // FSM Data

  // TODO: results should be Map[Algorithm, \/[String,String]] to keep notion of error responses
  case class ExperimentData(
      job: Job,
      replyTo: ActorRef,
      results: Map[Algorithm, String],
      algorithms: Seq[Algorithm]
  ) {
    def isComplete: Boolean = results.size == algorithms.length
  }

}

/**
  * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
  *
  * This actor will have the responsibility of spawning one ValidationActor plus one LocalCoordinatorActor per algorithm and aggregate
  * the results before responding
  *
  */
class ExperimentActor(val chronosService: ActorRef,
                      val resultDatabase: JobResultsDAL,
                      val jobResultsFactory: JobResults.Factory)
    extends Actor
    with ActorLogging
    with ActorTracing
    with LoggingFSM[ExperimentStates.State, Option[ExperimentStates.ExperimentData]] {

  import ExperimentActor._
  import ExperimentStates._

  log.info("Experiment started")

  startWith(WaitForNewJob, None)

  when(WaitForNewJob) {
    case Event(Start(job), _) if job.algorithms.nonEmpty =>

      val replyTo = sender()
      val algorithms  = job.algorithms
      val validations = job.validations

      log.info("Start new experiment job")
      log.info(s"List of algorithms: ${algorithms.mkString(",")}")

      // Spawn an AlgorithmActor for every algorithm
      for (a <- algorithms) {
        val jobId  = UUID.randomUUID().toString
        val subJob = AlgorithmActor.Job(jobId, Some(defaultDb), a, validations, job.parameters)
        val worker = context.actorOf(
          AlgorithmActor
            .props(chronosService, resultDatabase, RequestProtocol),
          AlgorithmActor.actorName(subJob)
        )
        worker ! AlgorithmActor.Start(subJob)
      }

      goto(WaitForWorkers) using Some(ExperimentData(job, replyTo, Map.empty, algorithms))
  }

  when(WaitForWorkers) {
    case Event(AlgorithmActor.ResultResponse(algorithm, algorithmResults), Some(experimentData)) =>
      log.info(s"Received algorithm result $algorithmResults")
      val updatedResults        = experimentData.results + (algorithm -> algorithmResults)
      val updatedExperimentData = Some(experimentData.copy(results = updatedResults))
      if (experimentData.isComplete)
        goto(Reduce) using updatedExperimentData
      else
        stay using updatedExperimentData

    case Event(AlgorithmActor.ErrorResponse(algorithm, errorMessage), Some(experimentData)) =>
      log.error(s"Algorithm ${algorithm.code} returned with error $errorMessage")
      val updatedResults        = experimentData.results + (algorithm -> errorMessage)
      val updatedExperimentData = Some(experimentData.copy(results = updatedResults))
      if (experimentData.isComplete)
        goto(Reduce) using updatedExperimentData
      else
        stay using updatedExperimentData
  }

  when(Reduce) {
    case Event(Done, Some(experimentData)) =>
      log.info("Experiment - build final response")

      //TODO WP3 Save the results in results DB

      // Concatenate results while respecting received algorithms order
      val output = JsArray(
        experimentData.algorithms
          .map(
            a =>
              JsObject("code" -> JsString(a.code),
                       "name" -> JsString(a.name),
                       "data" -> JsonParser(experimentData.results(a)))
          )
          .toVector
      )

      experimentData.replyTo ! jobResultsFactory(
        Seq(
          JobResult(
            jobId = experimentData.job.jobId,
            node = "",
            timestamp = OffsetDateTime.now(),
            shape = "pfa_json",
            function = "",
            // TODO: early serialisation to Json, keep Json type?
            data = Some(output.compactPrint),
            error = None
          )
        )
      )
      stop
  }

  onTransition {
    case _ -> Reduce =>
      self ! Done
  }

  initialize()
}

/**
  * We use the companion object to hold all the messages that the ``AlgorithmActor``
  * receives.
  */
object AlgorithmActor {

  // Incoming messages
  case class Job(
      jobId: String,
      inputDb: Option[String],
      algorithm: Algorithm,
      validations: Seq[ApiValidation],
                // TODO: contains low level details (environment variables)
      parameters: Map[String, String]
  )
  case class Start(job: Job)
  case object Done

  case class ResultResponse(algorithm: Algorithm, data: String)
  case class ErrorResponse(algorithm: Algorithm, message: String)

  def props(chronosService: ActorRef,
            resultDatabase: JobResultsDAL,
            jobResultsFactory: JobResults.Factory): Props =
    Props(classOf[AlgorithmActor],
          chronosService,
          resultDatabase,
          jobResultsFactory)

  def actorName(job: Job): String =
      s"AlgorithmActor_job_${job.jobId}_algo_${job.algorithm.code}"

}

/** FSM States and internal data */
object AlgorithmStates {
  import AlgorithmActor.Job

  // FSM States
  sealed trait State
  case object WaitForNewJob  extends State
  case object WaitForWorkers extends State
  case object Reduce         extends State

  // FSM Data
  case class AlgorithmData(job: Job,
                           replyTo: ActorRef,
                           model: Option[String],
                           results: Map[ApiValidation, String],
                           validationCount: Int) {

    def isComplete: Boolean = (results.size == validationCount) && model.isDefined
  }
}

class AlgorithmActor(val chronosService: ActorRef,
                     val resultDatabase: JobResultsDAL,
                     val jobResultsFactory: JobResults.Factory)
    extends Actor
    with ActorLogging
    with LoggingFSM[AlgorithmStates.State, Option[AlgorithmStates.AlgorithmData]] {

  import AlgorithmActor._
  import AlgorithmStates._

  startWith(WaitForNewJob, None)

  when(WaitForNewJob) {
    case Event(AlgorithmActor.Start(job), _) =>

      val replyTo = sender()
      val algorithm   = job.algorithm
      val validations = if (isPredictive(algorithm.code)) job.validations else List()
      val parameters = job.parameters ++ FunctionsInOut.algoParameters(algorithm)

      log.info(s"Start job for algorithm ${algorithm.code}")
      log.info(s"List of validations: ${validations.size}")

      // Spawn a LocalCoordinatorActor
      {
        val jobId = UUID.randomUUID().toString
        val subJob =
          JobDto(jobId, dockerImage(algorithm.code), None, None, Some(defaultDb), parameters, None)
        val worker = context.actorOf(
          CoordinatorActor.props(chronosService, resultDatabase, None, jobResultsFactory),
          CoordinatorActor.actorName(subJob)
        )
        worker ! CoordinatorActor.Start(subJob)
      }

      // Spawn a CrossValidationActor for every validation
      for (v <- validations) {
        val jobId  = UUID.randomUUID().toString
        val subJob = CrossValidationActor.Job(jobId, job.inputDb, algorithm, v, parameters)
        val validationWorker = context.actorOf(
          CrossValidationActor.props(chronosService,
                                     resultDatabase,
                                     jobResultsFactory),
          CrossValidationActor.actorName(subJob)
        )
        validationWorker ! CrossValidationActor.Start(subJob)
      }

      goto(WaitForWorkers) using Some(
        AlgorithmData(job, replyTo, None, Map(), validations.size)
      )
  }

  when(WaitForWorkers) {
    case Event(JsonMessage(pfa: JsValue), Some(data: AlgorithmData)) =>
      // TODO - LC: why receiving one model is enough to stop this actor? If there are several validations, there should be as many models?
      // TODO: not clear where this JsonMessage comes from. Need types...
      val updatedData = data.copy(model = Some(pfa.compactPrint))
      log.info(s"Received PFA result, complete? ${updatedData.isComplete}")
      if (updatedData.isComplete)
        goto(Reduce) using Some(updatedData)
      else
        stay using Some(updatedData)

    case Event(CoordinatorActor.ErrorResponse(message), Some(data: AlgorithmData)) =>
      log.error(s"Execution of algorithm ${data.job.algorithm.code} failed with message: $message")
      // We cannot trained the model we notify supervisor and we stop
      context.parent ! ErrorResponse(data.job.algorithm, message)
      stop

    case Event(CrossValidationActor.ResultResponse(validation, results),
               Some(data: AlgorithmData)) =>
      log.info("Received validation result")
      val updatedData = data.copy(results = data.results + (validation -> results))
      if (updatedData.isComplete)
        goto(Reduce) using Some(updatedData)
      else
        stay using Some(updatedData)

    case Event(CrossValidationActor.ErrorResponse(validation, message),
               Some(data: AlgorithmData)) =>
      log.error(s"Validation of algorithm ${data.job.algorithm.code} returned with error : $message")
      val updatedData = data.copy(results = data.results + (validation -> message))
      if (updatedData.isComplete)
        goto(Reduce) using Some(updatedData)
      else
        stay using Some(updatedData)
  }

  when(Reduce) {
    case Event(Done, Some(data: AlgorithmData)) =>
      val validations = JsArray(
        data.results
          .map({
            case (key, value) =>
              JsObject("code" -> JsString(key.code),
                       "name" -> JsString(key.name),
                       "data" -> JsonParser(value))
          })
          .toVector
      )

      // TODO Do better by merging JsObject (not yet supported by Spray...)
      val pfa = data.model.get
        .replaceFirst("\"cells\":\\{",
                      "\"cells\":{\"validations\":" + validations.compactPrint + ",")

      data.replyTo ! AlgorithmActor.ResultResponse(data.job.algorithm, pfa)
      stop
  }

  onTransition {
    case _ -> Reduce =>
      self ! Done
  }

  initialize()
}

/**
  * We use the companion object to hold all the messages that the ``ValidationActor``
  * receives.
  */
object CrossValidationActor {

  // Incoming messages
  case class Job(
      jobId: String,
      inputDb: Option[String],
      algorithm: Algorithm,
      validation: ApiValidation,
                // TODO: contains low level environment variables
      parameters: Map[String, String]
  )
  case class Start(job: Job)
  case object Done

  // Output Messages
  case class ResultResponse(validation: ApiValidation, data: String)
  case class ErrorResponse(validation: ApiValidation, message: String)

  def props(chronosService: ActorRef,
            resultDatabase: JobResultsDAL,
            jobResultsFactory: JobResults.Factory): Props =
    Props(classOf[CrossValidationActor],
          chronosService,
          resultDatabase,
          jobResultsFactory)

  def actorName(job: Job): String =
      s"CrossValidationActor_job_${job.jobId}_algo_${job.algorithm.code}"

}

/** FSM States and internal data */
object CrossValidationStates {
  import CrossValidationActor.Job

  // FSM States
  sealed trait State

  case object WaitForNewJob extends State

  case object WaitForWorkers extends State

  case object Reduce extends State

  type Fold = String

  // FSM Data
  trait StateData {
    def job: Job
  }

  case object Uninitialized extends StateData {
    def job = throw new IllegalAccessException()
  }

  case class WaitForWorkersState(job: Job,
                                 replyTo: ActorRef,
                                 validation: KFoldCrossValidation,
                                 workers: Map[ActorRef, Fold],
                                 foldCount: Int,
                                 targetMetaData: VariableMetaData,
                                 average: (List[String], List[String]),
                                 results: Map[String, ScoringResult])
      extends StateData

  case class ReduceData(job: Job,
                        replyTo: ActorRef,
                        targetMetaData: VariableMetaData,
                        average: (List[String], List[String]),
                        results: Map[String, ScoringResult])
      extends StateData

}

/**
  *
  * @param chronosService
  * @param resultDatabase
  * @param jobResultsFactory
  */
class CrossValidationActor(val chronosService: ActorRef,
                           val resultDatabase: JobResultsDAL,
                           val jobResultsFactory: JobResults.Factory)
    extends Actor
    with ActorLogging
    with LoggingFSM[CrossValidationStates.State, CrossValidationStates.StateData] {

  import CrossValidationActor._
  import CrossValidationStates._

  def adjust[A, B](m: Map[A, B], k: A)(f: B => B): Map[A, B] = m.updated(k, f(m(k)))

  def nextValidationActor: Option[ActorSelection] = {
    val validationPool = ValidationPoolManager.validationPool
    if (validationPool.isEmpty) None
    else
      Some(
        context.actorSelection(validationPool.toList(Random.nextInt(validationPool.size)))
      )
  }

  startWith(WaitForNewJob, Uninitialized)

  when(WaitForNewJob) {
    case Event(Start(job), _) =>
      val replyTo    = sender()
      val algorithm  = job.algorithm
      val validation = job.validation

      log.info(s"List of folds: ${validation.parameters("k")}")

      val foldCount = validation.parameters("k").toInt

      // TODO For now only kfold cross-validation
      val crossValidation = KFoldCrossValidation(job, foldCount)

      assert(crossValidation.partition.size == foldCount)

      // For every fold
      val workers = crossValidation.partition.map {
        case (fold, (s, n)) =>
          // Spawn a LocalCoordinatorActor for that one particular fold
          val jobId = UUID.randomUUID().toString
          // TODO To be removed in WP3
          val parameters = adjust(job.parameters, "PARAM_query")(
            (x: String) => x + " EXCEPT ALL (" + x + s" OFFSET $s LIMIT $n)"
          )
          val subJob = JobDto(jobId = jobId,
                              dockerImage = dockerImage(algorithm.code),
                              federationDockerImage = None,
                              jobName = None,
                              inputDb = Some(defaultDb),
                              parameters = parameters,
                              nodes = None)
          val worker = context.actorOf(
            CoordinatorActor
              .props(chronosService, resultDatabase, None, jobResultsFactory)
          )
          //workers(worker) = fold
          worker ! CoordinatorActor.Start(subJob)

          (worker, fold)
      }

      // TODO: this parsing should have been done earlier
      import MetaDataProtocol._
      val targetMetaData: VariableMetaData = job
        .parameters("PARAM_meta")
        .parseJson
        .convertTo[Map[String, VariableMetaData]]
        .get(job.parameters("PARAM_variables").split(",").head) match {
        case Some(v: VariableMetaData) => v
        case None                      => throw new Exception("Problem with variables' meta data!")
      }

      goto(WaitForWorkers) using WaitForWorkersState(job = job,
                                                     replyTo = replyTo,
                                                     validation = crossValidation,
                                                     workers = workers,
                                                     targetMetaData = targetMetaData,
                                                     average = (Nil, Nil),
                                                     results = Map(),
                                                     foldCount = foldCount)
  }

  when(WaitForWorkers) {
    case Event(JsonMessage(pfa: JsValue), data: WaitForWorkersState) =>
      // Validate the results
      log.info("Received result from local method.")
      val model    = pfa.toString()
      val fold     = data.workers(sender)
      val testData = data.validation.getTestSet(fold)._1.map(d => d.compactPrint)

      val sendTo = nextValidationActor
      log.info(s"Send a validation work for fold $fold to pool agent: $sendTo")
      sendTo.fold {
        context.parent ! CrossValidationActor.ErrorResponse(data.job.validation,
                                                            "Validation system not available")
        stop
      } { validationActor =>
        validationActor ! ValidationQuery(fold, model, testData, data.targetMetaData)
        stay
      }

    case Event(ValidationResult(fold, targetMetaData, results), data: WaitForWorkersState) =>
      log.info(s"Received validation results for fold $fold.")
      // Score the results
      val groundTruth = data.validation
        .getTestSet(fold)
        ._2
        .map(x => x.asJsObject.fields.toList.head._2.compactPrint)
      log.info(s"Ground truth: $groundTruth")

      import cats.syntax.list._
      import scala.concurrent.duration._
      import language.postfixOps

      (results.toNel, groundTruth.toNel) match {
        case (Some(r), Some(gt)) =>
          implicit val timeout: util.Timeout = Timeout(5 minutes)
          // TODO: replace ask pattern by 1) sending a ScoringQuery and handling ScoringResult in this state and
          // 2) start a timer to handle timeouts. A bit tricky as we need to keep track of several ScoringQueries at once
          val futureO: Option[Future[_]] =
            nextValidationActor.map(_ ? ScoringQuery(r, gt, data.targetMetaData))

          futureO.fold {
            log.error("Validation system not connected")
            data.replyTo ! CrossValidationActor.ErrorResponse(data.job.validation,
                                                              "Validation system not connected")
            stop
          } { future =>
            log.info("Waiting for scoring results...")
            val scores = Await.result(future, timeout.duration).asInstanceOf[ScoringResult]

            // TODO To be improved with new Spark integration - LC: what was that about?
            // Update the average score
            val updatedAverage = (data.average._1 ::: results, data.average._2 ::: groundTruth)
            val updatedResults = data.results + (fold -> scores)

            // TODO - LC: use updatedAverage in the next step
            // If we have validated all the fold we finish!
            if (updatedResults.size == data.foldCount) {
              log.info("Received the scores for each folds, moving on to final reduce step")
              goto(Reduce) using ReduceData(job = data.job,
                                            replyTo = data.replyTo,
                                            targetMetaData = data.targetMetaData,
                                            average = updatedAverage,
                                            results = updatedResults)
            } else {
              log.info(
                s"Waiting for more scoring results as we have received ${updatedResults.size} scores and there are ${data.foldCount} folds"
              )
              stay using data.copy(average = updatedAverage, results = updatedResults)
            }
          }

        case (Some(_), None) =>
          val message = s"No results on fold $fold"
          log.error(message)
          context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
          stop
        case (None, Some(_)) =>
          val message = s"Empty test set on fold $fold"
          log.error(message)
          context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
          stop
        case (None, None) =>
          val message = s"No data selected during fold $fold"
          log.error(message)
          context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
          stop
      }

    case Event(ValidationError(message), data: WaitForWorkersState) =>
      log.error(message)
      // On testing fold fails, we notify supervisor and we stop
      context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
      stop

    case Event(Error(message), data: WaitForWorkersState) =>
      log.error(message)
      // On training fold fails, we notify supervisor and we stop
      context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
      stop
  }

  when(Reduce) {
    case Event(Done, data: ReduceData) =>
      import cats.syntax.list._
      import scala.concurrent.duration._
      import language.postfixOps

      (data.average._1.toNel, data.average._2.toNel) match {
        case (Some(r), Some(gt)) =>
          implicit val timeout: util.Timeout = Timeout(5 minutes)

          // TODO: replace ask pattern by 1) sending a ScoringQuery and handling ScoringResult in this state and
          // 2) start a timer to handle timeouts
          val futureO: Option[Future[_]] =
            nextValidationActor.map(_ ? ScoringQuery(r, gt, data.targetMetaData))
          futureO.fold(
            data.replyTo ! CrossValidationActor.ErrorResponse(data.job.validation,
                                                              "Validation system not connected")
          ) { future =>
            val scores = Await.result(future, timeout.duration).asInstanceOf[ScoringResult]

            // Aggregation of results from all folds
            val jsonValidation = JsObject(
              "type"    -> JsString("KFoldCrossValidation"),
              "average" -> scores.scores,
              "folds"   -> new JsObject(data.results.mapValues(s => s.scores))
            )

            data.replyTo ! CrossValidationActor.ResultResponse(data.job.validation,
                                                               jsonValidation.compactPrint)
          }
        case _ =>
          val message = s"Final reduce for cross-validation uses empty datasets"
          log.error(message)
          context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
      }
      stop
  }

  onTransition {
    case _ -> Reduce =>
      self ! Done
  }

  initialize()
}
