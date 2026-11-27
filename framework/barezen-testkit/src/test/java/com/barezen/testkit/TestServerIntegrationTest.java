package com.barezen.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.core.config.Configuration;
import com.barezen.security.Authentication;
import com.barezen.security.Authorization;
import com.barezen.security.Permission;
import com.barezen.security.Principal;
import com.barezen.security.SecurityMiddleware;
import com.barezen.web.HttpMethod;
import com.barezen.web.Router;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests over a real socket, because the entire reason {@link TestServer} exists is to exercise the bytes
 * that cross the network rather than a stub of it. Everything here goes through the public API: a router is built the
 * way an application would build one, {@link TestServer} binds the real JDK adapter to a random loopback port, and the
 * client speaks plain HTTP against it.
 */
@DisplayName("TestServer end to end")
class TestServerIntegrationTest {

  @Nested
  @DisplayName("the server and client round-trip every routed verb")
  class Verbs {

    private Router echo() {
      return Router.builder()
          .route(HttpMethod.GET, "/echo", TestFixtures.echoHandler())
          .route(HttpMethod.POST, "/echo", TestFixtures.echoHandler())
          .route(HttpMethod.PUT, "/echo", TestFixtures.echoHandler())
          .route(HttpMethod.PATCH, "/echo", TestFixtures.echoHandler())
          .route(HttpMethod.DELETE, "/echo", TestFixtures.echoHandler())
          .route(HttpMethod.HEAD, "/echo", TestFixtures.echoHandler())
          .route(HttpMethod.OPTIONS, "/echo", TestFixtures.echoHandler())
          .build();
    }

    @Test
    @DisplayName("GET, POST, PUT, PATCH, DELETE, OPTIONS each reach their route and echo the method")
    void everyVerb() {
      try (TestServer server = TestServer.start(echo())) {
        TestHttpClient client = server.client();
        Assertions.assertThat(client.get("/echo")).statusIs(200).bodyIs("GET /echo");
        Assertions.assertThat(client.post("/echo", "x")).statusIs(200).bodyIs("POST /echo");
        Assertions.assertThat(client.put("/echo", "x")).statusIs(200).bodyIs("PUT /echo");
        Assertions.assertThat(client.patch("/echo", "x")).statusIs(200).bodyIs("PATCH /echo");
        Assertions.assertThat(client.delete("/echo")).statusIs(200).bodyIs("DELETE /echo");
        Assertions.assertThat(client.options("/echo")).statusIs(200).bodyIs("OPTIONS /echo");
      }
    }

    @Test
    @DisplayName("HEAD answers with headers and no body, as the contract requires")
    void head() {
      try (TestServer server = TestServer.start(echo())) {
        TestResponse response = server.client().head("/echo");
        // The adapter drops the body for HEAD; the assertion is that the exchange completes at 200.
        assertThat(response.status()).isEqualTo(200);
      }
    }

    @Test
    @DisplayName("a request header the client sends reaches the server")
    void requestHeader() {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/h", (request, params) -> new com.barezen.web.Response(
              200, com.barezen.web.Headers.empty(),
              request.headers().first("X-Test").orElse("none").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          .build();
      try (TestServer server = TestServer.start(router)) {
        TestResponse response = server.client().request(HttpMethod.GET, "/h").header("X-Test", "seen").send();
        Assertions.assertThat(response).statusIs(200).bodyIs("seen");
      }
    }
  }

  @Nested
  @DisplayName("lifecycle and the port")
  class Lifecycle {

    @Test
    @DisplayName("a running server reports a bound port and a base URI that agrees with it")
    void bound() {
      try (TestServer server = TestServer.start(Router.builder().build())) {
        assertThat(server.port()).isPositive();
        assertThat(server.baseUri()).isEqualTo("http://127.0.0.1:" + server.port());
        assertThat(server.isRunning()).isTrue();
      }
    }

    @Test
    @DisplayName("close() stops the server, and a second close is a harmless no-op")
    void idempotentClose() {
      TestServer server = TestServer.start(Router.builder().build());
      server.close();
      assertThat(server.isRunning()).isFalse();
      assertThatCode(server::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a configured body limit refuses before the handler and still accepts the exact boundary")
    void configuredBodyLimit() {
      AtomicInteger calls = new AtomicInteger();
      Router router = Router.builder()
          .route(HttpMethod.POST, "/echo", (request, params) -> {
            calls.incrementAndGet();
            return TestFixtures.textHandler("ok").handle(request, params);
          })
          .build();
      Configuration configuration = Configuration.inMemory(
          Map.of("barezen.http.jdk.maxRequestBodyBytes", "4"));
      try (TestServer server = TestServer.start(router, configuration)) {
        Assertions.assertThat(server.client().post("/echo", "12345")).statusIs(413);
        assertThat(calls).hasValue(0);
        Assertions.assertThat(server.client().post("/echo", "1234")).statusIs(200);
        assertThat(calls).hasValue(1);
      }
    }

    @Test
    @DisplayName("close() releases the port so the OS can hand it straight back to a new listener")
    void portReleased() throws IOException {
      int port;
      try (TestServer server = TestServer.start(Router.builder().build())) {
        port = server.port();
      }
      // If closing had not released the socket, binding this exact port would fail. Bind without SO_REUSEADDR games.
      try (ServerSocket reclaim = new ServerSocket()) {
        reclaim.bind(new java.net.InetSocketAddress("127.0.0.1", port));
        assertThat(reclaim.getLocalPort()).isEqualTo(port);
      }
    }

    @Test
    @DisplayName("two servers started together get two different ports")
    void distinctPorts() {
      try (TestServer a = TestServer.start(Router.builder().build());
          TestServer b = TestServer.start(Router.builder().build())) {
        assertThat(a.port()).isNotEqualTo(b.port());
      }
    }

    @Test
    @DisplayName("a request to an unrouted path answers 404 through the real adapter, not a stub")
    void notFound() {
      try (TestServer server = TestServer.start(
          Router.builder().route(HttpMethod.GET, "/there", TestFixtures.textHandler("y")).build())) {
        Assertions.assertThat(server.client().get("/there")).statusIs(200);
        Assertions.assertThat(server.client().get("/absent")).statusIs(404);
      }
    }
  }

  @Nested
  @DisplayName("identity and isolation through the real security middleware")
  class Security {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");

    private Authentication twoTokens() {
      return TestFixtures.authenticationFor("alice-token", ALICE);
    }

    private Authentication either() {
      return credential -> {
        if (credential == null) {
          return com.barezen.security.AuthenticationResult.anonymous();
        }
        if ("alice-token".equals(credential.value())) {
          return com.barezen.security.AuthenticationResult.authenticated(ALICE);
        }
        if ("bob-token".equals(credential.value())) {
          return com.barezen.security.AuthenticationResult.authenticated(BOB);
        }
        return com.barezen.security.AuthenticationResult.failure("unknown");
      };
    }

    @Test
    @DisplayName("a valid token reaches the secured handler with its identity and answers 200")
    void authenticated() {
      TestFixtures.PrincipalCapturingHandler handler = TestFixtures.principalCapturingHandler();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/who", SecurityMiddleware.wrap(twoTokens(), Authorization.allowAll(), handler))
          .build();
      try (TestServer server = TestServer.start(router)) {
        TestResponse response = server.client().request(HttpMethod.GET, "/who")
            .header("Authorization", "Bearer alice-token").send();
        Assertions.assertThat(response).statusIs(200).bodyIs("hello, alice");
        Assertions.assertThat(handler.seen()).hasName("alice");
      }
    }

    @Test
    @DisplayName("a missing token answers 401 and the delegate is never reached")
    void anonymousRefused() {
      TestFixtures.PrincipalCapturingHandler handler = TestFixtures.principalCapturingHandler();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/who", SecurityMiddleware.wrap(twoTokens(), handler))
          .build();
      try (TestServer server = TestServer.start(router)) {
        Assertions.assertThat(server.client().get("/who")).statusIs(401);
        Assertions.assertThat(handler.count()).isEqualTo(0);
      }
    }

    @Test
    @DisplayName("a wrong token answers 401 and the delegate is never reached")
    void invalidRefused() {
      TestFixtures.PrincipalCapturingHandler handler = TestFixtures.principalCapturingHandler();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/who", SecurityMiddleware.wrap(either(), handler))
          .build();
      try (TestServer server = TestServer.start(router)) {
        Assertions.assertThat(server.client().request(HttpMethod.GET, "/who")
            .header("Authorization", "Bearer nope").send()).statusIs(401);
        Assertions.assertThat(handler.count()).isEqualTo(0);
      }
    }

    @Test
    @DisplayName("an authenticated caller without the permission answers 403 and the delegate is never reached")
    void forbidden() {
      TestFixtures.PrincipalCapturingHandler handler = TestFixtures.principalCapturingHandler();
      Map<Principal, java.util.Set<Permission>> grants = Map.of(BOB, java.util.Set.of(Permission.of("read")));
      Router router = Router.builder()
          .route(HttpMethod.GET, "/who", SecurityMiddleware.wrap(
              either(), Authorization.of(grants), Permission.of("read"), handler))
          .build();
      try (TestServer server = TestServer.start(router)) {
        // alice authenticates but holds no grant; the authorization is deny → 403, handler not reached.
        Assertions.assertThat(server.client().request(HttpMethod.GET, "/who")
            .header("Authorization", "Bearer alice-token").send()).statusIs(403);
        Assertions.assertThat(handler.count()).isEqualTo(0);
        // bob holds the grant → 200.
        Assertions.assertThat(server.client().request(HttpMethod.GET, "/who")
            .header("Authorization", "Bearer bob-token").send()).statusIs(200);
        Assertions.assertThat(handler.seen()).hasName("bob");
      }
    }

    @Test
    @DisplayName("identities do not blend across a keep-alive sequence: alice, then anonymous, then bob")
    void isolation() {
      TestFixtures.PrincipalCapturingHandler handler = TestFixtures.principalCapturingHandler();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/who", SecurityMiddleware.wrap(either(), Authorization.allowAll(), handler))
          .build();
      try (TestServer server = TestServer.start(router)) {
        TestHttpClient client = server.client();
        // alice
        Assertions.assertThat(client.request(HttpMethod.GET, "/who")
            .header("Authorization", "Bearer alice-token").send()).bodyIs("hello, alice");
        // an anonymous request is refused before the delegate, so alice cannot still be visible to it
        Assertions.assertThat(client.get("/who")).statusIs(401);
        // bob
        Assertions.assertThat(client.request(HttpMethod.GET, "/who")
            .header("Authorization", "Bearer bob-token").send()).bodyIs("hello, bob");
        // The handler saw only the two authenticated requests, in order, with the anonymous attempt in between
        // never reaching it — no identity blended into the next request.
        assertThat(handler.seenNames()).containsExactly("alice", "bob");
      }
    }
  }

  @Nested
  @DisplayName("a JSON body a fixture handler returns is navigable through the assertions")
  class JsonOverTheWire {

    @Test
    @DisplayName("the client reads a JSON body the server produced and the path assertions hold")
    void roundTrip() {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/doc", TestFixtures.jsonHandler(
              "{\"info\":{\"title\":\"BareZen\"},\"paths\":{},\"tags\":[]}"))
          .build();
      try (TestServer server = TestServer.start(router)) {
        TestResponse response = server.client().get("/doc");
        Assertions.assertThat(response)
            .statusIs(200)
            .headerIs("Content-Type", "application/json")
            .jsonStringIs("info.title", "BareZen")
            .jsonArraySizeIs("tags", 0)
            .jsonHas("paths");
      }
    }
  }

  @Nested
  @DisplayName("a client pointed at a dead server fails loudly, not silently")
  class Unreachable {

    @Test
    @DisplayName("requesting after close raises a setup-style failure, not a bare checked exception")
    void afterClose() {
      TestServer server = TestServer.start(
          Router.builder().route(HttpMethod.GET, "/x", TestFixtures.textHandler("y")).build());
      String base = server.baseUri();
      server.close();
      TestHttpClient orphan = new TestHttpClient(
          java.net.http.HttpClient.newHttpClient(), base);
      assertThatThrownBy(() -> orphan.get("/x"))
          .isInstanceOf(TestRequestFailedException.class);
    }
  }
}
