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

import quasar.api.destination.{Destination, DestinationType, DestinationError}
import quasar.api.destination.DestinationError.InitializationError
import quasar.connector.{DestinationModule, MonadResourceErr}

import argonaut.{Argonaut, DecodeJson, Json}, Argonaut._
import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import cats.data.EitherT
import cats.implicits._
import eu.timepit.refined.auto._
import scala.util.Either

final case class GBQConfig(project: String)
object GBQConfig {
  implicit val GBQConfigDecodeJson: DecodeJson[GBQConfig] =
    DecodeJson(c => for {
      project <- c.downField("project").as[String]
    } yield GBQConfig(project))
}

object GBQDestinationModule extends DestinationModule {
  def destinationType = DestinationType("gbq", 1L)

  def sanitizeDestinationConfig(config: Json): Json = ???

  def destination[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer](
    config: Json): Resource[F,Either[InitializationError[Json],Destination[F]]] = {

    val cfg: Either[InitializationError[Json], GBQConfig] = 
      config.as[GBQConfig].fold(
        (err, c) => 
          Left(DestinationError.malformedConfiguration[Json, InitializationError[Json]](destinationType, jString("stuff"), err)),
        Right(_))

// --- arguments to job conifg ---
// get data schema/column types
// determine partitioning info
// determine the write disposition
// get the projectId
// get/compute the datasetId
// get/compute the tableId/table name

// -- job setup --
// make a request to create job
//
// echo $JOB_CONFIGURATION | curl --fail -i \
//  -H "Authorization: Bearer $ACCESS_TOKEN" \
//  -H "Content-type: application/json" \
//  --data @- -X POST "https://www.googleapis.com/upload/bigquery/v2/projects/${DESTINATION_PROJECT_ID}/jobs?uploadType=resumable")


// extract location from response

// -- push data --
// then stream data to job location url
// stream data -> curl --fail -X PUT --data-binary @- ${JOB_URL%$'\r'}

    val init = for {
      config <- EitherT(cfg.pure[Resource[F, ?]])
    } yield new GBQDestination[F](config.project): Destination[F]

    init.value
  }
}