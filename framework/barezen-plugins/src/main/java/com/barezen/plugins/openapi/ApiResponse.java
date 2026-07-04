package com.barezen.plugins.openapi;

import java.util.Objects;

/**
 * One documented response: a status code, a description, and the schema of the body the server sends —
 * Stage 2.14-B's "response body" and "status code" facts, declared rather than invented.
 *
 * <p>The status code is text, and the accepted shapes are the ones the specification's Responses Object defines:
 * a three-digit code ({@code "200"}), a wildcard range ({@code "2XX"}), or the one literal {@code "default"}.
 * Text rather than an int because {@code "2XX"} is a legal answer an int cannot hold, and because the router
 * layer (ADR-0029's framing work) treats codes as three-digit bytes end to end — the document stays honest by
 * keeping the same shape as the transport.
 *
 * <p>{@code bodySchema == null} means "this response has no body", and {@code 204}/{@code 304} are refused a
 * schema rather than quietly emitting one: a document that describes bytes for a response the HTTP contract
 * forbids (ADR-0029's decision table) is the invalid-response case Stage 2.14-F names, caught here at the point
 * of declaration instead of in a generated artifact nobody reviewed.
 */
public record ApiResponse(String statusCode, String description, Schema bodySchema) {

  public ApiResponse {
    Objects.requireNonNull(statusCode, "statusCode");
    // Required, not optional: the specification's Response Object states that description IS required, and a
    // model that can hold null would force the emitter to invent the one string this record exists to carry.
    Objects.requireNonNull(description, "description; a documented response that says what it means is the"
        + " difference between a status code and documentation");
    if (!statusCode.matches("([1-5]\\d{2}|[1-5]XX|default)")) {
      throw new IllegalArgumentException("status code \"" + statusCode
          + "\" is outside the subset: a three-digit code, an NXX range, or \"default\"");
    }
    if (statusCode.equals("204") || statusCode.equals("304")) {
      if (bodySchema != null) {
        throw new IllegalArgumentException("response " + statusCode
            + " declares a body schema; the framing contract (ADR-0029) forbids a body there, and a document"
            + " describing one teaches clients to parse bytes that never arrive");
      }
    }
    if (description != null && description.isBlank()) {
      throw new IllegalArgumentException("a response description, if declared, must not be blank");
    }
  }

  /** A documented response with a JSON body of this schema. */
  public static ApiResponse json(String statusCode, String description, Schema bodySchema) {
    return new ApiResponse(statusCode, description, bodySchema);
  }

  /** A documented response with no body — {@code 204}, {@code 304}, or an empty {@code 200}. */
  public static ApiResponse empty(String statusCode, String description) {
    return new ApiResponse(statusCode, description, null);
  }

  @Override
  public String toString() {
    return statusCode;
  }
}
