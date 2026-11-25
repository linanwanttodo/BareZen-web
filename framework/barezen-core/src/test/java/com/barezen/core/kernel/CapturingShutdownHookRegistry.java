package com.barezen.core.kernel;

import java.util.ArrayList;
import java.util.List;

/**
 * Records shutdown hook registration instead of touching the JVM, so the process termination path can be
 * exercised without killing the test run.
 */
class CapturingShutdownHookRegistry implements ShutdownHookRegistry {

  /** One registration: the thread the kernel received plus the action behind it. */
  record Hook(Thread thread, Runnable action) {}

  private final List<Hook> added = new ArrayList<>();
  private final List<Hook> removed = new ArrayList<>();
  private final IllegalStateException removeFailure;

  CapturingShutdownHookRegistry() {
    this(null);
  }

  CapturingShutdownHookRegistry(IllegalStateException removeFailure) {
    this.removeFailure = removeFailure;
  }

  @Override
  public Thread add(String name, Runnable onShutdown) {
    Thread thread = new Thread(onShutdown, name);
    added.add(new Hook(thread, onShutdown));
    return thread;
  }

  @Override
  public void remove(Thread hook) {
    if (removeFailure != null) {
      throw removeFailure;
    }
    added.stream().filter(h -> h.thread() == hook).findFirst().ifPresent(removed::add);
  }

  int addedCount() {
    return added.size();
  }

  int removedCount() {
    return removed.size();
  }

  /** Simulates process termination by running the action of every still registered hook. */
  void fireRegisteredHooks() {
    for (Hook hook : List.copyOf(added)) {
      if (!removed.contains(hook)) {
        hook.action().run();
      }
    }
  }
}
