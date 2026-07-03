package com.barezen.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Permission")
class PermissionTest {

  @Test
  @DisplayName("holds the name it was given")
  void holdsTheName() {
    assertThat(Permission.of("user:read").name()).isEqualTo("user:read");
  }

  @Test
  @DisplayName("refuses a null or blank name: a permission that names nothing protects nothing")
  void refusesNullAndBlank() {
    assertThatNullPointerException().isThrownBy(() -> Permission.of(null));
    assertThatIllegalArgumentException().isThrownBy(() -> Permission.of(" "));
  }

  @Test
  @DisplayName("equality is by name")
  void equalityByName() {
    assertThat(Permission.of("user:read")).isEqualTo(Permission.of("user:read"));
    assertThat(Permission.of("user:read")).isNotEqualTo(Permission.of("user:write"));
  }
}
