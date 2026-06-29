package com.barezen.core.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.core.runtime.RuntimeCapabilities;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Policy resolution rules, which is where "an explicitly requested capability never degrades silently"
 * lives.
 */
class ThreadProvidersTest {

  private static final RuntimeCapabilities JDK17 = new RuntimeCapabilities(17, false, "test");
  private static final RuntimeCapabilities JDK21 = new RuntimeCapabilities(21, true, "test");

  @Test
  void autoResolvesToPlatformWhenVirtualThreadsAreUnavailable() {
    ThreadProvider provider = ThreadProviders.create(ThreadPolicy.AUTO, JDK17);
    try {
      assertThat(provider.kind()).isEqualTo(ThreadProvider.Kind.PLATFORM);
      assertThat(provider.preferred()).isTrue();
    } finally {
      provider.close();
    }
  }

  @Test
  void autoResolvesToVirtualWhenAvailableOnThisRuntime() {
    RuntimeCapabilities detected = RuntimeCapabilities.detect();
    ThreadProvider provider = ThreadProviders.create(ThreadPolicy.AUTO, detected);
    try {
      assertThat(provider.kind())
          .isEqualTo(detected.virtualThreads()
              ? ThreadProvider.Kind.VIRTUAL
              : ThreadProvider.Kind.PLATFORM);
      assertThat(provider.preferred()).isTrue();
    } finally {
      provider.close();
    }
  }

  @Test
  @DisplayName("platform is honoured even on a runtime that could do better")
  void explicitPlatformNeverBecomesVirtual() {
    ThreadProvider provider = ThreadProviders.create(ThreadPolicy.PLATFORM, JDK21);
    try {
      assertThat(provider.kind()).isEqualTo(ThreadProvider.Kind.PLATFORM);
      // Not the recommendation on a JDK 21 runtime, which diagnostics have to reflect honestly.
      assertThat(provider.preferred()).isFalse();
    } finally {
      provider.close();
    }
  }

  @Test
  @DisplayName("explicit virtual on an unsupported runtime fails startup instead of degrading")
  void explicitVirtualOnUnsupportedRuntimeFails() {
    assertThatThrownBy(() -> ThreadProviders.create(ThreadPolicy.VIRTUAL, JDK17))
        .isInstanceOf(ThreadCapabilityUnavailableException.class)
        .hasMessageContaining("VIRTUAL")
        .hasMessageContaining("never downgraded");
  }

  /**
   * The message may name only the request and the runtime. Naming a configuration key would make a Phase 2.1
   * lifecycle failure depend on the API that supplies it (ADR-0013).
   */
  @Test
  void failureMessageDescribesTheRequestAndRuntimeOnly() {
    assertThatThrownBy(() -> ThreadProviders.create(ThreadPolicy.VIRTUAL, JDK17))
        .isInstanceOf(ThreadCapabilityUnavailableException.class)
        .hasMessageContaining("runtime=RuntimeCapabilities[jdk=17")
        .satisfies(error -> assertThat(error.getMessage()).doesNotContainIgnoringCase("barezen."));
  }

  @Test
  void unsupportedRequestReportsTheRequestedPolicy() {
    assertThatThrownBy(() -> ThreadProviders.create(ThreadPolicy.VIRTUAL, JDK17))
        .isInstanceOfSatisfying(ThreadCapabilityUnavailableException.class,
            error -> assertThat(error.requestedPolicy()).isEqualTo(ThreadPolicy.VIRTUAL));
  }

  @Test
  void nullArgumentsAreRejectedWithReadableMessages() {
    assertThatThrownBy(() -> ThreadProviders.create(null, JDK17))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("policy");
    assertThatThrownBy(() -> ThreadProviders.create(ThreadPolicy.AUTO, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("runtime");
    assertThatThrownBy(() -> ThreadProviders.create(ThreadPolicy.AUTO, JDK17, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("closeTimeout");
    assertThatThrownBy(() -> ThreadProviders.create(ThreadPolicy.AUTO, JDK17, Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a provider never exposes a way to look executors up by name")
  void providerIsAFactoryNotARegistry() {
    // The compile surface is the assertion: ThreadProvider has exactly the factory methods, no lookup,
    // no registration. This test documents the boundary checked further by ArchUnit.
    assertThat(ThreadProvider.class.getDeclaredMethods())
        .extracting(java.lang.reflect.Method::getName)
        .containsExactlyInAnyOrder("kind", "name", "preferred", "newBoundedExecutor",
            "newUnboundedExecutor", "close");
  }

  @Nested
  class Detection {

    @Test
    void detectReportsTheActualRuntime() {
      RuntimeCapabilities detected = RuntimeCapabilities.detect();
      assertThat(detected.javaFeature()).isEqualTo(Runtime.version().feature());
      assertThat(detected.vendor()).isNotBlank();
      assertThat(detected.virtualThreads()).isEqualTo(Runtime.version().feature() >= 21);
    }

    @Test
    void virtualExecutorCreationFollowsTheReportedFlag() {
      RuntimeCapabilities unsupported = new RuntimeCapabilities(17, false, "test");
      assertThatThrownBy(unsupported::newVirtualThreadPerTaskExecutor)
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructionValidatesTheSnapshot() {
      assertThatThrownBy(() -> new RuntimeCapabilities(0, false, "test"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new RuntimeCapabilities(17, false, " "))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a virtual provider refuses capabilities that deny virtual threads")
    void virtualProviderRejectsUnsupportedCapabilities() {
      assertThatThrownBy(() -> new VirtualThreadProvider(JDK17, 1_000L))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executorFromADetectedRuntimeIsUsable() throws Exception {
      RuntimeCapabilities detected = RuntimeCapabilities.detect();
      if (!detected.virtualThreads()) {
        return;
      }
      ExecutorService executor = detected.newVirtualThreadPerTaskExecutor();
      try {
        assertThat(executor.submit(() -> Thread.currentThread().getName()).get()).isNotNull();
      } finally {
        executor.shutdownNow();
      }
    }
  }
}
