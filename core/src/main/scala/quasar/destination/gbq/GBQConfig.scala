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
import argonaut._, Argonaut._

final case class GBQConfig(token: String, project: String)

object GBQConfig {

  //TODO: redact access token
  implicit val GBQConfigCodecJson: CodecJson[GBQConfig] =
    CodecJson(
      (g: GBQConfig) =>
        ("token" := g.token) ->:
        ("project" := g.project) ->:
        jEmptyObject,
      c => for {
        token <- (c --\ "token").as[String]
        project <- (c --\ "project").as[String]
      } yield GBQConfig(token, project))
}