package com.barezen.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * A session token: an opaque value that identifies a session, with an expiration time.
 *
 * <p>A token is not a JWT, not a session cookie and not a session itself. It is the identifier a client presents to
 * prove it has a session, plus the time at which that proof stops being valid. The session data — the principal, the
 * permissions, the application state — lives in a {@link SessionStore}, which the token is the key to. This
 * separation is deliberate: a token that carries its own data is a JWT, and a JWT cannot be revoked without a
 * distributed revocation list, which is a problem this framework does not have and does not want.
 *
 * <p>The value is a random string, not a sequential id, so a token cannot be guessed from a previous one. The
 * expiration is an {@link Instant}, not a duration, so a token's validity is a fact, not a computation that drifts
 * with the clock.
 *
 * @param value the token value, never null and never blank
 * @param expiresAt when the token stops being valid, never null
 */
public record SessionToken(String value, Instant expiresAt) {

  /**
   * A token with a value that is not blank and an expiration that is not null.
   *
   * @throws NullPointerException if {@code value} or {@code expiresAt} is null
   * @throws IllegalArgumentException if {@code value} is blank — a blank token is indistinguishable from no token,
   *     and no token is represented by the absence of a token, not by a token with no value
   */
  public SessionToken {
    Objects.requireNonNull(value, "the token value; without one there is no token to present");
    Objects.requireNonNull(expiresAt, "the expiration time; a token with no expiration never expires");
    if (value.isBlank()) {
      throw new IllegalArgumentException(
          "a token value must not be blank; an absent session has no token at all");
    }
  }

  /**
   * Whether this token has expired at the given instant.
   *
   * @param now the instant to check against, never null; injected rather than read from the system clock so tests can
   *     control time
   * @return true if the token's expiration is at or before {@code now}
   */
  public boolean isExpired(Instant now) {
    Objects.requireNonNull(now, "the instant to check against; without one expiration cannot be decided");
    return !expiresAt.isAfter(now);
  }

  /**
   * A token that expires in the given duration from now.
   *
   * @param value the token value, never null and never blank
   * @param duration how long the token is valid, must be positive
   * @param clock the clock to read the current time from, never null
   * @return a token that expires at {@code clock.instant().plus(duration)}
   */
  public static SessionToken of(String value, java.time.Duration duration, Clock clock) {
    Objects.requireNonNull(clock, "the clock; a token without a clock cannot know when it expires");
    Objects.requireNonNull(duration, "the validity duration; without one the token never expires");
    if (duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException(
          "a token's validity duration must be positive; zero or negative makes the token already expired");
    }
    return new SessionToken(value, clock.instant().plus(duration));
  }

  @Override
  public String toString() {
    return "SessionToken[expires=" + expiresAt + "]";
  }
}
