package com.barezen.core.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Startup rollback: which participant receives which down call, and what happens when cleanup itself
 * fails.
 */
class LifecycleRollbackTest {

  private static LifecycleOrchestrator.Builder builder() {
    return LifecycleOrchestrator.builder()
        .threadPolicy(ThreadPolicy.PLATFORM)
        .runtimeCapabilities(new RuntimeCapabilities(17, false, "test"))
        .registerShutdownHook(false);
  }

  @Test
  @DisplayName("initialize failure aborts the failing participant and stops the earlier ones in reverse")
  void failureInInitializeAbortsFailingAndStopsPreceding() {
    List<String> events = new ArrayList<>();
    LifecycleOrchestrator app = builder()
        .register("a", new RecordingParticipant("a", events))
        .register("b", new RecordingParticipant("b", events))
        .register("c", new RecordingParticipant("c", events).failingInitialize())
        .register("d", new RecordingParticipant("d", events))
        .build();

    assertThatThrownBy(app::start)
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("INITIALIZE")
        .hasMessageContaining("'c'");

    // d never ran, so it must not be stopped either. c gets abort(), a and b were fully initialized.
    assertThat(events).containsExactly(
        "a.initialize", "b.initialize", "c.initialize", "c.abort", "b.stop", "a.stop");
    assertThat(app.state()).isEqualTo(ApplicationState.FAILED);
  }

  @Test
  @DisplayName("start failure stops the failing participant instead of aborting it")
  void failureInStartStopsFailingParticipantNotAborts() {
    List<String> events = new ArrayList<>();
    LifecycleOrchestrator app = builder()
        .register("a", new RecordingParticipant("a", events))
        .register("b", new RecordingParticipant("b", events).failingStart())
        .register("c", new RecordingParticipant("c", events))
        .build();

    assertThatThrownBy(app::start)
        .isInstanceOf(StartupException.class)
        .satisfies(error -> assertThat(((StartupException) error).phase())
            .isEqualTo(StartupException.Phase.START));

    assertThat(events).containsExactly(
        "a.initialize", "b.initialize", "c.initialize",
        "a.start", "b.start", "c.stop", "b.stop", "a.stop");
    // c was initialized but never started: stop() must still release it, and nothing gets abort().
    assertThat(events).doesNotContain("b.abort", "c.abort");
  }

  @Test
  @DisplayName("startup failure closes the thread provider after participant rollback")
  void startupFailureClosesThreadProviderAfterRollback() {
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
    LifecycleOrchestrator app = builder()
        .threads(observing)
        .register("a", new RecordingParticipant("a", events))
        .register("b", new RecordingParticipant("b", events).failingStart())
        .build();

    assertThatThrownBy(app::start).isInstanceOf(StartupException.class);

    assertThat(events).containsExactly(
        "a.initialize", "b.initialize", "a.start", "b.start", "b.stop", "a.stop", "provider.close");
    assertThat(providerShutDown).isTrue();
    app.stop();
    assertThat(events).containsExactly(
        "a.initialize", "b.initialize", "a.start", "b.start", "b.stop", "a.stop", "provider.close");
  }

  @Test
  @DisplayName("abort and stop are never both called on the same participant")
  void abortAndStopAreMutuallyExclusive() {
    List<String> events = new ArrayList<>();
    LifecycleOrchestrator app = builder()
        .register("a", new RecordingParticipant("a", events))
        .register("b", new RecordingParticipant("b", events).failingInitialize())
        .build();

    assertThatThrownBy(app::start).isInstanceOf(StartupException.class);

    assertThat(events).contains("b.abort");
    assertThat(events).doesNotContain("b.stop");
  }

  @Test
  void noParticipantIsLeftEnteredAfterRollback() {
    List<String> events = new ArrayList<>();
    LifecycleOrchestrator app = builder()
        .register("a", new RecordingParticipant("a", events))
        .register("b", new RecordingParticipant("b", events).failingInitialize())
        .build();

    assertThatThrownBy(app::start).isInstanceOf(StartupException.class);

    // Whatever a caller does next must not run cleanup a second time.
    ShutdownReport report = app.stop();
    assertThat(app.state()).isEqualTo(ApplicationState.FAILED);
    assertThat(events).containsExactly("a.initialize", "b.initialize", "b.abort", "a.stop");
    assertThat(report.failures()).isEmpty();
  }

  @Test
  @DisplayName("the startup failure stays the reported cause while rollback failures are attached")
  void rollbackFailureNeverReplacesTheStartupFailure() {
    List<String> events = new ArrayList<>();
    LifecycleOrchestrator app = builder()
        .register("a", new RecordingParticipant("a", events).failingStop())
        .register("b", new RecordingParticipant("b", events).failingInitialize())
        .build();

    assertThatThrownBy(app::start)
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("b initialize failed")
        .satisfies(error -> {
          Throwable[] suppressed = error.getSuppressed();
          assertThat(suppressed).hasSize(1);
          assertThat(suppressed[0]).hasMessageContaining("a stop failed");
          assertThat(((StartupException) error).rollbackFailures())
              .extracting(ShutdownFailure::participantId)
              .containsExactly("a");
        });

    // The original failure must survive even for code that only prints getCause().
    assertThat(app.state()).isEqualTo(ApplicationState.FAILED);
    assertThat(app.stop().failures())
        .extracting(ShutdownFailure::participantId)
        .containsExactly("a");
  }

  @Test
  @DisplayName("a failure inside abort is reported without hiding the initialize failure")
  void abortFailureIsReportedAsRollbackFailure() {
    List<String> events = new ArrayList<>();
    LifecycleOrchestrator app = builder()
        .register("a", new RecordingParticipant("a", events).failingInitialize().failingAbort())
        .build();

    assertThatThrownBy(app::start)
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("a initialize failed")
        .satisfies(error -> assertThat(((StartupException) error).rollbackFailures())
            .extracting(ShutdownFailure::participantId)
            .containsExactly("a"));

    assertThat(events).containsExactly("a.initialize", "a.abort");
  }

  @Test
  @DisplayName("participants entered before the failing phase are rolled back even when several would fail")
  void everyEnteredParticipantIsAttemptedDuringRollback() {
    List<String> events = new ArrayList<>();
    LifecycleOrchestrator app = builder()
        .register("a", new RecordingParticipant("a", events).failingStop())
        .register("b", new RecordingParticipant("b", events))
        .register("c", new RecordingParticipant("c", events).failingStop())
        .register("d", new RecordingParticipant("d", events).failingStart())
        .build();

    assertThatThrownBy(app::start)
        .isInstanceOf(StartupException.class)
        .satisfies(error -> assertThat(((StartupException) error).rollbackFailures())
            .extracting(ShutdownFailure::participantId)
            .containsExactly("c", "a"));

    // d failed at start so it was stopped; b stopped cleanly; a and c reported failures. All were tried.
    assertThat(events).contains("d.stop", "c.stop", "b.stop", "a.stop");
  }
}
