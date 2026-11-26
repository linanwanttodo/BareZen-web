package com.barezen.examples.staticcontent;

import com.barezen.content.StaticContent;
import com.barezen.http.jdk.JdkHttpServerAdapter;
import com.barezen.web.Handler;
import com.barezen.web.HttpMethod;
import com.barezen.web.Router;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Static files on the contract: one route per file, an {@code ETag} from the bytes, and the answers a browser
 * actually depends on.
 *
 * <p>{@code barezen-content} exists because {@link Router} has no wildcard pattern by decision (ADR-0019 §19), so an
 * application that serves files registers one handler per file — the listing below is done at assembly, from a directory
 * the application chose, never from a path a client supplied (ADR-0051). The conditional-GET behaviour is the part worth
 * running rather than reading: a first request gets the bytes and a tag, a second with {@code If-None-Match} gets a 304
 * with no body and no length, a {@code HEAD} states the length without sending the body, and one {@code Range} gets a 206
 * with the selected bytes. The first three are the shape ADR-0029 decided and ADR-0051 re-measured once something could
 * know a file's size in-process.
 */
public final class StaticFileExample {

  private StaticFileExample() {
  }

  /**
   * A route per regular file in {@code directory}, addressed by its name under {@code /}, registered for both
   * {@code GET} and {@code HEAD}.
   *
   * <p>Two verbs because {@link Router} matches the method the client sent: {@code HEAD} is not a {@code GET} the
   * framework folds away (ADR-0028), so a file served only for {@code GET} answers a probe with 405. The same handler
   * serves both, and the adapter decides whether the bytes go on the wire.
   */
  static Router router(Path directory) throws IOException {
    Router.Builder builder = Router.builder();
    try (Stream<Path> files = Files.list(directory)) {
      List<Path> sorted = files.filter(Files::isRegularFile).sorted().toList();
      for (Path file : sorted) {
        String address = "/" + file.getFileName();
        builder.route(HttpMethod.GET, address, StaticContent.file(file));
        builder.route(HttpMethod.HEAD, address, StaticContent.file(file));
      }
    }
    Handler packaged = StaticContent.resource(StaticFileExample.class.getClassLoader(), "static-content/hello.txt");
    builder.route(HttpMethod.GET, "/classpath.txt", packaged);
    builder.route(HttpMethod.HEAD, "/classpath.txt", packaged);
    return builder.build();
  }

  /** A directory holding two files, so the example has something to serve without a repository path. */
  static Path workspace() throws IOException {
    Path directory = Files.createTempDirectory("barezen-example-static");
    directory.toFile().deleteOnExit();
    Files.writeString(directory.resolve("notes.txt"), "static content is served one file per route\n",
        StandardCharsets.UTF_8);
    Files.writeString(directory.resolve("status.json"), "{\"served\":\"by barezen-content\"}\n",
        StandardCharsets.UTF_8);
    return directory;
  }

  public static void main(String[] args) throws IOException {
    Path directory = workspace();
    Router routes = router(directory);
    System.out.println("files in " + directory.getFileName() + " -> " + Files.list(directory).count()
        + ", routes -> " + routes);

    JdkHttpServerAdapter server = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), routes);
    server.start();
    try {
      int port = server.port().orElseThrow();
      HttpClient client = HttpClient.newHttpClient();
      URI address = URI.create("http://127.0.0.1:" + port + "/notes.txt");

      HttpResponse<String> first = client.send(HttpRequest.newBuilder(address).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      String tag = first.headers().firstValue("ETag").orElseThrow();
      System.out.println("GET /notes.txt        -> " + first.statusCode() + ", " + first.body().length()
          + " bytes, ETag " + tag);

      HttpResponse<String> reload = client.send(HttpRequest.newBuilder(address)
          .header("If-None-Match", tag).GET().build(), HttpResponse.BodyHandlers.ofString());
      System.out.println("GET with the same tag -> " + reload.statusCode() + ", " + reload.body().length()
          + " bytes, Content-Length "
          + reload.headers().firstValue("Content-Length").orElse("(absent)"));

      HttpResponse<byte[]> head = client.send(HttpRequest.newBuilder(address).method("HEAD",
          HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
      System.out.println("HEAD /notes.txt       -> " + head.statusCode() + ", states Content-Length "
          + head.headers().firstValue("Content-Length").orElse("(absent)") + ", received "
          + head.body().length + " bytes of body");

      HttpResponse<String> partial = client.send(HttpRequest.newBuilder(address)
          .header("Range", "bytes=0-3").GET().build(), HttpResponse.BodyHandlers.ofString());
      System.out.println("GET bytes 0-3         -> " + partial.statusCode() + ", " + partial.body().length()
          + " bytes, Content-Range " + partial.headers().firstValue("Content-Range").orElse("(absent)"));

      HttpResponse<String> missing = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/../etc/passwd")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      System.out.println("GET outside the routes -> " + missing.statusCode() + ", body \"" + missing.body() + "\"");
    } catch (IOException | InterruptedException failure) {
      throw new IllegalStateException("the static example could not talk to its own server: " + failure.getMessage(),
          failure);
    } finally {
      server.stop();
    }
  }
}
