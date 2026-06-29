package com.barezen.core.error;

/**
 * Raised when an application cannot be assembled: a type registered twice, a dependency nobody registered, a
 * dependency cycle, a factory that returned {@code null}, or a failure while releasing assembled objects.
 *
 * <p>One type for the whole assembly layer, for the reason recorded in ADR-0014. Splitting it into duplicate,
 * missing, cyclic and close subclasses would tell callers nothing they act on differently - in every case the fix
 * is the assembly code itself - while the part that actually matters, the type involved and the path that reached
 * it, is carried in the message rather than in a class name.
 *
 * <p>Unchecked because an application that cannot be assembled cannot proceed, and the code that notices is not
 * in a position to repair it (ADR-0009).
 *
 * <p>Phase 2.8 folds this type into the {@code BarezenException} taxonomy alongside {@link LifecycleException}
 * and {@link ConfigurationException}; see ADR-0009.
 */
public class AssemblyException extends LifecycleException {

  private static final long serialVersionUID = 1L;

  public AssemblyException(String message) {
    super(message);
  }

  public AssemblyException(String message, Throwable cause) {
    super(message, cause);
  }
}
