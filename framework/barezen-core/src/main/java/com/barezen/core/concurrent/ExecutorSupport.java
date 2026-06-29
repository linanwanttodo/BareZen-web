package com.barezen.core.concurrent;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared validation and termination behaviour for {@link ThreadProvider} implementations.
 *
 * <p>Package private helper. Both providers must validate arguments and terminate executors in exactly
 * the same way, otherwise switching from platform to virtual threads would quietly change the
 * semantics callers depend on. One conformance test covers both implementations.
 */
final class ExecutorSupport {

  private static final Logger log = LoggerFactory.getLogger(ExecutorSupport.class);

  /**
   * Upper bound on waiting for a force stopped executor to finish. Interrupt-aware tasks end in
   * milliseconds; only a task that swallows its interrupt can consume the whole escort budget.
   */
  private static final long FORCE_STOP_ESCORT_MILLIS = 5_000L;

  private ExecutorSupport() {}

  static void requirePositiveConcurrency(int maxConcurrency) {
    if (maxConcurrency < 1) {
      throw new IllegalArgumentException(
          "maxConcurrency must be at least 1 but was " + maxConcurrency);
    }
  }

  static void requireNamePrefix(String namePrefix) {
    if (namePrefix == null || namePrefix.isBlank()) {
      throw new IllegalArgumentException("namePrefix must not be blank");
    }
  }

  static long requireTimeoutMillis(long closeTimeoutMillis) {
    if (closeTimeoutMillis < 0) {
      throw new IllegalArgumentException(
          "closeTimeoutMillis must not be negative but was " + closeTimeoutMillis);
    }
    return closeTimeoutMillis;
  }

  /**
   * Requests orderly termination of every owned executor, escalating to cancellation once the grace
   * period expires.
   *
   * <p>Shutdown is requested for all executors before any waiting begins, so a task that blocks in one
   * executor cannot delay the orderly shutdown of the others. Never throws: every owned executor gets
   * its termination attempt even if one of them misbehaves.
   *
   * @param executors executors created by the calling provider, iteration order preserved
   * @param closeTimeoutMillis grace period for orderly termination before cancellation
   * @param providerName executor name reported in diagnostics
   */
  static void shutdownAll(Set<ExecutorService> executors, long closeTimeoutMillis, String providerName) {
    for (ExecutorService executor : executors) {
      executor.shutdown();
    }
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeTimeoutMillis);
    for (ExecutorService executor : executors) {
      long remainingMillis = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
      if (awaitTerminationQuietly(executor, remainingMillis, providerName)) {
        continue;
      }
      log.warn("thread provider '{}' force-stopping an executor with running tasks", providerName);
      executor.shutdownNow();
      // A cancellation request is not a completed one: without this second wait close() would report
      // success while tasks were still running, and callers could release resources underneath them.
      if (!awaitTerminationQuietly(executor, FORCE_STOP_ESCORT_MILLIS, providerName)) {
        log.warn("thread provider '{}' has a task that survived force stop, it is still running",
            providerName);
      }
    }
  }

  /** @return {@code true} when the executor reached a terminated state within the given budget */
  private static boolean awaitTerminationQuietly(
      ExecutorService executor, long timeoutMillis, String providerName) {
    try {
      return executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      // Preserve the interrupt for the caller, which is usually the shutdown path itself.
      Thread.currentThread().interrupt();
      log.warn("thread provider '{}' interrupted while waiting for executor termination", providerName);
      executor.shutdownNow();
      return false;
    }
  }

  /**
   * Bounds the tasks running at the same instant inside an otherwise unbounded executor.
   *
   * <p>Used by the virtual thread provider, where one thread per task would otherwise let a caller start
   * as many concurrent blocking operations as it submits. Submission stays unbounded: surplus tasks park
   * on a permit instead of filling a queue, which is precisely the property that makes virtual threads
   * practical here.
   *
   * @param delegate per-task executor to run gated tasks on
   * @param maxConcurrency maximum simultaneously executing tasks, at least 1
   * @param name executor name used in diagnostics
   */
  static ExecutorService boundTo(ExecutorService delegate, int maxConcurrency, String name) {
    return new GatedExecutorService(delegate, maxConcurrency, name);
  }
}
