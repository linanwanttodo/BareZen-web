package com.barezen.plugins.openapi;

import java.util.Objects;

/**
 * One documented input to an operation, in the subset's three locations Stage 2.14-B names: {@code path},
 * {@code query}, {@code header}. (The spec's fourth location, {@code cookie}, is a deliberate non-goal — the
 * framework's own security design puts identity in a bearer header (ADR-0036), and shipping a location no
 * BareZen application answers is the "infinite OpenAPI" the phase warns about.)
 *
 * <p>A parameter is declared data, the same principle as {@link Schema} and {@link OperationMetadata}: the route
 * pattern {@code /users/{id}} already states that {@code id} is a path parameter, and the web contract's raw
 * query string states nothing about which names a handler reads — that knowledge lives in the handler's code,
 * above the framework, exactly where an application can write it down. The generator therefore asks rather than
 * guesses, and the two construction-time refusals are the phase's mutation targets made unreachable at the
 * source: a path parameter whose name is not in the path template the operation was built from, and a parameter
 * marked not-required inside a path — because the Router matches exactly one segment per parameter and an
 * optional path parameter is a parameter that may be absent, which the contract has no way to express.
 */
public record Parameter(String name, Location location, Schema schema, boolean required, String description) {

  /** Where the parameter arrives, in the subset's three locations. */
  public enum Location {
    PATH("path"), QUERY("query"), HEADER("header");

    private final String text;

    Location(String text) {
      this.text = text;
    }

    public String text() {
      return text;
    }
  }

  public Parameter {
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("parameter name must not be blank");
    }
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(schema, "schema");
    if (location == Location.PATH && !required) {
      throw new IllegalArgumentException("path parameter '" + name
          + "' is declared optional; the router matches one segment per parameter, so an absent path parameter"
          + " is a different route, not an optional field of this one");
    }
  }

  /** The required path parameter {@code name}, typed as {@code schema}. */
  public static Parameter path(String name, Schema schema) {
    return new Parameter(name, Location.PATH, schema, true, null);
  }

  public static Parameter query(String name, Schema schema, boolean required) {
    return new Parameter(name, Location.QUERY, schema, required, null);
  }

  public static Parameter header(String name, Schema schema, boolean required) {
    return new Parameter(name, Location.HEADER, schema, required, null);
  }

  @Override
  public String toString() {
    return location.text() + ":" + name;
  }
}
