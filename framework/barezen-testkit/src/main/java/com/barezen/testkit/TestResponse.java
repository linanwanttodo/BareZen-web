package com.barezen.testkit;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The answer a {@link TestHttpClient} got back: a status, the response headers, and the body as both bytes and text.
 *
 * <p>A {@code TestResponse} is a snapshot of one HTTP exchange, not a live connection. Once it exists the bytes are
 * already read and the connection is already released, so a test can hold onto it, assert on it twice, or pass it to a
 * helper without worrying about a stream closing underneath it. That is the whole point: integration tests should read
 * like a description of what was expected, not like socket management.
 *
 * <p>Header names are matched case-insensitively, the way HTTP itself matches them, so a test that asks for
 * {@code "content-type"} gets the same answer whether the server sent {@code "Content-Type"} or
 * {@code "CONTENT-TYPE"}. The original casing is preserved in {@link #headers()} for a test that wants to assert on it.
 */
public final class TestResponse {

  private final int status;
  private final Map<String, List<String>> headers;
  private final byte[] body;

  /**
   * Capture one response.
   *
   * @param status the HTTP status code
   * @param headers the response headers, name to values; copied, and grouped so a name differing only in case is one
   *     name — the caller may pass either JDK or framework shapes
   * @param body the response bytes; copied, so a later mutation of the caller's array cannot change what this records
   */
  public TestResponse(int status, Map<String, List<String>> headers, byte[] body) {
    this.status = status;
    Map<String, List<String>> caseInsensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (Map.Entry<String, List<String>> header : headers.entrySet()) {
      caseInsensitive.merge(header.getKey(), List.copyOf(header.getValue()),
          (existing, added) -> {
            List<String> joined = new java.util.ArrayList<>(existing);
            joined.addAll(added);
            return List.copyOf(joined);
          });
    }
    // unmodifiableMap, not Map.copyOf: copyOf would drop the case-insensitive comparator this map depends on.
    this.headers = Collections.unmodifiableMap(caseInsensitive);
    this.body = body.clone();
  }

  /** @return the HTTP status code exactly as the server sent it */
  public int status() {
    return status;
  }

  /**
   * All response headers, keyed by name with case-insensitive lookup and the original casing preserved in the keys.
   *
   * @return an unmodifiable view of the headers
   */
  public Map<String, List<String>> headers() {
    return headers;
  }

  /**
   * The first value of a header, matched without regard to case.
   *
   * @param name the header name
   * @return the first value, or empty when the header is absent
   */
  public Optional<String> header(String name) {
    List<String> values = headers.get(name);
    return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
  }

  /**
   * The body decoded as UTF-8.
   *
   * @return the body text; never null, empty when there was no body
   */
  public String bodyText() {
    return new String(body, StandardCharsets.UTF_8);
  }

  /**
   * The raw body bytes.
   *
   * @return a copy, so the caller cannot corrupt what this response records
   */
  public byte[] bodyBytes() {
    return body.clone();
  }

  /** @return a summary safe to put in a test failure message, without dumping a possibly-large body */
  @Override
  public String toString() {
    return "TestResponse[status=" + status + ", bytes=" + body.length + ", headers=" + headers.size() + "]";
  }
}
