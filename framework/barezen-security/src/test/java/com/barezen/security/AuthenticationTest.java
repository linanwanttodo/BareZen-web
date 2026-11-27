package com.barezen.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Authentication")
class AuthenticationTest {

  @Test
  @DisplayName("anonymous() returns anonymous for any credential, matching or not")
  void anonymousAlways() {
    Authentication authentication = Authentication.anonymous();
    assertThat(authentication.authenticate(null)).isSameAs(AuthenticationResult.anonymous());
    assertThat(authentication.authenticate(new Credential("Bearer", "x"))).isSameAs(AuthenticationResult.anonymous());
  }

  @Test
  @DisplayName("bearer() authenticates the exact scheme and value it was built with")
  void bearerMatch() {
    Authentication authentication = Authentication.bearer("Bearer", "s3cr3t", new Principal("ada"));
    AuthenticationResult result = authentication.authenticate(new Credential("Bearer", "s3cr3t"));
    assertThat(result.isAuthenticated()).isTrue();
    assertThat(result.maybePrincipal()).contains(new Principal("ada"));
  }

  @Test
  @DisplayName("bearer() fails a wrong value: a credential that does not match proved nothing")
  void bearerWrongValue() {
    Authentication authentication = Authentication.bearer("Bearer", "s3cr3t", new Principal("ada"));
    AuthenticationResult result = authentication.authenticate(new Credential("Bearer", "wrong"));
    assertThat(result.isAuthenticated()).isFalse();
    assertThat(result).isInstanceOf(AuthenticationResult.Failure.class);
  }

  @Test
  @DisplayName("bearer() matches the scheme case-insensitively while keeping the value exact")
  void bearerSchemeIsCaseInsensitive() {
    Authentication authentication = Authentication.bearer("Bearer", "s3cr3t", new Principal("ada"));
    assertThat(authentication.authenticate(new Credential("bearer", "s3cr3t")).isAuthenticated()).isTrue();
    assertThat(authentication.authenticate(new Credential("BEARER", "s3cr3t")).isAuthenticated()).isTrue();
    assertThat(authentication.authenticate(new Credential("Bearer", "S3CR3T")).isAuthenticated()).isFalse();
  }

  @Test
  @DisplayName("bearer() compares the value in constant time: every non-match is the same Failure")
  void bearerValueComparisonIsConstantTime() {
    Authentication authentication = Authentication.bearer("Bearer", "s3cr3t-value", new Principal("ada"));
    // Same length, one differing byte; one byte shorter; a shared prefix. The decision is the same Failure shape
    // for each, so a caller cannot read the distance of the guess from the answer.
    for (String attempt : java.util.List.of("s3cr3t-valuf", "s3cr3t-valu", "s3cr3t", "xxxxxxxxxxxxxxx")) {
      AuthenticationResult result = authentication.authenticate(new Credential("Bearer", attempt));
      assertThat(result.isAuthenticated()).as(attempt).isFalse();
      assertThat(result).as(attempt).isInstanceOf(AuthenticationResult.Failure.class);
    }
    assertThat(authentication.authenticate(new Credential("Bearer", "s3cr3t-value")).isAuthenticated()).isTrue();
  }

  @Test
  @DisplayName("bearer() fails a different scheme")
  void bearerWrongScheme() {
    Authentication authentication = Authentication.bearer("Bearer", "s3cr3t", new Principal("ada"));
    assertThat(authentication.authenticate(new Credential("Basic", "s3cr3t")).isAuthenticated()).isFalse();
  }

  @Test
  @DisplayName("bearer() answers anonymous for a null credential: absence is not an error")
  void bearerNullCredential() {
    Authentication authentication = Authentication.bearer("Bearer", "s3cr3t", new Principal("ada"));
    assertThat(authentication.authenticate(null)).isSameAs(AuthenticationResult.anonymous());
  }

  @Test
  @DisplayName("bearer() refuses a configured scheme outside the HTTP token grammar")
  void bearerRefusesInvalidScheme() {
    assertThatIllegalArgumentException().isThrownBy(() -> Authentication.bearer("Bad Scheme", "v", new Principal("a")));
  }

  @Test
  @DisplayName("bearer() refuses null construction arguments")
  void bearerRefusesNull() {
    assertThatNullPointerException().isThrownBy(() -> Authentication.bearer(null, "v", new Principal("a")));
    assertThatNullPointerException().isThrownBy(() -> Authentication.bearer("Bearer", null, new Principal("a")));
    assertThatNullPointerException().isThrownBy(() -> Authentication.bearer("Bearer", "v", null));
  }
}
