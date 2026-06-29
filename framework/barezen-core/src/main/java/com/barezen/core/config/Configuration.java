package com.barezen.core.config;

import com.barezen.core.error.ConfigurationException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A read-only snapshot of configuration, resolved from ordered {@link ConfigurationSource}s.
 *
 * <p>Values are stored as strings and converted to a type on read, so a source never has to know what a
 * {@code Duration} or an enum looks like and a key never has to know where its value came from.
 *
 * <p>One resolution method is the whole interface, and that is deliberate. There is no write API:
 * configuration is decided before startup and stays decided, so two components cannot disagree about a value
 * and a test cannot leave state behind for the next one. A source that reads mutable global state copies what
 * it found at construction, which makes the snapshot real rather than nominal.
 *
 * <p>Configuration answers questions about values and nothing else. It resolves no objects, registers nothing
 * and hands out no collaborators; a method returning one of those would belong to a different layer and would
 * turn configuration into the service locator this framework rejects elsewhere (ADR-0006).
 */
public interface Configuration {

  /**
   * Resolves a key, converting the winning source's string to the key's declared type.
   *
   * @return the configured value; the key's own default when no source has it; empty when the key has no
   *     default either
   * @throws ConfigurationException if a present value cannot be converted to the key's type. A bad value is
   *     never replaced by a default, because a component quietly running on a fallback looks healthy while
   *     doing the wrong thing
   */
  <T> Optional<T> get(ConfigurationKey<T> key);

  /**
   * Builds a layered snapshot from the given sources, weakest first, so later arguments win.
   *
   * <p>Precedence is expressed by argument order rather than by a property of each source, so a test can
   * assemble the same layering as production without a special hook, and {@link #standard} is one ordering
   * choice rather than a privileged one. A key's own default sits below every source, so it needs no layer.
   *
   * @throws IllegalArgumentException if no source is given
   */
  static Configuration of(ConfigurationSource... sourcesWeakestFirst) {
    return new MergedConfiguration(sourcesWeakestFirst);
  }

  /**
   * Builds a single-layer snapshot from values held in memory.
   *
   * <p>The only way to obtain a snapshot without a file or the process's own properties, which makes it the
   * construction path for an embedding application that configures programmatically and for a test that wants
   * deterministic values. The map is copied, so later edits to it are not visible here.
   */
  static Configuration inMemory(java.util.Map<String, String> values) {
    return new MergedConfiguration(PropertiesConfigurationSource.of("in-memory", values));
  }

  /**
   * Builds the framework's standard snapshot, weakest layer first: the properties file, then environment
   * variables, then system properties, with every key's own default below all three.
   *
   * <p>Later layers win, so a packaged {@code application.properties} can be overridden by an operator's
   * environment variable and both by a {@code -D} flag without editing anything. A file that does not exist
   * contributes nothing rather than failing: running on defaults plus environment is a supported deployment.
   *
   * @param propertiesFile path to a properties file, need not exist
   * @throws ConfigurationException if the file exists but cannot be read as UTF-8 properties
   */
  static Configuration standard(Path propertiesFile) {
    return new MergedConfiguration(
        PropertiesConfigurationSource.fromFile(propertiesFile),
        EnvironmentVariablesSource.current(),
        SystemPropertiesSource.current());
  }
}
