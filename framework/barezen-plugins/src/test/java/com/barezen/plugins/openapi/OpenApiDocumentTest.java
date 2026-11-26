package com.barezen.plugins.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.RouteMatch;
import com.barezen.web.Router;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The specification model Stage 2.14 builds on the metadata 2.13 collected. Two promises are tested here and
 * nothing else: a document either describes a valid API or does not exist (the six refusal categories of Stage
 * 2.14-F), and the same declarations always render byte-identical JSON (the Stage 2.14-D determinism the
 * {@link Schema} / {@link Operation} / {@link ApiDocument} trio owns structurally).
 */
class OpenApiDocumentTest {

  private static Operation listUsers() {
    return Operation.builder(new OperationMetadata(HttpMethod.GET, "/users", "List users", List.of("users")))
        .response(ApiResponse.json("200", "the users", Schema.array(Schema.ref("User"))))
        .build();
  }

  private static Operation getUser() {
    return Operation.builder(new OperationMetadata(HttpMethod.GET, "/users/{id}", "Fetch one user", List.of("users")))
        .parameter(Parameter.path("id", Schema.integer()))
        .response(ApiResponse.json("200", "the user", Schema.ref("User")))
        .response(ApiResponse.empty("404", "no such user"))
        .build();
  }

  /** The self-referential component the cycle-safety claim rests on: User.friends is array($ref User). */
  private static Schema userSchema() {
    return Schema.object(List.of(
        new Schema.Property("id", Schema.integer(), true),
        new Schema.Property("name", Schema.string(), true),
        new Schema.Property("friends", Schema.array(Schema.ref("User")), false)));
  }

  private static ApiDocument.Builder withUserComponent() {
    return ApiDocument.builder().info("Demo", "1.0").schema("User", userSchema());
  }

    @Nested
  @DisplayName("a valid document builds")
  class Valid {

    @Test
    @DisplayName("the minimum honest document: info and one operation with a response")
    void minimumDocument() {
      ApiDocument document = ApiDocument.builder()
          .info("Library", "2.0")
          .operation(Operation.builder(new OperationMetadata(HttpMethod.GET, "/health", "Liveness", List.of()))
              .response(ApiResponse.empty("200", "alive"))
              .build())
          .build();

      assertThat(document.title()).isEqualTo("Library");
      assertThat(document.version()).isEqualTo("2.0");
      assertThat(document.paths()).containsOnlyKeys("/health");
      // golden bytes, not a prefix: the emitter's exact output is the contract the Stage 2.14-G mutations are
      // checked against, and a generator change that moves one key or one field fails this line first
      assertThat(document.toJson()).isEqualTo(
          "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"Library\",\"version\":\"2.0\"},"
              + "\"paths\":{\"/health\":{\"get\":{\"operationId\":\"getHealth\",\"summary\":\"Liveness\","
              + "\"responses\":{\"200\":{\"description\":\"alive\"}}}}}}");
    }

    @Test
    @DisplayName("a full document with servers, components, security and tags renders every section")
    void fullDocument() {
      ApiDocument document = withUserComponent()
          .server("https://api.example.com")
          .securityScheme(Security.Scheme.bearer("sessionToken"))
          .operation(listUsers())
          .operation(getUser())
          .operation(Operation.builder(
              new OperationMetadata(HttpMethod.POST, "/users", "Create a user", List.of("users")))
              .requestBody(RequestBody.json(Schema.ref("User"), true))
              .security(Security.Requirement.of("sessionToken", List.of()))
              .response(ApiResponse.json("201", "created", Schema.ref("User")))
              .build())
          .build();

      String json = document.toJson();
      // golden bytes for the whole full-document surface: securitySchemes, both path items, every operation's
      // method key, parameters, requestBody content, security, the $ref *inside a response body*, tags, and the
      // component — the Stage 2.14-G drop-/scramble-mutations each remove one visible fragment of this string
      assertThat(json).isEqualTo(
          "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"Demo\",\"version\":\"1.0\"},"
              + "\"servers\":[{\"url\":\"https://api.example.com\"}],"
              + "\"paths\":{"
              + "\"/users\":{"
              + "\"get\":{\"operationId\":\"getUsers\",\"summary\":\"List users\",\"tags\":[\"users\"],"
              + "\"responses\":{\"200\":{\"description\":\"the users\",\"content\":{\"application/json\":"
              + "{\"schema\":{\"type\":\"array\",\"items\":{\"$ref\":\"#/components/schemas/User\"}}}}}}},"
              + "\"post\":{\"operationId\":\"postUsers\",\"summary\":\"Create a user\",\"tags\":[\"users\"],"
              + "\"requestBody\":{\"required\":true,\"content\":{\"application/json\":"
              + "{\"schema\":{\"$ref\":\"#/components/schemas/User\"}}}},"
              + "\"security\":[{\"sessionToken\":[]}],"
              + "\"responses\":{\"201\":{\"description\":\"created\",\"content\":{\"application/json\":"
              + "{\"schema\":{\"$ref\":\"#/components/schemas/User\"}}}}}}},"
              + "\"/users/{id}\":{\"get\":{\"operationId\":\"getUsersId\",\"summary\":\"Fetch one user\","
              + "\"tags\":[\"users\"],"
              + "\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"required\":true,"
              + "\"schema\":{\"type\":\"integer\",\"format\":\"int64\"}}],"
              + "\"responses\":{\"200\":{\"description\":\"the user\",\"content\":{\"application/json\":"
              + "{\"schema\":{\"$ref\":\"#/components/schemas/User\"}}}},"
              + "\"404\":{\"description\":\"no such user\"}}}}},"
              + "\"components\":{\"schemas\":{\"User\":{\"type\":\"object\",\"properties\":{"
              + "\"id\":{\"type\":\"integer\",\"format\":\"int64\"},\"name\":{\"type\":\"string\"},"
              + "\"friends\":{\"type\":\"array\",\"items\":{\"$ref\":\"#/components/schemas/User\"}}},"
              + "\"required\":[\"id\",\"name\"]}},"
              + "\"securitySchemes\":{\"sessionToken\":{\"type\":\"http\",\"scheme\":\"bearer\"}}},"
              + "\"tags\":[{\"name\":\"users\"}]}");
    }
  }

  @Nested
  @DisplayName("Stage 2.14-F: the six refusal categories")
  class Refusals {

    @Test
    @DisplayName("category 1 — a missing required field: blank title or version is refused at build")
    void missingRequiredField() {
      assertThatThrownBy(() -> ApiDocument.builder().info(" ", "1.0").operation(listUsers()).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("info.title");
      assertThatThrownBy(() -> ApiDocument.builder().info("T", "").operation(listUsers()).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("info.version");
    }

    @Test
    @DisplayName("category 2 — a duplicate (method, path) across two separately-built operations is refused")
    void duplicatePath() {
      Operation first = listUsers();
      Operation second = Operation.builder(new OperationMetadata(HttpMethod.GET, "/users", "Also lists", List.of()))
          .response(ApiResponse.empty("200", "ok"))
          .build();

      assertThatThrownBy(() -> ApiDocument.builder().info("T", "1.0").operation(first).operation(second).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("twice")
          .hasMessageContaining("GET /users");
    }

    @Test
    @DisplayName("category 3 — two templates that project to the same operationId are refused")
    void duplicateOperationId() {
      // /users/{id} and /users/id differ only in the braces the derivation strips, so both project to getUsersId
      Operation byPattern = getUser();
      Operation byLiteral = Operation.builder(
          new OperationMetadata(HttpMethod.GET, "/users/id", "The literal 'id' user", List.of()))
          .response(ApiResponse.empty("200", "ok"))
          .build();

      assertThatThrownBy(() -> ApiDocument.builder().info("T", "1.0")
          .schema("User", userSchema())
          .operation(byPattern).operation(byLiteral).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("getUsersId")
          .hasMessageContaining("operationId");
    }

    @Test
    @DisplayName("category 4 — an invalid response: an operation with no response at all is refused")
    void invalidResponse() {
      assertThatThrownBy(() -> Operation.builder(
          new OperationMetadata(HttpMethod.GET, "/x", "X", List.of())).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("no response");
    }

    @Test
    @DisplayName("category 5 — a $ref naming an undeclared component is refused, from an operation and from a schema")
    void invalidSchemaReference() {
      assertThatThrownBy(() -> ApiDocument.builder().info("T", "1.0").operation(listUsers()).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("never declared")
          .hasMessageContaining("User");

      // the component-to-component edge an operation never sees: Order references a missing User
      Schema order = Schema.object(List.of(
          new Schema.Property("owner", Schema.ref("User"), true)));
      assertThatThrownBy(() -> ApiDocument.builder().info("T", "1.0")
          .schema("Order", order)
          .operation(Operation.builder(new OperationMetadata(HttpMethod.GET, "/o", "O", List.of()))
              .response(ApiResponse.json("200", "order", Schema.ref("Order"))).build())
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("component schema \"Order\"");
    }

    @Test
    @DisplayName("component schema names use the OpenAPI component-key grammar")
    void invalidComponentSchemaName() {
      assertThatThrownBy(() -> ApiDocument.builder().info("T", "1.0")
          .schema("User/Profile", Schema.string())
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must match [A-Za-z0-9._-]+");
      assertThatThrownBy(() -> ApiDocument.builder().info("T", "1.0")
          .schema("User Profile", Schema.string())
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must match [A-Za-z0-9._-]+");
      assertThat(ApiDocument.builder().info("T", "1.0")
          .schema("User.Profile-1_2", Schema.string())
          .build().schemas()).containsKey("User.Profile-1_2");
    }

    @Test
    @DisplayName("category 6 — a security requirement naming an undeclared scheme is refused at build")
    void invalidSecurityRequirement() {
      Operation guarded = Operation.builder(new OperationMetadata(HttpMethod.DELETE, "/users/{id}", "Delete", List.of()))
          .parameter(Parameter.path("id", Schema.integer()))
          .security(Security.Requirement.of("sessionToken", List.of()))
          .response(ApiResponse.empty("204", "deleted"))
          .build();

      assertThatThrownBy(() -> withUserComponent().operation(guarded).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sessionToken")
          .hasMessageContaining("never declared");

      ApiDocument ok = withUserComponent()
          .securityScheme(Security.Scheme.bearer("sessionToken"))
          .operation(guarded)
          .build();
      assertThat(ok.toJson()).contains("\"scheme\":\"bearer\"");
    }
  }

  @Nested
  @DisplayName("Stage 2.14-D: determinism")
  class Determinism {

    @Test
    @DisplayName("the same declarations assembled in a different order render byte-identical JSON")
    void assemblyOrderDoesNotChangeBytes() {
      ApiDocument one = withUserComponent()
          .server("https://a")
          .operation(listUsers())
          .operation(getUser())
          .build();
      ApiDocument other = withUserComponent()
          .server("https://a")
          .operation(getUser())
          .operation(listUsers())
          .build();

      assertThat(other.toJson()).isEqualTo(one.toJson());
    }

    @Test
    @DisplayName("paths sort by template, operations within a path by method, regardless of input order")
    void orderIsDefinedNotAccidental() {
      Operation post = Operation.builder(new OperationMetadata(HttpMethod.POST, "/mike", "p", List.of()))
          .response(ApiResponse.empty("200", "ok")).build();
      Operation del = Operation.builder(new OperationMetadata(HttpMethod.DELETE, "/alpha", "d", List.of()))
          .response(ApiResponse.empty("200", "ok")).build();
      Operation get = Operation.builder(new OperationMetadata(HttpMethod.GET, "/alpha", "g", List.of()))
          .response(ApiResponse.empty("200", "ok")).build();

      String json = ApiDocument.builder().info("T", "1.0").operation(post).operation(get).operation(del)
          .build().toJson();
      int alpha = json.indexOf("\"/alpha\"");
      int mike = json.indexOf("\"/mike\"");
      int delete = json.indexOf("\"delete\"");
      int getAt = json.indexOf("\"get\"");
      assertThat(alpha).isLessThan(mike);
      assertThat(delete).isLessThan(getAt); // DELETE before GET within /alpha, by method text
    }

    @Test
    @DisplayName("responses keep declared order in the output: 404 before 200 stays 404 before 200")
    void responseOrderIsDeclaredOrderInOutput() {
      ApiDocument document = ApiDocument.builder().info("T", "1.0")
          .operation(Operation.builder(new OperationMetadata(HttpMethod.GET, "/x", "X", List.of()))
              .response(ApiResponse.empty("404", "missing"))
              .response(ApiResponse.empty("200", "ok"))
              .build())
          .build();

      assertThat(document.toJson()).contains(
          "\"responses\":{\"404\":{\"description\":\"missing\"},\"200\":{\"description\":\"ok\"}}");
    }
  }

  @Nested
  @DisplayName("declared details reach the output")
  class DeclaredDetails {

    @Test
    @DisplayName("a parameter description, a body description, a nullable property and a boolean schema render as declared")
    void detailsAreEmitted() {
      ApiDocument document = ApiDocument.builder().info("D", "1.0")
          .schema("Flags", Schema.object(List.of(
              new Schema.Property("active", Schema.booleanSchema().asNullable(), false))))
          .operation(Operation.builder(new OperationMetadata(HttpMethod.POST, "/users", "Create", List.of()))
              .parameter(new Parameter("id", Parameter.Location.QUERY, Schema.string(), false, "the user id"))
              .requestBody(new RequestBody("the new user", Schema.ref("Flags"), true))
              .response(ApiResponse.json("200", "flags", Schema.ref("Flags")))
              .build())
          .build();

      assertThat(document.toJson()).isEqualTo(
          "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"D\",\"version\":\"1.0\"},"
              + "\"paths\":{\"/users\":{\"post\":{\"operationId\":\"postUsers\",\"summary\":\"Create\","
              + "\"parameters\":[{\"name\":\"id\",\"in\":\"query\",\"required\":false,"
              + "\"description\":\"the user id\",\"schema\":{\"type\":\"string\"}}],"
              + "\"requestBody\":{\"description\":\"the new user\",\"required\":true,"
              + "\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/Flags\"}}}},"
              + "\"responses\":{\"200\":{\"description\":\"flags\",\"content\":{\"application/json\":"
              + "{\"schema\":{\"$ref\":\"#/components/schemas/Flags\"}}}}}}}},"
              + "\"components\":{\"schemas\":{\"Flags\":{\"type\":\"object\",\"properties\":{\"active\":"
              + "{\"type\":\"boolean\",\"nullable\":true}}}}}}");
    }
  }

  @Nested
  @DisplayName("cycle safety: $ref is a name, not a walk")
  class CycleSafety {

    @Test
    @DisplayName("a self-referential User renders without recursing, because a REFERENCE node is one string")
    void cyclicSchemaTerminates() {
      ApiDocument document = withUserComponent()
          .operation(Operation.builder(new OperationMetadata(HttpMethod.GET, "/users/{id}", "u", List.of()))
              .parameter(Parameter.path("id", Schema.integer()))
              .response(ApiResponse.json("200", "user", Schema.ref("User")))
              .build())
          .build();

      String json = document.toJson();
      // The test *ran* is the termination proof: a walk that recursed through the name would not have returned.
      // And the cycle's body is expanded exactly once, not unrolled — the friends edge is a reference, so
      // User's required-field list appears one time no matter how deep the type graph behind it goes.
      assertThat(json).contains("\"friends\":{\"type\":\"array\",\"items\":{\"$ref\":\"#/components/schemas/User\"}}");
      assertThat(json.split("\"required\":\\[\"id\",\"name\"\\]", -1).length - 1)
          .as("the User object is emitted once, by name, not recursed into").isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("wrong-path-parameter guard")
  class PathParameters {

    @Test
    @DisplayName("a path parameter the pattern does not contain is refused")
    void undeclaredPathParameter() {
      assertThatThrownBy(() -> Operation.builder(
          new OperationMetadata(HttpMethod.GET, "/users/{id}", "u", List.of()))
          .parameter(Parameter.path("nope", Schema.string()))
          .response(ApiResponse.empty("200", "ok"))
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("nope");
    }

    @Test
    @DisplayName("a pattern {name} with no matching path parameter is refused")
    void missingPathParameter() {
      assertThatThrownBy(() -> Operation.builder(
          new OperationMetadata(HttpMethod.GET, "/users/{id}", "u", List.of()))
          .response(ApiResponse.empty("200", "ok"))
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("{id}");
    }

    @Test
    @DisplayName("a path parameter typed as an object is refused: a captured segment is text")
    void nonPrimitivePathParameter() {
      assertThatThrownBy(() -> Operation.builder(
          new OperationMetadata(HttpMethod.GET, "/users/{id}", "u", List.of()))
          .parameter(Parameter.path("id", Schema.object(List.of(new Schema.Property("x", Schema.string(), true)))))
          .response(ApiResponse.empty("200", "ok"))
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("typed");
    }
  }

  @Nested
  @DisplayName("the projection is one-way and the handler serves exact bytes")
  class Serving {

    @Test
    @DisplayName("OpenApiPlugin metadata projects into Operations; the collector is never written back")
    void projectionFromCollector() {
      OpenApiPlugin collector = new OpenApiPlugin();
      collector.add(HttpMethod.GET, "/users/{id}", "Fetch one user", List.of("users"));

      List<Operation> projected = collector.operations().stream()
          .map(metadata -> Operation.builder(metadata)
              .parameter(Parameter.path("id", Schema.integer()))
              .response(ApiResponse.json("200", "the user", Schema.ref("User")))
              .build())
          .toList();

      assertThat(projected).singleElement()
          .satisfies(operation -> assertThat(operation.metadata().summary()).isEqualTo("Fetch one user"));
      // one-way: projecting did not add to the collector
      assertThat(collector.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("document.handler() answers the rendered bytes with a JSON content type through the router")
    void handlerServesTheDocument() {
      ApiDocument document = withUserComponent()
          .operation(getUser())
          .build();
      String expected = document.toJson();

      Router router = Router.builder()
          .route(HttpMethod.GET, "/openapi.json", document.handler())
          .build();
      RouteMatch match = router.match(new Request(HttpMethod.GET, "/openapi.json", null,
          com.barezen.web.Headers.empty(), null)).orElseThrow();
      com.barezen.web.Response response = match.handler().handle(
          new Request(HttpMethod.GET, "/openapi.json", null, com.barezen.web.Headers.empty(), null),
          Map.of());

      assertThat(response.status()).isEqualTo(200);
      assertThat(response.headers().first("Content-Type")).contains("application/json");
      assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("identity")
  class Identity {

    @Test
    @DisplayName("toString counts structure and leaks no declared text")
    void identity() {
      ApiDocument document = withUserComponent().operation(listUsers()).build();
      assertThat(document.toString()).contains("OpenAPI 3.0.3");
      assertThat(document.toString()).doesNotContain("List users");
    }
  }
}
