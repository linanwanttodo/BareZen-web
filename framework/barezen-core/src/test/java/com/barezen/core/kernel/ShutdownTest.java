package com.barezen.core.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import com.barezen.core.concurrent.ThreadPolicy;
import com.barezen.core.concurrent.ThreadProvider;
import com.barezen.core.runtime.RuntimeCapabilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Orderly shutdown behaviour: every participant is attempted, failures are reported, and nothing escapes
 * as an exception.
 */
class ShutdownTest {

  private static LifecycleOrchestrator.Builder builder() {
    return LifecycleOrchestrator.builder()
        .threadPolicy(ThreadPolicy.PLATFORM)
        .runtimeCapabilities(new RuntimeCapabilities(17, false, "test"))
        .registerShutdownHook(false);
  }

  @Test
  @DisplayName("one failing participant does not prevent the rest from being stopped")
  void stopAttemptsEveryParticipantEvenWhenOneThrows() {
    List<String> events = new ArrayList<>();
    LifecycleOrchestrator app = builder()
        .register("a", new RecordingParticipant("a", events))
        .register("b", new RecordingParticipant("b", events).failingStop())
        .register("c", new RecordingParticipant("c", events).failingStop())
        .register("d", new RecordingParticipant("d", events))
        .build();
    app.start();
    events.clear();

    ShutdownReport report = app.stop();

    assertThat(events).containsExactly("d.stop", "c.stop", "b.stop", "a.stop");
    assertThat(report.clean()).isFalse();
    assertThat(report.failures())
        .extracting(ShutdownFailure::participantId)
        .containsExactly("c", "b");
    assertThat(report.failures()).allSatisfy(failure ->
        assertThat(failure.cause()).hasMessageContaining(failure.participantId() + " stop failed"));
  }

  @Test
  void stopNeverThrowsForAnyParticipantFailureKind() {
    List<String> events = new ArrayList<>();
    LifecycleOrchestrator app = builder()
        .register("errorThrower", new RecordingParticipant("errorThrower", events)
            .failingStop(new IllegalStateException("ignored")))
        .register("npeThrower", new RecordingParticipant("npeThrower", events)
            .failingStop(new NullPointerException("no message")))
        .build();
    app.start();

    assertThat(app.stop().failures()).hasSize(2);
    assertThat(app.state()).isEqualTo(ApplicationState.STOPPED);
  }

  @Test
  @DisplayName("a clean shutdown reports no failures")
  void cleanShutdownReportsClean() {
    List<String> events = new ArrayList<>();
    LifecycleOrchestrator app = builder()
        .register("a", new RecordingParticipant("a", events))
        .build();
    app.start();

    assertThat(app.stop().clean()).isTrue();
  }

  @Test
  void shutdownHookIsInstalledOnStartAndRemovedOnStop() {
    List<String> events = new ArrayList<>();
    CapturingShutdownHookRegistry registry = new CapturingShutdownHookRegistry();
    LifecycleOrchestrator app = LifecycleOrchestrator.builder()
        .threadPolicy(ThreadPolicy.PLATFORM)
        .runtimeCapabilities(new RuntimeCapabilities(17, false, "test"))
        .shutdownHookRegistry(registry)
        .register("a", new RecordingParticipant("a", events))
        .build();

    assertThat(registry.addedCount()).isZero();

    app.start();
    assertThat(registry.addedCount()).isEqualTo(1);

    app.stop();
    assertThat(registry.removedCount()).isEqualTo(1);
    assertThat(events).containsExactly("a.initialize", "a.start", "a.stop");
  }

  @Test
  @DisplayName("a hook removal failure during JVM shutdown cannot skip participant or provider cleanup")
  void hookRemovalFailureDoesNotInterruptShutdown() {
    List<String> events = new ArrayList<>();
    AtomicBoolean providerShutDown = new AtomicBoolean();
    ThreadProvider observing = new ThreadProvider() {
      @Override
      public Kind kind() {
        return Kind.PLATFORM;
      }

      @Override
      public String name() {
        return "observing";
      }

      @Override
      public boolean preferred() {
        return true;
      }

      @Override
      public ExecutorService newBoundedExecutor(int maxConcurrency, String namePrefix) {
        return Executors.newFixedThreadPool(maxConcurrency);
      }

      @Override
      public ExecutorService newUnboundedExecutor(String namePrefix) {
        return Executors.newCachedThreadPool();
      }

      @Override
      public void close() {
        providerShutDown.set(true);
        events.add("provider.close");
      }
    };
    CapturingShutdownHookRegistry registry = new CapturingShutdownHookRegistry(
        new IllegalStateException("shutdown in progress"));
    LifecycleOrchestrator app = LifecycleOrchestrator.builder()
        .threads(observing)
        .runtimeCapabilities(new RuntimeCapabilities(17, false, "test"))
        .shutdownHookRegistry(registry)
        .register("a", new RecordingParticipant("a", events))
        .build();
    app.start();
    events.clear();

    ShutdownReport report = app.stop();

    assertThat(report.clean()).isTrue();
    assertThat(app.state()).isEqualTo(ApplicationState.STOPPED);
    assertThat(events).containsExactly("a.stop", "provider.close");
    assertThat(providerShutDown).isTrue();
  }

  @Test
  @DisplayName("a registered hook stops the application, which is the process termination path")
  void firingHookStopsApplication() {
    List<String> events = new ArrayList<>();
    CapturingShutdownHookRegistry registry = new CapturingShutdownHookRegistry();
    LifecycleOrchestrator app = LifecycleOrchestrator.builder()
        .threadPolicy(ThreadPolicy.PLATFORM)
        .runtimeCapabilities(new RuntimeCapabilities(17, false, "test"))
        .shutdownHookRegistry(registry)
        .register("a", new RecordingParticipant("a", events))
        .build();
    app.start();
    events.clear();

    registry.fireRegisteredHooks();

    assertThat(app.state()).isEqualTo(ApplicationState.STOPPED);
    assertThat(events).containsExactly("a.stop");
  }

  @Test
  void failedStartupDoesNotInstallAHook() {
    List<String> events = new ArrayList<>();
    CapturingShutdownHookRegistry registry = new CapturingShutdownHookRegistry();
    LifecycleOrchestrator app = LifecycleOrchestrator.builder()
        .threadPolicy(ThreadPolicy.PLATFORM)
        .runtimeCapabilities(new RuntimeCapabilities(17, false, "test"))
        .shutdownHookRegistry(registry)
        .register("a", new RecordingParticipant("a", events).failingInitialize())
        .build();

    try {
      app.start();
    } catch (StartupException expected) {
      assertThat(expected.participantId()).isEqualTo("a");
    }

    assertThat(registry.addedCount()).isZero();
    assertThat(app.state()).isEqualTo(ApplicationState.FAILED);
  }

  @Test
  void shutdownHookCanBeDisabledForHostManagedProcesses() {
    List<String> events = new ArrayList<>();
    CapturingShutdownHookRegistry registry = new CapturingShutdownHookRegistry();
    LifecycleOrchestrator app = LifecycleOrchestrator.builder()
        .threadPolicy(ThreadPolicy.PLATFORM)
        .runtimeCapabilities(new RuntimeCapabilities(17, false, "test"))
        .shutdownHookRegistry(registry)
        .registerShutdownHook(false)
        .register("a", new RecordingParticipant("a", events))
        .build();
    app.start();

    assertThat(registry.addedCount()).isZero();
    app.close();
  }
}
