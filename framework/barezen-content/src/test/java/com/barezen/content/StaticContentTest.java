package com.barezen.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.web.Handler;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.barezen.web.Router;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A file served as a route: the bytes, the type, the tag, and what a conditional GET answers.
 *
 * <p>Two layers of proof. The unit half drives {@link Handler#handle} directly, because an ETag that changes when the
 * bytes do and a 304 that carries no body are decisions about a value. The socket half runs the same handler through
 * {@code JdkHttpServerAdapter} on {@code 127.0.0.1:0}, because whether a 304 has a {@code Content-Length} is a fact
 * about the wire the adapter writes rather than about the object this module returned — and ADR-0029 D1 is a claim
 * about the wire, which ADR-0051 re-measures now that a conditional-GET consumer exists.
 */
class StaticContentTest {

  private static Request request(HttpMethod method, String path, Map<String, String> headers) {
    Headers built = Headers.empty();
    for (Map.Entry<String, String> header : headers.entrySet()) {
      built = built.with(header.getKey(), header.getValue());
    }
    return new Request(method, path, null, built, null);
  }

  private static Path write(Path directory, String name, String content) throws IOException {
    Path file = directory.resolve(name);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  @Nested
  @DisplayName("a plain GET")
  class Serving {

    @Test
    void theBytesAndTheGuessedTypeAndADerivedTag(@TempDir Path directory) throws Exception {
      Path css = write(directory, "app.css", "body{margin:0}");

      Response response = StaticContent.file(css).handle(request(HttpMethod.GET, "/app.css", Map.of()), Map.of());

      assertThat(response.status()).isEqualTo(200);
      assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("body{margin:0}");
      assertThat(response.headers().first("Content-Type")).contains("text/css; charset=utf-8");
      assertThat(response.headers().first("ETag")).get()
          .satisfies(tag -> assertThat(tag).matches("^\"[0-9a-f]{16}\"$"));
    }

    @Test
    void aStatedTypeOverrulesTheExtension(@TempDir Path directory) throws Exception {
      Path manifest = write(directory, "manifest.json", "{}");

      Response response = StaticContent.file(manifest, "application/manifest+json")
          .handle(request(HttpMethod.GET, "/manifest.json", Map.of()), Map.of());

      assertThat(response.headers().first("Content-Type")).contains("application/manifest+json");
    }

    @Test
    void theTagChangesWhenTheBytesDo(@TempDir Path directory) throws Exception {
      Path file = write(directory, "a.txt", "one");
      Handler handler = StaticContent.file(file);
      String before = handler.handle(request(HttpMethod.GET, "/a", Map.of()), Map.of())
          .headers().first("ETag").orElseThrow();

      Files.writeString(file, "two", StandardCharsets.UTF_8);
      String after = handler.handle(request(HttpMethod.GET, "/a", Map.of()), Map.of())
          .headers().first("ETag").orElseThrow();

      // No clock and no mtime: the tag is a fact about the content, so an edited asset is revalidated immediately and
      // a restart changes nothing.
      assertThat(after).isNotEqualTo(before);
    }

    @Test
    void theSameBytesAlwaysProduceTheSameTag(@TempDir Path directory) throws Exception {
      Path first = write(directory, "one.txt", "same content");
      Path second = write(directory, "two.txt", "same content");

      assertThat(StaticContent.file(first).handle(request(HttpMethod.GET, "/a", Map.of()), Map.of())
          .headers().first("ETag"))
          .isEqualTo(StaticContent.file(second).handle(request(HttpMethod.GET, "/b", Map.of()), Map.of())
              .headers().first("ETag"));
    }

    @Test
    void aFileThatWentAwayAnswersTheContractsPlainNotFound(@TempDir Path directory) throws Exception {
      Path file = write(directory, "gone.css", "body{}");
      Handler handler = StaticContent.file(file);
      Files.delete(file);

      Response response = handler.handle(request(HttpMethod.GET, "/gone.css", Map.of()), Map.of());

      assertThat(response.status()).isEqualTo(404);
      assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("Not Found");
      // ADR-0023 §2: a 404 that names a path teaches a probe what the deployment has.
      assertThat(new String(response.body(), StandardCharsets.UTF_8)).doesNotContain("gone.css");
    }
  }

  @Nested
  @DisplayName("a conditional GET")
  class Revalidation {

    @Test
    void aMatchingTagAnswers304WithNoBody(@TempDir Path directory) throws Exception {
      Path file = write(directory, "app.js", "console.log(1)");
      Handler handler = StaticContent.file(file);
      String etag = handler.handle(request(HttpMethod.GET, "/app.js", Map.of()), Map.of())
          .headers().first("ETag").orElseThrow();

      Response response = handler.handle(
          request(HttpMethod.GET, "/app.js", Map.of("If-None-Match", etag)), Map.of());

      assertThat(response.status()).isEqualTo(304);
      assertThat(response.body()).isEmpty();
      assertThat(response.headers().first("ETag")).contains(etag);
    }

    @Test
    void aWeakValidatorFromTheClientStillMatches(@TempDir Path directory) throws Exception {
      Path file = write(directory, "app.css", "a{}");
      Handler handler = StaticContent.file(file);
      String etag = handler.handle(request(HttpMethod.GET, "/x", Map.of()), Map.of())
          .headers().first("ETag").orElseThrow();

      assertThat(handler.handle(request(HttpMethod.GET, "/x", Map.of("If-None-Match", "W/" + etag)), Map.of())
          .status()).isEqualTo(304);
    }

    @Test
    void aListOfTagsMatchesAnyOfThem(@TempDir Path directory) throws Exception {
      Path file = write(directory, "app.css", "a{}");
      Handler handler = StaticContent.file(file);
      String etag = handler.handle(request(HttpMethod.GET, "/x", Map.of()), Map.of())
          .headers().first("ETag").orElseThrow();

      assertThat(handler.handle(request(HttpMethod.GET, "/x", Map.of("If-None-Match", "\"stale\", " + etag)), Map.of())
          .status()).isEqualTo(304);
      assertThat(handler.handle(request(HttpMethod.GET, "/x", Map.of("If-None-Match", "\"stale\", \"older\"")), Map.of())
          .status()).isEqualTo(200);
    }

    @Test
    void aWildcardMatchesWhateverTheFileContains(@TempDir Path directory) throws Exception {
      Path file = write(directory, "app.css", "a{}");

      assertThat(StaticContent.file(file)
          .handle(request(HttpMethod.GET, "/x", Map.of("If-None-Match", "*")), Map.of()).status()).isEqualTo(304);
    }

    @Test
    void anAbsentOrBlankValidatorIsAPlainGet(@TempDir Path directory) throws Exception {
      Path file = write(directory, "app.css", "a{}");
      Handler handler = StaticContent.file(file);

      assertThat(handler.handle(request(HttpMethod.GET, "/x", Map.of()), Map.of()).status()).isEqualTo(200);
      assertThat(handler.handle(request(HttpMethod.GET, "/x", Map.of("If-None-Match", "  ")), Map.of()).status())
          .isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("types, from a table rather than from the host")
  class Types {

    @Test
    void everyMappedExtensionIsNamed() {
      assertThat(StaticContent.contentTypeOf("index.html")).isEqualTo("text/html; charset=utf-8");
      assertThat(StaticContent.contentTypeOf("site.HTM")).isEqualTo("text/html; charset=utf-8");
      assertThat(StaticContent.contentTypeOf("a.CSS")).isEqualTo("text/css; charset=utf-8");
      assertThat(StaticContent.contentTypeOf("a.js")).isEqualTo("text/javascript; charset=utf-8");
      assertThat(StaticContent.contentTypeOf("a.mjs")).isEqualTo("text/javascript; charset=utf-8");
      assertThat(StaticContent.contentTypeOf("a.json")).isEqualTo("application/json");
      assertThat(StaticContent.contentTypeOf("a.js.map")).isEqualTo("application/json");
      assertThat(StaticContent.contentTypeOf("a.txt")).isEqualTo("text/plain; charset=utf-8");
      assertThat(StaticContent.contentTypeOf("a.md")).isEqualTo("text/plain; charset=utf-8");
      assertThat(StaticContent.contentTypeOf("a.xml")).isEqualTo("application/xml");
      assertThat(StaticContent.contentTypeOf("a.svg")).isEqualTo("image/svg+xml");
      assertThat(StaticContent.contentTypeOf("a.png")).isEqualTo("image/png");
      assertThat(StaticContent.contentTypeOf("a.jpg")).isEqualTo("image/jpeg");
      assertThat(StaticContent.contentTypeOf("a.jpeg")).isEqualTo("image/jpeg");
      assertThat(StaticContent.contentTypeOf("a.gif")).isEqualTo("image/gif");
      assertThat(StaticContent.contentTypeOf("a.webp")).isEqualTo("image/webp");
      assertThat(StaticContent.contentTypeOf("a.ico")).isEqualTo("image/x-icon");
      assertThat(StaticContent.contentTypeOf("a.woff")).isEqualTo("font/woff");
      assertThat(StaticContent.contentTypeOf("a.woff2")).isEqualTo("font/woff2");
      assertThat(StaticContent.contentTypeOf("a.wasm")).isEqualTo("application/wasm");
      assertThat(StaticContent.contentTypeOf("a.pdf")).isEqualTo("application/pdf");
    }

    @Test
    void anythingUnknownIsBinaryRatherThanAGuessAtText() {
      assertThat(StaticContent.contentTypeOf("data.bin")).isEqualTo("application/octet-stream");
      assertThat(StaticContent.contentTypeOf("LICENSE")).isEqualTo("application/octet-stream");
      assertThat(StaticContent.contentTypeOf("trailingdot.")).isEqualTo("application/octet-stream");
      assertThat(StaticContent.contentTypeOf(".hidden")).isEqualTo("application/octet-stream");
    }
  }

  @Nested
  @DisplayName("what is refused, and when")
  class Refusals {

    @Test
    void aDirectoryIsNotAFile(@TempDir Path directory) {
      assertThatThrownBy(() -> StaticContent.file(directory))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("no regular file");
    }

    @Test
    void aPathThatDoesNotExistYetIsAMisassemblyNotADelayedFourOhFour(@TempDir Path directory) {
      assertThatThrownBy(() -> StaticContent.file(directory.resolve("not-here.css")))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankTypeIsRefused(@TempDir Path directory) throws Exception {
      Path file = write(directory, "a.css", "a{}");

      assertThatThrownBy(() -> StaticContent.file(file, "  "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("blank");
    }

    @Test
    void nullArgumentsAreRefused(@TempDir Path directory) throws Exception {
      Path file = write(directory, "a.css", "a{}");

      assertThatThrownBy(() -> StaticContent.file(null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> StaticContent.file(file, null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> StaticContent.contentTypeOf(null)).isInstanceOf(NullPointerException.class);
    }
  }

  /**
   * The wire half: what a 200, a 304 and a HEAD actually carry once the adapter has framed them.
   *
   * <p>Read off the field lines rather than through {@code java.net.http.HttpClient}, because the claim is about
   * whether a {@code Content-Length} line exists at all (ADR-0029 D1), and a client that normalizes framing answers a
   * different question than the one being asked.
   */
  @Nested
  @DisplayName("over a real socket")
  class Wire {

    @Test
    void a200StatesTheLengthA304StatesNothingAndAHeadStatesWithoutSending(@TempDir Path directory) throws Exception {
      Path file = write(directory, "app.css", "body{margin:0}");
      Router router = Router.builder()
          .route(HttpMethod.GET, "/assets/app.css", StaticContent.file(file))
          .route(HttpMethod.HEAD, "/assets/app.css", StaticContent.file(file))
          .build();
      com.barezen.http.jdk.JdkHttpServerAdapter adapter =
          new com.barezen.http.jdk.JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router);
      adapter.start();
      try {
        int port = adapter.port().getAsInt();
        RawHttp.Response fresh = RawHttp.request(port, "GET", "/assets/app.css", java.util.List.of(), null);
        String etag = fresh.firstOf("ETag");
        assertThat(etag).isNotNull();

        assertThat(fresh.status()).isEqualTo(200);
        assertThat(fresh.firstOf("Content-Length")).isEqualTo("14");
        assertThat(fresh.bodyUtf8()).isEqualTo("body{margin:0}");

        RawHttp.Response cached = RawHttp.request(port, "GET", "/assets/app.css",
            java.util.List.of("If-None-Match: " + etag), null);
        // The stored representation's size is knowable here — the file is right there — and the answer still states
        // nothing. That is ADR-0029 D1 re-measured with its named consumer present, and kept: see ADR-0051.
        assertThat(cached.status()).isEqualTo(304);
        assertThat(cached.countOf("Content-Length")).isZero();
        assertThat(cached.body()).isEmpty();
        assertThat(cached.firstOf("ETag")).isEqualTo(etag);

        RawHttp.Response head = RawHttp.request(port, "HEAD", "/assets/app.css", java.util.List.of(), null);
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.firstOf("Content-Length")).isEqualTo("14");
        assertThat(head.body()).isEmpty();
      } finally {
        adapter.stop();
      }
    }

    @Test
    void aMissingFileOverTheSocketIsTheContracts404WithNoPathNamed(@TempDir Path directory) throws Exception {
      Path file = write(directory, "here.css", "a{}");
      Handler handler = StaticContent.file(file);
      Router router = Router.builder().route(HttpMethod.GET, "/here.css", handler).build();
      com.barezen.http.jdk.JdkHttpServerAdapter adapter =
          new com.barezen.http.jdk.JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router);
      adapter.start();
      try {
        Files.delete(file);
        RawHttp.Response response =
            RawHttp.request(adapter.port().getAsInt(), "GET", "/here.css", java.util.List.of(), null);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.bodyUtf8()).isEqualTo("Not Found");
      } finally {
        adapter.stop();
      }
    }
  }
}
