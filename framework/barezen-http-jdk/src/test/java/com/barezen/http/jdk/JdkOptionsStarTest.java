package com.barezen.http.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.barezen.web.RouteMatch;
import com.barezen.web.Router;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a request line naming {@code *} as its target actually does, measured on a socket (ADR-0026 §2, §3, §6).
 *
 * <p>This is the evidence for the phase's decision not to support {@code OPTIONS *}. It is written as tests rather than
 * as prose because the decision is only honest if something fails when it changes: a later adapter that silently starts
 * routing the asterisk, a JDK that starts delivering it to a context, or a handler that begins answering for the server
 * as a whole would each break a line below.
 *
 * <p><b>The finding in one sentence:</b> the JDK server never creates an exchange for a target of {@code "*"}, so no
 * code in this module — dispatcher, bridge, or an application's handler — can see the request, let alone answer it. The
 * mechanism is in {@code ServerImpl}: the target is resolved into a {@code URI}, its path is looked up in the context
 * list by prefix, and {@code createContext} only accepts a path that starts with {@code /} — so no registrable context
 * can be a prefix of {@code "*"}, the lookup returns null, and the server writes its own {@code 404} and closes the
 * connection. That answer has a fingerprint, and the tests below read it rather than the status code alone:
 *
 * <ul>
 *   <li>the body is {@code <h1>404 Not Found</h1>No context found for request}, which this module never writes;
 *   <li>the {@code Content-length} field is spelled with a lowercase {@code l} on a response the server wrote through an
 *       exchange, and with an uppercase {@code L} on this one, because two different pieces of code emit them — the
 *       uppercase spelling comes from the pre-context reply writer, the lowercase one from the canonicalizing header
 *       path that an actual response goes through;
 *   <li>there is no {@code Date} field, because the server's reply writer for a request with no context does not go
 *       through {@code sendResponseHeaders}, which is what always sets {@code Date}.
 * </ul>
 *
 * <p>Every test here opens a plain socket: {@code java.net.http.HttpClient} refuses to send a request line with a
 * {@code *} target at all, which would hide the behaviour rather than show it.
 */
class JdkOptionsStarTest {

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

  /**
   * Send hand-built request lines on one connection and return everything that came back, uninterpreted.
   *
   * <p>{@link RawHttp#request} adds {@code Connection: close} to every message and stops at one answer, and two tests
   * below need neither: one observes what the server does to a keep-alive connection after an asterisk, the other sends
   * an absolute-form target. Those claims are about the connection rather than about one message, so the fixture writes
   * the bytes and returns the whole byte-for-byte reply text.
   */
  private static String raw(int port, String... requestLines) throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
      socket.setSoTimeout(5_000);
      OutputStream out = socket.getOutputStream();
      for (String line : requestLines) {
        out.write(line.getBytes(StandardCharsets.US_ASCII));
      }
      out.flush();
      InputStream in = socket.getInputStream();
      ByteArrayOutputStream received = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int read;
      while ((read = in.read(buffer)) >= 0) {
        received.write(buffer, 0, read);
      }
      return received.toString(StandardCharsets.ISO_8859_1);
    }
  }

  /** How many status lines arrived on a connection, which is how "the server closed it" is counted. */
  private static int answers(String reply) {
    int at = 0;
    int count = 0;
    while ((at = reply.indexOf("HTTP/1.1 ", at)) >= 0) {
      count++;
      at += "HTTP/1.1 ".length();
    }
    return count;
  }

  /**
   * The asterisk itself: who answers, and how many handlers do not.
   *
   * <p>Two resource handlers are registered and counted, one of them for {@code OPTIONS} at the root — the pair a
   * synthetic implementation would have been tempted to reuse. Both counters must stay at zero, because the request is
   * answered before the context lookup that would have found them (ADR-0026 §6, the invocation counter the phase's §6
   * asks for).
   */
  @Nested
  @DisplayName("OPTIONS * is answered before any context exists")
  class AsteriskTarget {

    @Test
    void theServerAnswersAndEveryResourceHandlerStaysUncalled() throws IOException {
      AtomicInteger optionsRootCalls = new AtomicInteger();
      AtomicInteger getCalls = new AtomicInteger();
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.OPTIONS, "/", (request, parameters) -> {
            optionsRootCalls.incrementAndGet();
            return text("answered-by-the-resource-handler");
          })
          .route(HttpMethod.GET, "/users/{id}", (request, parameters) -> {
            getCalls.incrementAndGet();
            return text(parameters.get("id"));
          })
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "OPTIONS", "*", List.of(), null);

        assertThat(answer.status()).isEqualTo(404);
        // The server's own words, not this adapter's: a body this module never writes is the direct evidence that the
        // request did not travel through JdkRequestDispatcher.
        assertThat(answer.body()).isEqualTo("<h1>404 Not Found</h1>No context found for request");
        assertThat(answer.statusLine()).isEqualTo("HTTP/1.1 404 Not Found");
        assertThat(optionsRootCalls).hasValue(0);
        assertThat(getCalls).hasValue(0);
      }
    }

    @Test
    void theAnswerCarriesNoDateAndNoAllowAndExactlyOneLength() throws IOException {
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/users", (request, parameters) -> text("users"))
          .route(HttpMethod.OPTIONS, "/users", (request, parameters) -> text("options"))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "OPTIONS", "*", List.of(), null);

        // No Allow: nothing here is a capability list of the server, and the phase forbids inventing one
        // (ADR-0026 §5). No Date: sendResponseHeaders, which always sets it, never ran for this request.
        assertThat(answer.valuesOf("Allow")).isEmpty();
        assertThat(answer.valuesOf("Date")).isEmpty();
        assertThat(answer.valuesOf("Server")).isEmpty();
        assertThat(answer.countOf("Content-Length")).isEqualTo(1);
        assertThat(answer.valuesOf("Content-Length")).containsExactly("50");
        assertThat(answer.fieldLines()).anyMatch(line -> line.startsWith("Content-Length:"));
        assertThat(answer.valuesOf("Connection")).containsExactly("close");
      }
    }

    @Test
    void noRegisteredMethodOrRouteChangesThatAnswer() throws IOException {
      AtomicInteger calls = new AtomicInteger();
      Router.Builder routes = Router.builder();
      for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT,
          HttpMethod.DELETE, HttpMethod.OPTIONS, HttpMethod.TRACE, HttpMethod.PATCH)) {
        routes.route(method, "/", (request, parameters) -> {
          calls.incrementAndGet();
          return text("root");
        });
      }
      try (JdkHttpServerAdapter adapter = on(routes.build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "OPTIONS", "*", List.of(), null);

        // A route table that would answer OPTIONS / for every method still sees nothing: the request is not a path, and
        // no amount of registration turns it into one (ADR-0026 §4).
        assertThat(answer.body()).contains("No context found for request");
        assertThat(calls).hasValue(0);
      }
    }

    @Test
    void anyMethodNamingTheAsteriskGetsTheSameServerWrittenAnswer() throws IOException {
      AtomicInteger calls = new AtomicInteger();
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/users", (request, parameters) -> {
            calls.incrementAndGet();
            return text("users");
          })
          .build())) {
        int port = start(adapter);

        // Not an OPTIONS-specific quirk: the target is what fails to resolve, so PURGE * is refused exactly like
        // OPTIONS *. Recorded rather than assumed, because the claim "only OPTIONS * is unsupported" would be a
        // narrower and therefore misleading limitation (ADR-0026 §3).
        for (String method : List.of("OPTIONS", "GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "TRACE", "CONNECT",
            "PURGE")) {
          RawHttp.Response answer = RawHttp.request(port, method, "*", List.of(), null);

          assertThat(answer.statusLine()).isEqualTo("HTTP/1.1 404 Not Found");
          assertThat(answer.body()).isEqualTo("<h1>404 Not Found</h1>No context found for request");
        }
        assertThat(calls).hasValue(0);
      }
    }

    @Test
    void theConnectionCarryingTheAsteriskIsClosedRatherThanKeptAlive() throws IOException {
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/users", (request, parameters) -> text("users"))
          .build())) {
        int port = start(adapter);

        // No Connection: close in the request — HTTP/1.1 keeps this connection open by default. The server's reply for a
        // target with no context closes it anyway, so the request after the asterisk never gets an answer (ADR-0026 §7:
        // the limitation costs a connection, which is why a client's OPTIONS * is worth pinning as a wire fact).
        String first = "OPTIONS * HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n";
        String second = "GET /users HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n";

        String reply = raw(port, first, second);

        assertThat(reply).startsWith("HTTP/1.1 404 Not Found\r\n");
        assertThat(reply).contains("No context found for request");
        // The second request line was written to a socket the server had already decided to close: one answer ever
        // arrives on this connection, and the follow-up request is simply gone (ADR-0026 §7 — the limitation costs a
        // connection, which is why it is pinned as a wire fact rather than asserted as a policy).
        assertThat(answers(reply)).isEqualTo(1);
      }
    }
  }

  /**
   * The requests that are not the asterisk, pinned beside it, because "unsupported" only means something when the
   * supported neighbours still work.
   */
  @Nested
  @DisplayName("a target that is a path is routed normally")
  class PathTargets {

    @Test
    void optionsSlashReachesTheHandlerAtTheRoot() throws IOException {
      AtomicInteger calls = new AtomicInteger();
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.OPTIONS, "/", (request, parameters) -> {
            calls.incrementAndGet();
            return new Response(200, Headers.of("Allow", "GET, OPTIONS"), new byte[0]);
          })
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "OPTIONS", "/", List.of(), null);

        // The distinction the phase forbids blurring: {@code OPTIONS /} is a resource request and is answered by the
        // route table; {@code OPTIONS *} names the server and is answered by the server (ADR-0026 §4).
        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.valuesOf("Allow")).containsExactly("GET, OPTIONS");
        assertThat(calls).hasValue(1);
      }
    }

    @Test
    void optionsOnARegisteredParametrizedPathIsAFourOhFiveWithAllow() throws IOException {
      AtomicInteger getCalls = new AtomicInteger();
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/users/{id}", (request, parameters) -> {
            getCalls.incrementAndGet();
            return text(parameters.get("id"));
          })
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "OPTIONS", "/users/42", List.of(), null);

        assertThat(answer.status()).isEqualTo(405);
        assertThat(answer.valuesOf("Allow")).containsExactly("GET");
        assertThat(answer.body()).isEqualTo("Method Not Allowed");
        assertThat(getCalls).hasValue(0);
      }
    }

    @Test
    void optionsOnAnUnregisteredPathIsThisAdaptersFourOhFourNotTheServers() throws IOException {
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/users", (request, parameters) -> text("users"))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "OPTIONS", "/does-not-exist", List.of(), null);

        // Same status as the asterisk's answer, different writer, and the body tells them apart: this is the adapter's
        // four-word body, which names no path and carries no HTML (ADR-0023 §2).
        assertThat(answer.status()).isEqualTo(404);
        assertThat(answer.body()).isEqualTo("Not Found");
        assertThat(answer.body()).doesNotContain("No context found");
        assertThat(answer.valuesOf("Date")).hasSize(1);
      }
    }

    @Test
    void anAbsoluteFormTargetIsResolvedToAPathAndRouted() throws IOException {
      AtomicInteger calls = new AtomicInteger();
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/users/{id}", (request, parameters) -> {
            calls.incrementAndGet();
            return text(parameters.get("id"));
          })
          .build())) {
        int port = start(adapter);

        // The control for the whole finding: the server resolves the target before looking for a context, and a target
        // that resolves to a path is dispatched normally — which is what makes the asterisk's failure a lookup failure
        // rather than a parsing refusal (ADR-0026 §2).
        String reply = raw(port, "GET http://127.0.0.1:" + port + "/users/42 HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + port + "\r\nConnection: close\r\n\r\n");

        assertThat(answers(reply)).isEqualTo(1);
        assertThat(reply).startsWith("HTTP/1.1 200 OK\r\n");
        assertThat(reply).endsWith("42");
        assertThat(calls).hasValue(1);
        // The other spelling, from the other writer: a response that went through an exchange has the canonicalized
        // lowercase "l" in its length field, and the Date that the pre-context reply writer never sets. Both are what
        // distinguishes this answer from the asterisk's above, at the same status code and the same port.
        assertThat(reply).contains("\r\nContent-length: 2\r\n");
        assertThat(reply).doesNotContain("Content-Length:");
        assertThat(reply).contains("\r\nDate: ");
      }
    }
  }

  /**
   * The invocation counter the phase's §6 asks for, on the side of the boundary the asterisk does reach.
   *
   * <p>The socket tests above already show zero handler calls, and they would keep showing zero if this module grew code
   * that answered {@code OPTIONS *} — because on this server the request is refused before a context is looked up, no
   * mutation of this adapter's routing can make a socket call a resource handler for the asterisk. The counter that can
   * fail is the one taken where the asterisk is expressible: a {@link Request} whose path is {@code "*"} handed to the
   * route table an application built. That is the same table a second adapter — one whose server does deliver the
   * target — would dispatch through, so pinning it here is what makes ADR-0026 §3's "not supported, and not by the route
   * table either" a tested statement rather than a comment.
   */
  @Nested
  @DisplayName("the asterisk never reaches a resource handler, counting calls")
  class InvocationCounter {

    @Test
    void matchingAnAsteriskRequestCallsNoHandlerAndNamesNoMethods() {
      AtomicInteger calls = new AtomicInteger();
      Router router = Router.builder()
          .route(HttpMethod.OPTIONS, "/", (request, parameters) -> {
            calls.incrementAndGet();
            return text("root");
          })
          .route(HttpMethod.GET, "/users/{id}", (request, parameters) -> {
            calls.incrementAndGet();
            return text(parameters.get("id"));
          })
          .build();

      Request optionsStar = new Request(HttpMethod.OPTIONS, "*", null, Headers.empty(), null);
      Optional<RouteMatch> match = router.match(optionsStar);

      // Counted by invoking whatever the routing answer hands back, which is what an adapter does: a table that answered
      // the asterisk with a resource's handler would show one call here even though the status and the body still looked
      // plausible on a socket.
      match.ifPresent(found -> found.handler().handle(optionsStar, found.parameters()));
      assertThat(calls).as("an OPTIONS * request must reach no resource handler").hasValue(0);
      assertThat(match).as("no route answers the server-wide target").isEmpty();
      assertThat(router.methodsFor("*")).as("the route table holds no server-wide method set").isEmpty();
    }
  }
}
