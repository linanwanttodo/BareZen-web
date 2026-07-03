package com.barezen.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SessionToken")
class SessionTokenTest {

  private static final Instant T0 = Instant.parse("2026-10-02T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

  @Test
  @DisplayName("of() expires exactly the duration after the clock's instant")
  void expiresAfterTheDuration() {
    SessionToken token = SessionToken.of("tok", Duration.ofMinutes(30), CLOCK);
    assertThat(token.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(30)));
  }

  @Test
  @DisplayName("isExpired is true at the expiration instant and after, false before")
  void expirationBoundary() {
    SessionToken token = SessionToken.of("tok", Duration.ofMinutes(30), CLOCK);
    assertThat(token.isExpired(T0.plus(Duration.ofMinutes(29)))).isFalse();
    assertThat(token.isExpired(T0.plus(Duration.ofMinutes(30)))).isTrue();
    assertThat(token.isExpired(T0.plus(Duration.ofMinutes(31)))).isTrue();
  }

  @Test
  @DisplayName("of() refuses a zero or negative duration: the token would be born expired")
  void refusesNonPositiveDuration() {
    assertThatIllegalArgumentException().isThrownBy(() -> SessionToken.of("tok", Duration.ZERO, CLOCK));
    assertThatIllegalArgumentException().isThrownBy(() -> SessionToken.of("tok", Duration.ofSeconds(-1), CLOCK));
  }

  @Test
  @DisplayName("refuses null and blank construction")
  void refusesBadConstruction() {
    assertThatNullPointerException().isThrownBy(() -> new SessionToken(null, T0));
    assertThatNullPointerException().isThrownBy(() -> new SessionToken("tok", null));
    assertThatIllegalArgumentException().isThrownBy(() -> new SessionToken(" ", T0));
    assertThatNullPointerException().isThrownBy(() -> SessionToken.of("tok", null, CLOCK));
    assertThatNullPointerException().isThrownBy(() -> SessionToken.of("tok", Duration.ofMinutes(1), null));
    SessionToken token = new SessionToken("tok", T0);
    assertThatNullPointerException().isThrownBy(() -> token.isExpired(null));
  }

  @Test
  @DisplayName("toString names the expiration, never the value: a token in a log line is a leak")
  void toStringHidesTheValue() {
    SessionToken token = SessionToken.of("super-secret-token", Duration.ofMinutes(30), CLOCK);
    String printed = token.toString();
    assertThat(printed).contains("2026-10-02T12:30:00Z");
    assertThat(printed).doesNotContain("super-secret-token");
  }
}
