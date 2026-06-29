package com.barezen.core.concurrent;

import com.barezen.core.error.LifecycleException;
import com.barezen.core.runtime.RuntimeCapabilities;

/**
 * Raised when a {@link ThreadPolicy} demands a capability the current runtime cannot provide.
 *
 * <p>The rule behind it: an explicitly requested capability never degrades silently. Asking for
 * {@link ThreadPolicy#VIRTUAL} on a JDK 17 runtime is a configuration mistake, and continuing with platform
 * threads would hide it until someone compared production behaviour against the intended configuration.
 * {@link ThreadPolicy#AUTO} is the only policy allowed to fall back, and that path does not use this exception.
 *
 * <p>The message names only the request and the runtime. How the policy was chosen - a configuration file, an
 * environment variable, a builder call, a default - is not this type's business, and naming a configuration key
 * would make a lifecycle failure depend on an API that other layers own (ADR-0013). The capabilities come from
 * the caller, so nothing here reads global state.
 *
 * <p>Phase 2.8 folds this type into the {@code BarezenException} taxonomy; see ADR-0009.
 */
public class ThreadCapabilityUnavailableException extends LifecycleException {

  private static final long serialVersionUID = 1L;

  private final ThreadPolicy requested;

  public ThreadCapabilityUnavailableException(
      ThreadPolicy requested, RuntimeCapabilities runtime, String detail) {
    super(buildMessage(requested, runtime, detail));
    this.requested = requested;
  }

  /** @return the policy that could not be satisfied */
  public ThreadPolicy requestedPolicy() {
    return requested;
  }

  private static String buildMessage(
      ThreadPolicy requested, RuntimeCapabilities runtime, String detail) {
    return requested + " threads were explicitly requested but are unavailable here: " + detail
        + " | runtime=" + runtime
        + " | an explicitly requested thread policy is never downgraded";
  }
}
