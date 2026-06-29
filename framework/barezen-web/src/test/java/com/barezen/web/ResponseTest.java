package com.barezen.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The response contract: a status, fields and a body, produced once and not edited afterwards.
 */
class ResponseTest {

  @Nested
  @DisplayName("the three facts it carries")
  class Surface {

    @Test
    void carriesStatusHeadersBody() {
      Response response = new Response(
          201,
          Headers.of("Location", "/orders/7"),
          "created".getBytes(StandardCharsets.UTF_8));

      assertThat(response.status()).isEqualTo(201);
      assertThat(response.headers().first("location")).contains("/orders/7");
      assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("created");
    }

    @Test
    void exposesExactlyTheThreeAccessorsAndNothingElse() {
      // No send(), no write(), no setStatus, no body(OutputStream): a response cannot commit anything to a client,
      // which is what lets a later component still change it (ADR-0018 §3).
      assertThat(java.util.Arrays.stream(Response.class.getDeclaredMethods())
          .filter(method -> !method.isSynthetic())
          .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
          .map(java.lang.reflect.Method::getName)
          .distinct())
          .containsExactlyInAnyOrder("status", "headers", "body", "toString");
    }
  }

  @Nested
  @DisplayName("the status is a number HTTP can carry")
  class Status {

    @Test
    void acceptsEveryRangeTheSpecificationDefines() {
      for (int status : new int[] {100, 204, 302, 404, 451, 500, 599}) {
        assertThat(new Response(status, Headers.empty(), null).status()).isEqualTo(status);
      }
    }

    @Test
    void refusesAStatusThatCannotBeWrittenOnAResponseLine() {
      assertThatThrownBy(() -> new Response(99, Headers.empty(), null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("between 100 and 599");
      assertThatThrownBy(() -> new Response(600, Headers.empty(), null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("between 100 and 599");
      assertThatThrownBy(() -> new Response(0, Headers.empty(), null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("between 100 and 599");
      assertThatThrownBy(() -> new Response(-1, Headers.empty(), null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("between 100 and 599");
      // The client branches on this number, so an unusable one is the wrong answer delivered as a success — refused
      // at construction, where the developer is, rather than at the socket, where the caller is.
    }

    @Test
    void keepsTheNumberRatherThanAnEnumOfCodes() {
      // 418, 451 and the private 5xx range are all legal on the wire; an enum would be a list that needs a release to
      // grow, and every comparison would become a lookup of a constant somebody chose.
      assertThat(new Response(418, Headers.empty(), null).status()).isEqualTo(418);
    }
  }

  @Nested
  @DisplayName("the body is in memory and copied both ways")
  class Body {

    @Test
    void treatsNullAsNoBody() {
      assertThat(new Response(204, Headers.empty(), null).body()).isEmpty();
      assertThat(new Response(204, Headers.empty(), new byte[0]).body()).isEmpty();
    }

    @Test
    void doesNotAliasetheHandlerArray() {
      byte[] produced = "hello".getBytes(StandardCharsets.UTF_8);
      Response response = new Response(200, Headers.empty(), produced);

      // A handler that reuses a buffer per request — which is the obvious optimisation — must not be able to change
      // what it already returned.
      java.util.Arrays.fill(produced, (byte) '!');

      assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    @Test
    void handsBackACopySoNobodyCanEditWhatWillBeSent() {
      Response response = new Response(200, Headers.empty(), "plain".getBytes(StandardCharsets.UTF_8));

      response.body()[0] = 'X';

      assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("plain");
    }

    @Test
    void keepsBinaryContentUnchanged() {
      byte[] zip = {80, 75, 3, 4, -1, -2, 0, 7};
      Response response = new Response(200, Headers.of("Content-Type", "application/zip"), zip);

      assertThat(response.body()).isEqualTo(zip);
      // No charset conversion anywhere in the contract, which is what lets one type carry JSON, an image and an
      // archive. What the bytes mean is Content-Type's business, and that is an application's field to set.
    }
  }

  @Nested
  @DisplayName("refused construction")
  class Refusals {

    @Test
    void refusesANullHeaderSetAndNamesWhatWasMissing() {
      assertThatThrownBy(() -> new Response(200, null, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("response headers");
    }

    @Test
    void refusesInvalidHeaderContentComingThroughAResponse() {
      // The response is where an application writes a value built out of request data, so this is the path a splitting
      // attack actually takes.
      assertThatThrownBy(() -> new Response(302, Headers.of("Location", "https://h\r\nX-Injected: 1"), null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("control character");
    }
  }

  @Nested
  @DisplayName("toString carries neither body nor field value")
  class Printing {

    @Test
    void printsStatusBodySizeAndFieldCount() {
      byte[] body = "{\"error\":\"invalid credentials, password=hunter2\"}".getBytes(StandardCharsets.UTF_8);
      Response response = new Response(
          401, Headers.of("WWW-Authenticate", "Bearer realm=\"api\", error=\"invalid_token\""), body);

      String printed = response.toString();

      // The size is written from the same array the response carries, so a wrong count here is a wrong count on the
      // wire: Content-Length is derived from this number by whoever writes it.
      assertThat(printed).isEqualTo("Response[401, " + body.length + " body bytes, 1 field]");
      assertThat(printed).doesNotContain("hunter2").doesNotContain("invalid_token");
    }

    @Test
    void printsAPluralForNoFieldsAndSeveralFields() {
      assertThat(new Response(200, Headers.empty(), null).toString()).isEqualTo("Response[200, 0 body bytes, 0 fields]");
      assertThat(new Response(200, Headers.of("A", "1").with("B", "2"), new byte[1]).toString())
          .isEqualTo("Response[200, 1 body byte, 2 fields]");
    }
  }
}
