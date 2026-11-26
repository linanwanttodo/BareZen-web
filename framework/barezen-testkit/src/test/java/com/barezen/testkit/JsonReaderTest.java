package com.barezen.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the package-private JSON reader {@link Assertions} relies on.
 *
 * <p>These are not a conformance suite for a JSON library — the reader is not a library. They pin exactly the
 * behaviours a test author would be silently misled by if they broke: nested navigation by dotted path, object member
 * order preserved, every scalar kind distinguished, escapes decoded, and malformed input refused rather than tolerated.
 */
@DisplayName("JsonValue")
class JsonReaderTest {

  @Nested
  @DisplayName("scalars")
  class Scalars {

    @Test
    @DisplayName("a string keeps its decoded content")
    void string() {
      JsonValue.Value value = JsonValue.parse("\"hello\"");
      assertThat(value.kind()).isEqualTo(JsonValue.Value.Kind.STRING);
      assertThat(value.asText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("a number keeps its literal text, so \"1.0\" and \"1\" stay distinguishable")
    void number() {
      assertThat(JsonValue.parse("42").asText()).isEqualTo("42");
      assertThat(JsonValue.parse("-3.50").asText()).isEqualTo("-3.50");
      assertThat(JsonValue.parse("1e3").kind()).isEqualTo(JsonValue.Value.Kind.NUMBER);
    }

    @Test
    @DisplayName("true, false and null are their own kinds")
    void keywords() {
      assertThat(JsonValue.parse("true").kind()).isEqualTo(JsonValue.Value.Kind.TRUE);
      assertThat(JsonValue.parse("false").kind()).isEqualTo(JsonValue.Value.Kind.FALSE);
      assertThat(JsonValue.parse("null").kind()).isEqualTo(JsonValue.Value.Kind.NULL);
    }
  }

  @Nested
  @DisplayName("containers")
  class Containers {

    @Test
    @DisplayName("object members are kept in document order, not hash order")
    void objectOrder() {
      JsonValue.Value value = JsonValue.parse("{\"b\":1,\"a\":2,\"c\":3}");
      assertThat(value.members().keySet()).containsExactly("b", "a", "c");
    }

    @Test
    @DisplayName("array elements are kept in order")
    void arrayOrder() {
      List<JsonValue.Value> elements = JsonValue.parse("[10,20,30]").elements();
      assertThat(elements).hasSize(3);
      assertThat(elements.get(1).asText()).isEqualTo("20");
    }

    @Test
    @DisplayName("empty object and empty array parse to empty containers")
    void empty() {
      assertThat(JsonValue.parse("{}").members()).isEmpty();
      assertThat(JsonValue.parse("[]").elements()).isEmpty();
    }
  }

  @Nested
  @DisplayName("dotted navigation")
  class Navigation {

    @Test
    @DisplayName("a path descends through objects and array indices")
    void descend() {
      JsonValue.Value root = JsonValue.parse(
          "{\"info\":{\"title\":\"BareZen\"},\"tags\":[{\"name\":\"users\"}]}");
      assertThat(root.at("info.title").asText()).isEqualTo("BareZen");
      assertThat(root.at("tags.0.name").asText()).isEqualTo("users");
    }

    @Test
    @DisplayName("a missing segment yields null instead of throwing, so jsonHas can assert absence")
    void missing() {
      JsonValue.Value root = JsonValue.parse("{\"info\":{\"title\":\"x\"}}");
      assertThat(root.at("info.subtitle")).isNull();
      assertThat(root.at("nope.deeper")).isNull();
    }

    @Test
    @DisplayName("an out-of-range array index and a non-numeric index into an array both yield null")
    void badIndex() {
      JsonValue.Value root = JsonValue.parse("{\"tags\":[{\"name\":\"users\"}]}");
      assertThat(root.at("tags.5.name")).isNull();
      assertThat(root.at("tags.x.name")).isNull();
    }

    @Test
    @DisplayName("walking into a scalar stops and returns null rather than pretending to read through it")
    void scalarTerminal() {
      JsonValue.Value root = JsonValue.parse("{\"a\":1}");
      assertThat(root.at("a.b")).isNull();
    }
  }

  @Nested
  @DisplayName("escapes and whitespace")
  class Decoding {

    @Test
    @DisplayName("the standard escapes decode, including \\u")
    void escapes() {
      assertThat(JsonValue.parse("\"a\\\"b\\\\c\\/d\\ne\\tf\"").asText()).isEqualTo("a\"b\\c/d\ne\tf");
      assertThat(JsonValue.parse("\"\\u0041\\u20ac\"").asText()).isEqualTo("A\u20ac");
    }

    @Test
    @DisplayName("insignificant whitespace around every token is skipped")
    void whitespace() {
      JsonValue.Value value = JsonValue.parse("  {  \"a\"  :  [ 1 , 2 ]  }  ");
      assertThat(value.at("a.1").asText()).isEqualTo("2");
    }
  }

  @Nested
  @DisplayName("malformed input is refused")
  class Malformed {

    @Test
    @DisplayName("a trailing character after a complete value is an error")
    void trailing() {
      assertThatThrownBy(() -> JsonValue.parse("{} garbage"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("trailing characters");
    }

    @Test
    @DisplayName("an unterminated string and a bare key are errors")
    void strings() {
      assertThatThrownBy(() -> JsonValue.parse("\"unterminated"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> JsonValue.parse("{key:1}"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("string key");
    }

    @Test
    @DisplayName("a missing colon, a wrong bracket and an invalid escape are errors")
    void structure() {
      assertThatThrownBy(() -> JsonValue.parse("{\"a\" 1}")).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> JsonValue.parse("[1,2}")).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> JsonValue.parse("\"\\q\"")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a truncated or non-hex Unicode escape is a parse error, not a substring failure")
    void unicodeEscapes() {
      assertThatThrownBy(() -> JsonValue.parse("\"\\u12\""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("invalid JSON");
      assertThatThrownBy(() -> JsonValue.parse("\"\\u12G4\""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("invalid JSON");
      assertThatThrownBy(() -> JsonValue.parse("\"\\uＦＦＦＦ\""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("invalid JSON");
    }

    @Test
    @DisplayName("an empty input is an error, not a null")
    void empty() {
      assertThatThrownBy(() -> JsonValue.parse("")).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("the reader never misreports a foreign shape: kind() distinguishes an object from an array")
  void kindIntegrity() {
    Map<String, JsonValue.Value> object = JsonValue.parse("{\"a\":1}").members();
    assertThat(object).containsKey("a");
    assertThat(JsonValue.parse("[1]").elements()).hasSize(1);
    // A number's text is its literal; asserting it is not treated as a string guards the type discipline the
    // assertions rely on.
    assertThat(JsonValue.parse("1").kind()).isNotEqualTo(JsonValue.Value.Kind.STRING);
  }
}
