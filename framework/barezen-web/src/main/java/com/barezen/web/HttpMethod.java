package com.barezen.web;

import java.util.Objects;

/**
 * An HTTP request method: a name, with the nine methods the HTTP core defines as named values.
 *
 * <p>A type rather than a {@code String} because the first thing a web component does with a request is compare its
 * method, and a bare {@code String} makes that a trap: {@code "Get".equals(request.method())} is a check that never
 * matches, quietly, and the spelling a client chose is not the developer's decision. Named values put the method names
 * in front of a reader, and one parse rule at the edge means every comparison afterwards happens between values that
 * already agree about case.
 *
 * <p>A class rather than an {@code enum}, for one reason: a closed enum has nowhere to put a method it does not name.
 * Applications do see {@code PROPFIND} from a WebDAV client and {@code PURGE} from a cache manager, and an
 * {@code UNKNOWN} constant that discards the text leaves a handler unable to do anything with such a request except
 * reject it. So this holds a name and provides values for the nine known methods. The one thing that costs compared
 * with an enum is {@code switch}, which a handler needs rarely; when it does, it switches on {@link #text()}.
 *
 * <p>Case rules, stated once and applied by {@link #of(String)}:
 *
 * <ul>
 *   <li>the nine standard names are recognised in any case, so {@code of("get")} is {@link #GET} and reads back
 *       {@code "GET"}. A request is not malformed because of a spelling;
 *   <li>any other name is kept exactly as it arrived and compared exactly, because a method name is a case-sensitive
 *       token. {@code of("purge")} and {@code of("PURGE")} are therefore two different methods, which is what the
 *       specification says and the only rule that does not need a registry of exceptions.
 * </ul>
 *
 * <p>Instances are immutable and safe to share between threads. {@link #of(String)} hands back the shared value for a
 * standard method, so {@code ==} against a constant is as correct as {@link #equals}. Whether a method is one of the
 * nine is answered by comparing against them; there is no {@code isStandard()} predicate, because a type that already
 * names all nine does not need a second way to ask, and a predicate nothing in this phase calls is the kind of member
 * that turns later into "which three of these twelve should I use?".
 */
public final class HttpMethod {

  /** Request for a representation, answered with a body — the method most of an application is. */
  public static final HttpMethod GET = new HttpMethod("GET");

  /** {@link #GET} with no response body, which a handler must honour by sending headers only. */
  public static final HttpMethod HEAD = new HttpMethod("HEAD");

  /** Submit a representation for processing. */
  public static final HttpMethod POST = new HttpMethod("POST");

  /** Replace the target resource. */
  public static final HttpMethod PUT = new HttpMethod("PUT");

  /** Remove the target resource. */
  public static final HttpMethod DELETE = new HttpMethod("DELETE");

  /** Turn the connection into a tunnel; an application server has no meaning for it. */
  public static final HttpMethod CONNECT = new HttpMethod("CONNECT");

  /** Ask what options the target resource supports, which is what a CORS preflight is. */
  public static final HttpMethod OPTIONS = new HttpMethod("OPTIONS");

  /** Echo the request back to the client. A server refuses it by default: it is a cross-site amplification vector. */
  public static final HttpMethod TRACE = new HttpMethod("TRACE");

  /** Apply a partial modification. Added to the specification after the HTTP/1.1 set, so it is named rather than inherited. */
  public static final HttpMethod PATCH = new HttpMethod("PATCH");

  /** The nine, in the order HTTP defines them. Private: a caller that needs the set compares against the values above. */
  private static final HttpMethod[] STANDARD = {GET, HEAD, POST, PUT, DELETE, CONNECT, OPTIONS, TRACE, PATCH};

  /** RFC 9110 separator set plus space and tab: none may appear in a method token. */
  private static final String SEPARATORS = "()<>@,;:\\\"/[]?={} \t";

  private final String text;

  private HttpMethod(String text) {
    this.text = text;
  }

  /**
   * Parse the method token of a request line.
   *
   * @param method the token as it arrived, for example {@code "GET"} or {@code "PURGE"}; the nine standard names are
   *     recognised in any case
   * @return the standard value for one of the nine, or a value holding {@code method} exactly as sent for anything
   *     else
   * @throws NullPointerException if {@code method} is null
   * @throws IllegalArgumentException if {@code method} is blank or is not an HTTP token. A request line without a
   *     method names no method, and defaulting to {@code GET} would answer a request nobody sent; control characters,
   *     whitespace and separators are refused for the same reason a malformed request line is refused
   */
  public static HttpMethod of(String method) {
    Objects.requireNonNull(method,
        "the request method; a request with no method is a malformed request line, not a GET");
    if (method.isBlank()) {
      throw new IllegalArgumentException("the request method must not be blank; got \"" + method + "\"."
          + " An empty method names no method, and guessing one would run the wrong handler");
    }
    for (int index = 0; index < method.length(); index++) {
      char character = method.charAt(index);
      if (character <= 0x20 || character >= 0x7f || SEPARATORS.indexOf(character) >= 0) {
        String shown = character <= 0x20 || character >= 0x7f
            ? "\\u" + String.format("%04X", (int) character) : "'" + character + "'";
        throw new IllegalArgumentException("request method is not an HTTP token; got " + shown
            + " at index " + index + ". A method is one token on the request line, not a place for whitespace,"
            + " controls or separators");
      }
    }
    for (HttpMethod standard : STANDARD) {
      if (standard.text.equalsIgnoreCase(method)) {
        return standard;
      }
    }
    return new HttpMethod(method);
  }

  /**
   * The method name: upper case for one of the nine, exactly as sent for anything else.
   *
   * <p>What an adapter writes back to a request line when it forwards, and what a handler that switches on a method
   * reads. Safe to print: a method is neither request data nor a credential.
   *
   * @return the name, never null or blank
   */
  public String text() {
    return text;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof HttpMethod)) {
      return false;
    }
    // Exact comparison, never case-folded here: of() normalised the case once, at the edge. Folding again would make
    // two values that arrived with different spellings equal, which is the rule HTTP does not have for a method name.
    return text.equals(((HttpMethod) other).text);
  }

  @Override
  public int hashCode() {
    return text.hashCode();
  }

  /**
   * The method name, so a request reads as {@code GET /path} rather than {@code com.barezen.web.HttpMethod@1a2b3c}.
   *
   * @return the same text as {@link #text()}
   */
  @Override
  public String toString() {
    return text;
  }
}
