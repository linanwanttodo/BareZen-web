package com.barezen.core.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GatedExecutorService")
class GatedExecutorServiceTest {

  @Test
  @DisplayName("a null command is refused before it takes an admission slot")
  void refusesNullBeforeAdmission() {
    ExecutorService delegate = Executors.newCachedThreadPool();
    try {
      ExecutorService gated = ExecutorSupport.boundTo(delegate, 1, "null-command");

      assertThatNullPointerException().isThrownBy(() -> gated.execute(null));
      assertThat(delegate.isShutdown()).isFalse();
    } finally {
      delegate.shutdownNow();
    }
  }

  @Test
  @DisplayName("shutdown wakes a submitter parked on a full admission bound")
  void shutdownWakesParkedSubmitter() throws Exception {
    assertShutdownWakesParkedSubmitter(ExecutorService::shutdown);
  }

  @Test
  @DisplayName("shutdownNow wakes a submitter parked on a full admission bound")
  void shutdownNowWakesParkedSubmitter() throws Exception {
    assertShutdownWakesParkedSubmitter(ExecutorService::shutdownNow);
  }

  private static void assertShutdownWakesParkedSubmitter(ShutdownAction shutdown) throws Exception {
    ExecutorService delegate = Executors.newCachedThreadPool();
    ExecutorService gated = ExecutorSupport.boundTo(delegate, 1, "full");
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch releaseRunning = new CountDownLatch(1);
    CountDownLatch submitting = new CountDownLatch(1);
    CountDownLatch submissionFinished = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    gated.execute(() -> {
      running.countDown();
      boolean released = false;
      while (!released) {
        try {
          releaseRunning.await();
          released = true;
        } catch (InterruptedException ignored) {
          // Keep the slot occupied so shutdownNow must wake the submitter through its own admission state.
        }
      }
    });
    Thread submitter = new Thread(() -> {
      submitting.countDown();
      try {
        gated.execute(() -> {});
      } catch (Throwable rejected) {
        failure.set(rejected);
      } finally {
        submissionFinished.countDown();
      }
    }, "parked-submitter");

    try {
      assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
      submitter.start();
      assertThat(submitting.await(5, TimeUnit.SECONDS)).isTrue();
      awaitParked(submitter);

      shutdown.apply(gated);

      assertThat(submissionFinished.await(2, TimeUnit.SECONDS))
          .as("shutdown must reject a parked submitter without waiting for the running task")
          .isTrue();
      assertThat(failure.get()).isInstanceOf(RejectedExecutionException.class);
      assertThat(gated.isShutdown()).isTrue();
    } finally {
      releaseRunning.countDown();
      submitter.join(5_000);
      gated.shutdownNow();
      assertThat(delegate.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static void awaitParked(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
      Thread.yield();
    }
    assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
  }

  @FunctionalInterface
  private interface ShutdownAction {
    void apply(ExecutorService executor);
  }
}
