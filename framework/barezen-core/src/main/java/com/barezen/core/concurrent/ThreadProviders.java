package com.barezen.core.concurrent;

import java.time.Duration;
import com.barezen.core.runtime.RuntimeCapabilities;

/**
 * Entry point for obtaining a {@link ThreadProvider}.
 *
 * <p>The factory is the only place that turns a {@link ThreadPolicy} plus detected capabilities into a
 * concrete provider, which keeps the "explicit request never degrades silently" rule in one readable
 * method instead of scattered across callers.
 *
 * <p>Providers are scoped, not global. Each subsystem that needs executor ownership asks for its own
 * provider, so shutting one down cannot affect another (ADR-0011).
 */
public final class ThreadProviders {

  /** Grace period used when a caller does not state one. */
  public static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(10);

  private ThreadProviders() {}

  /**
   * Resolves a provider for the given policy and runtime capabilities, with
   * {@link #DEFAULT_CLOSE_TIMEOUT}.
   *
   * @throws ThreadCapabilityUnavailableException if {@code policy} is {@link ThreadPolicy#VIRTUAL}
   *     and {@code runtime} does not report virtual threads
   */
  public static ThreadProvider create(ThreadPolicy policy, RuntimeCapabilities runtime) {
    return create(policy, runtime, DEFAULT_CLOSE_TIMEOUT);
  }

  /**
   * Resolves a provider for the given policy and runtime capabilities.
   *
   * <p>Resolution rules:
   * <ul>
   *   <li>{@link ThreadPolicy#AUTO} picks virtual threads when available, platform threads otherwise;
   *   <li>{@link ThreadPolicy#PLATFORM} always picks platform threads, even on Java 21;
   *   <li>{@link ThreadPolicy#VIRTUAL} picks virtual threads or fails startup.
   * </ul>
   *
   * @param policy requested thread model, not {@code null}
   * @param runtime capabilities to branch on, not {@code null}
   * @param closeTimeout graceful termination budget for executors this provider owns, non negative
   * @return a provider whose ownership scope is limited to the executors it creates
   * @throws NullPointerException if any argument is {@code null}
   * @throws IllegalArgumentException if {@code closeTimeout} is negative
   * @throws ThreadCapabilityUnavailableException if the requested capability is unavailable
   */
  public static ThreadProvider create(ThreadPolicy policy, RuntimeCapabilities runtime, Duration closeTimeout) {
    if (policy == null) {
      throw new NullPointerException("policy must not be null");
    }
    if (runtime == null) {
      throw new NullPointerException("runtime must not be null");
    }
    if (closeTimeout == null || closeTimeout.isNegative()) {
      throw new IllegalArgumentException("closeTimeout must be non null and not negative but was " + closeTimeout);
    }
    long millis = closeTimeout.toMillis();
    boolean virtualRequested = policy == ThreadPolicy.VIRTUAL
        || (policy == ThreadPolicy.AUTO && runtime.virtualThreads());

    if (!virtualRequested) {
      return new PlatformThreadProvider(runtime, millis);
    }
    if (!runtime.virtualThreads()) {
      // Explicit request only: AUTO never lands here, since it resolves to platform instead.
      throw new ThreadCapabilityUnavailableException(
          policy, runtime, "virtual threads are not supported by this JVM");
    }
    return new VirtualThreadProvider(runtime, millis);
  }
}
