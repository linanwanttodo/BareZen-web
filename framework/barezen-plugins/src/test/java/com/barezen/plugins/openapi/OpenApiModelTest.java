package com.barezen.plugins.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.web.HttpMethod;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The construction-time rules of the model records. Each refusal here is a malformed-spec fragment that never
 * reaches a document — Stage 2.14-F's invalid-response and invalid-schema cases closed at the point of writing,
 * in the same style as {@link OperationMetadata}'s validations in 2.13.
 */
class OpenApiModelTest {

  @Nested
  @DisplayName("Schema")
  class Schemas {

    @Test
    @DisplayName("an object schema cannot carry items, format or a ref name")
    void objectCarriesOnlyItsProperties() {
      Schema object = Schema.object(List.of(new Schema.Property("a", Schema.string(), true)));
      assertThatThrownBy(() -> new Schema(Schema.SchemaType.OBJECT, "date-time", object.properties(), null, null, false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("format");
      assertThatThrownBy(() -> new Schema(Schema.SchemaType.OBJECT, null, List.of(), object, null, false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("items");
    }

    @Test
    @DisplayName("an array schema names its items and carries nothing else")
    void arrayNeedsItems() {
      assertThatThrownBy(() -> new Schema(Schema.SchemaType.ARRAY, null, List.of(), null, null, false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("no item schema");
      assertThatThrownBy(() -> new Schema(Schema.SchemaType.ARRAY, "int64", List.of(), Schema.integer(), null, false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("no properties, format or ref name");
    }

    @Test
    @DisplayName("nested arrays are legal; a reference schema carries only its name")
    void nestingAndReferences() {
      Schema matrix = Schema.array(Schema.array(Schema.number()));
      assertThat(matrix.items().items().type()).isEqualTo(Schema.SchemaType.NUMBER);

      assertThatThrownBy(() -> new Schema(Schema.SchemaType.REFERENCE, null, List.of(), null, " ", false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("name its component");
      assertThatThrownBy(() -> new Schema(Schema.SchemaType.REFERENCE, null,
          List.of(new Schema.Property("x", Schema.string(), false)), null, "User", false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("nothing but its name");
    }

    @Test
    @DisplayName("a ref name outside [A-Za-z0-9._-] is refused — it travels inside a $ref JSON pointer")
    void refNameCharset() {
      assertThatThrownBy(() -> Schema.ref("User/Two"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("characters outside");
      assertThat(Schema.ref("User.v2").refName()).isEqualTo("User.v2");
    }

    @Test
    @DisplayName("a duplicate property name in one object is refused and named")
    void duplicateProperty() {
      assertThatThrownBy(() -> Schema.object(List.of(
          new Schema.Property("id", Schema.string(), true),
          new Schema.Property("id", Schema.integer(), false))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'id'");
    }

    @Test
    @DisplayName("required is derived from the flags in declaration order, never stored separately")
    void requiredIsDerived() {
      Schema schema = Schema.object(List.of(
          new Schema.Property("id", Schema.integer(), true),
          new Schema.Property("nick", Schema.string(), false),
          new Schema.Property("name", Schema.string(), true)));

      assertThat(schema.requiredNames()).containsExactly("id", "name");
      assertThat(schema.asNullable().nullable()).isTrue();
      assertThat(schema.asNullable().requiredNames()).containsExactly("id", "name");
      assertThat(schema.toString()).isEqualTo("object");
      assertThat(Schema.integer().toString()).isEqualTo("integer(int64)");
      assertThat(Schema.ref("User").toString()).isEqualTo("$ref:User");
    }

    @Test
    @DisplayName("a primitive cannot carry structure and a blank property name is refused")
    void primitivesAndNames() {
      assertThatThrownBy(() -> new Schema(Schema.SchemaType.STRING, null,
          List.of(new Schema.Property("x", Schema.string(), true)), null, null, false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("string schema");
      assertThatThrownBy(() -> new Schema.Property(" ", Schema.string(), false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("the public constructor treats null properties as none — the copy is the caller's safety")
    void nullPropertiesBecomeEmpty() {
      Schema schema = new Schema(Schema.SchemaType.OBJECT, null, null, null, null, false);
      assertThat(schema.properties()).isEmpty();
      assertThat(schema.requiredNames()).isEmpty();
      assertThat(Schema.booleanSchema().toString()).isEqualTo("boolean");
    }
  }

  @Nested
  @DisplayName("Parameter")
  class Parameters {

    @Test
    @DisplayName("a path parameter is always required — the router has no optional segment")
    void pathParametersAreMandatory() {
      assertThatThrownBy(() -> new Parameter("id", Parameter.Location.PATH, Schema.string(), false, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("optional");
      assertThat(Parameter.path("id", Schema.string()).required()).isTrue();
      assertThat(Parameter.query("q", Schema.string(), false).required()).isFalse();
      assertThat(Parameter.header("X-Trace", Schema.string(), false).toString()).isEqualTo("header:X-Trace");
    }

    @Test
    @DisplayName("a blank parameter name is refused")
    void blankName() {
      assertThatThrownBy(() -> Parameter.query("  ", Schema.string(), false))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("ApiResponse")
  class Responses {

    @Test
    @DisplayName("the status-code subset: three digits, NXX range, or 'default'")
    void statusCodeGrammar() {
      assertThat(ApiResponse.empty("2XX", "any 2xx").statusCode()).isEqualTo("2XX");
      assertThat(ApiResponse.empty("default", "fallback").statusCode()).isEqualTo("default");
      for (String bad : List.of("20", "600", "2XX1", "OK", "")) {
        assertThatThrownBy(() -> ApiResponse.empty(bad, "d"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("outside the subset");
      }
    }

    @Test
    @DisplayName("204 and 304 are refused a body schema — the framing contract forbids the bytes (ADR-0029)")
    void noBodyStatuses() {
      assertThatThrownBy(() -> ApiResponse.json("204", "gone", Schema.string()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("204");
      assertThatThrownBy(() -> ApiResponse.json("304", "not modified", Schema.string()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("304");
      assertThat(ApiResponse.json("200", "body", Schema.string()).bodySchema()).isNotNull();
    }

    @Test
    @DisplayName("description is required and a null status or description is refused")
    void requiredFields() {
      assertThatThrownBy(() -> new ApiResponse("200", null, null))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new ApiResponse(null, "d", null))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new ApiResponse("200", " ", null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("blank");
      assertThat(ApiResponse.empty("204", "deleted").toString()).isEqualTo("204");
    }
  }

  @Nested
  @DisplayName("RequestBody")
  class Bodies {

    @Test
    @DisplayName("json() fixes the one media type and carries the required flag")
    void singleMediaType() {
      RequestBody body = RequestBody.json(Schema.string(), true);
      assertThat(RequestBody.JSON_MEDIA_TYPE).isEqualTo("application/json");
      assertThat(body.required()).isTrue();
      assertThat(body.description()).isNull();
      assertThat(body.toString()).isEqualTo("application/json (required)");
    }

    @Test
    @DisplayName("a blank description is refused, a null schema is refused")
    void refusals() {
      assertThatThrownBy(() -> new RequestBody("  ", Schema.string(), false))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new RequestBody(null, null, false))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("Security")
  class SecurityShapes {

    @Test
    @DisplayName("a scheme and a requirement keep the names they were declared with")
    void names() {
      Security.Scheme scheme = Security.Scheme.bearer("sessionToken");
      assertThat(scheme.name()).isEqualTo("sessionToken");
      assertThat(scheme.toString()).isEqualTo("bearer:sessionToken");

      Security.Requirement requirement = Security.Requirement.of("sessionToken", List.of("read", "write"));
      assertThat(requirement.schemeName()).isEqualTo("sessionToken");
      assertThat(requirement.scopes()).containsExactly("read", "write");
      assertThat(Security.Requirement.of("sessionToken", List.of()).scopes()).isEmpty();
    }

    @Test
    @DisplayName("blank scheme names and blank or null scopes are refused")
    void refusals() {
      assertThatThrownBy(() -> Security.Scheme.bearer(" "))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> Security.Requirement.of("", List.of()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> Security.Requirement.of("s", null))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> Security.Requirement.of("s", List.of("ok", " ")))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Operation builder refusals beyond the pattern cross-check")
  class OperationBuilder {

    private Operation.Builder getUser() {
      return Operation.builder(new OperationMetadata(HttpMethod.GET, "/users/{id}", "u", List.of()))
          .parameter(Parameter.path("id", Schema.integer()));
    }

    @Test
    @DisplayName("a second response for one code is refused and names the code")
    void duplicateResponseCode() {
      assertThatThrownBy(() -> getUser()
          .response(ApiResponse.empty("200", "ok"))
          .response(ApiResponse.empty("200", "also ok"))
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("200")
          .hasMessageContaining("twice");
    }

    @Test
    @DisplayName("a second request body is refused")
    void duplicateRequestBody() {
      assertThatThrownBy(() -> getUser()
          .requestBody(RequestBody.json(Schema.string(), false))
          .requestBody(RequestBody.json(Schema.string(), false)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("declared twice");
    }

    @Test
    @DisplayName("the same parameter name and location cannot be declared twice")
    void duplicateParameterIdentity() {
      assertThatThrownBy(() -> Operation.builder(
          new OperationMetadata(HttpMethod.GET, "/users", "u", List.of()))
          .parameter(Parameter.query("q", Schema.string(), false))
          .parameter(Parameter.query("q", Schema.integer(), true))
          .response(ApiResponse.empty("200", "ok"))
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("q")
          .hasMessageContaining("twice");
    }

    @Test
    @DisplayName("the same name in different locations remains legal")
    void sameNameInDifferentLocations() {
      Operation operation = Operation.builder(
          new OperationMetadata(HttpMethod.GET, "/users", "u", List.of()))
          .parameter(Parameter.query("q", Schema.string(), false))
          .parameter(Parameter.header("q", Schema.string(), false))
          .response(ApiResponse.empty("200", "ok"))
          .build();

      assertThat(operation.parameters()).hasSize(2);
    }

    @Test
    @DisplayName("a query or header parameter wearing a path name is refused")
    void nameCollisionWithPattern() {
      assertThatThrownBy(() -> getUser()
          .parameter(Parameter.query("id", Schema.string(), false))
          .response(ApiResponse.empty("200", "ok"))
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("reserves it as a path parameter");
    }

    @Test
    @DisplayName("a pattern with an unclosed brace is refused where it is read")
    void unclosedPattern() {
      assertThatThrownBy(() -> Operation.builder(new OperationMetadata(HttpMethod.GET, "/users/{id", "u", List.of()))
          .response(ApiResponse.empty("200", "ok"))
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unclosed");
    }

    @Test
    @DisplayName("braces must fill a whole path segment, as Router requires")
    void bracesMustFillAWholeSegment() {
      assertThatThrownBy(() -> Operation.builder(
          new OperationMetadata(HttpMethod.GET, "/users/prefix{id}", "u", List.of()))
          .response(ApiResponse.empty("200", "ok"))
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("whole parameter");
    }

    @Test
    @DisplayName("path parameter names use Router's identifier grammar")
    void pathParameterNamesUseIdentifierGrammar() {
      assertThatThrownBy(() -> Operation.builder(
          new OperationMetadata(HttpMethod.GET, "/users/{2fa}", "u", List.of()))
          .response(ApiResponse.empty("200", "ok"))
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not a parameter name");
    }

    @Test
    @DisplayName("a path template cannot name the same parameter twice")
    void duplicatePathParameterName() {
      assertThatThrownBy(() -> Operation.builder(
          new OperationMetadata(HttpMethod.GET, "/users/{id}/{id}", "u", List.of()))
          .parameter(Parameter.path("id", Schema.integer()))
          .response(ApiResponse.empty("200", "ok"))
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("twice");
    }

    @Test
    @DisplayName("a path template cannot carry a query")
    void queryInPathPattern() {
      assertThatThrownBy(() -> Operation.builder(
          new OperationMetadata(HttpMethod.GET, "/users?q=1", "u", List.of()))
          .response(ApiResponse.empty("200", "ok"))
          .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not contain \"?\"");
    }

    @Test
    @DisplayName("responses keep declaration order through the builder")
    void responseOrderIsDeclaredOrder() {
      Operation operation = getUser()
          .response(ApiResponse.empty("404", "missing"))
          .response(ApiResponse.empty("200", "ok"))
          .build();

      assertThat(operation.responses().keySet()).containsExactly("404", "200");
    }
  }
}
