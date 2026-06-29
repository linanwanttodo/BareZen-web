package com.barezen.core.config;

import java.util.Objects;
import java.util.Optional;

/**
 * The {@link Configuration} implementation: an ordered array of sources, first match wins.
 *
 * <p>Lookup walks the sources from the highest precedence layer down and stops at the first one holding the
 * key, then converts that one string. A lower layer is never consulted for a key a higher layer answered, so
 * an override replaces rather than merges, and a value always comes from exactly one source instead of being
 * combined out of several.
 *
 * <p>Package private; instances come from {@link Configuration#of} or {@link Configuration#standard}.
 */
final class MergedConfiguration implements Configuration {

  /** Highest precedence first, the reverse of the declared order, so lookup reads top down. */
  private final ConfigurationSource[] sources;

  MergedConfiguration(ConfigurationSource... sourcesWeakestFirst) {
    if (sourcesWeakestFirst.length == 0) {
      throw new IllegalArgumentException("a configuration needs at least one source");
    }
    ConfigurationSource[] strongestFirst = new ConfigurationSource[sourcesWeakestFirst.length];
    for (int i = 0; i < sourcesWeakestFirst.length; i++) {
      strongestFirst[i] = Objects.requireNonNull(
          sourcesWeakestFirst[sourcesWeakestFirst.length - 1 - i], "configuration source must not be null");
    }
    this.sources = strongestFirst;
  }

  @Override
  public <T> Optional<T> get(ConfigurationKey<T> key) {
    Objects.requireNonNull(key, "configuration key must not be null");
    for (ConfigurationSource source : sources) {
      Optional<String> raw = source.find(key.name());
      if (raw.isPresent()) {
        return Optional.of(ValueConverter.convert(key, raw.get(), source.name()));
      }
    }
    // The default is declared with the type, so an unconfigured key still yields a value that type-checks.
    return Optional.ofNullable(key.defaultValue());
  }
}
