package com.barezen.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Credential")
class CredentialTest {

  @Test
  @DisplayName("holds the scheme and value it was given")
  void holdsSchemeAndValue() {
    Credential credential = new Credential("Bearer", "abc123");
    assertThat(credential.scheme()).isEqualTo("Bearer");
    assertThat(credential.value()).isEqualTo("abc123");
  }

  @Test
  @DisplayName("refuses null scheme or value")
  void refusesNull() {
    assertThatNullPointerException().isThrownBy(() -> new Credential(null, "v"));
    assertThatNullPointerException().isThrownBy(() -> new Credential("Bearer", null));
  }

  @Test
  @DisplayName("refuses a blank scheme: a credential without a scheme cannot be interpreted")
  void refusesBlankScheme() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Credential(" ", "v"));
  }

  @Test
  @DisplayName("refuses a scheme outside the HTTP token grammar")
  void refusesInvalidSchemeTokens() {
    for (String scheme : List.of("Bad Scheme", "Bad/Scheme", "Bad,Scheme", "Béarer", "Bad\tScheme")) {
      assertThatIllegalArgumentException().isThrownBy(() -> new Credential(scheme, "v"));
    }
  }

  @Test
  @DisplayName("accepts every legal HTTP token punctuation character in a scheme")
  void acceptsTokenPunctuation() {
    String scheme = "!#$%&'*+-.^_`|~";
    assertThat(new Credential(scheme, "v").scheme()).isEqualTo(scheme);
  }

  @Test
  @DisplayName("toString never prints the value: a credential in a log line is a leak that cannot be recalled")
  void toStringHidesTheValue() {
    String printed = new Credential("Bearer", "super-secret-token").toString();
    assertThat(printed).contains("Bearer");
    assertThat(printed).doesNotContain("super-secret-token");
  }
}
