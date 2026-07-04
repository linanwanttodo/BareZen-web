package com.barezen.examples.routing;

import com.barezen.http.jdk.JdkHttpServerAdapter;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Response;
import com.barezen.web.Router;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Routing and path parameters: several routes, a path variable, and the framework's own 404/405 decision.
 *
 * <p>Where hello-world shows one route, this shows the routing table doing its job. {@code /books/{id}} binds one
 * non-empty segment to a named parameter the handler reads from the {@code parameters} map; the {@link Router} also
 * answers "which methods exist on this path", which is how a framework turns an unknown path into a 404 and a known
 * path with the wrong verb into a 405 — a distinction a hand-rolled matcher usually collapses into a single 404.
 *
 * <p>Handlers here stay pure functions of (request, parameters); the state a real catalog would hold is deliberately
 * left out, because routing is the subject and persistence has its own example.
 */
public final class RoutingExample {

  private RoutingExample() {
  }

  /** GET /books/{id}: reads the path variable and echoes it back as the title of that one book. */
  static Router router() {
    return Router.builder()
        .route(HttpMethod.GET, "/books", (request, parameters) ->
            text(200, "the library has no books yet"))
        .route(HttpMethod.GET, "/books/{id}", (request, parameters) ->
            text(200, "book " + parameters.get("id")))
        .route(HttpMethod.POST, "/books", (request, parameters) ->
            text(201, "created"))
        .build();
  }

  private static Response text(int status, String body) {
    return new Response(status, Headers.of("Content-Type", "text/plain; charset=utf-8"),
        body.getBytes(StandardCharsets.UTF_8));
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    Router router = router();
    // The routing table answers a request in-process before any socket exists — this is the 404-vs-405 decision:
    System.out.println("GET /books/42      -> " + router.match(get("/books/42")).map(m -> m.parameters()).orElse(null));
    System.out.println("methods on /books  -> " + router.methodsFor("/books"));
    System.out.println("methods on /author -> " + router.methodsFor("/author"));

    JdkHttpServerAdapter server = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router);
    server.start();
    try {
      int port = server.port().orElseThrow();
      HttpClient client = HttpClient.newHttpClient();
      System.out.println("GET /books/7       -> " + body(client, port, "/books/7"));
    } finally {
      server.stop();
    }
  }

  private static String body(HttpClient client, int port, String path) throws IOException, InterruptedException {
    return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString()).body();
  }

  private static com.barezen.web.Request get(String path) {
    return new com.barezen.web.Request(HttpMethod.GET, path, null, Headers.empty(), null);
  }
}
