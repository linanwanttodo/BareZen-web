package com.barezen.plugins.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The framework's only JSON emitter, tested on the two things its javadoc promises: it writes members in exactly
 * the order the caller adds them (the byte-determinism foundation of Stage 2.14-D), and a misuse throws rather
 * than emitting malformed JSON (Stage 2.14-F's "a generator that can produce an invalid document" case, closed
 * at the emitter level as well as the model level).
 */
class JsonWriterTest {

  @Nested
  @DisplayName("output")
  class Output {

    @Test
    @DisplayName("members come out in the order written, with no whitespace and nothing re-sorted")
    void insertionOrderIsTheOutputOrder() {
      String json = new JsonWriter().objectStart()
          .field("z").string("1")
          .field("a").string("2")
          .field("ok").bool(true)
          .field("no").bool(false)
          .objectEnd().finish();

      assertThat(json).isEqualTo("{\"z\":\"1\",\"a\":\"2\",\"ok\":true,\"no\":false}");
    }

    @Test
    @DisplayName("arrays separate with commas, objects separate at the field, nesting composes both rules")
    void nestedContainers() {
      String json = new JsonWriter().objectStart()
          .field("list").arrayStart().string("a").string("b").arrayEnd()
          .field("empty").arrayStart().arrayEnd()
          .field("obj").objectStart().field("k").string("v").objectEnd()
          .objectEnd().finish();

      assertThat(json).isEqualTo("{\"list\":[\"a\",\"b\"],\"empty\":[],\"obj\":{\"k\":\"v\"}}");
    }

    @Test
    @DisplayName("the seven named escapes and \\u00xx for the rest of the control range")
    void escaping() {
      String json = new JsonWriter().string("a\"b\\c\bd\fe\nf\rg\th\u0001i").finish();

      assertThat(json).isEqualTo("\"a\\\"b\\\\c\\bd\\fe\\nf\\rg\\th\\u0001i\"");
    }

    @Test
    @DisplayName("characters at and above 0x20 pass through untouched, non-ASCII included")
    void noAggressiveEscaping() {
      String json = new JsonWriter().string("héllo — ok? <script>").finish();

      // the emitter escapes control characters and the JSON delimiters only; a \\u sweep over non-ASCII would
      // change bytes across locales and break the determinism promise with it
      assertThat(json).isEqualTo("\"héllo — ok? <script>\"");
    }
  }

  @Nested
  @DisplayName("misuse throws instead of emitting")
  class Misuse {

    @Test
    @DisplayName("a field outside any object is refused")
    void fieldOutsideObject() {
      JsonWriter w = new JsonWriter();
      assertThatThrownBy(() -> w.field("x")).isInstanceOf(IllegalStateException.class);

      w.objectStart().field("arr").arrayStart();
      assertThatThrownBy(() -> w.field("x"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside an object");
    }

    @Test
    @DisplayName("closing the wrong container kind is refused, and so is closing past the top")
    void closeMismatch() {
      JsonWriter w = new JsonWriter();
      assertThatThrownBy(w::objectEnd).isInstanceOf(IllegalStateException.class);

      w.objectStart().arrayStart();
      assertThatThrownBy(w::objectEnd)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("closed an object while inside an array");
    }

    @Test
    @DisplayName("finish() with open containers is refused — a truncated document is the invalid output")
    void unclosedContainers() {
      JsonWriter w = new JsonWriter();
      w.objectStart().field("a").arrayStart();
      assertThatThrownBy(w::finish)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("2 unclosed container");
    }
  }
}
