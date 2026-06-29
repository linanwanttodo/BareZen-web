package com.barezen.core.kernel;

import java.util.List;

/**
 * Outcome of a shutdown attempt.
 *
 * <p>The only way a caller learns that cleanup was incomplete, since {@link LifecycleOrchestrator#stop()}
 * never throws. An empty failure list means every participant released its resources.
 *
 * @param failures participants that failed to clean up, in the order cleanup was attempted
 */
public record ShutdownReport(List<ShutdownFailure> failures) {

  public ShutdownReport {
    failures = List.copyOf(failures);
  }

  /** @return {@code true} when no participant left a cleanup failure */
  public boolean clean() {
    return failures.isEmpty();
  }

  @Override
  public String toString() {
    return clean()
        ? "ShutdownReport[clean]"
        : "ShutdownReport[" + failures.size() + " failure(s): "
            + failures.stream().map(ShutdownFailure::participantId).toList() + "]";
  }
}
