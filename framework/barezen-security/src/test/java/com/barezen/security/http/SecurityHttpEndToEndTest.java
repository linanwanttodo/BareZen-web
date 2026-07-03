package com.barezen.security.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.barezen.http.jdk.JdkHttpServerAdapter;
import com.barezen.security.Authentication;
import com.barezen.security.AuthenticationResult;
import com.barezen.security.Authorization;
import com.barezen.security.Principal;
import com.barezen.security.SecuredHandler;
import com.barezen.security.SecurityContext;
import com.barezen.security.SecurityMiddleware;
import com.barezen.web.Handler;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.barezen.web.Router;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 2.11's end-to-end claim: security is a {@link Handler} decorator on a {@link Router}, and it holds up on a
 * real connection — including a keep-alive connection where three identities arrive back to back.
 *
 * <p>The reason this test runs on a socket rather than on a direct {@code handler.handle(...)} call is that a
 * connection reuses a thread: a leak of one request's identity into the next would not be visible if each request
 * were dispatched on a fresh calling thread. The JDK HTTP client keeps one connection alive across these requests,
 * which is exactly the surface the leak would appear on.
 *
 * <p>It uses {@link HttpClient} rather than the module's own raw-socket fixture because this test is not asserting
 * framing: 401/403/500's bytes are already pinned by Phase 2.10's socket suite. What it asserts is who the handler
 * saw, and that is a text question.
 */
@DisplayName("security over a real HTTP connection")
class SecurityHttpEndToEndTest {

  private static final Principal ADA = new Principal("ada");
  private static final Principal GRACE = new Principal("grace");

  private JdkHttpServerAdapter adapter;
  private HttpClient client;
  private int port;

  /**
   * A handler that is both a {@link Handler} and a {@link SecuredHandler}: the middleware must deliver the context
   * through the {@code SecuredHandler} half and answer with the identity it carries. The plain half throws, so a
   * middleware that failed to detect the {@code SecuredHandler} half fails this test loudly rather than quietly
   * answering {@code "plain"}.
   */
  private static final class WhoAmI implements Handler, SecuredHandler {

    @Override
    public Response handle(Request request, Map<String, String> parameters) {
      throw new AssertionError("the middleware must reach the SecuredHandler half, not the plain one");
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters, SecurityContext context) {
      String name = context.principal().map(Principal::name).orElseThrow(() ->
          new AssertionError("a SecuredHandler that reached here must have a principal"));
      return text(name);
    }
  }

  @BeforeEach
  void startServer() {
    Authentication authentication = credential -> {
      if (credential == null) {
        return AuthenticationResult.anonymous();
      }
      if ("Bearer".equals(credential.scheme()) && "ada-token".equals(credential.value())) {
        return AuthenticationResult.authenticated(ADA);
      }
      if ("Bearer".equals(credential.scheme()) && "grace-token".equals(credential.value())) {
        return AuthenticationResult.authenticated(GRACE);
      }
      return AuthenticationResult.failure("unknown token");
    };
    Handler plain = (request, parameters) -> text("plain");
    Router router = Router.builder()
        .route(HttpMethod.GET, "/secure", SecurityMiddleware.wrap(authentication, Authorization.allowAll(), new WhoAmI()))
        .route(HttpMethod.GET, "/denied",
            SecurityMiddleware.wrap(authentication, Authorization.denyAll(), new WhoAmI()))
        .route(HttpMethod.GET, "/plain", SecurityMiddleware.wrap(authentication, plain))
        .build();
    adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router);
    adapter.start();
    port = adapter.port().orElseThrow(() -> new AssertionError("a started adapter reports the port it bound"));
    client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  @AfterEach
  void stopServer() {
    if (adapter != null) {
      adapter.close();
    }
  }

  private static Response text(String body) {
    return new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8"),
        body.getBytes(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> get(String target, String bearerOrNull) {
    Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target)).GET();
    if (bearerOrNull != null) {
      request.header("Authorization", "Bearer " + bearerOrNull);
    }
    try {
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new AssertionError("the socket failed on " + target, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("the test was interrupted on " + target, e);
    }
  }

  @Test
  @DisplayName("an anonymous GET to a secured route answers 401 and the handler never sees the request")
  void anonymousIsRefused() {
    HttpResponse<String> response = get("/secure", null);
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).isEqualTo("Unauthorized");
  }

  @Test
  @DisplayName("a wrong token answers 401 — the failure is a refusal to authenticate, not a permission issue")
  void wrongTokenIsRefused() {
    HttpResponse<String> response = get("/secure", "wrong");
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).isEqualTo("Unauthorized");
  }

  @Test
  @DisplayName("a valid token answers 200 and the SecuredHandler receives the identity that token established")
  void validTokenReachesTheHandler() {
    HttpResponse<String> response = get("/secure", "ada-token");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("ada");
  }

  @Test
  @DisplayName("an authenticated request the authorization denies answers 403, not 401")
  void authenticatedButDenied() {
    HttpResponse<String> response = get("/denied", "ada-token");
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).isEqualTo("Forbidden");
  }

  @Test
  @DisplayName("a handler that is not a SecuredHandler still answers when the middleware allows it, with no context")
  void plainHandlerStillAnswers() {
    HttpResponse<String> response = get("/plain", "grace-token");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("plain");
  }

  /**
   * The identity-isolation proof this test exists for.
   *
   * <p>Five sequential requests on one client, sharing a keep-alive connection and therefore typically a server
   * thread. Any thread-local or static currentUser — the exact failure this design forbids — would show up here as
   * "ada" answering the second or a later request.
   */
  @Test
  @DisplayName("authenticated, then anonymous, then another identity: the connection does not carry identity across")
  void identityDoesNotLeakAcrossKeepAliveRequests() {
    assertThat(get("/secure", "ada-token").body()).isEqualTo("ada");
    assertThat(get("/secure", null).statusCode()).isEqualTo(401);
    assertThat(get("/secure", "grace-token").body()).isEqualTo("grace");
    // After grace, an anonymous request must still be refused, not answered as "grace".
    assertThat(get("/secure", null).statusCode()).isEqualTo(401);
    // And back to ada: no residue of grace survives.
    assertThat(get("/secure", "ada-token").body()).isEqualTo("ada");
  }
}
