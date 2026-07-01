package com.barezen.http.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.web.Handler;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.barezen.web.RouteMatch;
import com.barezen.web.Router;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What one method token means at each of the four places it is read, measured on a socket rather than reasoned about
 * (Phase 2.10 Stage B §B.3, Stage C §C.3–§C.5, ADR-0028).
 *
 * <p>The four levels are deliberately separate tests, because the whole finding of this phase is that the levels agree
 * with each other only where this table says they do. {@code HttpMethod.of()} folds the case of the nine standard names
 * and keeps an extension token exactly; {@code Router} keys on the folded value; the server dispatches on what the
 * router answered; and the wire framing follows the <em>raw token</em>, because that is what the JDK's own
 * {@code ExchangeImpl.isHeadRequest()} compares. Only the pair (exact {@code HEAD}, suppressed body) is HEAD's, and every
 * other token receives the ordinary response its representation has — the commitment ADR-0028 §Decision records, which
 * replaced the false {@code Content-length: 0} ADR-0027 limitation 2 measured.
 *
 * <p>Everything here reads raw sockets ({@link RawHttp} or the keep-alive reader below), because a high-level client
 * hides exactly what the table asserts: which field lines left the server, in what count, and how many body bytes
 * followed them.
 */
class JdkMethodCaseSemanticsTest {

  private static final byte[] HELLO = "hello".getBytes(StandardCharsets.UTF_8);

  private static int start(JdkHttpServerAdapter adapter) {
    adapter.start();
    return adapter.port().orElseThrow(() -> new AssertionError("a started adapter reports the port it bound"));
  }

  private static JdkHttpServerAdapter on(Router router) {
    return new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router);
  }

  /**
   * The contract level of the matrix: what {@code HttpMethod.of()} makes of each token, asserted as one table rather
   * than as nine separate claims.
   */
  @Nested
  @DisplayName("of(): the nine fold, an extension token stays exact, blank is refused")
  class ContractLevel {

    @Test
    void everyMatrixTokenBecomesExactlyWhatTheContractDocuments() {
      // The nine standard names arrive identical whatever case the client picked, and the shared constants are the
      // values — so a route registered as HEAD is found by a client that sent "head" (ADR-0028 §Investigation).
      assertThat(HttpMethod.of("GET")).isSameAs(HttpMethod.GET);
      assertThat(HttpMethod.of("get")).isSameAs(HttpMethod.GET);
      assertThat(HttpMethod.of("Get")).isSameAs(HttpMethod.GET);
      assertThat(HttpMethod.of("HEAD")).isSameAs(HttpMethod.HEAD);
      assertThat(HttpMethod.of("head")).isSameAs(HttpMethod.HEAD);
      assertThat(HttpMethod.of("Head")).isSameAs(HttpMethod.HEAD);
      assertThat(HttpMethod.of("POST")).isSameAs(HttpMethod.POST);
      assertThat(HttpMethod.of("post")).isSameAs(HttpMethod.POST);
      assertThat(HttpMethod.of("OPTIONS")).isSameAs(HttpMethod.OPTIONS);
      assertThat(HttpMethod.of("options")).isSameAs(HttpMethod.OPTIONS);

      // A method HTTP does not name is kept with the spelling that arrived, and two spellings are two methods — the
      // rule ADR-0018 §4 chose, re-pinned here because the wire rows below depend on the fold being exactly nine-wide.
      assertThat(HttpMethod.of("unknown").text()).isEqualTo("unknown");
      assertThat(HttpMethod.of("PURGE")).isNotEqualTo(HttpMethod.of("purge"));
    }

    @Test
    void ablankTokenIsRefusedRatherThanGuessedAsGET() {
      assertThatThrownBy(() -> HttpMethod.of("")).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be blank");
      // The wire side of the same fact appears in InvalidRequestLines: an empty token reaches the dispatcher as a
      // refusal and is answered 400, never silently served as a GET.
    }
  }

  /**
   * The route-table level: which handler the folded value selects, independent of any socket.
   */
  @Nested
  @DisplayName("Router: the folded value is the key, so case never moves a request to another handler")
  class RouterLevel {

    @Test
    void eachTokenFindsTheHandlerItsFoldedValueRegistered() {
      Handler hello = (request, parameters) -> new Response(200, Headers.empty(), HELLO);
      Router router = Router.builder()
          .route(HttpMethod.GET, "/doc", hello)
          .route(HttpMethod.HEAD, "/doc", hello)
          .route(HttpMethod.POST, "/doc", hello)
          .route(HttpMethod.OPTIONS, "/doc", hello)
          .build();

      for (String token : List.of("GET", "get", "Get")) {
        assertThat(match(router, token)).hasValueSatisfying(match -> assertThat(match.parameters()).isEmpty());
      }
      for (String token : List.of("HEAD", "head", "Head")) {
        assertThat(match(router, token)).isPresent();
      }
      for (String token : List.of("POST", "post", "OPTIONS", "options")) {
        assertThat(match(router, token)).isPresent();
      }
      // The extension tokens match nothing here, and methodsFor still names what the table does hold — the 405 shape
      // the wire rows assert.
      assertThat(match(router, "unknown")).isEmpty();
      assertThat(router.methodsFor("/doc")).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST,
          HttpMethod.OPTIONS);
      // An unregistered path answers the second question too: nothing lives there under any method.
      assertThat(router.methodsFor("/nowhere")).isEmpty();
    }

    private Optional<RouteMatch> match(Router router, String token) {
      Request request = new Request(HttpMethod.of(token), "/doc", null, Headers.empty(), null);
      return router.match(request);
    }
  }

  /**
   * The dispatch and wire levels together, because they are observable only together: a socket sees which handler
   * answered (via its marker field) and how the answer was framed (via the field lines and the body bytes).
   */
  @Nested
  @DisplayName("server dispatch and wire response: one row per token, both handlers counted")
  class WireLevel {

    /**
     * One row of the matrix.
     *
     * @param token the raw method token, sent verbatim
     * @param status the status the socket must see
     * @param route the handler that must have run — {@code "none"} when no handler may run
     * @param length the exact {@code Content-Length} value, which for a suppressed-body HEAD is the representation's
     *     length and not the bytes that followed
     * @param bodyBytes how many body bytes actually follow the header block
     */
    private record Row(String token, int status, String route, String length, int bodyBytes) {}

    @Test
    void everyTokenGetsTheOneAnswerItsRowDefines() throws IOException {
      AtomicInteger getCalls = new AtomicInteger();
      AtomicInteger headCalls = new AtomicInteger();
      Router served = Router.builder()
          .route(HttpMethod.GET, "/doc", (request, parameters) -> {
            getCalls.incrementAndGet();
            return new Response(200, Headers.of("Content-Type", "text/plain").with("X-Route", "get"), HELLO);
          })
          .route(HttpMethod.HEAD, "/doc", (request, parameters) -> {
            headCalls.incrementAndGet();
            return new Response(200, Headers.of("Content-Type", "text/plain").with("X-Route", "head"), HELLO);
          })
          .route(HttpMethod.POST, "/doc", (request, parameters) -> new Response(200,
              Headers.of("Content-Type", "text/plain").with("X-Route", "post"), HELLO))
          .route(HttpMethod.OPTIONS, "/doc", (request, parameters) -> new Response(200,
              Headers.of("Content-Type", "text/plain").with("X-Route", "options"), HELLO))
          .build();
      try (JdkHttpServerAdapter adapter = on(served)) {
        int port = start(adapter);

        List<Row> matrix = List.of(
            new Row("GET", 200, "get", "5", 5),
            new Row("get", 200, "get", "5", 5),
            new Row("Get", 200, "get", "5", 5),
            // The one row that suppresses bytes: exact HEAD, length true, nothing follows.
            new Row("HEAD", 200, "head", "5", 0),
            // The rows ADR-0028 changed: same handler, same status, same fields, same length — and the body a GET
            // would have gotten, because only the exact token is a HEAD to the server.
            new Row("head", 200, "head", "5", 5),
            new Row("Head", 200, "head", "5", 5),
            new Row("POST", 200, "post", "5", 5),
            new Row("post", 200, "post", "5", 5),
            new Row("OPTIONS", 200, "options", "5", 5),
            new Row("options", 200, "options", "5", 5),
            // Unknown and extension tokens: routed as themselves, which is no route here, so the adapter's 405 —
            // ordinary framing, because the token is not HEAD in any case.
            new Row("unknown", 405, "none", "18", 18),
            new Row("PURGE", 405, "none", "18", 18),
            new Row("purge", 405, "none", "18", 18));

        for (Row row : matrix) {
          RawHttp.Response answer = RawHttp.request(port, row.token(), "/doc", List.of(), null);
          assertThat(answer.status()).as("status for token %s", row.token()).isEqualTo(row.status());
          assertThat(answer.firstOf("Content-Length")).as("length for token %s", row.token()).isEqualTo(row.length());
          assertThat(answer.countOf("Content-Length")).as("exactly one length for token %s", row.token()).isEqualTo(1);
          assertThat(answer.valuesOf("Transfer-Encoding")).as("no chunked for token %s", row.token()).isEmpty();
          assertThat(answer.body()).as("body bytes for token %s", row.token()).hasSize(row.bodyBytes());
          String route = answer.firstOf("X-Route");
          assertThat(route).as("handler for token %s", row.token())
              .isEqualTo("none".equals(row.route()) ? null : row.route());
        }
        // Dispatch counts prove the split this table asserts: three GET spellings reached the GET handler, and the
        // HEAD handler answered all three of its spellings — case never moved a request between handlers, which is
        // the half of the decision that keeps the Router untouched.
        assertThat(getCalls.get()).isEqualTo(3);
        assertThat(headCalls.get()).isEqualTo(3);
      }
    }

    @Test
    void a405NamesTheAllowedMethodsInOneStableOrderForEveryToken() throws IOException {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/doc", (request, parameters) -> new Response(200, Headers.empty(), HELLO))
          .route(HttpMethod.HEAD, "/doc", (request, parameters) -> new Response(200, Headers.empty(), HELLO))
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        for (String token : List.of("POST", "post", "unknown", "PURGE")) {
          RawHttp.Response answer = RawHttp.request(port, token, "/doc", List.of(), null);
          // One Allow line, in HTTP's order, identical for all four tokens: the route table answers the method question
          // independently of the token's case (ADR-0023 §3). "head" is deliberately NOT in this list — it folds to
          // HEAD, this table registers HEAD, so it gets 200 from that handler, which the matrix test above pins.
          assertThat(answer.status()).as("status for token %s", token).isEqualTo(405);
          assertThat(answer.valuesOf("Allow")).as("one Allow for token %s", token).containsExactly("GET, HEAD");
          assertThat(answer.body()).as("body for token %s", token).isEqualTo("Method Not Allowed");
        }
      }
    }

    @Test
    void aFailureAndAMissKeepTheirBodyForANonExactTokenAndSuppressItForTheExactOne() throws IOException {
      AtomicInteger boom = new AtomicInteger();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/boom", (request, parameters) -> {
            boom.incrementAndGet();
            throw new IllegalStateException("the handler failed");
          })
          .route(HttpMethod.HEAD, "/boom", (request, parameters) -> {
            boom.incrementAndGet();
            throw new IllegalStateException("the handler failed");
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        // The adapter's own answers follow the same rule as a handler's: the method decides whether the bytes go, and
        // only the exact token suppresses. A "head" of a 500 gets the 21-byte text a GET got; a HEAD gets the length
        // and no bytes; both still ran the HEAD handler.
        RawHttp.Response upper = RawHttp.request(port, "HEAD", "/boom", List.of(), null);
        RawHttp.Response lower = RawHttp.request(port, "head", "/boom", List.of(), null);
        RawHttp.Response missUpper = RawHttp.request(port, "HEAD", "/nowhere", List.of(), null);
        RawHttp.Response missLower = RawHttp.request(port, "head", "/nowhere", List.of(), null);

        assertThat(upper.valuesOf("Content-Length")).containsExactly("21");
        assertThat(upper.body()).isEmpty();
        assertThat(lower.valuesOf("Content-Length")).containsExactly("21");
        assertThat(lower.body()).isEqualTo("Internal Server Error");
        assertThat(missUpper.valuesOf("Content-Length")).containsExactly("9");
        assertThat(missUpper.body()).isEmpty();
        assertThat(missLower.valuesOf("Content-Length")).containsExactly("9");
        assertThat(missLower.body()).isEqualTo("Not Found");
        // Two runs, not four: the /boom rows called the throwing handler under both HEAD spellings, and the /nowhere
        // rows were answered by the route table alone — a 404 has no handler to run, and its length still behaves.
        assertThat(boom.get()).isEqualTo(2);
      }
    }
  }

  /**
   * The request lines the matrix's "empty" and "invalid" rows name: where each is answered, and by whom.
   *
   * <p>Three answers are possible and they come from different components. The JDK's parser can refuse the line before
   * an exchange exists (its own HTML error page, which this adapter never writes and cannot style — ADR-0023 §7). A
   * line can reach the dispatcher with a token {@code HttpMethod.of()} refuses, which the adapter answers with its own
   * plain 400 (ADR-0023 §5). Or an odd-but-parseable token can reach a route lookup and be answered 405 as the
   * unknown method it is. The rows below pin which shape lands where, on both supported JDKs.
   */
  @Nested
  @DisplayName("empty and invalid request lines: which component answers each")
  class InvalidRequestLines {

    @Test
    void anEmptyTokenReachesTheDispatcherAndIsAnsweredWithTheAdaptersPlainFourHundred() throws IOException {
      try (JdkHttpServerAdapter adapter = on(
          answer(HttpMethod.GET, "/doc", new Response(200, Headers.empty(), HELLO)))) {
        int port = start(adapter);

        // A leading space gives the JDK's splitter an empty method token with a real target, so an exchange IS
        // created; HttpMethod.of("") then refuses, and the dispatcher answers 400 before any handler runs. The body is
        // the adapter's own text, not the JDK's HTML — the marker that this failure was ours to answer.
        RawHttp.Response answer = RawHttp.requestRaw(port,
            " /doc HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n\r\n");
        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.body()).isEqualTo("Bad Request");
        assertThat(answer.firstOf("Content-Type")).isEqualTo("text/plain; charset=utf-8");
        assertThat(answer.valuesOf("Content-Length")).containsExactly("11");
      }
    }

    @Test
    void aLineWithoutATargetIsRefusedByTheJdkBeforeAnExchangeExists() throws IOException {
      try (JdkHttpServerAdapter adapter = on(
          answer(HttpMethod.GET, "/doc", new Response(200, Headers.empty(), HELLO)))) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.requestRaw(port,
            "GET /doc\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n\r\n");
        // The JDK's own page — "Bad request line" is its wording, and no BareZen handler or dispatcher path produced
        // this answer (ADR-0023 §7). Pinned because the matrix's "invalid" row is a measurement of the parser, not a
        // behaviour this module owns or could change.
        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.body()).contains("<h1>400 Bad Request</h1>");
        assertThat(answer.firstOf("Content-Type")).isEqualTo("text/html");
      }
    }

    @Test
    void aTokenBrokenByASpaceTargetsNoPathAndTheServerAnswersNoContext() throws IOException {
      try (JdkHttpServerAdapter adapter = on(
          answer(HttpMethod.GET, "/doc", new Response(200, Headers.empty(), HELLO)))) {
        int port = start(adapter);

        RawHttp.Response answer = RawHttp.requestRaw(port,
            "GE T /doc HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n\r\n");
        // The middle token becomes the target ("T"), which names no context, so the server's own 404 answers before
        // this module sees the request. Recorded, not adopted: it is the parser's boundary, identical on both JDKs.
        assertThat(answer.status()).isEqualTo(404);
        assertThat(answer.body()).contains("No context found for request");
      }
    }

    @Test
    void anOddButParseableTokenIsOnlyAnotherUnknownMethod() throws IOException {
      try (JdkHttpServerAdapter adapter = on(answer(HttpMethod.GET, "/doc",
          new Response(200, Headers.of("Content-Type", "text/plain"), HELLO)))) {
        int port = start(adapter);

        // A control character, a quote, a non-ASCII byte: the JDK puts them in the token and hands the exchange over,
        // so each arrives as an extension method and the route table answers it with the 405 the matrix already pins.
        // (JDK 17 and 21 both — the parser accepted all three identically, measured in /tmp/p210/probe2.)
        for (String line : List.of("G\u0001ET", "\"GET\"", "G\u00e9ET")) {
          RawHttp.Response answer = RawHttp.requestRaw(port,
              line + " /doc HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n\r\n");
          assertThat(answer.status()).as("status for token %s", line).isEqualTo(405);
          assertThat(answer.valuesOf("Allow")).as("Allow for token %s", line).containsExactly("GET");
        }
      }
    }

    private static Router answer(HttpMethod method, String path, Response value) {
      return Router.builder().route(method, path, (request, parameters) -> value).build();
    }
  }

  /**
   * Keep-alive combinations, read frame by frame on one socket (Stage C §C.4).
   *
   * <p>The orders the directive lists — HEAD→GET, GET→HEAD, HEAD→HEAD, GET→GET — plus the one this phase's change made
   * new: a {@code head} response that carries bytes again, followed by more requests on the same connection. A framing
   * mistake here does not fail one response; it desyncs every later request on the connection, which is why the read
   * below consumes each frame exactly and the last row asserts the body still arrives whole.
   */
  @Nested
  @DisplayName("keep-alive: every order frames so the next request parses")
  class KeepAliveOrders {

    @Test
    void theFourOrdersAndTheBodyBearingMixesAllKeepTheConnectionSynchronised() throws IOException {
      AtomicInteger headCalls = new AtomicInteger();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/doc", (request, parameters) -> new Response(200,
              Headers.of("Content-Type", "text/plain"), HELLO))
          .route(HttpMethod.HEAD, "/doc", (request, parameters) -> {
            headCalls.incrementAndGet();
            return new Response(200, Headers.of("Content-Type", "text/plain"), HELLO);
          })
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        // One entry per request: the token and how many body bytes a correct client reads after the frame header —
        // 0 for the exact HEAD (a stated length with no bytes behind it), 5 for every ordinary answer.
        run(port, List.of("HEAD", "GET"), List.of(0, 5));
        run(port, List.of("GET", "HEAD"), List.of(5, 0));
        run(port, List.of("HEAD", "HEAD"), List.of(0, 0));
        run(port, List.of("GET", "GET"), List.of(5, 5));
        run(port, List.of("GET", "head", "GET", "Head", "GET", "HEAD", "GET"), List.of(5, 5, 5, 5, 5, 0, 5));
        run(port, List.of("head", "Head", "get", "GET"), List.of(5, 5, 5, 5));
        // Nine HEAD spellings across the orders above (3 exact, 6 non-exact): dispatch stayed case-folded while
        // framing stayed exact-token — every one of the nine frames above was read without desyncing its connection.
        assertThat(headCalls.get()).isEqualTo(9);
      }
    }

    /** Send each token once on ONE connection and read exactly one framed response per request. */
    private void run(int port, List<String> tokens, List<Integer> expectedBodyBytes) throws IOException {
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setSoTimeout(5_000);
        socket.getOutputStream().write(bytes(tokens));
        socket.getOutputStream().flush();
        InputStream in = socket.getInputStream();
        for (int i = 0; i < tokens.size(); i++) {
          Frame frame = Frame.read(in, expectedBodyBytes.get(i));
          assertThat(frame.status).as("status for request %d (%s)", i, tokens.get(i)).isEqualTo(200);
          assertThat(frame.length).as("stated length for request %d (%s)", i, tokens.get(i)).isEqualTo("5");
          assertThat(frame.read).as("body bytes read for request %d (%s)", i, tokens.get(i))
              .isEqualTo(expectedBodyBytes.get(i).intValue());
        }
      }
    }

    private static byte[] bytes(List<String> tokens) {
      StringBuilder message = new StringBuilder();
      for (String token : tokens) {
        message.append(token).append(" /doc HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
      }
      return message.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** One response, consumed exactly: fields parsed, body taken by the client's own expectation. */
    private record Frame(int status, String length, int read) {

      static Frame read(InputStream in, int clientExpectsBody) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int current;
        int seen = 0;
        while ((current = in.read()) >= 0) {
          header.write(current);
          seen = current == '\r' || current == '\n' ? seen + 1 : 0;
          if (seen >= 4) {
            break;
          }
        }
        String block = header.toString(StandardCharsets.US_ASCII);
        int status = Integer.parseInt(block.substring(block.indexOf(' ') + 1, block.indexOf(' ') + 4));
        String length = null;
        for (String field : block.split("\r\n")) {
          int colon = field.indexOf(':');
          if (colon > 0 && field.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
            length = field.substring(colon + 1).trim();
          }
        }
        int consumed = clientExpectsBody == 0 ? 0 : in.readNBytes(clientExpectsBody).length;
        return new Frame(status, length, consumed);
      }
    }
  }

  /**
   * The shapes Stage C §C.3 lists for a HEAD answer that the existing suite did not yet cover: a large representation,
   * several fields, and a null body.
   */
  @Nested
  @DisplayName("HEAD over a large body, many fields, and a null body")
  class HeadShapes {

    @Test
    void aHeadOfALargeRepresentationStatesItsFullLengthAndSendsNoneOfTheBytes() throws IOException {
      byte[] large = new byte[1024 * 1024];
      java.util.Arrays.fill(large, (byte) 'x');
      Router router = Router.builder()
          .route(HttpMethod.GET, "/big", (request, parameters) -> new Response(200,
              Headers.of("Content-Type", "application/octet-stream"), large))
          .route(HttpMethod.HEAD, "/big", (request, parameters) -> new Response(200,
              Headers.of("Content-Type", "application/octet-stream"), large))
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.Response get = RawHttp.request(port, "GET", "/big", List.of(), null);
        RawHttp.Response head = RawHttp.request(port, "HEAD", "/big", List.of(), null);

        assertThat(get.valuesOf("Content-Length")).containsExactly("1048576");
        assertThat(get.body()).hasSize(1048576);
        // Same length, zero bytes: the representation's size is what a HEAD exists to learn, at any size, and the
        // derived field scales to the body the handler would have sent.
        assertThat(head.valuesOf("Content-Length")).isEqualTo(get.valuesOf("Content-Length"));
        assertThat(head.body()).isEmpty();
      }
    }

    @Test
    void aHeadKeepsEveryFieldAGetWouldHaveReceived() throws IOException {
      Headers many = Headers.of("Content-Type", "text/plain")
          .with("Cache-Control", "no-store")
          .with("X-First", "one")
          .with("X-Second", "two")
          .with("Set-Cookie", "a=1")
          .with("Set-Cookie", "b=2");
      Router router = Router.builder()
          .route(HttpMethod.HEAD, "/doc", (request, parameters) -> new Response(200, many, HELLO))
          .route(HttpMethod.GET, "/doc", (request, parameters) -> new Response(200, many, HELLO))
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        RawHttp.Response head = RawHttp.request(port, "HEAD", "/doc", List.of(), null);
        RawHttp.Response get = RawHttp.request(port, "GET", "/doc", List.of(), null);

        // Field-for-field identical apart from the bytes: "headers preserved" is proven by comparing the two answers,
        // including the two Set-Cookie lines staying two lines each.
        for (String field : List.of("Cache-Control", "X-First", "X-Second", "Set-Cookie", "Content-Type")) {
          assertThat(head.valuesOf(field)).as("%s identical on HEAD and GET", field).isEqualTo(get.valuesOf(field));
        }
        assertThat(head.valuesOf("Set-Cookie")).containsExactly("a=1", "b=2");
        assertThat(head.valuesOf("Content-Length")).containsExactly("5");
        assertThat(head.body()).isEmpty();
        assertThat(get.body()).isEqualTo("hello");
      }
    }

    @Test
    void aHeadOfANullBodyAndAHeadOfAnEmptyArrayStateZeroAndSendNothing() throws IOException {
      Router router = Router.builder()
          .route(HttpMethod.HEAD, "/n", (request, parameters) -> new Response(200, Headers.empty(), null))
          .route(HttpMethod.HEAD, "/e", (request, parameters) -> new Response(200, Headers.empty(), new byte[0]))
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        // One fact to the contract, one answer on the wire — the pair ADR-0027 §3 kept together, HEAD's own framing
        // added: a zero-length representation says Content-Length: 0 rather than going silent.
        RawHttp.Response nullBody = RawHttp.request(port, "HEAD", "/n", List.of(), null);
        RawHttp.Response emptyBody = RawHttp.request(port, "HEAD", "/e", List.of(), null);
        assertThat(nullBody.valuesOf("Content-Length")).containsExactly("0");
        assertThat(emptyBody.valuesOf("Content-Length")).containsExactly("0");
        assertThat(nullBody.body()).isEmpty();
        assertThat(emptyBody.body()).isEmpty();
      }
    }
  }

  /**
   * HTTP/1.0 requests, which the JDK frames by the same rules but cannot keep alive.
   */
  @Nested
  @DisplayName("HTTP/1.0: the same suppression and the same length")
  class HttpOneOh {

    @Test
    void anExactHeadSuppressesBytesAndANonExactTokenSendsThemUnderHttpOneOhToo() throws IOException {
      Router router = Router.builder()
          .route(HttpMethod.HEAD, "/doc", (request, parameters) -> new Response(200,
              Headers.of("Content-Type", "text/plain"), HELLO))
          .build();
      try (JdkHttpServerAdapter adapter = on(router)) {
        int port = start(adapter);

        // No Connection header: an HTTP/1.0 request closes by default, and the frame must still say Content-length: 5
        // for the HEAD with nothing behind it, and deliver 5 bytes for the token the server reads as ordinary
        // (measured identical on JDK 17 and JDK 21, /tmp/p210/probe2/mf-17.txt).
        RawHttp.Response upper = RawHttp.requestRaw(port,
            "HEAD /doc HTTP/1.0\r\nHost: 127.0.0.1:" + port + "\r\n\r\n");
        RawHttp.Response lower = RawHttp.requestRaw(port,
            "head /doc HTTP/1.0\r\nHost: 127.0.0.1:" + port + "\r\n\r\n");

        assertThat(upper.status()).isEqualTo(200);
        assertThat(upper.valuesOf("Content-Length")).containsExactly("5");
        assertThat(upper.body()).isEmpty();
        assertThat(lower.status()).isEqualTo(200);
        assertThat(lower.valuesOf("Content-Length")).containsExactly("5");
        assertThat(lower.body()).isEqualTo("hello");
      }
    }
  }
}
