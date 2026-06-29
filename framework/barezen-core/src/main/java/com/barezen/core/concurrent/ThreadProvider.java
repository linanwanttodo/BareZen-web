package com.barezen.core.concurrent;

import java.util.concurrent.ExecutorService;

/**
 * Creates thread executors that fit the current runtime.
 *
 * <p>Responsibility is deliberately narrow: this is a factory for executors, not a global executor
 * registry. It offers no way to look up an executor by name, to replace one, or to reach executors
 * created earlier, so callers cannot turn runtime capability detection into an application wide thread
 * pool bus.
 *
 * <p>Executors handed out are <em>owned by the provider</em>. {@link #close()} closes exactly those
 * executors this provider created and never touches executors a module built for itself. A provider is
 * therefore created per subsystem that wants ownership (one for the HTTP runtime, one for the event
 * bus, and so on) rather than shared as a process wide singleton.
 *
 * <p>Implementations are thread safe. {@link #close()} is idempotent and terminal: afterwards no
 * further executor may be created. Closing never throws, so a provider is safe inside
 * try-with-resources.
 */
public interface ThreadProvider extends AutoCloseable {

  /** Which thread model a provider produces. */
  enum Kind {
    /** One platform thread per task, or a bounded platform pool. */
    PLATFORM,
    /** One virtual thread per task, requiring Java 21 or later at runtime. */
    VIRTUAL
  }

  /** @return the thread model this provider produces, never {@code null} */
  Kind kind();

  /** @return short stable identifier for logs and diagnostics, for example {@code "virtual"} */
  String name();

  /**
   * @return {@code true} when this provider is the recommended choice for the runtime it was created
   *     for, which is what {@link ThreadPolicy#AUTO} resolves to
   */
  boolean preferred();

  /**
   * Creates an executor running at most {@code maxConcurrency} tasks at the same instant.
   *
   * <p>Excess tasks are queued rather than dropped, so the bound protects a finite resource such as a
   * connection pool or a memory hungry hashing function instead of shedding load. The guarantee is
   * identical for both kinds, which is what lets one conformance test cover both providers.
   *
   * @param maxConcurrency maximum simultaneously executing tasks, at least 1
   * @param namePrefix thread name prefix for diagnostics, must not be blank
   * @return a new executor owned by this provider
   * @throws IllegalArgumentException if {@code maxConcurrency} is below 1 or the prefix is blank
   * @throws IllegalStateException if this provider has been shut down
   */
  ExecutorService newBoundedExecutor(int maxConcurrency, String namePrefix);

  /**
   * Creates an executor without a concurrency limit.
   *
   * <p>Virtual providers start one virtual thread per task, platform providers grow a cached pool.
   * Prefer {@link #newBoundedExecutor(int, String)} whenever the workload reaches a limited resource.
   *
   * @param namePrefix thread name prefix for diagnostics, must not be blank
   * @return a new executor owned by this provider
   * @throws IllegalArgumentException if the prefix is blank
   * @throws IllegalStateException if this provider has been shut down
   */
  ExecutorService newUnboundedExecutor(String namePrefix);

  /**
   * Shuts down the executors this provider created, within the timeout it was configured with.
   *
   * <p>Best effort, idempotent and never throwing: executors that refuse to terminate are logged and
   * force stopped. Executors owned elsewhere are never touched.
   */
  @Override
  void close();
}
