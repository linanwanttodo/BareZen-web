package com.barezen.plugins.auth;

import com.barezen.core.plugin.Plugin;
import com.barezen.core.plugin.PluginDescriptor;
import com.barezen.security.Authentication;
import com.barezen.security.AuthenticationResult;
import com.barezen.security.Credential;
import com.barezen.security.Principal;
import com.barezen.security.SessionStore;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The auth plugin: it turns an offer into an identity and an identity into a session, and it is the "Authentication
 * provider" Stage 2.13-C asks for — implemented as {@link Authentication}, so the middleware the security module
 * already ships wraps this plugin with no security change (ADR-0033 unchanged, and that is the point: adding a
 * plugin must not bend the decorator).
 *
 * <p><b>Two operations, deliberately split.</b> {@link #authenticate(Credential)} answers the *returning request*
 * question: the credential is a bearer session token, looked up in the {@link SessionStore}, and it answers with the
 * principal the session was issued to — nothing here touches a password or the user table. {@link
 * #issueSession(String, String)} answers the *first login* question and is the only half that verifies a password;
 * an application's own login route calls it and hands back the token. The reason they are not one method is the
 * {@link Credential} shape: it holds a single scheme and a single value, so a "username plus password" credential
 * would have to be a {@code username:password} string split on a delimiter — a username containing {@code :} then
 * authenticates as the wrong user, and that is a security bug dressed as parsing. Keeping issuance separate means
 * the two fields stay two arguments end to end.
 *
 * <p>The declared dependency on {@code "user"} (ADR-0016's descriptor, verified by ADR-0041's registration check):
 * the {@link PasswordVerifier} this plugin holds is, in a real assembly, the user plugin. The auth plugin does not
 * know that — it knows an interface (ADR-0042's direction), so the auth plugin compiles, runs and is unit-tested
 * with a lambda and no database at all. Registration order guarantees the user plugin exists before the auth
 * plugin's collaborators could be needed; {@code PluginRegistration.validate} enforces the edge is not missing or
 * cyclic.
 */
public final class AuthPlugin implements Plugin, Authentication {

  private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor("auth", List.of("user"));

  private final SessionStore sessions;
  private final PasswordVerifier passwords;
  private final String scheme;
  private final Duration lifetime;

  /**
   * @param sessions the store sessions are issued into and looked up from (ADR-0036)
   * @param passwords the port that answers "does this username match this password", supplied by the application
   *     from the user plugin (ADR-0042)
   * @param scheme the bearer scheme a returning request must present, e.g. {@code "Bearer"}
   * @param lifetime how long an issued session stays valid
   */
  public AuthPlugin(SessionStore sessions, PasswordVerifier passwords, String scheme, Duration lifetime) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.passwords = Objects.requireNonNull(passwords, "passwords");
    this.scheme = Objects.requireNonNull(scheme, "scheme");
    this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
    if (scheme.isBlank()) {
      throw new IllegalArgumentException("scheme must not be blank; it is what the credential is matched against");
    }
    if (lifetime.isZero() || lifetime.isNegative()) {
      throw new IllegalArgumentException("session lifetime must be positive; a zero-length session is never valid");
    }
  }

  @Override
  public PluginDescriptor descriptor() {
    return DESCRIPTOR;
  }

  /**
   * A returning request is authenticated by its session token, never by a password. The scheme is compared
   * case-insensitively as HTTP requires, while the token value is looked up exactly. The opaque value carries nothing
   * the framework trusts (ADR-0036), so an unknown or expired token is
   * {@link AuthenticationResult#anonymous()} — offered but not recognised, which the middleware answers 401 (ADR-0037).
   */
  @Override
  public AuthenticationResult authenticate(Credential credential) {
    if (credential == null || !scheme.equalsIgnoreCase(credential.scheme())) {
      return AuthenticationResult.anonymous();
    }
    return sessions.lookup(credential.value())
        .<AuthenticationResult>map(session -> AuthenticationResult.authenticated(session.principal()))
        .orElseGet(AuthenticationResult::anonymous);
  }

  /**
   * Verifies a username and password and, only on success, issues a session and returns it so the caller's login
   * route can hand the token to the client.
   *
   * <p>Returns empty for an unknown user or a wrong password alike — the port contract (ADR-0037's enumeration
   * rule) and never "which one", because telling an attacker that the username exists is a free recon result. The
   * password argument never leaves this method: it goes to {@link PasswordVerifier} and, on failure, nowhere; no
   * session, no principal, no stored row is touched on the reject path. The returned {@link SessionStore.Session}
   * carries both halves the caller needs — the opaque token to send back and the principal it now identifies.
   */
  public Optional<SessionStore.Session> issueSession(String username, String password) {
    if (username == null || password == null || !passwords.verify(username, password)) {
      return Optional.empty();
    }
    return Optional.of(sessions.create(new Principal(username), lifetime));
  }

  @Override
  public String toString() {
    return "AuthPlugin[scheme=" + scheme + "]"; // no session, no token, no password (ADR-0035/0036 disclosure rule)
  }
}
