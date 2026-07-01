package com.barezen.http.jdk;

import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
 *   <li><b>the body is read fully and the stream is closed.</b> {@link Request} holds a {@code byte[]}, so the whole
 *       body is in memory — the cost ADR-0018 §7 accepted deliberately. Closing is not optional: an exchange whose
 *       request body was not drained is a connection the JDK has to recover from;
 *   <li><b>a target the contract refuses becomes {@code IllegalArgumentException}, not {@code 500}.</b> The client sent
 *       something unparseable; answering that with a server-failure status would blame the wrong party
 *       (ADR-0023 §5, §6).
 * </ul>
 *
 * <p>What this bridge does <em>not</em> do: decode, normalize, resolve a dot-segment, lowercase, redirect a trailing
 * slash, split a cookie, read {@code Forwarded}, or reject a body size. The first five would change what the client
 * asked for; the last two are policies with no consumer in this phase, and a body-size limit is recorded as the phase's
 * design debt rather than invented here (ADR-0022 §8, ADR-0023 §7).
 */
final class JdkExchangeBridge {

  /** The status-line text of the four answers this adapter can produce without an application. */
  private static final String BAD_REQUEST = "Bad Request";
  private static final String NOT_FOUND = "Not Found";
  private static final String METHOD_NOT_ALLOWED = "Method Not Allowed";
  private static final String INTERNAL_SERVER_ERROR = "Internal Server Error";

  private JdkExchangeBridge() {}

  /**
   * The request an exchange carries, in the form {@link Request} documents.
   *
   * @param exchange the JDK exchange, already parsed by the JDK's own HTTP parser
   * @return the contract's request, never null
   * @throws IOException if the client's body did not arrive — a truncated or aborted request, which is the client's
   *     failure and must be answered as one (ADR-0023 §6)
   * @throws IllegalArgumentException if the target is not a path this contract can name, thrown by {@link Request}
   *     itself rather than re-implemented here
   * @throws NullPointerException if {@code exchange} is null
   */
  static Request toRequest(HttpExchange exchange) throws IOException {
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
    byte[] body = readBody(exchange);
    return new Request(method, rawPath, target.getRawQuery(), headers, body);
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
   * The whole body, then close.
   *
   * <p>Tries-with-resources rather than {@code readAllBytes()} on the raw stream: the contract says a request is a
   * complete value, and the JDK says an exchange whose body was not drained is finished by closing it — doing both
   * leaves nothing for the JDK's recovery path to handle.
   */
  private static byte[] readBody(HttpExchange exchange) throws IOException {
    try (InputStream body = exchange.getRequestBody()) {
      return body.readAllBytes();
    }
  }
}
