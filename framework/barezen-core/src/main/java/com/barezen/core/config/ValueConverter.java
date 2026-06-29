package com.barezen.core.config;

import com.barezen.core.error.ConfigurationException;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.Locale;

/**
 * Turns the string a source stored into the type a {@link ConfigurationKey} declares.
 *
 * <p>Package private and static: conversion is a property of the key's type, so it belongs to the
 * configuration layer as a whole rather than to any one source. A public home would invite callers to convert
 * values outside the snapshot they came from, and a per-source converter would let two sources disagree about
 * what {@code "PT5S"} means.
 *
 * <p>Failure always produces {@link ConfigurationException} naming the key, the raw value and the source that
 * supplied it. Bad configuration is never coerced to a default, because a component that quietly runs on
 * fallbacks looks healthy while doing the wrong thing.
 */
final class ValueConverter {

  private ValueConverter() {}

  static <T> T convert(ConfigurationKey<T> key, String raw, String sourceName) {
    Class<T> type = key.type();
    String value = raw.trim();
    Object converted;
    try {
      if (type == String.class) {
        converted = value;
      } else if (type == Integer.class) {
        converted = Integer.valueOf(value);
      } else if (type == Long.class) {
        converted = Long.valueOf(value);
      } else if (type == Boolean.class) {
        converted = toBoolean(value);
      } else if (type == Duration.class) {
        converted = Duration.parse(value);
      } else if (type.isEnum()) {
        converted = toEnum(type, value);
      } else {
        // Primitive class literals land here rather than failing later on Class.cast: a key declared with
        // int.class cannot work, and saying so beats returning a value that cannot be unboxed.
        throw new ConfigurationException("configuration key '" + key.name() + "' declares unsupported type "
            + type.getName() + "; supported types are String, Integer, Long, Boolean, Duration and enums");
      }
    } catch (IllegalArgumentException | DateTimeException e) {
      // NumberFormatException extends IllegalArgumentException and DateTimeParseException extends
      // DateTimeException, which is not its sibling: both must be named or a bad Duration escapes as a
      // java.time failure the caller was never told to expect. Every conversion failure arrives here as one
      // message naming the key, the raw value and the source that supplied it.
      throw new ConfigurationException("value '" + raw + "' of configuration key '" + key.name()
          + "' from source '" + sourceName + "' is not a valid " + type.getSimpleName()
          + hint(type) + ": " + e.getMessage(), e);
    }
    return type.cast(converted);
  }

  /**
   * Enum names in configuration are written lower case ({@code auto}) while Java constants are upper case
   * ({@code AUTO}); the mapping is the framework's one documented spelling rule. Anything fancier, such as
   * accepting arbitrary aliases, makes a configuration file unpredictable.
   */
  private static Object toEnum(Class<?> type, String value) {
    for (Object constant : type.getEnumConstants()) {
      Enum<?> enumConstant = (Enum<?>) constant;
      if (enumConstant.name().equals(value.toUpperCase(Locale.ROOT))) {
        return enumConstant;
      }
    }
    StringBuilder allowed = new StringBuilder();
    for (Object constant : type.getEnumConstants()) {
      if (!allowed.isEmpty()) {
        allowed.append(", ");
      }
      allowed.append(((Enum<?>) constant).name().toLowerCase(Locale.ROOT));
    }
    throw new IllegalArgumentException("not one of: " + allowed);
  }

  /**
   * Explicit true/false only. {@code Boolean.parseBoolean} would answer {@code false} for a typo such as
   * {@code tru}, and a silently inverted flag is worse than a failed startup.
   */
  private static boolean toBoolean(String value) {
    if (value.equalsIgnoreCase("true")) {
      return true;
    }
    if (value.equalsIgnoreCase("false")) {
      return false;
    }
    throw new IllegalArgumentException("not 'true' or 'false'");
  }

  private static String hint(Class<?> type) {
    if (type == Duration.class) {
      return " Duration (ISO-8601, for example PT10S)";
    }
    if (type == Integer.class || type == int.class || type == Long.class || type == long.class) {
      return " number";
    }
    return "";
  }
}
