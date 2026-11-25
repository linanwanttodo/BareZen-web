package com.barezen.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("input decoding")
class InputDecodingTest {

  @Test
  @DisplayName("a malformed query escape is refused instead of thrown")
  void malformedQueryIsRefused() {
    Query query = Query.of(Optional.of("q=%GG"));

    assertThat(query.refused()).isTrue();
    assertThat(query.value("q")).isEmpty();
  }

  @Test
  @DisplayName("valid query escapes and plus signs are decoded")
  void validQueryIsDecoded() {
    Query query = Query.of(Optional.of("q=ada+lovelace%21"));

    assertThat(query.refused()).isFalse();
    assertThat(query.value("q")).contains("ada lovelace!");
  }

  @Test
  @DisplayName("a malformed form escape is a missing value instead of thrown")
  void malformedFormIsMissing() {
    byte[] body = "username=%GG&password=x".getBytes(StandardCharsets.UTF_8);

    assertThat(Json.formValue(body, "username")).isNull();
  }

  @Test
  @DisplayName("valid form escapes and plus signs are decoded")
  void validFormIsDecoded() {
    byte[] body = "username=ada+lovelace%21".getBytes(StandardCharsets.UTF_8);

    assertThat(Json.formValue(body, "username")).isEqualTo("ada lovelace!");
  }
}
