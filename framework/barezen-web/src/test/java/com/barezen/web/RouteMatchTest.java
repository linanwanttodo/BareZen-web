package com.barezen.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a match carries and what it refuses to carry.
 *
 * <p>{@link RouteMatch} exists for exactly one reason — a route with parameters produced something the caller cannot
 * rebuild (ADR-0019 §11) — so most of what is worth testing here is that it stays that size: two components, no
 * accessor that resolves anything, no metadata, no printed value the client sent.
 */
class RouteMatchTest {

  private static final Handler HANDLER = request -> new Response(200, Headers.empty(), null);

  @Nested
  @DisplayName("the surface")
  class Surface {

    @Test
    void amatchIsARecordOfExactlyTwoComponents() {
      assertThat(RouteMatch.class.isRecord()).isTrue();
      assertThat(java.util.Arrays.stream(RouteMatch.class.getRecordComponents())
          .map(component -> component.getName()))
          .containsExactly("handler", "parameters");
    }

    @Test
    void thereIsNothingElseToAsk() {
      // No pattern(), no route(), no name(), no priority(), no attribute(name): a third component would be metadata
      // with no consumer, and a lookup-shaped accessor would make this the service locator ADR-0018 §9 refuses.
      // equals and hashCode are the record's own, which is what lets a test assert one match against another.
      assertThat(java.util.Arrays.stream(RouteMatch.class.getDeclaredMethods())
          .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
          .map(java.lang.reflect.Method::getName)
          .distinct())
          .containsExactlyInAnyOrder("handler", "parameters", "toString", "equals", "hashCode");
    }

    @Test
    void aMatchCannotBeBuiltWithoutAHandlerOrWithoutAParameterMap() {
      assertThatThrownBy(() -> new RouteMatch(null, Map.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("matched handler");
      assertThatThrownBy(() -> new RouteMatch(HANDLER, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("matched parameters");
    }
  }

  @Nested
  @DisplayName("the parameters")
  class Parameters {

    @Test
    void theMapIsCopiedSoTheCallerBufferCannotEditAMatch() {
      Map<String, String> written = new LinkedHashMap<>();
      written.put("id", "42");
      RouteMatch found = new RouteMatch(HANDLER, written);

      written.put("id", "changed");
      written.put("extra", "added");

      assertThat(found.parameters()).containsExactly(Map.entry("id", "42"));
    }

    @Test
    void theMapIsUnmodifiable() {
      Map<String, String> parameters = new RouteMatch(HANDLER, Map.of("id", "42")).parameters();

      assertThatThrownBy(() -> parameters.put("id", "other")).isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(parameters::clear).isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(() -> parameters.remove("id")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void thePatternOrderSurvivesThroughTheMatch() {
      Map<String, String> written = new LinkedHashMap<>();
      written.put("zoo", "1");
      written.put("alpha", "2");

      assertThat(RouteMatch.class.getName()).isEqualTo("com.barezen.web.RouteMatch");
      assertThat(new RouteMatch(HANDLER, written).parameters().keySet()).containsExactly("zoo", "alpha");
    }

    @Test
    void aRouteWithoutParametersStillHasAMapAndItIsEmpty() {
      // Empty rather than absent: a handler that reads parameters() never has to test for null first, which is the
      // trade ADR-0019 §11 records for choosing RouteMatch over a bare Handler.
      RouteMatch found = new RouteMatch(HANDLER, Map.of());

      assertThat(found.parameters()).isEmpty();
      assertThatCode(() -> found.parameters().get("anything")).doesNotThrowAnyException();
    }

    @Test
    void valuesAreRawInTheWayTheyArrived() {
      RouteMatch found = new RouteMatch(HANDLER, Map.of("id", "a%2Fb"));

      assertThat(found.parameters().get("id")).isEqualTo("a%2Fb").doesNotContain("/");
    }
  }

  @Nested
  @DisplayName("equality and printing")
  class Identity {

    @Test
    void twoMatchesOfTheSameRouteAreEqual() {
      RouteMatch first = new RouteMatch(HANDLER, Map.of("id", "42"));
      RouteMatch second = new RouteMatch(HANDLER, Map.of("id", "42"));

      assertThat(second).isEqualTo(first).hasSameHashCodeAs(first);
    }

    @Test
    void anUnmodifiableMapStillComparesAgainstAPlainOne() {
      // The defensive copy is a wrapper, not a different map: a test that writes Map.of("id", "42") has to match what
      // Router.match() handed back, or the router tests would be asserting against a shape nobody can build.
      assertThat(new RouteMatch(HANDLER, Map.of("id", "42")).parameters()).isEqualTo(Map.of("id", "42"));
    }

    @Test
    void aDifferentHandlerOrADifferentCaptureIsADifferentAnswer() {
      Handler other = request -> new Response(204, Headers.empty(), null);

      assertThat(new RouteMatch(other, Map.of("id", "42"))).isNotEqualTo(new RouteMatch(HANDLER, Map.of("id", "42")));
      assertThat(new RouteMatch(HANDLER, Map.of("id", "43"))).isNotEqualTo(new RouteMatch(HANDLER, Map.of("id", "42")));
    }

    @Test
    void printingNamesTheParametersButNeverTheirValuesOrTheHandler() {
      assertThat(new RouteMatch(HANDLER, Map.of("id", "42"))).hasToString("RouteMatch[1 parameter: id]");
      // A LinkedHashMap rather than Map.of: the point of the printed list is that it is the pattern's order, and
      // Map.of iterates in a hash order that would make this assertion about two unpredictable strings.
      Map<String, String> two = new LinkedHashMap<>();
      two.put("userId", "42");
      two.put("token", "abc");
      assertThat(new RouteMatch(HANDLER, two)).hasToString("RouteMatch[2 parameters: userId, token]");
      assertThat(new RouteMatch(HANDLER, Map.of())).hasToString("RouteMatch[0 parameters]");

      String printed = new RouteMatch(HANDLER, Map.of("password", "hunter2")).toString();
      // A match is what a routing log line ends in, so the redaction rule is Request's and Headers' rule: names may
      // appear, values and live collaborators may not (ADR-0019 §21).
      assertThat(printed).doesNotContain("hunter2").doesNotContain("Handler").doesNotContain("com.barezen");
    }
  }
}
