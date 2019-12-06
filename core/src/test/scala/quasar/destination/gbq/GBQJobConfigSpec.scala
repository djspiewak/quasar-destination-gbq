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

import argonaut.{Argonaut, DecodeJson, Json}, Argonaut._
import org.specs2.mutable.Specification

object GBQJobConfigSpec extends Specification {
  val decode = DecodeJson.of[GBQJobConfig].decodeJson(_)

  "decode google bigquery job config" >> {
    val gbqSampleConfig = Json.obj(
      "configuration" := Json.obj(
        "load" := Json.obj(
          "sourceFormat" := jString("CSV"),
          "skipLeadingRows" := jString("1"),
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

    decode(gbqSampleConfig).toOption must beSome(
      GBQJobConfig(
        "CSV",
        "1",
        "true",
        List[String]("ALLOW_FIELD_ADDITION"),
        List[GBQSchema](GBQSchema("STRING", "Manager"), GBQSchema("INT", "Id")),
        Some("DAY"),
        WriteAppend("WRITE_APPEND"),
        GBQDestinationTable("myproject", "mydataset", "mytable")))
  }
}