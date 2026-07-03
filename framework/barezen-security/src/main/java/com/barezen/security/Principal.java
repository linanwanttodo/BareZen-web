package com.barezen.security;

import java.util.Objects;

/**
 * Who is making the request, in the sense a security decision can act on.
 *
 * <p>A {@code Principal} is not a user record, not a database row and not a session. It is the identity claim an
 * authentication component accepted, stripped of everything that is not needed for a security decision: a name and
 * nothing else. The name is what a token, a password lookup or an external system said it was, and the component that
 * accepted it — the {@link Authentication} that produced this principal — is responsible for having verified that the
 * claim is true.
 *
 * <p>A principal is immutable and safe to share between requests, but it is not expected to be: the whole point of a
 * request-scoped {@link SecurityContext} is that a new request authenticates again and produces a new principal if it
 * must. Two requests from the same person may produce two {@code Principal} instances that are equal by name; the
 * security layer does not cache, pool or compare them for identity.
 *
 * @param name the identity name, never null and never blank. Blank names are refused because a principal with no name
 *     is indistinguishable from an anonymous request, and an anonymous request is represented by the absence of a
 *     principal, not by a principal with no name
 */
public record Principal(String name) {

  /**
   * A principal with a name that is not blank.
   *
   * @throws NullPointerException if {@code name} is null
   * @throws IllegalArgumentException if {@code name} is blank — an empty string would mean "a name exists but is
   *     nothing", which is the anonymous state wearing a principal's clothes
   */
  public Principal {
    Objects.requireNonNull(name, "the principal name; without one the identity is anonymous, not a principal");
    if (name.isBlank()) {
      throw new IllegalArgumentException(
          "a principal name must not be blank; an anonymous request has no principal at all");
    }
  }
}
