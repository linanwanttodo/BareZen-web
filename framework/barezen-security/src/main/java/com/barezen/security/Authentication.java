package com.barezen.security;

import java.util.Objects;
import java.util.Optional;

/**
 * How a request is authenticated: a credential is offered, verified, and an {@link AuthenticationResult} is produced.
 *
 * <p>An {@code Authentication} is not a user database, not a password checker and not a token parser. It is the
 * component that takes whatever the client offered — a header, a cookie, a query parameter — and decides whether it
 * proves an identity. The verification itself is done by whatever the implementation was constructed with: a password
 * store, a token issuer, an external service. This type owns the decision, not the proof.
 *
 * <p>The result is always one of three states: authenticated, failure or anonymous. There is no fourth state, because
 * an authentication that cannot decide is a bug, not a policy. An implementation that throws must be caught by the
 * middleware that calls it, which translates the exception into a {@code 500} — an authentication failure is a client
 * problem, a broken authentication component is a server problem.
 *
 * <p>Implementations are not expected to be thread-safe towards each other, but they are invoked concurrently and must
 * therefore hold no mutable per-request state in fields. What belongs in a field is the collaborator injected at
 * construction: the password store, the token verifier, the clock.
 */
@FunctionalInterface
public interface Authentication {

  /**
   * Authenticate the credential the client offered.
   *
   * @param credential the credential to verify, or {@code null} when the client offered none. A null credential is
   *     not an error: it is the anonymous state, and the implementation must return {@link
   *     AuthenticationResult#anonymous()} rather than guessing
   * @return the authentication result, never null
   * @throws RuntimeException if the implementation itself failed — a database unreachable, a token parser that threw.
   *     The middleware that calls this method catches the exception and answers {@code 500}, because a broken
   *     authentication component is a server problem, not a client one
   */
  AuthenticationResult authenticate(Credential credential);

  /**
   * An authentication that trusts nothing: every request is anonymous.
   *
   * <p>Used as the default when no authentication is configured, so that a missing security setup is not silently
   * permissive — it is explicitly anonymous, and authorization decides what an anonymous request may do.
   *
   * @return an authentication that always returns anonymous
   */
  static Authentication anonymous() {
    return credential -> AuthenticationResult.anonymous();
  }

  /**
   * An authentication that trusts one fixed principal for a matching credential value.
   *
   * <p>Useful for tests and for the simplest possible bearer-token setup. The scheme is compared case-insensitively,
   * as HTTP authentication schemes require; the credential value remains exact and case-sensitive.
   *
   * @param scheme the scheme to match, never null
   * @param value the value to match, never null
   * @param principal the principal to return on a match, never null
   * @return an authentication that returns the principal for the matching credential, failure for a wrong one,
   *     anonymous for none
   */
  static Authentication bearer(String scheme, String value, Principal principal) {
    Objects.requireNonNull(scheme, "the scheme to match");
    Objects.requireNonNull(value, "the value to match");
    Objects.requireNonNull(principal, "the principal to return");
    return credential -> {
      if (credential == null) {
        return AuthenticationResult.anonymous();
      }
      if (credential.scheme().equalsIgnoreCase(scheme) && credential.value().equals(value)) {
        return AuthenticationResult.authenticated(principal);
      }
      return AuthenticationResult.failure("credential rejected");
    };
  }
}
