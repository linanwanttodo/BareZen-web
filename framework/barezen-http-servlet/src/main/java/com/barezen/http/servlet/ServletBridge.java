package com.barezen.http.servlet;

import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

/**
 * The translation between what a servlet container hands over and what the contract names, in both directions:
 * {@link #toRequest} reads an {@link HttpServletRequest} into a {@link Request}, {@link #send} writes a
 * {@link Response} onto an {@link HttpServletResponse}.
 *
 * <p>Package private on purpose, for ADR-0022 §9's reason: the boundary's <em>shape</em> is already public in
 * {@link Request}'s and {@link Response}'s constructors, and a second public way to build either one would offer a path
 * around the contract's own validation.
 *
 * <p><b>The same five rules the JDK adapter follows, each re-derived against a different container.</b> ADR-0022 settled
 * what a request is for {@code com.sun.net.httpserver}; this class reaches the same answers by different calls, because
 * the two containers differ in exactly the places that matter:
 *
 * <ul>
 *   <li><b>the path is the raw request URI, never a decoded or mapped path.</b> {@link HttpServletRequest#getPathInfo()}
 *       and {@code getServletPath()} are <em>decoded</em> by the container and are shaped by the servlet mapping, so an
 *       encoded slash would arrive here already changed into a segment separator. {@link
 *       HttpServletRequest#getRequestURI()} is the undecoded target the client sent, which is what ADR-0019 §4/§5's
 *       raw-segment routing needs;
 *   <li><b>the context path is removed, and nothing else is.</b> A war deployed at {@code /app} routes on
 *       {@code /users}, not on {@code /app/users}: the context is the container's namespace, not part of the resource
 *       the application named. No dot-segment resolution, no slash normalisation, no lower-casing, no decoding — the
 *       list ADR-0022 §6 refuses, refused here for the same reason;
 *   <li><b>the query is raw and {@code null} stays {@code null}.</b> {@link HttpServletRequest#getQueryString()} returns
 *       {@code null} for {@code /x} and the empty string for {@code /x?}, which is the absent-versus-empty distinction
 *       {@link Request#query()} keeps as an {@code Optional} (ADR-0022 §4). Parsing it would be a Query API nobody has
 *       asked for;
 *   <li><b>every header value is copied as its own entry.</b> {@code getHeader(name)} returns the first value only and
 *       folds {@code Set-Cookie} into a lie; the two-enumeration walk below keeps one entry per value, the way
 *       {@code Headers} is defined (ADR-0022 §5);
 *   <li><b>the body is read fully and bounded, before anything is written.</b> Same refusal on the declared
 *       {@code Content-Length} claim, same counted refusal for an unknown length, same 413 (ADR-0031). Nothing is read
 *       through {@code getParameter*}: that would let the container consume and decode the form body, which is a policy
 *       the contract does not have.
 * </ul>
 *
 * <p><b>Framing, which is where the two containers differ most.</b> {@code Content-Length} and
 * {@code Transfer-Encoding} are the container's fields to write, and {@link Response} refuses both names so no
 * application can state a second, contradictory one (ADR-0027 §5). What this class does is hand the container the number
 * it would otherwise guess: measured on the first socket run through Tomcat, a body written without a stated length left
 * as {@code Transfer-Encoding: chunked}, where the JDK adapter emits a length. Both containers then frame the same
 * response the same way, which is the requirement ADR-0018 §12 states and ADR-0029 asserts as a matrix.
 *
 * <p>A HEAD request's entity is suppressed by the container itself, from the method token it parsed, and the length
 * stated above is what survives the suppression — so unlike the JDK server, where {@code JdkExchangeBridge} had to
 * derive the length for a body it decided not to write while {@code ExchangeImpl.isHeadRequest()} decided the suppression
 * separately (the disagreement ADR-0028 exists to remove), here one component decides both from the same bytes. The
 * exact-token rule still holds, and it is measured rather than assumed: a {@code head} token is an ordinary method to
 * Tomcat's parser and gets the body its representation has.
 */
final class ServletBridge {

  /** The status-line texts of the four answers this integration produces without an application. */
  private static final String BAD_REQUEST = "Bad Request";
  private static final String NOT_FOUND = "Not Found";
  private static final String METHOD_NOT_ALLOWED = "Method Not Allowed";
  private static final String INTERNAL_SERVER_ERROR = "Internal Server Error";
  private static final String CONTENT_TOO_LARGE = "Content Too Large";

  /**
   * The ceiling on one request body, and the value an unconfigured servlet runs on: one mebibyte, the same bound
   * ADR-0031 chose for the JDK adapter so that "which server am I on" never changes how much content a client may send.
   */
  static final long DEFAULT_MAX_REQUEST_BODY_BYTES = 1024L * 1024L;

  private ServletBridge() {}

  /**
   * More content than the limit, claimed or arrived — the client's doing, not a server fault, and not an
   * {@code IOException}. Unchecked for ADR-0031 §Decision 3's reason: the request path's {@code catch (IOException)}
   * must not fold it into a 400.
   */
  static final class Exceeded extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private Exceeded(long limit, String framing) {
      super("the request content exceeds the limit of " + limit + " bytes (" + framing + ")", null, false, false);
    }
  }

  /**
   * Read one request into the contract's value type.
   *
   * @param request the container's request, never null
   * @param maxRequestBodyBytes the bound to enforce, as resolved once at assembly time by the servlet
   * @return the request the router sees
   * @throws IOException if the body stopped arriving — the client's failure, answered 400 by the caller
   * @throws Exceeded if the content is over the limit, before or during the read
   * @throws IllegalArgumentException if the target is one {@link Request} refuses, answered 400 by the caller
   */
  static Request toRequest(HttpServletRequest request, long maxRequestBodyBytes) throws IOException {
    HttpMethod method = HttpMethod.of(request.getMethod());
    String path = pathWithinContext(request);
    Headers headers = toHeaders(request);
    long declared = request.getContentLengthLong();
    if (declared > maxRequestBodyBytes) {
      // Judged on the claim, before a byte is read or allocated (ADR-0031 §Decision 4).
      throw new Exceeded(maxRequestBodyBytes, "declared Content-Length " + declared);
    }
    byte[] body = readBounded(request, maxRequestBodyBytes);
    return new Request(method, path, request.getQueryString(), headers, body);
  }

  /**
   * The target as the application named it: the raw request URI with the deployment's context path removed.
   *
   * <p>A context path that is not a prefix of the URI is a container that did not follow §12.2 of the spec; the URI is
   * then used whole rather than sliced at a guess, because cutting an arbitrary prefix off a path is how a routing
   * mistake becomes a security one.
   */
  private static String pathWithinContext(HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri == null || uri.isEmpty()) {
      throw new IllegalArgumentException("the request target carries no path; a container that hands over \""
          + uri + "\" has no resource for the router to name");
    }
    String context = request.getContextPath();
    if (context == null || context.isEmpty()) {
      return uri;
    }
    if (!uri.startsWith(context)) {
      return uri;
    }
    String path = uri.substring(context.length());
    // A request for the context root itself arrives as an empty remainder; the contract's paths all begin with "/".
    return path.isEmpty() ? "/" : path;
  }

  private static Headers toHeaders(HttpServletRequest request) {
    Headers headers = Headers.empty();
    Enumeration<String> names = request.getHeaderNames();
    while (names != null && names.hasMoreElements()) {
      String name = names.nextElement();
      Enumeration<String> values = request.getHeaders(name);
      while (values != null && values.hasMoreElements()) {
        headers = headers.with(name, values.nextElement());
      }
    }
    return headers;
  }

  /**
   * The whole body within the limit, or the counting refusal.
   *
   * <p>Grows into what it read rather than pre-committing the limit, so a 1 KiB body costs 1 KiB and not 1 MiB — the
   * same rule {@code BodyLimit} follows in the JDK adapter (ADR-0031 §Decision). Unlike that module there is no
   * leftover-drain timing to work around: the container owns the connection and reads what the client sends, so no
   * close here, and no stream is handed to the application either ({@link Request} holds bytes).
   */
  private static byte[] readBounded(HttpServletRequest request, long maxRequestBodyBytes) throws IOException {
    InputStream body = request.getInputStream();
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    long counted = 0;
    int read;
    while ((read = body.read(chunk)) != -1) {
      counted += read;
      if (counted > maxRequestBodyBytes) {
        throw new Exceeded(maxRequestBodyBytes, "counted past " + counted + " bytes");
      }
      buffer.write(chunk, 0, read);
    }
    return buffer.toByteArray();
  }

  /**
   * Write a response, exactly once, and state the length of the bytes handed to the container.
   *
   * <p>{@link HttpServletResponse} writes {@code Transfer-Encoding: chunked} when nobody told it how much is coming —
   * measured on this module's first socket run, where a five-byte body left as {@code 5\r\nhello\r\n0\r\n\r\n} with no
   * length anywhere. The JDK adapter derives a length from the bytes it is handed (ADR-0027 §6), so this module states
   * one too: switching server must not change what a client reads, and a response whose framing depends on which
   * container was chosen is exactly the difference ADR-0018 §12 exists to prevent.
   *
   * <p>Headers are added rather than set, because {@code setHeader} would keep only the last value of a repeated field
   * and {@link Headers} is defined as a list per name — measured as two {@code Set-Cookie} lines surviving as two lines.
   * The body is written whenever the application produced one: for a HEAD request the container discards the entity and
   * keeps the length stated here, which is ADR-0027 §6's answer produced by the component that also decided to suppress,
   * so the two cannot disagree the way they did on the JDK server (ADR-0028).
   *
   * @param response the container's response, never null
   * @param application the response the handler returned, never null
   */
  static void send(HttpServletResponse response, Response application) throws IOException {
    response.setStatus(application.status());
    for (String name : application.headers().names()) {
      for (String value : application.headers().values(name)) {
        response.addHeader(name, value);
      }
    }
    byte[] body = application.body();
    if (body.length == 0) {
      // Nothing to hand over, so nothing to frame: 204 and 304 arrive here as often as an empty 200 does, and a length
      // of any kind on them is a statement about a body this response does not have (ADR-0029).
      return;
    }
    response.setContentLengthLong(body.length);
    OutputStream out = response.getOutputStream();
    out.write(body);
    out.flush();
  }

  /**
   * A 400, 404, 413 or 500 as this integration answers it: a status, a plain-text body naming nothing but the status,
   * and no application data (ADR-0023 §2, §4, §5; ADR-0031 §Decision 3).
   *
   * <p>Built the same way every time because the answer must not depend on which of the four it is: a 404 that echoed
   * the target would teach a probe which paths the server distinguishes, and a 500 that carried a message would carry a
   * stack trace's first line.
   */
  private static Response statusOnly(int status, String text) {
    return new Response(status, Headers.of("Content-Type", "text/plain; charset=utf-8"),
        text.getBytes(StandardCharsets.UTF_8));
  }

  /** @return the answer to "nothing lives at this path", which names no path and no route */
  static Response notFound() {
    return statusOnly(404, NOT_FOUND);
  }

  /**
   * @param allow the field value, already ordered by the caller — {@code Allow} is the one answer whose body and fields
   *     have to be assembled together, and whose order is routing knowledge (ADR-0023 §3)
   * @return the answer to "something lives here, but not for this method"
   */
  static Response methodNotAllowed(String allow) {
    Headers headers = Headers.of("Content-Type", "text/plain; charset=utf-8").with("Allow", allow);
    return new Response(405, headers, METHOD_NOT_ALLOWED.getBytes(StandardCharsets.UTF_8));
  }

  /** @return the answer to a target the contract refuses, or a body that never arrived (ADR-0023 §5, §6) */
  static Response badRequest() {
    return statusOnly(400, BAD_REQUEST);
  }

  /** @return the answer to a handler that threw — the status text and nothing else, per ADR-0023 §4 */
  static Response internalServerError() {
    return statusOnly(500, INTERNAL_SERVER_ERROR);
  }

  /** @return the answer to content over the limit; the container's status line keeps its own reason phrase */
  static Response contentTooLarge() {
    return statusOnly(413, CONTENT_TOO_LARGE);
  }
}
