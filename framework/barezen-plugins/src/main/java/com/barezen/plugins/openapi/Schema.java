package com.barezen.plugins.openapi;

import java.util.List;
import java.util.Objects;

/**
 * One schema, in the subset this framework emits: {@code type}, {@code format}, {@code properties},
 * {@code items}, {@code required}, {@code nullable}, and the by-name component reference the document needs —
 * exactly Stage 2.14-C's five concepts plus the one structural necessity, and nothing else the specification's
 * grammar would allow (no {@code oneOf}, no discriminators, no composition keywords, no vendor extensions).
 *
 * <p><b>Why there is no Java reflection in here.</b> A schema is a value the application declares, not a shape a
 * generator inferred from a DTO class. The directive attaches four conditions to reflection-based schema
 * generation — deterministic, cycle-safe, no arbitrary classpath scanning, stable property ordering — and the
 * honest way to satisfy all four permanently is not to build a careful reflector but to take the answer as data:
 * properties are an ordered list the caller wrote, so stability is the caller's own source text; cycle-safety is
 * structural, because a {@code REFERENCE} node carries a name and the emitter prints the name without recursing
 * (a {@code User} whose {@code friends} is {@code array($ref "User")} is one string deep, and no walk of the
 * document can loop); the scanner never exists because scanning was never needed. The cost — a second
 * description of a type that already exists as Java — is the same trade ADR-0019 made for routes and
 * {@link OperationMetadata}: two lists kept aligned by the code that writes both, visible at one reading location.
 * A reflecting variant stays reopenable (ADR-0043) under all four conditions plus package-private-by-default.
 *
 * <p>Construction states the shape's own validity rules — {@code properties} only on {@code object},
 * {@code items} only on {@code array}, {@code refName} exactly on {@code REFERENCE} and nowhere else — because a
 * generator silently dropping a misplaced half is the invalid-schema case Stage 2.14-F is required to catch. The
 * single source of required-ness is {@link Property#required()}: the emitted {@code required} list is derived
 * from the flags in declaration order, so no document can carry two disagreeing answers to "which fields are
 * required".
 */
public record Schema(SchemaType type, String format, List<Property> properties, Schema items, String refName,
    boolean nullable) {

  /** One declared property of an object schema: its name, its (sub-)schema, and whether a payload must carry it. */
  public record Property(String name, Schema schema, boolean required) {
    public Property {
      Objects.requireNonNull(name, "name");
      if (name.isBlank()) {
        throw new IllegalArgumentException("property name must not be blank");
      }
      Objects.requireNonNull(schema, "schema");
    }
  }

  /** The types of this subset — Stage 2.14-C's five shapes plus the by-name component reference. */
  public enum SchemaType {
    OBJECT("object"), ARRAY("array"), STRING("string"), INTEGER("integer"), NUMBER("number"), BOOLEAN("boolean"),
    /** Resolves to a named entry in the document's component schemas; emitted as {@code $ref}. */
    REFERENCE("reference");

    private final String text;

    SchemaType(String text) {
      this.text = text;
    }

    /** The OpenAPI {@code type} string; {@link #REFERENCE} has no {@code type} in its emission, only the name. */
    public String text() {
      return text;
    }
  }

  public Schema {
    Objects.requireNonNull(type, "type");
    properties = List.copyOf(properties == null ? List.of() : properties);
    for (int i = 0; i < properties.size(); i++) {
      String name = properties.get(i).name();
      for (int j = i + 1; j < properties.size(); j++) {
        if (properties.get(j).name().equals(name)) {
          throw new IllegalArgumentException("an object schema declares property '" + name + "' twice");
        }
      }
    }
    switch (type) {
      case OBJECT -> {
        require(items == null, "an object schema has no items; that is an array's field");
        require(format == null && refName == null, "an object schema carries no format and no ref name");
      }
      case ARRAY -> {
        require(items != null, "an array schema names no item schema; an array of nothing is the invalid-schema"
            + " case this record refuses at the source");
        require(properties.isEmpty() && format == null && refName == null,
            "an array schema carries no properties, format or ref name");
      }
      case REFERENCE -> {
        require(refName != null && !refName.isBlank(), "a reference schema must name its component");
        require(properties.isEmpty() && items == null && format == null,
            "a reference schema carries nothing but its name; the target states its own shape");
      }
      default -> {
        require(items == null && properties.isEmpty() && refName == null,
            "a " + type.text() + " schema carries no items, properties or ref name; those belong to the shapes"
                + " that have structure");
      }
    }
    if (refName != null && !refName.matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("component name \"" + refName + "\" uses characters outside"
          + " [A-Za-z0-9._-]; it is emitted inside a $ref string and must survive JSON-pointer rules unchanged");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  /** A primitive: {@code string}/{@code integer}/{@code number}/{@code boolean}, with optional {@code format}. */
  public static Schema primitive(SchemaType type, String format) {
    return new Schema(type, format, List.of(), null, null, false);
  }

  public static Schema string() {
    return primitive(SchemaType.STRING, null);
  }

  /** {@code integer} in {@code int64}, the shape most id fields actually are. */
  public static Schema integer() {
    return primitive(SchemaType.INTEGER, "int64");
  }

  public static Schema number() {
    return primitive(SchemaType.NUMBER, null);
  }

  public static Schema booleanSchema() {
    return primitive(SchemaType.BOOLEAN, null);
  }

  /** An object schema; the property order given here is the emitted order, by construction. */
  public static Schema object(List<Property> properties) {
    return new Schema(SchemaType.OBJECT, null, properties, null, null, false);
  }

  public static Schema array(Schema items) {
    return new Schema(SchemaType.ARRAY, null, List.of(), items, null, false);
  }

  /**
   * A by-name reference to a document component. The name is charset-checked here and resolved against the
   * declared components when the document is built — an unresolved name is the invalid-schema-reference refusal
   * Stage 2.14-F names.
   *
   * @param componentName a declared entry in the document's components, e.g. {@code "User"}
   */
  public static Schema ref(String componentName) {
    Objects.requireNonNull(componentName, "componentName");
    return new Schema(SchemaType.REFERENCE, null, List.of(), null, componentName, false);
  }

  /** This schema with {@code nullable: true} — the flag an application declares, never a default that flips. */
  public Schema asNullable() {
    return new Schema(type, format, properties, items, refName, true);
  }

  /** The required property names, in declaration order — derived from the flags, so it cannot disagree with them. */
  public List<String> requiredNames() {
    return properties.stream().filter(Property::required).map(Property::name).toList();
  }

  @Override
  public String toString() {
    return type == SchemaType.REFERENCE
        ? "$ref:" + refName
        : type.text() + (format == null ? "" : "(" + format + ")");
  }
}
