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

package ch.chuv.lren.woken.config

import com.typesafe.config.Config
import ch.chuv.lren.woken.cromwell.core.ConfigUtil._
import ch.chuv.lren.woken.core.model.{ AlgorithmDefinition, AlgorithmEngine }
import ch.chuv.lren.woken.messages.query.ExecutionPlan

import cats.data.Validated._
import cats.implicits._

// TODO: this should feed AlgorithmLibraryService with metadata

object AlgorithmsConfiguration {

  def read(config: Config, path: List[String]): Validation[AlgorithmDefinition] = {
    val algoConfig = config.validateConfig(path.mkString("."))

    algoConfig.andThen { c: Config =>
      val code: Validation[String] =
        path.lastOption.map(_.validNel[String]).getOrElse("Empty path".invalidNel[String])
      val dockerImage          = c.validateString("dockerImage")
      val predictive           = c.validateBoolean("predictive")
      val variablesCanBeNull   = c.validateBoolean("variablesCanBeNull")
      val covariablesCanBeNull = c.validateBoolean("covariablesCanBeNull")
      val engine: Validation[AlgorithmEngine.Value] =
        c.validateString("engine").orElse("Docker".validNel[String]).map(AlgorithmEngine.withName)
      val distributedExecutionPlan: Validation[ExecutionPlan] =
        c.validateString("distributedExecutionPlan")
          .andThen {
            case "scatter-gather" => ExecutionPlan.scatterGather.validNel[String]
            case "map-reduce"     => ExecutionPlan.mapReduce.validNel[String]
            case "streaming"      => ExecutionPlan.streaming.validNel[String]
            case other            => s"Unknown type of execution plan: $other".invalidNel[ExecutionPlan]
          }
          .orElse(ExecutionPlan.scatterGather.validNel[String])

      (code,
       dockerImage,
       predictive,
       variablesCanBeNull,
       covariablesCanBeNull,
       engine,
       distributedExecutionPlan) mapN AlgorithmDefinition.apply
    }
  }

  def factory(config: Config): String => Validation[AlgorithmDefinition] =
    algorithm => read(config, List("algorithms", algorithm))

}
