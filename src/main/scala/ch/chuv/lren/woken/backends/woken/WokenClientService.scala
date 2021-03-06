/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.backends.woken

import java.time.OffsetDateTime

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ ActorMaterializer, FlowShape }
import akka.stream.scaladsl._
import ch.chuv.lren.woken.backends.{ AkkaClusterClient, HttpClient, WebSocketClient }
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.core.streams.debugElements
import ch.chuv.lren.woken.messages.remoting.RemoteLocation
import ch.chuv.lren.woken.messages.variables.{
  VariablesForDatasetsQuery,
  VariablesForDatasetsResponse
}

import scala.concurrent.{ ExecutionContext, Future }
import com.typesafe.scalalogging.LazyLogging
import cats.implicits._
import spray.json._
import queryProtocol._
import HttpClient._

case class WokenClientService(node: String)(implicit val system: ActorSystem,
                                            implicit val materializer: ActorMaterializer)
    extends DefaultJsonProtocol
    with SprayJsonSupport
    with LazyLogging {

  implicit private val ec: ExecutionContext = system.dispatcher

  // TODO: keep outgoing connections flows
  // val connectionFlow: Flow[HttpRequest, HttpResponse, _] = Http().outgoingConnection("host.com")
  // def pathToRequest(path : String) = HttpRequest(uri=Uri.Empty.withPath(Path(path)))
  // val reqFlow = Flow[String] map pathToRequest
  // see https://stackoverflow.com/questions/37659421/what-is-the-best-way-to-combine-akka-http-flow-in-a-scala-stream-flow?rq=1

  def queryFlow: Flow[(RemoteLocation, Query), (RemoteLocation, QueryResult), NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._

        def partitionByRemoteLocationType(locationAndQuery: (RemoteLocation, Query)): Int =
          locationAndQuery._1.url.scheme match {
            case "http" | "https" => 0
            case "ws" | "wss"     => 1
            case "akka"           => 2
            case _                => 1
          }

        val partition =
          builder.add(
            Partition[(RemoteLocation, Query)](outputPorts = 3,
                                               partitioner = partitionByRemoteLocationType)
          )
        val merger = builder.add(Merge[(RemoteLocation, QueryResult)](inputPorts = 3))

        partition.out(0).via(httpQueryFlow) ~> merger
        partition.out(1).via(wsQueryFlow) ~> merger
        partition.out(2).via(actorQueryFlow) ~> merger

        FlowShape(partition.in, merger.out)
      })
      .named("query-remote-woken")

  def httpQueryFlow: Flow[(RemoteLocation, Query), (RemoteLocation, QueryResult), NotUsed] =
    Flow[(RemoteLocation, Query)]
      .named("http-query-remote-woken")
      .map {
        case (location, query: MiningQuery) =>
          logger.info(s"Send Post request to ${location.url} for query $query")
          Post(location, query).map((location, query, _))
        case (location, query: ExperimentQuery) =>
          logger.info(s"Send Post request to ${location.url} for query $query")
          Post(location, query).map((location, query, _))
      }
      .mapAsync(parallelism = 10)(identity)
      .mapAsync(parallelism = 10) {
        case (url, query, response) if response.status.isSuccess() =>
          (url.pure[Future],
           Unmarshal(response)
             .to[QueryResult])
            .mapN((_, _))
            .map(rq => (rq._1, rq._2.copy(query = query)))
        case (url, query, failure) =>
          failure.discardEntityBytes()
          (url,
           QueryResult("",
                       node,
                       Set(),
                       Nil,
                       OffsetDateTime.now(),
                       Shapes.error,
                       Some("dispatch"),
                       None,
                       Some(failure.entity.toString),
                       query)).pure[Future]
      }
      .map(identity)

  def wsQueryFlow: Flow[(RemoteLocation, Query), (RemoteLocation, QueryResult), NotUsed] =
    Flow[(RemoteLocation, Query)]
      .named("ws-query-remote-woken")
      .mapAsync(parallelism = 100) {
        case (location, query: MiningQuery) =>
          logger.info(s"Send Post request to ${location.url}")
          WebSocketClient.sendReceive(location, query)
        case (location, query: ExperimentQuery) =>
          logger.info(s"Send Post request to ${location.url}")
          WebSocketClient.sendReceive(location, query)
      }

  def actorQueryFlow: Flow[(RemoteLocation, Query), (RemoteLocation, QueryResult), NotUsed] =
    Flow[(RemoteLocation, Query)]
      .named("actor-query-remote-woken")
      .mapAsync(100) {
        case (location, query) => AkkaClusterClient.sendReceive(location, query).map((location, _))
      }

  def variableMetaFlow
    : Flow[(RemoteLocation, VariablesForDatasetsQuery),
           (RemoteLocation, VariablesForDatasetsQuery, VariablesForDatasetsResponse),
           NotUsed] =
    Flow[(RemoteLocation, VariablesForDatasetsQuery)]
      .named("remote-variable-meta")
      .map(query => sendReceive(Get(query._1)).map((query._1, query._2, _)))
      .mapAsync(parallelism = 10)(identity)
      .mapAsync[(RemoteLocation, VariablesForDatasetsQuery, VariablesForDatasetsResponse)](
        parallelism = 10
      ) {
        case (url, query, response) if response.status.isSuccess() =>
          val varResponse = Unmarshal(response).to[VariablesForDatasetsResponse]
          (url.pure[Future], query.pure[Future], varResponse).mapN((_, _, _))
        case (url, query, failure) =>
          failure.discardEntityBytes()
          logger.error(s"url: $url failure: $failure")
          (url, query, VariablesForDatasetsResponse(Set.empty, None)).pure[Future]
      }
      .map(identity)
      .log("Variables for dataset response")
      .withAttributes(debugElements)

}
