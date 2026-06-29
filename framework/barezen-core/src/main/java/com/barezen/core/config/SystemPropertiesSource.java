package com.barezen.core.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link ConfigurationSource} reading JVM system properties, the highest precedence layer of
 * {@link Configuration#standard}.
 *
 * <p>Properties whose value is not a string, and keys that are not strings, are skipped rather than coerced:
 * the {@code Properties} API allows arbitrary objects, and turning one into a configuration value would make a
 * startup failure depend on a {@code toString} nobody wrote on purpose.
 *
 * <p>Snapshot semantics matter here in a way they do not for the other sources, because {@code
 * System.setProperty} is writable at runtime. Copying at construction is what makes "the configuration this
 * application started with" a real statement instead of a race with whatever the last caller set.
 */
final class SystemPropertiesSource implements ConfigurationSource {

  private final Map<String, String> properties;

  private SystemPropertiesSource(Map<String, String> properties) {
    this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
  }

  static SystemPropertiesSource current() {
    java.util.Properties in = System.getProperties();
    Map<String, String> snapshot = new LinkedHashMap<>();
    // stringPropertyNames() already leaves out keys and values that are not strings, which is the filter
    // this class wants: a Properties carrying an arbitrary object is not configuration.
    for (String key : in.stringPropertyNames()) {
      snapshot.put(key, in.getProperty(key));
    }
    return new SystemPropertiesSource(snapshot);
  }

  /** Used by tests, which must not leak a system property into the rest of the build. */
  static SystemPropertiesSource of(Map<String, String> properties) {
    return new SystemPropertiesSource(properties);
  }

  @Override
  public String name() {
    return "system properties";
  }

  @Override
  public Optional<String> find(String keyName) {
    return Optional.ofNullable(properties.get(keyName));
  }
}
