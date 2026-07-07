package com.barezen.console;

import com.barezen.plugins.auth.AuthPlugin;
import com.barezen.plugins.user.UserRepository;
import com.barezen.security.Authorization;
import com.barezen.security.Principal;
import com.barezen.security.SecuredHandler;
import com.barezen.security.SecurityContext;
import com.barezen.security.SecurityMiddleware;
import com.barezen.security.SessionStore;
import com.barezen.web.Handler;
import com.barezen.web.Headers;
import com.barezen.web.Request;
import com.barezen.web.Response;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The session endpoints: sign in, sign out, and ask who the server thinks you are.
 *
 * <p>A bearer token, not a cookie. {@link SecurityMiddleware} reads its credential from {@code Authorization} and
 * offers no other source (its {@code credentialFrom} is private by decision), and widening that seam for a demo page
 * would be the framework growing a capability because a consumer wanted it. So the page keeps the token in memory and
 * sends it on each {@code fetch} — which also means there is no cookie to forge a request with, and therefore no CSRF
 * surface to reason about. The cost is stated in the UI rather than hidden: a reload loses the session and asks you to
 * sign in again, because a token parked in {@code localStorage} would be a token handed to any script that ever runs on
 * this origin.
 *
 * <p>Sign-out needs the token itself, not just the identity, so its handler reads the request's own
 * {@code Authorization} field: {@link SessionStore#invalidate(String)} is keyed by value, and a {@link SecurityContext}
 * carries a principal rather than the credential that produced it. Everything that only needs an identity is
 * authenticated by {@link AuthPlugin} — the shipped plugin, not a hand-written {@code Authentication} beside it.
 */
final class Sessions {

  private Sessions() {
  }

  static Handler signIn(AuthPlugin auth) {
    return (request, parameters) -> {
      String username = Json.formValue(request.body(), "username");
      String password = Json.formValue(request.body(), "password");
      if (username == null || password == null || username.isBlank()) {
        return json(400, Json.field("error", "username and password are both required"));
      }
      return auth.issueSession(username, password)
          .map(session -> {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("token", session.token().value());
            fields.put("user", session.principal().name());
            return json(200, Json.object(fields));
          })
          // 401, not 403: the password did not produce an identity, so there is no one to deny (ADR-0037).
          .orElseGet(() -> json(401, Json.field("error", "credentials rejected")));
    };
  }

  static Handler signOut(AuthPlugin auth, SessionStore sessions) {
    return SecurityMiddleware.wrap(auth, Authorization.allowAll(), new SignOut(sessions));
  }

  static Handler whoAmI(AuthPlugin auth, UserRepository users) {
    return SecurityMiddleware.wrap(auth, Authorization.allowAll(), new WhoAmI(users));
  }

  private static final class SignOut implements Handler, SecuredHandler {

    private final SessionStore sessions;

    SignOut(SessionStore sessions) {
      this.sessions = sessions;
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters) {
      throw new AssertionError("SignOut is registered under SecurityMiddleware, which delivers the secured half");
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters, SecurityContext context) {
      token(request).ifPresent(sessions::invalidate);
      // 204 says "gone" with no body, and the contract refuses a body here rather than dropping one at the socket.
      return new Response(204, Headers.empty(), new byte[0]);
    }
  }

  private static final class WhoAmI implements Handler, SecuredHandler {

    private final UserRepository users;

    WhoAmI(UserRepository users) {
      this.users = users;
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters) {
      throw new AssertionError("WhoAmI is registered under SecurityMiddleware, which delivers the secured half");
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters, SecurityContext context) {
      String name = context.principal().map(Principal::name).orElse("anonymous");
      Map<String, String> fields = new LinkedHashMap<>();
      fields.put("user", name);
      fields.put("permissions", String.join(",", users.permissionsOf(name)));
      return json(200, Json.object(fields));
    }
  }

  private static Optional<String> token(Request request) {
    return request.headers().first("Authorization").map(value -> {
      String prefix = "Bearer ";
      return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    });
  }

  /**
   * A JSON answer that says {@code no-store}. A sign-in response carries a session token, and a cache — a proxy, a
   * browser's own — that kept it would be a second copy of a credential the application cannot revoke.
   */
  private static Response json(int status, String body) {
    return new Response(status, Headers.of("Content-Type", "application/json")
        .with("Cache-Control", "no-store"), body.getBytes(StandardCharsets.UTF_8));
  }
}
