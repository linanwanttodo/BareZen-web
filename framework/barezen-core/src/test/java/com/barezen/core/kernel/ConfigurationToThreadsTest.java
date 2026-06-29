package com.barezen.core.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.barezen.core.concurrent.RuntimeConfig;
import com.barezen.core.concurrent.ThreadCapabilityUnavailableException;
import com.barezen.core.concurrent.ThreadPolicy;
import com.barezen.core.concurrent.ThreadProvider.Kind;
import com.barezen.core.concurrent.ThreadProviders;
import com.barezen.core.config.Configuration;
import com.barezen.core.config.ConfigurationKey;
import com.barezen.core.config.ConfigurationSource;
import com.barezen.core.error.ConfigurationException;
import com.barezen.core.runtime.RuntimeCapabilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The chain Phase 2.2 exists to close, run end to end:
 *
 * <pre>
 *   configuration value -&gt; Configuration -&gt; RuntimeConfig -&gt; ThreadPolicy -&gt; ThreadProvider
 * </pre>
 *
 * <p>Tests elsewhere check one link each. This file checks that a value an operator wrote reaches the executor
 * threads, because that is where a wrong default, a swallowed type error or a silent downgrade would finally
 * become visible in production. Most cases state the runtime as a {@link RuntimeCapabilities} value instead of
 * assuming anything about the host JVM, so "virtual threads are refused where they are unavailable" is proven
 * on Java 21 as well as on Java 17; a nested class repeats the host-dependent versions.
 */
class ConfigurationToThreadsTest {

  /** A Java 17 runtime, which is the baseline and the one without virtual threads. */
  private static final RuntimeCapabilities JDK_17 = new RuntimeCapabilities(17, false, "test");

  /** A Java 21 runtime with virtual threads, stated rather than assumed about the host. */
  private static final RuntimeCapabilities JDK_21 = new RuntimeCapabilities(21, true, "test");

  private static Configuration threadsSetTo(String value) {
    return Configuration.of(fileLayer(value));
  }

  private static ConfigurationSource fileLayer(String value) {
    return layer("application.properties", RuntimeConfig.THREADS.name(), value);
  }

  private static LifecycleOrchestrator.Builder builder(RuntimeCapabilities runtime) {
    return LifecycleOrchestrator.builder().runtimeCapabilities(runtime).registerShutdownHook(false);
  }

  /** The link between a snapshot and the thread layer: the policy an operator's value resolves to. */
  private static ThreadPolicy policyOf(Configuration configuration) {
    return RuntimeConfig.from(configuration).threads();
  }

  /**
   * One named layer holding one value, spelled the way that layer spells it. A test cannot write its own
   * process environment or a real {@code -D} flag, so the layers the production wiring reads are supplied
   * here with their real names and their real key spelling.
   */
  private static ConfigurationSource layer(String name, String keyName, String value) {
    return new ConfigurationSource() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Optional<String> find(String lookedUp) {
        return lookedUp.equals(keyName) ? Optional.of(value) : Optional.empty();
      }
    };
  }

  private static ConfigurationSource envLayer(String value) {
    String variable = RuntimeConfig.THREADS.name().replace('.', '_').toUpperCase(java.util.Locale.ROOT);
    return layer("environment variables", variable, value);
  }

  @Test
  @DisplayName("an unset key resolves to auto, the one default the framework ships")
  void absentKeyDefaultsToAuto() {
    assertThat(policyOf(Configuration.inMemory(Map.of()))).isEqualTo(ThreadPolicy.AUTO);
    assertThat(RuntimeConfig.from(Configuration.inMemory(Map.of())))
        .isEqualTo(new RuntimeConfig(ThreadPolicy.AUTO));
  }

  @Test
  void configurationValuesBindToEveryPolicy() {
    assertThat(policyOf(threadsSetTo("auto"))).isEqualTo(ThreadPolicy.AUTO);
    assertThat(policyOf(threadsSetTo("platform"))).isEqualTo(ThreadPolicy.PLATFORM);
    assertThat(policyOf(threadsSetTo("virtual"))).isEqualTo(ThreadPolicy.VIRTUAL);
    // Constants are upper case in Java and lower case in configuration; both spellings resolve.
    assertThat(policyOf(threadsSetTo("VIRTUAL"))).isEqualTo(ThreadPolicy.VIRTUAL);
  }

  @Test
  @DisplayName("a misspelled policy fails instead of falling back to auto")
  void aTypoIsNotSilentlyAuto() {
    // The failure mode the error contract exists for: "abc" read as auto would start the application on a
    // thread model nobody asked for, while looking healthy.
    assertThatThrownBy(() -> policyOf(threadsSetTo("abc")))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("abc")
        .hasMessageContaining(RuntimeConfig.THREADS.name())
        .hasMessageContaining("auto, platform, virtual");
  }

  @Test
  @DisplayName("the highest configured layer decides the thread model")
  void precedenceReachesTheThreadDecision() {
    // The specification's worked example: file says platform, environment says virtual, command line says
    // platform. Platform must win, so an operator who overrides a container default is not left on virtual.
    Configuration configuration = Configuration.of(
        fileLayer("platform"),
        envLayer("virtual"),
        layer("system properties", RuntimeConfig.THREADS.name(), "platform"));

    assertThat(policyOf(configuration)).isEqualTo(ThreadPolicy.PLATFORM);
    assertThat(ThreadProviders.create(policyOf(configuration), JDK_21).kind()).isEqualTo(Kind.PLATFORM);
  }

  @Test
  @DisplayName("the policy is the only thing configuration tells the thread layer")
  void threadLayerSeesAPolicyAndNotAConfiguration() {
    // RuntimeConfig holds no Configuration reference, so nothing below it can go back for another key after
    // the assembler has decided. That is what keeps the chain one directional at runtime, not just in imports.
    assertThat(RuntimeConfig.from(threadsSetTo("virtual")).getClass().getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getType)
        .containsExactly(ThreadPolicy.class);
  }

  @Test
  @DisplayName("a provider is built from a policy and a runtime, never from a configuration")
  void threadProvidersHasNoConfigurationEntryPoint() {
    // If ThreadProviders grew a create(Configuration) overload, the thread layer could resolve its own
    // settings and the single decision point in the chain would be gone.
    assertThat(java.util.Arrays.stream(ThreadProviders.class.getDeclaredMethods())
        .filter(method -> !method.isSynthetic()))
        .allSatisfy(method -> assertThat(method.getName()).isEqualTo("create"));
    assertThat(java.util.Arrays.stream(ThreadProviders.class.getDeclaredMethods())
        .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes())))
        .doesNotContain(Configuration.class);
  }

  @Nested
  class AgainstASimulatedRuntime {

    @Test
    @DisplayName("auto picks platform on a Java 17 runtime")
    void autoOnJdk17IsPlatform() {
      assertThat(ThreadProviders.create(policyOf(threadsSetTo("auto")), JDK_17).kind())
          .isEqualTo(Kind.PLATFORM);
    }

    @Test
    @DisplayName("auto picks virtual on a Java 21 runtime")
    void autoOnJdk21IsVirtual() {
      assertThat(ThreadProviders.create(policyOf(threadsSetTo("auto")), JDK_21).kind())
          .isEqualTo(Kind.VIRTUAL);
    }

    @Test
    @DisplayName("platform stays platform on a runtime that has virtual threads")
    void platformIsNotUpgradedBecauseItIsAvailable() {
      assertThat(ThreadProviders.create(policyOf(threadsSetTo("platform")), JDK_21).kind())
          .isEqualTo(Kind.PLATFORM);
    }

    @Test
    @DisplayName("an explicit virtual request on Java 17 fails rather than degrading")
    void explicitVirtualOnUnsupportedRuntimeFails() {
      assertThatThrownBy(() -> ThreadProviders.create(policyOf(threadsSetTo("virtual")), JDK_17))
          .isInstanceOf(ThreadCapabilityUnavailableException.class)
          .hasMessageContaining("never downgraded")
          .hasMessageContaining("jdk=17");
    }

    @Test
    void explicitVirtualOnASupportingRuntimeIsHonoured() {
      assertThat(ThreadProviders.create(policyOf(threadsSetTo("virtual")), JDK_21).kind())
          .isEqualTo(Kind.VIRTUAL);
    }

    @Test
    @DisplayName("the orchestrator applies the same rule when it is built")
    void orchestratorRefusesToBuildOnAnUnsatisfiableRequest() {
      List<String> events = new ArrayList<>();

      // The application's assembly code resolves the snapshot to a policy; the orchestrator only ever sees a
      // policy. kernel may not depend on config, so no part of this chain happens inside the builder (ADR-0015).
      assertThatThrownBy(() -> builder(JDK_17)
          .threadPolicy(policyOf(threadsSetTo("virtual")))
          .register("worker", new RecordingParticipant("worker", events))
          .build())
          .isInstanceOf(ThreadCapabilityUnavailableException.class);
      // Refused before any participant ran: a configuration the runtime cannot honour must not half start.
      assertThat(events).isEmpty();
    }

    @Test
    void orchestratorBuildsWhenTheRequestIsSatisfiable() {
      // A satisfied request simply builds, and closing it is legal without starting it first. What kind the
      // provider ended up being is the provider's own assertion (the cases above); the orchestrator keeps it
      // private rather than exposing a diagnostic getter no application was asking for (ADR-0015).
      try (LifecycleOrchestrator app = builder(JDK_21)
          .threadPolicy(policyOf(threadsSetTo("virtual")))
          .build()) {
        assertThat(app.state()).isEqualTo(ApplicationState.NEW);
      }
    }
  }

  /**
   * The same decisions taken against the JVM running the build, which is what the CI matrix on 17 and 21
   * compares. Only these depend on the host, so only these carry assumptions; a failure here would mean the
   * probe and the policy disagree about what the host can do.
   */
  @Nested
  class AgainstTheHostRuntime {

    @Test
    @DisplayName("auto yields the provider this JVM prefers")
    void autoFollowsTheHostCapability() {
      RuntimeCapabilities host = RuntimeCapabilities.detect();
      ThreadPolicy policy = policyOf(Configuration.inMemory(Map.of()));

      Kind resolved = ThreadProviders.create(policy, host).kind();
      assertThat(resolved).isEqualTo(host.virtualThreads() ? Kind.VIRTUAL : Kind.PLATFORM);
      // The same policy the host resolves to builds an orchestrator without refusing, so the default chain
      // cannot strand an application on a host the probe already accepted.
      LifecycleOrchestrator app = builder(host).threadPolicy(policy).build();
      assertThat(app.state()).isEqualTo(ApplicationState.NEW);
      app.close();
    }

    @Test
    @DisplayName("on a host without virtual threads an explicit request fails the build")
    void virtualFailsWhereItIsUnavailable() {
      assumeTrue(!RuntimeCapabilities.detect().virtualThreads(), "host provides virtual threads");

      assertThatThrownBy(() -> builder(RuntimeCapabilities.detect())
          .threadPolicy(policyOf(threadsSetTo("virtual")))
          .build())
          .isInstanceOf(ThreadCapabilityUnavailableException.class);
    }

    @Test
    @DisplayName("on a host with virtual threads an explicit request is honoured")
    void virtualSucceedsWhereItIsAvailable() {
      assumeTrue(RuntimeCapabilities.detect().virtualThreads(), "host lacks virtual threads");

      // Honoured means the host accepts the request rather than degrading it, which is what a satisfiable
      // build proves; the kind itself is the provider's own assertion above.
      LifecycleOrchestrator app = builder(RuntimeCapabilities.detect())
          .threadPolicy(policyOf(threadsSetTo("virtual")))
          .build();

      assertThat(app.state()).isEqualTo(ApplicationState.NEW);
      app.close();
    }

    @Test
    @DisplayName("platform starts and stops on the host whatever it is")
    void platformRunsAWholeLifecycleOnTheHost() {
      List<String> events = new ArrayList<>();
      LifecycleOrchestrator app = builder(RuntimeCapabilities.detect())
          .threadPolicy(policyOf(threadsSetTo("platform")))
          .register("worker", new RecordingParticipant("worker", events))
          .build();
      app.start();

      assertThat(app.state()).isEqualTo(ApplicationState.RUNNING);
      assertThat(app.stop().clean()).isTrue();
      assertThat(events).containsExactly("worker.initialize", "worker.start", "worker.stop");
    }
  }

  /**
   * A key that resolves to nothing can only happen if a future key forgets its default, because THREADS
   * declares one. The guard says so rather than letting a null reach the record constructor.
   */
  @Test
  void aResolutionToNothingIsReportedNotUnboxed() {
    ConfigurationKey<ThreadPolicy> noDefault =
        ConfigurationKey.of(RuntimeConfig.THREADS.name(), ThreadPolicy.class);
    ConfigurationSource refusesEverything = new ConfigurationSource() {
      @Override
      public String name() {
        return "empty";
      }

      @Override
      public Optional<String> find(String keyName) {
        return Optional.empty();
      }
    };

    assertThat(Configuration.of(refusesEverything).get(noDefault)).isEmpty();
    assertThatThrownBy(() -> new RuntimeConfig(Configuration.of(refusesEverything).get(noDefault).orElse(null)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("threads policy");
  }
}
