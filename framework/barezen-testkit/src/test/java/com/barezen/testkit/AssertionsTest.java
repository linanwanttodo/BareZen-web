package com.barezen.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.security.Principal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Assertions}: every method must pass when the expectation holds and throw an
 * {@link AssertionError} when it does not. An assertion that cannot fail is worse than no assertion, so each method
 * here is shown both accepting the true case and rejecting a near-miss — the mutation "assertion ignored" from the
 * phase's list is exactly a version of these methods that only ever passes.
 */
@DisplayName("Assertions")
class AssertionsTest {

  private static TestResponse json(String body) {
    return new TestResponse(200, Map.of("Content-Type", List.of("application/json")),
        body.getBytes(StandardCharsets.UTF_8));
  }

  @Nested
  @DisplayName("status")
  class Status {

    @Test
    @DisplayName("passes on the matching status, throws on the wrong one")
    void status() {
      TestResponse response = new TestResponse(404, Map.of(), new byte[0]);
      assertThatCode(() -> Assertions.assertThat(response).statusIs(404)).doesNotThrowAnyException();
      assertThatThrownBy(() -> Assertions.assertThat(response).statusIs(200))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("expected status 200 but got 404");
    }
  }

  @Nested
  @DisplayName("headers")
  class Headers {

    @Test
    @DisplayName("headerIs matches case-insensitively on the name and exactly on the value")
    void headerIs() {
      TestResponse response = new TestResponse(200, Map.of("Content-Type", List.of("text/plain")), new byte[0]);
      assertThatCode(() -> Assertions.assertThat(response).headerIs("content-type", "text/plain"))
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> Assertions.assertThat(response).headerIs("Content-Type", "application/json"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("application/json");
    }

    @Test
    @DisplayName("headerIs reports an absent header, hasHeader accepts any value")
    void presence() {
      TestResponse response = new TestResponse(200, Map.of("X-Trace", List.of("abc")), new byte[0]);
      assertThatCode(() -> Assertions.assertThat(response).hasHeader("x-trace")).doesNotThrowAnyException();
      assertThatThrownBy(() -> Assertions.assertThat(response).headerIs("X-Missing", "x"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("absent");
      assertThatThrownBy(() -> Assertions.assertThat(response).hasHeader("X-Nope"))
          .isInstanceOf(AssertionError.class);
    }
  }

  @Nested
  @DisplayName("body")
  class Body {

    @Test
    @DisplayName("bodyIs is exact, bodyContains is a substring")
    void body() {
      TestResponse response = new TestResponse(200, Map.of(), "hello world".getBytes(StandardCharsets.UTF_8));
      assertThatCode(() -> Assertions.assertThat(response).bodyIs("hello world")).doesNotThrowAnyException();
      assertThatCode(() -> Assertions.assertThat(response).bodyContains("lo wo")).doesNotThrowAnyException();
      assertThatThrownBy(() -> Assertions.assertThat(response).bodyIs("hello"))
          .isInstanceOf(AssertionError.class);
      assertThatThrownBy(() -> Assertions.assertThat(response).bodyContains("nope"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("to contain");
    }
  }

  @Nested
  @DisplayName("json paths")
  class Json {

    private final TestResponse response = json(
        "{\"info\":{\"title\":\"BareZen\",\"version\":1,\"stable\":true},\"tags\":[{\"name\":\"a\"},{\"name\":\"b\"}]}");

    @Test
    @DisplayName("jsonHas accepts a present path and rejects a missing one")
    void has() {
      assertThatCode(() -> Assertions.assertThat(response).jsonHas("info.title")).doesNotThrowAnyException();
      assertThatThrownBy(() -> Assertions.assertThat(response).jsonHas("info.missing"))
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("jsonStringIs checks both kind and content")
    void string() {
      assertThatCode(() -> Assertions.assertThat(response).jsonStringIs("info.title", "BareZen"))
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> Assertions.assertThat(response).jsonStringIs("info.title", "Other"))
          .isInstanceOf(AssertionError.class);
      // A number is not a string: asserting a string at a numeric path must fail, not coerce.
      assertThatThrownBy(() -> Assertions.assertThat(response).jsonStringIs("info.version", "1"))
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("jsonNumberIs compares the literal text")
    void number() {
      assertThatCode(() -> Assertions.assertThat(response).jsonNumberIs("info.version", "1"))
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> Assertions.assertThat(response).jsonNumberIs("info.version", "2"))
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("jsonBooleanIs distinguishes true from false from non-boolean")
    void bool() {
      assertThatCode(() -> Assertions.assertThat(response).jsonBooleanIs("info.stable", true))
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> Assertions.assertThat(response).jsonBooleanIs("info.stable", false))
          .isInstanceOf(AssertionError.class);
      assertThatThrownBy(() -> Assertions.assertThat(response).jsonBooleanIs("info.title", true))
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("jsonArraySizeIs checks size and rejects a non-array")
    void array() {
      assertThatCode(() -> Assertions.assertThat(response).jsonArraySizeIs("tags", 2)).doesNotThrowAnyException();
      assertThatThrownBy(() -> Assertions.assertThat(response).jsonArraySizeIs("tags", 3))
          .isInstanceOf(AssertionError.class);
      assertThatThrownBy(() -> Assertions.assertThat(response).jsonArraySizeIs("info", 2))
          .isInstanceOf(AssertionError.class);
      assertThatThrownBy(() -> Assertions.assertThat(response).jsonArraySizeIs("nope", 1))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("absent");
    }

    @Test
    @DisplayName("asserting a scalar at an absent path reports absence, not a kind mismatch")
    void absentScalar() {
      assertThatThrownBy(() -> Assertions.assertThat(response).jsonNumberIs("info.absent", "1"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("absent");
    }

    @Test
    @DisplayName("a non-JSON body is refused by the parser rather than silently asserted against")
    void malformed() {
      TestResponse bad = new TestResponse(200, Map.of(), "not json".getBytes(StandardCharsets.UTF_8));
      assertThatThrownBy(() -> Assertions.assertThat(bad).jsonHas("a.b")).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("counts and identity")
  class CountsAndIdentity {

    @Test
    @DisplayName("CountAssert.isEqualTo accepts the exact count and rejects any other")
    void count() {
      assertThatCode(() -> Assertions.assertThat(3).isEqualTo(3)).doesNotThrowAnyException();
      assertThatThrownBy(() -> Assertions.assertThat(0).isEqualTo(1))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("expected 1 invocation(s) but there were 0");
    }

    @Test
    @DisplayName("a present principal has its name; anonymous is anonymous; a wrong name is rejected")
    void identity() {
      Optional<Principal> alice = Optional.of(new Principal("alice"));
      assertThatCode(() -> Assertions.assertThat(alice).hasName("alice")).doesNotThrowAnyException();
      assertThatCode(() -> Assertions.assertThat(Optional.<Principal>empty()).isAnonymous())
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> Assertions.assertThat(alice).isAnonymous())
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("alice");
      assertThatThrownBy(() -> Assertions.assertThat(alice).hasName("bob"))
          .isInstanceOf(AssertionError.class);
      assertThatThrownBy(() -> Assertions.assertThat(Optional.<Principal>empty()).hasName("alice"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("anonymous");
    }
  }
}
