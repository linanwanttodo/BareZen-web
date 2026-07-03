package com.barezen.security;

import java.util.Objects;
import java.util.Optional;

/**
 * What an authentication attempt produced: a verified identity, an explicit refusal, or the absence of a claim.
 *
 * <p>Three states, because HTTP security has three answers: the client authenticated successfully, the client offered a
 * credential that did not verify, and the client offered no credential at all. The third is not the second — an
 * anonymous request is a valid request that will be answered differently, while a bad credential is a request that
 * failed to prove anything, and the difference is the one between {@code 401} and {@code 403}.
 *
 * <p>The result is a sealed hierarchy rather than a {@code null} or a flag, because a flag invites the caller to ask
 * "is there a principal?" and forget to ask "why not?" — which is the difference between sending {@code 401} and
 * silently treating a failure as anonymous. A {@link #failure(String)} carries the reason so the answer can say
 * "bad token" instead of "you seem to be nobody", and the reason is the application's own text, never the client's
 * credential.
 */
public sealed interface AuthenticationResult {

  /**
   * The client proved an identity.
   *
   * @param principal the verified principal, never null
   * @return the authenticated result
   */
  static AuthenticationResult authenticated(Principal principal) {
    return new Authenticated(Objects.requireNonNull(principal,
        "the verified principal; an authenticated result without one is a failure dressed as a success"));
  }

  /**
   * The client offered a credential that did not verify.
   *
   * @param reason why the credential was refused, in words the application chooses; never the credential itself
   * @return the failure result
   */
  static AuthenticationResult failure(String reason) {
    return new Failure(Objects.requireNonNull(reason,
        "the refusal reason; without one the caller cannot tell a bad token from a missing one"));
  }

  /**
   * The client offered no credential, which is the anonymous state.
   *
   * @return the anonymous result
   */
  static AuthenticationResult anonymous() {
    return Anonymous.INSTANCE;
  }

  /**
   * The verified identity, if this result carries one.
   *
   * <p>Named {@code maybePrincipal} rather than {@code principal} because the {@link Authenticated} record has a
   * component of that name, and a record's component accessor is final — the interface could not offer a view of the
   * same fact under the same name.
   *
   * @return the principal, or empty when the result is a failure or anonymous
   */
  default Optional<Principal> maybePrincipal() {
    return Optional.empty();
  }

  /**
   * Whether this result represents a verified identity.
   *
   * @return true if authenticated, false for failure or anonymous
   */
  default boolean isAuthenticated() {
    return false;
  }

  /**
   * The verified identity.
   *
   * @param principal the principal
   */
  record Authenticated(Principal principal) implements AuthenticationResult {

    @Override
    public Optional<Principal> maybePrincipal() {
      return Optional.of(principal);
    }

    @Override
    public boolean isAuthenticated() {
      return true;
    }
  }

  /**
   * A credential that did not verify.
   *
   * @param reason why the credential was refused
   */
  record Failure(String reason) implements AuthenticationResult {}

  /**
   * No credential offered.
   */
  enum Anonymous implements AuthenticationResult {
    INSTANCE
  }
}
