package com.barezen.examples.requestresponse;

import com.barezen.testkit.Assertions;
import com.barezen.testkit.TestServer;
import org.junit.jupiter.api.Test;

/** Proves the request/response example reads body, headers and query, and frames a 204 with no body. */
final class RequestResponseExampleTest {

  @Test
  void echoReturnsTheRequestBodyVerbatim() {
    try (TestServer server = TestServer.start(RequestResponseExample.router())) {
      Assertions.assertThat(server.client().post("/echo", "ping")).statusIs(200).bodyIs("ping");
    }
  }

  @Test
  void describeReportsMethodHeaderAndQuery() {
    try (TestServer server = TestServer.start(RequestResponseExample.router())) {
      Assertions.assertThat(server.client().request(com.barezen.web.HttpMethod.GET, "/describe?a=1")
              .header("X-Name", "zen").send())
          .statusIs(200)
          .bodyIs("GET /describe name=zen query=a=1");
    }
  }

  @Test
  void twoOhFourCarriesNoBodyAndAssertsNoLength() {
    try (TestServer server = TestServer.start(RequestResponseExample.router())) {
      var response = server.client().delete("/gone");
      Assertions.assertThat(response).statusIs(204).bodyIs("");
      // A 204 is bodyless, so the framework asserts no length for it: the handler sent empty headers.
      org.assertj.core.api.Assertions.assertThat(response.header("Content-Length")).isEmpty();
    }
  }
}
