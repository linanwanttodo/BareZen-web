package com.barezen.core.kernel;

/**
 * One participant whose cleanup did not complete cleanly.
 *
 * <p>Recorded rather than thrown, because cleanup has to attempt every remaining participant: letting a
 * single failure abort the loop would leave other participants open (ADR-0005).
 *
 * @param participantId id the participant was registered under, not blank
 * @param cause what the participant threw, not {@code null}
 */
public record ShutdownFailure(String participantId, Throwable cause) {

  public ShutdownFailure {
    if (participantId == null || participantId.isBlank()) {
      throw new IllegalArgumentException("participantId must not be blank");
    }
    if (cause == null) {
      throw new IllegalArgumentException("cause must not be null for participant '" + participantId + "'");
    }
  }
}
