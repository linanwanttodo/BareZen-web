package com.barezen.http.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Response;
import com.barezen.web.Router;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the wire carries for every response-body shape, on a real socket and through a real client (ADR-0027 §6, §7, §8).
 *
 * <p>The three concepts this class keeps apart, because collapsing them was the bug the phase was written against:
 *
 * <ul>
 *   <li><b>an empty {@code byte[]}</b> — a body of no bytes, which a {@code 200} may carry and which reaches the client
 *       as {@code Content-Length: 0} and nothing else;
 *   <li><b>a status that has no place for a body</b> — {@code 1xx}, {@code 204} and {@code 304}, for which the contract
 *       refuses bytes (ADR-0027 §4) so no length is written either;
 *   <li><b>a {@code HEAD} response</b> — no bytes on the wire, and a length that is the representation's. "No body" and
 *       "no length" are different statements, and HEAD is the case where the first is true and the second is false.
 * </ul>
 *
 * <p>A test that asserted only "no bytes arrived" would pass for all three and distinguish none of them, which is why the
 * assertions below are about field lines: several of the shapes are defined by whether a {@code Content-Length} is
 * present, how many times, and with what value. {@link RawHttp} reads them uninterpreted; where the claim is about how a
 * real client reacts, {@code java.net.http.HttpClient} is used as well — the implementation that refused a {@code 204}
 * carrying a length, which is the evidence for refusing the field on the contract (ADR-0027 §5).
 *
 * <p>Everything here runs against the shipped adapter on a real {@code HttpServer}, because {@code HttpExchange} cannot
 * be built or faked (see {@link JdkErrorSemanticsTest}).
 */
class JdkResponseBodySemanticsTest {

  private static final byte[] HELLO = "hello".getBytes(StandardCharsets.UTF_8);

  private static int start(JdkHttpServerAdapter adapter) {
    adapter.start();
    return adapter.port().orElseThrow(() -> new AssertionError("a started adapter reports the port it bound"));
  }

  private static JdkHttpServerAdapter on(Router router) {
    return new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router);
  }

  private static Router answer(HttpMethod method, String path, Response value) {
    return Router.builder().route(method, path, (request, parameters) -> value).build();
  }

  /**
   * A {@code 200} with an empty body: a real answer, carried as {@code Content-Length: 0} and no bytes.
   *
   * <p>Pinned against the JDK's {@code length == 0} branch, which means <em>chunked</em> rather than empty (ADR-0022 §6):
   * an adapter that passed the body's length through literally would send a transfer-encoding line for a response with
   * nothing in it.
   */
  @Nested
  @DisplayName("a 200 with an empty body")
  class EmptyBodies {

    @Test
    void anEmptyByteArrayArrivesAsZeroLengthAndNoBytes() throws IOException {
      try (JdkHttpServerAdapter adapter = on(
          answer(HttpMethod.GET, "/h", new Response(200, Headers.of("Content-Type", "text/plain"), new byte[0])))) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/h", List.of(), null);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.valuesOf("Content-Length")).containsExactly("0");
        assertThat(answer.valuesOf("Transfer-Encoding")).isEmpty();
        assertThat(answer.body()).isEmpty();
      }
    }

    @Test
    void aNullBodyArrivesIdentically() throws IOException {
      try (JdkHttpServerAdapter adapter = on(
          answer(HttpMethod.GET, "/h", new Response(200, Headers.of("Content-Type", "text/plain"), null)))) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/h", List.of(), null);

        // The same field lines as the test above: "empty" and "absent" are one fact to Response, and they reach the wire
        // as one fact rather than as two shapes a client has to distinguish.
        assertThat(answer.valuesOf("Content-Length")).containsExactly("0");
        assertThat(answer.valuesOf("Transfer-Encoding")).isEmpty();
        assertThat(answer.body()).isEmpty();
      }
    }

    @Test
    void aNonEmptyBodyArrivesWithItsLengthAndItsBytes() throws IOException {
      try (JdkHttpServerAdapter adapter = on(
          answer(HttpMethod.GET, "/h", new Response(200, Headers.of("Content-Type", "text/plain"), HELLO)))) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/h", List.of(), null);

        assertThat(answer.valuesOf("Content-Length")).containsExactly("5");
        assertThat(answer.body()).isEqualTo("hello");
      }
    }
  }

  /**
   * {@code 204}: neither bytes nor a length, which is what the status means rather than what was omitted.
   *
   * <p>Measured on JDK 17 and JDK 21 alike: a no-body status makes the server drop any declared length and log a warning
   * when one was handed to it. The contract's refusal (ADR-0027 §4) means the adapter never has one to hand, so the shape
   * that reaches the wire is the legal one: a status line, the handler's own fields, no {@code Content-Length}, no
   * {@code Transfer-Encoding}, no bytes.
   */
  @Nested
  @DisplayName("a 204 carries neither bytes nor a length")
  class TwoOhFour {

    @Test
    void aTwoOhFourIsWrittenWithNoBodyAndNoLength() throws IOException {
      AtomicInteger calls = new AtomicInteger();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/h", (request, parameters) -> {
            calls.incrementAndGet();
            return new Response(204, Headers.of("X-Reason", "deleted"), null);
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/h", List.of(), null);

        assertThat(answer.status()).isEqualTo(204);
        assertThat(answer.firstOf("X-Reason")).isEqualTo("deleted");
        assertThat(answer.valuesOf("Content-Length")).isEmpty();
        assertThat(answer.valuesOf("Transfer-Encoding")).isEmpty();
        assertThat(answer.body()).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
      }
    }

    @Test
    void aHandlerCannotGiveATwoOhFourABody() {
      // Refused before the adapter is involved, which is what makes the case above the only case: bytes cannot reach a
      // write that the server would then undo. The measured alternative was a 204 whose body was dropped silently, with
      // the only trace a WARNING in the server's own log.
      assertThatThrownBy(() -> new Response(204, Headers.empty(), HELLO))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot carry a body");
    }

    @Test
    void aRealClientAcceptsATwoOhFourAndKeepsTheConnection() throws Exception {
      // The same client threw "unexpected content length header with 204 response" when a 204 carried a length, which is
      // the evidence for refusing that field on the contract rather than merely documenting it (ADR-0027 §5). With no
      // length to be surprised by, a keep-alive sequence against one server completes.
      Router router = Router.builder()
          .route(HttpMethod.GET, "/n", (request, parameters) -> new Response(204, Headers.empty(), null))
          .route(HttpMethod.GET, "/t", (request, parameters) -> new Response(200, Headers.empty(), HELLO))
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);
        HttpClient client = client();

        HttpResponse<byte[]> first = send(client, port, "GET", "/n");
        HttpResponse<byte[]> second = send(client, port, "GET", "/t");

        assertThat(first.statusCode()).isEqualTo(204);
        assertThat(first.body()).isEmpty();
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(new String(second.body(), StandardCharsets.UTF_8)).isEqualTo("hello");
      }
    }
  }

  /**
   * {@code 304}: the same refusal as 204, and the one consequence of it this phase records rather than hides.
   *
   * <p>HTTP lets a 304 carry {@code Content-Length} stating what a {@code 200} would have sent, while forbidding its body.
   * Expressing that length through this contract would mean handing the adapter bytes it must not write — which is the
   * silent-discarding shape ADR-0027 §4 refuses for 204, under a different status. So the rule is uniform and the loss is
   * real: <b>a 304 through this framework cannot state the stored representation's length.</b> It is recorded as a
   * limitation (ADR-0027 §9) rather than worked around by letting one status keep a body, because "a body is refused
   * except where a body is secretly a length" is the ambiguity this phase was written to remove.
   */
  @Nested
  @DisplayName("a 304 carries no body and, as a consequence, no length")
  class ThreeOhFour {

    @Test
    void aThreeOhFourIsWrittenWithNoBodyAndNoLength() throws IOException {
      AtomicInteger calls = new AtomicInteger();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/doc", (request, parameters) -> {
            calls.incrementAndGet();
            return new Response(304, Headers.of("ETag", "\"7\""), null);
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/doc", List.of(), null);

        assertThat(answer.status()).isEqualTo(304);
        // The fields a 304 exists to carry survive; the length it would be allowed to state does not, because nothing
        // here can express it without a body.
        assertThat(answer.firstOf("ETag")).isEqualTo("\"7\"");
        assertThat(answer.valuesOf("Content-Length")).isEmpty();
        assertThat(answer.valuesOf("Transfer-Encoding")).isEmpty();
        assertThat(answer.body()).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
      }
    }

    @Test
    void aHandlerCannotGiveAThreeOhFourABody() {
      assertThatThrownBy(() -> new Response(304, Headers.of("ETag", "\"7\""), HELLO))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("304")
          .hasMessageContaining("no message body");
    }

    @Test
    void aRealClientReadsAEmptyThreeOhFourAndKeepsTheConnection() throws Exception {
      // Measured before the refusal: a 304 with a handler-set Content-Length was read by java.net.http without blocking,
      // because a 304's length is advisory to a client. That tolerance is a client's behaviour rather than this server's
      // contract, and the shape that produced it — a field the server did not write — is the one now refused.
      Router router = Router.builder()
          .route(HttpMethod.GET, "/c", (request, parameters) -> new Response(304, Headers.of("ETag", "\"7\""), null))
          .route(HttpMethod.GET, "/t", (request, parameters) -> new Response(200, Headers.empty(), HELLO))
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);
        HttpClient client = client();

        HttpResponse<byte[]> first = send(client, port, "GET", "/c");
        HttpResponse<byte[]> second = send(client, port, "GET", "/t");

        assertThat(first.statusCode()).isEqualTo(304);
        assertThat(first.body()).isEmpty();
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(new String(second.body(), StandardCharsets.UTF_8)).isEqualTo("hello");
      }
    }

    @Test
    void anUnconditionalGetNeverBecomesAThreeOhFourByItself() throws IOException {
      // A 304 is only an answer to a conditional request, and this framework has no validator: a handler that returns one
      // for any GET gets exactly the framing above, which is why the status is a handler's choice and not an adapter's
      // inference (ADR-0023 §1).
      try (JdkHttpServerAdapter adapter = on(
          answer(HttpMethod.GET, "/doc", new Response(304, Headers.of("ETag", "\"7\""), null)))) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/doc", List.of(), null);

        assertThat(answer.status()).isEqualTo(304);
      }
    }
  }

  /**
   * {@code HEAD}: the fields a {@code GET} would have produced, the representation's length, and never any bytes.
   *
   * <p>The distinction ADR-0027 §6 makes: a HEAD response has no body and does have a length. Measured on both JDKs, the
   * server suppresses the bytes and does not suppress a length the adapter wrote into the response fields — which is why
   * the length is written there rather than declared through {@code sendResponseHeaders}, where the JDK's HEAD branch
   * discards it and logs a warning.
   */
  @Nested
  @DisplayName("HEAD answers with fields and a length and no bytes")
  class HeadResponses {

    @Test
    void aHeadRequestRunsItsOwnHandlerAndStatesTheLengthWithoutSendingIt() throws IOException {
      AtomicInteger headCalls = new AtomicInteger();
      AtomicInteger getCalls = new AtomicInteger();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/doc", (request, parameters) -> {
            getCalls.incrementAndGet();
            return new Response(200, Headers.of("Content-Type", "text/plain"), HELLO);
          })
          .route(HttpMethod.HEAD, "/doc", (request, parameters) -> {
            headCalls.incrementAndGet();
            return new Response(200, Headers.of("Content-Type", "text/plain"), HELLO);
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.Response head = RawHttp.request(port, "HEAD", "/doc", List.of(), null);
        RawHttp.Response get = RawHttp.request(port, "GET", "/doc", List.of(), null);

        // Two routes, two handler calls: the router matches on the method the client sent, so HEAD is not "GET with the
        // body removed" at the routing level (ADR-0019 §2). Each method's own handler runs, once.
        assertThat(headCalls.get()).isEqualTo(1);
        assertThat(getCalls.get()).isEqualTo(1);
        assertThat(head.status()).isEqualTo(get.status());
        assertThat(head.firstOf("Content-Type")).isEqualTo("text/plain");
        // The same length on both, and no bytes on the HEAD: which is the whole of HEAD's contract, stated on the wire.
        assertThat(head.valuesOf("Content-Length")).isEqualTo(get.valuesOf("Content-Length"));
        assertThat(head.valuesOf("Content-Length")).containsExactly("5");
        assertThat(head.body()).isEmpty();
        assertThat(get.body()).isEqualTo("hello");
      }
    }

    @Test
    void aHeadOfAnEmptyRepresentationStatesZeroBytes() throws IOException {
      // "The representation is empty" and "this is a HEAD" must not collapse into one answer: a HEAD of a zero-length
      // resource says Content-Length: 0, which is the length rather than a suppression artefact.
      try (JdkHttpServerAdapter adapter = on(answer(HttpMethod.HEAD, "/empty",
          new Response(200, Headers.of("Content-Type", "text/plain"), new byte[0])))) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "HEAD", "/empty", List.of(), null);

        assertThat(answer.valuesOf("Content-Length")).containsExactly("0");
        assertThat(answer.valuesOf("Transfer-Encoding")).isEmpty();
        assertThat(answer.body()).isEmpty();
      }
    }

    @Test
    void aHeadOfATwoOhFourStatesNoLength() throws IOException {
      // The composition the two rules have to agree on: a status that may not carry a body wins over a method that wants
      // a length. Without the status check in the bridge this line would write "Content-Length: 0" onto a 204, which is
      // the field HTTP forbids and the one java.net.http refuses — so this test is what makes the bridge's second
      // condition load-bearing rather than decorative.
      try (JdkHttpServerAdapter adapter = on(answer(HttpMethod.HEAD, "/n",
          new Response(204, Headers.of("X-Reason", "no content"), null)))) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "HEAD", "/n", List.of(), null);

        assertThat(answer.status()).isEqualTo(204);
        assertThat(answer.firstOf("X-Reason")).isEqualTo("no content");
        assertThat(answer.valuesOf("Content-Length")).isEmpty();
        assertThat(answer.body()).isEmpty();
      }
    }

    @Test
    void aHeadOfAThreeOhFourStatesNeitherALengthNorABody() throws IOException {
      // Conditional HEAD, which a cache validator does send. Both rules now say the same thing for the same reason: a 304
      // may not be handed bytes, so there is no length to state.
      try (JdkHttpServerAdapter adapter = on(answer(HttpMethod.HEAD, "/doc",
          new Response(304, Headers.of("ETag", "\"7\""), null)))) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "HEAD", "/doc", List.of(), null);

        assertThat(answer.status()).isEqualTo(304);
        assertThat(answer.firstOf("ETag")).isEqualTo("\"7\"");
        assertThat(answer.valuesOf("Content-Length")).isEmpty();
        assertThat(answer.body()).isEmpty();
      }
    }

    @Test
    void aHeadOfA405CarriesAllowAndTheLengthOfTheBodyItDoesNotSend() throws IOException {
      // The adapter's own answer rather than a handler's: a 405 has a body for a GET and none for a HEAD, and Allow has
      // to survive both. The length is what makes this a correct HEAD answer — the sixteen bytes "Method Not Allowed"
      // would have been, stated without sending them.
      try (JdkHttpServerAdapter adapter = on(
          answer(HttpMethod.GET, "/doc", new Response(200, Headers.of("Content-Type", "text/plain"), HELLO)))) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "HEAD", "/doc", List.of(), null);

        assertThat(answer.status()).isEqualTo(405);
        assertThat(answer.firstOf("Allow")).isEqualTo("GET");
        assertThat(answer.body()).isEmpty();
        assertThat(answer.valuesOf("Content-Length")).containsExactly("18");
      }
    }

    @Test
    void aHeadOfA404AndAHeadOfA500BothStateTheirLengthAndSendNothing() throws IOException {
      // Every status this module writes for a GET has a body, so each has a length for a HEAD. Measured as a set because
      // the rule is one rule: the method decides whether bytes go, the body decides the number.
      Router router = Router.builder()
          .route(HttpMethod.GET, "/boom", (request, parameters) -> {
            throw new IllegalStateException("the handler failed");
          })
          .route(HttpMethod.HEAD, "/boom", (request, parameters) -> {
            throw new IllegalStateException("the handler failed");
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.Response notFound = RawHttp.request(port, "HEAD", "/nowhere", List.of(), null);
        RawHttp.Response serverError = RawHttp.request(port, "HEAD", "/boom", List.of(), null);

        assertThat(notFound.status()).isEqualTo(404);
        assertThat(notFound.valuesOf("Content-Length")).containsExactly("9");
        assertThat(notFound.body()).isEmpty();
        assertThat(serverError.status()).isEqualTo(500);
        assertThat(serverError.valuesOf("Content-Length")).containsExactly("21");
        assertThat(serverError.body()).isEmpty();
      }
    }

    @Test
    void aHeadConnectionStaysUsableForTheNextRequest() throws Exception {
      Router router = Router.builder()
          .route(HttpMethod.HEAD, "/h", (request, parameters) -> new Response(200, Headers.empty(), HELLO))
          .route(HttpMethod.GET, "/g", (request, parameters) -> new Response(200, Headers.empty(), HELLO))
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);
        HttpClient client = client();

        HttpResponse<byte[]> head = send(client, port, "HEAD", "/h");
        HttpResponse<byte[]> get = send(client, port, "GET", "/g");

        assertThat(head.statusCode()).isEqualTo(200);
        // A real client reads the length a HEAD states and waits for no bytes: this is the combination the adapter's
        // derived field exists for, and the second request on the same connection is the proof it framed correctly.
        assertThat(head.body()).isEmpty();
        assertThat(new String(get.body(), StandardCharsets.UTF_8)).isEqualTo("hello");
      }
    }

    @Test
    void aMethodTokenThatIsNotTheUpperCaseHeadIsAnOrdinaryRequestOnTheWire() throws IOException {
      // The commitment ADR-0028 §Decision records, measured rather than assumed. HttpMethod.of() folds the case of the
      // nine known methods, so a client sending "head" reaches this dispatcher as HttpMethod.HEAD and its HEAD handler
      // runs — routing does not depend on case. The framing does: the JDK's isHeadRequest() compares "HEAD".equals(method)
      // exactly, so it treats a lowercase token as an ordinary request and computes its own length for a body it will
      // send. Judging suppression from the folded value while the server judges it from the token lets the two disagree,
      // and the server wins — which is the false "Content-length: 0" ADR-0027 §9 recorded as a limitation. This adapter
      // now compares the same raw token the server compares, so suppression and framing cannot diverge: a token that is
      // not exactly HEAD gets the body its representation has, with a true length. The three non-exact spellings below are
      // identical to a GET; only the exact "HEAD" suppresses bytes.
      AtomicInteger calls = new AtomicInteger();
      Router router = Router.builder()
          .route(HttpMethod.HEAD, "/doc", (request, parameters) -> {
            calls.incrementAndGet();
            return new Response(200, Headers.of("Content-Type", "text/plain"), HELLO);
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.Response uppercase = RawHttp.request(port, "HEAD", "/doc", List.of(), null);
        RawHttp.Response lowercase = RawHttp.request(port, "head", "/doc", List.of(), null);
        RawHttp.Response mixed = RawHttp.request(port, "Head", "/doc", List.of(), null);

        // All three run the HEAD handler once each: the route table sees HttpMethod.HEAD regardless of case.
        assertThat(calls.get()).isEqualTo(3);
        // The exact token is the only HEAD response: fields plus the representation's length, and no bytes.
        assertThat(uppercase.status()).isEqualTo(200);
        assertThat(uppercase.firstOf("Content-Type")).isEqualTo("text/plain");
        assertThat(uppercase.valuesOf("Content-Length")).containsExactly("5");
        assertThat(uppercase.body()).isEmpty();
        // A non-exact token is the ordinary request the server already treats it as: same status, same fields, the true
        // length, and the bytes a GET would have gotten. The length stays truthful rather than collapsing to a false zero.
        for (RawHttp.Response answer : List.of(lowercase, mixed)) {
          assertThat(answer.status()).isEqualTo(200);
          assertThat(answer.firstOf("Content-Type")).isEqualTo("text/plain");
          assertThat(answer.countOf("Content-Length")).isEqualTo(1);
          assertThat(answer.valuesOf("Content-Length")).containsExactly("5");
          assertThat(answer.body()).isEqualTo("hello");
        }
      }
    }
  }

  /**
   * What a {@code 1xx} status carries on this server, measured rather than described (ADR-0027 §7).
   *
   * <p>The contract refuses a body for these codes, so what remains checkable is the server's own behaviour: an interim
   * response arrives with fields and no bytes, and a code the JDK has no text for arrives with an empty reason phrase.
   * Recorded so that a later phase adding {@code 100 Continue} support reads a measurement rather than an assumption.
   */
  @Nested
  @DisplayName("1xx: bytes are refused and the status line is the JDK's")
  class Informational {

    @Test
    void anInterimStatusCarriesNoLengthAndNoBytes() throws IOException {
      try (JdkHttpServerAdapter adapter = on(answer(HttpMethod.GET, "/i",
          new Response(103, Headers.of("Link", "</style.css>; rel=preload"), null)))) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/i", List.of(), null);

        assertThat(answer.status()).isEqualTo(103);
        assertThat(answer.valuesOf("Content-Length")).isEmpty();
        assertThat(answer.body()).isEmpty();
      }
    }

    @Test
    void aHandlerCannotSendBytesWithAnInterimStatus() {
      assertThatThrownBy(() -> new Response(100, Headers.empty(), HELLO))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("100");
      assertThatThrownBy(() -> new Response(199, Headers.empty(), HELLO))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("199");
    }

    @Test
    void aCodeTheJdkDoesNotNameArrivesWithAnEmptyReasonPhrase() throws IOException {
      // Measured on both JDKs: 103 leaves as "HTTP/1.1 103 " with nothing after the number, because the server's status
      // table knows no text for it. The client branches on the number, so this is cosmetic rather than semantic — pinned
      // so a later reader does not mistake it for a framing bug.
      try (JdkHttpServerAdapter adapter = on(answer(HttpMethod.GET, "/i", new Response(103, Headers.empty(), null)))) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/i", List.of(), null);

        assertThat(answer.status()).isEqualTo(103);
        assertThat(answer.statusLine()).isEqualTo("HTTP/1.1 103 ");
      }
    }
  }

  /**
   * The responses this module writes for itself, each of which must carry exactly one length (ADR-0023 §2, §3, §4).
   *
   * <p>Checked as a count as well as a value: the property the handler-written field used to break was that a response
   * could leave with two {@code Content-Length} lines, and only the adapter's own answers are in a position to guarantee
   * there is one.
   */
  @Nested
  @DisplayName("the adapter's 400, 404, 405 and 500 keep one length each")
  class AdapterAnswers {

    @Test
    void anErrorAnswerCarriesExactlyOneLengthAndItsBody() throws IOException {
      AtomicInteger calls = new AtomicInteger();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/boom", (request, parameters) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("the handler failed");
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.Response notFound = RawHttp.request(port, "GET", "/nowhere", List.of(), null);
        RawHttp.Response serverError = RawHttp.request(port, "GET", "/boom", List.of(), null);

        assertThat(notFound.status()).isEqualTo(404);
        assertThat(notFound.countOf("Content-Length")).isEqualTo(1);
        assertThat(notFound.valuesOf("Content-Length")).containsExactly("9");
        assertThat(notFound.body()).isEqualTo("Not Found");
        assertThat(serverError.status()).isEqualTo(500);
        assertThat(serverError.countOf("Content-Length")).isEqualTo(1);
        assertThat(serverError.valuesOf("Content-Length")).containsExactly("21");
        assertThat(serverError.body()).isEqualTo("Internal Server Error");
        assertThat(calls.get()).isEqualTo(1);
      }
    }

    @Test
    void aMethodNotAllowedAnswerCarriesAllowAndOneLength() throws IOException {
      try (JdkHttpServerAdapter adapter = on(
          answer(HttpMethod.GET, "/doc", new Response(200, Headers.of("Content-Type", "text/plain"), HELLO)))) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "POST", "/doc", List.of(), null);

        assertThat(answer.status()).isEqualTo(405);
        assertThat(answer.firstOf("Allow")).isEqualTo("GET");
        assertThat(answer.countOf("Content-Length")).isEqualTo(1);
        assertThat(answer.body()).isEqualTo("Method Not Allowed");
      }
    }

    @Test
    void aBadRequestAnswerKeepsItsBodyAndOneLength() throws IOException {
      try (JdkHttpServerAdapter adapter = on(
          answer(HttpMethod.GET, "/doc", new Response(200, Headers.of("Content-Type", "text/plain"), HELLO)))) {
        int port = start(adapter);

        // The reachable form of a target the contract refuses (ADR-0023 §5): a bare %2F has no leading slash.
        RawHttp.Response answer = RawHttp.request(port, "GET", "%2F", List.of(), null);

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.countOf("Content-Length")).isEqualTo(1);
        assertThat(answer.body()).isEqualTo("Bad Request");
      }
    }
  }

  /**
   * The directive §E.1 table asserted as one table, from the shipped adapter's socket, both JDKs.
   *
   * <p>Every row elsewhere in this file is one shape; a reader checking "does the implementation match the matrix" should
   * not have to assemble the answer from seven nested classes. The columns are exactly §E.1's: the status, what the body
   * was, the {@code Content-Length} the wire carries, and {@code Transfer-Encoding}, which is absent in every row because
   * this adapter never chunks a response it can count (§E.4).
   */
  @Nested
  @DisplayName("§E.1: the final framing matrix in one socket run")
  class FramingMatrix {

    /** One row: what was answered, what the wire says about it. */
    private record Row(String label, int status, String lengthField, String body) {}

    @Test
    void everyMatrixRowArrivesAsItsRowDefines() throws IOException {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/empty", (request, parameters) -> new Response(200, Headers.empty(), new byte[0]))
          .route(HttpMethod.GET, "/full", (request, parameters) -> new Response(200, Headers.empty(), HELLO))
          .route(HttpMethod.GET, "/n204", (request, parameters) -> new Response(204, Headers.empty(), null))
          .route(HttpMethod.GET, "/n304", (request, parameters) -> new Response(304, Headers.of("ETag", "\"7\""), null))
          .route(HttpMethod.GET, "/boom", (request, parameters) -> {
            throw new IllegalStateException("the handler failed");
          })
          .route(HttpMethod.HEAD, "/full", (request, parameters) -> new Response(200, Headers.empty(), HELLO))
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        List<Row> expected = List.of(
            new Row("200 empty", 200, "0", ""),
            new Row("200 non-empty", 200, "5", "hello"),
            new Row("204", 204, null, ""),
            // §E.1 leaves this cell to the final Stage-D decision, and the decision is D1 (ADR-0029): a 304 through this
            // framework carries no Content-Length, because the only value the adapter could write is one a handler
            // asserted without any bytes to verify.
            new Row("304", 304, null, ""),
            new Row("HEAD 200 suppressed", 200, "5", ""),
            new Row("404", 404, "9", "Not Found"),
            new Row("405", 405, "18", "Method Not Allowed"),
            new Row("500", 500, "21", "Internal Server Error"));

        List<Row> actual = List.of(
            row(port, "GET", "/empty", "200 empty"),
            row(port, "GET", "/full", "200 non-empty"),
            row(port, "GET", "/n204", "204"),
            row(port, "GET", "/n304", "304"),
            row(port, "HEAD", "/full", "HEAD 200 suppressed"),
            row(port, "GET", "/nowhere", "404"),
            row(port, "DELETE", "/full", "405"),
            row(port, "GET", "/boom", "500"));

        assertThat(actual).isEqualTo(expected);
      }
    }

    private static Row row(int port, String method, String path, String label) throws IOException {
      RawHttp.Response answer = RawHttp.request(port, method, path, List.of(), null);
      assertThat(answer.valuesOf("Transfer-Encoding")).as("no Transfer-Encoding in row %s", label).isEmpty();
      return new Row(label, answer.status(), answer.firstOf("Content-Length"), answer.body());
    }
  }

  /**
   * What the server's own log says about a response this adapter writes: nothing, on every shape above.
   *
   * <p>The JDK logs {@code sendResponseHeaders: rCode = 204: forcing contentLen = -1} when handed a positive length for a
   * status that cannot carry a body, and a second warning for a HEAD with a declared length. Those lines are the
   * machine-readable form of "the application and the wire disagreed", so asserting their absence is asserting that this
   * adapter never asks the server to undo what it was told to do.
   */
  @Nested
  @DisplayName("the JDK is never left correcting this adapter")
  class NoServerSideCorrection {

    @Test
    void noFramingShapeMakesTheServerLogAWarning() throws Exception {
      List<String> warnings = new ArrayList<>();
      java.util.logging.Logger serverLog = java.util.logging.Logger.getLogger("com.sun.net.httpserver");
      java.util.logging.Handler capture = new java.util.logging.Handler() {
        @Override
        public void publish(java.util.logging.LogRecord record) {
          if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
            warnings.add(record.getMessage());
          }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };
      java.util.logging.Level previousLevel = serverLog.getLevel();
      serverLog.addHandler(capture);
      serverLog.setLevel(java.util.logging.Level.ALL);
      try {
        Router router = Router.builder()
            .route(HttpMethod.GET, "/n204", (request, parameters) -> new Response(204, Headers.empty(), null))
            .route(HttpMethod.GET, "/n304", (request, parameters) -> new Response(304, Headers.of("ETag", "\"7\""), null))
            .route(HttpMethod.GET, "/n103", (request, parameters) -> new Response(103, Headers.empty(), null))
            .route(HttpMethod.GET, "/empty", (request, parameters) -> new Response(200, Headers.empty(), new byte[0]))
            .route(HttpMethod.GET, "/full", (request, parameters) -> new Response(200, Headers.empty(), HELLO))
            .route(HttpMethod.HEAD, "/doc", (request, parameters) -> new Response(200, Headers.empty(), HELLO))
            .route(HttpMethod.HEAD, "/doc304", (request, parameters) -> new Response(304, Headers.empty(), null))
            .route(HttpMethod.HEAD, "/doc204", (request, parameters) -> new Response(204, Headers.empty(), null))
            .build();
        try (JdkHttpServerAdapter adapter = on(router)) {
          int port = start(adapter);
          for (String path : List.of("/n204", "/n304", "/n103", "/empty", "/full", "/nowhere")) {
            RawHttp.request(port, "GET", path, List.of(), null);
          }
          for (String path : List.of("/doc", "/doc304", "/doc204", "/nowhere")) {
            RawHttp.request(port, "HEAD", path, List.of(), null);
          }
        }
      } finally {
        serverLog.removeHandler(capture);
        serverLog.setLevel(previousLevel);
      }

      // The three warnings measured on the bare JDK — `rCode = 204: forcing contentLen = -1` twice and `being invoked
      // with a content length for a HEAD request` once — each come from a declaration this module no longer makes: bytes
      // on a 204 or a 304 are refused by Response before they reach here, and a HEAD's length is written into the fields
      // rather than declared. Probe F, which declares a positive length for a HEAD, is caught by this assertion.
      assertThat(warnings).isEmpty();
    }
  }

  private static HttpClient client() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .version(HttpClient.Version.HTTP_1_1).build();
  }

  private static HttpResponse<byte[]> send(HttpClient client, int port, String method, String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .method(method, HttpRequest.BodyPublishers.noBody())
        .timeout(Duration.ofSeconds(5))
        .build();
    return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
  }
}
