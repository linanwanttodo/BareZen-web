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
 * <p>Three facts and no helpers, deliberately. There is no {@code ok()}, {@code notFound()}, {@code json(...)} or
 * {@code redirect(...)}: each of those is a status plus a {@code Content-Type} plus a body shape, and the pair
 * {@code (302, Location)} or {@code (204, must-not-have-a-body)} is a rule about HTTP that belongs to a type written
 * with the first application that needs it, where the rule can be tested. A convenience on this type is a rule no
 * handler can opt out of. What an application writes instead is legible in one line:
 *
 * <pre>{@code
 * new Response(200, Headers.of("Content-Type", "application/json"), jsonBytes);
 * }</pre>
 *
 * <p>{@code HEAD} is the case this shape cannot express on its own: HTTP says a HEAD response carries the headers a
 * GET would have produced and no body, so a handler either returns the full body and lets the adapter discard it, or
 * reads the method and builds the headers twice. Neither choice belongs in the contract, and the honest answer is that
 * the adapter decides whether to write a body for a {@code HEAD} request — which it can, because it holds the method.
 * A future {@code Response.noBody()} would only hide that decision, so it is not here.
 *
 * <p>Instances are immutable; the body array is copied on the way in and on the way out.
 */
public final class Response {

  private static final byte[] NO_BODY = new byte[0];

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
   *     that needs none, though an adapter still adds {@code Content-Length}
   * @param body the response body, or {@code null} for none — a body of zero bytes and no body are the same thing to
   *     this contract, which {@code Content-Length: 0} distinguishes on the wire. Copied on construction, so a handler
   *     cannot change what it returned after returning it, and an adapter's buffer is not held.
   * @throws NullPointerException if {@code headers} is null
   * @throws IllegalArgumentException if {@code status} is outside 100..599
   */
  public Response(int status, Headers headers, byte[] body) {
    this.status = checkedStatus(status);
    this.headers = Objects.requireNonNull(headers,
        "the response headers; a response with no header set is not a response a server can write");
    this.body = body == null ? NO_BODY : body.clone();
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
}
