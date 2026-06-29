package com.barezen.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The header model: case-insensitive names, repeated values, absent versus empty, and the two refusals that make a
 * header set safe to build out of request data.
 *
 * <p>Grouped by the rule rather than by the method, because a method-level test of {@code first} would pass whether
 * names were matched case-insensitively or not.
 */
class HeadersTest {

  @Nested
  @DisplayName("names match case-insensitively and keep the case they were given")
  class NameMatching {

    @Test
    void findsAFieldWhateverCaseEitherSideUsed() {
      Headers headers = Headers.of("Content-Type", "application/json");

      assertThat(headers.first("content-type")).contains("application/json");
      assertThat(headers.first("CONTENT-TYPE")).contains("application/json");
      assertThat(headers.first("Content-Type")).contains("application/json");
    }

    @Test
    void reportsNamesTheWayTheApplicationSpelledThem() {
      Headers headers = Headers.of("X-Trace-Id", "1").with("Content-Type", "text/plain");

      // A response built with these fields goes to the socket spelled as written. Lower-casing on the way in would
      // change the message, and a Map with lower-cased keys would fail this test.
      assertThat(headers.names()).containsExactly("X-Trace-Id", "Content-Type");
    }

    @Test
    void reportsANameOnceEvenWhenTheFieldArrivedInTwoCases() {
      Headers headers = Headers.of("Set-Cookie", "a=1").with("set-cookie", "b=2");

      assertThat(headers.names()).containsExactly("Set-Cookie");
      assertThat(headers.values("SET-COOKIE")).containsExactly("a=1", "b=2");
    }

    @Test
    void matchesNoNameByPrefixOrSubstring() {
      Headers headers = Headers.of("X-Trace-Id", "long").with("Accept", "*/*");

      assertThat(headers.first("A")).isEmpty();
      assertThat(headers.first("X-Trace")).isEmpty();
      assertThat(headers.values("Accept ")).isEmpty();
      // "X-Trace" must not read "X-Trace-Id": a prefix match would let one field answer for another.
      assertThat(headers.first("X-Trace-Id")).contains("long");
    }
  }

  @Nested
  @DisplayName("a repeated field keeps every value, in order")
  class RepeatedFields {

    @Test
    void keepsBothValuesOfANameSentTwice() {
      Headers headers = Headers.of("Link", "</a>; rel=prev").with("Link", "</b>; rel=next");

      assertThat(headers.values("link")).containsExactly("</a>; rel=prev", "</b>; rel=next");
    }

    @Test
    void joinsNothingWithAComma() {
      Headers headers = Headers.of("Set-Cookie", "session=1; Path=/").with("Set-Cookie", "cart=2; Path=/");

      List<String> values = headers.values("Set-Cookie");

      // Folding repeated lines into one comma-joined value is the normalisation that corrupts exactly the fields that
      // repeat, because a cookie attribute list holds commas and semicolons a client reads per line.
      assertThat(values).hasSize(2);
      assertThat(values.get(0)).isEqualTo("session=1; Path=/");
      assertThat(values.get(1)).isEqualTo("cart=2; Path=/");
    }

    @Test
    void givesTheFirstValueForTheFieldsThatHaveOne() {
      Headers headers = Headers.of("X-Cursor", "a").with("X-Cursor", "b");

      assertThat(headers.first("X-Cursor")).contains("a");
    }

    @Test
    void keepsTheOrderFieldsArrivedInAcrossSeveralNames() {
      Headers headers = Headers.of("B-One", "1").with("A-Two", "2").with("B-One", "3").with("C-Three", "4");

      assertThat(headers.names()).containsExactly("B-One", "A-Two", "C-Three");
      assertThat(headers.values("b-one")).containsExactly("1", "3");
      // The entries list is what an adapter writes out; a HashMap of names would make this unanswerable.
      assertThat(headers.toString()).isEqualTo("Headers[4 fields: B-One, A-Two, C-Three]");
    }
  }

  @Nested
  @DisplayName("absent and empty are different facts")
  class AbsentVersusEmpty {

    @Test
    void readsAFieldSentWithNoValueAsPresentAndEmpty() {
      Headers headers = Headers.of("X-Cursor", "");

      assertThat(headers.first("X-Cursor")).contains("");
      assertThat(headers.values("X-Cursor")).containsExactly("");
      assertThat(headers.names()).containsExactly("X-Cursor");
    }

    @Test
    void readsAFieldThatWasNeverSentAsAbsent() {
      Headers headers = Headers.of("X-Cursor", "");

      assertThat(headers.first("X-Other")).isEmpty();
      assertThat(headers.values("X-Other")).isEmpty();
      assertThat(headers.first("X-Other")).isNotEqualTo(headers.first("X-Cursor"));
    }

    @Test
    void reportsAbsenceTheWayCoreDoes() {
      Headers headers = Headers.of("Accept", "*/*");

      assertThat(headers.first("Accept")).isInstanceOf(Optional.class).contains("*/*");
      assertThat(headers.first("Authorization")).isEqualTo(Optional.empty());
    }
  }

  @Nested
  @DisplayName("a name is an HTTP token and a value holds no control character")
  class RefusedInput {

    @Test
    void refusesANameThatIsNotAToken() {
      // Each of these turns one field into two, or a name into a name plus a value.
      assertThatThrownBy(() -> Headers.of("Bad Name", "1"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not an HTTP token");
      assertThatThrownBy(() -> Headers.of("Bad:Name", "1"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not an HTTP token");
      assertThatThrownBy(() -> Headers.of("Bad,Name", "1"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not an HTTP token");
      assertThatThrownBy(() -> Headers.of("Bad/Name", "1"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not an HTTP token");
      assertThatThrownBy(() -> Headers.of("", "1"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be empty");
    }

    @Test
    void acceptsEveryNameAnApplicationActuallyUses() {
      assertThatCode(() -> Headers.of("Content-Type", "a")
          .with("X-Trace-Id", "b")
          .with("If-None-Match", "c")
          .with("_private", "d")
          .with("Token2", "e"))
          .doesNotThrowAnyException();
      // Letters, digits, hyphen and underscore are all token characters. A rule that refused them would refuse real
      // fields and push callers to assemble headers somewhere with no validation at all.
    }

    @Test
    void refusesAValueHoldingACarriageReturnOrLineFeed() {
      // HTTP response splitting: the classic way an echoed value becomes a second field or a new response line.
      assertThatThrownBy(() -> Headers.of("X-Redirect", "https://a\r\nX-Admin: true"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("control character");
      assertThatThrownBy(() -> Headers.of("X-Redirect", "https://a\nX-Admin: true"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("control character");
    }

    @Test
    void refusesAValueHoldingAnyOtherControlCharacter() {
      assertThatThrownBy(() -> Headers.of("X-Trace", "a\u0007b"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("control character");
      assertThatThrownBy(() -> Headers.of("X-Trace", "a\u007fb"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("control character");
      // Tab is below 0x20 as well, so it is refused: some clients read a tab inside a value as the folded
      // continuation of the previous field line, which is a second way to smuggle a field.
      assertThatThrownBy(() -> Headers.of("X-Trace", "a\tb"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("control character");
    }

    @Test
    void neverRepeatsTheRejectedCharacterInItsOwnMessage() {
      // The message goes to a terminal and a log. Echoing a raw CR/LF there would let the refused value keep working
      // in the place the refusal was meant to stop it.
      assertThatThrownBy(() -> Headers.of("X-Trace", "safe\r\nX-Admin: true"))
          .isInstanceOf(IllegalArgumentException.class)
          .satisfies(failure -> {
            String message = String.valueOf(failure.getMessage());
            assertThat(message).doesNotContain("\r");
            assertThat(message).doesNotContain("\n");
            assertThat(message).contains("\\u000D").contains("\\u000A");
          });
    }

    @Test
    void refusesNullsNamingWhatWasRefused() {
      assertThatThrownBy(() -> Headers.of(null, "1"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("header name");
      assertThatThrownBy(() -> Headers.of("X-Trace", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("X-Trace");
      assertThatThrownBy(() -> Headers.empty().with("X-Trace", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("X-Trace");
    }

    @Test
    void refusesToReadWithANullOrBlankName() {
      assertThatThrownBy(() -> Headers.of("A", "1").first(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("header name to read");
      assertThatThrownBy(() -> Headers.of("A", "1").values(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("header name to read");
      assertThatThrownBy(() -> Headers.of("A", "1").first(""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be blank");
      assertThatThrownBy(() -> Headers.of("A", "1").values(""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be blank");
      // Both readers refuse the same way. An empty name matches no field, so returning an empty list would report
      // "no such header" for a caller that never named one — and returning one for `values` while throwing for
      // `first` would make the two accessors disagree about the same mistake.
      // Reading "" is not "read nothing": it is a caller that has lost the name and would get a guess.
    }
  }

  @Nested
  @DisplayName("immutable, and a value cannot be changed behind a handover")
  class Immutability {

    @Test
    void doesNotChangeWhenTheCallerKeepsBuilding() {
      List<String> collected = new ArrayList<>();
      collected.add("a=1");
      Headers headers = Headers.of("Set-Cookie", collected.get(0));
      collected.add("b=2");

      assertThat(headers.names()).containsExactly("Set-Cookie");
      assertThat(headers.values("Set-Cookie")).containsExactly("a=1");
      assertThat(headers).hasToString("Headers[1 field: Set-Cookie]");
    }

    @Test
    void handsBackListsThatCannotBeEdited() {
      Headers headers = Headers.of("Link", "</a>").with("Link", "</b>");

      assertThatThrownBy(() -> headers.values("Link").add("</c>"))
          .isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(() -> headers.names().add("X"))
          .isInstanceOf(UnsupportedOperationException.class);
      // A caller that appended to a returned list would otherwise be editing the headers of whoever handed them over.
    }

    @Test
    void withReturnsANewInstanceAndLeavesTheOriginalAlone() {
      Headers original = Headers.of("A", "1");
      Headers extended = original.with("B", "2");

      assertThat(extended).isNotSameAs(original);
      assertThat(original.names()).containsExactly("A");
      assertThat(extended.names()).containsExactly("A", "B");
    }

    @Test
    void sharesOneEmptyInstanceBecauseItHoldsNothing() {
      assertThat(Headers.empty()).isSameAs(Headers.empty());
      assertThat(Headers.empty().names()).isEmpty();
      assertThat(Headers.empty().first("Anything")).isEmpty();
    }
  }

  @Nested
  @DisplayName("toString prints names, never values")
  class Printing {

    @Test
    void omitsValuesSoACredentialCannotReachALogThroughIt() {
      Headers headers = Headers.of("Authorization", "Bearer super-secret-token")
          .with("Cookie", "session=abcdef")
          .with("Content-Type", "application/json");

      String printed = headers.toString();

      assertThat(printed).contains("Authorization").contains("Content-Type");
      assertThat(printed).doesNotContain("super-secret-token").doesNotContain("session=abcdef");
      assertThat(printed).isEqualTo("Headers[3 fields: Authorization, Cookie, Content-Type]");
    }
  }
}
