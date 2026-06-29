package com.barezen.core.config;

import com.barezen.core.error.ConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * A {@link ConfigurationSource} holding flat string pairs, read from a properties file or supplied in memory.
 *
 * <p>{@link Properties} does the parsing rather than a hand-written reader, because that parser has absorbed
 * three decades of edge cases in escapes, continuation lines and comment syntax that a bespoke one would
 * quietly get wrong. UTF-8 is imposed explicitly: {@code Properties.load(InputStream)} reads ISO-8859-1, which
 * turns a non-ASCII value into mojibake on some platforms and correct text on others.
 *
 * <p>Values are copied into an immutable map at construction, so a file is read once and a caller-supplied map
 * cannot be mutated underneath a snapshot already handed out.
 */
final class PropertiesConfigurationSource implements ConfigurationSource {

  private final String name;
  private final Map<String, String> values;

  private PropertiesConfigurationSource(String name, Map<String, String> values) {
    this.name = name;
    this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  /** An in-memory layer, used for test fixtures and for a programmatic configuration. */
  static PropertiesConfigurationSource of(String name, Map<String, String> values) {
    return new PropertiesConfigurationSource(name, values);
  }

  /**
   * Reads a properties file. A file that does not exist yields a source holding nothing: running entirely on
   * defaults and environment is a supported way to deploy, so a missing file is not a startup failure.
   *
   * @throws ConfigurationException if the path exists but cannot be read or parsed
   */
  static PropertiesConfigurationSource fromFile(Path file) {
    if (!Files.exists(file)) {
      return new PropertiesConfigurationSource(file.toString(), Map.of());
    }
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(file);
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException e) {
      throw new ConfigurationException("configuration file '" + file + "' exists but could not be read"
          + " as UTF-8 properties", e);
    }
    Map<String, String> values = new LinkedHashMap<>();
    // stringPropertyNames() skips any entry whose key or value is not a String, which is the filter wanted
    // here: a Properties carrying an arbitrary object is not configuration.
    for (String key : properties.stringPropertyNames()) {
      values.put(key, properties.getProperty(key));
    }
    return new PropertiesConfigurationSource(file.toString(), values);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Optional<String> find(String keyName) {
    return Optional.ofNullable(values.get(keyName));
  }
}
