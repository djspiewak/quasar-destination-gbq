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

import org.asynchttpclient.{AsyncHttpClientConfig, DefaultAsyncHttpClientConfig}

import argonaut._, Argonaut._

import org.http4s.Uri
import org.http4s.Status
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.headers.Authorization
import org.http4s.Request
import org.http4s.Method
import org.http4s.headers.`Content-Type`
import org.http4s.{MediaType => MT}
import org.http4s.client.Client
import org.http4s.client.asynchttpclient.AsyncHttpClient
import org.http4s.util.threads.threadFactory
import org.http4s.EntityEncoder
import org.http4s.argonaut.jsonEncoderOf

import cats.effect.{Concurrent, ConcurrentEffect, ContextShift, Resource, Timer}
import cats.data.EitherT
import cats.implicits._
import eu.timepit.refined.auto._
import scala.util.Either
import scalaz.NonEmptyList

object GBQDestinationModule extends DestinationModule {

  def destinationType = DestinationType("gbq", 1L)

  def sanitizeDestinationConfig(config: Json): Json = ???

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

    client.fetch(request) { resp =>
      resp.status match {
        case Status.Ok =>
          ().asRight[InitializationError[Json]].pure[F]
        //TODO: edit messages in errors
        case Status.BadRequest => 
          DestinationError.invalidConfiguration(
            (destinationType, jString("project: " + project), 
            NonEmptyList("Project does not exist"))).asLeft.pure[F]
        case Status.Unauthorized => 
          DestinationError.accessDenied(
            (destinationType, jString("token: REDACTED"), 
            "Access denied")).asLeft.pure[F]
        case status => 
          DestinationError.malformedConfiguration(
            (destinationType, jString("Reason: " + status.reason), 
            "Response Code: " + status.code)).asLeft.pure[F]
      }
    }
  }

  private def mkDataset[F[_]: Concurrent: ContextShift](
      client: Client[F],
      token: String,
      project: String,
      datasetId: String): F[Either[InitializationError[Json], Unit]] = {
    implicit def jobConfigEntityEncoder: EntityEncoder[F, GBQDatasetConfig] = jsonEncoderOf[F, GBQDatasetConfig]

    val dCfg = GBQDatasetConfig(project, datasetId)
    val authToken = Authorization(Credentials.Token(AuthScheme.Bearer, token))
    val datasetReq = Request[F](
      method = Method.POST,
      uri = Uri.fromString(s"https://bigquery.googleapis.com/bigquery/v2/projects/${project}/datasets").getOrElse(Uri()))
        .withHeaders(authToken)
        .withContentType(`Content-Type`(MT.application.json))
        .withEntity(dCfg)

      client.fetch(datasetReq) { resp =>
        resp.status match {
          case Status.Ok | Status.Conflict => {
            println("status OK | Conflict when creating dataset: " + dCfg )
            ().asRight[InitializationError[Json]].pure[F] //TODO: if we get conflict 409, the dataset already exists?
          }
          case status => {
            println("hitting alternative case when creating dataset: " + dCfg)
            println("resp: " + resp)
            DestinationError.malformedConfiguration(
              (destinationType, jString("Reason: " + status.reason), 
              "Response Code: " + status.code)).asLeft.pure[F]
          }
        }
      }
  }

  def destination[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer](config: Json): Resource[F,Either[InitializationError[Json],Destination[F]]] = {

    //TODO: fix error message 
    val cfg: Either[InitializationError[Json], GBQConfig] = config.as[GBQConfig].fold(
      (err, c) => Left(DestinationError.malformedConfiguration[Json, InitializationError[Json]](destinationType, jString("stuff"), err)),
      Right(_))

    val init = for {
      cfg <- EitherT(cfg.pure[Resource[F, ?]])
      client <- EitherT(mkClient.map(_.asRight[InitializationError[Json]]))
      _ <- EitherT(Resource.liftF(isLive(client, cfg.token, cfg.project)))
      _ <- EitherT(Resource.liftF(mkDataset(client, cfg.token, cfg.project, cfg.datasetId)))
    } yield new GBQDestination[F](client, cfg): Destination[F]

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
