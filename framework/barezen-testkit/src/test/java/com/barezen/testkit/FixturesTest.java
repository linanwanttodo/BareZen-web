package com.barezen.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.security.AuthenticationResult;
import com.barezen.security.Principal;
import com.barezen.security.SecurityContext;
import com.barezen.web.Handler;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the handler fixtures, driven directly (no socket) through the framework's own {@link Request} and
 * {@link Response} types. A fixture that returns the wrong bytes or miscounts a run is a fixture that will make some
 * future integration test lie, so the response shape and the counting are asserted here at the cheapest possible level.
 */
@DisplayName("TestFixtures")
class FixturesTest {

  private static Request get(String path) {
    return new Request(HttpMethod.GET, path, "", Headers.empty(), new byte[0]);
  }

  private static String body(Response response) {
    return new String(response.body(), StandardCharsets.UTF_8);
  }

  @Nested
  @DisplayName("plain handlers")
  class Plain {

    @Test
    @DisplayName("textHandler answers 200 with the given body and a text content type")
    void text() {
      Response response = TestFixtures.textHandler("ok").handle(get("/x"), Map.of());
      assertThat(response.status()).isEqualTo(200);
      assertThat(body(response)).isEqualTo("ok");
      assertThat(response.headers().first("Content-Type")).get().asString().startsWith("text/plain");
    }

    @Test
    @DisplayName("statusHandler answers the given status with no body")
    void status() {
      Response response = TestFixtures.statusHandler(204).handle(get("/x"), Map.of());
      assertThat(response.status()).isEqualTo(204);
      assertThat(response.body()).isEmpty();
    }

    @Test
    @DisplayName("echoHandler reproduces the method and path it was given")
    void echo() {
      Response response = TestFixtures.echoHandler().handle(get("/health"), Map.of());
      assertThat(body(response)).isEqualTo("GET /health");
    }

    @Test
    @DisplayName("jsonHandler answers application/json")
    void json() {
      Response response = TestFixtures.jsonHandler("{\"a\":1}").handle(get("/x"), Map.of());
      assertThat(response.headers().first("Content-Type")).get().isEqualTo("application/json");
      assertThat(body(response)).isEqualTo("{\"a\":1}");
    }
  }

  @Nested
  @DisplayName("counting")
  class Counting {

    @Test
    @DisplayName("a counting handler runs its delegate and records each invocation")
    void counts() {
      TestFixtures.CountingHandler handler = TestFixtures.countingHandler("ping");
      assertThat(handler.count()).isEqualTo(0);
      handler.handle(get("/x"), Map.of());
      handler.handle(get("/x"), Map.of());
      assertThat(handler.count()).isEqualTo(2);
      assertThat(body(handler.handle(get("/x"), Map.of()))).isEqualTo("ping");
      assertThat(handler.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("reset puts the count back to zero")
    void reset() {
      TestFixtures.CountingHandler handler = TestFixtures.countingHandler("ping");
      handler.handle(get("/x"), Map.of());
      handler.reset();
      assertThat(handler.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("two counting handlers do not share a counter — per-instance state, not a class-level one")
    void independentInstances() {
      TestFixtures.CountingHandler a = TestFixtures.countingHandler("x");
      a.handle(get("/x"), Map.of());
      a.handle(get("/x"), Map.of());
      TestFixtures.CountingHandler b = TestFixtures.countingHandler("y");
      // If the counter were static, b would open at a's two and this isolation test would be lying.
      assertThat(b.count()).isEqualTo(0);
      b.handle(get("/x"), Map.of());
      assertThat(b.count()).isEqualTo(1);
      assertThat(a.count()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("principal capture")
  class PrincipalCapture {

    private static SecurityContext context(Principal principal) {
      return new SecurityContext(
          principal == null ? AuthenticationResult.anonymous() : AuthenticationResult.authenticated(principal));
    }

    @Test
    @DisplayName("the secured half records the identity and greets it")
    void records() {
      TestFixtures.PrincipalCapturingHandler handler = TestFixtures.principalCapturingHandler();
      Response response = handler.handle(get("/who"), Map.of(), context(new Principal("ada")));
      assertThat(handler.seen()).map(Principal::name).contains("ada");
      assertThat(handler.count()).isEqualTo(1);
      assertThat(body(response)).isEqualTo("hello, ada");
    }

    @Test
    @DisplayName("an anonymous context records no principal and greets the anonymous caller")
    void anonymous() {
      TestFixtures.PrincipalCapturingHandler handler = TestFixtures.principalCapturingHandler();
      handler.handle(get("/who"), Map.of(), context(null));
      assertThat(handler.seen()).isEmpty();
      assertThat(body(handler.handle(get("/who"), Map.of(), context(null)))).isEqualTo("hello, anonymous");
      assertThat(handler.seenNames()).containsExactly("anonymous", "anonymous");
    }

    @Test
    @DisplayName("seenNames keeps the whole sequence, so a leak between requests is visible")
    void sequence() {
      TestFixtures.PrincipalCapturingHandler handler = TestFixtures.principalCapturingHandler();
      handler.handle(get("/who"), Map.of(), context(new Principal("alice")));
      handler.handle(get("/who"), Map.of(), context(new Principal("bob")));
      assertThat(handler.seenNames()).containsExactly("alice", "bob");
      assertThat(handler.seen()).map(Principal::name).contains("bob");
    }

    @Test
    @DisplayName("the plain half is a tripwire: reaching it without a context is a bug the fixture refuses")
    void tripwire() {
      Handler handler = TestFixtures.principalCapturingHandler();
      assertThatThrownBy(() -> handler.handle(get("/who"), Map.of()))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("SecurityContext");
    }

    @Test
    @DisplayName("two principal handlers do not share captured identity — per-instance, not class-level")
    void independentInstances() {
      TestFixtures.PrincipalCapturingHandler a = TestFixtures.principalCapturingHandler();
      a.handle(get("/who"), Map.of(),
          new SecurityContext(AuthenticationResult.authenticated(new Principal("ada"))));
      TestFixtures.PrincipalCapturingHandler b = TestFixtures.principalCapturingHandler();
      // A static lastSeen would make b report ada; per-instance state is what makes the isolation claim real.
      assertThat(b.seen()).isEmpty();
      assertThat(b.count()).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("authentications")
  class Authentications {

    @Test
    @DisplayName("authenticationFor accepts exactly its token and rejects others")
    void bearer() {
      AuthenticationResult ok = TestFixtures.authenticationFor("t0ken", new Principal("ada"))
          .authenticate(new com.barezen.security.Credential("Bearer", "t0ken"));
      assertThat(ok.isAuthenticated()).isTrue();
      AuthenticationResult bad = TestFixtures.authenticationFor("t0ken", new Principal("ada"))
          .authenticate(new com.barezen.security.Credential("Bearer", "wrong"));
      assertThat(bad.isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("anonymousAuthentication never authenticates")
    void anonymous() {
      assertThat(TestFixtures.anonymousAuthentication()
          .authenticate(new com.barezen.security.Credential("Bearer", "anything")).isAuthenticated()).isFalse();
    }
  }
}
