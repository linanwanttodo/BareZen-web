package com.barezen.plugins.openapi;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * The framework's only JSON emitter, and it exists because deterministic output is a property of the emitter, not
 * only of the data. Any JSON library could serialize this model; none of them promises byte-equivalence across runs
 * — map iteration, key ordering and number formatting are their decisions, not ours (Stage 2.14-D: same input →
 * byte-equivalent output). Writing ~100 lines of emitter is how the promise becomes a construction property: this
 * class writes members in exactly the order the caller adds them, never re-sorting and never re-hashing.
 *
 * <p>Package-private by choice (ADR-0043): an application consumes the <em>document</em>, not the bytes protocol,
 * and a public JSON writer is a second API the framework would then owe forever.
 *
 * <p>The comma state is one stack of frames — one per open container — where a frame remembers whether anything has
 * been written into it yet and whether it is an array (whose items need separators) or an object (whose separators
 * belong to {@link #field}, not to the value after it). A misuse — a field inside an array, closing past the top —
 * throws immediately rather than emitting malformed JSON, because a generator that <em>can</em> produce an invalid
 * document is the thing Stage 2.14-F's validation exists to catch.
 */
final class JsonWriter {

  private static final class Frame {
    private final boolean array;
    private boolean first = true;

    Frame(boolean array) {
      this.array = array;
    }
  }

  private final StringBuilder out = new StringBuilder(4096);
  private final Deque<Frame> stack = new ArrayDeque<>();

  JsonWriter objectStart() {
    valueStart();
    out.append('{');
    stack.push(new Frame(false));
    return this;
  }

  JsonWriter objectEnd() {
    requireFrame(false);
    out.append('}');
    stack.pop();
    return this;
  }

  /** Starts a named member of the enclosing object; the value follows from the next call. */
  JsonWriter field(String name) {
    Frame frame = stack.peek();
    if (frame == null || frame.array) {
      throw new IllegalStateException("field \"" + name + "\" written outside an object");
    }
    if (!frame.first) {
      out.append(',');
    }
    frame.first = false;
    escape(name);
    out.append(':');
    return this;
  }

  JsonWriter arrayStart() {
    valueStart();
    out.append('[');
    stack.push(new Frame(true));
    return this;
  }

  JsonWriter arrayEnd() {
    requireFrame(true);
    out.append(']');
    stack.pop();
    return this;
  }

  JsonWriter string(String value) {
    valueStart();
    escape(value);
    return this;
  }

  JsonWriter bool(boolean value) {
    valueStart();
    out.append(value);
    return this;
  }

  String finish() {
    if (!stack.isEmpty()) {
      throw new IllegalStateException("document ended with " + stack.size() + " unclosed container(s)");
    }
    return out.toString();
  }

  /** Every value position needs a separator only inside an array; object commas belong to {@link #field}. */
  private void valueStart() {
    Frame frame = stack.peek();
    if (frame == null) {
      return; // top level: one value, no separators
    }
    if (frame.array) {
      if (!frame.first) {
        out.append(',');
      }
      frame.first = false;
    }
  }

  private void requireFrame(boolean wantArray) {
    Frame frame = stack.peek();
    if (frame == null) {
      throw new IllegalStateException("closed a container that was never opened");
    }
    if (frame.array != wantArray) {
      throw new IllegalStateException("closed an " + (wantArray ? "array" : "object") + " while inside an "
          + (frame.array ? "array" : "object"));
    }
  }

  private void escape(String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
