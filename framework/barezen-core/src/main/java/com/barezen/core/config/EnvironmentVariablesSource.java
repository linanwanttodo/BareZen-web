package com.barezen.core.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link ConfigurationSource} reading the process environment.
 *
 * <p>One name translation rule, documented rather than clever:
 *
 * <pre>
 *   barezen.runtime.threads  -&gt;  BAREZEN_RUNTIME_THREADS
 * </pre>
 *
 * <p>Dots become underscores, then the whole name is uppercased. There is no kebab-case folding, no reading a
 * shorter prefix as a tree, no alias list. An operator override is the one layer that has to be predictable
 * from a shell, and every extra rule makes it less so. If a real need for something richer appears, it gets an
 * ADR rather than a special case here.
 *
 * <p>The environment is snapshotted at construction. The process cannot write its own environment through the
 * public JDK API anyway, so this is about consistency rather than protection.
 */
final class EnvironmentVariablesSource implements ConfigurationSource {

  private final Map<String, String> variables;

  private EnvironmentVariablesSource(Map<String, String> variables) {
    this.variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
  }

  static EnvironmentVariablesSource current() {
    return new EnvironmentVariablesSource(System.getenv());
  }

  /** Used by tests, which cannot set the environment of the JVM they are already running in. */
  static EnvironmentVariablesSource of(Map<String, String> variables) {
    return new EnvironmentVariablesSource(variables);
  }

  @Override
  public String name() {
    return "environment variables";
  }

  @Override
  public Optional<String> find(String keyName) {
    return Optional.ofNullable(variables.get(toVariableName(keyName)));
  }

  static String toVariableName(String keyName) {
    return keyName.replace('.', '_').toUpperCase(Locale.ROOT);
  }
}
