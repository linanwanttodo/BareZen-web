package com.barezen.security;

import java.util.Objects;

/**
 * What a security decision needs to know about the current request: the authentication result, and nothing else.
 *
 * <p>A {@code SecurityContext} is not a thread-local, not a session and not a cache. It is a value that lives for
 * exactly one request, created by the authentication middleware and handed to the authorization middleware and the
 * handler. It is immutable because a request's security answer is decided once — a context that could be mutated after
 * authentication would let a handler change the identity of the request it is answering, which is the same violation
 * as a static currentUser.
 *
 * <p>The context is request-scoped rather than thread-scoped because a thread is not a request. A server thread may
 * handle many requests, and a request may not complete on the thread that started it. A {@code ThreadLocal} would leak
 * one request's identity into another, which is why this type carries no static state and offers no static accessor.
 * The only way to reach a context is to be handed it by the middleware that built it.
 *
 * @param authentication the authentication result for this request, never null
 */
public record SecurityContext(AuthenticationResult authentication) {

  /**
   * A context with an authentication result that is not null.
   *
   * @throws NullPointerException if {@code authentication} is null — a context with no authentication result is not a
   *     security context, it is a missing decision
   */
  public SecurityContext {
    Objects.requireNonNull(authentication,
        "the authentication result; a context with no result is not a security context, it is a missing decision");
  }

  /**
   * The verified identity, if the authentication result carries one.
   *
   * @return the principal, or empty when the result is a failure or anonymous
   */
  public java.util.Optional<Principal> principal() {
    return authentication.maybePrincipal();
  }

  /**
   * Whether this request is authenticated.
   *
   * @return true if the authentication result is {@link AuthenticationResult.Authenticated}
   */
  public boolean isAuthenticated() {
    return authentication.isAuthenticated();
  }
}
