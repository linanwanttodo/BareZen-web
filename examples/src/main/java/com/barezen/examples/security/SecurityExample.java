package com.barezen.examples.security;

import com.barezen.http.jdk.JdkHttpServerAdapter;
import com.barezen.security.Authentication;
import com.barezen.security.Authorization;
import com.barezen.security.PasswordHasher;
import com.barezen.security.Permission;
import com.barezen.security.Principal;
import com.barezen.security.SecuredHandler;
import com.barezen.security.SecurityContext;
import com.barezen.security.SecurityMiddleware;
import com.barezen.web.Handler;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.barezen.web.Router;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Security: a public route and a protected one, with the identity arriving at the handler as a parameter.
 *
 * <p>Authentication and authorization are the application's policy, and the framework applies it as a decorator.
 * Here {@link Authentication#bearer} accepts one fixed token for one principal, an {@link Authorization} table grants
 * that principal the {@code report:read} permission, and {@link SecurityMiddleware#wrap} turns a handler into one that
 * answers 401 without a valid token and 403 with a token that lacks the permission.
 *
 * <p>A handler that needs to know who is asking implements {@link SecuredHandler} <em>beside</em> {@link Handler}. The
 * middleware delivers a {@link SecurityContext} to the three-argument method, and the handler reads
 * {@code context.principal()} to greet the caller by name. Identity is a method argument, never a thread-local — so
 * there is no ambient state for a test to forget to clear or a concurrent request to bleed across. The plain
 * {@code handle(request, parameters)} half is a tripwire: while this handler is wrapped, that half is never reached,
 * and calling it directly would be a wiring mistake worth failing loudly on.
 *
 * <p>The example also hashes a password with {@link PasswordHasher} to show what a real credential check looks like;
 * the fixed-token {@code Authentication} stands in for a store so the routing and the gate stay the focus.
 */
public final class SecurityExample {

  private SecurityExample() {
  }

  static final String TOKEN = "s3cret-token";
  static final Principal ADA = new Principal("ada");
  static final Permission REPORT_READ = Permission.of("report:read");
  static final Permission ADMIN = Permission.of("admin");

  static Authentication authentication() {
    return Authentication.bearer("Bearer", TOKEN, ADA);
  }

  // ada may read reports but is not an admin — so the same token gives 200 on /report and 403 on /admin.
  static Authorization authorization() {
    return Authorization.of(Map.of(ADA, Set.of(REPORT_READ)));
  }

  /**
   * A handler that answers with the caller's identity. It implements both {@link Handler} (so it can be registered on
   * a router and passed to the middleware) and {@link SecuredHandler} (so the middleware hands it the context).
   */
  static final class ReportHandler implements Handler, SecuredHandler {

    @Override
    public Response handle(Request request, Map<String, String> parameters) {
      throw new AssertionError("registered under SecurityMiddleware, which delivers the secured half — "
          + "reaching the plain half means the handler was wired up without the middleware");
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters, SecurityContext context) {
      String name = context.principal().map(Principal::name).orElse("anonymous");
      return text(200, "report for " + name);
    }
  }

  static Router router() {
    return Router.builder()
        .route(HttpMethod.GET, "/health", (request, parameters) -> text(200, "ok"))
        .route(HttpMethod.GET, "/report",
            SecurityMiddleware.wrap(authentication(), authorization(), REPORT_READ, new ReportHandler()))
        // The same token, a permission ada lacks: the middleware answers 403 before the handler ever runs.
        .route(HttpMethod.GET, "/admin",
            SecurityMiddleware.wrap(authentication(), authorization(), ADMIN, new ReportHandler()))
        .build();
  }

  private static Response text(int status, String body) {
    return new Response(status, Headers.of("Content-Type", "text/plain; charset=utf-8"),
        body.getBytes(StandardCharsets.UTF_8));
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    PasswordHasher hasher = PasswordHasher.standard();
    String stored = hasher.hash("hunter2");
    System.out.println("verify correct password -> " + hasher.verify("hunter2", stored));
    System.out.println("verify wrong password   -> " + hasher.verify("wrong", stored));

    JdkHttpServerAdapter server = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router());
    server.start();
    try {
      int port = server.port().orElseThrow();
      HttpClient client = HttpClient.newHttpClient();
      System.out.println("GET /health           -> " + status(client, port, "/health", null));
      System.out.println("GET /report (none)    -> " + status(client, port, "/report", null));
      System.out.println("GET /report (bearer)  -> " + status(client, port, "/report", TOKEN));
      System.out.println("GET /admin  (bearer)  -> " + status(client, port, "/admin", TOKEN));
    } finally {
      server.stop();
    }
  }

  private static String status(HttpClient client, int port, String path, String token)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    int code = client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    return String.valueOf(code);
  }
}
