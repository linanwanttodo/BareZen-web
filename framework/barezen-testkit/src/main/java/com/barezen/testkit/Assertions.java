package com.barezen.testkit;

import com.barezen.security.Principal;
import java.util.Optional;

/**
 * Thin assertions over what a BareZen integration test observes: a response, a route-invocation count, a security
 * identity.
 *
 * <p>This is not an assertion library. It wraps exactly the three things that are awkward to assert on directly and
 * leaves everything else to JUnit. There is no fluent matcher DSL, no {@code anyOf}/{@code allOf}, no recursion —
 * those are AssertJ's job, and AssertJ is already on a test's classpath if a project wants it. What lives here is
 * small and specific to BareZen's shapes: status/header/body and a JSON path on a {@link TestResponse}, an equality on
 * an invocation count, a name on a {@link Principal}.
 *
 * <p>Every failure throws an {@link AssertionError} with a message that shows both what was expected and what the
 * response actually was, because the most common way these fail in a real debugging session is a test author guessing
 * at the bytes the server sent when they could have been told.
 *
 * <pre>{@code
 * Assertions.assertThat(server.client().get("/health")).statusIs(200).bodyIs("ok");
 * Assertions.assertThat(server.client().get("/openapi.json")).jsonStringIs("info.title", "BareZen");
 * Assertions.assertThat(handler.count()).isEqualTo(1);
 * Assertions.assertThat(principalFixture.seen()).hasName("alice");
 * }</pre>
 *
 * <p>A JSON path is dot-separated, so it cannot reach an object member whose own name contains a dot (an OpenAPI path
 * template like {@code "/a.b"} would be split at the wrong place). Such a body is reached by {@link
 * ResponseAssert#bodyContains} or by splitting the path where its segments actually are.
 */
public final class Assertions {

  private Assertions() {}

  /**
   * Assert on a response.
   *
   * @param response the response to assert on
   * @return a fluent view over the response
   */
  public static ResponseAssert assertThat(TestResponse response) {
    return new ResponseAssert(response);
  }

  /**
   * Assert on a route-invocation count, typically a handler's {@code count()}.
   *
   * @param count the observed number of invocations
   * @return a fluent view over the count
   */
  public static CountAssert assertThat(int count) {
    return new CountAssert(count);
  }

  /**
   * Assert on the security identity a fixture recorded.
   *
   * @param principal the principal seen, or empty when the request was anonymous
   * @return a fluent view over the identity
   */
  public static IdentityAssert assertThat(Optional<Principal> principal) {
    return new IdentityAssert(principal);
  }

  /** Fluent assertions over one {@link TestResponse}; each method returns {@code this} so they chain. */
  public static final class ResponseAssert {

    private final TestResponse response;

    private ResponseAssert(TestResponse response) {
      this.response = java.util.Objects.requireNonNull(response, "the response to assert on");
    }

    /**
     * Assert the status code.
     *
     * @param expected the status the handler must have returned
     * @return this
     */
    public ResponseAssert statusIs(int expected) {
      if (response.status() != expected) {
        throw new AssertionException(
            "expected status " + expected + " but got " + response.status() + " (" + response + ")");
      }
      return this;
    }

    /**
     * Assert a header is present with an exact value, matched case-insensitively on the name.
     *
     * @param name the header name
     * @param expected the exact value
     * @return this
     */
    public ResponseAssert headerIs(String name, String expected) {
      Optional<String> actual = response.header(name);
      if (actual.isEmpty()) {
        throw new AssertionException("expected header \"" + name + "\" to be " + expected + " but it was absent");
      }
      if (!actual.get().equals(expected)) {
        throw new AssertionException("expected header \"" + name + "\" to be \"" + expected + "\" but it was \""
            + actual.get() + "\"");
      }
      return this;
    }

    /**
     * Assert a header is present, whatever its value.
     *
     * @param name the header name
     * @return this
     */
    public ResponseAssert hasHeader(String name) {
      if (response.header(name).isEmpty()) {
        throw new AssertionException("expected header \"" + name + "\" to be present; headers were "
            + response.headers().keySet());
      }
      return this;
    }

    /**
     * Assert the whole body equals a string, byte-for-byte after UTF-8 decoding.
     *
     * @param expected the exact body text
     * @return this
     */
    public ResponseAssert bodyIs(String expected) {
      String actual = response.bodyText();
      if (!actual.equals(expected)) {
        throw new AssertionException("expected body \"" + expected + "\" but got \"" + actual + "\"");
      }
      return this;
    }

    /**
     * Assert the body contains a substring.
     *
     * @param fragment a piece the body must contain
     * @return this
     */
    public ResponseAssert bodyContains(String fragment) {
      if (!response.bodyText().contains(fragment)) {
        throw new AssertionException("expected body to contain \"" + fragment + "\" but it was \""
            + response.bodyText() + "\"");
      }
      return this;
    }

    /**
     * Assert a JSON path exists in the body.
     *
     * @param path a dot-separated path, e.g. {@code "info.title"}
     * @return this
     */
    public ResponseAssert jsonHas(String path) {
      if (parse().at(path) == null) {
        throw new AssertionException("expected JSON path \"" + path + "\" to exist in " + response.bodyText());
      }
      return this;
    }

    /**
     * Assert the string at a JSON path.
     *
     * @param path a dot-separated path
     * @param expected the string value the path must hold
     * @return this
     */
    public ResponseAssert jsonStringIs(String path, String expected) {
      JsonValue.Value node = requireScalar(path);
      if (node.kind() != JsonValue.Value.Kind.STRING || !node.asText().equals(expected)) {
        throw new AssertionException("expected JSON \"" + path + "\" to be string \"" + expected + "\" but it was "
            + node);
      }
      return this;
    }

    /**
     * Assert the number at a JSON path by its literal text, so no float rounding changes the comparison.
     *
     * @param path a dot-separated path
     * @param expected the number literal, e.g. {@code "42"}
     * @return this
     */
    public ResponseAssert jsonNumberIs(String path, String expected) {
      JsonValue.Value node = requireScalar(path);
      if (node.kind() != JsonValue.Value.Kind.NUMBER || !node.asText().equals(expected)) {
        throw new AssertionException("expected JSON \"" + path + "\" to be number " + expected + " but it was " + node);
      }
      return this;
    }

    /**
     * Assert the boolean at a JSON path.
     *
     * @param path a dot-separated path
     * @param expected true or false
     * @return this
     */
    public ResponseAssert jsonBooleanIs(String path, boolean expected) {
      JsonValue.Value node = requireScalar(path);
      JsonValue.Value.Kind expectedKind = expected ? JsonValue.Value.Kind.TRUE : JsonValue.Value.Kind.FALSE;
      if (node.kind() != expectedKind) {
        throw new AssertionException("expected JSON \"" + path + "\" to be " + expected + " but it was " + node);
      }
      return this;
    }

    /**
     * Assert the JSON array at a path has an exact size.
     *
     * @param path a dot-separated path
     * @param size the number of elements
     * @return this
     */
    public ResponseAssert jsonArraySizeIs(String path, int size) {
      JsonValue.Value node = parse().at(path);
      if (node == null) {
        throw new AssertionException("expected JSON array \"" + path + "\" of size " + size + " but the path was absent");
      }
      if (node.kind() != JsonValue.Value.Kind.ARRAY || node.elements().size() != size) {
        throw new AssertionException("expected JSON array \"" + path + "\" to have " + size + " elements but it was "
            + node + " with " + node.elements().size());
      }
      return this;
    }

    private JsonValue.Value parse() {
      return JsonValue.parse(response.bodyText());
    }

    private JsonValue.Value requireScalar(String path) {
      JsonValue.Value node = parse().at(path);
      if (node == null) {
        throw new AssertionException("expected a value at JSON path \"" + path + "\" but the path was absent in "
            + response.bodyText());
      }
      return node;
    }
  }

  /** Fluent assertion over a route-invocation count. */
  public static final class CountAssert {

    private final int count;

    private CountAssert(int count) {
      this.count = count;
    }

    /**
     * Assert the count equals an expected number.
     *
     * @param expected the number of invocations expected
     * @return this
     */
    public CountAssert isEqualTo(int expected) {
      if (count != expected) {
        throw new AssertionException("expected " + expected + " invocation(s) but there were " + count);
      }
      return this;
    }
  }

  /** Fluent assertion over the security identity a fixture recorded. */
  public static final class IdentityAssert {

    private final Optional<Principal> principal;

    private IdentityAssert(Optional<Principal> principal) {
      this.principal = java.util.Objects.requireNonNull(principal, "the identity to assert on; use Optional.empty()"
          + " for anonymous, never null");
    }

    /**
     * Assert the request was anonymous.
     *
     * @return this
     */
    public IdentityAssert isAnonymous() {
      if (principal.isPresent()) {
        throw new AssertionException("expected an anonymous request but the identity was \""
            + principal.get().name() + "\"");
      }
      return this;
    }

    /**
     * Assert the request carried a principal with an exact name.
     *
     * @param name the expected principal name
     * @return this
     */
    public IdentityAssert hasName(String name) {
      if (principal.isEmpty()) {
        throw new AssertionException("expected principal \"" + name + "\" but the request was anonymous");
      }
      if (!principal.get().name().equals(name)) {
        throw new AssertionException("expected principal \"" + name + "\" but it was \"" + principal.get().name() + "\"");
      }
      return this;
    }
  }
}
