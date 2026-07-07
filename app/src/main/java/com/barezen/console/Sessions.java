package com.barezen.console;

import com.barezen.plugins.auth.AuthPlugin;
import com.barezen.plugins.user.UserRepository;
import com.barezen.security.Authentication;
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
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The session endpoints: sign in, sign out, ask who you are, see when it ends, and trade the token for a new one.
 *
 * <p>A bearer token, not a cookie. {@link SecurityMiddleware} reads its credential from {@code Authorization} and
 * offers no other source (its {@code credentialFrom} is private by decision), and widening that seam for a demo page
 * would be the framework growing a capability because a consumer wanted it. So the page keeps the token in memory and
 * sends it on each {@code fetch} — which also means there is no cookie to forge a request with, and therefore no CSRF
 * surface to reason about. The cost is stated in the UI rather than hidden: a reload loses the session and asks you to
 * sign in again, because a token parked in {@code localStorage} would be a token handed to any script that ever runs on
 * this origin.
 *
 * <p>Sign-out and rotation need the token itself rather than just the identity, so those handlers read the request's own
 * {@code Authorization} field: {@link SessionStore#invalidate(String)} and {@link SessionStore#rotate(String, Duration)}
 * are keyed by value, and a {@link SecurityContext} carries a principal instead of the credential that produced it.
 * Rotation is the one worth watching from a browser: the old value answers 401 the moment the call returns, because the
 * store removed it — not because the client stopped sending it (ADR-0036).
 */
final class Sessions {

  /** How long a console session lives, and how far a rotation extends it. One number, in one place. */
  static final Duration LIFETIME = Duration.ofMinutes(30);

  private Sessions() {
  }

  static Handler signIn(AuthPlugin auth, Audit audit) {
    return (request, parameters) -> {
      String username = Json.formValue(request.body(), "username");
      String password = Json.formValue(request.body(), "password");
      if (username == null || password == null || username.isBlank()) {
        return json(400, Json.field("error", "username and password are both required"));
      }
      return auth.issueSession(username, password)
          .map(session -> {
            audit.publish(new Audit.SignedIn(session.principal().name()));
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("token", session.token().value());
            fields.put("user", session.principal().name());
            return json(200, Json.object(fields));
          })
          // 401, not 403: the password produced no identity, so there is no one to deny (ADR-0037). One answer for an
          // unknown user and a wrong password, because which half failed is a recon result.
          .orElseGet(() -> {
            audit.publish(new Audit.SignInRejected(username));
            return json(401, Json.field("error", "credentials rejected"));
          });
    };
  }

  static Handler signOut(Authentication authentication, SessionStore sessions, Audit audit) {
    return SecurityMiddleware.wrap(authentication, Authorization.allowAll(), new SignOut(sessions, audit));
  }

  static Handler whoAmI(Authentication authentication, UserRepository users) {
    return SecurityMiddleware.wrap(authentication, Authorization.allowAll(), new WhoAmI(users));
  }

  static Handler detail(Authentication authentication, SessionStore sessions, Clock clock) {
    return SecurityMiddleware.wrap(authentication, Authorization.allowAll(), new Detail(sessions, clock));
  }

  static Handler rotate(Authentication authentication, SessionStore sessions, Audit audit) {
    return SecurityMiddleware.wrap(authentication, Authorization.allowAll(), new Rotate(sessions, audit));
  }

  /** {@code GET /api/session} — the identity, and the grants that come with it. */
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
      fields.put("permissions", String.join(",", People.grantsOf(users, name)));
      return json(200, Json.object(fields));
    }
  }

  /**
   * {@code GET /api/session/detail} — when this session ends, as stated by the store rather than by the client.
   *
   * <p>The {@link Clock} arrives in the constructor (ADR-0013's assembly-time rule applied to a collaborator), so a test
   * can pin "1800 seconds left" to a fixed instant instead of asserting against wall time.
   */
  private static final class Detail implements Handler, SecuredHandler {

    private final SessionStore sessions;
    private final Clock clock;

    Detail(SessionStore sessions, Clock clock) {
      this.sessions = sessions;
      this.clock = clock;
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters) {
      throw new AssertionError("Detail is registered under SecurityMiddleware, which delivers the secured half");
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters, SecurityContext context) {
      Optional<SessionStore.Session> current = token(request).flatMap(sessions::lookup);
      if (current.isEmpty()) {
        // The middleware let the call through, so a missed lookup means the value went between the two reads. The
        // server states that as 401 rather than letting a client infer it from a clock of its own.
        return plain(401, "Unauthorized");
      }
      SessionStore.Session session = current.orElseThrow();
      long seconds = Duration.between(clock.instant(), session.token().expiresAt()).getSeconds();
      Map<String, String> fields = new LinkedHashMap<>();
      fields.put("user", session.principal().name());
      fields.put("expiresAt", session.token().expiresAt().toString());
      fields.put("secondsLeft", Long.toString(Math.max(seconds, 0)));
      fields.put("sessionsOpen", Integer.toString(sessions.size()));
      return json(200, Json.object(fields));
    }
  }

  /**
   * {@code POST /api/session/rotate} — a new token for the same principal, and the old value stops working.
   *
   * <p>The answer names both halves, because the page's job is to show what the contract does: the superseded value is
   * echoed so a reader can send it back on purpose and watch it be refused.
   */
  private static final class Rotate implements Handler, SecuredHandler {

    private final SessionStore sessions;
    private final Audit audit;

    Rotate(SessionStore sessions, Audit audit) {
      this.sessions = sessions;
      this.audit = audit;
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters) {
      throw new AssertionError("Rotate is registered under SecurityMiddleware, which delivers the secured half");
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters, SecurityContext context) {
      Optional<String> presented = token(request);
      if (presented.isEmpty()) {
        return plain(400, "no credential to rotate");
      }
      String previous = presented.orElseThrow();
      return sessions.rotate(previous, Sessions.LIFETIME)
          .map(session -> {
            audit.publish(new Audit.SessionRotated(session.principal().name()));
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("token", session.token().value());
            fields.put("user", session.principal().name());
            fields.put("superseded", previous);
            return json(200, Json.object(fields));
          })
          .orElseGet(() -> plain(401, "Unauthorized"));
    }
  }

  /** {@code DELETE /api/sessions} — revoke the value, then answer 204 with nothing to say. */
  private static final class SignOut implements Handler, SecuredHandler {

    private final SessionStore sessions;
    private final Audit audit;

    SignOut(SessionStore sessions, Audit audit) {
      this.sessions = sessions;
      this.audit = audit;
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters) {
      throw new AssertionError("SignOut is registered under SecurityMiddleware, which delivers the secured half");
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters, SecurityContext context) {
      token(request).ifPresent(value -> {
        sessions.invalidate(value);
        audit.publish(new Audit.SignedOut(context.principal().map(Principal::name).orElse("anonymous")));
      });
      // 204 says "gone" with no body, and the contract refuses a body here rather than dropping one at the socket.
      return new Response(204, Headers.empty(), new byte[0]);
    }
  }

  private static Optional<String> token(Request request) {
    return request.headers().first("Authorization").map(value -> {
      String prefix = "Bearer ";
      return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    });
  }

  /**
   * A JSON answer that says {@code no-store}. A sign-in response carries a session token, and a cache — a proxy, or a
   * browser's own — that kept it would hold a second copy of a credential the application cannot revoke.
   */
  private static Response json(int status, String body) {
    return new Response(status, Headers.of("Content-Type", "application/json").with("Cache-Control", "no-store"),
        body.getBytes(StandardCharsets.UTF_8));
  }

  private static Response plain(int status, String body) {
    return new Response(status, Headers.of("Content-Type", "text/plain; charset=utf-8"),
        body.getBytes(StandardCharsets.UTF_8));
  }
}
