package com.barezen.security;

import java.util.Objects;

/**
 * Something a client offered as proof of identity, in the form it arrived.
 *
 * <p>A {@code Credential} is not a password, not a token and not a session cookie. It is whatever the client sent that
 * an {@link Authentication} component decided to trust as an identity claim — a bearer token, a password, an API key —
 * carried here as an opaque value with a scheme. The scheme is the authentication scheme the client used (e.g.
 * {@code Bearer}, {@code Basic}), so an authentication component can reject a credential it does not understand without
 * guessing.
 *
 * <p>The value is held as a {@code String} rather than a {@code byte[]} because the transport that produced it — an
 * HTTP header — is text. A credential that needs to carry binary material is Base64-encoded by its sender, which is
 * HTTP's own rule, and the security layer does not double-decode it.
 *
 * <p>The value is never logged, never included in {@code toString} and never returned from an accessor without the
 * caller naming it. This is the same rule {@code Headers} applies to header values: a credential is a secret, and a
 * secret that appears in a log line, an exception message or a debugger view is a leak that cannot be recalled.
 *
 * @param scheme the authentication scheme, never null and never blank
 * @param value the credential value, never null; may be empty when the scheme itself is the credential (e.g. a session
 *     cookie scheme where the cookie name is the scheme and the value is the cookie value, still a string)
 */
public record Credential(String scheme, String value) {

  /**
   * A credential with a scheme that is not blank.
   *
   * @throws NullPointerException if {@code scheme} or {@code value} is null
   * @throws IllegalArgumentException if {@code scheme} is blank — a credential with no scheme is uninterpretable, and
   *     guessing the scheme from the value is how tokens get accepted as passwords
   */
  public Credential {
    Objects.requireNonNull(scheme, "the credential scheme; without one the value cannot be interpreted");
    Objects.requireNonNull(value, "the credential value; a credential with no value is not a credential");
    if (scheme.isBlank()) {
      throw new IllegalArgumentException(
          "a credential scheme must not be blank; a value without a scheme is uninterpretable");
    }
  }

  /**
   * The value, named explicitly so that a log line cannot accidentally call it.
   *
   * @return the credential value, never null
   */
  @Override
  public String toString() {
    return "Credential[" + scheme + "]";
  }
}
