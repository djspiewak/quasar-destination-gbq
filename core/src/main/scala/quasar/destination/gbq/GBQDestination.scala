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

import quasar.connector.{MonadResourceErr, ResourceError}
import quasar.api.destination.{Destination, DestinationError}
import quasar.api.destination.DestinationError.InitializationError
import quasar.api.destination.DestinationType
import quasar.api.destination.ResultSink
import quasar.api.push.RenderConfig
import quasar.api.table.ColumnType
import quasar.api.resource.ResourceName

import argonaut._, Argonaut._

import fs2.Stream
import org.http4s.client.Client
import org.http4s.AuthScheme
import org.http4s.headers.Authorization
import org.http4s.Credentials
import org.http4s.Request
import org.http4s.Method
import org.http4s.Uri
import org.http4s.headers.`Content-Type`
import org.http4s.{MediaType => MT}
import org.http4s.EntityEncoder
import org.http4s.Status
import org.http4s.headers.Location
import org.http4s.argonaut.jsonEncoderOf

import cats.effect.{Concurrent, ContextShift, Resource, Sync}
import cats.implicits._

import eu.timepit.refined.auto._
import org.slf4s.Logging
import scalaz.NonEmptyList
import quasar.api.table.ColumnType
import quasar.api.table.TableColumn

 import shims._

final class GBQDestination[F[_]: Concurrent: ContextShift: MonadResourceErr](
    client: Client[F], 
    config: GBQConfig) extends Destination[F] with Logging {

  def destinationType: DestinationType = DestinationType("gbq", 1L)

  def sinks: NonEmptyList[ResultSink[F]] = NonEmptyList(gbqSink)

  private def gbqSink: ResultSink[F] = 
    ResultSink.csv[F](RenderConfig.Csv()) { (path, columns, bytes) =>
      val gbqSchema = tblColumnToGBQSchema(columns)
      
      val tableNameF = path.uncons match {
        case Some((ResourceName(name), _)) => name.pure[F]
        case _ => MonadResourceErr[F].raiseError[String](
          ResourceError.MalformedResource(
            path,
            "path must contain exactly one component",
            None,
            None))
      }

      for {
        tableName <- Stream.eval[F, String](tableNameF)

        gbqJobConfig = formGBQJobConfig(gbqSchema, config.project, config.datasetId, tableName)
        _ = println("job config: " + gbqJobConfig.asJson)
        eitherloc <- Stream.eval(mkGbqJob(client, config.token, config.project, gbqJobConfig))
        _ = println("Location URL: " + eitherloc)

        _ <- Stream.eval(
          Sync[F].delay(
            log.info(s"(re)creating ${config.project}.${config.datasetId}.${tableName} with schema ${columns.show}")))

        // now we have everything to upload bytes
        _ <- eitherloc match {
          case Right(u) => {
            println("about to upload bytes")
            upload(client, bytes, u, config.token)
          }
          case _ => ??? //TODO: no Location uri to upload to
        }
      } yield ()
  }

  private def formGBQJobConfig(
      schema: List[GBQSchema],
      projectId: String,
      datasetId: String,
      tableId: String): GBQJobConfig =
    GBQJobConfig(
      "CSV",
      "1",
      "true",
      List[String]("ALLOW_FIELD_ADDITION"),
      schema,
      Some("DAY"),
      WriteAppend("WRITE_APPEND"),
      GBQDestinationTable(projectId, datasetId, tableId))

    //TODO: change return type to include error if we hit offsetdate, interval, and null
    // see how we do this in ThoughtSpot
  private def tblColumnToGBQSchema(cols: List[TableColumn]): List[GBQSchema] =
    cols map { col => col match {
      case TableColumn(name, tpe) => tpe match {
        case ColumnType.String => GBQSchema("STRING", name)
        case ColumnType.Boolean => GBQSchema("BOOL", name)
        case ColumnType.Number => GBQSchema("NUMERIC", name)
        case ColumnType.LocalDate => GBQSchema("DATE", name)
        case ColumnType.LocalDateTime => GBQSchema("DATETIME", name)
        case ColumnType.LocalTime => GBQSchema("TIME", name)
        case ColumnType.OffsetDate => ???
        case ColumnType.OffsetDateTime => GBQSchema("TIMESTAMP", name)
        case ColumnType.OffsetTime => GBQSchema("TIMESTAMP", name)
        case ColumnType.Interval => ???
        case ColumnType.Null => ???
      }
    }
  }

  private def mkGbqJob(
      client: Client[F],
      token: String,
      project: String,
      jCfg: GBQJobConfig): F[Either[InitializationError[Json], Uri]] = {

    implicit def jobConfigEntityEncoder: EntityEncoder[F, GBQJobConfig] = jsonEncoderOf[F, GBQJobConfig]

    val authToken = Authorization(Credentials.Token(AuthScheme.Bearer, token))

    println("job config: " + jCfg)
    println("job config json: " + jCfg.asJson)

    val jobReq = Request[F](
      method = Method.POST,
      uri = Uri.fromString(s"https://bigquery.googleapis.com/upload/bigquery/v2/projects/${project}/jobs?uploadType=resumable").getOrElse(Uri()))
        .withHeaders(authToken)
        .withContentType(`Content-Type`(MT.application.json))
        .withEntity(jCfg)

    println("Job Request: " + jobReq)
    
    client.fetch(jobReq) { resp =>
      resp.status match {
        case Status.Ok => resp.headers match {
          case Location(loc) => {
            println("Location URI: " + loc.uri)
            loc.uri.asRight.pure[F]
          }
        }
        case _ => {
          println("resp: " + resp)
          DestinationError.malformedConfiguration((destinationType, jString("Reason: " + resp.status.reason), "Response Code: " + resp.status.code)).asLeft.pure[F]
        }
      }
    }
  }

    private def upload(client: Client[F], bytes: Stream[F, Byte], uploadLocation: Uri, token: String): Stream[F, Unit] = {
      val authToken = Authorization(Credentials.Token(AuthScheme.Bearer, token))
      val destReq = Request[F](method = Method.POST, uri = uploadLocation)
          .withHeaders(authToken)
          .withContentType(`Content-Type`(MT.application.`octet-stream`))
          .withEntity(bytes)

      val x = client.fetch(destReq) {resp =>
        resp.status match {
          case Status.Ok => {
            println("data uploaded: " + resp)
            ().pure[F] //TODO: fix this?
          }
          case _ => {
            println("data uploaded: " + resp)
            ().pure[F] //TODO: fix this, error out correctly
          }
        }
      }

      Stream.eval(x)



    }
}

object GBQDestination {
    def apply[F[_]: Concurrent: ContextShift: MonadResourceErr, C](client: Client[F], config: GBQConfig)
        : Resource[F, Either[InitializationError[C], Destination[F]]] = {
      val x: Either[InitializationError[C], Destination[F]] = new GBQDestination[F](client, config).asRight[InitializationError[C]]
      Resource.liftF(x.pure[F])
    }
}