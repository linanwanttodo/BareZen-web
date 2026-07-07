package com.barezen.console;

import com.barezen.web.Handler;
import com.barezen.web.Response;

/**
 * The response headers a page that keeps a credential in script-readable storage has to carry.
 *
 * <p>The console stores its bearer token in {@code sessionStorage} because a reader who moves between seven pages must
 * still be signed in after the document is replaced — see ADR-0057 for what that buys and what it costs. One of the
 * costs is that a successful cross-site script could read the token, and no amount of server-side typing prevents a
 * script that a page itself allows to run. So every page and asset is served with a policy that admits no inline
 * script, no third-party origin and no framing: the single shared {@code app.js} is the only script this application
 * can load, which is also why the per-page wiring branches on {@code body[data-page]} instead of a {@code <script>}
 * block in each file.
 *
 * <p>It is a handler decorator rather than a framework concern for the same reason security is elsewhere in this
 * project (ADR-0033): wrapping what you register is how an application states its own policy, and
 * {@link com.barezen.content.StaticContent} was right to know nothing about either header.
 */
public final class SecurityHeaders {

  /**
   * Same-origin for everything, no exceptions: {@code 'unsafe-inline'} is absent on purpose, since it would also
   * re-admit the inline handlers that make a CSP trivially bypassable.
   */
  static final String PAGE_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
      + "connect-src 'self'; font-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

  private SecurityHeaders() {
  }

  /** Serve the delegate's answer with the headers added, leaving a stamped answer alone. */
  public static Handler of(Handler delegate) {
    return (request, parameters) -> stamp(delegate.handle(request, parameters));
  }

  private static Response stamp(Response answer) {
    if (answer.headers().first("Content-Security-Policy").isPresent()) {
      return answer;
    }
    return new Response(answer.status(), answer.headers()
        .with("Content-Security-Policy", PAGE_POLICY)
        .with("X-Content-Type-Options", "nosniff")
        .with("Referrer-Policy", "no-referrer"), answer.body());
  }
}
