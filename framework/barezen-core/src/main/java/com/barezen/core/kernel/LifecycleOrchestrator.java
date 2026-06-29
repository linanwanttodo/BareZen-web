package com.barezen.core.kernel;

import com.barezen.core.concurrent.RuntimeConfig;
import com.barezen.core.concurrent.ThreadCapabilityUnavailableException;
import com.barezen.core.concurrent.ThreadPolicy;
import com.barezen.core.concurrent.ThreadProvider;
import com.barezen.core.concurrent.ThreadProviders;
import com.barezen.core.config.Configuration;
import com.barezen.core.error.LifecycleException;
import com.barezen.core.kernel.StartupException.Phase;
import com.barezen.core.runtime.RuntimeCapabilities;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives a fixed set of {@link LifecycleParticipant} components through startup, shutdown and startup
 * rollback.
 *
 * <p>Startup runs in two global phases: every participant is initialized before any participant is
 * started. That ordering is not stylistic. A participant that makes work observable, such as an HTTP
 * runtime binding a port, must do so only after every earlier participant has finished registering its
 * routes, and interleaving the two phases per component cannot express it (ADR-0003).
 *
 * <p>Registration order is startup order, and reverse registration order is shutdown order. Deciding
 * that order belongs to the assembler, for example from a dependency graph, so this class carries no
 * priority field.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>if {@link #start()} throws, nothing is left acquired: every entered participant received
 *       {@link LifecycleParticipant#stop()}, the failing one additionally received
 *       {@link LifecycleParticipant#abort()} instead, the entered set is empty and the state is
 *       {@link ApplicationState#FAILED};
 *   <li>the startup failure is always what is reported; cleanup problems found during rollback are
 *       attached to it and never allowed to replace it;
 *   <li>{@link #stop()} attempts every entered participant even when one of them throws, and itself
 *       never throws;
 *   <li>{@code stop()} and {@code close()} on an application that never started are legal and idempotent,
 *       which is what makes try-with-resources usable (ADR-0012);
 *   <li>the owned {@link ThreadProvider} is released after all participants, because a participant may
 *       submit cleanup work to an executor during its own stop phase.
 * </ul>
 *
 * <p>Thread safe. Guarded by a {@link ReentrantLock} rather than {@code synchronized}, in line with the
 * framework rule that shared code must not pin a carrier thread.
 */
public final class LifecycleOrchestrator implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(LifecycleOrchestrator.class);

  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, LifecycleParticipant> participants;
  private final RuntimeCapabilities runtime;
  private final ThreadProvider threads;
  private final boolean useShutdownHook;
  private final ShutdownHookRegistry hookRegistry;

  /** Participants whose {@code initialize()} completed, in order; the rollback and shutdown set. */
  private final Deque<String> entered = new ArrayDeque<>();

  private ApplicationState state = ApplicationState.NEW;
  private ShutdownReport lastReport = null;
  private Thread shutdownHook;

  private LifecycleOrchestrator(Builder builder) {
    this.participants = new LinkedHashMap<>(builder.participants);
    this.runtime = builder.runtime;
    this.threads = builder.threads;
    this.useShutdownHook = builder.registerShutdownHook;
    this.hookRegistry = builder.hookRegistry;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** @return the state at the moment of the call */
  public ApplicationState state() {
    lock.lock();
    try {
      return state;
    } finally {
      lock.unlock();
    }
  }

  /** @return participant ids in registration order, which is startup order */
  public List<String> participantIds() {
    lock.lock();
    try {
      return List.copyOf(participants.keySet());
    } finally {
      lock.unlock();
    }
  }

  /**
   * @return the thread model the owned provider produces. The only observation point for the
   *     configuration-to-provider decision, so a deployment can log what {@code barezen.runtime.threads}
   *     resolved to instead of inferring it from thread dumps
   */
  public ThreadProvider.Kind threadKind() {
    return threads.kind();
  }

  /**
   * Initializes then starts every participant.
   *
   * <p>On failure, already entered participants are released in reverse order before the exception is
   * thrown, so a caller can rely on there being nothing left to clean up.
   *
   * @throws StartupException a participant failed to initialize or start, after rollback completed
   * @throws LifecycleException this orchestrator is not in {@link ApplicationState#NEW}
   */
  public void start() {
    lock.lock();
    try {
      requireState(ApplicationState.NEW, "start");
      state = ApplicationState.STARTING;

      PhaseFailure failure = initializeAll();
      if (failure == null) {
        failure = startAll();
      }

      if (failure != null) {
        List<ShutdownFailure> rollbackFailures = releaseEntered();
        if (failure.abortFailure() != null) {
          rollbackFailures.add(0, new ShutdownFailure(failure.participantId(), failure.abortFailure()));
        }
        lastReport = new ShutdownReport(rollbackFailures);
        state = ApplicationState.FAILED;
        throw new StartupException(failure.participantId(), failure.phase(), failure.cause(),
            rollbackFailures);
      }

      state = ApplicationState.RUNNING;
      installShutdownHook();
      log.info("application started: {} participant(s), thread provider '{}', runtime {}",
          participants.size(), threads.name(), runtime);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Stops every participant and releases the owned thread provider.
   *
   * <p>Best effort and never throwing. Legal from any state, including {@link ApplicationState#NEW}: an
   * application that never started has no participants to stop, but its thread provider is still
   * released. Repeated calls invoke nothing twice and return the same report.
   *
   * @return what could not be cleaned up
   */
  public ShutdownReport stop() {
    lock.lock();
    try {
      switch (state) {
        case STARTING -> {
          // The startup path owns rollback; a concurrent stop must not cut into it.
          log.debug("stop() requested while STARTING, cleanup left to the startup path");
          return reportOrClean();
        }
        case STOPPING -> {
          return reportOrClean();
        }
        case STOPPED, FAILED -> {
          return reportOrClean();
        }
        default -> {
          // NEW and RUNNING fall through to the real shutdown below.
        }
      }

      boolean wasRunning = state == ApplicationState.RUNNING;
      state = ApplicationState.STOPPING;
      removeShutdownHook();
      List<ShutdownFailure> failures = releaseEntered();
      threads.close();
      lastReport = new ShutdownReport(failures);
      state = ApplicationState.STOPPED;
      if (wasRunning) {
        log.info("application stopped: {}", lastReport);
      }
      return lastReport;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Releases all participants and the thread provider, as {@link #stop()} does.
   *
   * <p>Present so an orchestrator can be held in a try-with-resources block and an application that was
   * built but never started still has one cleanup path.
   */
  @Override
  public void close() {
    stop();
  }

  private PhaseFailure initializeAll() {
    for (Map.Entry<String, LifecycleParticipant> entry : participants.entrySet()) {
      String id = entry.getKey();
      LifecycleParticipant participant = entry.getValue();
      log.debug("initializing participant '{}'", id);
      try {
        participant.initialize();
      } catch (RuntimeException | Error e) {
        // This participant never entered the entered set, so its down call is abort(), not stop().
        return new PhaseFailure(id, Phase.INITIALIZE, e, abortQuietly(id, participant));
      }
      entered.addLast(id);
    }
    return null;
  }

  private PhaseFailure startAll() {
    for (Map.Entry<String, LifecycleParticipant> entry : participants.entrySet()) {
      log.debug("starting participant '{}'", entry.getKey());
      try {
        entry.getValue().start();
      } catch (RuntimeException | Error e) {
        // Already entered, so the down call is stop(), which releaseEntered() performs for it.
        return new PhaseFailure(entry.getKey(), Phase.START, e, null);
      }
    }
    return null;
  }

  /**
   * Calls {@code stop()} on every entered participant in reverse order, collecting rather than
   * propagating failures, and empties the entered set.
   */
  private List<ShutdownFailure> releaseEntered() {
    List<ShutdownFailure> failures = new ArrayList<>();
    while (!entered.isEmpty()) {
      String id = entered.removeLast();
      try {
        participants.get(id).stop();
      } catch (RuntimeException | Error e) {
        log.warn("participant '{}' failed to stop cleanly", id, e);
        failures.add(new ShutdownFailure(id, e));
      }
    }
    return failures;
  }

  /** Runs {@code abort()} so a failure inside it cannot hide the startup failure that caused it. */
  private Throwable abortQuietly(String id, LifecycleParticipant participant) {
    try {
      participant.abort();
      return null;
    } catch (RuntimeException | Error e) {
      log.warn("participant '{}' failed to abort after a broken initialize()", id, e);
      return e;
    }
  }

  private ShutdownReport reportOrClean() {
    return lastReport != null ? lastReport : new ShutdownReport(List.of());
  }

  private void requireState(ApplicationState expected, String operation) {
    if (state != expected) {
      throw new LifecycleException("cannot " + operation + " in state " + state + ", expected " + expected);
    }
  }

  private void installShutdownHook() {
    if (!useShutdownHook) {
      return;
    }
    shutdownHook = hookRegistry.add("barezen-shutdown", this::stop);
  }

  private void removeShutdownHook() {
    if (shutdownHook != null) {
      hookRegistry.remove(shutdownHook);
      shutdownHook = null;
    }
  }

  /**
   * What went wrong in an up phase.
   *
   * @param abortFailure a secondary failure from {@code abort()}, or {@code null}
   */
  private record PhaseFailure(String participantId, Phase phase, Throwable cause, Throwable abortFailure) {}

  /**
   * Fluent builder for {@link LifecycleOrchestrator}.
   *
   * <p>Defaults: {@link ThreadPolicy#AUTO} applied to {@link RuntimeCapabilities#detect()}, a JVM shutdown
   * hook, and {@link ThreadProviders#DEFAULT_CLOSE_TIMEOUT} for executor termination. The participant set
   * is fixed at build time, so no participant can register a neighbour halfway through startup.
   */
  public static final class Builder {

    private final Map<String, LifecycleParticipant> participants = new LinkedHashMap<>();
    private RuntimeCapabilities runtime = RuntimeCapabilities.detect();
    private ThreadPolicy threadPolicy = ThreadPolicy.AUTO;
    private ThreadProvider threads;
    private Duration threadCloseTimeout = ThreadProviders.DEFAULT_CLOSE_TIMEOUT;
    private boolean registerShutdownHook = true;
    private ShutdownHookRegistry hookRegistry = ShutdownHookRegistry.jvm();

    private Builder() {}

    /**
     * Chooses the thread model the owned provider is resolved from. Ignored when
     * {@link #threads(ThreadProvider)} supplied one.
     *
     * <p>Prefer {@link #configuration(Configuration)} when the application has a configuration snapshot:
     * hard-coding a policy here silently overrides whatever the operator set.
     */
    public Builder threadPolicy(ThreadPolicy policy) {
      this.threadPolicy = Objects.requireNonNull(policy, "threadPolicy");
      return this;
    }

    /**
     * Reads {@link RuntimeConfig} from a configuration snapshot and applies the thread policy it resolves to.
     *
     * <p>This is the intended entry point for the setting, and the only place in the lifecycle path where a
     * configuration value becomes a thread decision. The provider itself never reads a property or an
     * environment variable, so {@link ThreadProvider} stays a pure infrastructure component (ADR-0013).
     *
     * <p>Resolution is not validation: a snapshot saying {@code barezen.runtime.threads=virtual} is accepted
     * here and rejected at {@link #build()} only if the runtime cannot provide it, which is where capabilities
     * are known. A bad value fails with the key, the value and the source that supplied it; an unavailable one
     * fails as {@link ThreadCapabilityUnavailableException}. Neither ever degrades to another policy.
     */
    public Builder configuration(Configuration configuration) {
      Objects.requireNonNull(configuration, "configuration");
      this.threadPolicy = RuntimeConfig.from(configuration).threads();
      return this;
    }

    /** Overrides the detected runtime capabilities, used by tests and by controlled deployment probes. */
    public Builder runtimeCapabilities(RuntimeCapabilities capabilities) {
      this.runtime = Objects.requireNonNull(capabilities, "runtimeCapabilities");
      return this;
    }

    /** Supplies an externally created provider; the orchestrator still owns its shutdown. */
    public Builder threads(ThreadProvider provider) {
      this.threads = Objects.requireNonNull(provider, "threads");
      return this;
    }

    /** Grace period for terminating executors the owned provider created. */
    public Builder threadCloseTimeout(Duration timeout) {
      this.threadCloseTimeout = Objects.requireNonNull(timeout, "threadCloseTimeout");
      return this;
    }

    /**
     * Appends a participant. Call order defines startup order.
     *
     * @param id stable identifier used in logs and failure reports
     * @throws IllegalArgumentException if the id is blank or already registered
     */
    public Builder register(String id, LifecycleParticipant participant) {
      if (id == null || id.isBlank()) {
        throw new IllegalArgumentException("participant id must not be blank");
      }
      Objects.requireNonNull(participant, "participant");
      if (participants.containsKey(id)) {
        throw new IllegalArgumentException("participant id '" + id + "' is already registered");
      }
      participants.put(id, participant);
      return this;
    }

    /**
     * Whether the JVM shutdown hook stops an embedded application. Turn it off when a host container
     * already owns process lifecycle, otherwise two mechanisms race on shutdown order.
     */
    public Builder registerShutdownHook(boolean enabled) {
      this.registerShutdownHook = enabled;
      return this;
    }

    /**
     * Replaces shutdown hook registration. Not part of the public surface beyond package visibility:
     * callers configure this through {@link #registerShutdownHook(boolean)}.
     */
    Builder shutdownHookRegistry(ShutdownHookRegistry registry) {
      this.hookRegistry = Objects.requireNonNull(registry, "hookRegistry");
      return this;
    }

    /**
     * Builds the orchestrator, resolving a {@link ThreadProvider} if none was supplied.
     *
     * @throws com.barezen.core.concurrent.ThreadCapabilityUnavailableException when
     *     {@link ThreadPolicy#VIRTUAL} was requested and the runtime cannot provide it
     */
    public LifecycleOrchestrator build() {
      if (threads == null) {
        threads = ThreadProviders.create(threadPolicy, runtime, threadCloseTimeout);
      }
      return new LifecycleOrchestrator(this);
    }
  }
}
