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

import argonaut._ , Argonaut._

final case class GBQAccessToken(token: String)
final case class GBQDestinationTable(project: String, dataset: String, table: String)

sealed trait  WriteDisposition
final case class WriteAppend(value: String) extends WriteDisposition
final case class WriteTruncate(value: String) extends WriteDisposition

final case class GBQSchema(typ: String, name: String)

final case class GBQJobConfig(
  sourceFormat: String,
  skipLeadingRows: Int,
  allowQuotedNewLines: String,
  schemaUpdateOptions: List[String],
  schema: List[GBQSchema], 
  timePartition: Option[String],
  writeDisposition: WriteDisposition,
  destinationTable: GBQDestinationTable,
)

object GBQJobConfig {
  implicit val GBQJobConfigDecodeJson: DecodeJson[GBQJobConfig] =
    DecodeJson(c => {
      val load = c --\ "configuration" --\ "load"
      for {
        sourceFormat <- (load --\ ("sourceFormat")).as[String]
        skipLeadingRows <- (load --\ "skipLeadingRows").as[Int]
        allowQuotedNewLines <- (load --\ "allowQuotedNewLines").as[String]
        schemaUpdateOptions <- (load --\ "schemaUpdateOptions").as[List[String]]
        schema <- (load --\ "schema" --\ "fields").as[List[GBQSchema]]
        timePartition <- (load --\ "timePartition").as[Option[String]]
        writeDisposition <- (load --\ "writeDisposition").as(writeDispositionDecodeJson)
        destinationTable <- (load --\ "destinationTable").as[GBQDestinationTable]
      } yield GBQJobConfig(
          sourceFormat,
          skipLeadingRows,
          allowQuotedNewLines,
          schemaUpdateOptions,
          schema,
          timePartition,
          writeDisposition,
          destinationTable)
    })

  implicit val schemaDecodeJson: DecodeJson[GBQSchema] =
    DecodeJson(c => {
      for {
        typ <- (c --\ "type").as[String]
        name <- (c --\ "name").as[String]
      } yield GBQSchema(typ, name)
    })

  val writeDispositionDecodeJson: DecodeJson[WriteDisposition] = 
    DecodeJson {
      c => c.as[String].flatMap {
        case wp @ "WRITE_APPEND" => DecodeResult.ok(WriteAppend(wp))
        case wt @ "WRITE_TRUNCATE" => DecodeResult.ok(WriteAppend(wt))
        case other => DecodeResult.fail("Unrecognized Write Disposition: " + other, c.history)
      }
    }

  implicit val GBQDestinationTableDecodeJson: DecodeJson[GBQDestinationTable] =
    DecodeJson(c => for {
      projectId <- (c --\ "projectId").as[String]
      datasetId <- (c --\ "datasetId").as[String]
      tableId <- (c --\ "tableId").as[String] 
    } yield GBQDestinationTable(projectId, datasetId, tableId))

  //TODO: redact the token value
  implicit val GBQAccessTokenDecodeJson: DecodeJson[GBQAccessToken] = 
    DecodeJson(c => for {
        token <- (c --\ "token").as[String]
    } yield GBQAccessToken(token))

  implicit val GBQAccessTokenEncodeJson: EncodeJson[GBQAccessToken] = 
    EncodeJson(config => Json.obj(
      "value" := config.token.value
    ))
}

/*
Figure out what/where these are/do/come from:

a. TABLE_COLUMNS
  example: this is the table schema, we'll have to translate ours to gbq column types

b. PARTITIONING_INFO
  example: 
    "timePartitioning": { "type": "DAY" }

c. WRITE_DISPOSITION
  example:
    WRITE_APPEND | WRITE_TRUNCATE determine which to use

d. DESTINATION_PROJECT_ID
  example: we get this fom the user

e. DESTINATION_DATASET_ID
  example: what should we name the dataset? do we make new datasets for each table? can we stuff multiple table into the same dataset?

f. TABLE_NAME
  example: use the table name created in the ui? or create our own unique table name, like reform-table-{uuid}

some of the above should come from the user during the destination config in the UI
others will be need to computed/determined on our own and some will need the passed in destination config params to compute

{
  "configuration": {
    "load": {
      "sourceFormat": "CSV",
      "skipLeadingRows": 1,
      "allowQuotedNewlines": true,
      "schemaUpdateOptions": ["ALLOW_FIELD_ADDITION"],
      "schema": {
        "fields": [
                    { "type": "STRING",
                      "name": "dateTime"
                    },
                    { "type": "STRING",
                      "name": "to"
                    }
                  ]
      },
      $PARTITIONING_INFO
      "writeDisposition": "$WRITE_DISPOSITION",
      "destinationTable": {
        "projectId": "$DESTINATION_PROJECT_ID",
        "datasetId": "$DESTINATION_DATASET_ID",
        "tableId": "$TABLE_NAME"
      }
    }
  }
}

 */