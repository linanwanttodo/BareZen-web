package com.barezen.web;

import java.util.OptionalInt;

/**
 * The HTTP runtime an application needs: something that can be started, can be stopped, and says whether it is serving.
 *
 * <p>This is the seam between the contract and a server. {@link Router} answers which handler a request is for,
 * {@link Handler} answers a request, and neither of them owns a socket — so a component has to own the port, the threads
 * and the bytes, and this type is the part of it an application can write against without naming which server it got.
 * Phase 2.9 ships one implementation, {@code barezen-http-jdk}, on {@code com.sun.net.httpserver}; a Jetty or Netty
 * adapter implements this same four-sentence interface and nothing else in this module changes (ADR-0018 §12, ADR-0021).
 *
 * <p>Four members and no configuration. There is no {@code restart()}, no {@code startAsync()}, no
 * {@code awaitTermination()}, no {@code shutdownNow()}, no {@code port} setter and no {@code router(...)} mutator: an
 * instance corresponds to one server lifecycle, and a second start after a stop means a different server with a
 * possibly-different port, which an application should ask for by building a new adapter (ADR-0024 §5).
 *
 * <p><b>Not a lifecycle participant.</b> {@code LifecycleOrchestrator} owns the application's startup and shutdown
 * order; this type owns an HTTP runtime. They overlap on one verb and diverge on everything else — a participant has
 * {@code initialize()}, {@code abort()} and a registration order the orchestrator controls, while an adapter has a bound
 * port, a thread pool and a request path. So this interface does not extend the core lifecycle SPI, and an implementation
 * does not implement it either: the application assembly decides <em>when</em> to start the server and registers a
 * participant of its own that calls {@link #start()} and {@link #stop()} (ADR-0024 §2, ADR-0006).
 *
 * <p><b>Ownership.</b> Whatever an implementation created, it releases. An implementation that was handed a collaborator
 * — an executor, a thread provider — leaves it running, because whoever built it has other work that runs on it
 * (ADR-0024 §3, §4). {@link #stop()} and {@link #close()} are therefore idempotent and neither throws: a shutdown path
 * that fails halfway is a shutdown path nobody can finish.
 *
 * <p><b>Threading.</b> {@link #start()} returns once the server is accepting connections, not after every request has
 * been answered; requests are served on the implementation's own threads, which is precisely why {@link Handler} is
 * documented as concurrent and stateless. {@link #isRunning()} and {@link #port()} may be called from any thread and
 * report the state at the moment of the call rather than a snapshot consistent with each other.
 */
public interface HttpServerAdapter extends AutoCloseable {

  /**
   * Binds the port and begins serving.
   *
   * @throws IllegalStateException if this adapter was already started, or already stopped — an adapter instance is one
   *     lifecycle, and a silent second start would leave the caller holding a server that is not the one it configured
   * @throws RuntimeException whatever the server's own startup failure is, most often a port already in use; the cause
   *     is left as the server reported it, because "address in use" is information an operator needs
   */
  void start();

  /**
   * Stops serving and releases what this adapter created.
   *
   * <p>Idempotent and never throwing: a second call does nothing, and a partially stopped server is still stopped. In
   * flight requests are given the implementation's grace window and then abandoned; no method here waits for them,
   * because waiting is a policy and policies belong to the assembly that has a deadline (ADR-0024 §6).
   */
  void stop();

  /**
   * Whether this adapter is currently serving.
   *
   * @return {@code true} between a successful {@link #start()} and a {@link #stop()}
   */
  boolean isRunning();

  /**
   * The port the server is actually bound to.
   *
   * <p>An adapter may be asked to bind port {@code 0} and be given an ephemeral one by the operating system, which is how
   * a test starts a server without choosing a number, and how two servers share a machine. So the port is an <em>output</em>
   * of starting rather than an input, and it is reported here instead of being assumed by the caller.
   *
   * @return the bound port, or empty before {@link #start()} and after {@link #stop()} — a server that is not running has
   *     no port to name, and reporting {@code 0} instead would read like "port 0", the value that means "ask the OS"
   */
  OptionalInt port();

  /**
   * {@link #stop()}, so an adapter fits try-with-resources.
   *
   * <p>Declared here rather than left to {@link AutoCloseable}'s abstract {@code close() throws Exception}, because a
   * shutdown that required every caller to catch {@code Exception} would push error handling into application code that
   * cannot act on it — the same reason {@code Handler} declares no checked exception (ADR-0018 §8).
   */
  @Override
  default void close() {
    stop();
  }
}
