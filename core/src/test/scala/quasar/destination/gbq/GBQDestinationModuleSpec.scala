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

import quasar.EffectfulQSpec
//import quasar.connector.ResourceError
//import quasar.connector.MonadResourceErr
import quasar.connector.ResourceError
import quasar.contrib.scalaz.MonadError_
import quasar.api.destination.DestinationError

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.AccessToken

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global

import java.io.File

import argonaut._, Argonaut._
import cats.effect.{IO, Resource, Timer}
import scala.concurrent.ExecutionContext.Implicits.global
import scalaz.NonEmptyList

object GBQDestinationModuleSpec extends EffectfulQSpec[IO] {

  //The start of wrapping the below access token stuff in IO
  def inputStream(f: File): Resource[IO, FileInputStream] = 
    Resource.fromAutoCloseable(IO(new FileInputStream(f)))

  //TODO: wrap this in IO and determine if we even want to use this type of auth
  val iStream = new FileInputStream("/Users/nicandroflores/Documents/github/onprem/gcp-marketplace/travis-ci-reform-test-proj-b4f9fc4bf97e.json")
  val credentials = GoogleCredentials.fromStream(iStream).createScoped("https://www.googleapis.com/auth/cloud-platform")
  val accessToken = credentials.refreshAccessToken()

  //Errors when checking for project existance with token
  val malformedConfig = DestinationError.malformedConfiguration((GBQDestinationModule.destinationType, jString("Reason: Not Foun"), "Response Code: 404"))
  val accessDeniedError = DestinationError.accessDenied((GBQDestinationModule.destinationType, jString("token: REDACTED"), "Access denied"))
  def invalidConfig(project: String) = DestinationError.invalidConfiguration((GBQDestinationModule.destinationType, jString("project: " + project), NonEmptyList("Project does not exist")))

  val goodProject = "travis-ci-reform-test-proj"
  val badProject = "nonExistingGcpProject"
  val badToken = "bogustoken"
  val goodToken = accessToken.getTokenValue()

  "fail gbq destination with bad token and bad project" >>* {
    val destination = Resource.suspend(configWith(badToken, badProject).map(GBQDestinationModule.destination[IO](_)))
    destination.use(dst => IO(dst must beLeft(accessDeniedError)))
  }

  "fail gbq destination with bad token and good project" >>* {
    val destination = Resource.suspend(configWith(badToken, goodProject).map(GBQDestinationModule.destination[IO](_)))
    destination.use(dst => IO(dst must beLeft(accessDeniedError)))
  }

  "fail gbq destination with good token and bad project" >>* {
    val destination = Resource.suspend(configWith(goodToken, badProject).map(GBQDestinationModule.destination[IO](_)))
    destination.use(dst => IO(dst must beLeft(invalidConfig(badProject))))
  }

  "fail gbq destination with good token and good project" >>* {
    val destination = Resource.suspend(configWith(goodToken, goodProject).map(GBQDestinationModule.destination[IO](_)))
    destination.use(dst => IO(dst must beRight))
  }

  def configWith(token: String, project: String): IO[Json] =
    IO(Json.obj(
        "project" := project,
        "token" := token))

  implicit val ioMonadResourceErr: MonadError_[IO, ResourceError] =
    MonadError_.facet[IO](ResourceError.throwableP)

  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
}