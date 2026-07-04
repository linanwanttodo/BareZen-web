package com.barezen.examples.openapi;

import com.barezen.plugins.openapi.ApiDocument;
import com.barezen.testkit.Assertions;
import com.barezen.testkit.TestServer;
import com.barezen.web.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the openapi example's document is valid (build refuses otherwise) and its handler serves deterministic JSON. */
final class OpenApiExampleTest {

  @Test
  void documentIsWellFormedAndItsJsonIsTheDocument() {
    ApiDocument document = OpenApiExample.document();
    assertThat(document.title()).isEqualTo("BareZen Users API");
    assertThat(document.version()).isEqualTo("1.0");
    assertThat(document.servers()).containsExactly("https://api.example.com");
    assertThat(document.paths()).containsOnlyKeys("/users", "/users/{id}");
    // Determinism: rendering the same document twice yields the same bytes.
    assertThat(document.toJson()).isEqualTo(OpenApiExample.document().toJson());
  }

  @Test
  void theHandlerServesTheJsonOnItsOwnRoute() {
    try (TestServer server = TestServer.start(OpenApiExample.router())) {
      var response = server.client().get("/openapi.json");
      Assertions.assertThat(response)
          .statusIs(200)
          .headerIs("Content-Type", "application/json")
          .jsonStringIs("openapi", ApiDocument.SPEC_VERSION)
          .jsonStringIs("info.title", "BareZen Users API")
          .jsonStringIs("info.version", "1.0")
          .bodyContains("\"/users/{id}\"");
      // Every declared operation is on the wire; the schema is registered.
      Assertions.assertThat(response).jsonHas("paths./users.post");
      Assertions.assertThat(response).jsonHas("paths./users/{id}.get");
      Assertions.assertThat(response).jsonHas("components.schemas.User");
    }
  }

  @Test
  void unknownDocumentPathIsNotFound() {
    try (TestServer server = TestServer.start(OpenApiExample.router())) {
      Assertions.assertThat(server.client().get("/docs")).statusIs(404);
    }
  }

  @Test
  void handlerServesTheSameBytesTwice() {
    // ApiDocument.handler() renders once at assembly, so every request answers the same bytes: this proves the
    // example's JSON is stable across a real HTTP round-trip, not only inside the JVM.
    try (TestServer server = TestServer.start(OpenApiExample.router())) {
      String first = server.client().get("/openapi.json").bodyText();
      String second = server.client().request(HttpMethod.GET, "/openapi.json").send().bodyText();
      assertThat(first).isEqualTo(second).hasSizeGreaterThan(100);
    }
  }
}
