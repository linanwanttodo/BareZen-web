package com.barezen.core.concurrent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import com.barezen.core.runtime.RuntimeCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ThreadProvider} using platform threads, available on every supported JDK.
 *
 * <p>Package private on purpose: {@link ThreadProviders} is the only entry point, because that is where
 * the {@link ThreadPolicy} rules are enforced. A public constructor would let callers request a virtual
 * thread pool while bypassing the capability check.
 *
 * <p>A bounded provider uses a fixed pool, which already bounds execution, whereas the virtual provider
 * has to add admission control for the same guarantee. One conformance test covers both so the behaviour
 * stays equivalent across the switch.
 */
final class PlatformThreadProvider implements ThreadProvider {

  private static final Logger log = LoggerFactory.getLogger(PlatformThreadProvider.class);

  private final boolean virtualThreadsAvailable;
  private final long closeTimeoutMillis;
  private final Set<ExecutorService> owned;
  private boolean shutDown;

  PlatformThreadProvider(RuntimeCapabilities runtime, long closeTimeoutMillis) {
    this.virtualThreadsAvailable = runtime.virtualThreads();
    this.closeTimeoutMillis = ExecutorSupport.requireTimeoutMillis(closeTimeoutMillis);
    this.owned = Collections.newSetFromMap(new IdentityHashMap<>());
  }

  @Override
  public Kind kind() {
    return Kind.PLATFORM;
  }

  @Override
  public String name() {
    return "platform";
  }

  @Override
  public boolean preferred() {
    // Platform threads are only the recommendation when the runtime cannot do better.
    return !virtualThreadsAvailable;
  }

  @Override
  public synchronized ExecutorService newBoundedExecutor(int maxConcurrency, String namePrefix) {
    checkNotShutDown();
    ExecutorSupport.requirePositiveConcurrency(maxConcurrency);
    ExecutorSupport.requireNamePrefix(namePrefix);
    return adopt(Executors.newFixedThreadPool(maxConcurrency, namedFactory(namePrefix)));
  }

  @Override
  public synchronized ExecutorService newUnboundedExecutor(String namePrefix) {
    checkNotShutDown();
    ExecutorSupport.requireNamePrefix(namePrefix);
    return adopt(Executors.newCachedThreadPool(namedFactory(namePrefix)));
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

  /**
   * Builds named, non daemon threads. The kernel does not get to decide that a caller's work may be
   * abandoned once the last user thread exits; ordered termination is the job of {@link #close()}.
   */
  private static ThreadFactory namedFactory(String prefix) {
    AtomicInteger counter = new AtomicInteger(1);
    return runnable -> new Thread(runnable, prefix + "-" + counter.getAndIncrement());
  }
}
