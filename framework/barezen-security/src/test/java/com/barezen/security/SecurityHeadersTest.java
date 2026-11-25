package com.barezen.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SecurityHeaders")
class SecurityHeadersTest {

  private static final String POLICY = "default-src 'self'; frame-ancestors 'none'";
  private static final Request REQUEST = new Request(HttpMethod.GET, "/", null, Headers.empty(), null);

  @Test
  @DisplayName("adds the application's CSP and the fixed browser-safety fields without changing the answer")
  void stampsResponse() {
    Response original = new Response(200, Headers.of("Content-Type", "text/plain"), "ok".getBytes(StandardCharsets.UTF_8));

    Response stamped = SecurityHeaders.of((request, parameters) -> original, POLICY).handle(REQUEST, Map.of());

    assertThat(stamped.status()).isEqualTo(200);
    assertThat(stamped.body()).isEqualTo("ok".getBytes(StandardCharsets.UTF_8));
    assertThat(stamped.headers().first("Content-Type")).contains("text/plain");
    assertThat(stamped.headers().first("Content-Security-Policy")).contains(POLICY);
    assertThat(stamped.headers().first("X-Content-Type-Options")).contains("nosniff");
    assertThat(stamped.headers().first("Referrer-Policy")).contains("no-referrer");
  }

  @Test
  @DisplayName("preserves fields the application already chose instead of duplicating or replacing them")
  void preservesExistingFields() {
    Headers headers = Headers.of("Content-Security-Policy", "default-src 'none'")
        .with("X-Content-Type-Options", "custom")
        .with("Referrer-Policy", "same-origin");
    Response original = new Response(200, headers, null);

    Response stamped = SecurityHeaders.of((request, parameters) -> original, POLICY).handle(REQUEST, Map.of());

    assertThat(stamped.headers().values("Content-Security-Policy")).containsExactly("default-src 'none'");
    assertThat(stamped.headers().values("X-Content-Type-Options")).containsExactly("custom");
    assertThat(stamped.headers().values("Referrer-Policy")).containsExactly("same-origin");
  }

  @Test
  @DisplayName("refuses a missing delegate or blank policy at assembly")
  void refusesBadArguments() {
    assertThatNullPointerException().isThrownBy(() -> SecurityHeaders.of(null, POLICY));
    assertThatNullPointerException().isThrownBy(() -> SecurityHeaders.of((request, parameters) -> new Response(200, Headers.empty(), null), null));
    assertThatThrownBy(() -> SecurityHeaders.of((request, parameters) -> new Response(200, Headers.empty(), null), " "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
