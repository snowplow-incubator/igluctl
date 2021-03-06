/*
 * Copyright (c) 2012-2021 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.iglu.ctl
package commands

import java.nio.file.Path
import java.util.UUID

import cats.data.EitherT
import cats.effect.IO
import cats.implicits._

import fs2.Stream

import scalaj.http.{HttpRequest, HttpResponse}

import io.circe.Decoder
import io.circe.jawn.parse
import io.circe.generic.semiauto._

import com.snowplowanalytics.iglu.ctl.File.{filterJsonSchemas, streamFiles}
import com.snowplowanalytics.iglu.ctl.{ Result => IgluctlResult }

/**
 * Companion objects, containing functions not closed on `masterApiKey`, `registryRoot`, etc
 */
object Push {

  /**
    * Primary function, performing IO reading, processing and printing results
    * @param inputDir path to schemas to upload
    * @param registryRoot Iglu Server endpoint (without `/api`)
    * @param apiKey API key with write permissions (master key if `legacy` is true)
    * @param isPublic whether schemas should be publicly available
    * @param legacy whether it should be compatible with pre-0.6.0 Server,
    *               which required to create temporary keys first
    */
  def process(inputDir: Path,
              registryRoot: Server.HttpUrl,
              apiKey: UUID,
              isPublic: Boolean,
              legacy: Boolean): IgluctlResult = {
    val stream = for {
      key    <- if (legacy) Stream.resource(Server.temporaryKeys(registryRoot, apiKey)).map(_.write) else Stream.emit(apiKey)
      file   <- streamFiles(inputDir, Some(filterJsonSchemas)).translate[IO, Failing](Common.liftIO).map(_.flatMap(_.asJsonSchema))
      result <- file match {
        case Right(schema) =>
          val request = Server.buildPushRequest(registryRoot, isPublic, schema.content, key)
          Stream.eval[Failing, Result](postSchema(request))
        case Left(error) =>
          Stream.eval(EitherT.leftT[IO, Result](error))
      }
      _      <- Stream.eval[Failing, Unit](EitherT.liftF(IO(println(result.asString))))
    } yield ()

    EitherT(stream.compile.drain.value.map(_.toEitherNel.as(Nil)))
  }

  /**
    * End-of-the-world data containing all results of uploading and
    * app closing logic
    */
  case class Total(updates: Int, creates: Int, failures: Int, unknown: Int) {
    /**
      * Print summary information and exit with 0 or 1 status depending on
      * presence of errors during processing
      */
    def exit(): Unit = {
      println(s"TOTAL: ${creates + updates} Schemas successfully uploaded ($creates created; $updates updated)")
      println(s"TOTAL: $failures failed Schema uploads")
      if (unknown > 0) println(s"WARNING: $unknown unknown statuses")

      if (unknown > 0 || failures > 0) sys.exit(1)
      else ()
    }

    /**
      * Modify end-of-the-world object, by sinking reports and printing info
      * Performs IO
      *
      * @param result result of upload
      * @return new modified total object
      */
    def add(result: Result): Total = result match {
      case s @ Result(_, Status.Updated) =>
        println(s"SUCCESS: ${s.asString}")
        copy(updates = updates + 1)
      case s @ Result(_, Status.Created) =>
        println(s"SUCCESS: ${s.asString}")
        copy(creates = creates + 1)
      case s @ Result(_, Status.Failed) =>
        println(s"FAILURE: ${s.asString}")
        copy(failures = failures + 1)
      case s @ Result(_, Status.Unknown) =>
        println(s"FAILURE: ${s.asString}")
        copy(unknown = unknown + 1)
    }
  }

  object Total {
    val empty = Total(0,0,0,0)
  }

  /**
   * Common server message extracted from HTTP JSON response
   *
   * @param status HTTP status code
   * @param message human-readable message
   * @param location optional URI available for successful upload
   */
  case class ServerMessage(status: Option[Int], message: String, location: Option[String])
  object ServerMessage {
    def asString(status: Option[Int], message: String, location: Option[String]): String =
      s"$message ${location.map("at " + _ + " ").getOrElse("")} ${status.map(x => s"($x)").getOrElse("")}"

    implicit val serverMessageCirceDecoder: Decoder[ServerMessage] =
      deriveDecoder[ServerMessage]
  }

  /**
   * ADT representing all possible statuses for Schema upload
   */
  sealed trait Status extends Serializable
  object Status {
    case object Updated extends Status
    case object Created extends Status
    case object Unknown extends Status
    case object Failed extends Status
  }

  /**
   * Final result of uploading schema, with server response or error message
   *
   * @param serverMessage message, represented as valid [[ServerMessage]]
   *                      extracted from response or plain string if response
   *                      was unexpected
   * @param status short description of outcome
   */
  case class Result(serverMessage: Either[String, ServerMessage], status: Status) {
    def asString: String =
      serverMessage match {
        case Right(message) => ServerMessage.asString(message.status, message.message, message.location)
        case Left(responseBody) => responseBody
      }
  }


  /**
   * Transform failing [[Result]] to plain [[Result]] by inserting exception
   * message instead of server message
   *
   * @param result disjucntion of string with result
   * @return plain result
   */
  def flattenResult(result: Either[String, Result]): Result =
    result match {
      case Right(status) => status
      case Left(failure) => Result(Left(failure), Status.Failed)
    }

  /**
   * Extract stringified message from server response through [[ServerMessage]]
   *
   * @param response HTTP response from Iglu registry, presumably containing JSON
   * @return success message processed from JSON or error message if upload
   *         wasn't successful
   */
  def getUploadStatus(response: HttpResponse[String]): Result = {
    if (response.isSuccess) {
      val result = for {
        json <- parse(response.body)
        message <- json.as[ServerMessage]
      } yield message

      result match {
        case Right(serverMessage) if serverMessage.message.contains("updated") =>
          Result(Right(serverMessage), Status.Updated)
        case Right(serverMessage) =>
          Result(Right(serverMessage), Status.Created)
        case Left(_) =>
          Result(Left(response.body), Status.Unknown)
      }
    } else Result(Left(response.body), Status.Failed)
  }

  /**
   * Perform HTTP request bundled with temporary write key and valid
   * self-describing JSON Schema to /api/schemas/SCHEMAPATH to publish new
   * Schema.
   * Performs IO
   *
   * @param request HTTP POST-request with JSON Schema
   * @return successful parsed message or error message
   */
  def postSchema(request: HttpRequest): Failing[Result] =
    EitherT.liftF(IO(request.asString).map(getUploadStatus))
}
