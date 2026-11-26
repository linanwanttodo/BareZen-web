package com.barezen.plugins.openapi;

import java.util.List;
import java.util.Objects;

/**
 * A declared security scheme and its use on one operation — Stage 2.14-A's "security" concept, in exactly the
 * shape the framework's own security model justifies: one {@code http/bearer} scheme, because ADR-0036 made the
 * session a bearer token and ADR-0037 made the middleware answer 401 for anonymous and 403 for denied. Documenting
 * an {@code oauth2} or {@code apiKey} flow the framework does not implement would be a spec that promises a login
 * the server cannot answer — the same "a spec that lies" the duplicate-operation refusal guards, applied to
 * security.
 *
 * <p>Two types live here on purpose. {@link Scheme} is the reusable declaration that goes in the document's
 * {@code components}; {@link Requirement} is the per-operation statement that the scheme named in {@code Scheme}
 * is needed. The pairing is checked by the generator against the schemes actually
 * declared: a {@link Requirement} naming an undeclared scheme is the "invalid security requirement" mutation
 * Stage 2.14-G lists, refused at document build rather than emitted. This subset supports only HTTP bearer, whose
 * OpenAPI scopes array must be empty.
 */
public final class Security {

  private Security() {}

  /** The bearer scheme this framework can honestly describe. */
  public static final class Scheme {
    private final String name;

    private Scheme(String name) {
      this.name = name;
    }

    /** Declares the bearer scheme under {@code name}, referenced by {@link Requirement}s and the document. */
    public static Scheme bearer(String name) {
      Objects.requireNonNull(name, "name");
      if (name.isBlank()) {
        throw new IllegalArgumentException("scheme name must not be blank");
      }
      return new Scheme(name);
    }

    public String name() {
      return name;
    }

    @Override
    public String toString() {
      return "bearer:" + name;
    }
  }

  /** One operation's security requirement: the named bearer scheme. OAuth scopes are outside this subset. */
  public static final class Requirement {
    private final String schemeName;
    private final List<String> scopes;

    private Requirement(String schemeName, List<String> scopes) {
      this.schemeName = schemeName;
      this.scopes = List.copyOf(scopes);
    }

    /** The requirement "present a token under this scheme"; the scopes list must be empty for HTTP bearer. */
    public static Requirement of(String schemeName, List<String> scopes) {
      Objects.requireNonNull(schemeName, "schemeName");
      if (schemeName.isBlank()) {
        throw new IllegalArgumentException("scheme name must not be blank");
      }
      Objects.requireNonNull(scopes, "scopes; use List.of() for none");
      if (!scopes.isEmpty()) {
        throw new IllegalArgumentException("a bearer security requirement must have an empty scopes list; OpenAPI"
            + " 3.0.3 permits scope names only for oauth2 or openIdConnect schemes");
      }
      return new Requirement(schemeName, scopes);
    }

    public String schemeName() {
      return schemeName;
    }

    public List<String> scopes() {
      return scopes;
    }

    @Override
    public String toString() {
      return schemeName + scopes;
    }
  }
}
