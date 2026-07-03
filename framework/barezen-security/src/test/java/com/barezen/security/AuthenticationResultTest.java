package com.barezen.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AuthenticationResult")
class AuthenticationResultTest {

  @Test
  @DisplayName("authenticated carries the principal and reports authenticated")
  void authenticatedState() {
    AuthenticationResult result = AuthenticationResult.authenticated(new Principal("ada"));
    assertThat(result.isAuthenticated()).isTrue();
    assertThat(result.maybePrincipal()).contains(new Principal("ada"));
  }

  @Test
  @DisplayName("failure carries the reason and reports not authenticated, no principal")
  void failureState() {
    AuthenticationResult result = AuthenticationResult.failure("bad token");
    assertThat(result.isAuthenticated()).isFalse();
    assertThat(result.maybePrincipal()).isEmpty();
    assertThat(result).isInstanceOf(AuthenticationResult.Failure.class);
    assertThat(((AuthenticationResult.Failure) result).reason()).isEqualTo("bad token");
  }

  @Test
  @DisplayName("anonymous reports not authenticated, no principal")
  void anonymousState() {
    AuthenticationResult result = AuthenticationResult.anonymous();
    assertThat(result.isAuthenticated()).isFalse();
    assertThat(result.maybePrincipal()).isEmpty();
  }

  @Test
  @DisplayName("anonymous is a singleton: absence of a claim is one fact, not many")
  void anonymousSingleton() {
    assertThat(AuthenticationResult.anonymous()).isSameAs(AuthenticationResult.anonymous());
  }

  @Test
  @DisplayName("authenticated refuses a null principal: a success without an identity is a failure dressed as one")
  void authenticatedRefusesNull() {
    assertThatNullPointerException().isThrownBy(() -> AuthenticationResult.authenticated(null));
  }

  @Test
  @DisplayName("failure refuses a null reason")
  void failureRefusesNull() {
    assertThatNullPointerException().isThrownBy(() -> AuthenticationResult.failure(null));
  }
}
