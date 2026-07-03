package com.barezen.security;

import java.util.Map;

import com.barezen.web.Handler;
import com.barezen.web.Request;
import com.barezen.web.Response;

/**
 * A handler that answers a request with the security context of that request.
 *
 * <p>The web contract's {@link Handler#handle(Request, Map)} has no slot for a security context, and it must never
 * grow one: a context parameter on the contract would make every handler, router and adapter in the framework carry
 * a security concern they do not have. This interface is how the security module delivers the context instead. A
 * handler that needs to know who is asking implements {@code SecuredHandler} next to {@link Handler}; the security
 * middleware detects it and calls {@link #handle(Request, Map, SecurityContext)} with the context it just
 * established, and a handler that does not implement it simply never sees the context, exactly as before.
 *
 * <p>The context is request-scoped: it is created by the middleware for one request, passed to this method for that
 * request and discarded when the method returns. It is never stored in a thread-local, never cached and never shared
 * between requests. An implementation that stores it in a field would leak one request's identity into the next,
 * which is precisely the failure this interface exists to make impossible by construction.
 *
 * <p>Why an interface and not an attribute on {@link Request}: a request is a record of what the client sent, and
 * the security context is what the server decided about it; putting the decision on the record would make an
 * immutable fact mutable in meaning. Why not a map entry on {@code parameters}: parameters are what the route
 * captured from the path, and smuggling identity through them would make a route parameter a lie.
 */
@FunctionalInterface
public interface SecuredHandler {

  /**
   * Answer one request with the security context the middleware established for it.
   *
   * @param request the request to answer, never null
   * @param parameters the path parameters the matched pattern captured, exactly as {@link Handler#handle} receives them
   * @param context the security context of this request, never null; by the time this method is called the request is
   *     authenticated and authorized, so {@link SecurityContext#principal()} is present
   * @return the response to send, never null
   */
  Response handle(Request request, Map<String, String> parameters, SecurityContext context);
}
