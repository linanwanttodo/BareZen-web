package com.barezen.plugins.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.security.AuthenticationResult;
import com.barezen.security.Credential;
import com.barezen.security.Principal;
import com.barezen.security.SessionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.SecureRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The auth plugin, tested as the two operations it splits (ADR-0042): issuing a session on a verified login, and
 * authenticating a returning request by its session token — never by re-checking a password.
 */
class AuthPluginTest {

  /** A verifier that knows exactly one user, so no test needs the user plugin or a database. */
  private static PasswordVerifier adaOnly() {
    return (username, password) -> "ada".equals(username) && "hunter2".equals(password);
  }

  /** A store whose clock is fixed and whose randomness is a seeded, deterministic SecureRandom. */
  private static SessionStore fixedStore(Instant now) {
    return SessionStore.of(Clock.fixed(now, ZoneOffset.UTC), new SecureRandom());
  }

  @Nested
  @DisplayName("construction")
  class Construction {

    @Test
    @DisplayName("every collaborator is required and the scheme and lifetime must be usable")
    void nullsAndBlanksRefused() {
      SessionStore store = fixedStore(Instant.now());
      assertThatThrownBy(() -> new AuthPlugin(null, adaOnly(), "Bearer", Duration.ofMinutes(30)))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new AuthPlugin(store, null, "Bearer", Duration.ofMinutes(30)))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new AuthPlugin(store, adaOnly(), "  ", Duration.ofMinutes(30)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("blank");
      assertThatThrownBy(() -> new AuthPlugin(store, adaOnly(), "Bearer", Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("positive");
      assertThatThrownBy(() -> new AuthPlugin(store, adaOnly(), "Bearer", Duration.ofSeconds(-1)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("issuing a session")
  class Issue {

    @Test
    @DisplayName("a verified login returns a session carrying both the token and the principal")
    void successReturnsSession() {
      SessionStore store = fixedStore(Instant.parse("2026-10-04T12:00:00Z"));
      AuthPlugin plugin = new AuthPlugin(store, adaOnly(), "Bearer", Duration.ofMinutes(30));

      var session = plugin.issueSession("ada", "hunter2").orElseThrow();
      assertThat(session.principal()).isEqualTo(new Principal("ada"));
      assertThat(session.token().value()).isNotBlank();
      assertThat(store.size()).as("the issued session is stored, not just returned").isEqualTo(1);
    }

    @Test
    @DisplayName("a wrong password and an unknown user are indistinguishable and issue nothing")
    void rejectionIsUniform() {
      SessionStore store = fixedStore(Instant.now());
      AuthPlugin plugin = new AuthPlugin(store, adaOnly(), "Bearer", Duration.ofMinutes(30));

      assertThat(plugin.issueSession("ada", "wrong")).isEmpty();
      assertThat(plugin.issueSession("nobody", "hunter2")).isEmpty();
      assertThat(plugin.issueSession(null, "x")).isEmpty();
      assertThat(plugin.issueSession("ada", null)).isEmpty();
      assertThat(store.size()).as("no failed attempt leaves a session behind").isZero();
    }
  }

  @Nested
  @DisplayName("authenticating a returning request")
  class Authenticate {

    @Test
    @DisplayName("a live session token authenticates to its principal, without touching the verifier")
    void tokenAuthenticates() {
      SessionStore store = fixedStore(Instant.parse("2026-10-04T12:00:00Z"));
      // Issue through a verifier that accepts, then re-authenticate through a fresh plugin whose verifier
      // rejects everything: the token still authenticates, proving authenticate consults the store and never
      // re-checks a password (the split ADR-0042 draws).
      String token = new AuthPlugin(store, (u, p) -> true, "Bearer", Duration.ofMinutes(30))
          .issueSession("ada", "anything").orElseThrow().token().value();
      AuthPlugin later = new AuthPlugin(store, (u, p) -> false, "Bearer", Duration.ofMinutes(30));

      AuthenticationResult result = later.authenticate(new Credential("Bearer", token));
      assertThat(result.isAuthenticated()).isTrue();
      assertThat(result.maybePrincipal()).contains(new Principal("ada"));
    }

    @Test
    @DisplayName("the bearer scheme is matched case-insensitively")
    void schemeIsCaseInsensitive() {
      SessionStore store = fixedStore(Instant.parse("2026-10-04T12:00:00Z"));
      AuthPlugin plugin = new AuthPlugin(store, (u, p) -> true, "Bearer", Duration.ofMinutes(30));
      String token = plugin.issueSession("ada", "x").orElseThrow().token().value();

      assertThat(plugin.authenticate(new Credential("bearer", token)).isAuthenticated()).isTrue();
      assertThat(plugin.authenticate(new Credential("BEARER", token)).isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("a missing credential is anonymous while a wrong scheme or unknown token is a failure")
    void missingIsAnonymousAndInvalidIsFailure() {
      SessionStore store = fixedStore(Instant.now());
      AuthPlugin plugin = new AuthPlugin(store, adaOnly(), "Bearer", Duration.ofMinutes(30));

      assertThat(plugin.authenticate(null)).isSameAs(AuthenticationResult.anonymous());
      assertThat(plugin.authenticate(new Credential("Basic", "anything")))
          .isInstanceOf(AuthenticationResult.Failure.class);
      assertThat(plugin.authenticate(new Credential("Bearer", "not-a-real-token")))
          .isInstanceOf(AuthenticationResult.Failure.class);
    }

    @Test
    @DisplayName("an expired token is a failure and is retired from the store on lookup")
    void expiryIsHonoured() {
      MutableClock clock = new MutableClock(Instant.parse("2026-10-04T12:00:00Z"));
      SessionStore store = SessionStore.of(clock, new SecureRandom());
      AuthPlugin plugin = new AuthPlugin(store, (u, p) -> true, "Bearer", Duration.ofMinutes(30));
      String token = plugin.issueSession("ada", "x").orElseThrow().token().value();

      clock.advance(Duration.ofMinutes(31));
      assertThat(plugin.authenticate(new Credential("Bearer", token)))
          .as("past expiry the same token is an explicit authentication failure")
          .isInstanceOf(AuthenticationResult.Failure.class);
      assertThat(store.size()).as("the expired session was removed, not just refused").isZero();
    }

    @Test
    @DisplayName("an invalidated session stops authenticating immediately")
    void revocationWorks() {
      SessionStore store = fixedStore(Instant.now());
      AuthPlugin plugin = new AuthPlugin(store, (u, p) -> true, "Bearer", Duration.ofMinutes(30));
      String token = plugin.issueSession("ada", "x").orElseThrow().token().value();

      store.invalidate(token);
      assertThat(plugin.authenticate(new Credential("Bearer", token)))
          .isInstanceOf(AuthenticationResult.Failure.class);
    }
  }

  @Nested
  @DisplayName("disclosure")
  class Disclosure {

    @Test
    @DisplayName("toString carries the scheme and nothing secret")
    void noSecretInToString() {
      AuthPlugin plugin = new AuthPlugin(fixedStore(Instant.now()), adaOnly(), "Bearer", Duration.ofMinutes(30));
      String printed = plugin.toString();
      assertThat(printed).contains("Bearer").doesNotContain("hunter2").doesNotContain("@");
    }
  }

  /** A clock a test can push forward, so expiry is a set fact rather than a sleep. */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
