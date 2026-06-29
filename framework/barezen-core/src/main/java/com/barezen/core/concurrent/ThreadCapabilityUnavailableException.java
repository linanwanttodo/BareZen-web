package com.barezen.core.concurrent;

import com.barezen.core.error.LifecycleException;

/**
 * Raised when a {@link ThreadPolicy} demands a capability the current runtime cannot provide.
 *
 * <p>The rule behind it: an explicitly requested capability never degrades silently. Asking for
 * {@link ThreadPolicy#VIRTUAL} on a JDK 17 runtime is a configuration mistake, and continuing with
 * platform threads would hide it until someone compared production behaviour against the intended
 * configuration. {@link ThreadPolicy#AUTO} is the only policy allowed to fall back, and that path does
 * not use this exception.
 *
 * <p>Phase 2.8 folds this type into the {@code BarezenException} taxonomy; see ADR-0009.
 */
public class ThreadCapabilityUnavailableException extends LifecycleException {

  private static final long serialVersionUID = 1L;

  private final ThreadPolicy requested;

  public ThreadCapabilityUnavailableException(ThreadPolicy requested, String detail) {
    super(buildMessage(requested, detail));
    this.requested = requested;
  }

  /** @return the policy that could not be satisfied */
  public ThreadPolicy requestedPolicy() {
    return requested;
  }

  private static String buildMessage(ThreadPolicy requested, String detail) {
    // Names nothing but the runtime and the request. How the policy was chosen - a configuration key, a
    // builder call, a hard-coded default - is the caller's business, and the message must not hint at a
    // configuration system that does not exist yet.
    return requested + " threads were explicitly requested but are unavailable in this runtime: " + detail
        + " | java.version=" + System.getProperty("java.version")
        + " | an explicitly requested thread policy is never downgraded";
  }
}
