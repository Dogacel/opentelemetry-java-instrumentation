/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.pekkohttp.v1_0

import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension
import io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.SUCCESS
import io.opentelemetry.instrumentation.testing.junit.http.{HttpServerInstrumentationExtension, HttpServerTestOptions}
import io.opentelemetry.testing.internal.armeria.common.{AggregatedHttpRequest, AggregatedHttpResponse}
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class PekkoHttpServerInstrumentationTestAsync
    extends AbstractHttpServerInstrumentationTest {

  @RegisterExtension val extension: InstrumentationExtension =
    HttpServerInstrumentationExtension.forAgent()

  override protected def setupServer(): AnyRef = {
    PekkoHttpTestAsyncWebServer.start(port)
    null
  }

  override protected def stopServer(server: Object): Unit =
    PekkoHttpTestAsyncWebServer.stop()

  override protected def configure(
      options: HttpServerTestOptions
  ): Unit = {
    super.configure(options)
    options.setTestHttpPipelining(false)
  }

  @Test
  def successfulPostRequestWithParent(): Unit = {
    val method = "POST"
    val traceId = "00000000000000000000000000000123"
    val parentId = "0000000000000456"
    val aggregatedHttpRequest = AggregatedHttpRequest.of( // intentionally sending mixed-case "tracePARENT" to make sure that TextMapGetters are
      // not case-sensitive
      request(SUCCESS, method).headers.toBuilder.set("tracePARENT", "00-" + traceId + "-" + parentId + "-01").build)
    val response = client.execute(aggregatedHttpRequest).aggregate.join
    assertThat(response.status.code).isEqualTo(SUCCESS.getStatus)
    assertThat(response.contentUtf8).isEqualTo(SUCCESS.getBody)
    val spanId = assertResponseHasCustomizedHeaders(response, SUCCESS, traceId)
    assertTheTraces(1, traceId, parentId, spanId, "POST", SUCCESS)
  }
}
