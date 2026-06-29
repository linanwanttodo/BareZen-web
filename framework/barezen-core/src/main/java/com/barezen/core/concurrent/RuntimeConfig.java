package com.barezen.core.concurrent;

import com.barezen.core.config.Configuration;
import com.barezen.core.config.ConfigurationKey;
import com.barezen.core.error.ConfigurationException;
import java.util.Objects;

/**
 * The {@code barezen.runtime.*} options, as a typed value object.
 *
 * <p>This is the seam between configuration and the thread subsystem, and it exists so that neither has to
 * know about the other: {@link Configuration} never learns what a thread policy is, and {@link ThreadProvider}
 * never reads a property or an environment variable (ADR-0013). The chain is
 *
 * <pre>
 *   Configuration -&gt; RuntimeConfig -&gt; ThreadPolicy -&gt; ThreadProviders.create(...)
 * </pre>
 *
 * <p>Binding is written out by hand rather than inferred. There is no annotation to place on a record, no
 * classpath scan for types to bind and no reflection over components: one key declaration per field, and the
 * compiler checks that a renamed field still binds to the right value. A generic binder would be the magic this
 * framework's founding rule rejects (ADR-0006).
 */
public record RuntimeConfig(ThreadPolicy threads) {

  /** How executor threads are chosen. Lower-case in configuration: {@code auto}, {@code platform}, {@code virtual}. */
  public static final ConfigurationKey<ThreadPolicy> THREADS =
      ConfigurationKey.of("barezen.runtime.threads", ThreadPolicy.class, ThreadPolicy.AUTO);

  public RuntimeConfig {
    Objects.requireNonNull(threads, "threads policy must not be null");
  }

  /**
   * Reads the runtime options from a configuration snapshot.
   *
   * <p>An unsupported value for {@code barezen.runtime.threads} fails here, naming the key, the value and the
   * source that supplied it; it is never rounded to {@code AUTO}. Whether the resolved policy can actually be
   * satisfied is the next question, answered by {@link ThreadProviders#create} when the provider is built,
   * which fails startup rather than downgrading.
   *
   * @throws ConfigurationException if a value is present but not a supported policy
   */
  public static RuntimeConfig from(Configuration configuration) {
    Objects.requireNonNull(configuration, "configuration must not be null");
    return new RuntimeConfig(configuration.get(THREADS).orElseThrow(() -> new ConfigurationException(
        "configuration key '" + THREADS.name() + "' resolved to nothing, which cannot happen while it"
            + " declares a default")));
  }
}
