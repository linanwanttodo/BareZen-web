package com.barezen.core.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The only place in the framework that reaches for a Java API newer than the JDK 17 baseline.
 *
 * <p>BareZen compiles with {@code --release 17}, so {@code Executors.newVirtualThreadPerTaskExecutor()}
 * is not a compile time symbol. One {@link MethodHandle}, resolved during class initialisation, is how
 * the kernel uses virtual threads anyway, without lowering the baseline and without splitting the build
 * into JDK specific modules (ADR-0002).
 *
 * <p>Package private on purpose: consumers ask {@link RuntimeCapabilities#virtualThreads()} whether the
 * runtime supports virtual threads and then call
 * {@link RuntimeCapabilities#newVirtualThreadPerTaskExecutor()}. A second probe elsewhere would mean the
 * capability model is no longer sufficient, which belongs in an ADR rather than being coded around
 * (ADR-0010).
 *
 * <p>Resolution never throws. A runtime reporting feature 21 or above without the API, for example a
 * stripped custom JRE, is reported as unsupported instead of breaking startup.
 */
final class VirtualThreadProbe {

  private static final MethodHandle PER_TASK_EXECUTOR = resolve();

  private VirtualThreadProbe() {}

  /** @return {@code true} when this runtime exposes virtual threads. */
  static boolean supported() {
    return PER_TASK_EXECUTOR != null;
  }

  /**
   * Creates an executor running each task on its own virtual thread.
   *
   * <p>The created threads carry no application specific name, because naming them would need a second
   * Java 21 handle ({@code Thread.ofVirtual}). Diagnostics are served by the executor name recorded by
   * the owning {@code ThreadProvider}; revisit only if thread naming proves necessary.
   *
   * @throws IllegalStateException if this runtime does not support virtual threads
   */
  static ExecutorService newPerTaskExecutor() {
    if (PER_TASK_EXECUTOR == null) {
      throw new IllegalStateException(
          "this runtime does not support virtual threads (java.version="
              + System.getProperty("java.version") + ")");
    }
    try {
      return (ExecutorService) PER_TASK_EXECUTOR.invoke();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException("unable to create the virtual thread executor", e);
    }
  }

  /** Resolves the handle once, skipping the lookup below Java 21 where the answer is already known. */
  private static MethodHandle resolve() {
    if (Runtime.version().feature() < 21) {
      return null;
    }
    try {
      return MethodHandles.lookup()
          .findStatic(Executors.class, "newVirtualThreadPerTaskExecutor",
              MethodType.methodType(ExecutorService.class));
    } catch (NoSuchMethodException | IllegalAccessException | LinkageError e) {
      return null;
    }
  }
}
