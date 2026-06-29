package com.barezen.core.config;

import java.util.Objects;

/**
 * A named, typed configuration entry, declared once and read through {@link Configuration}.
 *
 * <p>The type is part of the key, which is what keeps callers from writing {@code (Integer) get("port")} and
 * meeting a {@code String} at runtime. A component declares the keys it owns as constants, so a key name
 * appears in exactly one place in the codebase, is greppable, and a misspelling in a properties file shows up
 * as an unset value rather than a silently wrong one.
 *
 * <p>The default lives with the declaration rather than at every call site, so one line states the whole
 * answer to "what happens if the operator configures nothing".
 *
 * @param <T> the type the stored string is converted to
 * @param name dotted key name, for example {@code barezen.runtime.threads}
 * @param type target type, one of the types {@link ValueConverter} supports
 * @param defaultValue used when no source has the key, {@code null} for "no default"
 */
public record ConfigurationKey<T>(String name, Class<T> type, T defaultValue) {

  public ConfigurationKey {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("configuration key name must not be blank");
    }
    // Segments of letters, digits, dash and underscore, dot separated. An empty segment ("a..b") would
    // otherwise produce a key no source can ever contain, which reads as unset rather than as a typo.
    if (!name.matches("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*")) {
      throw new IllegalArgumentException("configuration key name must be dot separated segments, got '"
          + name + "'");
    }
    Objects.requireNonNull(type, "configuration key '" + name + "' must declare a type");
  }

  /** Declares a key with no default, so {@link Configuration#get} returns empty when nothing configures it. */
  public static <T> ConfigurationKey<T> of(String name, Class<T> type) {
    return new ConfigurationKey<>(name, type, null);
  }

  /** Declares a key and what to use when nothing configures it. */
  public static <T> ConfigurationKey<T> of(String name, Class<T> type, T defaultValue) {
    return new ConfigurationKey<>(name, type, defaultValue);
  }
}
