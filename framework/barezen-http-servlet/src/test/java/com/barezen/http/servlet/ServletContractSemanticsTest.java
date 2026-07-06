package com.barezen.http.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.barezen.web.Router;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a client reads when a BareZen route table runs inside a servlet container — asserted against bytes on a socket,
 * against a real Tomcat, because the phase's promise is that switching server does not change the answer.
 *
 * <p>Every expectation here was measured before it was written: the first run of this module's tests left bodies framed
 * as {@code Transfer-Encoding: chunked} where the JDK adapter states a length, which is why {@link ServletBridge#send}
 * states one, and the HEAD asymmetry ADR-0028 found in the JDK server reproduces here for its own reason (the container
 * suppresses an entity by exact token, so a lowercase {@code head} gets a body). ADR-0049 records both measurements and
 * the three cases the container refuses before this module can see them.
 */
class ServletContractSemanticsTest {

  private static ServletServer server;

  private static Response text(int status, String body) {
    return new Response(status, Headers.of("Content-Type", "text/plain; charset=utf-8"),
        body.getBytes(StandardCharsets.UTF_8));
  }

  @BeforeEach
  void start() throws Exception {
    Router router = Router.builder()
        .route(HttpMethod.GET, "/doc", (request, ignored) -> text(200, "hello"))
        .route(HttpMethod.HEAD, "/doc", (request, ignored) -> text(200, "hello"))
        .route(HttpMethod.GET, "/users/{id}", (request, params) -> text(200, "id=" + params.get("id")))
        .route(HttpMethod.GET, "/empty", (request, ignored) -> new Response(204, Headers.empty(), null))
        .route(HttpMethod.GET, "/notmodified", (request, ignored) -> new Response(304, Headers.empty(), null))
        .route(HttpMethod.GET, "/cookies", (request, ignored) -> new Response(200,
            Headers.of("Set-Cookie", "a=1").with("Set-Cookie", "b=2"), "x".getBytes(StandardCharsets.UTF_8)))
        .route(HttpMethod.POST, "/echo", (request, ignored) ->
            text(200, "body=" + new String(request.body(), StandardCharsets.UTF_8)))
        .route(HttpMethod.GET, "/query", (request, ignored) ->
            text(200, "query=" + request.query().map(value -> "[" + value + "]").orElse("<absent>")))
        .route(HttpMethod.GET, "/boom", (request, ignored) -> {
          throw new IllegalStateException("handler said no about /internal/path");
        })
        .build();
    server = ServletServer.servlet(router);
  }

  @AfterEach
  void stop() throws Exception {
    server.close();
  }

  private RawHttp.Response ask(String method, String target) throws Exception {
    return RawHttp.request(server.port(), method, target, List.of(), null);
  }

  @Nested
  @DisplayName("a handler's response, framed like the JDK adapter frames it")
  class Responses {

    @Test
    void aBodyIsWrittenWithTheLengthStated() throws Exception {
      RawHttp.Response response = ask("GET", "/doc");

      assertThat(response.status()).isEqualTo(200);
      assertThat(response.bodyUtf8()).isEqualTo("hello");
      assertThat(response.firstOf("Content-Length")).isEqualTo("5");
      assertThat(response.hasHeader("Transfer-Encoding")).isFalse();
    }

    @Test
    void twoValuesOfOneFieldStayTwoLines() throws Exception {
      RawHttp.Response response = ask("GET", "/cookies");

      // setHeader() would have kept only the last of these, which is how a session cookie silently disappears.
      assertThat(response.valuesOf("Set-Cookie")).containsExactly("a=1", "b=2");
    }

    @Test
    void a204CarriesNeitherALengthNorABody() throws Exception {
      RawHttp.Response response = ask("GET", "/empty");

      assertThat(response.status()).isEqualTo(204);
      assertThat(response.hasHeader("Content-Length")).isFalse();
      assertThat(response.body()).isEmpty();
    }

    @Test
    void a304CarriesNeitherALengthNorABody() throws Exception {
      RawHttp.Response response = ask("GET", "/notmodified");

      assertThat(response.status()).isEqualTo(304);
      assertThat(response.hasHeader("Content-Length")).isFalse();
      assertThat(response.body()).isEmpty();
    }
  }

  @Nested
  @DisplayName("HEAD, which the container decides by its own token")
  class Head {

    @Test
    void aHeadStatesTheLengthOfTheBodyItDoesNotSend() throws Exception {
      RawHttp.Response response = ask("HEAD", "/doc");

      // The handler returned the same five bytes a GET would have returned; the container suppressed the entity and
      // kept the length, which is ADR-0027 §6's answer produced by the one component that decides both.
      assertThat(response.status()).isEqualTo(200);
      assertThat(response.firstOf("Content-Length")).isEqualTo("5");
      assertThat(response.body()).isEmpty();
    }

    @Test
    void aTokenThatIsNotExactlyHeadGetsTheBodyItsRepresentationHas() throws Exception {
      RawHttp.Response response = ask("head", "/doc");

      // The route table folded the token and ran the HEAD handler, but the container's parser compares exactly, so the
      // bytes go out. Same shape as ADR-0028 measured on the JDK server — here it is the container's rule, and the
      // length stays true either way, which is the property a cache depends on.
      assertThat(response.status()).isEqualTo(200);
      assertThat(response.bodyUtf8()).isEqualTo("hello");
      assertThat(response.firstOf("Content-Length")).isEqualTo("5");
    }

    @Test
    void aHeadWithNoHeadRouteIsRefusedWithoutABody() throws Exception {
      RawHttp.Response response = ask("HEAD", "/query");

      assertThat(response.status()).isEqualTo(405);
      assertThat(response.firstOf("Allow")).isEqualTo("GET");
      assertThat(response.body()).isEmpty();
    }
  }

  @Nested
  @DisplayName("the routing answers, in the router's hands")
  class Routing {

    @Test
    void anUnknownPathIsA404ThatNamesNoPath() throws Exception {
      RawHttp.Response response = ask("GET", "/nothing-here");

      assertThat(response.status()).isEqualTo(404);
      assertThat(response.bodyUtf8()).isEqualTo("Not Found");
      // A 404 that echoes the target teaches a probe which paths the server distinguishes (ADR-0023 §2).
      assertThat(response.bodyUtf8()).doesNotContain("nothing-here");
    }

    @Test
    void aKnownPathWithTheWrongVerbIsA405WithTheMethodsTheTableNamed() throws Exception {
      RawHttp.Response response = ask("POST", "/doc");

      assertThat(response.status()).isEqualTo(405);
      assertThat(response.firstOf("Allow")).isEqualTo("GET, HEAD");
      assertThat(response.bodyUtf8()).isEqualTo("Method Not Allowed");
    }

    @Test
    void anUnregisteredVerbOnAKnownPathStillAsksTheRouter() throws Exception {
      RawHttp.Response response = ask("OPTIONS", "/doc");

      // HttpServlet's own service() would have answered this before the router saw it; the phase overrides dispatch
      // precisely so the route table is the only thing that decides (ADR-0019 §10).
      assertThat(response.status()).isEqualTo(405);
      assertThat(response.firstOf("Allow")).isEqualTo("GET, HEAD");
      assertThat(response.bodyUtf8()).isEqualTo("Method Not Allowed");
    }

    @Test
    void aCapturedParameterReachesTheHandlerAsAParameter() throws Exception {
      assertThat(ask("GET", "/users/42").bodyUtf8()).isEqualTo("id=42");
    }

    @Test
    void anEncodedPathSegmentIsSeenAsTheClientSentIt() throws Exception {
      // The router's raw-segment rule (ADR-0019 §4): a percent sign is part of the segment, not a hint to decode it.
      // Tomcat refuses %2F in a path before any servlet runs, which is the container's own rule and is recorded in
      // ADR-0049; %7B and a plain segment reach the router intact.
      assertThat(ask("GET", "/users/a%20b").bodyUtf8()).isEqualTo("id=a%20b");
    }
  }

  @Nested
  @DisplayName("what the client sent, arriving unchanged")
  class Requests {

    @Test
    void anAbsentQueryAndAnEmptyOneAreDifferentAnswers() throws Exception {
      assertThat(ask("GET", "/query").bodyUtf8()).isEqualTo("query=<absent>");
      assertThat(ask("GET", "/query?").bodyUtf8()).isEqualTo("query=[]");
      assertThat(ask("GET", "/query?a=1").bodyUtf8()).isEqualTo("query=[a=1]");
    }

    @Test
    void aDeclaredBodyArrivesWhole() throws Exception {
      RawHttp.Response response = RawHttp.request(server.port(), "POST", "/echo", List.of(), "payload");

      assertThat(response.bodyUtf8()).isEqualTo("body=payload");
    }

    @Test
    void aChunkedBodyIsReadAsTheSameBytes() throws Exception {
      RawHttp.Response response = RawHttp.requestRaw(server.port(), "POST /echo HTTP/1.1\r\nHost: 127.0.0.1:"
          + server.port() + "\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n2\r\nhi\r\n0\r\n\r\n");

      assertThat(response.status()).isEqualTo(200);
      assertThat(response.bodyUtf8()).isEqualTo("body=hi");
    }

    @Test
    void aRepeatedHeaderKeepsEveryValueInTheContractRequest() throws Exception {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/count", (request, ignored) ->
              text(200, "x=" + request.headers().values("X-Test").size())).build();
      try (ServletServer counted = ServletServer.servlet(router)) {
        RawHttp.Response response = RawHttp.request(counted.port(), "GET", "/count",
            List.of("X-Test: one", "X-Test: two"), null);

        assertThat(response.bodyUtf8()).isEqualTo("x=2");
      }
    }
  }

  @Nested
  @DisplayName("a handler that failed")
  class Failures {

    @Test
    void aHandlerThrowingIsA500ThatCarriesNothingOfTheFailure() throws Exception {
      RawHttp.Response response = ask("GET", "/boom");

      assertThat(response.status()).isEqualTo(500);
      assertThat(response.bodyUtf8()).isEqualTo("Internal Server Error");
      String wholeMessage = response.statusLine() + String.join("\n", response.fieldLines()) + response.body();
      // The exception named an internal path and a class; none of it reaches the socket, and the container's error
      // page is not used because the catch is inside the servlet (ADR-0023 §4).
      assertThat(wholeMessage).doesNotContain("internal/path")
          .doesNotContain("IllegalStateException")
          .doesNotContain("handler said no")
          .doesNotContain("Tomcat");
    }

    @Test
    void twoRequestsOnOneConnectionBothGetTheirOwnAnswer() throws Exception {
      RawHttp.Response first = ask("GET", "/doc");
      RawHttp.Response second = ask("GET", "/nothing-here");

      // Connection policy belongs to the container (ADR-0024's port-ownership argument applies here too), and this
      // asserts only what this module is responsible for: no state carried from one request into the next.
      assertThat(first.status()).isEqualTo(200);
      assertThat(second.status()).isEqualTo(404);
    }
  }
}
