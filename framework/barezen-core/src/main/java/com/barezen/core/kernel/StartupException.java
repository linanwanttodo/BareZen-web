package com.barezen.core.kernel;

import com.barezen.core.error.LifecycleException;
import java.util.List;

/**
 * Startup failed, after the kernel had already completed rollback.
 *
 * <p>Guarantee: when this exception escapes {@link LifecycleOrchestrator#start()}, no participant is left
 * holding a resource. The state is {@link ApplicationState#FAILED} and the entered-participant stack is
 * empty.
 *
 * <p>The original cause is always the root of this exception. Cleanup problems discovered during
 * rollback are attached as suppressed exceptions and mirrored in {@link #rollbackFailures()}, so a log
 * statement that only prints {@code getCause()} still surfaces them. A rollback failure never replaces
 * the startup failure (ADR-0004).
 */
public final class StartupException extends LifecycleException {

  private static final long serialVersionUID = 1L;

  private final String participantId;
  private final Phase phase;
  private final List<ShutdownFailure> rollbackFailures;

  StartupException(String participantId, Phase phase, Throwable cause,
      List<ShutdownFailure> rollbackFailures) {
    super(buildMessage(participantId, phase, cause, rollbackFailures), cause);
    this.participantId = participantId;
    this.phase = phase;
    this.rollbackFailures = List.copyOf(rollbackFailures);
    for (ShutdownFailure failure : this.rollbackFailures) {
      addSuppressed(failure.cause());
    }
  }

  /** @return id of the participant whose lifecycle method failed */
  public String participantId() {
    return participantId;
  }

  /** @return which up phase failed */
  public Phase phase() {
    return phase;
  }

  /**
   * @return participants that also failed to release resources during rollback, in the order cleanup was
   *     attempted
   */
  public List<ShutdownFailure> rollbackFailures() {
    return rollbackFailures;
  }

  /** The lifecycle phase a participant was in when it failed. */
  public enum Phase {
    /** {@link LifecycleParticipant#initialize()} threw. */
    INITIALIZE,
    /** {@link LifecycleParticipant#start()} threw. */
    START
  }

  private static String buildMessage(String participantId, Phase phase, Throwable cause,
      List<ShutdownFailure> rollbackFailures) {
    StringBuilder message = new StringBuilder("startup failed during ")
        .append(phase)
        .append(" of participant '")
        .append(participantId)
        .append("': ")
        .append(cause.getClass().getSimpleName())
        .append(": ")
        .append(cause.getMessage());
    if (!rollbackFailures.isEmpty()) {
      message.append(" | rollback also failed for ")
          .append(rollbackFailures.stream().map(ShutdownFailure::participantId).toList());
    }
    return message.toString();
  }
}
