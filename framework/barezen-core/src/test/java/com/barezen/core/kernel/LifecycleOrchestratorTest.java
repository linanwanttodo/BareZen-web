package com.barezen.core.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.core.concurrent.ThreadPolicy;
import com.barezen.core.concurrent.ThreadProvider;
import com.barezen.core.error.LifecycleException;
import com.barezen.core.runtime.RuntimeCapabilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Startup ordering, state machine and idempotence of {@link LifecycleOrchestrator}.
 */
class LifecycleOrchestratorTest {

  private static LifecycleOrchestrator.Builder builder() {
    // Platform threads keep the orchestration tests independent of the runtime they execute on, and the
    // JVM hook is disabled here so a failing assertion cannot leave a hook registered in the test JVM.
    return LifecycleOrchestrator.builder()
        .threadPolicy(ThreadPolicy.PLATFORM)
        .runtimeCapabilities(new RuntimeCapabilities(17, false, "test"))
        .registerShutdownHook(false);
  }

  @Test
  @DisplayName("every participant is initialized before any participant is started")
  void startRunsGlobalInitializePhaseBeforeGlobalStartPhase() {
    List<String> events = new ArrayList<>();
    LifecycleOrchestrator app = builder()
        .register("a", new RecordingParticipant("a", events))
        .register("b", new RecordingParticipant("b", events))
        .register("c", new RecordingParticipant("c", events))
        .build();

    app.start();

    assertThat(events).containsExactly(
        "a.initialize", "b.initialize", "c.initialize",
        "a.start", "b.start", "c.start");
    assertThat(app.state()).isEqualTo(ApplicationState.RUNNING);
    app.close();
  }

  @Test
  @DisplayName("shutdown runs participants in reverse registration order")
  void stopRunsParticipantsInReverseOrder() {
    List<String> events = new ArrayList<>();
    LifecycleOrchestrator app = builder()
        .register("a", new RecordingParticipant("a", events))
        .register("b", new RecordingParticipant("b", events))
        .build();
    app.start();
    events.clear();

    app.stop();

    assertThat(events).containsExactly("b.stop", "a.stop");
    assertThat(app.state()).isEqualTo(ApplicationState.STOPPED);
  }

  @Test
  void participantIdsAreReportedInRegistrationOrder() {
    List<String> events = new ArrayList<>();
    try (LifecycleOrchestrator app = builder()
        .register("first", new RecordingParticipant("first", events))
        .register("second", new RecordingParticipant("second", events))
        .build()) {
      assertThat(app.participantIds()).containsExactly("first", "second");
    }
  }

  @Test
  void stopAfterStartIsIdempotentAndInvokesParticipantsOnce() {
    List<String> events = new ArrayList<>();
    LifecycleOrchestrator app = builder()
        .register("a", new RecordingParticipant("a", events))
        .build();
    app.start();
    events.clear();

    ShutdownReport first = app.stop();
    ShutdownReport second = app.stop();
    app.close();

    assertThat(events).containsExactly("a.stop");
    assertThat(second).isSameAs(first);
    assertThat(first.clean()).isTrue();
  }

  @Test
  @DisplayName("stop and close on a never started application are legal and release the thread provider")
  void stopBeforeStartMovesNewStraightToStopped() {
    List<String> events = new ArrayList<>();
    LifecycleOrchestrator app = builder()
        .register("a", new RecordingParticipant("a", events))
        .build();

    ShutdownReport report = app.stop();

    assertThat(app.state()).isEqualTo(ApplicationState.STOPPED);
    assertThat(report.clean()).isTrue();
    assertThat(events).isEmpty();
    // A second close must not resurrect anything.
    app.close();
    assertThat(app.state()).isEqualTo(ApplicationState.STOPPED);
  }

  @Test
  @DisplayName("the owned thread provider is released after the last participant")
  void threadProviderIsReleasedAfterAllParticipants() {
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

    LifecycleOrchestrator app = LifecycleOrchestrator.builder()
        .threads(observing)
        .registerShutdownHook(false)
        .register("a", new RecordingParticipant("a", events))
        .register("b", new RecordingParticipant("b", events)
            // A participant may submit cleanup work, so the provider must still be alive here.
            .duringStop(() -> assertThat(providerShutDown).isFalse()))
        .build();
    app.start();
    events.clear();

    app.stop();

    assertThat(events).containsExactly("b.stop", "a.stop", "provider.close");
    assertThat(providerShutDown).isTrue();
  }

  @Test
  void tryWithResourcesClosesWithoutStart() {
    List<String> events = new ArrayList<>();
    try (LifecycleOrchestrator app = builder()
        .register("counted", new RecordingParticipant("counted", events))
        .build()) {
      assertThat(app.state()).isEqualTo(ApplicationState.NEW);
    }
    assertThat(events).isEmpty();
  }

  @Nested
  class StateMachine {

    @Test
    void startTwiceIsRejected() {
      List<String> events = new ArrayList<>();
      LifecycleOrchestrator app = builder().register("a", new RecordingParticipant("a", events)).build();
      app.start();

      assertThatThrownBy(app::start)
          .isInstanceOf(LifecycleException.class)
          .hasMessageContaining("cannot start in state RUNNING");
      assertThat(events).containsExactly("a.initialize", "a.start");
      app.close();
    }

    @Test
    void startAfterStopIsRejected() {
      List<String> events = new ArrayList<>();
      LifecycleOrchestrator app = builder().register("a", new RecordingParticipant("a", events)).build();
      app.start();
      app.stop();

      assertThatThrownBy(app::start).isInstanceOf(LifecycleException.class);
    }

    @Test
    @Timeout(10)
    void concurrentStartExecutesUpPhasesExactlyOnce() throws Exception {
      List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
      LifecycleOrchestrator app = builder()
          .register("a", new RecordingParticipant("a", events).duringInitialize(() -> sleep(20)))
          .build();

      int racers = 4;
      ExecutorService pool = Executors.newFixedThreadPool(racers);
      CountDownLatch ready = new CountDownLatch(racers);
      CountDownLatch go = new CountDownLatch(1);
      AtomicInteger successes = new AtomicInteger();
      AtomicInteger rejections = new AtomicInteger();
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < racers; i++) {
        futures.add(pool.submit(() -> {
          ready.countDown();
          go.await();
          try {
            app.start();
            successes.incrementAndGet();
          } catch (LifecycleException expected) {
            rejections.incrementAndGet();
          }
          return null;
        }));
      }
      ready.await();
      go.countDown();
      for (Future<?> future : futures) {
        future.get(5, TimeUnit.SECONDS);
      }
      pool.shutdownNow();

      assertThat(successes.get()).isEqualTo(1);
      assertThat(rejections.get()).isEqualTo(racers - 1);
      assertThat(events).containsExactly("a.initialize", "a.start");
      app.close();
    }

    @Test
    void reentrantStopDuringStartupDoesNotCutIntoRollbackOwnership() {
      List<String> events = new ArrayList<>();
      LifecycleOrchestrator[] handle = new LifecycleOrchestrator[1];
      // A participant that calls stop() while startup owns the lock is the only way STARTING is observable
      // from outside. Cleanup must stay with the startup path, so stop() must be a no-op here.
      handle[0] = builder()
          .register("stopper", new RecordingParticipant("stopper", events)
              .duringInitialize(() ->
                  events.add("stopper.stopReturned." + handle[0].stop().clean())))
          .register("a", new RecordingParticipant("a", events))
          .build();

      handle[0].start();

      assertThat(handle[0].state()).isEqualTo(ApplicationState.RUNNING);
      assertThat(events).containsExactly(
          "stopper.initialize", "stopper.stopReturned.true", "a.initialize",
          "stopper.start", "a.start");
      handle[0].close();
      assertThat(events).endsWith("a.stop", "stopper.stop");
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
