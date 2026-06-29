package com.barezen.core.error;

/**
 * Raised when configuration cannot be used as asked: a value is absent where one is required, a stored string
 * cannot be converted to the type a key declares, or a source cannot be read.
 *
 * <p>One type for the whole configuration layer. Splitting it into parse, bind, convert and validate
 * subclasses would tell callers nothing they can act on differently: in every case the fix is the same, the
 * configuration itself, and the message carries the key, the offending value and the source that supplied it.
 *
 * <p>Unchecked for the reason recorded in ADR-0009: a misconfigured application cannot proceed, and the code
 * that notices is not in a position to repair it.
 *
 * <p>Phase 2.8 folds this type into the {@code BarezenException} taxonomy alongside
 * {@link LifecycleException}; see ADR-0009.
 */
public class ConfigurationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ConfigurationException(String message) {
    super(message);
  }

  public ConfigurationException(String message, Throwable cause) {
    super(message, cause);
  }
}
