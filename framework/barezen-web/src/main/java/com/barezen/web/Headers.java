package com.barezen.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The header fields of a request or a response: an ordered list of name-value pairs, immutable, with names matched
 * case-insensitively and a name allowed to carry more than one value.
 *
 * <p>HTTP defines a field as a name plus a value, sent on its own line, and says two things that decide this type's
 * shape: field names are case-insensitive (ADR-0018 §5), and the same field name may appear more than once in a
 * message. The second rule is why the obvious representation is wrong. A {@code Map<String, String>} loses all but one
 * value of a repeated field, and folding the values into one comma-joined string corrupts the fields where a comma is
 * part of a value rather than a separator — {@code Set-Cookie} being the well known case. So this type stores field
 * lines, in the order they were given, and looks names up by case-insensitive comparison instead of by lowering keys
 * and thereby forgetting how the developer spelled them.
 *
 * <p>Four rules are enforced here rather than left to a reader (ADR-0018 §5):
 *
 * <ul>
 *   <li><b>names are case-insensitive for matching, and keep the case they were given for output.</b> A response built
 *       with {@code Content-Type} is sent as {@code Content-Type}, not as {@code content-type};
 *   <li><b>repeated names keep every value, in order.</b> {@link #values(String)} returns them all;
 *       {@link #first(String)} is the accessor for the many fields that have one value;
 *   <li><b>absent and empty are different.</b> A field sent as {@code X-Cursor:} exists with an empty value, so
 *       {@code first} reads back {@code Optional.of("")}; a field that was never sent reads back
 *       {@code Optional.empty()}. Conflating them is how "no cursor" and "cursor at the start" become the same
 *       request;
 *   <li><b>a name is an ASCII HTTP token and a value holds no control characters.</b> This is the one place the contract
 *       refuses a value outright, and it is a security rule rather than tidiness: a header value is routinely built
 *       from request data — a file name in {@code Content-Disposition}, an echo of {@code Origin} — and a CR or LF in
 *       such a value starts a new response line. An application cannot accidentally get this wrong, which is what
 *       "secure by default" means for this type.
 * </ul>
 *
 * <p>What is deliberately absent (ADR-0018 §5):
 *
 * <ul>
 *   <li><b>a {@code Map} view.</b> {@code Map<String, String>} cannot represent a repeated field, and
 *       {@code Map<String, List<String>>} would offer the interpretation this type refuses to make.
 *       {@link #names()} plus {@link #values(String)} carries the same information without pretending a header is a
 *       map entry;
 *   <li><b>per-field interpretation.</b> No cookie parser, no {@code Content-Type} parameter reader, no
 *       comma-splitting of multi-value fields, no quality-weight sorting for content negotiation. Each of those is a
 *       rule about one field, and a field rule written into the generic container is a rule that can never be
 *       overridden or tested on its own. They belong to small types next to this one, with the first real consumer;
 *   <li><b>a {@code set} that replaces.</b> To change a field, build the headers again — these structures hold a
 *       handful of entries, and one way to do it beats two that mean nearly the same thing;
 *   <li><b>mutability, including a builder type.</b> {@link #with(String, String)} returns a new {@code Headers}, so
 *       a header set handed to something else cannot be changed behind the handover.
 * </ul>
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class Headers {

  /** The RFC 9110 separator set plus space and tab: none of them may appear in a field name. */
  private static final String SEPARATORS = "()<>@,;:\\\"/[]?={} \t";

  /** The empty header set, reused because it holds nothing and cannot be modified. */
  private static final Headers EMPTY = new Headers(List.of());

  private final List<Entry> entries;

  private Headers(List<Entry> entries) {
    this.entries = entries;
  }

  /**
   * Headers with no fields, for a response that needs none and a request that arrived with none.
   *
   * @return an immutable empty instance, shared
   */
  public static Headers empty() {
    return EMPTY;
  }

  /**
   * One field with one value, which is what most call sites need.
   *
   * @param name field name, an ASCII HTTP token, matched case-insensitively and kept as given
   * @param value field value, without control characters; empty is legal and means the field was sent with no value
   * @return an immutable instance holding exactly this field
   * @throws NullPointerException if {@code name} or {@code value} is null
   * @throws IllegalArgumentException if {@code name} is blank or is not an HTTP token, or {@code value} holds a
   *     control character
   */
  public static Headers of(String name, String value) {
    return new Headers(List.of(entry(name, value)));
  }

  /**
   * These headers plus one more field line, as a new instance.
   *
   * <p>Calling this twice with the same name gives a field with two values, which is the repeated field HTTP means,
   * not a field that was set and then changed. To replace a value, build the headers from the fields you want.
   *
   * @param name field name, an ASCII HTTP token, matched case-insensitively and kept as given
   * @param value field value, without control characters
   * @return a new immutable instance with this field appended
   * @throws NullPointerException if {@code name} or {@code value} is null
   * @throws IllegalArgumentException if {@code name} is blank or is not an HTTP token, or {@code value} holds a
   *     control character
   */
  public Headers with(String name, String value) {
    List<Entry> extended = new ArrayList<>(entries.size() + 1);
    extended.addAll(entries);
    extended.add(entry(name, value));
    return new Headers(List.copyOf(extended));
  }

  /**
   * The first value of a field, which is the right accessor for the fields that have one value.
   *
   * <p>{@link Optional} rather than {@code null}, because an absent header is an expected answer, not a failed one,
   * and core already has the rule for it: {@code Configuration.get} and {@code Services.find} both hand absence back
   * as {@code Optional}. A {@code null} here would make every caller choose between trusting this type and checking
   * again. A field that was sent with no value is {@code Optional.of("")}, which is a different fact from absence and
   * the reason there is no {@code isPresent}-style pair of methods.
   *
   * @param name field name, matched case-insensitively
   * @return the value of the first line with that name, or empty if the field was never sent
   * @throws NullPointerException if {@code name} is null
   * @throws IllegalArgumentException if {@code name} is blank
   */
  public Optional<String> first(String name) {
    Objects.requireNonNull(name, "the header name to read; a null name matches nothing, so it asks for a guess");
    if (name.isBlank()) {
      throw new IllegalArgumentException("a header name to read must not be blank; \"\" matches no field");
    }
    for (Entry candidate : entries) {
      if (candidate.name().equalsIgnoreCase(name)) {
        return Optional.of(candidate.value());
      }
    }
    return Optional.empty();
  }

  /**
   * Every value of a field, in the order the lines arrived.
   *
   * @param name field name, matched case-insensitively
   * @return the values, empty when the field was not sent; never null, and immutable
   * @throws NullPointerException if {@code name} is null
   * @throws IllegalArgumentException if {@code name} is blank
   */
  public List<String> values(String name) {
    Objects.requireNonNull(name, "the header name to read; a null name matches nothing, so it asks for a guess");
    if (name.isBlank()) {
      throw new IllegalArgumentException("a header name to read must not be blank; \"\" matches no field");
    }
    List<String> found = new ArrayList<>(2);
    for (Entry candidate : entries) {
      if (candidate.name().equalsIgnoreCase(name)) {
        found.add(candidate.value());
      }
    }
    return List.copyOf(found);
  }

  /**
   * The field names present, each once, in the order they first appeared, spelled as they were given.
   *
   * <p>This is the read side an adapter needs to write the fields back to a socket, and it is the surface a handler
   * can check for presence without knowing every name in HTTP. Paired with {@link #values(String)} it carries a whole
   * header set, which is why there is no map: a map of names would either drop the repeated values or hand back a
   * mutable structure that this type's immutability was built to prevent.
   *
   * @return the distinct names in first-appearance order, immutable, empty when there are no fields
   */
  public List<String> names() {
    List<String> distinct = new ArrayList<>(entries.size());
    for (Entry candidate : entries) {
      boolean seen = false;
      for (String already : distinct) {
        if (already.equalsIgnoreCase(candidate.name())) {
          seen = true;
          break;
        }
      }
      if (!seen) {
        distinct.add(candidate.name());
      }
    }
    return List.copyOf(distinct);
  }

  /**
   * Names only, never values.
   *
   * <p>A header set routinely holds a credential — {@code Authorization} on a request, a token in a custom field —
   * and a {@code toString} that prints values ends up in a log line, an exception message or a debugger view, which is
   * where a secret leaks. So this prints the field names and counts. An application that wants a value in a log
   * writes the value it wants there, on purpose, with the field it chose.
   *
   * @return a summary such as {@code Headers[3 fields: Content-Type, Authorization, X-Trace]}
   */
  @Override
  public String toString() {
    // Joined by hand rather than printed as a List, so a log line reads "Headers[2 fields: Content-Type, ETag]" and
    // not "Headers[2 fields: [Content-Type, ETag]]".
    StringBuilder text = new StringBuilder("Headers[")
        .append(entries.size())
        .append(entries.size() == 1 ? " field: " : " fields: ");
    List<String> distinct = names();
    for (int index = 0; index < distinct.size(); index++) {
      if (index > 0) {
        text.append(", ");
      }
      text.append(distinct.get(index));
    }
    return text.append("]").toString();
  }

  /**
   * Validate one field and store it. Everything a header can get wrong is refused here, so no reader of an existing
   * instance has to re-check it.
   */
  private static Entry entry(String name, String value) {
    Objects.requireNonNull(name, "the header name; a field with no name cannot be matched, sent or read back");
    checkName(name);
    Objects.requireNonNull(
        value, "the value of header '" + name + "'; use \"\" for a field that is present with no value");
    checkValue(name, value);
    return new Entry(name, value);
  }

  /**
   * A field name is an RFC 9110 token: visible ASCII only, with no whitespace, control character or separator. Enforcing
   * the token grammar costs one loop and removes a family of bugs where a name built out of request data turns into a
   * different field, into a field plus a value, or into a new line.
   */
  private static void checkName(String name) {
    if (name.isEmpty()) {
      throw new IllegalArgumentException("a header name must not be empty; a field with no name is not a field");
    }
    for (int index = 0; index < name.length(); index++) {
      char character = name.charAt(index);
      if (character <= 0x20 || character >= 0x7f || SEPARATORS.indexOf(character) >= 0) {
        throw new IllegalArgumentException("header name " + quoted(name) + " is not an HTTP token; "
            + offending(character, index, name)
            + ". A field name is matched case-insensitively against a fixed set of tokens, so anything that is not a"
            + " token is a name that can never be read back");
      }
    }
  }

  /**
   * A field value may not hold a control character. CR or LF ends the field and starts a line, which in a response
   * built from request data is HTTP response splitting.
   */
  private static void checkValue(String name, String value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (isControl(character)) {
        throw new IllegalArgumentException("value of header " + quoted(name) + " holds "
            + offending(character, index, value)
            + ". A control character in a field value ends the field early, which turns an echoed value into a new"
            + " response line");
      }
    }
  }

  private static boolean isControl(char character) {
    return character <= 0x1f || character == 0x7f;
  }

  /**
   * Describe the refused character, in the refused string, at the place it was refused.
   *
   * <p>Both refusal paths write through here, because a rejection message is a channel for the same content it is
   * rejecting: a raw CR or LF put into a log line keeps working there, in the place the refusal was meant to stop it.
   */
  private static String offending(char character, int index, String held) {
    return "the character " + printable(character) + " at index " + index + " of " + quoted(held);
  }

  private static String printable(char character) {
    return isControl(character) ? escape(character) : "'" + character + "'";
  }

  /** Quote a rejected string without repeating any control character it holds. */
  private static String quoted(String held) {
    StringBuilder text = new StringBuilder(held.length() + 8).append('"');
    for (int index = 0; index < held.length(); index++) {
      char character = held.charAt(index);
      text.append(isControl(character) ? escape(character) : String.valueOf(character));
    }
    return text.append('"').toString();
  }

  /**
   * Four hex digits after the backslash-u, which is the form a reader can look up and a test can paste. An earlier
   * version trimmed the padding and printed a two-digit escape for CR, which is no escape any parser accepts.
   */
  private static String escape(char character) {
    return "\\u" + String.format("%04X", (int) character);
  }

  /**
   * One field line. Private and nested, because a header set is the only thing that ever holds one and exposing it
   * would offer a second way to build headers that nothing validates differently.
   *
   * @param name the name as it was given, matched case-insensitively
   * @param value the value as it was given, never null and never holding a control character
   */
  private record Entry(String name, String value) {}
}
