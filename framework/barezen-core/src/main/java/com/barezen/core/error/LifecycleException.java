package com.barezen.core.error;

/**
 * Root of every error raised by the BareZen kernel.
 *
 * <p>Deliberately a {@link RuntimeException}: lifecycle violations describe a programming or
 * assembly mistake, not a recoverable application condition, and forcing every caller to catch a
 * checked exception would only push defensive code into places that cannot act on it.
 *
 * <p>This type is intentionally minimal. Phase 2.8 introduces the {@code BarezenException}
 * taxonomy for application level errors and will re-parent the kernel exceptions onto it; see
 * ADR-0009.
 */
public class LifecycleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public LifecycleException(String message) {
    super(message);
  }

  public LifecycleException(String message, Throwable cause) {
    super(message, cause);
  }
}
