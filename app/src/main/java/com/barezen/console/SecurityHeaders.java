package com.barezen.console;

import com.barezen.web.Handler;

/** The console's CSP, applied through the security module's response-header decorator. */
public final class SecurityHeaders {

  static final String PAGE_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
      + "connect-src 'self'; font-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

  private SecurityHeaders() {}

  /** Serve the delegate's answer with the console's policy and the framework's browser-safety fields. */
  public static Handler of(Handler delegate) {
    return com.barezen.security.SecurityHeaders.of(delegate, PAGE_POLICY);
  }
}
