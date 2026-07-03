package com.barezen.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SecurityContext")
class SecurityContextTest {

  @Test
  @DisplayName("delegates the authentication result's facts")
  void delegatesTheResult() {
    SecurityContext context = new SecurityContext(AuthenticationResult.authenticated(new Principal("ada")));
    assertThat(context.isAuthenticated()).isTrue();
    assertThat(context.principal()).contains(new Principal("ada"));
  }

  @Test
  @DisplayName("an anonymous context has no principal and is not authenticated")
  void anonymousContext() {
    SecurityContext context = new SecurityContext(AuthenticationResult.anonymous());
    assertThat(context.isAuthenticated()).isFalse();
    assertThat(context.principal()).isEmpty();
  }

  @Test
  @DisplayName("refuses a null result: a context without a decision is no context")
  void refusesNull() {
    assertThatNullPointerException().isThrownBy(() -> new SecurityContext(null));
  }
}
