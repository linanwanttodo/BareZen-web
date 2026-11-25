package com.barezen.console;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The query string, read by the application because the contract hands it over raw.
 *
 * <p>{@code Request.query()} is an {@link Optional} of the uninterpreted text, and absence is a different fact from an
 * empty string (ADR-0018 §4: a query is not a path, and a framework that decoded eagerly would decide for the
 * application what an absent {@code ?q} means). So a consumer that filters parses its own parameters, which is what this
 * is: the small parser that turns {@code q=ada&limit=5} into a map, plus the two defaults and the refusals the reports
 * list needs. Malformed percent escapes are a client-input refusal, not a server exception.
 */
final class Query {

  /** The list size a caller may ask for, and the most the server will return however hard it is asked. */
  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 100;

  private final Map<String, String> values;
  private final boolean refused;

  private Query(Map<String, String> values, boolean refused) {
    this.values = values;
    this.refused = refused;
  }

  /**
   * Parse a raw query, distinguishing "no query at all" from "a query with nothing in it".
   *
   * @param raw the text {@code Request.query()} carried, or empty when the request had no {@code ?} at all
   */
  static Query of(Optional<String> raw) {
    Map<String, String> parsed = new LinkedHashMap<>();
    try {
      // An absent query and an empty one arrive as different Optionals; both mean "no filters", and that is the only
      // reading worth having, so they collapse here rather than in every handler.
      raw.filter(text -> !text.isEmpty()).ifPresent(text -> {
        for (String pair : text.split("&")) {
          int separator = pair.indexOf('=');
          if (separator > 0) {
            parsed.put(decode(pair.substring(0, separator)), decode(pair.substring(separator + 1)));
          } else if (!pair.isEmpty()) {
            parsed.put(decode(pair), "");
          }
        }
      });
      return new Query(Map.copyOf(parsed), false);
    } catch (IllegalArgumentException malformed) {
      return new Query(Map.of(), true);
    }
  }

  boolean refused() {
    return refused;
  }

  private static String decode(String text) {
    return URLDecoder.decode(text, StandardCharsets.UTF_8);
  }

  Optional<String> value(String name) {
    return Optional.ofNullable(values.get(name));
  }

  /**
   * An integer parameter with a bound, or a refusal the caller can tell the difference about.
   *
   * <p>{@code null} means "use the default"; a failure is reported rather than clamped, because silently turning
   * {@code limit=abc} into 20 is a server deciding what a malformed client request meant.
   */
  static Number read(String name, Optional<String> raw, int fallback, int maximum) {
    if (raw.isEmpty() || raw.get().isEmpty()) {
      return new Number(fallback, null);
    }
    try {
      int parsed = Integer.parseInt(raw.get().trim());
      if (parsed < 0) {
        return new Number(fallback, name + " must not be negative");
      }
      return new Number(Math.min(parsed, maximum), null);
    } catch (NumberFormatException malformed) {
      return new Number(fallback, name + " must be a number; got \"" + raw.get() + "\"");
    }
  }

  /** A number the caller can use, and the reason it cannot if the answer was malformed. */
  record Number(int value, String refusal) {

    boolean refused() {
      return refusal != null;
    }
  }
}
