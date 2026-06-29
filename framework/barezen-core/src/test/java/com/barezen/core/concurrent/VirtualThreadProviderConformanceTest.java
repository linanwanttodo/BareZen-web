package com.barezen.core.concurrent;

import com.barezen.core.runtime.RuntimeCapabilities;
import java.time.Duration;

/**
 * Runs the {@link ThreadProvider} conformance suite against the virtual implementation.
 *
 * <p>Skipped through an assumption on JDK 17, where the capability is genuinely absent. That is the
 * intended shape: the suite is proof that the two providers behave identically, and on a runtime without
 * virtual threads there is nothing to compare.
 */
class VirtualThreadProviderConformanceTest extends ThreadProviderConformanceTest {

  @Override
  ThreadProvider create(long closeTimeoutMillis) {
    RuntimeCapabilities runtime = RuntimeCapabilities.detect();
    if (!runtime.virtualThreads()) {
      return null;
    }
    return ThreadProviders.create(
        ThreadPolicy.VIRTUAL, runtime, Duration.ofMillis(closeTimeoutMillis));
  }

  @Override
  ThreadProvider.Kind expectedKind() {
    return ThreadProvider.Kind.VIRTUAL;
  }
}
