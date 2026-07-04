# Testing security

`SecurityMiddleware` sits between the router and a handler. A test of a secured route needs three things — a token
fixture, a way to see which identity arrived, and a way to prove a refused request never reached the delegate — and
TestKit ships all three without touching security internals.

## The pieces

`TestFixtures.authenticationFor(token, principal)` builds a `Bearer` authentication that trusts exactly one token; it is
`Authentication.bearer` with the framework's own default scheme spelled out, and there is no secret to hide because the
"secret" is a fixed string written in the test. `TestFixtures.PrincipalCapturingHandler` implements both `Handler` and
`SecuredHandler`: the middleware hands the `SecurityContext` to the secured half, and the fixture records every
principal in order and refuses to answer through the plain half. A `Handler` that a middleware allowed without a
context is a middleware bug the tripwire surfaces, not a fixture that lies.

## The three outcomes, each in one assertion

```java
import com.barezen.security.Authentication;
import com.barezen.security.Authorization;
import com.barezen.security.Permission;
import com.barezen.security.Principal;
import com.barezen.security.SecurityMiddleware;
import com.barezen.testkit.Assertions;
import com.barezen.testkit.TestFixtures;
import com.barezen.testkit.TestServer;
import com.barezen.web.HttpMethod;
import com.barezen.web.Router;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WhoAmITest {

  private static final Principal ALICE = new Principal("alice");

  @Test
  void authenticated_reaches_the_delegate_anonymous_and_invalid_are_401() {
    Authentication authentication = TestFixtures.authenticationFor("alice-token", ALICE);
    TestFixtures.PrincipalCapturingHandler handler = TestFixtures.principalCapturingHandler();
    Router router = Router.builder()
        .route(HttpMethod.GET, "/who",
            SecurityMiddleware.wrap(authentication, Authorization.allowAll(), handler))
        .build();
    try (TestServer server = TestServer.start(router)) {
      var client = server.client();
      Assertions.assertThat(client.request(HttpMethod.GET, "/who")
          .header("Authorization", "Bearer alice-token").send())
          .statusIs(200).bodyIs("hello, alice");
      Assertions.assertThat(handler.seen()).hasName("alice");

      Assertions.assertThat(client.get("/who")).statusIs(401);       // anonymous
      Assertions.assertThat(client.request(HttpMethod.GET, "/who")
          .header("Authorization", "Bearer nope").send()).statusIs(401); // invalid
      Assertions.assertThat(handler.count()).isEqualTo(1);           // neither refusal reached the delegate
    }
  }

  @Test
  void a_principal_without_the_permission_is_refused_403_without_reaching_the_delegate() {
    Authentication authentication = TestFixtures.authenticationFor("alice-token", ALICE);
    TestFixtures.PrincipalCapturingHandler handler = TestFixtures.principalCapturingHandler();
    Router router = Router.builder()
        .route(HttpMethod.GET, "/who", SecurityMiddleware.wrap(
            authentication, Authorization.of(Map.of(ALICE, Set.of())), Permission.of("read"), handler))
        .build();
    try (TestServer server = TestServer.start(router)) {
      Assertions.assertThat(server.client().request(HttpMethod.GET, "/who")
          .header("Authorization", "Bearer alice-token").send())
          .statusIs(403);
      Assertions.assertThat(handler.count()).isEqualTo(0);
    }
  }
}
```

The two refusals the middleware must answer before the delegate — 401 for anonymous and 401 for invalid — are checked
against the same `handler.count()`, so a bug that ran the delegate anyway shows up as a count of 2 rather than as a
wrong body. The 403 case is where the identity exists and the policy says no; the assertion is on the count, because the
body on 403 is what the framework's default 403 says, and the phase's own claim (ADR-0037) is not about that body but
about *which* status and *whether* the delegate ran.

## Identity isolation across requests

The one thing a `ThreadLocal`-shaped security bug can look like in a test is a passing test — the leak happens after
the request that would have detected it. `PrincipalCapturingHandler.seenNames()` keeps every recorded identity in the
order the fixture observed them, so a leak becomes a `containsExactly` failure naming what should and should not be
there:

```java
@Test
void identities_do_not_blend_across_a_keep_alive_sequence() {
  Authentication authentication = credential -> switch (credential == null ? "" : credential.value()) {
    case "alice-token" -> com.barezen.security.AuthenticationResult.authenticated(new Principal("alice"));
    case "bob-token"   -> com.barezen.security.AuthenticationResult.authenticated(new Principal("bob"));
    default            -> com.barezen.security.AuthenticationResult.anonymous();
  };
  TestFixtures.PrincipalCapturingHandler handler = TestFixtures.principalCapturingHandler();
  Router router = Router.builder()
      .route(HttpMethod.GET, "/who",
          SecurityMiddleware.wrap(authentication, Authorization.allowAll(), handler))
      .build();
  try (TestServer server = TestServer.start(router)) {
    var client = server.client();
    Assertions.assertThat(client.request(HttpMethod.GET, "/who")
        .header("Authorization", "Bearer alice-token").send()).bodyIs("hello, alice");
    Assertions.assertThat(client.get("/who")).statusIs(401);
    Assertions.assertThat(client.request(HttpMethod.GET, "/who")
        .header("Authorization", "Bearer bob-token").send()).bodyIs("hello, bob");
    // seenNames() is a List<String>; TestKit has no List assertion, so this uses AssertJ's own assertThat.
    org.assertj.core.api.Assertions.assertThat(handler.seenNames()).containsExactly("alice", "bob");
  }
}
```

The anonymous request in the middle never reaches the delegate, so it contributes nothing to the sequence. The
`TestServer` client is HTTP/1.1 with keep-alive, so the three requests likely share a connection — the shape the
ADR-0034 "no `ThreadLocal`, ever" rule was written against. If a leak were possible in this framework, it would be
visible right here.

`Assertions.assertThat(Optional<Principal>).hasName(...)` and `.isAnonymous()` cover the single-request form; the
`seenNames` sequence covers the multi-request form. There is no global to inspect from a test, because none is allowed
to exist.

## What a real application swaps in

`authenticationFor` is the smallest `Authentication` that can pass a test; a production route wires a `SessionStore`
(ADR-0036) or a `PasswordHasher`-backed login (ADR-0035), and the same test shape still holds: hand the real
`Authentication` to `SecurityMiddleware.wrap`, keep the `PrincipalCapturingHandler`, keep the assertions. The fixture is
not part of the security module and does not shadow any of it — an integration test over a real authentication is the
same three lines, and nothing in TestKit knows whether the token came from a database or from a hard-coded string.
