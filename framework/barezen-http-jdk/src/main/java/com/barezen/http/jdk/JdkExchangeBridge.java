package com.barezen.http.jdk;

import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * The translation between what {@code com.sun.net.httpserver} hands over and what the contract names, in both
 * directions: {@link #toRequest(HttpExchange)} reads an exchange into a {@link Request}, {@link #send(HttpExchange,
 * boolean, Response)} writes a {@link Response} out of it.
 *
 * <p>Package private on purpose. This is not an API an application calls; it is the JDK side of a boundary whose
 * <em>shape</em> is already public — {@link Request}'s and {@link Response}'s constructors. Making the bridge public
 * would offer a second way to build a request, one that bypasses the contract's own validation, which is what ADR-0018
 * §2 refuses, and a second way to write a response, which is what ADR-0018 §3 refuses (ADR-0022 §9).
 *
 * <p>Every rule below exists because the JDK's view of a request line is not the contract's view, and the difference is
 * load-bearing rather than cosmetic:
 *
 * <ul>
 *   <li><b>the path is read from {@code getRawPath()}, never {@code getPath()}.</b> {@code URI} decodes on the way to
 *       {@code getPath()}: the probe in ADR-0022 §3 sent {@code /users/a%2Fb} and read back {@code path=[/users/a/b]}
 *       beside {@code rawPath=[/users/a%2Fb]}. Choosing the decoded half would route an encoded slash as two segments
 *       and reach a different resource than the one the client named, so the router's raw-segment semantics
 *       (ADR-0019 §4, §5) depend on this one method call;
 *   <li><b>the query is read from {@code getRawQuery()}, and null stays null.</b> The JDK hands {@code ""} for
 *       {@code /x?} and {@code null} for {@code /x}, which is the same absent-versus-empty distinction
 *       {@link Request#query()} keeps as an {@code Optional}; parsing it into a map would be a Query API nobody has
 *       asked for (ADR-0018 §2, ADR-0022 §4);
 *   <li><b>the body is read fully, bounded by the request-body limit.</b> {@link Request}
 *       holds a {@code byte[]}, so the whole body is in memory — the cost ADR-0018 §7 accepted, with the bound
 *       ADR-0031 §Decision added: a declared {@code Content-Length} above the limit is refused on the claim, before
 *       the counting read, and a body of unknown length is refused at the first byte past it. Neither refusal closes
 *       anything here: the answer goes to the client first and the exchange is released by the dispatcher's
 *       {@code finally}, which is what lets the JDK's own drain-or-close recovery run <em>after</em> the 413 rather
 *       than before it ({@link #readBody});
 *   <li><b>a target the contract refuses becomes {@code IllegalArgumentException}, not {@code 500}.</b> The client sent
 *       something unparseable; answering that with a server-failure status would blame the wrong party
 *       (ADR-0023 §5, §6).
 * </ul>
 *
 * <p>What this bridge does <em>not</em> do: decode, normalize, resolve a dot-segment, lowercase, redirect a trailing
 * slash, split a cookie, or read {@code Forwarded}. The first five would change what the client asked for, and the last
 * two are policies with no consumer in this phase. The body-size limit it does enforce is not an invented policy: it is
 * the phase's formal decision, with the configuration key, the default, and the refusal status in ADR-0031.
 */
final class JdkExchangeBridge {

  /** The status-line text of the five answers this adapter can produce without an application. */
  private static final String BAD_REQUEST = "Bad Request";
  private static final String NOT_FOUND = "Not Found";
  private static final String METHOD_NOT_ALLOWED = "Method Not Allowed";
  private static final String CONTENT_TOO_LARGE = "Content Too Large";
  private static final String INTERNAL_SERVER_ERROR = "Internal Server Error";

  private JdkExchangeBridge() {}

  /**
   * The request an exchange carries, in the form {@link Request} documents, read under {@code limit}.
   *
   * @param exchange the JDK exchange, already parsed by the JDK's own HTTP parser
   * @param limit the request-body ceiling enforced on the way in (ADR-0031 §Decision)
   * @return the contract's request, never null
   * @throws BodyLimit.Exceeded if the content is over the limit — refused on a declared {@code Content-Length} before
   *     the counting read, or at the first byte past the bound when the length was never declared; unchecked on purpose
   *     so that the {@code IOException} catch below cannot fold it into a 400
   * @throws IOException if the client's body did not arrive — a truncated or aborted request, which is the client's
   *     failure and must be answered as one (ADR-0023 §6)
   * @throws IllegalArgumentException if the target is not a path this contract can name, thrown by {@link Request}
   *     itself rather than re-implemented here
   * @throws NullPointerException if {@code exchange} is null
   */
  static Request toRequest(HttpExchange exchange, BodyLimit limit) throws IOException {
    Objects.requireNonNull(exchange, "the exchange to read; with none there is no request to build");
    HttpMethod method = HttpMethod.of(exchange.getRequestMethod());
    URI target = exchange.getRequestURI();
    String rawPath = target.getRawPath();
    if (rawPath == null) {
      // An opaque request target ("urn:x") carries no path at all. Probed behaviour is that the JDK refuses one before
      // an exchange reaches a handler (ADR-0022 §2), so this line is a boundary check rather than a branch with a known
      // input; ADR-0022 §9 records it as the module's one line a socket test cannot reach.
      throw new IllegalArgumentException("the request target \"" + target + "\" carries no path; an HTTP request must"
          + " name either a path beginning with \"/\" or an absolute-form URI");
    }
    Headers headers = toHeaders(exchange.getRequestHeaders());
    long declared = declaredLength(headers);
    if (declared > limit.bytes()) {
      // Refused on the claim, before the counting read and before this method touches the stream. Two measured
      // reasons for that placement. It saves the bytes: a 64 MiB claim is refused without a byte of it being read
      // or allocated (ADR-0031 §Decision 4). And it cannot be improved on by refusing from inside the body read:
      // the server already materializes the request stream before the handler runs ({@code ServerImpl.java:899} on
      // JDK 17, {@code :903} on JDK 21), so opening it here changes nothing — while closing it on the refusal
      // would run the JDK's leftover drain BEFORE the 413 is written ({@code LeftOverInputStream.java:65-69}),
      // which is a wait on the client's remaining bytes that sits between the client and its answer
      // (ADR-0031 §Decision 5; the drain belongs after the answer, in {@code JdkRequestDispatcher}'s finally).
      throw new BodyLimit.Exceeded(limit.bytes(), "declared Content-Length of " + declared);
    }
    byte[] body = readBody(exchange, limit);
    return new Request(method, rawPath, target.getRawQuery(), headers, body);
  }

  /**
   * The {@code Content-Length} the client declared, or {@code -1} when the length is not a usable declared number.
   *
   * <p>{@code -1} is the conservative answer, chosen to match what the JDK does with the same value rather than to
   * invent a fourth state: a declared length that this module cannot parse ({@code abc}, {@code +5}, a value past
   * {@code Long}) never reaches an exchange at all — the JDK's parser answers it with a 400 of its own before a
   * handler exists, measured on both JDKs ({@code ServerImpl.java:824-833}, ADR-0023 §7) — and an absent field is the
   * chunked case, where the bounded counting read below is the enforcement. So of the values this line can return,
   * the ones that can be refused early are refused early and the ones that can't are counted. A
   * {@code Content-Length}/{@code Transfer-Encoding} pair — the one shape where the declared number would be a lie —
   * is likewise refused by the parser before this method runs ({@code ServerImpl.java:797-801}).
   */
  private static long declaredLength(Headers headers) {
    List<String> values = headers.values("Content-Length");
    if (values.size() != 1) {
      return -1;
    }
    try {
      return Long.parseLong(values.get(0).trim());
    } catch (NumberFormatException unreadable) {
      return -1;
    }
  }

  /**
   * The JDK's header map into the contract's field lines.
   *
   * <p>Two limits are recorded here because they are real and the phase's rule is not to claim otherwise (ADR-0022 §5):
   * {@code com.sun.net.httpserver.Headers} is case-insensitive <em>and</em> canonicalizes each name to
   * {@code First-upper, rest-lower}, so a client that sent {@code X-mixedCase} arrives as {@code X-mixedcase}, and it is
   * backed by a hash table, so the order the field lines arrived in is already gone before this method runs. Matching
   * stays case-insensitive either way, repeated names keep every value in order, and an empty value stays distinct from
   * an absent one — the three properties {@link Headers} actually promises.
   *
   * @param source the JDK's view of the request headers, never null
   * @return the contract's field lines, in the JDK's name order rather than the client's
   */
  static Headers toHeaders(com.sun.net.httpserver.Headers source) {
    Objects.requireNonNull(source, "the request headers to read; with none there is no header set to build");
    Headers target = Headers.empty();
    for (String name : source.keySet()) {
      for (String value : source.get(name)) {
        // An empty name or a control character in a value cannot arrive from the JDK's parser, so Headers' own refusal
        // is a boundary statement rather than a path a client can reach (ADR-0022 §5).
        target = target.with(name, value);
      }
    }
    return target;
  }

  /**
   * Write a response to an exchange, exactly once.
   *
   * <p>The framing rules below are the JDK's, measured on JDK 17 and JDK 21 rather than assumed, and they are the reason
   * this method exists instead of three lines in the caller (ADR-0022 §6, ADR-0027 §6):
   *
   * <ul>
   *   <li>{@code sendResponseHeaders(code, length)} with {@code length > 0} writes that exact {@code Content-Length}
   *       and throws {@code IOException: insufficient bytes written to stream} if fewer bytes arrive, so the length
   *       passed here is always {@code body().length} and the array written here is that same array — the two cannot
   *       disagree, because {@code Response} copies its body and nothing re-reads it in between;
   *   <li>{@code length == 0} means <em>chunked</em> to this JDK, not "empty body": a probed {@code 200} sent
   *       {@code Transfer-encoding: chunked} for it. So an empty body is declared as {@code -1}, which writes
   *       {@code Content-Length: 0} and no body, which is what a zero-length response is;
   *   <li>{@code -1} means "no body follows" and, for a status that may carry one, writes {@code Content-Length: 0}. For
   *       a {@code 1xx}, {@code 204} or {@code 304} the JDK writes no {@code Content-Length} at all, which is what those
   *       statuses require;
   *   <li><b>a {@code HEAD} response is the one case the declaration cannot express</b>, because HEAD needs the length of
   *       the representation and none of its bytes. The JDK's {@code HEAD} branch suppresses the body and writes no
   *       {@code Content-Length} of its own — and warns if a positive length was declared — so the length is written into
   *       the response fields directly here, and {@code -1} is declared. Measured: the field reaches the client and the
   *       body does not, which is exactly what HTTP asks of HEAD, and no server-side warning is logged. This is what
   *       closes the limitation ADR-0022 §7 recorded; "no body" and "no length" are different statements, and a HEAD
   *       needs the second one to be the length a GET would have sent. The branch is entered by the raw token only
   *       ({@code ExchangeImpl.isHeadRequest()} is {@code "HEAD".equals(method)}), which is why the caller compares the
   *       same token the same way rather than the case-folded {@code HttpMethod} — see
   *       {@link JdkRequestDispatcher} and ADR-0028;
   *   <li>field lines are added one per value, so two {@code Set-Cookie} values leave as two lines. The JDK canonicalizes
   *       and re-orders names on the way out, which is recorded with the same honesty as the request side
   *       (ADR-0022 §5, §6).
   * </ul>
   *
   * <p><b>{@code Content-Length} and {@code Transfer-Encoding} are written here or not at all.</b> {@link Response}
   * refuses both fields (ADR-0027 §5), which is what makes the single {@code Content-Length} on the wire this method's,
   * derived from the array it was handed. The measured reason: on {@code HEAD} and {@code 304} the JDK never reaches the
   * line that writes its declared length, so a handler-set field was the only one sent — and on {@code 204} a
   * handler-set {@code Content-Length} made {@code java.net.http.HttpClient} fail the response outright.
   *
   * @param exchange the exchange to answer
   * @param writesBody whether the body goes on the wire — {@code false} for a {@code HEAD} request, which the caller
   *     decides because {@code Response} documents that the adapter owns that rule (ADR-0018 §3)
   * @param response what the handler returned, never null
   * @throws IOException if bytes did not reach the client. A failure after the status line was written leaves the
   *     response committed, and the caller must not attempt a second status (ADR-0023 §6)
   */
  static void send(HttpExchange exchange, boolean writesBody, Response response) throws IOException {
    Objects.requireNonNull(exchange, "the exchange to answer");
    Objects.requireNonNull(response, "the response to write; with none there is no status to send");
    byte[] body = response.body();
    boolean statusCarriesABody = mayCarryABody(response.status());
    int declared = (writesBody && statusCarriesABody && body.length > 0) ? body.length : -1;
    com.sun.net.httpserver.Headers outgoing = exchange.getResponseHeaders();
    for (String name : response.headers().names()) {
      for (String value : response.headers().values(name)) {
        outgoing.add(name, value);
      }
    }
    if (!writesBody && statusCarriesABody) {
      // The representation's length, stated without its bytes: HEAD's definition. Written after the handler's fields
      // because a handler cannot supply this name at all — Response refuses it — so set() has nothing to replace.
      outgoing.set("Content-Length", Integer.toString(body.length));
    }
    exchange.sendResponseHeaders(response.status(), declared);
    if (!writesBody || body.length == 0) {
      // Nothing to commit beyond the header block. Closing is left to the caller's finally so that both this path and
      // the throwing one release the exchange exactly once (ADR-0022 §6).
      return;
    }
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  /**
   * Whether HTTP allows a body on this status, which is the same set of codes {@link Response} enforces.
   *
   * <p>Duplicated rather than exposed, deliberately: {@code Response} owns "you may not hand me bytes for a 204" and this
   * module owns "nothing gets written for a 204", and making the second rule read the first would put a wire decision in
   * the contract or a contract rule in the adapter. The two lists are pinned to each other by tests on both sides —
   * {@code refusesEveryStatusThatCarriesNoBodyWhenBytesWereGiven} in the contract, and
   * {@code aTwoOhFourIsWrittenWithNoBodyAndNoLength} beside {@code aHeadOfATwoOhFourStatesNoLength} here
   * (ADR-0027 §4, §9).
   */
  private static boolean mayCarryABody(int status) {
    return !(status >= 100 && status < 200) && status != 204 && status != 304;
  }

  /**
   * A 400, 404 or 500 as this adapter answers it: a status, a plain-text body naming nothing but the status, and no
   * application data (ADR-0023 §2, §4, §5).
   *
   * <p>Private rather than inlined at the three call sites because the body has to be built the same way each time — the
   * same content type, the same absence of anything the client sent. A 405 is the one answer that does not go through
   * here, because it carries a field as well as a body.
   *
   * @param status the status code to write
   * @param text the whole body, which is the status text and nothing else
   * @return a response an application's data cannot reach
   */
  private static Response statusOnly(int status, String text) {
    byte[] body = text.getBytes(StandardCharsets.UTF_8);
    return new Response(status, Headers.of("Content-Type", "text/plain; charset=utf-8"), body);
  }

  /** @return the answer to "nothing lives at this path", which names no path and no route */
  static Response notFound() {
    return statusOnly(404, NOT_FOUND);
  }

  /**
   * @param allow the field value, already ordered by the caller — {@code Allow} is built here because this is the one
   *     response this module produces whose body and fields have to be assembled together, and the ordering rule is
   *     routing knowledge rather than framing knowledge (ADR-0023 §3)
   * @return the answer to "something lives here, but not for this method"
   */
  static Response methodNotAllowed(String allow) {
    Headers headers = Headers.of("Content-Type", "text/plain; charset=utf-8").with("Allow", allow);
    return new Response(405, headers, METHOD_NOT_ALLOWED.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * @return the body of a 400, which is what a target {@link Request} itself refuses arrives as — the client named a
   *     path this contract cannot hold (a percent-encoded slash with no leading {@code /} is the reachable case, see
   *     ADR-0023 §5), and the answer blames the request rather than the server
   */
  static Response badRequest() {
    return statusOnly(400, BAD_REQUEST);
  }

  /** @return the answer to a handler that threw — the status text and nothing else, per ADR-0023 §4 */
  static Response internalServerError() {
    return statusOnly(500, INTERNAL_SERVER_ERROR);
  }

  /**
   * @return the answer to content over the limit — the status text and nothing else. RFC 9110 §15.5.14 names the
   *     status Content Too Large, which is what this body says; the status line carries the JDK's own reason phrase,
   *     {@code Request Entity Too Large}, for the same reason 404/405/500 carry theirs — the status line is the
   *     server's, and a framework that rewrote it would be editing an answer it does not own (ADR-0023 §2, §4;
   *     ADR-0031 §Decision 3).
   */
  static Response contentTooLarge() {
    return statusOnly(413, CONTENT_TOO_LARGE);
  }

  /**
   * The whole body within the limit, or the counting refusal — and no {@code close()} here, deliberately.
   *
   * <p>No {@code readAllBytes()}: the contract says a request is a complete value, and the limit says a complete value
   * has a size (ADR-0031 §Decision). No try-with-resources either, which is a timing fact rather than a style choice:
   * closing a body stream whose surplus has not arrived runs the JDK's leftover drain
   * ({@code LeftOverInputStream.close()} → {@code drain(drainAmount)}), and a drain on a stalled client blocks — so a
   * refusal closed here would stall <em>before its own 413 was written</em>, measured as nine silent seconds against
   * four milliseconds when the answer goes first. The release point is the dispatcher's {@code finally} on
   * {@code exchange.close()}, which every path reaches after the answer has been written; the drain's rules then work
   * on the JDK's terms — at most {@code drainAmount} (64 KiB) absorbed, connection kept if the body ends inside that,
   * closed otherwise ({@code ServerImpl.java:456-462}) — and the surplus is never parsed as the next request, which
   * is the only desynchronization this path could have caused.
   *
   * <p>The declared-length refusal is not here either: {@link #toRequest} judges the claim before this method is
   * reached, so a client that announces 64 MiB is refused without this module reading or allocating a byte of it.
   */
  private static byte[] readBody(HttpExchange exchange, BodyLimit limit) throws IOException {
    return BodyLimit.readBounded(exchange.getRequestBody(), limit.bytes());
  }
}
