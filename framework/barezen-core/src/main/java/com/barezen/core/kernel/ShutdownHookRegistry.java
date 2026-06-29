package com.barezen.core.kernel;

/**
 * Indirection over JVM shutdown hook registration.
 *
 * <p>Package private on purpose. It exists for one reason: {@code Runtime.addShutdownHook} cannot be
 * exercised without risking the test JVM, and a guarantee as important as "process termination also stops
 * the application" has to be tested. Publishing it would invite callers to wire their own shutdown
 * ordering next to the kernel's.
 */
interface ShutdownHookRegistry {

  /**
   * Registers a hook that stops the application.
   *
   * @param name hook name for diagnostics
   * @param onShutdown action to run, expected to be idempotent
   * @return a handle representing the registration, so it can be removed again
   */
  Thread add(String name, Runnable onShutdown);

  /** @param handle value returned by {@link #add}, {@code null} tolerated */
  void remove(Thread handle);

  /** Registry backed by the current runtime, used unless a test injects another one. */
  static ShutdownHookRegistry jvm() {
    return new ShutdownHookRegistry() {
      @Override
      public Thread add(String name, Runnable onShutdown) {
        Thread hook = new Thread(onShutdown, name);
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
      }

      @Override
      public void remove(Thread hook) {
        if (hook != null) {
          Runtime.getRuntime().removeShutdownHook(hook);
        }
      }
    };
  }
}
