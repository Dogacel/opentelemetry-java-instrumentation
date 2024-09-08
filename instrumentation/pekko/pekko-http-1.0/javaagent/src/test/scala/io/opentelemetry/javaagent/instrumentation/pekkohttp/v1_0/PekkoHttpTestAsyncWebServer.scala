/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.pekkohttp.v1_0

import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint._
import io.opentelemetry.instrumentation.testing.junit.http.{AbstractHttpServerTest, ServerEndpoint}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.model.HttpMethods.{GET, POST}
import org.apache.pekko.http.scaladsl.model.StatusCodes.OK
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.pattern.after
import org.apache.pekko.stream.ActorMaterializer

import java.util.function.Supplier
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

object PekkoHttpTestAsyncWebServer {
  implicit val system: ActorSystem = ActorSystem("my-system")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  // needed for the future flatMap/onComplete in the end
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher
  val asyncHandler: HttpRequest => Future[HttpResponse] = {
    case HttpRequest(GET, uri: Uri, _, _, _) =>
      Future {
        val endpoint = ServerEndpoint.forPath(uri.path.toString())
        AbstractHttpServerTest.controller(
          endpoint,
          new Supplier[HttpResponse] {
            def get(): HttpResponse = {
              val resp = HttpResponse(status =
                endpoint.getStatus
              ) // .withHeaders(headers.Type)resp.contentType = "text/plain"
              endpoint match {
                case SUCCESS => resp.withEntity(endpoint.getBody)
                case INDEXED_CHILD =>
                  INDEXED_CHILD.collectSpanAttributes(new UrlParameterProvider {
                    override def getParameter(name: String): String =
                      uri.query().get(name).orNull
                  })
                  resp.withEntity("")
                case QUERY_PARAM => resp.withEntity(uri.queryString().orNull)
                case REDIRECT =>
                  resp.withHeaders(headers.Location(endpoint.getBody))
                case ERROR     => resp.withEntity(endpoint.getBody)
                case EXCEPTION => throw new Exception(endpoint.getBody)
                case _ =>
                  HttpResponse(status = NOT_FOUND.getStatus)
                    .withEntity(NOT_FOUND.getBody)
              }
            }
          }
        )
      }
    case HttpRequest(POST, uri: Uri, _, _, _) =>
      val endpoint = ServerEndpoint.forPath(uri.path.toString())
      AbstractHttpServerTest.controller(endpoint, () =>
        for {
          _ <- Future.successful(2)
          firstContext = Context.current()
          _ <- after(1.second, system.scheduler)(Future.successful(1))
          secondContext = Context.current()
          _ = assert(firstContext eq secondContext)
        } yield HttpResponse(status = OK, entity = endpoint.getBody)
      )
  }

  private var binding: ServerBinding = _

  def start(port: Int): Unit = synchronized {
    if (null == binding) {
      import scala.concurrent.duration._
      binding = Await.result(
        Http().bindAndHandleAsync(asyncHandler, "localhost", port),
        10.seconds
      )
    }
  }

  def stop(): Unit = synchronized {
    if (null != binding) {
      binding.unbind()
      system.terminate()
      binding = null
    }
  }
}
