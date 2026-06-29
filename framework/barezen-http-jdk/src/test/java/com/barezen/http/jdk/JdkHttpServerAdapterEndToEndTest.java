package com.barezen.http.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Response;
import com.barezen.web.Router;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The phase's claim, tested where it is actually made: a byte leaves a socket, the JDK's parser turns it into an
 * exchange, this module turns that into a {@link com.barezen.web.Request}, the route table answers, and the
 * {@link com.barezen.web.Response} comes back as bytes again (ADR-0021 §1, ADR-0022 §1).
 *
 * <p>Every test here goes through {@link RawHttp} rather than a Java HTTP client, because the assertions are about the
 * wire: a claim that {@code %2F} survived can only be made against the request line that was sent, and a claim that two
 * {@code Set-Cookie} lines stayed two can only be made against the field lines that arrived. A client that folds,
 * decodes or re-orders would test its own tolerance instead of this adapter's behaviour.
 *
 * <p>No test names a port. Each binds {@code 127.0.0.1:0}, reads the number back from
 * {@link JdkHttpServerAdapter#port()} and talks to that, which is why two of these classes can run in the same build
 * and on the same machine (ADR-0021 §5).
 *
 * <p>The handlers return the value they were routed to, as text, and nothing else — no JSON, no template. That is the
 * point: a response body this module could not have produced is a body that proves the handler, not the adapter,
 * answered.
 */
class JdkHttpServerAdapterEndToEndTest {

  /** The body the {@code /users/{id}} handler writes: the captured parameter, and nothing derived from the path. */
  private static final Response text(String body) {
    return new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8"),
        body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static int start(JdkHttpServerAdapter adapter) {
    adapter.start();
    return adapter.port().orElseThrow(() -> new AssertionError("a started adapter reports the port it bound"));
  }

  /**
   * The answer as text in the charset the handler wrote, rather than through {@link RawHttp}'s byte-preserving view.
   *
   * <p>These bodies are ASCII today so the two agree; the indirection exists so that a body with a non-ASCII character
   * in it is compared in UTF-8 rather than against the ISO-8859-1 mapping the fixture reads the wire through.
   */
  private static String body(RawHttp.Response answer) {
    return answer.bodyUtf8();
  }

  @Nested
  @DisplayName("a routed request answers with what routing captured")
  class Routing {

    @Test
    void getRootIsAnsweredByTheHandlerRegisteredForIt() throws IOException {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/", (request, parameters) -> text("root"))
          .build();
      try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router)) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/", List.of(), null);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).isEqualTo("root");
      }
    }

    @Test
    void aStaticRouteReachesItsOwnHandler() throws IOException {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/hello", (request, parameters) -> text("hello"))
          .route(HttpMethod.GET, "/other", (request, parameters) -> text("other"))
          .build();
      try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router)) {
        int port = start(adapter);

        assertThat(RawHttp.request(port, "GET", "/hello", List.of(), null).body()).isEqualTo("hello");
        assertThat(RawHttp.request(port, "GET", "/other", List.of(), null).body()).isEqualTo("other");
      }
    }

    /**
     * The phase's one required acceptance sentence, in the form the directive asks for:
     * {@code curl http://127.0.0.1:<ephemeral>/users/42} answers {@code 200} with the body {@code 42}.
     */
    @Test
    void aPathParameterReachesTheHandlerAsACapturedValue() throws IOException {
      AtomicReference<Map<String, String>> seen = new AtomicReference<>();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/users/{id}", (request, parameters) -> {
            seen.set(parameters);
            // Deliberately reads parameters, never request.path(): ADR-0020 §1 is the reason the second argument exists,
            // and a test that re-parsed the path here would pass without proving the parameter arrived.
            return text(parameters.get("id"));
          })
          .build();
      try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router)) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/users/42", List.of(), null);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).isEqualTo("42");
        assertThat(seen.get()).containsExactly(java.util.Map.entry("id", "42"));
      }
    }

    @Test
    void twoParametersArriveAtTheNamesTheirPatternDeclared() throws IOException {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/users/{userId}/posts/{postId}",
              (request, parameters) -> text(parameters.get("userId") + ":" + parameters.get("postId")))
          .build();
      try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router)) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/users/42/posts/7", List.of(), null);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).isEqualTo("42:7");
      }
    }

    @Test
    void theMostLiteralRouteWinsOnASocketThatCouldMatchBoth() throws IOException {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/users/{id}", (request, parameters) -> text("parameter:" + parameters.get("id")))
          .route(HttpMethod.GET, "/users/new", (request, parameters) -> text("literal"))
          .build();
      try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router)) {
        int port = start(adapter);

        assertThat(RawHttp.request(port, "GET", "/users/new", List.of(), null).body()).isEqualTo("literal");
        assertThat(RawHttp.request(port, "GET", "/users/42", List.of(), null).body()).isEqualTo("parameter:42");
      }
    }
  }

  @Nested
  @DisplayName("the raw target survives the trip (ADR-0022 §2, §3, §4)")
  class RawSemantics {

    @Test
    void anEncodedSlashStaysOneSegmentAndIsNotDecoded() throws IOException {
      // /users/a%2Fb must match /users/{id} with one captured segment holding "a%2Fb". A bridge that used
      // getPath() would see /users/a/b, which is two segments, and answer 404 — probe D in ADR-0022 §9.
      Router router = Router.builder()
          .route(HttpMethod.GET, "/users/{id}", (request, parameters) -> text(parameters.get("id")))
          .route(HttpMethod.GET, "/users/{a}/{b}", (request, parameters) -> text("two:" + parameters))
          .build();
      try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router)) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/users/a%2Fb", List.of(), null);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).isEqualTo("a%2Fb");
      }
    }

    @Test
    void anEncodedDotSegmentIsNotResolved() throws IOException {
      // ADR-0019 §5's test case on a real socket: /users/%2e%2e/admin is three segments whose second one holds "%2e%2e".
      // URI.normalize() would fold it to /admin before routing, which is the difference between serving a resource and
      // serving a traversal attempt — so the assertion is that the segments are still there to be captured.
      Router router = Router.builder()
          .route(HttpMethod.GET, "/users/{id}", (request, parameters) -> text("one:" + parameters.get("id")))
          .route(HttpMethod.GET, "/users/{a}/{b}", (request, parameters) ->
              text("two:" + parameters.get("a") + "," + parameters.get("b")))
          .route(HttpMethod.GET, "/admin", (request, parameters) -> text("admin"))
          .build();
      try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router)) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/users/%2e%2e/admin", List.of(), null);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).isEqualTo("two:%2e%2e,admin");
      }
    }

    @Test
    void aPlusInAPathIsAPlusAndNotASpace() throws IOException {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/users/{id}", (request, parameters) -> text(parameters.get("id")))
          .build();
      try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router)) {
        int port = start(adapter);

        assertThat(RawHttp.request(port, "GET", "/users/a+b", List.of(), null).body()).isEqualTo("a+b");
      }
    }

    @Test
    void percentEscapesAreNotFoldedIntoTheCharacterTheyName() throws IOException {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/users/ABC", (request, parameters) -> text("upper"))
          .route(HttpMethod.GET, "/users/{id}", (request, parameters) -> text("parameter:" + parameters.get("id")))
          .build();
      try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router)) {
        int port = start(adapter);

        // /users/%41BC would decode to /users/ABC. Raw matching means it reaches the parameter route instead, which
        // is the only answer that does not silently re-route a request to a resource nobody named (ADR-0019 §4).
        assertThat(RawHttp.request(port, "GET", "/users/ABC", List.of(), null).body()).isEqualTo("upper");
        assertThat(RawHttp.request(port, "GET", "/users/%41BC", List.of(), null).body()).isEqualTo("parameter:%41BC");
        assertThat(RawHttp.request(port, "GET", "/users/abc", List.of(), null).body()).isEqualTo("parameter:abc");
      }
    }

    @Test
    void aTrailingSlashIsADifferentResourceAndNeitherRedirects() throws IOException {
      try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0),
          Router.builder()
              .route(HttpMethod.GET, "/users", (request, parameters) -> text("collection"))
              .build())) {
        int port = start(adapter);

        assertThat(RawHttp.request(port, "GET", "/users", List.of(), null).body()).isEqualTo("collection");
        RawHttp.Response slashed = RawHttp.request(port, "GET", "/users/", List.of(), null);
        // 404, not 301: ADR-0019 §6 says a trailing slash is a different segment count, and a redirect is a policy
        // this contract has no type for. The body is the adapter's 404 and names no path.
        assertThat(slashed.status()).isEqualTo(404);
        assertThat(slashed.body()).doesNotContain("/users/");
      }
    }

    @Test
    void aQueryIsHandedOverRawAndAbsentStaysDistinctFromEmpty() throws IOException {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/users", (request, parameters) -> text(request.query().orElse("<none>")))
          .build();
      try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router)) {
        int port = start(adapter);

        assertThat(RawHttp.request(port, "GET", "/users?id=42&name=a+b", List.of(), null).body())
            .isEqualTo("id=42&name=a+b");
        assertThat(RawHttp.request(port, "GET", "/users?", List.of(), null).body()).isEmpty();
        assertThat(RawHttp.request(port, "GET", "/users", List.of(), null).body()).isEqualTo("<none>");
      }
    }

    @Test
    void thePathTheHandlerSeesIsTheTargetThatWasSent() throws IOException {
      AtomicReference<String> arrived = new AtomicReference<>();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/users", (request, parameters) -> {
            arrived.set(request.path());
            return text("ok");
          })
          .build();
      try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router)) {
        int port = start(adapter);

        RawHttp.request(port, "GET", "/users?a=1&b=2", List.of(), null);

        assertThat(arrived.get()).isEqualTo("/users");
      }
    }
  }
}
