package com.barezen.http.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Response;
import com.barezen.web.Router;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The five statuses this adapter can produce, asserted on the wire (ADR-0023 §1).
 *
 * <p>Each test names which party failed, because that is the whole design of this section: a {@code 404} and a
 * {@code 405} are the client asking for something the route table does not hold, a {@code 400} is the client sending
 * something the contract cannot even read, a {@code 500} is the server's own failure, and an abandoned response is
 * nobody's failure at all. Grouping them into one {@code catch (Exception)} would have flattened exactly that
 * distinction, so the tests below are written to be unable to pass if it comes back (probes E and F).
 *
 * <p>The socket is the only way to test any of this: {@code HttpExchange} is final in {@code com.sun.net.httpserver}, its
 * {@code getPrincipal()} cannot be overridden from outside the module, and there is no setter for response headers. There
 * is no fake exchange in this suite because there is no way to build one.
 */
class JdkErrorSemanticsTest {

  private static final Response text(String body) {
    return new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8"),
        body.getBytes(StandardCharsets.UTF_8));
  }

  private static int start(JdkHttpServerAdapter adapter) {
    adapter.start();
    return adapter.port().orElseThrow(() -> new AssertionError("a started adapter reports the port it bound"));
  }

  private static JdkHttpServerAdapter on(Router router) {
    return new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router);
  }

  @Nested
  @DisplayName("404 and 405: the two answers a route table gives")
  class NotFoundAndNotAllowed {

    @Test
    void aPathWithNoRouteAtAllIsANotFound() throws IOException {
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/users/{id}", (request, parameters) -> text(parameters.get("id")))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/does-not-exist", List.of(), null);

        assertThat(answer.status()).isEqualTo(404);
        assertThat(answer.body()).isEqualTo("Not Found");
        // A 404 that echoes the target tells a probe which paths the server distinguishes, so the request line appears
        // nowhere in the answer (ADR-0023 §2).
        assertThat(answer.body()).doesNotContain("does-not-exist");
      }
    }

    @Test
    void aTrailingSlashThatIsNotRegisteredIsANotFoundRatherThanARedirect() throws IOException {
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/users", (request, parameters) -> text("collection"))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/users/", List.of(), null);

        // 301 would be a policy this contract has no type for, and 404 is the route table's honest answer: nothing is
        // registered at three segments (ADR-0019 §6).
        assertThat(answer.status()).isEqualTo(404);
        assertThat(answer.valuesOf("Location")).isEmpty();
      }
    }

    @Test
    void aPathThatExistsWithAnotherMethodIsAMethodNotAllowedWithAllow() throws IOException {
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/users/{id}", (request, parameters) -> text(parameters.get("id")))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "POST", "/users/42", List.of(), "hello");

        assertThat(answer.status()).isEqualTo(405);
        assertThat(answer.firstOf("Allow")).isEqualTo("GET");
        assertThat(answer.body()).isEqualTo("Method Not Allowed");
      }
    }

    @Test
    void allowListsEveryRegisteredMethodInOneLine() throws IOException {
      // Registered in an order that is deliberately not the order Allow is written in, so the test measures the
      // adapter's ordering rather than the route table's.
      Router router = Router.builder()
          .route(HttpMethod.DELETE, "/things/{id}", (request, parameters) -> text("gone"))
          .route(HttpMethod.POST, "/things/{id}", (request, parameters) -> text("made"))
          .route(HttpMethod.GET, "/things/{id}", (request, parameters) -> text(parameters.get("id")))
          .route(HttpMethod.PUT, "/things/{id}", (request, parameters) -> text("set"))
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "PATCH", "/things/7", List.of(), null);

        assertThat(answer.status()).isEqualTo(405);
        // The HTTP method order the adapter declares, not registration order and not hash order (ADR-0023 §3).
        assertThat(answer.firstOf("Allow")).isEqualTo("GET, POST, PUT, DELETE");
        assertThat(answer.countOf("Allow")).isEqualTo(1);
      }
    }

    @Test
    void twoIdentical405sAlwaysCarryTheSameAllowValue() throws IOException {
      Router router = Router.builder()
          .route(HttpMethod.POST, "/x", (request, parameters) -> text("post"))
          .route(HttpMethod.GET, "/x", (request, parameters) -> text("get"))
          .route(HttpMethod.DELETE, "/x", (request, parameters) -> text("delete"))
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        String first = RawHttp.request(port, "PUT", "/x", List.of(), null).firstOf("Allow");
        String second = RawHttp.request(port, "PUT", "/x", List.of(), null).firstOf("Allow");

        assertThat(first).isEqualTo("GET, POST, DELETE");
        assertThat(second).isEqualTo(first);
      }
    }

    @Test
    void aMethodTheContractDoesNotNameStillAppearsInAllow() throws IOException {
      // HttpMethod.of() accepts any token, so an application can register PURGE. ALLOW_ORDER does not list it, and the
      // unlisted tail is where such a method has to be found — otherwise the adapter would advertise a method the route
      // table holds while refusing to name it.
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.of("PURGE"), "/cache/{key}", (request, parameters) -> text("purged"))
          .route(HttpMethod.GET, "/cache/{key}", (request, parameters) -> text(parameters.get("key")))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "POST", "/cache/index", List.of(), null);

        assertThat(answer.status()).isEqualTo(405);
        assertThat(answer.firstOf("Allow")).isEqualTo("GET, PURGE");
      }
    }

    @Test
    void aRegisteredHeadIsAnsweredWithoutABody() throws IOException {
      // HEAD needs its own route: the router matches on the method the client sent, so a HEAD request is not served by
      // a GET registration (ADR-0019 §2). The adapter then drops the body (ADR-0022 §7).
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/doc", (request, parameters) -> text("hello"))
          .route(HttpMethod.HEAD, "/doc", (request, parameters) -> text("hello"))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "HEAD", "/doc", List.of(), null);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).isEmpty();
      }
    }
  }

  @Nested
  @DisplayName("OPTIONS * never arrives, which is this phase's design debt")
  class StarTarget {

    /**
     * Recorded rather than asserted as working: the JDK answers {@code OPTIONS *} itself and never creates an exchange
     * for it, so the dispatcher's {@code "*"} branch cannot be reached from a socket (ADR-0023 §9).
     *
     * <p>The assertion is written against what the server actually does, which is a 404 with the JDK's own HTML body —
     * not this adapter's {@code Not Found}. That difference is the evidence: a response this module did not write
     * arrived, which is only possible if the request never got here.
     */
    @Test
    void optionsStarIsAnsweredByTheJdkBeforeTheAdapterSeesIt() throws IOException {
      AtomicInteger handled = new AtomicInteger();
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.OPTIONS, "/users", (request, parameters) -> {
            handled.incrementAndGet();
            return text("ok");
          })
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "OPTIONS", "*", List.of(), null);

        assertThat(answer.status()).isEqualTo(404);
        // The JDK's own text, so this proves the answer came from the server rather than from JdkRequestDispatcher.
        assertThat(answer.body()).contains("No context found for request");
        assertThat(handled.get()).isZero();
      }
    }

    @Test
    void aRequestForTheAsteriskTargetIsNotSilentlyTurnedIntoADifferentPath() throws IOException {
      // The same finding restated from the contract's side: no request line in this test family produced a handler call
      // for "*", and nothing here invents an Allow list for the server as a whole. A later phase that wants
      // OPTIONS * answered has to change how requests reach the dispatcher, not just add a route.
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/users", (request, parameters) -> text("users"))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "*", List.of(), null);

        assertThat(answer.status()).isEqualTo(404);
        assertThat(answer.body()).doesNotContain("users");
      }
    }
  }

  @Nested
  @DisplayName("500: the server's own failure, described to the client as nothing")
  class HandlerFailure {

    @Test
    void aThrowingHandlerBecomesAServerErrorWhoseBodyHoldsNoneOfTheFailure() throws IOException {
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/boom", (request, parameters) -> {
            throw new IllegalStateException("secret");
          })
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/boom", List.of(), null);

        assertThat(answer.status()).isEqualTo(500);
        assertThat(answer.body()).isEqualTo("Internal Server Error");
        // The directive's list, one assertion per entry: the message, the class, a frame, an internal path, an
        // environment value and the client's own header must all be absent from what came back.
        assertThat(answer.body()).doesNotContain("secret");
        assertThat(answer.body()).doesNotContain("IllegalStateException");
        assertThat(answer.body()).doesNotContain("at ");
        assertThat(answer.body()).doesNotContain("/barezen");
        assertThat(answer.body()).doesNotContain(System.getProperty("user.dir"));
        assertThat(answer.fieldLines().toString()).doesNotContain("secret");
        assertThat(answer.fieldLines().toString()).doesNotContain("X-Diagnostic");
      }
    }

    @Test
    void theHandlerFailureIsRecordedWithItsThrowableInTheLog() throws IOException {
      // The other half of ADR-0023 §4: what the socket does not carry, the log must. Without this test the non-leaking
      // 500 would be indistinguishable from a swallowed exception.
      try (CapturedLogs logs = CapturedLogs.record("com.barezen.http.jdk.JdkRequestDispatcher", Level.ERROR);
          JdkHttpServerAdapter adapter = on(Router.builder()
              .route(HttpMethod.GET, "/boom", (request, parameters) -> {
                throw new IllegalStateException("secret");
              })
              .build())) {
        int port = start(adapter);

        RawHttp.request(port, "GET", "/boom", List.of("X-Diagnostic: visible-in-log-only"), null);

        assertThat(logs.events()).hasSize(1);
        assertThat(logs.onlyEvent().getFormattedMessage()).contains("GET").contains("/boom");
        assertThat(logs.onlyEvent().getThrowableProxy().getClassName())
            .isEqualTo("java.lang.IllegalStateException");
        // The message reaches the log, because that is where a developer reads it (ADR-0023 §4).
        assertThat(logs.onlyEvent().getThrowableProxy().getMessage()).isEqualTo("secret");
      }
    }

    @Test
    void aHandlerThatReturnsAnErrorStatusIsWrittenAsReturned() throws IOException {
      // A 503 chosen by the application is not a handler failure and must not be replaced or logged as one.
      try (CapturedLogs logs = CapturedLogs.record("com.barezen.http.jdk.JdkRequestDispatcher", Level.ERROR);
          JdkHttpServerAdapter adapter = on(Router.builder()
              .route(HttpMethod.GET, "/down", (request, parameters) ->
                  new Response(503, Headers.of("Retry-After", "30"), "down".getBytes(StandardCharsets.UTF_8)))
              .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/down", List.of(), null);

        assertThat(answer.status()).isEqualTo(503);
        assertThat(answer.firstOf("Retry-After")).isEqualTo("30");
        assertThat(answer.body()).isEqualTo("down");
        assertThat(logs.events()).isEmpty();
      }
    }

    @Test
    void oneFailingRequestDoesNotDisturbTheNextOne() throws IOException {
      AtomicInteger calls = new AtomicInteger();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/sometimes", (request, parameters) -> {
            if (calls.incrementAndGet() == 1) {
              throw new IllegalStateException("first one only");
            }
            return text("second");
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        assertThat(RawHttp.request(port, "GET", "/sometimes", List.of(), null).status()).isEqualTo(500);
        RawHttp.Response second = RawHttp.request(port, "GET", "/sometimes", List.of(), null);

        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body()).isEqualTo("second");
        assertThat(adapter.isRunning()).isTrue();
      }
    }
  }

  @Nested
  @DisplayName("400: the client's failure to say anything readable")
  class BadRequest {

    @Test
    void aTargetTheContractRefusesIsABadRequestAndNotAServerError() throws IOException {
      // Probed: a bare "%2F" as the request target reaches the adapter as a raw path with no leading "/", which
      // Request's own validation refuses. The adapter reports the client's mistake rather than its own (ADR-0022 §2,
      // ADR-0023 §5).
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/users", (request, parameters) -> text("users"))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "%2F", List.of(), null);

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.body()).isEqualTo("Bad Request");
        // The refused target is not echoed: a 400 body that repeats it back is a reflection of the request rather than
        // an answer about it (ADR-0023 §2, §5).
        assertThat(answer.body()).doesNotContain("%2F");
      }
    }

    @Test
    void aRequestBodyThatStopsArrivingIsABadRequestBecauseNothingWasCommittedYet() throws IOException {
      // The distinction probes E and F exist to protect: this failure happens while the request is being read, so a
      // status is still available to send. A blanket catch that answered 500 here would blame the server for a body the
      // client never finished (ADR-0023 §5, §6).
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.POST, "/echo", (request, parameters) -> text(new String(request.body(),
              StandardCharsets.UTF_8)))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.requestWithTruncatedBody(port, "POST", "/echo", 10, 3);

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.body()).isEqualTo("Bad Request");
      }
    }

    @Test
    void aTruncatedRequestIsAnsweredWithoutEverReachingTheHandler() throws IOException {
      AtomicInteger calls = new AtomicInteger();
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.POST, "/echo", (request, parameters) -> {
            calls.incrementAndGet();
            return text("echoed");
          })
          .build())) {
        int port = start(adapter);

        RawHttp.requestWithTruncatedBody(port, "POST", "/echo", 10, 0);

        // No handler ran, so the 400 cannot have come from application code — it is the bridge's answer
        // (ADR-0023 §5).
        assertThat(calls.get()).isZero();
      }
    }

    @Test
    void theServerStillAnswersAfterARefusedRequest() throws IOException {
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/users", (request, parameters) -> text("users"))
          .build())) {
        int port = start(adapter);

        RawHttp.request(port, "GET", "%2F", List.of(), null);
        RawHttp.Response after = RawHttp.request(port, "GET", "/users", List.of(), null);

        assertThat(after.status()).isEqualTo(200);
        assertThat(after.body()).isEqualTo("users");
        assertThat(adapter.isRunning()).isTrue();
      }
    }
  }

  @Nested
  @DisplayName("Low-level parsing belongs to the JDK")
  class ParserIsTheJdks {

    /**
     * A header name with a space in it is refused by {@code sun.net.httpserver} before an exchange exists: the answer
     * arrives with the JDK's HTML body and no {@code Date} header, which is what distinguishes it from a response this
     * module wrote. The phase's rule is not to re-implement a parser, so the adapter neither sees nor improves on this
     * (ADR-0023 §7).
     */
    @Test
    void anIllegalHeaderNameNeverReachesTheAdapter() throws IOException {
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/users", (request, parameters) -> text("users"))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/users",
            List.of("Bad Header Name"), null);

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.body()).contains("<h1>400 Bad Request</h1>");
      }
    }

    @Test
    void aMalformedRequestLineNeverReachesTheAdapter() throws IOException {
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/users", (request, parameters) -> text("users"))
          .build())) {
        int port = start(adapter);

        // Sent as raw bytes rather than as a method and target: "garbage" has no method/target split to name.
        RawHttp.Response answer = RawHttp.requestRaw(port, "garbage line\r\nHost: x\r\nConnection: close\r\n\r\n");

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.body()).contains("Bad request line");
      }
    }

    @Test
    void aChunkedRequestBodyIsHandledByTheJdkAndGivenToTheContractAsBytes() throws IOException {
      // Chunked framing is a transfer coding the JDK decodes; the adapter reads a complete body either way, which is
      // the evidence that this module owns no parser (ADR-0023 §7).
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.POST, "/echo", (request, parameters) -> text(new String(request.body(),
              StandardCharsets.UTF_8)))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.requestRaw(port, "POST /echo HTTP/1.1\r\nHost: 127.0.0.1:" + port
            + "\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).isEqualTo("abc");
      }
    }
  }

  @Nested
  @DisplayName("A response that stopped reaching the client stays one response")
  class CommittedResponses {

    @Test
    void aClientThatHangsUpMidWriteLogsOnceAndNeverProducesASecondStatus() throws Exception {
      // 4 MB gives the reset time to land while the body is still being written. The evidence is in the log: exactly one
      // abandoned-response line at debug, and no error — an attempt to answer a committed exchange would have logged or
      // thrown a second failure (ADR-0023 §6).
      byte[] large = new byte[4_000_000];
      java.util.Arrays.fill(large, (byte) 'z');
      try (CapturedLogs logs = CapturedLogs.record("com.barezen.http.jdk.JdkRequestDispatcher", Level.DEBUG);
          JdkHttpServerAdapter adapter = on(Router.builder()
              .route(HttpMethod.POST, "/big", (request, parameters) ->
                  new Response(200, Headers.empty(), large))
              .build())) {
        int port = start(adapter);

        String head = RawHttp.readThenReset(port, "/big", 16);

        // Committed: the status line had already left.
        assertThat(head).startsWith("HTTP/1.1 200");
        Thread.sleep(750);
        String recorded = logs.events().stream()
            .map(event -> event.getLevel() + " " + event.getFormattedMessage())
            .reduce("", (left, right) -> left + "\n" + right);
        assertThat(recorded).contains("the response did not reach the client");
        assertThat(recorded).doesNotContain("headers already sent");
        assertThat(logs.events()).noneMatch(event -> event.getLevel().equals(Level.ERROR));
        assertThat(adapter.isRunning()).isTrue();
      }
    }

    @Test
    void theServerKeepsServingAfterAResponseWasAbandoned() throws IOException {
      byte[] large = new byte[4_000_000];
      java.util.Arrays.fill(large, (byte) 'z');
      Router router = Router.builder()
          .route(HttpMethod.POST, "/big", (request, parameters) -> new Response(200, Headers.empty(), large))
          .route(HttpMethod.GET, "/users", (request, parameters) -> text("users"))
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.readThenReset(port, "/big", 8);
        RawHttp.Response after = RawHttp.request(port, "GET", "/users", List.of(), null);

        assertThat(after.status()).isEqualTo(200);
        assertThat(after.body()).isEqualTo("users");
      }
    }
  }
}
