package com.barezen.core.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.core.concurrent.ThreadPolicy;
import com.barezen.core.di.ServiceAssembly;
import com.barezen.core.error.AssemblyException;
import com.barezen.core.runtime.RuntimeCapabilities;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The boundary ADR-0015 describes, exercised the way an application's own assembly code exercises it.
 *
 * <p>BareZen has no application object: nothing in the framework owns the sequence below, because the sequence
 * <em>is</em> the application. This file is the executable form of that claim - it stands in for {@code main}, and
 * it is the only place in the build allowed to hold both a {@link ServiceAssembly} and a
 * {@link LifecycleOrchestrator}, because assembling them into one application is exactly what that code is for.
 *
 * <p>What each test measures is a rule the two layers cannot check themselves:
 * <ul>
 *   <li>startup order: configuration, then capabilities, then objects, then the orchestrator, then start;
 *   <li>which of the two release paths runs when, and that both run;
 *   <li>that a participant may still use assembled infrastructure while it is stopping;
 *   <li>that closing twice releases nothing twice.
 * </ul>
 */
class ApplicationAssemblyBoundaryTest {

  private final List<String> events = new ArrayList<>();

  /** Infrastructure the assembly created, so the assembly releases it. */
  private final class ConnectionPool implements AutoCloseable {
    private boolean open = true;

    ConnectionPool() {
      events.add("create pool");
    }

    void usedWhileStopping() {
      assertThat(open).as("the pool is the assembly's to close, so it is still open while participants stop")
          .isTrue();
    }

    @Override
    public void close() {
      events.add("close pool");
      open = false;
    }
  }

  /**
   * A participant that is also closeable, registered as an {@code instance} so the assembly hands it over without
   * taking ownership. This is the shape where two owners would be a real hazard: both {@code stop()} and
   * {@code close()} could reach the same object.
   */
  private final class SessionStore implements LifecycleParticipant, AutoCloseable {

    SessionStore() {
      events.add("create store");
    }

    @Override
    public void initialize() {
      events.add("store.initialize");
    }

    @Override
    public void start() {
      events.add("store.start");
    }

    @Override
    public void stop() {
      events.add("store.stop");
    }

    @Override
    public void close() {
      events.add("store.close");
    }
  }

  /** A participant doing cleanup work through assembled infrastructure, which is what fixes the shutdown order. */
  private final class Reporter implements LifecycleParticipant {
    private final ConnectionPool pool;

    Reporter(ConnectionPool pool) {
      this.pool = pool;
      events.add("create reporter");
    }

    @Override
    public void initialize() {
      events.add("reporter.initialize");
    }

    @Override
    public void start() {
      events.add("reporter.start");
    }

    @Override
    public void stop() {
      events.add("reporter.stop");
      pool.usedWhileStopping();
    }
  }

  private static ServiceAssembly.Builder assembly() {
    return ServiceAssembly.builder();
  }

  private static LifecycleOrchestrator.Builder orchestrator() {
    return LifecycleOrchestrator.builder()
        .threadPolicy(ThreadPolicy.PLATFORM)
        .runtimeCapabilities(new RuntimeCapabilities(17, false, "test"))
        .registerShutdownHook(false);
  }

  @Nested
  class TheHappyPath {

    @Test
    @DisplayName("assembly runs before lifecycle, and each layer acts only on what it owns")
    void startupFollowsTheDocumentedOrder() {
      ServiceAssembly built;
      LifecycleOrchestrator started;

      // 1-4. Configuration is already resolved at this point (ADR-0013); assembling is the next step, and
      // building creates objects without touching their lifecycle.
      built = assembly()
          .factory(ConnectionPool.class, services -> new ConnectionPool())
          .factory(Reporter.class, services -> new Reporter(services.require(ConnectionPool.class)))
          .build();
      assertThat(events).containsExactly("create pool", "create reporter");

      // 5-6. The orchestrator is built next and receives finished objects; registering drives nothing.
      events.clear();
      started = orchestrator()
          .register("reporter", built.require(Reporter.class))
          .build();
      assertThat(events).as("registration is not startup").isEmpty();
      assertThat(started.state()).isEqualTo(ApplicationState.NEW);

      // 7-8. Only start() runs phases, and the state machine is the observable proof of RUNNING.
      started.start();
      assertThat(events).containsExactly("reporter.initialize", "reporter.start");
      assertThat(started.state()).isEqualTo(ApplicationState.RUNNING);

      started.close();
      built.close();
      assertThat(events).endsWith("reporter.stop", "close pool");
      assertThat(started.state()).isEqualTo(ApplicationState.STOPPED);
    }

    @Test
    @DisplayName("participants stop before the assembly releases what they depend on")
    void shutdownStopsTheParticipantsThenClosesTheAssembly() {
      ServiceAssembly assembly = assembly()
          .factory(ConnectionPool.class, services -> new ConnectionPool())
          .factory(Reporter.class, services -> new Reporter(services.require(ConnectionPool.class)))
          .build();
      LifecycleOrchestrator app = orchestrator().register("reporter", assembly.require(Reporter.class)).build();
      app.start();
      events.clear();

      app.stop();
      assembly.close();

      // The order is not cosmetic: Reporter.stop() uses the pool, so closing the pool first would fail that
      // cleanup and report it as a broken participant. The assertion inside usedWhileStopping() is what proves the
      // pool was still open at that moment, rather than merely that the event names arrived in a plausible order.
      assertThat(events).containsExactly("reporter.stop", "close pool");
    }

    @Test
    @DisplayName("an object the caller registered is released by neither layer automatically")
    void aCloseableParticipantHasExactlyOneReleasePath() {
      SessionStore store = new SessionStore();
      events.clear();
      ServiceAssembly assembly = assembly().instance(SessionStore.class, store).build();
      LifecycleOrchestrator app = orchestrator().register("store", store).build();
      app.start();
      events.clear();

      app.stop();
      assembly.close();

      // The orchestrator drove stop(), because that is lifecycle. Nobody called close(): the assembly did not
      // create the object, and lifecycle phases are not a release mechanism. Releasing it is the caller's,
      // which is what "whoever created it owns it" costs, and the test says so out loud rather than hiding it.
      assertThat(events).containsExactly("store.stop");
    }
  }

  @Nested
  class FailurePaths {

    @Test
    @DisplayName("a startup failure rolls back the lifecycle and still leaves the assembly to be closed")
    void aFailedStartRollsBackParticipantsThenTheAssemblyIsClosed() {
      ServiceAssembly assembly = assembly()
          .factory(ConnectionPool.class, services -> new ConnectionPool())
          .factory(Reporter.class, services -> new Reporter(services.require(ConnectionPool.class)))
          .build();
      LifecycleOrchestrator app = orchestrator()
          .register("worker", new RecordingParticipant("worker", events))
          .register("reporter", assembly.require(Reporter.class))
          .register("broken", new RecordingParticipant("broken", events).failingStart())
          .build();
      events.clear();

      assertThatThrownBy(app::start).isInstanceOf(StartupException.class);

      // Rollback is complete on the lifecycle side and stops there: every entered participant received stop() in
      // reverse order, the failing one received stop() rather than abort() because it had already started, and the
      // orchestrator did not reach into the assembly to release the pool.
      assertThat(events).containsExactly(
          "worker.initialize", "reporter.initialize", "broken.initialize",
          "worker.start", "reporter.start", "broken.start",
          "broken.stop", "reporter.stop", "worker.stop");
      assertThat(app.state()).isEqualTo(ApplicationState.FAILED);

      events.clear();
      assembly.close();

      // That last close is the application's responsibility, and it is the half the two layers cannot do for
      // each other: had it been skipped, the pool would still be open with nobody left holding it (ADR-0015).
      assertThat(events).containsExactly("close pool");
    }

    @Test
    @DisplayName("a build failure is cleaned up by the assembly, so the application closes only what it holds")
    void aFailedAssemblyNeedsNoOrchestratorRollback() {
      // The assembly never returns, so the application has no ServiceAssembly to close and no orchestrator to
      // build: the objects that did exist are already released, by the layer that created them (ADR-0015).
      assertThatThrownBy(() -> assembly()
          .factory(ConnectionPool.class, services -> new ConnectionPool())
          .factory(Reporter.class, services -> {
            throw new AssemblyException("a service cannot be built");
          })
          .build()).isInstanceOf(AssemblyException.class);

      assertThat(events).containsExactly("create pool", "close pool");
    }
  }

  /** Shutdown is called from more than one place in a real application - a signal handler, a catch block, a
   * try-with-resources exit - so each owner has to tolerate being asked twice. */
  @Nested
  class RepeatedRelease {

    @Test
    @DisplayName("closing the assembly three times releases each object once")
    void closingTheAssemblyTwiceDoesNotDoubleRelease() {
      ServiceAssembly assembly = assembly().factory(ConnectionPool.class, services -> new ConnectionPool()).build();
      events.clear();

      assembly.close();
      assembly.close();
      assembly.close();

      assertThat(events).containsExactly("close pool");
    }

    @Test
    @DisplayName("a factory-made participant is stopped once and closed never by the other owner")
    void theTwoReleasePathsDoNotOverlap() {
      // Reporter came out of a factory, so the assembly owns releasing it while the orchestrator owns driving it.
      // Both owners being asked twice must not reach the object twice through the wrong door: stop() runs once,
      // close() never, and the pool is released exactly once by the assembly.
      ServiceAssembly assembly = assembly()
          .factory(ConnectionPool.class, services -> new ConnectionPool())
          .factory(Reporter.class, services -> new Reporter(services.require(ConnectionPool.class)))
          .build();
      LifecycleOrchestrator app = orchestrator().register("reporter", assembly.require(Reporter.class)).build();
      app.start();
      events.clear();

      assertThat(app.stop().clean()).isTrue();
      app.stop();
      assembly.close();
      assembly.close();

      assertThat(events).containsExactly("reporter.stop", "close pool");
      assertThat(app.state()).as("an orchestrator stays stopped rather than failing a second time")
          .isEqualTo(ApplicationState.STOPPED);
    }
  }

  /**
   * What the boundary leaves out, asserted rather than promised. A {@code threadKind()} getter existed only so a
   * test could read the provider back, and a {@code configuration(Configuration)} builder method existed only to
   * save the application one line - the line that is the boundary. Both are gone, and an assertion is the only
   * cheap way to keep them from coming back one convenience method at a time.
   */
  @Test
  @DisplayName("the orchestrator exposes no configuration handle and no thread diagnostics")
  void theBoundaryIsNotReopenedByConvenienceAccessors() {
    List<String> orchestratorSurface = new ArrayList<>();
    for (java.lang.reflect.Method method : LifecycleOrchestrator.class.getDeclaredMethods()) {
      if (!method.isSynthetic() && java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
        orchestratorSurface.add(method.getName());
      }
    }
    for (java.lang.reflect.Method method : LifecycleOrchestrator.Builder.class.getDeclaredMethods()) {
      if (!method.isSynthetic() && java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
        orchestratorSurface.add("builder." + method.getName());
      }
    }

    assertThat(orchestratorSurface).containsExactlyInAnyOrder(
        "builder", "state", "participantIds", "start", "stop", "close",
        "builder.threadPolicy", "builder.runtimeCapabilities", "builder.threads", "builder.threadCloseTimeout",
        "builder.register", "builder.registerShutdownHook", "builder.build");

    // Stated as types rather than as a name list, so the assertion is about the dependency and not about spelling.
    assertThat(java.util.Arrays.stream(LifecycleOrchestrator.Builder.class.getDeclaredMethods())
        .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes())))
        .as("a Configuration handed to the lifecycle engine would put the resolution decision inside it")
        .noneMatch(type -> type.getName().startsWith("com.barezen.core.config"));
  }
}
