package com.barezen.core.concurrent;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bounds how many submitted tasks execute at the same instant on top of a per-task delegate.
 *
 * <p>Package private, reachable only through {@link ExecutorSupport#boundTo}. The delegate is always a
 * per-task virtual thread executor, which by itself would run as many concurrent operations as a caller
 * submits.
 *
 * <p>Admission is taken on the submitting thread, so a caller that outruns the bound is slowed down
 * instead of accumulating an unbounded queue. This differs deliberately from a bounded platform pool,
 * which queues surplus tasks without limit: with virtual threads an unlimited queue of parked tasks
 * reaching a finite resource is exactly the growth the bound is meant to prevent. Both providers bound
 * concurrency identically; what they do with excess submissions is not the same, and callers who need
 * queueing should size the platform pool instead.
 *
 * <p>Because the permit is held by the delegate task rather than the caller, every accepted task
 * completes normally or exceptionally, including under {@link #shutdownNow()}: an interrupted worker
 * releases the permit in its {@code finally} block, so no {@code Future} can be left hanging.
 */
final class GatedExecutorService extends AbstractExecutorService {

  private final ExecutorService delegate;
  private final Semaphore permits;
  private final String name;

  GatedExecutorService(ExecutorService delegate, int maxConcurrency, String name) {
    this.delegate = delegate;
    this.permits = new Semaphore(maxConcurrency, true);
    this.name = name;
  }

  @Override
  public void execute(Runnable command) {
    // Blocking here is the backpressure contract; acquireUninterruptibly keeps execute() free of a
    // checked exception and leaves cancellation to the shutdown path.
    permits.acquireUninterruptibly();
    boolean admitted = false;
    try {
      delegate.execute(() -> {
        try {
          command.run();
        } finally {
          permits.release();
        }
      });
      admitted = true;
    } finally {
      if (!admitted) {
        permits.release();
      }
    }
  }

  @Override
  public void shutdown() {
    delegate.shutdown();
  }

  @Override
  public List<Runnable> shutdownNow() {
    return delegate.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return delegate.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.awaitTermination(timeout, unit);
  }

  @Override
  public String toString() {
    return "GatedExecutor[" + name + ", available=" + permits.availablePermits() + "]";
  }
}
