package com.barezen.core.concurrent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

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
 * <p>Because the admission slot is held by the delegate task rather than the caller, every accepted task
 * completes normally or exceptionally, including under {@link #shutdownNow()}: an interrupted worker
 * releases the slot in its {@code finally} block, so no {@code Future} can be left hanging. Shutdown closes
 * admission and wakes every parked submitter with {@link RejectedExecutionException}; it never waits for a
 * running task merely to reject a submission.
 */
final class GatedExecutorService extends AbstractExecutorService {

  private final ExecutorService delegate;
  private final ReentrantLock admission = new ReentrantLock(true);
  private final Condition slotAvailable = admission.newCondition();
  private final int maxConcurrency;
  private final String name;
  private int running;
  private boolean shutdown;

  GatedExecutorService(ExecutorService delegate, int maxConcurrency, String name) {
    this.delegate = delegate;
    this.maxConcurrency = maxConcurrency;
    this.name = name;
  }

  @Override
  public void execute(Runnable command) {
    Objects.requireNonNull(command, "command");
    admission.lock();
    try {
      while (running >= maxConcurrency && !shutdown) {
        slotAvailable.awaitUninterruptibly();
      }
      if (shutdown) {
        throw new RejectedExecutionException("executor '" + name + "' is shut down");
      }
      running++;
    } finally {
      admission.unlock();
    }
    boolean delegated = false;
    try {
      delegate.execute(() -> {
        try {
          command.run();
        } finally {
          releaseSlot();
        }
      });
      delegated = true;
    } finally {
      if (!delegated) {
        releaseSlot();
      }
    }
  }

  private void releaseSlot() {
    admission.lock();
    try {
      running--;
      slotAvailable.signalAll();
    } finally {
      admission.unlock();
    }
  }

  private void closeAdmission() {
    admission.lock();
    try {
      shutdown = true;
      slotAvailable.signalAll();
    } finally {
      admission.unlock();
    }
  }

  @Override
  public void shutdown() {
    closeAdmission();
    delegate.shutdown();
  }

  @Override
  public List<Runnable> shutdownNow() {
    closeAdmission();
    return delegate.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    admission.lock();
    try {
      return shutdown;
    } finally {
      admission.unlock();
    }
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
    admission.lock();
    try {
      return "GatedExecutor[" + name + ", available=" + (maxConcurrency - running) + "]";
    } finally {
      admission.unlock();
    }
  }
}
