package com.barezen.examples.routing;

import com.barezen.testkit.Assertions;
import com.barezen.testkit.TestServer;
import org.junit.jupiter.api.Test;

/**
 * Proves the routing table binds path variables and distinguishes 404 from 405, exactly as
 * {@link RoutingExample#main} describes.
 */
final class RoutingExampleTest {

  @Test
  void pathVariablesArriveInTheParametersMap() {
    try (TestServer server = TestServer.start(RoutingExample.router())) {
      Assertions.assertThat(server.client().get("/books/42")).statusIs(200).bodyIs("book 42");
      Assertions.assertThat(server.client().get("/books")).statusIs(200).bodyIs("the library has no books yet");
    }
  }

  @Test
  void postOnACollectionIsCreated() {
    try (TestServer server = TestServer.start(RoutingExample.router())) {
      Assertions.assertThat(server.client().post("/books", "a title")).statusIs(201).bodyIs("created");
    }
  }

  @Test
  void unknownPathIsNotFoundWhileKnownPathWithWrongVerbIsItsOwnAnswer() {
    // 404 vs 405 is the framework's routing decision, not a handler's; both come from the router table.
    try (TestServer server = TestServer.start(RoutingExample.router())) {
      Assertions.assertThat(server.client().get("/author")).statusIs(404);
      Assertions.assertThat(server.client().request(com.barezen.web.HttpMethod.DELETE, "/books").send()).statusIs(405);
    }
  }
}
