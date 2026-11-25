package com.barezen.examples.staticcontent;

import static org.assertj.core.api.Assertions.assertThat;

import com.barezen.testkit.Assertions;
import com.barezen.testkit.TestResponse;
import com.barezen.testkit.TestServer;
import com.barezen.web.HttpMethod;
import com.barezen.web.Router;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Proves the three answers {@link StaticFileExample#main} prints, over a real socket: a first request carries the bytes
 * and a tag, a conditional reload is a 304 with no body and no length, and a {@code HEAD} states a length it does not
 * send (ADR-0029, re-measured by ADR-0051 with a consumer in the room).
 */
final class StaticFileExampleTest {

  @Test
  void everyFileInTheDirectoryIsRoutedUnderItsOwnName() throws IOException {
    try (TestServer server = TestServer.start(StaticFileExample.router(StaticFileExample.workspace()))) {
      Assertions.assertThat(server.client().get("/notes.txt")).statusIs(200)
          .headerIs("Content-Type", "text/plain; charset=utf-8");
      Assertions.assertThat(server.client().get("/status.json")).statusIs(200)
          .headerIs("Content-Type", "application/json");
    }
  }

  @Test
  void aPackagedClasspathResourceIsRoutedThroughThePublicApi() throws IOException {
    try (TestServer server = TestServer.start(StaticFileExample.router(StaticFileExample.workspace()))) {
      Assertions.assertThat(server.client().get("/classpath.txt")).statusIs(200)
          .headerIs("Content-Type", "text/plain; charset=utf-8")
          .bodyIs("hello from the example classpath\n");
      Assertions.assertThat(server.client().head("/classpath.txt")).statusIs(200);
    }
  }

  @Test
  void aReloadWithTheTagIsABodyFreeThreeOhFour() throws IOException {
    try (TestServer server = TestServer.start(StaticFileExample.router(StaticFileExample.workspace()))) {
      TestResponse first = server.client().get("/notes.txt");
      String tag = first.header("ETag").orElseThrow();

      TestResponse reload = server.client().request(HttpMethod.GET, "/notes.txt")
          .header("If-None-Match", tag).send();

      // A 304 that stated a length would state the length of a body it is forbidden to send (ADR-0027 §5).
      assertThat(reload.status()).isEqualTo(304);
      assertThat(reload.bodyBytes()).isEmpty();
      assertThat(reload.header("Content-Length")).isEmpty();
      assertThat(reload.header("ETag")).contains(tag);
      assertThat(first.header("Content-Length")).contains(Integer.toString(first.bodyBytes().length));
    }
  }

  @Test
  void headStatesTheLengthOfTheBodyItDoesNotSend() throws IOException {
    try (TestServer server = TestServer.start(StaticFileExample.router(StaticFileExample.workspace()))) {
      int length = server.client().get("/notes.txt").bodyBytes().length;

      TestResponse head = server.client().head("/notes.txt");

      assertThat(head.status()).isEqualTo(200);
      assertThat(head.header("Content-Length")).contains(Integer.toString(length));
      assertThat(head.bodyBytes()).isEmpty();
    }
  }

  @Test
  void aFileRegisteredOnlyForGetRefusesHead() throws IOException {
    // HEAD is a route an application registers, not a GET the framework folds away (ADR-0028): this is why
    // StaticFileExample.router registers both verbs for every file.
    Router getOnly = Router.builder()
        .route(HttpMethod.GET, "/notes.txt",
            com.barezen.content.StaticContent.file(StaticFileExample.workspace().resolve("notes.txt")))
        .build();
    try (TestServer server = TestServer.start(getOnly)) {
      Assertions.assertThat(server.client().get("/notes.txt")).statusIs(200);
      Assertions.assertThat(server.client().head("/notes.txt")).statusIs(405);
    }
  }

  @Test
  void anUnregisteredAddressIsTheContractsOwnFourOhFour() throws IOException {
    try (TestServer server = TestServer.start(StaticFileExample.router(StaticFileExample.workspace()))) {
      // One route per file is a listing made at assembly, so anything else is simply not there: no path a client
      // supplied was ever joined to a directory (ADR-0051's traversal point).
      Assertions.assertThat(server.client().get("/secret.txt")).statusIs(404).bodyIs("Not Found");
      Assertions.assertThat(server.client().request(HttpMethod.POST, "/notes.txt").send()).statusIs(405);
    }
  }
}
