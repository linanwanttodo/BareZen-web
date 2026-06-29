package com.barezen.core.config;

import java.util.Optional;

/**
 * One place configuration values come from.
 *
 * <p>A source answers for individual key names and owns no precedence logic: ordering and first-match-wins
 * belong to {@link Configuration}, which keeps every source small enough to reason about on its own and lets a
 * future format sit beside these three without a shared abstraction growing.
 *
 * <p>Implementations are immutable, or made immutable at construction, and thread safe.
 */
public interface ConfigurationSource {

  /**
   * @return identifier used in failure messages, for example a file path or {@code "system properties"}; when
   *     two sources share a kind, the name distinguishes them
   */
  String name();

  /**
   * Looks up one key, exactly as spelled.
   *
   * <p>No trimming, no case folding, no prefix guessing here: normalisation is done by the one source that
   * needs it, which is why {@link EnvironmentVariablesSource} translates the name and the others do not.
   *
   * @param keyName dotted key name, for example {@code barezen.runtime.threads}
   * @return the stored string, empty when this source has no such key
   */
  Optional<String> find(String keyName);
}
