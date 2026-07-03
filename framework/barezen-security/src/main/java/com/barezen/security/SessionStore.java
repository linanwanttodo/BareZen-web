package com.barezen.security;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where sessions live: an in-memory store keyed by token value.
 *
 * <p>A {@code SessionStore} is not a database, not a cache with TTL and not a distributed session service. It is the
 * simplest possible thing that answers the questions a session must answer: create, lookup, expire, invalidate,
 * rotate. The store is in-memory because a session is a server-local concern — a request that moves to another
 * server must re-authenticate, which is the same rule HTTP itself follows for connections.
 *
 * <p>The store is concurrent-safe: a token is created, looked up and invalidated without external synchronization,
 * because a session is a per-request concern and a server thread must not wait for another request's session lock.
 * The store does not persist across restarts; a server that restarts loses its sessions, which is the correct
 * behavior for a session — a session that survives a restart is a credential, not a session.
 *
 * <p>Time is read from a {@link Clock} injected at construction, never from the system clock, so tests can control
 * when a session expires without sleeping.
 */
public final class SessionStore {

  private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
  private final Clock clock;
  private final SecureRandom random;

  private SessionStore(Clock clock, SecureRandom random) {
    this.clock = clock;
    this.random = random;
  }

  /**
   * A store with the system clock and a fresh secure random source.
   *
   * @return the default store
   */
  public static SessionStore standard() {
    return new SessionStore(Clock.systemUTC(), new SecureRandom());
  }

  /**
   * A store with a specific clock and random source, for tests.
   *
   * @param clock the clock to read time from, never null
   * @param random the random source for token values, never null
   * @return a store with the given clock and random
   */
  public static SessionStore of(Clock clock, SecureRandom random) {
    Objects.requireNonNull(clock, "the clock; a session store without a clock cannot expire sessions");
    Objects.requireNonNull(random, "the random source; a session store with no randomness cannot generate tokens");
    return new SessionStore(clock, random);
  }

  /**
   * Create a new session for the given principal.
   *
   * @param principal the principal to create a session for, never null
   * @param duration how long the session is valid, must be positive
   * @return the created session, never null
   */
  public Session create(Principal principal, Duration duration) {
    Objects.requireNonNull(principal, "the principal to create a session for; without one there is no session");
    Objects.requireNonNull(duration, "the session duration; without one the session never expires");
    if (duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException(
          "a session's duration must be positive; zero or negative makes the session already expired");
    }
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    SessionToken token = SessionToken.of(value, duration, clock);
    Session session = new Session(token, principal);
    sessions.put(value, session);
    return session;
  }

  /**
   * Look up a session by token value.
   *
   * @param value the token value to look up, never null; a blank value is not a token, so it finds nothing
   * @return the session, or empty when the token is unknown or expired
   */
  public Optional<Session> lookup(String value) {
    Objects.requireNonNull(value, "the token value to look up; without one there is nothing to find");
    Session session = sessions.get(value);
    if (session == null) {
      return Optional.empty();
    }
    if (session.token().isExpired(clock.instant())) {
      sessions.remove(value);
      return Optional.empty();
    }
    return Optional.of(session);
  }

  /**
   * Invalidate a session by token value.
   *
   * @param value the token value to invalidate, never null
   * @return true if the session existed and was removed, false if it was not found
   */
  public boolean invalidate(String value) {
    Objects.requireNonNull(value, "the token value to invalidate; without one there is nothing to remove");
    return sessions.remove(value) != null;
  }

  /**
   * Rotate a session: invalidate the old token and create a new one for the same principal.
   *
   * @param value the token value to rotate, never null
   * @param duration how long the new session is valid, must be positive
   * @return the new session, or empty when the old token was not found
   */
  public Optional<Session> rotate(String value, Duration duration) {
    Objects.requireNonNull(value, "the token value to rotate; without one there is nothing to rotate");
    Session old = sessions.remove(value);
    if (old == null) {
      return Optional.empty();
    }
    return Optional.of(create(old.principal(), duration));
  }

  /**
   * How many sessions are currently stored, for tests and diagnostics.
   *
   * @return the number of sessions in the store
   */
  public int size() {
    return sessions.size();
  }

  /**
   * Remove all sessions, for tests.
   */
  public void clear() {
    sessions.clear();
  }

  /**
   * A session: a token plus the principal it belongs to.
   *
   * @param token the session token, never null
   * @param principal the principal the session belongs to, never null
   */
  public record Session(SessionToken token, Principal principal) {

    /**
     * A session with a token and a principal that are not null.
     *
     * @throws NullPointerException if {@code token} or {@code principal} is null
     */
    public Session {
      Objects.requireNonNull(token, "the session token; a session without a token is not a session");
      Objects.requireNonNull(principal, "the session principal; a session without a principal is not a session");
    }
  }
}
