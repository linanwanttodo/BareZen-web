package com.barezen.core.kernel;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle state of a BareZen application.
 *
 * <p>Six states and no more. There is deliberately no {@code INITIALIZED} state: startup runs one global
 * initialize phase before a global start phase, so a partially initialized application is still
 * {@link #STARTING} and therefore still owned by the rollback path. A state that could only ever be
 * observed from inside the kernel would invite callers to branch on it.
 */
public enum ApplicationState {

  /** Built but never started. */
  NEW,

  /** Startup in progress, either initializing or starting participants. */
  STARTING,

  /** Startup completed, the application is serving. */
  RUNNING,

  /** Shutdown in progress. */
  STOPPING,

  /** Shutdown completed, reached either from {@link #RUNNING} or from {@link #NEW}. */
  STOPPED,

  /** Startup failed and rollback completed; the application never served. */
  FAILED;

  private static final Set<ApplicationState> TERMINAL =
      Collections.unmodifiableSet(EnumSet.of(STOPPED, FAILED));

  /**
   * @return {@code true} when no further transition is possible, which is what lets {@code stop()} answer
   *     a repeated call with the recorded report instead of cleaning up a second time
   */
  public boolean terminal() {
    return TERMINAL.contains(this);
  }
}
