package com.barezen.plugins.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.barezen.web.RouteMatch;
import com.barezen.web.Router;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The README's Phase 2.14 example, executed. A documented snippet that no test runs is a snippet that rots with
 * the first signature change, so the README's own code is copied here line for line and asserted to compile,
 * build a document, mount on a real {@link Router}, and serve exactly that document's bytes.
 */
class ReadmeDocumentExampleTest {

  @Test
  @DisplayName("the README 2.14 snippet compiles, builds, and serves exactly its document's bytes")
  void readmeExampleIsReal() {
    OpenApiPlugin openapi = new OpenApiPlugin();
    openapi.add(HttpMethod.GET, "/users/{id}", "Fetch one user", List.of("users"));

    Schema user = Schema.object(List.of(
        new Schema.Property("id", Schema.integer(), true),
        new Schema.Property("name", Schema.string(), true)));

    ApiDocument document = ApiDocument.builder()
        .info("Demo", "1.0")
        .schema("User", user)
        .securityScheme(Security.Scheme.bearer("sessionToken"))
        .operations(openapi.operations().stream()
            .map(metadata -> Operation.builder(metadata)
                .parameter(Parameter.path("id", Schema.integer()))
                .security(Security.Requirement.of("sessionToken", List.of()))
                .response(ApiResponse.json("200", "the user", Schema.ref("User")))
                .build())
            .toList())
        .build();

    Router router = Router.builder()
        .route(HttpMethod.GET, "/openapi.json", document.handler())
        .build();

    Request request = new Request(HttpMethod.GET, "/openapi.json", null, Headers.empty(), null);
    RouteMatch match = router.match(request).orElseThrow();
    Response response = match.handler().handle(request, match.parameters());

    assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(document.toJson());
    assertThat(document.toJson())
        .contains("\"security\":[{\"sessionToken\":[]}]")
        .contains("\"$ref\":\"#/components/schemas/User\"");
  }
}
