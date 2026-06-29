package com.barezen.core.kernel;

/**
 * A component whose startup and shutdown is driven by {@link LifecycleOrchestrator}.
 *
 * <p>Participants are plain objects. Dependencies arrive through their constructor, where a reader can
 * see them, and never through a context object handed to a lifecycle callback: a parameter that starts
 * as "just configuration" ends as a service locator, and BareZen rejects that pattern by construction
 * (ADR-0006).
 *
 * <p>Obligations the orchestrator relies on:
 * <ul>
 *   <li>{@link #stop()} and {@link #abort()} are idempotent and never throw;
 *   <li>{@link #stop()} must tolerate a participant that only reached {@link #initialize()}, because the
 *       orchestrator uses it for both orderly shutdown and rollback of an initialized participant;
 *   <li>registration order is startup order. Choosing that order is the assembler's job, for example
 *       from a dependency graph, and it is not expressed by an order field here.
 * </ul>
 *
 * <p>Every method has an empty default, so an implementation writes only the phases it actually has.
 */
public interface LifecycleParticipant {

  /**
   * Acquires resources and completes internal wiring without becoming observable from the outside.
   *
   * <p>All participants are initialized before any participant is started, which is what lets a late
   * participant such as an HTTP runtime bind a port only after every earlier participant has finished
   * registering its routes (ADR-0003).
   */
  default void initialize() {}

  /** Activates the participant so it begins serving work. */
  default void start() {}

  /**
   * Releases resources. Invoked on every participant that reached {@link #initialize()} or
   * {@link #start()}, in reverse registration order, during both orderly shutdown and rollback.
   *
   * <p>Exceptions thrown here are contained by the orchestrator and reported as
   * {@link ShutdownFailure}; they never abort the remaining cleanup.
   */
  default void stop() {}

  /**
   * Releases resources acquired by a partially executed {@link #initialize()}.
   *
   * <p>Called instead of {@link #stop()} when {@code initialize()} itself failed, since at that point the
   * participant is in a state its own code did not finish setting up. Without this hook every
   * participant would have to hand-roll rollback inside {@code initialize()}, in a shape no test can
   * check (ADR-0004).
   */
  default void abort() {}
}
