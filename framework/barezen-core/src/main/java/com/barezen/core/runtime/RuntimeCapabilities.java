package com.barezen.core.runtime;

import java.util.concurrent.ExecutorService;

/**
 * Immutable snapshot of the runtime the application is executing on.
 *
 * <p>Phase 2.1 records exactly one capability, because that is the only one with a consumer: virtual
 * thread support, which decides the {@code ThreadProvider} implementation. Fields for ALPN, TLS or
 * cryptographic providers are deliberately absent; a capability is added here only once a module
 * actually has to branch on it (ADR-0002, ADR-0010).
 *
 * @param javaFeature {@code Runtime.version().feature()}, for example {@code 17} or {@code 21}
 * @param virtualThreads {@code true} when this runtime exposes virtual threads
 * @param vendor VM vendor, taken from {@code java.vm.vendor}
 */
public record RuntimeCapabilities(int javaFeature, boolean virtualThreads, String vendor) {

  public RuntimeCapabilities {
    if (javaFeature <= 0) {
      throw new IllegalArgumentException("javaFeature must be positive but was " + javaFeature);
    }
    if (vendor == null || vendor.isBlank()) {
      throw new IllegalArgumentException("vendor must not be blank");
    }
  }

  /** Reads the capabilities of the JVM this code runs in. */
  public static RuntimeCapabilities detect() {
    return new RuntimeCapabilities(
        Runtime.version().feature(),
        VirtualThreadProbe.supported(),
        System.getProperty("java.vm.vendor", "unknown"));
  }

  /**
   * Creates an executor running each task on its own virtual thread.
   *
   * <p>Belongs here rather than in a separate support class so that capability detection and capability
   * instantiation stay one seam with a single handle, and so that no second place in the framework is
   * tempted to probe the runtime itself.
   *
   * @return a new per-task virtual thread executor
   * @throws IllegalStateException when {@link #virtualThreads()} is {@code false}; callers check the
   *     flag first, which {@code ThreadProviders} does
   */
  public ExecutorService newVirtualThreadPerTaskExecutor() {
    if (!virtualThreads) {
      throw new IllegalStateException(
          "virtual threads were reported unavailable for javaFeature=" + javaFeature);
    }
    return VirtualThreadProbe.newPerTaskExecutor();
  }

  /** @return human readable form for startup logs */
  @Override
  public String toString() {
    return "RuntimeCapabilities[jdk=" + javaFeature + ", virtualThreads=" + virtualThreads
        + ", vendor=" + vendor + "]";
  }
}
