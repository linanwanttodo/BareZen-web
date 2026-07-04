package com.barezen.plugins.openapi;

import java.util.Objects;

/**
 * One documented request body — Stage 2.14-B's "request body", again as declared data.
 *
 * <p>The subset documents {@code application/json} bodies only, which mirrors the contract itself: a BareZen
 * handler reads {@code Request.body()} as bytes and the framework's content rules (ADR-0029's framing, ADR-0031's
 * 413) are body-format-agnostic — a server that accepts JSON, form data, or a file upload is the same
 * {@code byte[]} underneath. The *document*, however, may only promise what the model can express, so the media
 * type is a single fixed string here rather than a map this phase has no use for. A second media type enters with
 * a real consumer and a revision (ADR-0043).
 *
 * <p>{@code required} is the payload question the specification asks separately from the schema's own
 * required-fields list: "must a body be present at all" vs "which fields must the body carry". Keeping them on
 * different types (this flag vs {@link Schema.Property#required()}) is what stops a document that says "body
 * optional" and "every field mandatory" from being silently contradictory — the combination is legal here because
 * the second only applies when the first is satisfied.
 */
public record RequestBody(String description, Schema schema, boolean required) {

  /** The one media type this subset describes. */
  public static final String JSON_MEDIA_TYPE = "application/json";

  public RequestBody {
    Objects.requireNonNull(schema, "schema");
    if (description != null && description.isBlank()) {
      throw new IllegalArgumentException("a request body description, if declared, must not be blank");
    }
  }

  public static RequestBody json(Schema schema, boolean required) {
    return new RequestBody(null, schema, required);
  }

  @Override
  public String toString() {
    return JSON_MEDIA_TYPE + (required ? " (required)" : "");
  }
}
