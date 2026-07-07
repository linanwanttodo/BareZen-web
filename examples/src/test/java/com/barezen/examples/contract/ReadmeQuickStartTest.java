package com.barezen.examples.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.barezen.http.jdk.JdkHttpServerAdapter;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Response;
import com.barezen.web.Router;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executes the README's Quick start snippet as written, against a real socket, so the first code a reader copies is
 * proven rather than proofread.
 *
 * <p>Everything between the markers is copied character-for-character from the "Quick start" section of README.md. If
 * the example stops compiling, the README is wrong and this test says so — the same discipline
 * {@code ReadmeTestKitExampleTest} and {@code ReadmeDocumentExampleTest} apply to their sections (ADR-0045's "an example
 * that cannot be run is a claim", extended to the guide Phase 3.5 added).
 */
@DisplayName("README Quick start, run as written")
class ReadmeQuickStartTest {

  @Test
  void theQuickStartServesBothRoutesOnAPortItDidNotChoose() throws Exception {
    // ---- BEGIN README Quick start snippet ----
    byte[] OK = "ok".getBytes(StandardCharsets.UTF_8);
    Router router = Router.builder()
        .route(HttpMethod.GET, "/health", (request, params) ->
            new Response(200, Headers.of("Content-Type", "text/plain"), OK))
        .route(HttpMethod.GET, "/users/{id}", (request, params) ->
            new Response(200, Headers.of("Content-Type", "application/json"),
                ("{\"id\":\"" + params.get("id") + "\"}").getBytes(StandardCharsets.UTF_8)))
        .build();

    // Port 0 asks the operating system for a free one; name the port a deployment owns instead.
    JdkHttpServerAdapter server = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router);
    server.start();
    try {
      int port = server.port().getAsInt();
      HttpResponse<String> response = HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/users/7")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      // The README's comment line ("response.statusCode() == 200, response.body() == ...") is where this test asserts:
      // everything above is the snippet as written, and the closing try/finally below is its last two lines.
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).isEqualTo("{\"id\":\"7\"}");
      assertThat(response.headers().firstValue("Content-Type")).contains("application/json");

      HttpResponse<String> health = HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(health.statusCode()).isEqualTo(200);
      assertThat(health.body()).isEqualTo("ok");

      // The captured parameter reached the handler as an argument, and an unknown path is the adapter's own 404.
      HttpResponse<String> missing = HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/nothing")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(missing.statusCode()).isEqualTo(404);
      assertThat(missing.body()).isEqualTo("Not Found");
    } finally {
      server.stop();   // idempotent, never throws, releases only what this adapter created (ADR-0024, ADR-0005)
    }
    // ---- END README Quick start snippet ----
    assertThat(server.isRunning()).isFalse();
    assertThat(server.port()).isEmpty();
  }
}
