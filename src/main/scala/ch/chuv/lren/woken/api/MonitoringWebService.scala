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

package ch.chuv.lren.woken.api

import akka.cluster.{ Cluster, MemberStatus }
import akka.http.scaladsl.model.{ StatusCodes, Uri }
import akka.http.scaladsl.server.{ Directives, Route }
import akka.http.scaladsl.model.StatusCodes._
import akka.management.cluster.{ ClusterHealthCheck, ClusterHttpManagementRoutes }
import akka.management.http.ManagementRouteProviderSettings
import akka.util.Helpers
import cats.implicits._
import cats.effect.Effect
import ch.chuv.lren.woken.config.{ AppConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.service.{ BackendServices, DatabaseServices }
import ch.chuv.lren.woken.core.fp.runNow
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import sup.data.Report._
import sup.data.{ HealthReporter, Tagged }

import scala.language.higherKinds
import scala.collection.JavaConverters._

/**
  *  Monitoring API
  *
  *  /readiness : readiness check of the application
  *  /health : application health
  *  /health/backend : backend health
  *  /health/db : database health
  *  /health/cluster : cluster health
  *  /cluster/alive : ping works while the cluster seems alive
  *  /cluster/ready : readiness check of the cluster
  *  /cluster/members : list members of the cluster
  */
@SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.Throw"))
class MonitoringWebService[F[_]: Effect](cluster: Cluster,
                                         config: Config,
                                         appConfig: AppConfiguration,
                                         jobsConfig: JobsConfiguration,
                                         databaseServices: DatabaseServices[F],
                                         backendServices: BackendServices[F])
    extends Directives
    with LazyLogging {

  private val allChecks =
    HealthReporter.fromChecks(databaseServices.healthChecks, backendServices.healthChecks)

  val healthRoute: Route = pathPrefix("health") {
    pathEndOrSingleSlash {
      get {
        val up = runNow(allChecks.check).value.health.isHealthy
        (cluster.state.leader.nonEmpty,
         !appConfig.disableWorkers,
         cluster.state.members.size < 2,
         up) match {
          case (true, true, true, true) =>
            complete("UP - Expected at least one worker (Woken validation server) in the cluster")
          case (true, _, _, true) => complete("UP")
          case (false, _, _, _) =>
            val msg = "No leader elected for the cluster"
            logger.warn(msg)
            complete((StatusCodes.InternalServerError, msg))
          case (_, _, _, false) =>
            val report = runNow(allChecks.check.map(_.value))
            val msg =
              s"${report.health}: \n${report.checks.toList.filter(!_.health.isHealthy).mkString("\n")}"
            logger.warn(msg)
            complete((StatusCodes.InternalServerError, msg))
        }
      }
    } ~ dbHealth ~ backendHealth ~ clusterHealth
  }

  val readinessRoute: Route = pathPrefix("readiness") {
    get {
      if (cluster.state.leader.isEmpty)
        complete((StatusCodes.InternalServerError, "No leader elected for the cluster"))
      else
        complete("READY")
    }
  }

  val clusterManagementRoutes: Route = ClusterHttpManagementRoutes(cluster)

  val clusterHealthRoutes: Route = pathPrefix("cluster") {
    new ClusterHealthCheck(cluster.system).routes(new ManagementRouteProviderSettings {
      override def selfBaseUri: Uri = Uri./
    })
  }

  private val healthcheckConfig = config.getConfig("akka.management.cluster.http.healthcheck")
  private val readyStates: Set[MemberStatus] =
    healthcheckConfig.getStringList("ready-states").asScala.map(memberStatus).toSet

  private def clusterHealth: Route = pathPrefix("cluster") {
    get {
      val selfState = cluster.selfMember.status
      if (readyStates.contains(selfState)) complete(StatusCodes.OK)
      else complete(StatusCodes.InternalServerError)
    }
  }

  private def backendHealth: Route = pathPrefix("backend") {
    get {
      if (runNow(backendServices.healthChecks.check).value.health.isHealthy) {
        complete(OK)
      } else {
        complete(
          (StatusCodes.InternalServerError,
           runNow(backendServices.healthChecks.check.map(_.value.show)))
        )
      }
    }
  }

  private def dbHealth: Route = pathPrefix("db") {
    get {
      if (runNow(databaseServices.healthChecks.check).value.health.isHealthy) {
        complete(OK)
      } else {
        complete(
          (StatusCodes.InternalServerError,
           runNow(databaseServices.healthChecks.check.map(_.value.show)))
        )
      }
    }
  }

  val routes: Route = healthRoute ~ readinessRoute ~ clusterManagementRoutes ~ clusterHealthRoutes

  type TaggedS[H] = Tagged[String, H]

  private def memberStatus(status: String): MemberStatus =
    Helpers.toRootLowerCase(status) match {
      case "weaklyup" => MemberStatus.WeaklyUp
      case "up"       => MemberStatus.Up
      case "exiting"  => MemberStatus.Exiting
      case "down"     => MemberStatus.Down
      case "joining"  => MemberStatus.Joining
      case "leaving"  => MemberStatus.Leaving
      case "removed"  => MemberStatus.Removed
      case invalid =>
        throw new IllegalArgumentException(
          s"'$invalid' is not a valid MemberStatus. See reference.conf for valid values"
        )
    }

}
