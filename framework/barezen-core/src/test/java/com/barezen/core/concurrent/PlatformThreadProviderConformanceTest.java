package com.barezen.core.concurrent;

import com.barezen.core.runtime.RuntimeCapabilities;
import java.time.Duration;

/**
 * Runs the {@link ThreadProvider} conformance suite against the platform implementation, which must pass
 * on every supported JDK.
 */
class PlatformThreadProviderConformanceTest extends ThreadProviderConformanceTest {

  @Override
  ThreadProvider create(long closeTimeoutMillis) {
    // Deliberately through the factory: the platform provider must be reachable on any runtime, so a
    // resolution failure here is itself a conformance failure.
    return ThreadProviders.create(
        ThreadPolicy.PLATFORM, new RuntimeCapabilities(17, false, "test"),
        Duration.ofMillis(closeTimeoutMillis));
  }

  @Override
  ThreadProvider.Kind expectedKind() {
    return ThreadProvider.Kind.PLATFORM;
  }
}
