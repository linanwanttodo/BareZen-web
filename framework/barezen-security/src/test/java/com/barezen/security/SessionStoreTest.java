package com.barezen.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SessionStore")
class SessionStoreTest {

  private static final Instant T0 = Instant.parse("2026-10-02T12:00:00Z");
  private static final Principal ADA = new Principal("ada");

  /** A clock the test advances by hand, so expiry is controlled, never slept for. */
  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  private static SessionStore storeAt(MutableClock clock) {
    return SessionStore.of(clock, new SecureRandom(new byte[32]));
  }

  @Test
  @DisplayName("create() produces a session whose lookup finds the same principal")
  void createAndLookup() {
    SessionStore store = storeAt(new MutableClock(T0));
    SessionStore.Session session = store.create(ADA, Duration.ofMinutes(30));
    assertThat(store.lookup(session.token().value())).contains(session);
    assertThat(store.lookup(session.token().value()).orElseThrow().principal()).isEqualTo(ADA);
  }

  @Test
  @DisplayName("two sessions for the same principal get different token values: a token is random, not sequential")
  void tokensAreUnique() {
    SessionStore store = storeAt(new MutableClock(T0));
    Set<String> values = new HashSet<>();
    for (int i = 0; i < 64; i++) {
      values.add(store.create(ADA, Duration.ofMinutes(30)).token().value());
    }
    assertThat(values).hasSize(64);
  }

  @Test
  @DisplayName("lookup() of an unknown token finds nothing")
  void lookupUnknown() {
    SessionStore store = storeAt(new MutableClock(T0));
    assertThat(store.lookup("no-such-token")).isEmpty();
  }

  @Test
  @DisplayName("an expired session is not found and is removed: expiry is not a filter over live state")
  void expiredSessionIsGone() {
    MutableClock clock = new MutableClock(T0);
    SessionStore store = storeAt(clock);
    SessionStore.Session session = store.create(ADA, Duration.ofMinutes(30));
    clock.advance(Duration.ofMinutes(31));
    assertThat(store.lookup(session.token().value())).isEmpty();
    assertThat(store.size()).isZero();
  }

  @Test
  @DisplayName("a session at its exact expiration instant is expired")
  void expirationBoundary() {
    MutableClock clock = new MutableClock(T0);
    SessionStore store = storeAt(clock);
    SessionStore.Session session = store.create(ADA, Duration.ofMinutes(30));
    clock.advance(Duration.ofMinutes(30));
    assertThat(store.lookup(session.token().value())).isEmpty();
  }

  @Test
  @DisplayName("invalidate() removes the session and reports whether it existed")
  void invalidate() {
    SessionStore store = storeAt(new MutableClock(T0));
    SessionStore.Session session = store.create(ADA, Duration.ofMinutes(30));
    assertThat(store.invalidate(session.token().value())).isTrue();
    assertThat(store.lookup(session.token().value())).isEmpty();
    assertThat(store.invalidate(session.token().value())).isFalse();
  }

  @Test
  @DisplayName("rotate() replaces the token for the same principal and the old token dies")
  void rotate() {
    SessionStore store = storeAt(new MutableClock(T0));
    SessionStore.Session original = store.create(ADA, Duration.ofMinutes(30));
    SessionStore.Session rotated = store.rotate(original.token().value(), Duration.ofMinutes(30)).orElseThrow();
    assertThat(rotated.principal()).isEqualTo(ADA);
    assertThat(rotated.token().value()).isNotEqualTo(original.token().value());
    assertThat(store.lookup(original.token().value())).isEmpty();
    assertThat(store.lookup(rotated.token().value())).contains(rotated);
  }

  @Test
  @DisplayName("rotate() refuses an expired token instead of extending it")
  void rotateExpired() {
    MutableClock clock = new MutableClock(T0);
    SessionStore store = storeAt(clock);
    SessionStore.Session expired = store.create(ADA, Duration.ofMinutes(30));
    clock.advance(Duration.ofMinutes(30));

    assertThat(store.rotate(expired.token().value(), Duration.ofMinutes(30))).isEmpty();
    assertThat(store.size()).isZero();
  }

  @Test
  @DisplayName("a failed rotation keeps the original session instead of losing it")
  void failedRotationKeepsOriginal() {
    SecureRandom failing = new SecureRandom(new byte[32]) {
      private int calls;

      @Override
      public void nextBytes(byte[] bytes) {
        if (++calls > 1) {
          throw new IllegalStateException("random source failed");
        }
        super.nextBytes(bytes);
      }
    };
    SessionStore store = SessionStore.of(new MutableClock(T0), failing);
    SessionStore.Session original = store.create(ADA, Duration.ofMinutes(30));

    assertThatThrownBy(() -> store.rotate(original.token().value(), Duration.ofMinutes(30)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(store.lookup(original.token().value())).contains(original);
    assertThat(store.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("rotate() of an unknown token finds nothing and creates nothing")
  void rotateUnknown() {
    SessionStore store = storeAt(new MutableClock(T0));
    assertThat(store.rotate("no-such-token", Duration.ofMinutes(30))).isEmpty();
    assertThat(store.size()).isZero();
  }

  @Test
  @DisplayName("clear() empties the store")
  void clearEmpties() {
    SessionStore store = storeAt(new MutableClock(T0));
    store.create(ADA, Duration.ofMinutes(30));
    store.clear();
    assertThat(store.size()).isZero();
  }

  @Test
  @DisplayName("refuses invalid construction and use")
  void refusesBadUse() {
    assertThatNullPointerException().isThrownBy(() -> SessionStore.of(null, new SecureRandom()));
    assertThatNullPointerException().isThrownBy(() -> SessionStore.of(Clock.systemUTC(), null));
    SessionStore store = storeAt(new MutableClock(T0));
    assertThatNullPointerException().isThrownBy(() -> store.create(null, Duration.ofMinutes(1)));
    assertThatNullPointerException().isThrownBy(() -> store.create(ADA, null));
    assertThatIllegalArgumentException().isThrownBy(() -> store.create(ADA, Duration.ZERO));
    assertThatNullPointerException().isThrownBy(() -> store.lookup(null));
    assertThatNullPointerException().isThrownBy(() -> store.invalidate(null));
    assertThatNullPointerException().isThrownBy(() -> store.rotate(null, Duration.ofMinutes(1)));
  }

  @Test
  @DisplayName("standard() builds a working store against the system clock")
  void standardStoreWorks() {
    SessionStore store = SessionStore.standard();
    SessionStore.Session session = store.create(ADA, Duration.ofMinutes(30));
    assertThat(store.lookup(session.token().value())).contains(session);
  }

  @Test
  @DisplayName("a session record refuses a null token or principal")
  void sessionRecordRefusesNull() {
    SessionToken token = new SessionToken("tok", T0);
    assertThatNullPointerException().isThrownBy(() -> new SessionStore.Session(null, ADA));
    assertThatNullPointerException().isThrownBy(() -> new SessionStore.Session(token, null));
  }
}
