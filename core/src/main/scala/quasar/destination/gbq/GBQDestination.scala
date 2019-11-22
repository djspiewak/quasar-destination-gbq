/*
 * Copyright 2014–2019 SlamData Inc.
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

package quasar.destination.gbq

import slamdata.Predef._

import quasar.connector.MonadResourceErr
import quasar.api.destination.{Destination}
import quasar.api.destination.DestinationError.InitializationError
import quasar.api.destination.DestinationType
import quasar.api.destination.ResultSink
import quasar.api.push.RenderConfig

import cats.effect.{Concurrent, ContextShift, Resource}
import cats.implicits._
import eu.timepit.refined.auto._
import org.slf4s.Logging
import scalaz.NonEmptyList


final class GBQDestination[F[_]: Concurrent: ContextShift: MonadResourceErr](project: String) extends Destination[F] with Logging {
    def destinationType: DestinationType = DestinationType("gbq", 1L)
  
    def sinks: NonEmptyList[ResultSink[F]] = NonEmptyList(gbqSink)
    
    private[this] val gbqSink: ResultSink[F] = ResultSink.csv[F](RenderConfig.Csv()) { (path, columns, bytes) => 
      // columns map {
      //   case ColumnThingy(name, tpe) =>
      //     val convertedType = tpe match {
      //       case ColumnType.String => "STRING"
      //       case ColumnType.Number => "Float64"
      //     }

      //     s"$name $convertedType"
      // }
      ???
    }
}

object GBQDestination {
    def apply[F[_]: Concurrent: ContextShift: MonadResourceErr, C](config: String)
    : Resource[F, Either[InitializationError[C], Destination[F]]] = {
      val x: Either[InitializationError[C], Destination[F]] = new GBQDestination[F](config).asRight[InitializationError[C]]
      Resource.liftF(x.pure[F])
    }
}