package com.barezen.examples.requestresponse;

import com.barezen.http.jdk.JdkHttpServerAdapter;
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
import java.util.Optional;

/**
 * Reading a request and writing a response: body, headers, query string, and status codes.
 *
 * <p>A {@link Request} is a value — method, path, an optional query, headers, and a body that is copied on every
 * read so a handler cannot corrupt what the server still holds. A {@link Response} is built the same way: a status,
 * headers, and a byte body. This example ties the two together with an echo endpoint that reports what came in, so
 * the shape of the request/response contract is visible in one handler.
 *
 * <p>Note what the framework does <em>not</em> hand you: there is no parsed-body object and no header abstraction
 * beyond {@link Headers}. Reading a body is {@code new String(request.body(), UTF_8)} and reading a header is
 * {@code request.headers().first("X-Token")} returning an {@link Optional}. That thinness is the design — the web
 * contract stays small enough to read in one sitting, and the mapping layer is yours to add when you need it.
 */
public final class RequestResponseExample {

  private RequestResponseExample() {
  }

  static Router router() {
    return Router.builder()
        // Echo: the request body comes back verbatim, so a POST's payload is visible in the response.
        .route(HttpMethod.POST, "/echo", (request, parameters) ->
            new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8"), request.body()))
        // Report the request: method, path, a header the caller set, and the query string if present.
        .route(HttpMethod.GET, "/describe", (request, parameters) ->
            text(describe(request)))
        // A handler chooses its own status; the framework only refuses framing it cannot honour.
        .route(HttpMethod.DELETE, "/gone", (request, parameters) ->
            new Response(204, Headers.empty(), null))
        .build();
  }

  private static String describe(Request request) {
    Optional<String> name = request.headers().first("X-Name");
    Optional<String> query = request.query();
    return request.method().text() + " " + request.path()
        + " name=" + name.orElse("<none>")
        + " query=" + query.orElse("<none>");
  }

  private static Response text(String body) {
    return new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8"),
        body.getBytes(StandardCharsets.UTF_8));
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    JdkHttpServerAdapter server = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router());
    server.start();
    try {
      int port = server.port().orElseThrow();
      HttpClient client = HttpClient.newHttpClient();
      HttpResponse<String> echo = client.send(HttpRequest.newBuilder(uri(port, "/echo"))
          .POST(HttpRequest.BodyPublishers.ofString("ping")).build(), HttpResponse.BodyHandlers.ofString());
      System.out.println("POST /echo 'ping'      -> " + echo.statusCode() + " body=" + echo.body());

      HttpResponse<String> described = client.send(HttpRequest.newBuilder(uri(port, "/describe?a=1"))
          .header("X-Name", "zen").GET().build(), HttpResponse.BodyHandlers.ofString());
      System.out.println("GET /describe?a=1      -> " + described.statusCode() + " body=" + described.body());

      HttpResponse<String> gone = client.send(HttpRequest.newBuilder(uri(port, "/gone")).DELETE().build(),
          HttpResponse.BodyHandlers.ofString());
      System.out.println("DELETE /gone           -> " + gone.statusCode() + " (no body, no content-length)");
    } finally {
      server.stop();
    }
  }

  private static URI uri(int port, String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }
}
