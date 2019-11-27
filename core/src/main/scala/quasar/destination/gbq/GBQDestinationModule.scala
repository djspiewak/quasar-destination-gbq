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

import argonaut._, Argonaut._

import cats.effect.{Concurrent, ConcurrentEffect, ContextShift, Resource, Timer}
import cats.data.EitherT
import cats.implicits._

import org.asynchttpclient.{AsyncHttpClientConfig, DefaultAsyncHttpClientConfig}

import org.http4s.Uri
import org.http4s.Status
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.headers.Authorization
import org.http4s.Request
import org.http4s.Method
import org.http4s.client.Client
import org.http4s.client.asynchttpclient.AsyncHttpClient
import org.http4s.util.threads.threadFactory

import eu.timepit.refined.auto._
import scala.util.Either
import scalaz.NonEmptyList

object GBQDestinationModule extends DestinationModule {
  //import quasar.destination.gbq.{GBQJobConfig, GBQConfig}

  def destinationType = DestinationType("gbq", 1L)

  def sanitizeDestinationConfig(config: Json): Json = ???

  def jobCfg: Json = {
    // TODO: fill in schema once we have the columns
    // along with projectId, datasetId will be table name, 
    // and tableId will be reform-table-<uuid>
    val jobcfg = Json.obj(
      "configuration" := Json.obj(
        "load" := Json.obj(
          "sourceFormat" := jString("CSV"),
          "skipLeadingRows" := jNumber(1),
          "allowQuotedNewLines" := jString("true"),
          "schemaUpdateOptions" := jString("ALLOW_FIELD_ADDITION") -->>: jEmptyArray,
          "schema" := Json.obj(
            "fields" := Json.array(
              Json.obj(
                "type" := jString("STRING"),
                "name" := jString("Manager")
              ),
              Json.obj(
                "type" := jString("INT"),
                "name" := jString("Id")
              )
            )
          ),
          "timePartition" := jString("DAY"),
          "writeDisposition" := jString("WRITE_APPEND"),
          "destinationTable" := Json.obj(
            "projectId" := jString("myproject"),
            "datasetId" := jString("mydataset"),
            "tableId" := jString("mytable")))))

      jobcfg 
  }

  private def mkConfig[F[_]]: AsyncHttpClientConfig =
    new DefaultAsyncHttpClientConfig.Builder()
      .setMaxConnectionsPerHost(200)
      .setMaxConnections(400)
      .setRequestTimeout(Int.MaxValue)
      .setReadTimeout(Int.MaxValue)
      .setConnectTimeout(Int.MaxValue)
      .setThreadFactory(threadFactory(name = { i =>
        s"http4s-async-http-client-worker-${i}"
      })).build()

  private def mkClient[F[_]: ConcurrentEffect]: Resource[F, Client[F]] = {
    AsyncHttpClient.resource[F](mkConfig)
  }

  //TODO: here we check this url for 200 or 404 responses
  // must pass along auth Bearer token
  // https://www.googleapis.com/bigquery/v2/projects/<project>/datasets
  // 200 if auth token is good and project exists
  // 400 if project doesn't exit
  // 401 invalid auth credentials
  // 404 not found
  private def isLive[F[_]: Concurrent: ContextShift](
    client: Client[F],
    token: String,
    project: String): F[Either[InitializationError[Json], Unit]] = {
      val authToken = Authorization(Credentials.Token(AuthScheme.Bearer, token))
      val request = Request[F](
        method = Method.GET,
        uri = Uri.fromString(s"https://www.googleapis.com/bigquery/v2/projects/${project}/datasets").getOrElse(Uri())
      ).withHeaders(authToken)
      //TODO: should this be wrapped with Concurrent[F]?
      client.fetch(request) { resp =>
        resp.status match {
          case Status.Ok => 
            ().asRight[InitializationError[Json]].pure[F]
          //TODO: fix error messages
          case Status.BadRequest => 
            DestinationError.invalidConfiguration((destinationType, jString("project: " + project), NonEmptyList("Project does not exist"))).asLeft.pure[F]
          case Status.Unauthorized => 
            DestinationError.accessDenied((destinationType, jString("token: REDACTED"), "Access denied")).asLeft.pure[F]
          case status => 
            DestinationError.malformedConfiguration((destinationType, jString("Reason: " + status.reason), "Response Code: " + status.code)).asLeft.pure[F]
        }
      }
    }

  def destination[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer](config: Json): Resource[F,Either[InitializationError[Json],Destination[F]]] = {

    //TODO: fix error message
    val cfg: Either[InitializationError[Json], GBQConfig] = config.as[GBQConfig].fold(
      (err, c) => Left(DestinationError.malformedConfiguration[Json, InitializationError[Json]](destinationType, jString("stuff"), err)),
      Right(_))

    //TODO: fix error message
    val gbqJobConfig: Either[InitializationError[Json], GBQJobConfig] = jobCfg.as[GBQJobConfig].fold(
      (err, c) => Left(DestinationError.malformedConfiguration[Json, InitializationError[Json]](destinationType, jString("stuff"), err)),
      Right(_)
    )

    val init = for {
      cfg <- EitherT(cfg.pure[Resource[F, ?]])
      jobCfg <- EitherT(gbqJobConfig.pure[Resource[F, ?]])
      client <- EitherT(mkClient.map(_.asRight[InitializationError[Json]]))
      _ <- EitherT(Resource.liftF(isLive(client, cfg.token, cfg.project)))
    } yield new GBQDestination[F](client, cfg, jobCfg): Destination[F]

    init.value
  }
}

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
