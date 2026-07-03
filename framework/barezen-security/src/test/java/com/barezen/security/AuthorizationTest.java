package com.barezen.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Authorization")
class AuthorizationTest {

  private static final Principal ADA = new Principal("ada");
  private static final Permission READ = Permission.of("user:read");
  private static final Permission WRITE = Permission.of("user:write");

  @Test
  @DisplayName("allowAll() allows every permission for every principal")
  void allowAll() {
    Authorization authorization = Authorization.allowAll();
    assertThat(authorization.authorize(ADA, READ)).isEqualTo(Authorization.Decision.ALLOW);
    assertThat(authorization.authorize(ADA, WRITE)).isEqualTo(Authorization.Decision.ALLOW);
  }

  @Test
  @DisplayName("denyAll() denies every permission for every principal: the secure default")
  void denyAll() {
    Authorization authorization = Authorization.denyAll();
    assertThat(authorization.authorize(ADA, READ)).isEqualTo(Authorization.Decision.DENY);
  }

  @Test
  @DisplayName("of() allows exactly the permissions the table grants")
  void ofGrants() {
    Authorization authorization = Authorization.of(Map.of(ADA, Set.of(READ)));
    assertThat(authorization.authorize(ADA, READ)).isEqualTo(Authorization.Decision.ALLOW);
  }

  @Test
  @DisplayName("of() denies a permission the table does not grant, even to a known principal")
  void ofDeniesUngranted() {
    Authorization authorization = Authorization.of(Map.of(ADA, Set.of(READ)));
    assertThat(authorization.authorize(ADA, WRITE)).isEqualTo(Authorization.Decision.DENY);
  }

  @Test
  @DisplayName("of() denies a principal absent from the table")
  void ofDeniesUnknownPrincipal() {
    Authorization authorization = Authorization.of(Map.of(ADA, Set.of(READ)));
    assertThat(authorization.authorize(new Principal("grace"), READ)).isEqualTo(Authorization.Decision.DENY);
  }

  @Test
  @DisplayName("of() refuses a null table")
  void ofRefusesNull() {
    assertThatNullPointerException().isThrownBy(() -> Authorization.of(null));
  }
}
