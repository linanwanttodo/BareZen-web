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
      assertThat(new Response(200, Headers.empty(), null).body()).isEmpty();
      assertThat(new Response(200, Headers.empty(), new byte[0]).body()).isEmpty();
    }

    @Test
    void keepsAnEmptyBodyAndNoBodyAsTheSameFactToTheContract() {
      // Two spellings of "nothing to send", one meaning to this type. The wire distinguishes them anyway — an adapter
      // writes Content-Length: 0 for both — which is why Response does not carry a third state for "deliberately empty"
      // versus "absent" (ADR-0027 §3, and the reason there is no noBody()).
      byte[] empty = new byte[0];

      assertThat(new Response(200, Headers.empty(), null).body()).isEqualTo(empty);
      assertThat(new Response(200, Headers.empty(), empty).body()).isEqualTo(empty);
      assertThat(new Response(200, Headers.empty(), empty)).hasToString("Response[200, 0 body bytes, 0 fields]");
      assertThat(new Response(200, Headers.empty(), null)).hasToString("Response[200, 0 body bytes, 0 fields]");
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

  /**
   * A status that has no place for a body is not given one, and a framing field is not the handler's to write
   * (ADR-0027 §4, §5).
   *
   * <p>These refusals exist because the alternative was measured on this framework's own adapter rather than argued from
   * the specification: a {@code 204} carrying bytes is written, then discarded by the server with a warning in the server
   * log and nothing in the client's answer; a {@code 204} carrying a {@code Content-Length} makes
   * {@code java.net.http.HttpClient} abandon the response; and a {@code 304} or {@code HEAD} response with a handler-set
   * length arrives with two of them. A contract that permits a shape the wire cannot honour is a contract that decides
   * the failure happens somewhere the developer is not looking.
   */
  @Nested
  @DisplayName("a status with no body cannot be given one")
  class BodylessStatuses {

    /** The status families HTTP defines as carrying no message body. */
    private static final int[] NO_BODY = {100, 101, 103, 199, 204, 205, 304};

    @Test
    void refusesEveryStatusThatCarriesNoBodyWhenBytesWereGiven() {
      byte[] body = "hello".getBytes(StandardCharsets.UTF_8);

      for (int status : NO_BODY) {
        assertThatThrownBy(() -> new Response(status, Headers.empty(), body))
            .as("a %d response with a body must be refused at construction", status)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot carry a body")
            .hasMessageContaining("5 body bytes");
      }
    }

    @Test
    void acceptsTheSameStatusesWithNoBodyAtAll() {
      // Empty is not the thing being refused — a 204 with nothing in it is the correct answer, and refusing it would
      // make the status unusable.
      for (int status : NO_BODY) {
        assertThat(new Response(status, Headers.empty(), null).body()).isEmpty();
        assertThat(new Response(status, Headers.empty(), new byte[0]).body()).isEmpty();
      }
    }

    @Test
    void refuses204RatherThanLetTheServerDropTheBodySilently() {
      // The specific case the phase's directive names: the JDK logs
      // "sendResponseHeaders: rCode = 204: forcing contentLen = -1" and the client gets a 204 with none of the bytes the
      // handler returned. A refusal here means the developer sees the line they wrote; a drop there means the next
      // person reads a socket that disagrees with the code.
      assertThatThrownBy(() -> new Response(204, Headers.of("Content-Type", "text/plain"),
          "deleted".getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("204")
          .hasMessageContaining("silent disagreement");
    }

    @Test
    void refuses304BecauseItsBodyWouldNeverArrive() {
      // Measured: the JDK suppresses the body of a 304 whatever it is handed, and warns only when a length was declared
      // through its own API. A handler that returns a full representation with a 304 has been answered something else.
      assertThatThrownBy(() -> new Response(304, Headers.of("ETag", "\"7\""),
          "the whole representation".getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("304")
          .hasMessageContaining("no message body");
    }

    @Test
    void stillAllowsABodyForEveryStatusThatHasAPlaceForOne() {
      byte[] body = "hello".getBytes(StandardCharsets.UTF_8);

      // 200, 201, 206, 301, 400, 404, 405, 500 and the rest are ordinary: none of them is in the no-body family, and a
      // rule that over-refuses is as wrong as one that under-refuses — it would make a 200 empty answer impossible.
      for (int status : new int[] {200, 201, 203, 206, 300, 301, 302, 400, 404, 405, 418, 451, 500, 503, 599}) {
        assertThat(new Response(status, Headers.empty(), body).body()).isEqualTo(body);
      }
    }
  }

  @Nested
  @DisplayName("framing fields belong to the writer, not the handler")
  class FramingFields {

    @Test
    void refusesContentLengthOnAResponse() {
      assertThatThrownBy(() -> new Response(200, Headers.of("Content-Length", "5"),
          "hello".getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not carry Content-Length");
    }

    @Test
    void refusesTransferEncodingOnAResponse() {
      // Measured: a handler-written "Transfer-Encoding: chunked" reaches the wire beside an identity-encoded body,
      // which is the contradictory-framing shape a downstream proxy desynchronises on.
      assertThatThrownBy(() -> new Response(200, Headers.of("Transfer-Encoding", "chunked"),
          "hello".getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not carry Transfer-Encoding");
    }

    @Test
    void refusesBothNamesInAnyCaseAndOnAnyStatus() {
      // Case-insensitive because that is how a field name is matched everywhere else in this contract, and on every
      // status because the field is wrong for the type rather than for one code: the 304 and HEAD cases were the ones
      // that produced a duplicate length on the wire, and the 204 case was the one that broke a real client.
      for (int status : new int[] {200, 204, 304, 404}) {
        assertThatThrownBy(() -> new Response(status, Headers.of("content-length", "0"), null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Response(status, Headers.of("TRANSFER-ENCODING", "gzip"), null))
            .isInstanceOf(IllegalArgumentException.class);
      }
    }

    @Test
    void refusesAnEmptyValueForThoseNamesToo() {
      // "Content-Length:" is still a Content-Length, and an empty value would be sent as an empty field line that some
      // clients read as a zero length. Presence is what is refused, not a particular number.
      assertThatThrownBy(() -> new Response(200, Headers.of("Content-Length", ""), null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesADuplicatedLengthWhetherTheTwoValuesAgreeOrNot() {
      // Phase 2.10 §E.3 asks what happens to "Content-Length: 5" beside "content-length: 5", and to 5 beside 6. The
      // answer is one answer for both pairs: refused, because the shape is refused rather than a value checked. An
      // agreement-insists-on-the-lie reading (accept when both say 5) would need the contract to parse framing values
      // it has decided it does not own, and would still leave the disagreeing pair to be handled somewhere else.
      assertThatThrownBy(() -> new Response(200, Headers.of("Content-Length", "5").with("content-length", "5"),
          "hello".getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not carry Content-Length");
      assertThatThrownBy(() -> new Response(200, Headers.of("Content-Length", "5").with("content-length", "6"),
          "hello".getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not carry Content-Length");
      // The same for the other name, and mixed-case spellings of it — Headers keeps one entry per distinct spelling,
      // so both lines below are two field lines on their way to the wire (ADR-0027 §5's duplicate row measured there).
      assertThatThrownBy(() -> new Response(200,
          Headers.of("Transfer-Encoding", "chunked").with("TRANSFER-ENCODING", "chunked"),
          "hello".getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not carry Transfer-Encoding");
    }

    @Test
    void letsEveryOtherFieldThroughIncludingItsOwnLengthsAndTypes() {
      // The refusal is about two names, not about sizing information in general: Content-Range, Content-Type and
      // X-Total-Count all describe an answer, while the refused pair describes the connection.
      Response response = new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8")
          .with("Content-Range", "bytes 0-4/5").with("X-Total-Count", "5"), "hello".getBytes(StandardCharsets.UTF_8));

      assertThat(response.headers().first("X-Total-Count")).contains("5");
    }

    @Test
    void namesTheStatusAndTheFieldRatherThanGuessingAtADrop() {
      assertThatThrownBy(() -> new Response(304, Headers.of("Content-Length", "12"), null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("on a 304 response")
          .hasMessageContaining("derives Content-Length from the body");
    }
  }
}
