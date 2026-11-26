package com.barezen.web;

import java.util.Objects;
import java.util.Optional;

/**
 * The part of an HTTP request a handler needs, in the form the client sent it.
 *
 * <p>Five facts: a method, a path, a query string, headers, and a body. That is the whole type, and it is the whole
 * point of the phase — an application receives a request through this instead of through a server's own request object,
 * so the same handler runs on any server and the server can be replaced without touching application code
 * (ADR-0018 §2, §12).
 *
 * <p>The path and the query are kept in the form that arrived on the wire, percent-decoded by nobody:
 *
 * <ul>
 *   <li>{@code /a%2Fb} is one segment containing a slash, while {@code /a/b} is two segments. {@code java.net.URI}
 *       would fold them together — its {@code getPath()} returns {@code "/a/b"} for both — so a router built on the
 *       decoded form would route {@code /a%2Fb} as though the client had asked for a different resource. Decoding is a
 *       decision about one resource, made by whoever owns it, not by the object that carries the bytes;
 *   <li>{@code /%2e%2e/x} decodes to {@code /../x}, which is the path traversal attempt in its useful form only after
 *       someone decides what {@code ..} means for a resource. The contract reports the encoded text and stays silent on
 *       the meaning, which is the only answer that is correct for every application.
 * </ul>
 *
 * <p>So there is no {@code uri()}, no {@code param(name)}, and no decoded twin of {@link #path()}. A query parameter
 * map is a parse of one field with rules of its own — repeated keys, {@code +} for space, charset, {@code ;} as a
 * separator, whether {@code a=1&a=2} is one value or two — and each of those is a decision that would be forced on
 * every application by putting it in this type. It belongs to a small helper with the first application that needs to
 * read a parameter, where the rules can be stated and overridden.
 *
 * <p>{@link #body()} is a copy of the whole body in memory (ADR-0018 §7). That is a decision with a cost, written
 * plainly: a request whose body is an upload cannot be served through this contract without buffering it, and buffering
 * an unbounded body is a denial of service. The reason for choosing it anyway is ownership — a stream can only be read
 * once, and only while whatever opened it stays open, which means the contract would have to say who closes it, what
 * happens when a handler returns without reading it, and what happens when a handler hands it to another thread. None
 * of those questions has an answer that does not depend on a server, so a streaming body is a separate type, written
 * with an adapter, not a flag on this one. The limits that make {@code byte[]} honest — a maximum body size, rejected
 * before the handler runs — belong to an adapter, which is the layer that knows what it can afford to read.
 *
 * <p>Instances are immutable: the arrays and lists this type holds are copied on the way in and copied on the way out,
 * so a handler cannot change the request an adapter built, and one handler passing a {@code Request} to another cannot
 * have it edited behind its back.
 */
public final class Request {

  private static final byte[] NO_BODY = new byte[0];

  private final HttpMethod method;
  private final String path;
  private final String query;
  private final Headers headers;
  private final byte[] body;

  /**
   * A request as an adapter assembled it from the wire.
   *
   * @param method the request method, never null
   * @param path the raw path, exactly as sent, beginning with {@code /} — or {@code *} for an {@code OPTIONS *} request
   *     line, which asks about the server rather than a resource
   * @param query the raw query string without the leading {@code ?}, or {@code null} when the request line had none.
   *     Null is accepted here rather than refused because a server hands over what it parsed, and a handler must be
   *     able to tell "no query" from an empty one; {@link #query()} expresses the difference as {@link Optional}
   * @param headers the header fields, never null; {@link Headers#empty()} for a request that arrived with none
   * @param body the request body, or {@code null} for none. Copied on construction, so the caller's buffer — a
   *     server's read buffer, usually — can be reused without changing this request
   * @throws NullPointerException if {@code method}, {@code path} or {@code headers} is null
   * @throws IllegalArgumentException if {@code path} does not begin with {@code /} and is not {@code *}, which is not a
   *     request target, or if {@code path} or {@code query} contains an ASCII control character or space. A raw space
   *     separates request-line fields and a control character can end a line; both must be percent-encoded instead.
   *     A relative path such as {@code user/1} would be resolved differently by every server, so accepting it would
   *     move the disagreement into the handler
   */
  public Request(HttpMethod method, String path, String query, Headers headers, byte[] body) {
    this.method = Objects.requireNonNull(method, "the request method; without one a handler cannot know"
        + " whether the client is asking or telling");
    this.path = checkedPath(path);
    this.query = checkedQuery(query);
    this.headers = Objects.requireNonNull(headers,
        "the request headers; a request without a header set is not a request, it is a missing message");
    this.body = body == null ? NO_BODY : body.clone();
  }

  /**
   * What the client asked the server to do.
   *
   * @return the method, never null
   */
  public HttpMethod method() {
    return method;
  }

  /**
   * The path, percent-decoded by nobody.
   *
   * <p>Always starts with {@code /}, or is exactly {@code *} for an {@code OPTIONS *} request. Compare it, split it,
   * decode it — those are the application's rules, not the contract's.
   *
   * @return the raw path, never null and never empty
   */
  public String path() {
    return path;
  }

  /**
   * The query string, without the leading {@code ?}, decoded by nobody.
   *
   * <p>Absent and empty stay distinct: {@code /search} gives an empty {@code Optional} while {@code /search?} gives
   * {@code Optional.of("")}. A handler that treats those as one is a handler that cannot tell "no filter given" from
   * "filtered by nothing", which is the difference between a default and a reset.
   *
   * @return the raw query string, or empty when the request line had no {@code ?}
   */
  public Optional<String> query() {
    return Optional.ofNullable(query);
  }

  /**
   * The header fields.
   *
   * @return the headers, never null
   */
  public Headers headers() {
    return headers;
  }

  /**
   * The body, complete, as a copy.
   *
   * <p>Every call copies, which is what keeps the immutability claim true: an array handed out once could be edited by
   * a handler and read differently by whatever runs after it. A body is read once and kept once by the handler that
   * needs it, so the copy is not in a loop. The consequence of this type existing at all — the whole body is in memory —
   * is stated in the class documentation, and a body too large for that is the case a streaming contract is written
   * for.
   *
   * @return the body, or an empty array when there is none; never null
   */
  public byte[] body() {
    return body.clone();
  }

  /**
   * Method, path and body size — never the body, and never a header value.
   *
   * <p>{@code Headers.toString()} prints names only for the same reason, and it is the reason this prints a length
   * instead of the bytes: a request summary lands in a log line or an exception message, and what is in there is then
   * in the log. A body can hold a password, a token or a document, and the request does not know which.
   *
   * @return a summary such as {@code Request[POST /login, 42 body bytes]}
   */
  @Override
  public String toString() {
    return "Request[" + method.text() + " " + path + ", " + body.length + " body byte"
        + (body.length == 1 ? "" : "s") + "]";
  }

  private static String checkedPath(String path) {
    Objects.requireNonNull(path, "the request path; with no path there is no resource a handler could act on");
    checkTargetCharacters(path, "request path");
    if (!path.equals("*") && !path.startsWith("/")) {
      throw new IllegalArgumentException("the request path must start with \"/\" or be \"*\"; got \"" + path + "\"."
          + " A path without a leading slash is relative, and what it is relative to is the server's business, not"
          + " the handler's");
    }
    return path;
  }

  private static String checkedQuery(String query) {
    if (query != null) {
      checkTargetCharacters(query, "request query");
    }
    return query;
  }

  private static void checkTargetCharacters(String target, String field) {
    for (int index = 0; index < target.length(); index++) {
      char character = target.charAt(index);
      if (character <= 0x20 || character == 0x7f) {
        throw new IllegalArgumentException("the " + field + " contains a control character or space at index " + index
            + " (\\u" + String.format("%04X", (int) character) + "); use percent-encoding in a request target");
      }
    }
  }
}
