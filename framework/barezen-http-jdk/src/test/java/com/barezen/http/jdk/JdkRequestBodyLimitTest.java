package com.barezen.http.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.core.config.Configuration;
import com.barezen.core.error.ConfigurationException;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.barezen.web.Router;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The request-body limit, ADR-0031: what the bound does on a real socket, at the boundary, in both framings, and what
 * a refusal costs.
 *
 * <p>Raw sockets for everything with a framing consequence, because a limit about {@code Content-Length} and chunked
 * bodies cannot be tested through a client that rewrites either ({@link RawHttp}, ADR-0029 §Tests). Each test starts
 * its own server under its own limit through the configured constructor, so an assertion is about the limit rather
 * than about a default; the default, the resolution path and the failure shapes have their own nested class.
 */
class JdkRequestBodyLimitTest {

  private static final String PATH = "/up";
  private static final String LEN_OK = "text/plain";

  private JdkHttpServerAdapter server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop();
      server = null;
    }
  }

  /** Start the server under {@code limit}; returns its port. Handlers echo {@code len=N} of the body they received. */
  private int startWithLimit(long limit) {
    Router router = Router.builder()
        .route(HttpMethod.POST, PATH, (Request request, Map<String, String> params) ->
            new Response(200, Headers.of("Content-Type", LEN_OK + "; charset=utf-8"),
                ("len=" + request.body().length).getBytes(StandardCharsets.UTF_8)))
        .route(HttpMethod.GET, PATH, (Request request, Map<String, String> params) ->
            new Response(200, Headers.of("Content-Type", LEN_OK + "; charset=utf-8"),
                ("len=" + request.body().length).getBytes(StandardCharsets.UTF_8)))
        .build();
    server = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router,
        Configuration.inMemory(Map.of(BodyLimit.MAX_REQUEST_BODY_BYTES.name(), Long.toString(limit))));
    server.start();
    return server.port().orElseThrow();
  }

  private int port() {
    return server.port().orElseThrow();
  }

  /** A well-formed POST with a body of {@code bodyBytes} 'x' bytes and an exact declared length. */
  private RawHttp.Response upload(int bodyBytes) throws IOException {
    // One response read, not a read-to-EOF: a refusal of a body this big leaves surplus unread past the JDK's drain,
    // and the close after it is a reset — the 413 is still the first and only answer the socket has to give.
    byte[] payload = "x".repeat(bodyBytes).getBytes(StandardCharsets.US_ASCII);
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port()), 5_000);
      socket.setSoTimeout(5_000);
      OutputStream out = socket.getOutputStream();
      try {
        out.write((postHeader("Content-Length: " + payload.length + "\r\n\r\n")).getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
      } catch (IOException goneEarly) {
        // The server refused before the whole body could be pushed; the answer is read anyway.
      }
      return readOneResponse(socket);
    }
  }

  /**
   * The request line and {@code Host}, ready for framing lines to be appended.
   *
   * <p>No {@code Connection: close} here, deliberately: a test that reads the answer with {@link RawHttp} has to send
   * it (that helper reads to EOF, which a kept-alive connection never reaches — measured, not theorized), while a test
   * that keeps the connection to send a second request must not. Each caller states its own framing, which is what
   * makes the keep-alive tests below real rather than accidental.
   */
  private static String postHeader(String framingLines) {
    return "POST " + PATH + " HTTP/1.1\r\nHost: 127.0.0.1\r\n" + framingLines;
  }

  /**
   * Send a message and half-close, then read to the server's EOF.
   *
   * <p>{@link RawHttp#requestRaw} writes and reads to EOF without ever shutting its side down, which hangs on the two
   * shapes here that need this helper: the server, having answered, still waits inside its leftover drain for the end
   * of a body that will never come, and only the client's FIN releases it. A real client that sent its whole (refused
   * or malformed) message and wants its answer hangs up exactly this way, so this is the honest shape rather than a
   * workaround — measured both directions: with FIN the 413 arrives in milliseconds, without it the drain waits.
   */
  private RawHttp.Response requestWithFin(String message) throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port()), 5_000);
      socket.setSoTimeout(5_000);
      socket.getOutputStream().write(message.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      socket.shutdownOutput();
      return readOneResponse(socket);
    }
  }

  @Nested
  @DisplayName("the boundary in the known-length framing")
  final class KnownLength {

    @Test
    @DisplayName("a body under the limit arrives whole")
    void aBodyUnderTheLimitArrivesWhole() throws IOException {
      startWithLimit(16);
      RawHttp.Response response = upload(15);
      assertThat(response.status()).isEqualTo(200);
      assertThat(response.bodyUtf8()).isEqualTo("len=15");
    }

    @Test
    @DisplayName("a declared body larger than the read buffer arrives whole")
    void aDeclaredBodyPastTheFirstBufferArrivesWhole() throws IOException {
      // The same shape the chunked test below makes, on the other framing surface: 20 000 bytes is under the limit and
      // over the counting read's first buffer, so it is the case where the read must ask for room that is still in the
      // array. A read that asked for buf.length at an offset instead of buf.length - size reached the JDK's
      // BufferedInputStream out of bounds, and the handler never ran (ADR-0031 §Tests).
      startWithLimit(100_000);
      RawHttp.Response response = upload(20_000);
      assertThat(response.status()).isEqualTo(200);
      assertThat(response.bodyUtf8()).isEqualTo("len=20000");
    }

    @Test
    @DisplayName("a body exactly at the limit arrives whole — the limit is the largest accepted size")
    void aBodyExactlyAtTheLimitArrivesWhole() throws IOException {
      startWithLimit(16);
      RawHttp.Response response = upload(16);
      assertThat(response.status()).isEqualTo(200);
      assertThat(response.bodyUtf8()).isEqualTo("len=16");
    }

    @Test
    @DisplayName("a body one byte over the limit is refused, and the refusal is framed like every adapter answer")
    void oneByteOverTheLimitIsRefused() throws IOException {
      startWithLimit(16);
      RawHttp.Response response = upload(17);
      assertThat(response.status()).isEqualTo(413);
      // RFC 9110 §15.5.14 renamed 413 "Content Too Large"; the JDK's Code table still spells the reason phrase
      // "Request Entity Too Large", and the status line is the server's, so the wire carries the JDK's phrase and
      // the body carries the RFC's words (ADR-0031 §Decision 3).
      assertThat(response.statusLine()).isEqualTo("HTTP/1.1 413 Request Entity Too Large");
      assertThat(response.bodyUtf8()).isEqualTo("Content Too Large");
      assertThat(response.firstOf("Content-Length")).isEqualTo("17");
      assertThat(response.firstOf("Content-Type")).isEqualTo("text/plain; charset=utf-8");
      assertThat(response.firstOf("Transfer-Encoding")).isNull();
    }

    @Test
    @DisplayName("the 413 names neither the path nor the limit — a status text and nothing else")
    void theRefusalNamesNeitherThePathNorTheLimit() throws IOException {
      // Same rule as the 404 (ADR-0023 §2): the socket answer carries no target, and the limit lives in the debug
      // log line rather than in a number a client can steer by.
      startWithLimit(4);
      RawHttp.Response response = upload(5);
      String wire = response.bodyUtf8() + String.join("\n", response.fieldLines());
      assertThat(wire).doesNotContain(PATH);
      assertThat(response.bodyUtf8()).isEqualTo("Content Too Large");
    }

    @Test
    @DisplayName("a declared length far above the limit is refused with no body sent at all")
    void aHugeDeclaredLengthIsRefusedWithoutReading() throws IOException {
      // The client claims 64 MiB, sends zero body bytes, and hangs up: the answer arrives on the claim alone.
      // Were the module reading instead of refusing, this would be the truncated-body 400 (measured: RawHttp's
      // half-close shapes answer 400) — the early refusal of ADR-0031 §Decision 4 is the only rule that answers
      // 413 here, and it does so without a byte of the claim being read or allocated.
      startWithLimit(16);
      RawHttp.Response response = requestWithFin(postHeader("Content-Length: 67108864\r\n\r\n"));
      assertThat(response.status()).isEqualTo(413);
      assertThat(response.bodyUtf8()).isEqualTo("Content Too Large");
    }

    @Test
    @DisplayName("a HEAD over the limit states the refusal's 17 bytes and sends none of them")
    void aHeadOverTheLimitStatesTheRefusalLengthAndSendsNoneOfIt() throws IOException {
      // The refusal passes the same `writesBody` predicate as every other answer, so the framing matrix holds for a
      // sixth status without a new rule (ADR-0031 §Decision 3): Content-Length says 17, the wire carries no body, and
      // no Transfer-Encoding joins them. The claim alone is what is refused, so this is the known-length framing, and
      // the answer is read on the same instrument the other big-claim refusals use: a HEAD has no body to end a read,
      // so only the half-close releases the server's drain and lets the socket reach EOF (ADR-0031 §Decision 5).
      startWithLimit(16);
      RawHttp.Response response = requestWithFin(
          "HEAD " + PATH + " HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 67108864\r\n\r\n");
      assertThat(response.status()).isEqualTo(413);
      assertThat(response.statusLine()).isEqualTo("HTTP/1.1 413 Request Entity Too Large");
      assertThat(response.firstOf("Content-Length")).isEqualTo("17");
      assertThat(response.firstOf("Content-Type")).isEqualTo("text/plain; charset=utf-8");
      assertThat(response.valuesOf("Transfer-Encoding")).isEmpty();
      assertThat(response.body()).isEmpty();
    }

    @Test
    @DisplayName("a truncated body under the limit stays the read failure it was — 400")
    void aTruncatedBodyUnderTheLimitStaysABadRequest() throws IOException {
      startWithLimit(64);
      RawHttp.Response response = RawHttp.requestWithTruncatedBody(port(), "POST", PATH, 12, 4);
      assertThat(response.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("a truncated body over the limit is refused on the claim — 413, not the arrival failure")
    void aTruncatedBodyOverTheLimitIsTooLargeNotIncomplete() throws IOException {
      // Declared 20, sent 4, bound 8: two client faults are present and the limit is the one known BEFORE the
      // read, so 413 answers the claim rather than the shortfall (ADR-0031 §Decision 4).
      startWithLimit(8);
      RawHttp.Response response = RawHttp.requestWithTruncatedBody(port(), "POST", PATH, 20, 4);
      assertThat(response.status()).isEqualTo(413);
    }
  }

  @Nested
  @DisplayName("the boundary in the chunked framing")
  final class UnknownLength {

    @Test
    @DisplayName("a chunked body under the limit arrives whole")
    void aChunkedBodyUnderTheLimitArrivesWhole() throws IOException {
      startWithLimit(24);
      RawHttp.Response response = RawHttp.requestRaw(port(), chunked("aaaaaaaaaa", "bbbbbbbbbb"));
      assertThat(response.status()).isEqualTo(200);
      assertThat(response.bodyUtf8()).isEqualTo("len=20");
    }

    @Test
    @DisplayName("a chunked body exactly at the limit arrives whole")
    void aChunkedBodyExactlyAtTheLimitArrivesWhole() throws IOException {
      startWithLimit(20);
      RawHttp.Response response = RawHttp.requestRaw(port(), chunked("aaaaaaaaaa", "bbbbbbbbbb"));
      assertThat(response.status()).isEqualTo(200);
      assertThat(response.bodyUtf8()).isEqualTo("len=20");
    }

    @Test
    @DisplayName("a chunked body past the limit is refused at the first byte beyond it")
    void aChunkedBodyPastTheLimitIsRefusedAtTheByteThatPassesIt() throws IOException {
      startWithLimit(20);
      RawHttp.Response response = RawHttp.requestRaw(port(),
          chunked("aaaaaaaaaa", "bbbbbbbbbb", "cccccccccc"));
      assertThat(response.status()).isEqualTo(413);
      assertThat(response.bodyUtf8()).isEqualTo("Content Too Large");
    }

    @Test
    @DisplayName("a refusal stops counting: an still-streaming endless body gets its 413 without being finished")
    void aRefusalStopsReadingAtTheBound() throws IOException {
      // 20 chunks of 32 bytes against a bound of 64. A reader that kept reading would hang here; the bounded read
      // answers while the client is still mid-body (ADR-0031 §Decision 5).
      startWithLimit(64);
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", port()), 5_000);
        socket.setSoTimeout(5_000);
        OutputStream out = socket.getOutputStream();
        out.write(postHeader("Transfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        byte[] chunk = (Integer.toHexString(32) + "\r\n" + "x".repeat(32) + "\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        for (int round = 0; round < 20; round++) {
          try {
            out.write(chunk);
            out.flush();
          } catch (IOException alreadyGone) {
            break; // the server closed mid-stream: that is the refusal acting, not a failure of this test
          }
        }
        RawHttp.Response response = readOneResponse(socket);
        assertThat(response.status()).isEqualTo(413);
      }
    }

    @Test
    @DisplayName("a chunked body larger than the read buffer arrives whole, because the buffer grows into it")
    void aChunkedBodyPastTheFirstBufferArrivesWhole() throws IOException {
      // The bound is a read bound, not a pre-committed allocation (ADR-0031 §Decision 5), and the growth step is only
      // exercised by a body bigger than the buffer's first size. 20 000 bytes against a 100 000 bound: the reader
      // starts at 8 KiB, doubles, and lands on a buffer larger than what arrived — so the copy returned is the body's
      // length, not the bound's. A reader that allocated the limit up front would pass this test too, which is why it
      // asserts the length the handler saw rather than a memory claim.
      startWithLimit(100_000);
      String[] chunks = new String[20];
      Arrays.fill(chunks, "x".repeat(1_000));
      // A keep-alive message read as one response, the same instrument the big-body refusals use: requestRaw reads
      // to EOF, and a body this size is served while the client is still writing, so the close can arrive as a reset
      // before the reader has its answer. That is the client's shape, not the server's behavior, and ADR-0031 §5
      // already measures which side owns it.
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", port()), 5_000);
        socket.setSoTimeout(5_000);
        StringBuilder message = new StringBuilder(postHeader("Transfer-Encoding: chunked\r\n\r\n"));
        for (String payload : chunks) {
          message.append(Integer.toHexString(payload.length())).append("\r\n").append(payload).append("\r\n");
        }
        message.append("0\r\n\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(message.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
        RawHttp.Response response = readOneResponse(socket);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.bodyUtf8()).isEqualTo("len=20000");
      }
    }

    private String chunked(String... payloads) {
      // Connection: close because RawHttp.requestRaw reads to EOF: a keep-alive server would keep the socket open
      // after the one answer, and this helper is not the keep-alive instrument (the ConnectionOrder tests drive a
      // raw socket for that).
      StringBuilder message = new StringBuilder(postHeader("Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"));
      for (String payload : payloads) {
        message.append(Integer.toHexString(payload.length())).append("\r\n").append(payload).append("\r\n");
      }
      return message.append("0\r\n\r\n").toString();
    }
  }

  @Nested
  @DisplayName("the shapes this module never sees, because the parser owns them")
  final class ParserOwnedShapes {

    @Test
    @DisplayName("a POST with no length line arrives EMPTY under any limit — framing, not refusal")
    void anUndeclaredBodyIsNoContentByFraming() throws IOException {
      // Measured before the decision (ADR-0031 §Investigation): the JDK frames an undeclared request body as
      // length zero, so the bytes after the blank line are not content and cannot exceed a content limit. Inventing
      // a refusal here would refuse the server's framing rather than a body.
      startWithLimit(8);
      RawHttp.Response response = RawHttp.requestRaw(port(),
          postHeader("Connection: close\r\n\r\ndealer"));
      assertThat(response.status()).isEqualTo(200);
      assertThat(response.bodyUtf8()).isEqualTo("len=0");
    }

    @Test
    @DisplayName("an unparseable Content-Length is the JDK parser's 400, before any exchange")
    void anUnparseableLengthIsTheParsers() throws IOException {
      startWithLimit(8);
      RawHttp.Response response = RawHttp.requestRaw(port(),
          postHeader("Content-Length: abc\r\n\r\nabc"));
      assertThat(response.status()).isEqualTo(400);
      // The parser's answer is HTML naming the parse failure; the adapter's own answers are text/plain.
      assertThat(response.firstOf("Content-Type")).isEqualTo("text/html");
    }

    @Test
    @DisplayName("a Content-Length plus Transfer-Encoding pair is the JDK parser's 400")
    void aLengthAndChunkedPairIsRefusedByTheParser() throws IOException {
      startWithLimit(8);
      RawHttp.Response response = RawHttp.requestRaw(port(), postHeader(
          "Content-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n"));
      assertThat(response.status()).isEqualTo(400);
      assertThat(response.firstOf("Content-Type")).isEqualTo("text/html");
    }

    @Test
    @DisplayName("a garbage chunk header is the adapter's 400 — the framing was legal, the read failed")
    void aGarbageChunkHeaderIsTheAdaptersBadRequest() throws IOException {
      // Connection: close is not enough here: the refused body's stream is abandoned mid-chunk, and the JDK's
      // leftover drain then waits on the socket for bytes that identify the body's end — measured, the answer
      // reached a reading client but the connection stayed open past the read timeout until FIN. The client that
      // sent a whole malformed message hangs up, so this one does too. The answer itself is the adapter's
      // text/plain 400, not the parser's HTML — the request line and headers were legal, and it was the chunked
      // read that failed.
      startWithLimit(8);
      RawHttp.Response response =
          requestWithFin(postHeader("Transfer-Encoding: chunked\r\n\r\nzz\r\nabc\r\n0\r\n\r\n"));
      assertThat(response.status()).isEqualTo(400);
      assertThat(response.firstOf("Content-Type")).isEqualTo("text/plain; charset=utf-8");
      assertThat(response.bodyUtf8()).isEqualTo("Bad Request");
    }
  }

  @Nested
  @DisplayName("the connection after a refusal")
  final class ConnectionOrder {

    @Test
    @DisplayName("a surplus the drain can finish keeps the connection: 413, then the next request on the same socket")
    void aDrainedSurplusKeepsTheConnectionUsable() throws IOException {
      startWithLimit(10);
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", port()), 5_000);
        socket.setSoTimeout(5_000);
        OutputStream out = socket.getOutputStream();
        // 12 bytes in one chunk against a bound of 10: the refusal stops counting at 11, the JDK's leftover drain
        // (64 KiB by default) finishes the one remaining byte and the terminating chunk, and keep-alive survives.
        out.write(postHeader("Transfer-Encoding: chunked\r\n\r\nc\r\naaaaaaaaaaaa\r\n0\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        assertThat(readOneResponse(socket).status()).isEqualTo(413);
        out.write(postHeader("Content-Length: 5\r\n\r\nhello").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        RawHttp.Response next = readOneResponse(socket);
        assertThat(next.status()).isEqualTo(200);
        assertThat(next.bodyUtf8()).isEqualTo("len=5");
      }
    }

    @Test
    @DisplayName("a surplus past the drain ends the connection after the 413 — the next bytes are never parsed")
    void anUndrainableSurplusClosesTheConnection() throws IOException {
      startWithLimit(8);
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", port()), 5_000);
        socket.setSoTimeout(5_000);
        OutputStream out = socket.getOutputStream();
        out.write(postHeader("Content-Length: 131072\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        byte[] flood = new byte[16 * 1024];
        java.util.Arrays.fill(flood, (byte) 'a');
        for (int round = 0; round < 8; round++) {
          try {
            out.write(flood);
            out.flush();
          } catch (IOException alreadyGone) {
            break; // the server closed while we were flooding: that IS the rule acting
          }
        }
        RawHttp.Response refusal = readOneResponse(socket);
        assertThat(refusal.status()).isEqualTo(413);
        // After the 413 the connection must end, not continue: the drain (64 KiB) cannot finish a 128 KiB surplus,
        // so the dispatcher's not-at-EOF rule closes the socket. Measured on JDK 21 the close lands as a reset —
        // there are still unread bytes — and a reset and an orderly EOF are the same promise here: no further byte
        // arrives for a client to mistake for the next request.
        int more;
        try {
          more = socket.getInputStream().read(flood);
        } catch (java.net.SocketException reset) {
          more = -1;
        }
        assertThat(more).isEqualTo(-1);
      }
    }
  }

  @Nested
  @DisplayName("resolution through the existing Configuration layer")
  final class Resolution {

    @Test
    @DisplayName("a configured value is the limit")
    void aConfiguredValueIsTheLimit() throws IOException {
      startWithLimit(5);
      assertThat(upload(6).status()).isEqualTo(413);
      assertThat(upload(5).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a snapshot without the key takes the key's default, not no limit")
    void anAbsentKeyTakesTheDefault() {
      BodyLimit resolved = BodyLimit.from(Configuration.inMemory(Map.of("something.else", "1")));
      assertThat(resolved.bytes()).isEqualTo(BodyLimit.MAX_REQUEST_BODY_BYTES.defaultValue());
      assertThat(BodyLimit.unconfigured().bytes()).isEqualTo(resolved.bytes());
    }

    @Test
    @DisplayName("the default is one mebibyte")
    void theDefaultIsOneMebibyte() {
      assertThat(BodyLimit.MAX_REQUEST_BODY_BYTES.defaultValue()).isEqualTo(1024L * 1024L);
    }

    @Test
    @DisplayName("an unconfigured server refuses 1 MiB and accepts 1 KiB — the default is enforced, not documented")
    void theDefaultIsEnforcedOnAnUnconfiguredAdapter() throws IOException {
      Router router = Router.builder()
          .route(HttpMethod.POST, PATH, (Request request, Map<String, String> params) ->
              new Response(200, Headers.empty(), new byte[0]))
          .build();
      server = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router);
      server.start();
      assertThat(upload(1024).status()).isEqualTo(200);
      assertThat(upload(1024 * 1024 + 1).status()).isEqualTo(413);
    }

    @Test
    @DisplayName("a non-numeric value fails at construction, naming key and value — before any port")
    void aBadValueFailsAtConstruction() {
      Configuration bad = Configuration.inMemory(Map.of(BodyLimit.MAX_REQUEST_BODY_BYTES.name(), "enormous"));
      assertThatThrownBy(() -> new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), emptyRouter(), bad))
          .isInstanceOf(ConfigurationException.class)
          .hasMessageContaining(BodyLimit.MAX_REQUEST_BODY_BYTES.name())
          .hasMessageContaining("enormous");
    }

    @Test
    @DisplayName("a negative limit is refused as a misassembly")
    void aNegativeLimitIsRefusedAsMisassembly() {
      Configuration negative = Configuration.inMemory(Map.of(BodyLimit.MAX_REQUEST_BODY_BYTES.name(), "-1"));
      assertThatThrownBy(() -> new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), emptyRouter(),
          negative))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("a zero limit accepts a bodiless POST and refuses any body that claims bytes")
    void aZeroLimitAcceptsNothingThatClaimsBytes() throws IOException {
      startWithLimit(0);
      // No length line at all is the framing case (ParserOwnedShapes): clen 0, an empty body, nothing to exceed.
      RawHttp.Response plain = RawHttp.requestRaw(port(), postHeader("Connection: close\r\n\r\n"));
      assertThat(plain.status()).isEqualTo(200);
      assertThat(plain.bodyUtf8()).isEqualTo("len=0");
      RawHttp.Response declared =
          RawHttp.requestRaw(port(), postHeader("Content-Length: 1\r\nConnection: close\r\n\r\nx"));
      assertThat(declared.status()).isEqualTo(413);
      RawHttp.Response get = RawHttp.request(port(), "GET", PATH, List.of(), null);
      assertThat(get.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("null configuration is refused like every other constructor argument")
    void nullConfigurationIsRefused() {
      assertThatThrownBy(() -> new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), emptyRouter(),
          (Configuration) null))
          .isInstanceOf(NullPointerException.class);
    }

    private Router emptyRouter() {
      return Router.builder()
          .route(HttpMethod.GET, "/", (Request request, Map<String, String> params) ->
              new Response(200, Headers.empty(), new byte[0]))
          .build();
    }
  }

  /**
   * Read exactly one response from a socket that stays open — {@link RawHttp} closes after writing, which is what the
   * keep-alive tests above need to control themselves.
   */
  private static RawHttp.Response readOneResponse(Socket socket) throws IOException {
    InputStream in = socket.getInputStream();
    ByteArrayOutputStream received = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    String text = "";
    while (!text.contains("\r\n\r\n")) {
      int read = in.read(buffer);
      if (read < 0) {
        break;
      }
      received.write(buffer, 0, read);
      text = received.toString(StandardCharsets.ISO_8859_1);
    }
    int headersEnd = text.indexOf("\r\n\r\n");
    if (headersEnd < 0) {
      return new RawHttp.Response(0, "", List.of(), "");
    }
    String head = text.substring(0, headersEnd);
    List<String> lines = List.of(head.split("\r\n"));
    int status = Integer.parseInt(lines.get(0).substring("HTTP/1.1 ".length()).split(" ")[0]);
    String declared = null;
    for (String line : lines.subList(1, lines.size())) {
      if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
        declared = line.substring(line.indexOf(':') + 1).trim();
      }
    }
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(text.substring(headersEnd + 4).getBytes(StandardCharsets.ISO_8859_1));
    if (declared != null) {
      int missing = Integer.parseInt(declared) - body.size();
      while (missing > 0) {
        int read = in.read(buffer, 0, Math.min(missing, buffer.length));
        if (read < 0) {
          break;
        }
        body.write(buffer, 0, read);
        missing -= read;
      }
    }
    return new RawHttp.Response(status, lines.get(0), List.copyOf(lines.subList(1, lines.size())),
        body.toString(StandardCharsets.ISO_8859_1));
  }
}
