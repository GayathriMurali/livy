/*
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudera.livy.server

import scala.concurrent.{ExecutionContext, Future}

import com.fasterxml.jackson.core.JsonParseException
import org.json4s.JsonDSL._
import org.json4s.{DefaultFormats, Formats, JValue, MappingException}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport

import com.cloudera.livy.Logging
import com.cloudera.livy.sessions.{SessionManager, Session}
import com.cloudera.livy.sessions.interactive.InteractiveSession.SessionFailedToStart
import com.cloudera.livy.spark.ConfigOptionNotAllowed

object SessionServlet extends Logging

abstract class SessionServlet[S <: Session](sessionManager: SessionManager[S])
  extends ScalatraServlet
  with FutureSupport
  with MethodOverride
  with JacksonJsonSupport
  with UrlGeneratorSupport
{
  override protected implicit def executor: ExecutionContext = ExecutionContext.global

  override protected implicit lazy val jsonFormats: Formats = DefaultFormats

  protected def serializeSession(session: S): JValue

  before() {
    contentType = formats("json")
  }

  get("/") {
    val from = params.get("from").map(_.toInt).getOrElse(0)
    val size = params.get("size").map(_.toInt).getOrElse(100)

    val sessions = sessionManager.all()

    Map(
      "from" -> from,
      "total" -> sessionManager.size(),
      "sessions" -> sessions.view(from, from + size).map(serializeSession)
    )
  }

  val getSession = get("/:id") {
    val id = params("id").toInt

    sessionManager.get(id) match {
      case None => NotFound("session not found")
      case Some(session) => serializeSession(session)
    }
  }

  get("/:id/state") {
    val id = params("id").toInt

    sessionManager.get(id) match {
      case None => NotFound("batch not found")
      case Some(batch) =>
        ("id", batch.id) ~ ("state", batch.state.toString)
    }
  }

  get("/:id/log") {
    val id = params("id").toInt

    sessionManager.get(id) match {
      case None => NotFound("session not found")
      case Some(session) =>
        val from = params.get("from").map(_.toInt)
        val size = params.get("size").map(_.toInt)
        val (from_, total, logLines) = serializeLogs(session, from, size)

        ("id", session.id) ~
          ("from", from_) ~
          ("total", total) ~
          ("log", logLines)
    }
  }

  delete("/:id") {
    val id = params("id").toInt

    sessionManager.delete(id) match {
      case None => NotFound("session not found")
      case Some(future) => new AsyncResult {
        val is = future.map { case () => Ok(Map("msg" -> "deleted")) }
      }
    }
  }

  post("/") {
    new AsyncResult {
      val is = Future {
        val session = sessionManager.create(parsedBody)
        Created(serializeSession(session),
          headers = Map("Location" -> url(getSession, "id" -> session.id.toString))
        )
      }
    }
  }

  error {
    case e: JsonParseException => BadRequest(e.getMessage)
    case e: MappingException => BadRequest(e.getMessage)
    case e: ConfigOptionNotAllowed => BadRequest(e.getMessage)
    case e: SessionFailedToStart => InternalServerError(e.getMessage)
    case e: dispatch.StatusCode => ActionResult(ResponseStatus(e.code), e.getMessage, Map.empty)
    case e: IllegalArgumentException => BadRequest(e.getMessage)
    case e =>
      SessionServlet.error("internal error", e)
      InternalServerError(e.toString)
  }

  protected val sessionIdParam: String = "id"

  protected def doAsync(fn: => Any): AsyncResult = {
    new AsyncResult {
      val is = Future { fn }
    }
  }

  protected def withSession(fn: (S => Any)): Any = {
    val sessionId = params(sessionIdParam).toInt
    sessionManager.get(sessionId) match {
      case Some(session) => fn(session)
      case None => NotFound(s"Session '$sessionId' not found.")
    }
  }

  private def serializeLogs(session: S, fromOpt: Option[Int], sizeOpt: Option[Int]) = {
    val lines = session.logLines()

    val size = sizeOpt.getOrElse(100)
    var from = fromOpt.getOrElse(-1)
    if (from < 0) {
      from = math.max(0, lines.length - size)
    }
    val until = from + size

    (from, lines.length, lines.view(from, until))
  }
}
