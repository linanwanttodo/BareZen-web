package com.barezen.security;

import com.barezen.web.Handler;
import com.barezen.web.Headers;
import com.barezen.web.Response;
import java.util.Objects;

/** Adds browser-safety response fields without replacing an application's own policy. */
public final class SecurityHeaders {

  private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
  private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
  private static final String REFERRER_POLICY = "Referrer-Policy";

  private SecurityHeaders() {}

  /** Wrap a handler with the supplied CSP, {@code nosniff}, and {@code no-referrer}. */
  public static Handler of(Handler delegate, String contentSecurityPolicy) {
    Objects.requireNonNull(delegate, "the handler to decorate; without one there is no response to stamp");
    Objects.requireNonNull(contentSecurityPolicy, "the Content-Security-Policy; the application owns this policy");
    if (contentSecurityPolicy.isBlank()) {
      throw new IllegalArgumentException("the Content-Security-Policy must not be blank");
    }
    return (request, parameters) -> stamp(delegate.handle(request, parameters), contentSecurityPolicy);
  }

  private static Response stamp(Response answer, String contentSecurityPolicy) {
    Headers headers = answer.headers();
    if (headers.first(CONTENT_SECURITY_POLICY).isEmpty()) {
      headers = headers.with(CONTENT_SECURITY_POLICY, contentSecurityPolicy);
    }
    if (headers.first(CONTENT_TYPE_OPTIONS).isEmpty()) {
      headers = headers.with(CONTENT_TYPE_OPTIONS, "nosniff");
    }
    if (headers.first(REFERRER_POLICY).isEmpty()) {
      headers = headers.with(REFERRER_POLICY, "no-referrer");
    }
    return headers == answer.headers() ? answer : new Response(answer.status(), headers, answer.body());
  }
}
