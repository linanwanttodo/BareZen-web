package com.barezen.http.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.barezen.web.Handler;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.barezen.web.Router;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The bridge in both directions, measured on the wire: what the client sent is what the handler reads, and what the
 * handler returned is what the client reads (ADR-0022 §5, §6).
 *
 * <p>Header assertions are made against field lines rather than a parsed header map for one reason — half of what this
 * class asserts is that two lines stayed two lines. A parsing client would have folded them before the test could see
 * them.
 *
 * <p>Two of these tests exist to record limitations rather than to celebrate behaviour. They are written as assertions
 * because an unasserted limitation is a claim that a later reader cannot check: if the JDK ever starts preserving field
 * order and name case, these tests fail and the ADR has to be amended, which is the correct outcome for a documented
 * restriction (ADR-0022 §5).
 */
class JdkHeaderAndBodyTest {

  private static final Response text(String body) {
    return new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8"),
        body.getBytes(StandardCharsets.UTF_8));
  }

  private static int start(JdkHttpServerAdapter adapter) {
    adapter.start();
    return adapter.port().orElseThrow(() -> new AssertionError("a started adapter reports the port it bound"));
  }

  private static JdkHttpServerAdapter on(Router router) {
    return new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router);
  }

  @Nested
  @DisplayName("request headers reach the handler")
  class RequestHeaders {

    @Test
    void aHeaderTheClientSentIsReadableByTheHandler() throws IOException {
      AtomicReference<String> seen = new AtomicReference<>();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/h", (request, parameters) -> {
            seen.set(request.headers().first("X-Api-Key").orElse("<absent>"));
            return text("ok");
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.request(port, "GET", "/h", List.of("X-Api-Key: bearer-xyz"), null);

        assertThat(seen.get()).isEqualTo("bearer-xyz");
      }
    }

    @Test
    void aNameSentInAnotherCaseIsFoundByAnyCase() throws IOException {
      AtomicReference<String> viaGiven = new AtomicReference<>();
      AtomicReference<String> viaCanonical = new AtomicReference<>();
      AtomicReference<String> viaUpper = new AtomicReference<>();
      Handler recorder = (request, parameters) -> {
        viaGiven.set(request.headers().first("X-MixedCase").orElse("<absent>"));
        viaCanonical.set(request.headers().first("X-mixedcase").orElse("<absent>"));
        viaUpper.set(request.headers().first("X-MIXEDCASE").orElse("<absent>"));
        return text("ok");
      };
      try (JdkHttpServerAdapter adapter = on(Router.builder().route(HttpMethod.GET, "/h", recorder).build())) {
        int port = start(adapter);

        RawHttp.request(port, "GET", "/h", List.of("x-mixedCase: value"), null);

        // Headers matches case-insensitively, so the spelling the client used is not a condition for finding the value
        // — the property a handler actually depends on (ADR-0022 §5).
        assertThat(viaGiven.get()).isEqualTo("value");
        assertThat(viaCanonical.get()).isEqualTo("value");
        assertThat(viaUpper.get()).isEqualTo("value");
      }
    }

    @Test
    void everyValueOfARepeatedFieldArrivesInOrder() throws IOException {
      AtomicReference<List<String>> seen = new AtomicReference<>(List.of());
      Router router = Router.builder()
          .route(HttpMethod.GET, "/h", (request, parameters) -> {
            seen.set(request.headers().values("Accept"));
            return text("ok");
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.request(port, "GET", "/h", List.of("Accept: text/html", "Accept: application/json",
            "Accept: text/plain"), null);

        // Three lines in, three values out, in the order sent: nothing was folded into one comma-joined value and
        // nothing was dropped (ADR-0022 §5).
        assertThat(seen.get()).containsExactly("text/html", "application/json", "text/plain");
      }
    }

    @Test
    void anEmptyValueIsPresentAndDistinctFromAnAbsentField() throws IOException {
      AtomicReference<List<String>> empty = new AtomicReference<>();
      AtomicReference<List<String>> absent = new AtomicReference<>();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/h", (request, parameters) -> {
            empty.set(request.headers().values("X-Empty"));
            absent.set(request.headers().values("X-Never-Sent"));
            return text("ok");
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.request(port, "GET", "/h", List.of("X-Empty:"), null);

        // Sent as "X-Empty:" with nothing after the colon: one value, the empty string. A bridge that skipped empty
        // values would make this field indistinguishable from one that was never sent.
        assertThat(empty.get()).containsExactly("");
        assertThat(absent.get()).isEmpty();
      }
    }

    /**
     * A limitation, asserted so that it stays visible: the JDK canonicalizes a field name to first-upper-rest-lower, so
     * the spelling the client used does not survive.
     *
     * <p>{@code X-mixedCase} arrives as {@code X-mixedcase}. HTTP field names are case-insensitive so no correct client
     * is harmed, but the phase forbids claiming full preservation, and this is the half that is lost. The lookup still
     * works, which the test above covers.
     */
    @Test
    void theNameArrivesCanonicalizedRatherThanAsTheClientSpelledIt() throws IOException {
      AtomicReference<List<String>> names = new AtomicReference<>();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/h", (request, parameters) -> {
            names.set(request.headers().names());
            return text("ok");
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.request(port, "GET", "/h", List.of("X-mixedCase: value"), null);

        assertThat(names.get()).contains("X-mixedcase");
        assertThat(names.get()).doesNotContain("X-mixedCase");
      }
    }

    /**
     * The other lost property: field order. The JDK backs the request headers with a hash table, so
     * {@code Headers.names()} reports the JDK's iteration order rather than the order the lines arrived in.
     *
     * <p>The client sent {@code X-mixedCase} first and it arrived later than {@code Accept-encoding}. Nothing in this
     * contract depends on request header order — HTTP defines no meaning for it — so this is recorded rather than
     * worked around (ADR-0022 §5).
     */
    @Test
    void theOrderTheFieldsArrivedInIsNotTheOrderTheHandlerSeesThemIn() throws IOException {
      AtomicReference<List<String>> names = new AtomicReference<>();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/h", (request, parameters) -> {
            names.set(request.headers().names());
            return text("ok");
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.request(port, "GET", "/h",
            List.of("X-mixedCase: value", "Accept-Encoding: gzip", "Z-Last: z"), null);

        List<String> seenNames = names.get();
        assertThat(seenNames).contains("X-mixedcase", "Accept-encoding", "Z-last");
        // Arrival order would put X-mixedcase before Accept-encoding; the JDK's own order does not, and this test says
        // so instead of pretending the bridge could fix it.
        assertThat(seenNames.indexOf("X-mixedcase")).isGreaterThan(seenNames.indexOf("Accept-encoding"));
      }
    }

    @Test
    void aBodyReadByTheHandlerIsTheBodyTheClientSent() throws IOException {
      AtomicReference<String> seen = new AtomicReference<>();
      Router router = Router.builder()
          .route(HttpMethod.POST, "/echo", (request, parameters) -> {
            seen.set(new String(request.body(), StandardCharsets.UTF_8));
            return text(seen.get());
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "POST", "/echo", List.of(), "hello");

        assertThat(seen.get()).isEqualTo("hello");
        assertThat(answer.body()).isEqualTo("hello");
      }
    }

    @Test
    void aRequestWithNoBodyCarriesAnEmptyArrayRatherThanNothing() throws IOException {
      AtomicReference<Request> seen = new AtomicReference<>();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/h", (request, parameters) -> {
            seen.set(request);
            return text("ok");
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.request(port, "GET", "/h", List.of(), null);

        // Request.body() is a byte[] and never null, so a GET handler does not have to ask whether a body exists.
        assertThat(seen.get().body()).isEmpty();
      }
    }
  }

  @Nested
  @DisplayName("response headers and framing reach the client")
  class ResponseHeaders {

    @Test
    void aFieldTheHandlerSetArrivesWithItsValue() throws IOException {
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/h", (request, parameters) ->
              new Response(200, Headers.of("Content-Type", "text/plain").with("X-Api-Version", "2.9"),
                  "ok".getBytes(StandardCharsets.UTF_8)))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/h", List.of(), null);

        assertThat(answer.firstOf("X-Api-Version")).isEqualTo("2.9");
      }
    }

    @Test
    void twoValuesForOneNameLeaveAsTwoLines() throws IOException {
      AtomicReference<List<String>> readBack = new AtomicReference<>(List.of());
      Router router = Router.builder()
          .route(HttpMethod.GET, "/h", (request, parameters) -> {
            Response built = new Response(200, Headers.of("Content-Type", "text/plain")
                .with("X-Test", "one").with("X-Test", "two"), "ok".getBytes(StandardCharsets.UTF_8));
            readBack.set(built.headers().values("X-Test"));
            return built;
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/h", List.of(), null);

        // The contract's two values, and two lines on the wire. A bridge that joined them with ", " would have produced
        // one line that a Set-Cookie cannot legally be (ADR-0022 §6).
        assertThat(readBack.get()).containsExactly("one", "two");
        assertThat(answer.valuesOf("X-Test")).containsExactly("one", "two");
        assertThat(answer.countOf("X-Test")).isEqualTo(2);
      }
    }

    @Test
    void twoSetCookieValuesLeaveAsTwoLines() throws IOException {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/h", (request, parameters) -> new Response(200, Headers.of("Content-Type", "text/plain")
              .with("Set-Cookie", "a=1; Path=/").with("Set-Cookie", "b=2; Path=/secure"),
              "ok".getBytes(StandardCharsets.UTF_8)))
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/h", List.of(), null);

        // The case that a folded header actually breaks, so it is tested on the wire rather than in the contract. No
        // cookie logic lives here — the fields are opaque to this module, which is why a cookie phase can add meaning
        // without changing the bridge.
        assertThat(answer.countOf("Set-Cookie")).isEqualTo(2);
        assertThat(answer.valuesOf("Set-Cookie")).containsExactly("a=1; Path=/", "b=2; Path=/secure");
      }
    }

    @Test
    void theContentLengthIsTheBodyLengthInBytes() throws IOException {
      // "héllo" is six UTF-8 bytes and five characters, which is the difference a length-of-characters bug hides.
      byte[] body = "héllo".getBytes(StandardCharsets.UTF_8);
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/h", (request, parameters) ->
              new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8"), body))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/h", List.of(), null);

        assertThat(answer.firstOf("Content-Length")).isEqualTo("6");
        assertThat(answer.body()).hasSize(6);
        assertThat(answer.bodyUtf8()).isEqualTo("héllo");
      }
    }

    @Test
    void anEmptyBodyIsDeclaredAsZeroBytesAndNotChunked() throws IOException {
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/h", (request, parameters) ->
              new Response(200, Headers.of("Content-Type", "text/plain"), new byte[0]))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/h", List.of(), null);

        // Probed: declaring 0 to sendResponseHeaders means "chunked" to this JDK, and -1 means Content-Length: 0. A
        // hand-rolled chunked response would arrive with a transfer-encoding field instead (ADR-0022 §6).
        assertThat(answer.firstOf("Content-Length")).isEqualTo("0");
        assertThat(answer.valuesOf("Transfer-Encoding")).isEmpty();
        assertThat(answer.body()).isEmpty();
      }
    }

    @Test
    void aNullBodyIsAlsoZeroBytes() throws IOException {
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/h", (request, parameters) ->
              new Response(204, Headers.empty(), null))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/h", List.of(), null);

        // The JDK forbids a body for 204 and writes no Content-Length at all; the adapter's -1 declaration is what
        // keeps that legal rather than throwing.
        assertThat(answer.status()).isEqualTo(204);
        assertThat(answer.body()).isEmpty();
      }
    }

    @Test
    void aResetContentResponseCarriesNoBody() throws IOException {
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/h", (request, parameters) ->
              new Response(205, Headers.of("X-Reset", "form"), null))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/h", List.of(), null);

        assertThat(answer.status()).isEqualTo(205);
        assertThat(answer.firstOf("X-Reset")).isEqualTo("form");
        assertThat(answer.firstOf("Content-Length")).isEqualTo("0");
        assertThat(answer.body()).isEmpty();
      }
    }

    /**
     * A HEAD response carries the fields a GET would have produced, the length of that representation, and no body.
     *
     * <p>The length is the point of this test and it is the sentence ADR-0022 §7 recorded as impossible. "No body" and
     * "no length" are different statements: HTTP defines HEAD as the GET's fields without its bytes, which means a
     * correct HEAD answer keeps {@code Content-Length} and drops the body — and the two were measured separately on JDK
     * 17 and JDK 21, because the JDK's own {@code HEAD} branch suppresses the body while never writing a length of its
     * own. The adapter therefore writes the length into the response fields and declares {@code -1} to the JDK, which
     * leaves exactly one {@code Content-Length} line and no bytes (ADR-0027 §6).
     */
    @Test
    void aHeadResponseHasFieldsAndTheRepresentationLengthButNoBody() throws IOException {
      byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
      Router router = Router.builder()
          .route(HttpMethod.HEAD, "/doc", (request, parameters) ->
              new Response(200, Headers.of("Content-Type", "text/plain").with("ETag", "W/\"7\""), body))
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "HEAD", "/doc", List.of(), null);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.firstOf("Content-Type")).isEqualTo("text/plain");
        assertThat(answer.firstOf("ETag")).isEqualTo("W/\"7\"");
        assertThat(answer.body()).isEmpty();
        // The five bytes a GET would have sent, stated without sending them.
        assertThat(answer.valuesOf("Content-Length")).containsExactly("5");
      }
    }

    @Test
    void theResponseNameCaseIsCanonicalizedAndTheOrderIsTheJdks() throws IOException {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/h", (request, parameters) -> new Response(200, Headers.of("X-MixedCase", "V")
              .with("Content-Type", "text/plain"), "ok".getBytes(StandardCharsets.UTF_8)))
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/h", List.of(), null);

        // Recorded limitation on the way out as well as the way in: the handler wrote "X-MixedCase" and the client reads
        // "X-mixedcase". Values are untouched; only names are canonicalized (ADR-0022 §5).
        assertThat(answer.fieldLines().toString()).contains("X-mixedcase: V");
        assertThat(answer.fieldLines().toString()).doesNotContain("X-MixedCase");
      }
    }

    @Test
    void aResponseWrittenToADeliberatelyWideBodyStillArrivesWithAMatchingLength() throws IOException {
      byte[] body = new byte[70_000];
      java.util.Arrays.fill(body, (byte) 'k');
      try (JdkHttpServerAdapter adapter = on(Router.builder()
          .route(HttpMethod.GET, "/big", (request, parameters) ->
              new Response(200, Headers.of("Content-Type", "application/octet-stream"), body))
          .build())) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.request(port, "GET", "/big", List.of(), null);

        // A declared length that does not equal the written bytes throws inside the JDK ("insufficient bytes written to
        // stream"), so a complete read of the right size is the proof the two agreed (ADR-0022 §6).
        assertThat(answer.firstOf("Content-Length")).isEqualTo("70000");
        assertThat(answer.body()).hasSize(70_000);
      }
    }
  }
}
