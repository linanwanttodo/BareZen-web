package com.barezen.testkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader, package-private, so {@link Assertions} can navigate a response body without a third-party
 * library.
 *
 * <p>This is not a JSON library for applications to use — it is not public and it is not offered as API. It exists for
 * one job: turn a test's expected-shaped JSON into a tree of {@link Value} nodes a test can walk with
 * {@link Value#at(String)}. It is strict about structure and lenient about nothing: malformed input is a parse error,
 * which is what a test wants, because a body that does not parse is a body that fails the assertion.
 *
 * <p>Objects keep their member order ({@link LinkedHashMap}) so a test asserting on order-sensitive rendering — the
 * OpenAPI generator promises byte-deterministic output — sees the order the bytes were in, not a hash order.
 */
final class JsonValue {

  private JsonValue() {}

  /** A parsed JSON value: an object, an array, or a scalar. */
  static final class Value {

    enum Kind {
      OBJECT, ARRAY, STRING, NUMBER, TRUE, FALSE, NULL
    }

    private final Kind kind;
    private final Map<String, Value> object;
    private final List<Value> array;
    private final String text;

    private Value(Kind kind, Map<String, Value> object, List<Value> array, String text) {
      this.kind = kind;
      this.object = object;
      this.array = array;
      this.text = text;
    }

    Kind kind() {
      return kind;
    }

    /**
     * Navigate to a child by dot-separated path, e.g. {@code "info.title"} or {@code "paths./users.get"}. A segment is
     * an object member; arrays are reached by their index as a segment ({@code "tags.0.name"}).
     *
     * @param path the dotted path
     * @return the value at the path, or {@code null} when any segment is missing
     */
    Value at(String path) {
      Value current = this;
      for (String segment : path.split("\\.", -1)) {
        if (current == null) {
          return null;
        }
        if (current.kind == Kind.OBJECT) {
          current = current.object.get(segment);
        } else if (current.kind == Kind.ARRAY) {
          int index;
          try {
            index = Integer.parseInt(segment);
          } catch (NumberFormatException notIndex) {
            return null;
          }
          current = index >= 0 && index < current.array.size() ? current.array.get(index) : null;
        } else {
          return null;
        }
      }
      return current;
    }

    /** @return the string content for a STRING, the literal for a NUMBER, or the keyword for TRUE/FALSE/NULL */
    String asText() {
      return text;
    }

    /** @return the members of an OBJECT, in document order; empty for any other kind */
    Map<String, Value> members() {
      return object == null ? Map.of() : object;
    }

    /** @return the elements of an ARRAY; empty for any other kind */
    List<Value> elements() {
      return array == null ? List.of() : array;
    }

    @Override
    public String toString() {
      return kind + (text == null ? "" : "(" + text + ")");
    }
  }

  /**
   * Parse JSON text into a {@link Value}.
   *
   * @param json the text to parse
   * @return the root value
   * @throws IllegalArgumentException if the text is not valid JSON
   */
  static Value parse(String json) {
    Parser parser = new Parser(json);
    parser.skipWhitespace();
    Value value = parser.parseValue();
    parser.skipWhitespace();
    if (!parser.atEnd()) {
      throw new IllegalArgumentException("trailing characters after JSON value at index " + parser.position);
    }
    return value;
  }

  private static final class Parser {

    private final String input;
    private int position;

    Parser(String input) {
      this.input = input;
    }

    boolean atEnd() {
      return position >= input.length();
    }

    int position() {
      return position;
    }

    void skipWhitespace() {
      while (position < input.length() && Character.isWhitespace(input.charAt(position))) {
        position++;
      }
    }

    Value parseValue() {
      if (atEnd()) {
        throw error("unexpected end of input");
      }
      char c = input.charAt(position);
      switch (c) {
        case '{':
          return parseObject();
        case '[':
          return parseArray();
        case '"':
          return new Value(Value.Kind.STRING, null, null, parseString());
        case 't':
          expectLiteral("true");
          return new Value(Value.Kind.TRUE, null, null, "true");
        case 'f':
          expectLiteral("false");
          return new Value(Value.Kind.FALSE, null, null, "false");
        case 'n':
          expectLiteral("null");
          return new Value(Value.Kind.NULL, null, null, "null");
        default:
          if (c == '-' || (c >= '0' && c <= '9')) {
            return parseNumber();
          }
          throw error("unexpected character '" + c + "'");
      }
    }

    private Value parseObject() {
      position++; // consume {
      Map<String, Value> members = new LinkedHashMap<>();
      skipWhitespace();
      if (peek() == '}') {
        position++;
        return new Value(Value.Kind.OBJECT, members, null, null);
      }
      while (true) {
        skipWhitespace();
        if (peek() != '"') {
          throw error("expected a string key");
        }
        String key = parseString();
        skipWhitespace();
        if (peek() != ':') {
          throw error("expected ':' after key");
        }
        position++;
        skipWhitespace();
        members.put(key, parseValue());
        skipWhitespace();
        char next = peek();
        if (next == ',') {
          position++;
        } else if (next == '}') {
          position++;
          return new Value(Value.Kind.OBJECT, members, null, null);
        } else {
          throw error("expected ',' or '}'");
        }
      }
    }

    private Value parseArray() {
      position++; // consume [
      List<Value> elements = new ArrayList<>();
      skipWhitespace();
      if (peek() == ']') {
        position++;
        return new Value(Value.Kind.ARRAY, null, elements, null);
      }
      while (true) {
        skipWhitespace();
        elements.add(parseValue());
        skipWhitespace();
        char next = peek();
        if (next == ',') {
          position++;
        } else if (next == ']') {
          position++;
          return new Value(Value.Kind.ARRAY, null, elements, null);
        } else {
          throw error("expected ',' or ']'");
        }
      }
    }

    private String parseString() {
      position++; // consume opening quote
      StringBuilder out = new StringBuilder();
      while (true) {
        if (atEnd()) {
          throw error("unterminated string");
        }
        char c = input.charAt(position++);
        if (c == '"') {
          return out.toString();
        }
        if (c == '\\') {
          if (atEnd()) {
            throw error("unterminated escape");
          }
          char esc = input.charAt(position++);
          switch (esc) {
            case '"':
              out.append('"');
              break;
            case '\\':
              out.append('\\');
              break;
            case '/':
              out.append('/');
              break;
            case 'b':
              out.append('\b');
              break;
            case 'f':
              out.append('\f');
              break;
            case 'n':
              out.append('\n');
              break;
            case 'r':
              out.append('\r');
              break;
            case 't':
              out.append('\t');
              break;
            case 'u':
              out.append((char) Integer.parseInt(input.substring(position, position + 4), 16));
              position += 4;
              break;
            default:
              throw error("invalid escape '\\" + esc + "'");
          }
        } else {
          out.append(c);
        }
      }
    }

    private Value parseNumber() {
      int start = position;
      if (peek() == '-') {
        position++;
      }
      while (!atEnd() && Character.isDigit(input.charAt(position))) {
        position++;
      }
      if (!atEnd() && (input.charAt(position) == '.' || input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
        if (input.charAt(position) == '.') {
          position++;
          while (!atEnd() && Character.isDigit(input.charAt(position))) {
            position++;
          }
        }
        if (!atEnd() && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
          position++;
          if (!atEnd() && (input.charAt(position) == '+' || input.charAt(position) == '-')) {
            position++;
          }
          while (!atEnd() && Character.isDigit(input.charAt(position))) {
            position++;
          }
        }
      }
      return new Value(Value.Kind.NUMBER, null, null, input.substring(start, position));
    }

    private void expectLiteral(String literal) {
      if (!input.regionMatches(position, literal, 0, literal.length())) {
        throw error("expected '" + literal + "'");
      }
      position += literal.length();
    }

    private char peek() {
      if (atEnd()) {
        throw error("unexpected end of input");
      }
      return input.charAt(position);
    }

    private IllegalArgumentException error(String message) {
      return new IllegalArgumentException("invalid JSON at index " + position + ": " + message);
    }
  }
}
