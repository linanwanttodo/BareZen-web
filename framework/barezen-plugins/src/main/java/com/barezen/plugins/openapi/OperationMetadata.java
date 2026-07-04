package com.barezen.plugins.openapi;

import com.barezen.web.HttpMethod;
import java.util.List;
import java.util.Objects;

/**
 * What one route contributes to an API description: the operation. This is the *metadata*, not a document — 2.13
 * records it, 2.14 projects it into a specification, and the split is Stage 2.13-E's boundary ("不得直接生成完整文档").
 *
 * <p>A plain immutable value with no framework behaviour, which is the honest shape for a phase that has no
 * generator yet: nothing here knows JSON, OpenAPI's grammar, or HTML, so there is no half-written serializer
 * pretending to be a model. It carries the three facts Stage 2.13-E names — the route (method + path), a summary,
 * and tags — and validates them at construction so a malformed operation is refused where it is written, not where
 * a later generator would crash on it.
 *
 * @param method the HTTP method the operation answers
 * @param path the route pattern, e.g. {@code /users/{id}} — the shape, not one expansion of it
 * @param summary a one-line human description, never blank
 * @param tags classification labels, copied and frozen; empty is legal (an untagged operation)
 */
public record OperationMetadata(HttpMethod method, String path, String summary, List<String> tags) {

  public OperationMetadata {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(summary, "summary; an operation that says nothing is an operation nobody can read");
    if (path.isBlank() || !path.startsWith("/")) {
      throw new IllegalArgumentException("path must be non-blank and start with '/', got \"" + path + "\"");
    }
    if (summary.isBlank()) {
      throw new IllegalArgumentException("summary must not be blank");
    }
    Objects.requireNonNull(tags, "tags; use List.of() for none");
    for (String tag : tags) {
      if (tag == null || tag.isBlank()) {
        throw new IllegalArgumentException("a tag must be non-blank; a blank tag is a placeholder someone forgot"
            + " to fill, and it would render as an empty group");
      }
    }
    tags = List.copyOf(tags);
  }

  @Override
  public String toString() {
    return method.text() + " " + path; // the route identity only; summary and tags are for the reader of a spec
  }
}
