package com.barezen.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.barezen.web.Handler;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SecurityMiddleware")
class SecurityMiddlewareTest {

  private static final Principal ADA = new Principal("ada");
  private static final Principal GRACE = new Principal("grace");

  private static Request requestWithAuthorization(String headerValue) {
    return new Request(HttpMethod.GET, "/secure", null,
        Headers.of("Authorization", headerValue), new byte[0]);
  }

  private static Request anonymousRequest() {
    return new Request(HttpMethod.GET, "/secure", null, Headers.empty(), new byte[0]);
  }

  private static Response ok(String body) {
    return new Response(200, Headers.empty(), body.getBytes(StandardCharsets.UTF_8));
  }

  private static String bodyOf(Response response) {
    return new String(response.body(), StandardCharsets.UTF_8);
  }

  /** A handler that is both: the middleware must detect the {@link SecuredHandler} half and deliver the context. */
  private static final class EchoPrincipal implements Handler, SecuredHandler {

    @Override
    public Response handle(Request request, Map<String, String> parameters) {
      return ok("plain");
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters, SecurityContext context) {
      return ok(context.principal().map(Principal::name).orElse("nobody"));
    }
  }

  @Nested
  @DisplayName("the five states")
  class FiveStates {

    @Test
    @DisplayName("authenticated and allowed reaches the handler")
    void authenticatedAndAllowed() {
      AtomicInteger calls = new AtomicInteger();
      Handler handler = SecurityMiddleware.wrap(
          Authentication.bearer("Bearer", "good", ADA),
          Authorization.allowAll(),
          (request, parameters) -> {
            calls.incrementAndGet();
            return ok("answered");
          });
      Response response = handler.handle(requestWithAuthorization("Bearer good"), Map.of());
      assertThat(response.status()).isEqualTo(200);
      assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("anonymous answers 401 and the handler is never called")
    void anonymousIsRefused() {
      AtomicInteger calls = new AtomicInteger();
      Handler handler = SecurityMiddleware.wrap(
          Authentication.bearer("Bearer", "good", ADA),
          (request, parameters) -> {
            calls.incrementAndGet();
            return ok("answered");
          });
      Response response = handler.handle(anonymousRequest(), Map.of());
      assertThat(response.status()).isEqualTo(401);
      assertThat(bodyOf(response)).isEqualTo("Unauthorized");
      assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("a failed authentication answers 401 and the handler is never called")
    void failureIsRefused() {
      AtomicInteger calls = new AtomicInteger();
      Handler handler = SecurityMiddleware.wrap(
          Authentication.bearer("Bearer", "good", ADA),
          (request, parameters) -> {
            calls.incrementAndGet();
            return ok("answered");
          });
      Response response = handler.handle(requestWithAuthorization("Bearer wrong"), Map.of());
      assertThat(response.status()).isEqualTo(401);
      assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("authenticated but denied answers 403 and the handler is never called")
    void deniedIsForbidden() {
      AtomicInteger calls = new AtomicInteger();
      Handler handler = SecurityMiddleware.wrap(
          Authentication.bearer("Bearer", "good", ADA),
          Authorization.denyAll(),
          (request, parameters) -> {
            calls.incrementAndGet();
            return ok("answered");
          });
      Response response = handler.handle(requestWithAuthorization("Bearer good"), Map.of());
      assertThat(response.status()).isEqualTo(403);
      assertThat(bodyOf(response)).isEqualTo("Forbidden");
      assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("a throwing authentication answers 500 and the handler is never called")
    void brokenAuthenticationIsAServerError() {
      AtomicInteger calls = new AtomicInteger();
      Handler handler = SecurityMiddleware.wrap(
          credential -> {
            throw new IllegalStateException("credential store unreachable");
          },
          (request, parameters) -> {
            calls.incrementAndGet();
            return ok("answered");
          });
      Response response = handler.handle(requestWithAuthorization("Bearer anything"), Map.of());
      assertThat(response.status()).isEqualTo(500);
      assertThat(bodyOf(response)).isEqualTo("Internal Server Error");
      assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("a throwing authorization answers 500 and the handler is never called")
    void brokenAuthorizationIsAServerError() {
      AtomicInteger calls = new AtomicInteger();
      Handler handler = SecurityMiddleware.wrap(
          Authentication.bearer("Bearer", "good", ADA),
          (principal, required) -> {
            throw new IllegalStateException("policy service unreachable");
          },
          (request, parameters) -> {
            calls.incrementAndGet();
            return ok("answered");
          });
      Response response = handler.handle(requestWithAuthorization("Bearer good"), Map.of());
      assertThat(response.status()).isEqualTo(500);
      assertThat(calls).hasValue(0);
    }
  }

  @Nested
  @DisplayName("the security context")
  class ContextDelivery {

    @Test
    @DisplayName("a SecuredHandler receives the context of the request it answers")
    void securedHandlerReceivesTheContext() {
      Handler handler = SecurityMiddleware.wrap(
          Authentication.bearer("Bearer", "good", ADA), new EchoPrincipal());
      Response response = handler.handle(requestWithAuthorization("Bearer good"), Map.of());
      assertThat(bodyOf(response)).isEqualTo("ada");
    }

    @Test
    @DisplayName("a plain Handler answers without ever seeing a context, exactly as before")
    void plainHandlerSeesNoContext() {
      Handler handler = SecurityMiddleware.wrap(
          Authentication.bearer("Bearer", "good", ADA),
          (request, parameters) -> ok("answered"));
      Response response = handler.handle(requestWithAuthorization("Bearer good"), Map.of());
      assertThat(response.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("one request's identity does not leak into the next on the same middleware")
    void noIdentityLeakBetweenRequests() {
      Authentication authentication = credential -> {
        if (credential != null && credential.value().equals("ada-token")) {
          return AuthenticationResult.authenticated(ADA);
        }
        if (credential != null && credential.value().equals("grace-token")) {
          return AuthenticationResult.authenticated(GRACE);
        }
        return AuthenticationResult.anonymous();
      };
      Handler handler = SecurityMiddleware.wrap(authentication, new EchoPrincipal());

      assertThat(bodyOf(handler.handle(requestWithAuthorization("Bearer ada-token"), Map.of()))).isEqualTo("ada");
      // The next request offers no credential: if identity leaked, it would answer "ada".
      Response second = handler.handle(anonymousRequest(), Map.of());
      assertThat(second.status()).isEqualTo(401);
      assertThat(bodyOf(handler.handle(requestWithAuthorization("Bearer grace-token"), Map.of()))).isEqualTo("grace");
    }
  }

  @Nested
  @DisplayName("credential extraction")
  class CredentialExtraction {

    @Test
    @DisplayName("a header without a scheme is read as a Bearer value")
    void schemelessHeaderDefaultsToBearer() {
      Handler handler = SecurityMiddleware.wrap(
          Authentication.bearer("Bearer", "raw-token", ADA),
          (request, parameters) -> ok("answered"));
      Response response = handler.handle(requestWithAuthorization("raw-token"), Map.of());
      assertThat(response.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("the header name match is case-insensitive, as Headers promises")
    void headerNameIsCaseInsensitive() {
      Request request = new Request(HttpMethod.GET, "/secure", null,
          Headers.of("authorization", "Bearer good"), new byte[0]);
      Handler handler = SecurityMiddleware.wrap(
          Authentication.bearer("Bearer", "good", ADA),
          (request2, parameters) -> ok("answered"));
      assertThat(handler.handle(request, Map.of()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a scheme and value split on the first space only: values may contain spaces")
    void splitsOnTheFirstSpace() {
      Handler handler = SecurityMiddleware.wrap(
          Authentication.bearer("Bearer", "token with spaces", ADA),
          (request, parameters) -> ok("answered"));
      Response response = handler.handle(requestWithAuthorization("Bearer token with spaces"), Map.of());
      assertThat(response.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a trailing space ends the scheme and opens an empty value, which a real header can carry")
    void emptyValueAfterTheScheme() {
      Handler handler = SecurityMiddleware.wrap(
          Authentication.bearer("Bearer", "", ADA),
          (request, parameters) -> ok("answered"));
      // "Bearer " is scheme "Bearer" and value "": the space separates them even when nothing follows.
      Response response = handler.handle(requestWithAuthorization("Bearer "), Map.of());
      assertThat(response.status()).isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("the table-driven authorization")
  class TableAuthorization {

    private final Permission read = Permission.of("user:read");
    private final Permission write = Permission.of("user:write");

    @Test
    @DisplayName("a named permission the identity holds reaches the handler")
    void grantedPermissionAllows() {
      Authorization authorization = Authorization.of(Map.of(ADA, java.util.Set.of(read)));
      Handler handler = SecurityMiddleware.wrap(
          Authentication.bearer("Bearer", "good", ADA), authorization, read,
          (request, parameters) -> ok("answered"));
      assertThat(handler.handle(requestWithAuthorization("Bearer good"), Map.of()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a named permission the identity does not hold answers 403")
    void ungrantedPermissionDenies() {
      Authorization authorization = Authorization.of(Map.of(ADA, java.util.Set.of(read)));
      AtomicInteger calls = new AtomicInteger();
      Handler handler = SecurityMiddleware.wrap(
          Authentication.bearer("Bearer", "good", ADA), authorization, write,
          (request, parameters) -> {
            calls.incrementAndGet();
            return ok("answered");
          });
      Response response = handler.handle(requestWithAuthorization("Bearer good"), Map.of());
      assertThat(response.status()).isEqualTo(403);
      assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("the ANY permission is what the three-argument wrap asks the policy for")
    void defaultPermissionIsAny() {
      Authorization authorization = Authorization.of(Map.of(ADA, java.util.Set.of(Permission.ANY)));
      Handler handler = SecurityMiddleware.wrap(
          Authentication.bearer("Bearer", "good", ADA), authorization,
          (request, parameters) -> ok("answered"));
      assertThat(handler.handle(requestWithAuthorization("Bearer good"), Map.of()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("refuses a null required permission")
    void refusesNullPermission() {
      Handler delegate = (request, parameters) -> ok("answered");
      assertThatNullPointerException().isThrownBy(() ->
          SecurityMiddleware.wrap(Authentication.anonymous(), Authorization.allowAll(), null, delegate));
    }
  }

  @Nested
  @DisplayName("construction")
  class Construction {

    @Test
    @DisplayName("refuses null collaborators: a middleware without them cannot decide anything")
    void refusesNull() {
      Handler delegate = (request, parameters) -> ok("answered");
      assertThatNullPointerException().isThrownBy(() ->
          SecurityMiddleware.wrap(null, Authorization.allowAll(), delegate));
      assertThatNullPointerException().isThrownBy(() ->
          SecurityMiddleware.wrap(Authentication.anonymous(), null, delegate));
      assertThatNullPointerException().isThrownBy(() ->
          SecurityMiddleware.wrap(Authentication.anonymous(), Authorization.allowAll(), null));
      assertThatNullPointerException().isThrownBy(() ->
          SecurityMiddleware.wrap(null, delegate));
    }
  }
}
