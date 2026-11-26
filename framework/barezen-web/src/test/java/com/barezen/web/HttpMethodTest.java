package com.barezen.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The method model: the nine names HTTP defines, a client's spelling recognised whatever its case, and an unusual name
 * kept rather than discarded.
 */
class HttpMethodTest {

  @Nested
  @DisplayName("the nine names")
  class NamedMethods {

    @Test
    void parsesEveryStandardNameToTheSharedValue() {
      assertThat(HttpMethod.of("GET")).isSameAs(HttpMethod.GET);
      assertThat(HttpMethod.of("HEAD")).isSameAs(HttpMethod.HEAD);
      assertThat(HttpMethod.of("POST")).isSameAs(HttpMethod.POST);
      assertThat(HttpMethod.of("PUT")).isSameAs(HttpMethod.PUT);
      assertThat(HttpMethod.of("DELETE")).isSameAs(HttpMethod.DELETE);
      assertThat(HttpMethod.of("CONNECT")).isSameAs(HttpMethod.CONNECT);
      assertThat(HttpMethod.of("OPTIONS")).isSameAs(HttpMethod.OPTIONS);
      assertThat(HttpMethod.of("TRACE")).isSameAs(HttpMethod.TRACE);
      assertThat(HttpMethod.of("PATCH")).isSameAs(HttpMethod.PATCH);
      // Identity, not equality: a handler may write == against a constant and mean it.
    }

    @Test
    void recognisesAStandardNameInAnyCase() {
      // A client is allowed to send "get"; a framework that made that a different method would create routes that
      // never match, on a machine where the client's library happened to lowercase the line.
      assertThat(HttpMethod.of("get")).isSameAs(HttpMethod.GET);
      assertThat(HttpMethod.of("PoSt")).isSameAs(HttpMethod.POST);
      assertThat(HttpMethod.of("delete")).isSameAs(HttpMethod.DELETE);
      assertThat(HttpMethod.of("get").text()).isEqualTo("GET");
    }

    @Test
    void namesAreDistinctFromEachOther() {
      assertThat(Arrays.asList(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH))
          .doesNotHaveDuplicates();
      assertThat(HttpMethod.GET).isNotEqualTo(HttpMethod.HEAD);
      // HEAD is not a GET here: a handler that answered them alike would send a body to a client that must not read
      // one.
      assertThat(HttpMethod.GET).isNotEqualTo(HttpMethod.OPTIONS);
    }

    @Test
    void exposesTheNameThatGoesOnTheWire() {
      assertThat(HttpMethod.GET.text()).isEqualTo("GET");
      assertThat(HttpMethod.GET).hasToString("GET");
      // toString and text() are the same string on purpose: a summary line, an exception message and a request line
      // should not describe one request two different ways.
    }
  }

  @Nested
  @DisplayName("a method the nine do not name")
  class OtherMethods {

    @Test
    void keepsTheSpellingThatArrived() {
      HttpMethod purge = HttpMethod.of("PURGE");

      assertThat(purge).isNotSameAs(HttpMethod.GET);
      assertThat(purge.text()).isEqualTo("PURGE");
      // An enum with an UNKNOWN constant would lose "PURGE" here, and a handler would have no way to answer the
      // request it is holding.
    }

    @Test
    void comparesUnusualNamesExactly() {
      // A method name is a case-sensitive token, so two spellings of a non-standard name are two methods. Folding here
      // would invent a rule HTTP does not have, and would quietly route PROPFIND against propfind's handler.
      assertThat(HttpMethod.of("PROPFIND")).isNotEqualTo(HttpMethod.of("propfind"));
      assertThat(HttpMethod.of("PROPFIND").text()).isEqualTo("PROPFIND");
      assertThat(HttpMethod.of("propfind").text()).isEqualTo("propfind");
    }

    @Test
    void treatsEqualNamesAsEqualSoACollectionWorks() {
      assertThat(HttpMethod.of("PURGE")).isEqualTo(HttpMethod.of("PURGE"));
      assertThat(HttpMethod.of("PURGE")).hasSameHashCodeAs(HttpMethod.of("PURGE"));
      // A router — a later phase, but the same values — keys on these objects, so equals and hashCode have to agree.
    }

    @Test
    void isNotEqualToSomethingThatIsNotAMethod() {
      assertThat(HttpMethod.GET).isNotEqualTo("GET");
      // Deliberate. A String comparison would compile nowhere, which is the point of the type: "GET".equals(method)
      // reads as a bug the moment it is written.
    }
  }

  @Nested
  @DisplayName("refused input")
  class Refusals {

    @Test
    void refusesANullMethodAndSaysWhyItIsNotAGet() {
      assertThatThrownBy(() -> HttpMethod.of(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("malformed request line, not a GET");
    }

    @Test
    void refusesABlankMethod() {
      assertThatThrownBy(() -> HttpMethod.of(""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be blank");
      assertThatThrownBy(() -> HttpMethod.of("   "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be blank");
      // Guessing GET for an empty method would serve a resource to a request that never asked for one, which is the
      // one failure mode a method parser must not have.
    }

    @Test
    void refusesCharactersThatCannotOccurInAMethodToken() {
      List<String> accepted = new ArrayList<>();
      for (String method : List.of("GET POST", "GET\r\nX-Injected: 1", "GET\t", "GÉT", "GET/POST", "GET,POST")) {
        Throwable failure = catchThrowableOfType(() -> HttpMethod.of(method), IllegalArgumentException.class);
        if (failure == null) {
          accepted.add(method);
        } else {
          assertThat(failure).hasMessageContaining("HTTP token")
              .satisfies(error -> assertThat(error.getMessage()).doesNotContain("\r").doesNotContain("\n"));
        }
      }
      assertThat(accepted).as("invalid method tokens accepted by the parser").isEmpty();
    }

    @Test
    void acceptsEveryLegalTokenPunctuationCharacter() {
      String punctuation = "!#$%&'*+-.^_`|~";
      assertThat(HttpMethod.of(punctuation).text()).isEqualTo(punctuation);
      assertThat(HttpMethod.of("M-SEARCH").text()).isEqualTo("M-SEARCH");
    }
  }

  @Nested
  @DisplayName("the surface stays a name")
  class Surface {

    @Test
    void exposesNoBehaviourBeyondItsOwnName() {
      assertThat(Arrays.stream(HttpMethod.class.getDeclaredMethods())
          .filter(method -> !method.isSynthetic())
          .map(java.lang.reflect.Method::getName)
          .distinct())
          .containsExactlyInAnyOrder("of", "text", "equals", "hashCode", "toString");
      // No requiresBody(), no isIdempotent(), no safe(): each of those is an HTTP semantics table the contract would
      // own, and the first real consumer of one is a router — which is not in this phase (ADR-0018 §10).
    }
  }
}
