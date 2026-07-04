package com.barezen.examples.hello;

import com.barezen.web.Handler;
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

import com.barezen.http.jdk.JdkHttpServerAdapter;

/**
 * The smallest BareZen application: one route, one plain-text answer.
 *
 * <p>This is the "hello world" of the framework — it shows the three pieces every BareZen app is built from:
 * a {@link Handler} that turns a request into a {@link Response}, a {@link Router} that binds a handler to a
 * method and path, and the JDK adapter that actually listens on a socket. Nothing else is required to answer a
 * request, which is the point: the framework adds capabilities (security, data, plugins) only when you reach for
 * them, and never as scaffolding you have to clear out.
 *
 * <p>{@link #router()} builds the routing table; {@link #main(String[])} boots a real server on an ephemeral port,
 * makes one request against it over the network, prints the answer, and shuts down — so running this class is a
 * complete, self-verifying demonstration. The matching test drives the same {@code router()} through the TestKit.
 */
public final class HelloWorld {

  private HelloWorld() {
  }

  /** A handler that ignores its input and always answers with the same greeting. */
  static Handler greeting() {
    return (request, parameters) ->
        new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8"),
            "hello, world".getBytes(StandardCharsets.UTF_8));
  }

  /** The routing table: GET /hello is served by {@link #greeting()}. */
  public static Router router() {
    return Router.builder()
        .route(HttpMethod.GET, "/hello", greeting())
        .build();
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    JdkHttpServerAdapter server = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router());
    server.start();
    try {
      int port = server.port().orElseThrow(() -> new IllegalStateException("server did not bind a port"));
      HttpResponse<String> response = HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      System.out.println("GET /hello -> " + response.statusCode() + " " + response.body());
    } finally {
      server.stop();
    }
  }
}
