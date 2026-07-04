package com.barezen.plugins.openapi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A complete, checked API description — the specification model Stage 2.14-A names, in the subset the framework
 * can honestly fill: {@code openapi} version, {@code info}, {@code servers}, {@code paths} (with operations,
 * parameters, request bodies, responses and security), {@code components} (schemas and security schemes), and
 * {@code tags} as the collected labels of the operations. The full 3.0.3 grammar is not the goal; the phase's own
 * warning is that «不要为了"完整 OpenAPI"无限扩张», and every concept on this list is one Stage 2.14-A names by hand.
 *
 * <p><b>Validation is build-time, and it is the six refusal categories of Stage 2.14-F.</b> A document either
 * describes an API or does not exist: {@code info} fields that are blank, an (method, path) registered twice
 * across operations, an operationId collision, a {@code $ref} to a component the document never declared, a
 * security requirement naming a scheme never declared, and an operation whose responses carry none of the
 * {@code 2XX}/{@code 4XX}/{@code 5XX}/{@code default} honesty (already refused per-response in
 * {@link ApiResponse}). These are refusals, not warnings, because a published spec document is read by clients
 * and tools that cannot see a warning — the only contract a generator can keep is "the bytes that exist
 * describe a valid document".
 *
 * <p><b>Order is decided once, here, deterministically:</b> paths sorted by their template text, operations
 * within a path sorted by method text, component schemas sorted by name, security schemes by name, tags sorted
 * and de-duplicated, servers kept in declaration order (they are an ordered preference, per the specification).
 * {@link OpenApiJson} renders this object's order and adds nothing; feeding the same application's declarations
 * — in any assembly order — produces byte-identical JSON, which is the Stage 2.14-D promise this type owns.
 */
public final class ApiDocument {

  /** The exact document shape this subset emits: OpenAPI 3.0.3, whose version string is part of the output. */
  public static final String SPEC_VERSION = "3.0.3";

  private final String title;
  private final String version;
  private final List<String> servers;
  private final Map<String, List<Operation>> paths; // sorted template -> sorted operations
  private final Map<String, Schema> schemas; // sorted component name -> schema
  private final Map<String, Security.Scheme> securitySchemes;
  private final List<String> tags;

  private ApiDocument(Builder builder) {
    this.title = builder.title;
    this.version = builder.version;
    this.servers = List.copyOf(builder.servers);
    this.schemas = sortedByName(builder.schemas);
    this.securitySchemes = sortedByName(builder.securitySchemes);
    this.tags = builder.operations.stream()
        .flatMap(operation -> operation.metadata().tags().stream())
        .distinct()
        .sorted()
        .toList();

    Map<String, List<Operation>> byPath = new LinkedHashMap<>();
    for (Operation operation : builder.operations) {
      byPath.computeIfAbsent(operation.path(), ignored -> new ArrayList<>()).add(operation);
    }
    Map<String, List<Operation>> paths = new LinkedHashMap<>();
    byPath.keySet().stream().sorted().forEach(path -> paths.put(path,
        byPath.get(path).stream()
            .sorted(Comparator.comparing(operation -> operation.method().text()))
            .toList()));
    this.paths = java.util.Collections.unmodifiableMap(paths);

    validate(builder);
  }

  private void validate(Builder builder) {
    // Pass 1 — duplicate (method, path): the collector refuses it per-call, and this refuses the document built
    // from two separately-constructed operations that disagree anyway. A full pass before the operationId pass,
    // because two identical templates collide on their derived id too, and "the same operation declared twice"
    // is the truer diagnosis than "two operations share an id".
    Set<String> seen = new java.util.HashSet<>();
    for (Operation operation : builder.operations) {
      String id = operation.method().text() + " " + operation.path();
      if (!seen.add(id)) {
        throw new IllegalArgumentException("the document registers operation " + id
            + " twice; one method and one path is one operation (a duplicated path is a spec that lies)");
      }
    }
    // Pass 2 — derived operationId collisions between *distinct* templates (e.g. /users/{id} vs /users/id).
    Map<String, String> ids = new LinkedHashMap<>();
    for (Operation operation : builder.operations) {
      String id = operation.method().text() + " " + operation.path();
      String previous = ids.putIfAbsent(operationId(operation), id);
      if (previous != null) {
        throw new IllegalArgumentException("operations " + previous + " and " + id
            + " project to the same operationId \"" + operationId(operation)
            + "\"; the collision is in the path templates (e.g. /users/{id} vs /users/id, which differ only in"
            + " the braces the derivation strips), and a client generator keys on this string");
      }
    }
    // Pass 3 — every $ref anywhere in the document, from operation payloads and from component bodies alike,
    // must name a declared component. The walk is recursive because array(ref) and object{...ref} are the
    // common case, not the edge; it stops at a REFERENCE node, so a cyclic component graph cannot loop here.
    for (Operation operation : builder.operations) {
      String site = "operation " + operation;
      for (Parameter parameter : operation.parameters()) {
        requireResolved(parameter.schema(), site);
      }
      if (operation.requestBody() != null) {
        requireResolved(operation.requestBody().schema(), site);
      }
      for (ApiResponse response : operation.responses().values()) {
        if (response.bodySchema() != null) {
          requireResolved(response.bodySchema(), site);
        }
      }
    }
    for (Operation operation : builder.operations) {
      for (Security.Requirement requirement : operation.security()) {
        if (!securitySchemes.containsKey(requirement.schemeName())) {
          throw new IllegalArgumentException("operation " + operation + " requires security scheme \""
              + requirement.schemeName() + "\" which the document never declared; an invalid security"
              + " requirement is refused at build, not published (Stage 2.14-F)");
        }
      }
    }
    for (Map.Entry<String, Schema> component : schemas.entrySet()) {
      requireResolved(component.getValue(), "component schema \"" + component.getKey() + "\"");
    }
  }

  private void requireResolved(Schema schema, String site) {
    if (schema.type() == Schema.SchemaType.REFERENCE) {
      if (!schemas.containsKey(schema.refName())) {
        throw new IllegalArgumentException(site + " references \"" + schema.refName()
            + "\" which the document never declared; an invalid schema reference cannot ship, because every"
            + " consumer that resolves $ref would resolve nothing (Stage 2.14-F)");
      }
      return; // a reference carries nothing but its name; the target is validated as its own root
    }
    schema.properties().forEach(property -> requireResolved(property.schema(), site));
    if (schema.items() != null) {
      requireResolved(schema.items(), site);
    }
  }

  /**
   * The stable, derived operation id: method + path with its braces removed, camel-cased
   * ({@code GET /users/{id}} → {@code getUsersId}). Derived rather than declared because a declared id is one
   * more thing that can collide silently, and the path is already required to be unique per method.
   */
  public static String operationId(Operation operation) {
    StringBuilder id = new StringBuilder(operation.method().text().toLowerCase(Locale.ROOT));
    for (String segment : operation.path().split("/")) {
      if (segment.isEmpty()) {
        continue;
      }
      String word = segment.startsWith("{") ? segment.substring(1, segment.length() - 1) : segment;
      id.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    return id.toString();
  }

  public String title() {
    return title;
  }

  public String version() {
    return version;
  }

  public List<String> servers() {
    return servers;
  }

  /** Path templates in sorted order, each with its operations sorted by method. */
  public Map<String, List<Operation>> paths() {
    return paths;
  }

  public Map<String, Schema> schemas() {
    return schemas;
  }

  public Map<String, Security.Scheme> securitySchemes() {
    return securitySchemes;
  }

  public List<String> tags() {
    return tags;
  }

  /** Renders this document as the deterministic JSON text (see {@link OpenApiJson}). */
  public String toJson() {
    return OpenApiJson.render(this);
  }

  /**
   * A route handler serving exactly this document's bytes; the application mounts it on its own router
   * ({@code route(GET, "/openapi.json", document.handler())}), which keeps the document inside the framework's
   * one request path rather than inventing a second serving mechanism (ADR-0016's extension point rule).
   */
  public com.barezen.web.Handler handler() {
    String json = toJson(); // rendered once, at assembly; every request answers the same bytes
    byte[] body = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return (request, pathParameters) -> new com.barezen.web.Response(
        200, com.barezen.web.Headers.of("Content-Type", RequestBody.JSON_MEDIA_TYPE), body);
  }

  private static <T> Map<String, T> sortedByName(Map<String, T> source) {
    Map<String, T> sorted = new LinkedHashMap<>();
    source.keySet().stream().sorted().forEach(name -> sorted.put(name, source.get(name)));
    return java.util.Collections.unmodifiableMap(sorted);
  }

  /** Assembles a document; every refusal above fires from {@link #build()}. */
  public static final class Builder {

    private String title;
    private String version;
    private final List<String> servers = new ArrayList<>();
    private final List<Operation> operations = new ArrayList<>();
    private final Map<String, Schema> schemas = new LinkedHashMap<>();
    private final Map<String, Security.Scheme> securitySchemes = new LinkedHashMap<>();

    private Builder() {}

    public Builder info(String title, String version) {
      this.title = Objects.requireNonNull(title, "title");
      this.version = Objects.requireNonNull(version, "version");
      return this;
    }

    /** Appends one server URL, in preference order (the specification's own rule for {@code servers}). */
    public Builder server(String url) {
      Objects.requireNonNull(url, "url");
      if (url.isBlank() || url.chars().anyMatch(Character::isWhitespace)) {
        throw new IllegalArgumentException("server URL \"" + url
            + "\" must be non-blank and contain no whitespace; it is emitted inside a JSON string as-is");
      }
      servers.add(url);
      return this;
    }

    public Builder operation(Operation operation) {
      Objects.requireNonNull(operation, "operation");
      operations.add(operation);
      return this;
    }

    public Builder operations(List<Operation> declared) {
      Objects.requireNonNull(declared, "declared");
      declared.forEach(this::operation);
      return this;
    }

    /** Declares a named component schema; {@code Schema.ref(name)} targets resolve against these. */
    public Builder schema(String name, Schema schema) {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(schema, "schema");
      if (name.isBlank()) {
        throw new IllegalArgumentException("component schema name must not be blank");
      }
      Schema previous = schemas.putIfAbsent(name, schema);
      if (previous != null) {
        throw new IllegalArgumentException("component schema \"" + name + "\" declared twice; a second definition"
            + " for one name is the duplicate-path bug one level down, and $ref could resolve either");
      }
      return this;
    }

    public Builder securityScheme(Security.Scheme scheme) {
      Objects.requireNonNull(scheme, "scheme");
      Security.Scheme previous = securitySchemes.putIfAbsent(scheme.name(), scheme);
      if (previous != null) {
        throw new IllegalArgumentException("security scheme \"" + scheme.name() + "\" declared twice");
      }
      return this;
    }

    public ApiDocument build() {
      if (title == null || title.isBlank()) {
        throw new IllegalArgumentException("document info.title is required and must not be blank;"
            + " a spec with no name describes no API (Stage 2.14-F: missing required field)");
      }
      if (version == null || version.isBlank()) {
        throw new IllegalArgumentException("document info.version is required and must not be blank; clients"
            + " cache on it (Stage 2.14-F: missing required field)");
      }
      return new ApiDocument(this);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String toString() {
    return "ApiDocument[" + paths.size() + " paths, " + schemas.size() + " schemas, OpenAPI " + SPEC_VERSION + "]";
  }
}
