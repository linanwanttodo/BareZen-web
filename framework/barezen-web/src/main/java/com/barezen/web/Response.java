package com.barezen.web;

import java.util.Objects;

/**
 * What a handler hands back: a status, header fields, and a body.
 *
 * <p>A response is produced, not edited. A {@link Handler} builds one and returns it, and the adapter writes it out —
 * which is why there is no {@code setStatus}, no {@code addHeader} and no {@code send}: a response with a mutable body
 * and a mutable status is a response that can be written to the client twice, or half-written then changed. A server
 * that commits bytes to a socket as they arrive cannot be stopped by an API, but a contract that has no way to write
 * anything is the strongest statement this phase can make about it (ADR-0018 §3).
 *
 * <p>Three facts and no helper <em>constructors</em>, deliberately. There is no {@code ok()}, {@code notFound()},
 * {@code json(...)} or {@code redirect(...)}: each of those is a status plus a {@code Content-Type} plus a body shape,
 * and a convenience on this type is a rule no handler can opt out of. What an application writes instead is legible in
 * one line:
 *
 * <pre>{@code
 * new Response(200, Headers.of("Content-Type", "application/json"), jsonBytes);
 * }</pre>
 *
 * <p><b>Two rules are enforced here rather than left to a reader</b>, and both are here because nowhere else could they
 * be enforced early enough to be useful (ADR-0027 §4, §5):
 *
 * <ul>
 *   <li><b>a status that forbids a message body is not given one.</b> HTTP says {@code 1xx}, {@code 204}, {@code 205}
 *       and {@code 304} carry no body, so a non-empty body alongside one of those statuses is refused rather than
 *       written and then quietly discarded at the socket. The alternative was measured: the JDK forces the length to
 *       {@code -1}, drops
 *       the bytes, and logs a warning on the server — the response a client sees is not the response the handler
 *       returned, and nobody is told. A refusal at construction names the line the developer wrote; a silent drop names
 *       nothing. An empty body stays legal, because {@code (204, no content)} is exactly what the status means.
 *   <li><b>{@code Content-Length} and {@code Transfer-Encoding} are not a handler's to write.</b> These two fields
 *       describe how many bytes follow the header block, which is a fact about the connection rather than about the
 *       application's answer, and only the component writing the bytes can state it truly. Measured consequences of
 *       letting a handler write them: a {@code 204} carrying a {@code Content-Length} makes a real Java client
 *       ({@code java.net.http.HttpClient}) fail the response with {@code IOException}, a {@code 304} and a {@code HEAD}
 *       emit <em>two</em> {@code Content-Length} lines when a handler adds one beside the server's, and a handler-written
 *       {@code Transfer-Encoding: chunked} reaches the wire beside an identity-encoded body. Each of those is a
 *       desynchronisation waiting for a proxy downstream, so the fields are refused here and derived by the adapter from
 *       the body it was actually handed.</ul>
 *
 * <p>What the shape still cannot express is a response whose body is not in memory — a large file, a generated export.
 * That is the cost ADR-0018 §7 accepted rather than hidden here, and it is why there is no {@code noBody()} either: with
 * the rule above, "no body" is already expressible as an empty one, and a second way to say it would be a way to say it
 * for a status that was allowed to have one.
 *
 * <p>{@code HEAD} is the case this shape expresses by saying nothing. HTTP defines a HEAD response as the fields a GET
 * would have produced and no body — where {@code "no body"} is a statement about the bytes on the wire, not about the
 * representation, which still has a length. So a handler returns the same {@code Response} it would return for the GET,
 * and the adapter decides both consequences: it writes no bytes, and it derives the length of the body it did not write
 * (ADR-0027 §6). A handler that instead returned an empty body for a HEAD would be reporting a zero-length
 * representation, which is why {@code Response} cannot own that decision — it cannot tell "the resource is empty" from
 * "this is a HEAD".
 *
 * <p>Instances are immutable; the body array is copied on the way in and on the way out.
 */
public final class Response {

  private static final byte[] NO_BODY = new byte[0];

  /**
   * The two fields that say how many bytes follow the header block, refused on a response whatever its status.
   *
   * <p>Measured on this framework's own adapter, on JDK 17 and JDK 21 alike: a {@code 204} carrying a
   * {@code Content-Length} makes {@code java.net.http.HttpClient} abandon the response with
   * {@code IOException: unexpected content length header with 204 response}; a {@code 304} and a {@code HEAD} response
   * leave <em>two</em> {@code Content-Length} lines on the wire when a handler adds one beside the length the server
   * writes; and a handler-written {@code Transfer-Encoding: chunked} reaches the wire beside a body the server is
   * sending identity-encoded. The first two are the duplicate-length and the contradictory-framing shapes a downstream
   * desynchronises on, and the third is request smuggling by field disagreement. A handler has no way to know which of
   * those it is producing, so it is not given the field: the adapter writes framing fields, from the body it was handed
   * (ADR-0027 §5). A {@code Request} may still carry them — refusing a field the client sent would be editing what the
   * client asked for, which ADR-0018 §2 forbids.
   */
  private static final String[] FRAMING_FIELDS = {"Content-Length", "Transfer-Encoding"};

  private final int status;
  private final Headers headers;
  private final byte[] body;

  /**
   * A response as a handler built it.
   *
   * @param status the status code, between 100 and 599 inclusive. Checked, because an out-of-range code cannot be
   *     written on a response line — a wrong status is a worse bug than a wrong body, since the client's control flow
   *     reads it, and 599 is the last code HTTP defines.
   * @param headers the header fields, never null; {@link Headers#empty()} is a legitimate answer for a response
   *     that needs none, though the adapter still writes {@code Content-Length}. May not carry {@code Content-Length}
   *     or {@code Transfer-Encoding}, which are the server's to state.
   * @param body the response body, or {@code null} for none — a body of zero bytes and no body are the same thing to
   *     this contract, which {@code Content-Length: 0} distinguishes on the wire. Copied on construction, so a handler
   *     cannot change what it returned after returning it, and an adapter's buffer is not held. Must be empty for a
   *     status HTTP says carries no message body: {@code 100..199}, {@code 204}, {@code 205} and {@code 304}.
   * @throws NullPointerException if {@code headers} is null
   * @throws IllegalArgumentException if {@code status} is outside 100..599, if {@code status} carries no body but a
   *     non-empty one was given, or if {@code headers} holds {@code Content-Length} or {@code Transfer-Encoding}
   */
  public Response(int status, Headers headers, byte[] body) {
    this.status = checkedStatus(status);
    this.headers = Objects.requireNonNull(headers,
        "the response headers; a response with no header set is not a response a server can write");
    checkCarriesNoFramingField(this.status, this.headers);
    byte[] copied = body == null ? NO_BODY : body.clone();
    checkBodyAllowed(this.status, copied.length);
    this.body = copied;
  }

  /**
   * The status code the client will read.
   *
   * @return a code between 100 and 599
   */
  public int status() {
    return status;
  }

  /**
   * The header fields to send.
   *
   * @return the headers, never null
   */
  public Headers headers() {
    return headers;
  }

  /**
   * The body, complete, as a copy.
   *
   * <p>Copied on every call for the same reason {@code Request.body()} is: the array this handler wrote is the array an
   * adapter writes to the socket, and a handler that kept a reference to it would be sharing the response body with
   * whatever runs after it. A streaming response — a large file, a generated export — is not expressible through this
   * method, which is the cost chosen in {@link Request} and recorded in ADR-0018 §7 rather than hidden here.
   *
   * @return the body, or an empty array when there is none; never null
   */
  public byte[] body() {
    return body.clone();
  }

  /**
   * Status, body size and header names — never the body and never a header value.
   *
   * <p>Same rule as {@link Request#toString()} and for the same reason: an error response is exactly the response an
   * application logs, and an error body often repeats what the client sent.
   *
   * @return a summary such as {@code Response[200, 128 body bytes, 2 fields]}
   */
  @Override
  public String toString() {
    return "Response[" + status + ", " + body.length + " body byte" + (body.length == 1 ? "" : "s") + ", "
        + headers.names().size() + " field" + (headers.names().size() == 1 ? "" : "s") + "]";
  }

  private static int checkedStatus(int status) {
    if (status < 100 || status > 599) {
      throw new IllegalArgumentException("the status code must be between 100 and 599; got " + status
          + ". A number outside that range cannot be written on a response line, and a client branches on the status"
          + " — so a wrong one is not a cosmetic error, it is the wrong answer delivered successfully");
    }
    return status;
  }

  /**
   * Refuse a body on a status that has no place for one.
   *
   * <p>Checked rather than left to the write because the write cannot check it usefully: by then the handler has already
   * returned, and the only two things an adapter can do with the bytes are send them where HTTP forbids them or drop them
   * without saying so. Both were measured on this adapter — the JDK forces the declared length to {@code -1}, logs
   * {@code WARNING: sendResponseHeaders: rCode = 204: forcing contentLen = -1} on the server, and the client receives a
   * response with none of the body the handler wrote. A silent disagreement between what an application returned and
   * what a client got is the failure this contract exists to prevent (ADR-0018 §3), and the earliest point at which it
   * can be named is the constructor the developer wrote the line in.
   *
   * <p>{@code 100..199}, {@code 204}, {@code 205} and {@code 304} are the statuses HTTP defines as carrying no message
   * body. Zero bytes are not checked and not refused: {@code (204, no content)} is the meaning of the status, and a
   * handler that returns an empty array or {@code null} is answering it correctly.
   */
  private static void checkBodyAllowed(int status, int bodyLength) {
    if (bodyLength == 0 || !carriesNoBody(status)) {
      return;
    }
    throw new IllegalArgumentException("a " + status + " response cannot carry a body; got " + bodyLength
        + " body bytes. HTTP defines 1xx, 204, 205 and 304 as responses with no message body. Sending these bytes"
        + " violates the status; discarding them later creates the silent disagreement this contract refuses. Return"
        + " the body with a status that has a place for it, or return no body at all (ADR-0027 §4)");
  }

  /** Whether HTTP defines this status as carrying no message body. */
  private static boolean carriesNoBody(int status) {
    return (status >= 100 && status < 200) || status == 204 || status == 205 || status == 304;
  }

  /**
   * Refuse a framing field on a response, whatever its status.
   *
   * <p>{@code Content-Length} and {@code Transfer-Encoding} state how many bytes follow the header block, which is a
   * fact about the write rather than about the answer, and only the component doing the writing can state it truly. The
   * measured shapes a handler could otherwise produce are on {@link #FRAMING_FIELDS}; note that none of them is visible
   * to the handler, which is exactly why the fields are refused rather than merely discouraged — a developer adding a
   * length "because the response should have one" has no way to know that this particular status and method combination
   * is the one that breaks.
   */
  private static void checkCarriesNoFramingField(int status, Headers headers) {
    for (String field : FRAMING_FIELDS) {
      String value = headers.first(field).orElse(null);
      if (value != null) {
        throw new IllegalArgumentException("a response must not carry " + field + "; got \"" + field + ": " + value
            + "\" on a " + status + " response. That field states how many bytes follow the header block, which the"
            + " component writing the bytes decides: this framework's adapter derives Content-Length from the body it was"
            + " handed and never sends Transfer-Encoding, because an in-memory body has a known length. A handler-set"
            + " value is dropped, duplicated or contradicted depending on the status and the request method, and all"
            + " three outcomes desynchronise a client downstream (ADR-0027 §5)");
      }
    }
  }
}
