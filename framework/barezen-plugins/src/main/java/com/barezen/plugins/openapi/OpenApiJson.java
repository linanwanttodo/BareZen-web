package com.barezen.plugins.openapi;

import java.util.List;
import java.util.Map;

/**
 * The deterministic projection from {@link ApiDocument} to OpenAPI 3.0.3 JSON text.
 *
 * <p>One rule governs this class: <b>it renders the document's order and adds nothing.</b> Every collection it
 * walks — paths, operations, responses, parameters, security requirements, schemas, servers, tags — arrives
 * already ordered by {@link ApiDocument} and {@link Operation}, so the only degrees of freedom left here are
 * the key order within each emitted object, which is fixed by the sequence of calls below and nothing else.
 * Two builds of the same declarations therefore produce byte-identical text, which is the Stage 2.14-D promise;
 * if this class ever sorted, hashed, or branched on anything but the model, that promise would die here first.
 *
 * <p>Key order inside an object follows the specification's own field order (a human diffing two documents
 * reads them top-down), with two subset choices recorded in ADR-0043: {@code required} is emitted from
 * {@link Schema#requiredNames()} rather than stored, and a REFERENCE schema emits exactly {@code $ref} — no
 * siblings, because the target states its own shape (the same rule {@link Schema}'s construction enforces).
 *
 * <p>Package-private: the public surface is the document ({@link ApiDocument#toJson()}); the bytes protocol is
 * an implementation detail no application should depend on (ADR-0043).
 */
final class OpenApiJson {

  private OpenApiJson() {}

  static String render(ApiDocument document) {
    JsonWriter w = new JsonWriter();
    w.objectStart();
    w.field("openapi").string(ApiDocument.SPEC_VERSION);

    w.field("info").objectStart();
    w.field("title").string(document.title());
    w.field("version").string(document.version());
    w.objectEnd();

    if (!document.servers().isEmpty()) {
      w.field("servers").arrayStart();
      for (String url : document.servers()) {
        w.objectStart().field("url").string(url).objectEnd();
      }
      w.arrayEnd();
    }

    w.field("paths").objectStart();
    for (Map.Entry<String, List<Operation>> path : document.paths().entrySet()) {
      w.field(path.getKey()).objectStart();
      for (Operation operation : path.getValue()) {
        w.field(operation.method().text().toLowerCase(java.util.Locale.ROOT));
        renderOperation(w, operation);
      }
      w.objectEnd();
    }
    w.objectEnd();

    if (!document.schemas().isEmpty() || !document.securitySchemes().isEmpty()) {
      w.field("components").objectStart();
      if (!document.schemas().isEmpty()) {
        w.field("schemas").objectStart();
        for (Map.Entry<String, Schema> component : document.schemas().entrySet()) {
          w.field(component.getKey());
          renderSchema(w, component.getValue());
        }
        w.objectEnd();
      }
      if (!document.securitySchemes().isEmpty()) {
        w.field("securitySchemes").objectStart();
        for (Map.Entry<String, Security.Scheme> scheme : document.securitySchemes().entrySet()) {
          // the one scheme this subset can honestly describe (ADR-0042's security note; ADR-0036's bearer session)
          w.field(scheme.getKey()).objectStart();
          w.field("type").string("http");
          w.field("scheme").string("bearer");
          w.objectEnd();
        }
        w.objectEnd();
      }
      w.objectEnd();
    }

    if (!document.tags().isEmpty()) {
      // tags as labels only: the spec's Tag Object can carry description and externalDocs, and a generator that
      // emitted an empty description field would be inventing one (ADR-0043's subset boundary)
      w.field("tags").arrayStart();
      for (String tag : document.tags()) {
        w.objectStart().field("name").string(tag).objectEnd();
      }
      w.arrayEnd();
    }

    w.objectEnd();
    return w.finish();
  }

  private static void renderOperation(JsonWriter w, Operation operation) {
    w.objectStart();
    w.field("operationId").string(ApiDocument.operationId(operation));
    w.field("summary").string(operation.metadata().summary());
    if (!operation.metadata().tags().isEmpty()) {
      w.field("tags").arrayStart();
      operation.metadata().tags().forEach(w::string);
      w.arrayEnd();
    }
    if (!operation.parameters().isEmpty()) {
      w.field("parameters").arrayStart();
      for (Parameter parameter : operation.parameters()) {
        renderParameter(w, parameter);
      }
      w.arrayEnd();
    }
    if (operation.requestBody() != null) {
      RequestBody body = operation.requestBody();
      w.field("requestBody").objectStart();
      if (body.description() != null) {
        w.field("description").string(body.description());
      }
      w.field("required").bool(body.required());
      w.field("content").objectStart();
      w.field(RequestBody.JSON_MEDIA_TYPE).objectStart();
      w.field("schema");
      renderSchema(w, body.schema());
      w.objectEnd();
      w.objectEnd();
      w.objectEnd();
    }
    if (!operation.security().isEmpty()) {
      w.field("security").arrayStart();
      for (Security.Requirement requirement : operation.security()) {
        w.objectStart();
        w.field(requirement.schemeName()).arrayStart();
        for (String scope : requirement.scopes()) {
          w.string(scope);
        }
        w.arrayEnd();
        w.objectEnd();
      }
      w.arrayEnd();
    }
    w.field("responses").objectStart();
    for (Map.Entry<String, ApiResponse> response : operation.responses().entrySet()) {
      w.field(response.getKey()).objectStart();
      w.field("description").string(response.getValue().description());
      if (response.getValue().bodySchema() != null) {
        w.field("content").objectStart();
        w.field(RequestBody.JSON_MEDIA_TYPE).objectStart();
        w.field("schema");
        renderSchema(w, response.getValue().bodySchema());
        w.objectEnd();
        w.objectEnd();
      }
      w.objectEnd();
    }
    w.objectEnd();
    w.objectEnd();
  }

  private static void renderParameter(JsonWriter w, Parameter parameter) {
    w.objectStart();
    w.field("name").string(parameter.name());
    w.field("in").string(parameter.location().text());
    w.field("required").bool(parameter.required());
    if (parameter.description() != null) {
      w.field("description").string(parameter.description());
    }
    w.field("schema");
    renderSchema(w, parameter.schema());
    w.objectEnd();
  }

  /**
   * Emits one schema node. A REFERENCE prints its {@code $ref} name and stops — the node is one string deep
   * however cyclic the graph behind it is, which is Schema's no-reflection cycle-safety promise made visible in
   * the emitter (ADR-0043); nothing in this method recurses through a name.
   */
  private static void renderSchema(JsonWriter w, Schema schema) {
    if (schema.type() == Schema.SchemaType.REFERENCE) {
      w.objectStart();
      w.field("$ref").string("#/components/schemas/" + schema.refName());
      w.objectEnd();
      return;
    }
    w.objectStart();
    w.field("type").string(schema.type().text());
    if (schema.format() != null) {
      w.field("format").string(schema.format());
    }
    if (schema.type() == Schema.SchemaType.OBJECT && !schema.properties().isEmpty()) {
      w.field("properties").objectStart();
      for (Schema.Property property : schema.properties()) {
        w.field(property.name());
        renderSchema(w, property.schema());
      }
      w.objectEnd();
      List<String> required = schema.requiredNames();
      if (!required.isEmpty()) {
        w.field("required").arrayStart();
        required.forEach(w::string);
        w.arrayEnd();
      }
    }
    if (schema.type() == Schema.SchemaType.ARRAY) {
      w.field("items");
      renderSchema(w, schema.items());
    }
    if (schema.nullable()) {
      w.field("nullable").bool(true);
    }
    w.objectEnd();
  }
}
