package com.barezen.console;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON this application writes, written here.
 *
 * <p>The framework has a JSON writer — {@code JsonWriter} in the OpenAPI plugin — and keeps it package-private on
 * purpose: "a public JSON writer is a second API the framework would then owe forever" is that class's own comment, and
 * ADR-0001's single third-party compile dependency means a consumer that returns JSON writes it itself. So this is the
 * whole of it: string escaping, a flat object, and an array of objects. It is not a parser; the one body this
 * application reads is a form, not JSON, and parsing a form is two JDK calls.
 */
final class Json {

  private Json() {
  }

  /** One JSON string literal, with the escapes that matter for a value embedded in a document. */
  static String string(String value) {
    StringBuilder out = new StringBuilder(value.length() + 2).append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          // Control characters are the ones that break a document silently; everything else is UTF-8 as it arrived.
          if (character < 0x20) {
            out.append(String.format("\\u%04x", (int) character));
          } else {
            out.append(character);
          }
        }
      }
    }
    return out.append('"').toString();
  }

  /** A flat {@code {"key":"value", …}} object, in the order given. */
  static String object(Map<String, String> fields) {
    StringBuilder out = new StringBuilder("{");
    fields.forEach((key, value) -> {
      if (out.length() > 1) {
        out.append(',');
      }
      out.append(string(key)).append(':').append(string(value));
    });
    return out.append('}').toString();
  }

  /** An array of already-built JSON objects, so a list of rows reads as a list rather than a blob. */
  static String array(List<String> elements) {
    return "[" + String.join(",", elements) + "]";
  }

  static String field(String name, String value) {
    Map<String, String> one = new LinkedHashMap<>();
    one.put(name, value);
    return object(one);
  }

  /**
   * Read one field out of an {@code application/x-www-form-urlencoded} body.
   *
   * <p>A form rather than JSON because this is what an HTML page has always sent and because the framework has no JSON
   * parser to reach for — a login body parsed by hand would be the beginning of a document model this project does not
   * have and has refused (ADR-0030's same reasoning about bodies). Percent-decoding is the JDK's, not a local guess.
   */
  static String formValue(byte[] body, String name) {
    if (body == null || body.length == 0) {
      return null;
    }
    String text = new String(body, StandardCharsets.UTF_8);
    for (String pair : text.split("&")) {
      int separator = pair.indexOf('=');
      if (separator < 0) {
        continue;
      }
      String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
      if (key.equals(name)) {
        return URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }
}
