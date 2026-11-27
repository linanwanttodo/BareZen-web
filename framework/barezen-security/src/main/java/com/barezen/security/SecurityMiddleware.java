package com.barezen.security;

import com.barezen.web.Handler;
import com.barezen.web.Headers;
import com.barezen.web.Request;
import com.barezen.web.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The security middleware: authenticate, then authorize, then the handler.
 *
 * <p>This is a decorator, not a filter chain, not a servlet filter and not an interceptor. It wraps a {@link Handler}
 * and produces a {@link Handler}, which is the only extension surface the web contract offers. The order is fixed:
 * authentication first, because authorization without an identity is meaningless; authorization second, because a
 * handler must never see a request it should not answer; the handler last, because the handler is the only component
 * that knows how to answer the request.
 *
 * <p>The middleware is not part of the adapter. The adapter knows how to turn an HTTP request into a {@link Request}
 * and a {@link Response} into bytes; it knows nothing about security policy. This is the same rule the adapter
 * applies to routing: the adapter dispatches, the router decides which handler, and the middleware decides whether
 * the handler runs.
 *
 * <p>The security context is request-scoped: it is created here for one request, delivered to the handler when the
 * handler is a {@link SecuredHandler}, and discarded when the request is answered. It is never stored in a
 * thread-local, never cached and never shared between requests. Two requests on the same keep-alive connection each
 * authenticate and authorize independently; a keep-alive connection is a transport, not a session.
 *
 * <p>The failure semantics, stated so an application does not have to guess:
 *
 * <ul>
 *   <li>no credential, an anonymous result or a failed authentication answers {@code 401 Unauthorized}: the request
 *       has no identity, so the question "is it allowed?" cannot be asked;
 *   <li>a malformed {@code Authorization} field that cannot form a credential, or repeated {@code Authorization}
 *       fields, answers {@code 401 Unauthorized}: it is a client input error, not a broken authentication component;
 *   <li>an authenticated identity that the authorization denies answers {@code 403 Forbidden}: the identity is known,
 *       and the answer is no;
 *   <li>an authentication or authorization component that throws answers {@code 500 Internal Server Error}: a broken
 *       security component is a server problem, not a client one, and the detail stays in the server's logs;
 *   <li>in every refusal the delegate handler is never called, so a handler that runs is a handler that was allowed
 *       to run.
 * </ul>
 *
 * <p>The middleware is immutable and thread-safe: it holds no mutable state, and the authentication and
 * authorization it was constructed with are the only collaborators it needs.
 */
public final class SecurityMiddleware implements Handler {

  private final Authentication authentication;
  private final Authorization authorization;
  private final Permission required;
  private final Handler delegate;

  private SecurityMiddleware(
      Authentication authentication, Authorization authorization, Permission required, Handler delegate) {
    this.authentication = authentication;
    this.authorization = authorization;
    this.required = required;
    this.delegate = delegate;
  }

  /**
   * Wrap a handler that requires one named permission.
   *
   * <p>This is the full surface: an identity is established, then the policy is asked whether that identity holds
   * {@code required}, then and only then the delegate runs. A route that means "only grace may read" passes its own
   * {@link Permission} here and an {@link Authorization} that grants it; the middleware asks exactly that question.
   *
   * @param authentication the authentication to use, never null
   * @param authorization the authorization to use, never null
   * @param required the permission the request must be granted, never null
   * @param delegate the handler to wrap, never null
   * @return the wrapped handler
   */
  public static Handler wrap(
      Authentication authentication, Authorization authorization, Permission required, Handler delegate) {
    Objects.requireNonNull(authentication, "the authentication; without one the middleware cannot decide who is asking");
    Objects.requireNonNull(authorization, "the authorization; without one the middleware cannot decide what is allowed");
    Objects.requireNonNull(required, "the required permission; without one the middleware has nothing to ask about");
    Objects.requireNonNull(delegate, "the handler to wrap; without one there is nothing to protect");
    return new SecurityMiddleware(authentication, authorization, required, delegate);
  }

  /**
   * Wrap a handler with authentication and authorization, requiring {@link Permission#ANY}.
   *
   * @param authentication the authentication to use, never null
   * @param authorization the authorization to use, never null
   * @param delegate the handler to wrap, never null
   * @return the wrapped handler
   */
  public static Handler wrap(Authentication authentication, Authorization authorization, Handler delegate) {
    return wrap(authentication, authorization, Permission.ANY, delegate);
  }

  /**
   * Wrap a handler with authentication only: every authenticated request is allowed, every anonymous or failed
   * request is refused.
   *
   * @param authentication the authentication to use, never null
   * @param delegate the handler to wrap, never null
   * @return the wrapped handler
   */
  public static Handler wrap(Authentication authentication, Handler delegate) {
    return wrap(authentication, Authorization.allowAll(), Permission.ANY, delegate);
  }

  @Override
  public Response handle(Request request, Map<String, String> parameters) {
    Credential credential;
    try {
      credential = credentialFrom(request);
    } catch (IllegalArgumentException malformedCredential) {
      return status(401, "Unauthorized");
    }
    AuthenticationResult result;
    try {
      result = authentication.authenticate(credential);
    } catch (RuntimeException e) {
      return status(500, "Internal Server Error");
    }
    if (!result.isAuthenticated()) {
      return status(401, "Unauthorized");
    }
    SecurityContext context = new SecurityContext(result);
    try {
      if (authorization.authorize(result.maybePrincipal().orElseThrow(), required) != Authorization.Decision.ALLOW) {
        return status(403, "Forbidden");
      }
    } catch (RuntimeException e) {
      return status(500, "Internal Server Error");
    }
    if (delegate instanceof SecuredHandler secured) {
      return secured.handle(request, parameters, context);
    }
    return delegate.handle(request, parameters);
  }

  /**
   * Extract the credential from the request's {@code Authorization} header.
   *
   * @param request the request to extract from
   * @return the credential, or null when the header is absent
   * @throws IllegalArgumentException when fields are repeated or the sole value cannot form a credential
   */
  private static Credential credentialFrom(Request request) {
    List<String> values = request.headers().values("Authorization");
    if (values.isEmpty()) {
      return null;
    }
    if (values.size() != 1) {
      throw new IllegalArgumentException("a request must carry at most one Authorization field; repeated credentials"
          + " leave intermediaries and the application free to authenticate different identities");
    }
    String value = values.get(0);
    int space = value.indexOf(' ');
    if (space < 1) {
      return new Credential("Bearer", value);
    }
    return new Credential(value.substring(0, space), value.substring(space + 1));
  }

  private static Response status(int code, String message) {
    return new Response(code, Headers.empty(), message.getBytes(StandardCharsets.UTF_8));
  }
}
