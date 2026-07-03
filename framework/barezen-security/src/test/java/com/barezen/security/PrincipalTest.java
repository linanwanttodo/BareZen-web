package com.barezen.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Principal")
class PrincipalTest {

  @Test
  @DisplayName("holds the name it was given")
  void holdsTheName() {
    assertThat(new Principal("ada").name()).isEqualTo("ada");
  }

  @Test
  @DisplayName("refuses a null name")
  void refusesNull() {
    assertThatNullPointerException().isThrownBy(() -> new Principal(null));
  }

  @Test
  @DisplayName("refuses a blank name: an anonymous request has no principal at all")
  void refusesBlank() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Principal("  "));
  }

  @Test
  @DisplayName("two principals with the same name are equal")
  void equalityByName() {
    assertThat(new Principal("ada")).isEqualTo(new Principal("ada"));
    assertThat(new Principal("ada")).isNotEqualTo(new Principal("grace"));
  }
}
