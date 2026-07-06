package com.barezen.http.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Response;
import com.barezen.web.Router;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The bound on one request body, enforced the way ADR-0031 enforced it in the JDK adapter — judged on the client's own
 * {@code Content-Length} claim before a byte is read, and counted when the client declared nothing.
 *
 * <p>The limit is deliberately tiny in these tests (64 bytes) so the boundary is reachable without sending megabytes,
 * and the assertion that matters most is the one about the answer's author: a refusal this module writes is the plain
 * contract body, while a refusal the container writes itself is Tomcat's HTML page. Which of the two a client gets is a
 * difference worth a test, because it is a difference a client can see.
 */
class ServletRequestBodyLimitTest {

  private static final int LIMIT = 64;

  private static Router echoRouter() {
    return Router.builder()
        .route(HttpMethod.GET, "/health", (request, ignored) ->
            new Response(200, Headers.of("Content-Type", "text/plain"), "ok".getBytes(StandardCharsets.UTF_8)))
        .route(HttpMethod.POST, "/echo", (request, ignored) -> new Response(200,
            Headers.of("Content-Type", "text/plain"),
            ("bytes=" + request.body().length).getBytes(StandardCharsets.UTF_8)))
        .build();
  }

  private static ServletServer bounded(long limit) throws Exception {
    return ServletServer.servlet(echoRouter(), Map.of(
        BarezenServlet.MAX_REQUEST_BODY_BYTES_PARAM, Long.toString(limit)));
  }

  @Nested
  @DisplayName("a declared length")
  class Declared {

    @Test
    void aClaimOverTheLimitIsRefusedBeforeTheBodyIsRead() throws Exception {
      try (ServletServer server = bounded(LIMIT)) {
        RawHttp.Response response = RawHttp.request(server.port(), "POST", "/echo", List.of(),
            "x".repeat(LIMIT + 1));

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.bodyUtf8()).isEqualTo("Content Too Large");
        assertThat(response.hasHeader("Content-Length")).isTrue();
      }
    }

    @Test
    void aClaimExactlyAtTheLimitPasses() throws Exception {
      try (ServletServer server = bounded(LIMIT)) {
        RawHttp.Response response = RawHttp.request(server.port(), "POST", "/echo", List.of(), "x".repeat(LIMIT));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.bodyUtf8()).isEqualTo("bytes=" + LIMIT);
      }
    }

    @Test
    void aBodilessRequestIsNeverRefused() throws Exception {
      try (ServletServer server = bounded(LIMIT)) {
        assertThat(RawHttp.request(server.port(), "GET", "/health", List.of(), null).status()).isEqualTo(200);
      }
    }

    @Test
    void aLimitOfZeroRefusesEveryBodyAndKeepsTheHead() throws Exception {
      try (ServletServer server = bounded(0)) {
        assertThat(RawHttp.request(server.port(), "GET", "/health", List.of(), null).status()).isEqualTo(200);
        assertThat(RawHttp.request(server.port(), "POST", "/echo", List.of(), "x").status()).isEqualTo(413);
      }
    }
  }

  @Nested
  @DisplayName("no declared length")
  class Counted {

    @Test
    void aChunkedBodyPastTheLimitIsRefusedAsItIsCounted() throws Exception {
      // Four chunks of 32 bytes under a 64-byte limit: the third crosses it, so the refusal is the counting half of
      // the rule rather than the claim half (ADR-0031 §Decision).
      String body = "20\r\n" + "x".repeat(32) + "\r\n20\r\n" + "x".repeat(32) + "\r\n20\r\n"
          + "x".repeat(32) + "\r\n0\r\n\r\n";
      try (ServletServer server = bounded(LIMIT)) {
        RawHttp.Response response = RawHttp.requestRaw(server.port(), "POST /echo HTTP/1.1\r\nHost: 127.0.0.1:"
            + server.port() + "\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" + body);

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.bodyUtf8()).isEqualTo("Content Too Large");
      }
    }

    @Test
    void aChunkedBodyUnderTheLimitPasses() throws Exception {
      String body = "10\r\n" + "x".repeat(16) + "\r\n0\r\n\r\n";
      try (ServletServer server = bounded(LIMIT)) {
        RawHttp.Response response = RawHttp.requestRaw(server.port(), "POST /echo HTTP/1.1\r\nHost: 127.0.0.1:"
            + server.port() + "\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" + body);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.bodyUtf8()).isEqualTo("bytes=16");
      }
    }
  }

  @Nested
  @DisplayName("who wrote the refusal")
  class Authorship {

    @Test
    void theRefusalIsTheContractsAnswerAndNotTheContainersPage() throws Exception {
      try (ServletServer server = bounded(LIMIT)) {
        RawHttp.Response response = RawHttp.request(server.port(), "POST", "/echo", List.of(),
            "x".repeat(LIMIT + 1));
        String whole = response.statusLine() + String.join("\n", response.fieldLines()) + response.body();

        // Tomcat's own error page names the server and its version; the contract's answer names neither, and an
        // application that switched server would still be sending the same body (ADR-0023 §2, §4).
        assertThat(whole).doesNotContain("Apache Tomcat").doesNotContain("<html");
        assertThat(response.firstOf("Content-Type")).startsWith("text/plain");
      }
    }
  }
}
