package com.barezen.plugins.openapi;

import com.barezen.web.HttpMethod;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One operation in the specification: the {@link OperationMetadata} the {@link OpenApiPlugin} collected, enriched
 * with the parts the collector deliberately does not know — parameters, request body, responses, security. This is
 * Stage 2.14-B's projection, and it is a <em>one-way</em> projection by construction: an operation is built
 * <em>from</em> a metadata record, and nothing here can flow back into the plugin's collection. A generator that
 * could edit the collector would let documentation silently redefine the declared API surface; the constructor
 * refusing a (method, path) that disagrees with its metadata source is the seam.
 *
 * <p>Responses and parameters are ordered as declared and preserved in declaration order through generation — the
 * same determinism stance as {@link OpenApiPlugin#operations()} and {@link Schema}: the application's assembly
 * code is the order, and the document reports it instead of sorting something out of a hash. The response codes
 * are checked for uniqueness here ({@code "200"} twice is the duplicate-operation bug one level down) and the
 * path-parameter cross-check against the route pattern happens in {@link Builder#build()}, because that is where
 * the pattern and the parameter list are finally both visible.
 */
public final class Operation {

  private final OperationMetadata metadata;
  private final List<Parameter> parameters;
  private final RequestBody requestBody;
  private final Map<String, ApiResponse> responses;
  private final List<Security.Requirement> security;

  private Operation(Builder builder) {
    this.metadata = builder.metadata;
    this.parameters = List.copyOf(builder.parameters);
    this.requestBody = builder.requestBody;
    // unmodifiable, not Map.copyOf: the response order the caller declared is the order the document prints,
    // and a copyOf HashMap would scramble exactly the determinism this class promises.
    this.responses = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(builder.responses));
    this.security = List.copyOf(builder.security);
  }

  /** The route identity, summary and tags this operation was built from — kept, so a document cites its source. */
  public OperationMetadata metadata() {
    return metadata;
  }

  public List<Parameter> parameters() {
    return parameters;
  }

  /** The documented body, or {@code null} when the operation declares none. */
  public RequestBody requestBody() {
    return requestBody;
  }

  /** The documented responses by status code, in declaration order. */
  public Map<String, ApiResponse> responses() {
    return responses;
  }

  public List<Security.Requirement> security() {
    return security;
  }

  public HttpMethod method() {
    return metadata.method();
  }

  public String path() {
    return metadata.path();
  }

  public static Builder builder(OperationMetadata metadata) {
    return new Builder(metadata);
  }

  @Override
  public String toString() {
    return metadata.toString();
  }

  /** Fluent builder; {@link #build()} runs the cross-field checks no single record can see. */
  public static final class Builder {

    private final OperationMetadata metadata;
    private final List<Parameter> parameters = new ArrayList<>();
    private final Map<String, ApiResponse> responses = new LinkedHashMap<>();
    private final List<Security.Requirement> security = new ArrayList<>();
    private RequestBody requestBody;

    private Builder(OperationMetadata metadata) {
      this.metadata = Objects.requireNonNull(metadata,
          "metadata; an Operation is built from what the OpenApiPlugin collected, never from a route alone");
    }

    public Builder parameter(Parameter parameter) {
      Objects.requireNonNull(parameter, "parameter");
      parameters.add(parameter);
      return this;
    }

    public Builder parameters(List<Parameter> declared) {
      Objects.requireNonNull(declared, "declared");
      declared.forEach(this::parameter);
      return this;
    }

    public Builder requestBody(RequestBody body) {
      Objects.requireNonNull(body, "body");
      if (requestBody != null) {
        throw new IllegalStateException("request body declared twice for " + metadata);
      }
      requestBody = body;
      return this;
    }

    /** Adds one response; the first declaration for a code wins the right to error — the second is refused. */
    public Builder response(ApiResponse response) {
      Objects.requireNonNull(response, "response");
      ApiResponse previous = responses.putIfAbsent(response.statusCode(), response);
      if (previous != null) {
        throw new IllegalArgumentException("operation " + metadata + " documents status "
            + response.statusCode() + " twice; one code is one response");
      }
      return this;
    }

    public Builder responses(List<ApiResponse> declared) {
      Objects.requireNonNull(declared, "declared");
      declared.forEach(this::response);
      return this;
    }

    public Builder security(Security.Requirement requirement) {
      Objects.requireNonNull(requirement, "requirement");
      security.add(requirement);
      return this;
    }

    /**
     * Verifies the whole: at least one response (a documented operation that promises no answer is the missing
     * response field Stage 2.14-F names), every path parameter of the pattern declared and typed as a string-ish
     * primitive, and no parameter name claimed twice or placed outside a pattern that has it.
     */
    public Operation build() {
      if (responses.isEmpty()) {
        throw new IllegalArgumentException("operation " + metadata + " documents no response; an operation"
            + " that promises no answer describes a route nobody can call");
      }
      List<String> patternNames = pathParameterNames(metadata.path());
      for (Parameter parameter : parameters) {
        if (parameter.location() == Parameter.Location.PATH) {
          if (!patternNames.contains(parameter.name())) {
            throw new IllegalArgumentException("operation " + metadata + " declares path parameter '"
                + parameter.name() + "' which the pattern does not contain; the wrong-path-parameter mutation"
                + " Stage 2.14-G names is refused at the source, not emitted and found in review");
          }
          if (parameter.schema().type() != Schema.SchemaType.STRING
              && parameter.schema().type() != Schema.SchemaType.INTEGER) {
            throw new IllegalArgumentException("path parameter '" + parameter.name() + "' is typed "
                + parameter.schema() + "; the router captures raw segments, so a path value is text, and the only"
                + " honest schema types are string and integer (which a client may parse)");
          }
        }
        if (parameter.location() != Parameter.Location.PATH && patternNames.contains(parameter.name())) {
          throw new IllegalArgumentException("parameter '" + parameter.name() + "' is declared as "
              + parameter.location().text() + " but the pattern reserves it as a path parameter");
        }
      }
      for (String name : patternNames) {
        if (parameters.stream().noneMatch(p ->
            p.location() == Parameter.Location.PATH && p.name().equals(name))) {
          throw new IllegalArgumentException("operation " + metadata + " has path parameter '{" + name
              + "}' in its pattern but declares none; the wrong-path-parameter pair closes on the missing side too");
        }
      }
      return new Operation(this);
    }

    /** The capture names {@code /users/{id}/orders/{oid}} contributes: {@code [id, oid]}, in order. */
    private static List<String> pathParameterNames(String pattern) {
      List<String> names = new ArrayList<>();
      int i = 0;
      while (i < pattern.length()) {
        int open = pattern.indexOf('{', i);
        if (open < 0) {
          break;
        }
        int close = pattern.indexOf('}', open);
        if (close < 0) {
          throw new IllegalArgumentException("path pattern \"" + pattern
              + "\" has an unclosed '{'; the router's grammar is exactly {name} per parameter segment");
        }
        names.add(pattern.substring(open + 1, close));
        i = close + 1;
      }
      return names;
    }
  }
}
