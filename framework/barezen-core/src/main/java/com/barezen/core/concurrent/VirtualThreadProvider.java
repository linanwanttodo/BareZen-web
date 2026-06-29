package com.barezen.core.concurrent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import com.barezen.core.runtime.RuntimeCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ThreadProvider} backed by virtual threads, requiring Java 21 or later at runtime.
 *
 * <p>Package private on purpose: obtain it through {@link ThreadProviders}, which refuses to build this
 * provider on a runtime that cannot support it (ADR-0002).
 *
 * <p>Unbounded executors are truly one thread per task, which is the point of virtual threads. Bounded
 * executors need extra care: a per-task executor cannot bound concurrency by itself, so admission is
 * gated by a semaphore while submission stays unbounded. A bounded virtual executor therefore gives the
 * same guarantee as a fixed platform pool, keeping the platform to virtual switch free of hidden
 * semantic change.
 */
final class VirtualThreadProvider implements ThreadProvider {

  private static final Logger log = LoggerFactory.getLogger(VirtualThreadProvider.class);

  private final RuntimeCapabilities runtime;
  private final long closeTimeoutMillis;
  private final Set<ExecutorService> owned;
  private boolean shutDown;

  VirtualThreadProvider(RuntimeCapabilities runtime, long closeTimeoutMillis) {
    if (!runtime.virtualThreads()) {
      throw new IllegalArgumentException("this runtime does not support virtual threads");
    }
    this.runtime = runtime;
    this.closeTimeoutMillis = ExecutorSupport.requireTimeoutMillis(closeTimeoutMillis);
    this.owned = Collections.newSetFromMap(new IdentityHashMap<>());
  }

  @Override
  public Kind kind() {
    return Kind.VIRTUAL;
  }

  @Override
  public String name() {
    return "virtual";
  }

  @Override
  public boolean preferred() {
    // Constructed only when the runtime supports it, so here it is always the recommendation.
    return true;
  }

  @Override
  public synchronized ExecutorService newBoundedExecutor(int maxConcurrency, String namePrefix) {
    checkNotShutDown();
    ExecutorSupport.requirePositiveConcurrency(maxConcurrency);
    ExecutorSupport.requireNamePrefix(namePrefix);
    return adopt(ExecutorSupport.boundTo(
        runtime.newVirtualThreadPerTaskExecutor(), maxConcurrency, namePrefix + "-gated"));
  }

  @Override
  public synchronized ExecutorService newUnboundedExecutor(String namePrefix) {
    checkNotShutDown();
    ExecutorSupport.requireNamePrefix(namePrefix);
    return adopt(runtime.newVirtualThreadPerTaskExecutor());
  }

  @Override
  public void close() {
    Set<ExecutorService> snapshot;
    synchronized (this) {
      if (shutDown) {
        return;
      }
      shutDown = true;
      snapshot = new LinkedHashSet<>(owned);
      owned.clear();
    }
    log.debug("closing {} executors created by thread provider '{}'", snapshot.size(), name());
    ExecutorSupport.shutdownAll(snapshot, closeTimeoutMillis, name());
  }

  private ExecutorService adopt(ExecutorService executor) {
    owned.add(executor);
    return executor;
  }

  private void checkNotShutDown() {
    if (shutDown) {
      throw new IllegalStateException(
          "thread provider '" + name() + "' is shut down and cannot create executors");
    }
  }
}
