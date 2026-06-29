package com.barezen.core.concurrent;

/**
 * How a {@link ThreadProvider} is chosen.
 *
 * <p>The values encode the rule that an explicitly requested capability must never silently degrade:
 * asking for {@link #VIRTUAL} on a runtime without virtual threads fails at startup, while
 * {@link #AUTO} is the only policy allowed to fall back.
 */
public enum ThreadPolicy {

  /** Virtual threads when the runtime supports them, platform threads otherwise. */
  AUTO,

  /** Platform threads, even when the runtime would support virtual threads. */
  PLATFORM,

  /** Virtual threads, or a startup failure when the runtime cannot provide them. */
  VIRTUAL
}
