package com.barezen.core.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Conformance suite shared by every {@link ThreadProvider} implementation.
 *
 * <p>A caller must not be able to tell platform from virtual by behaviour, otherwise changing the
 * requested {@link ThreadPolicy} changes semantics silently. So the assertions here describe the
 * contract rather than a class, and each concrete subclass supplies the provider under test.
 */
abstract class ThreadProviderConformanceTest {

  /** @return a provider with the given executor termination grace, or {@code null} if unsupported here */
  abstract ThreadProvider create(long closeTimeoutMillis);

  abstract ThreadProvider.Kind expectedKind();

  private ThreadProvider provider;

  /** Lazily creates the provider for one test and guarantees it is released afterwards. */
  private ThreadProvider provider() {
    if (provider == null) {
      provider = create(defaultGraceMillis());
      org.junit.jupiter.api.Assumptions.assumeTrue(provider != null,
          "no " + expectedKind() + " provider available on this runtime");
    }
    return provider;
  }

  private static long defaultGraceMillis() {
    return 1_000L;
  }

  @AfterEach
  void releaseProvider() {
    if (provider != null) {
      provider.close();
      provider = null;
    }
  }

  @Test
  void identityIsStableAndMatchesTheExpectedKind() {
    ThreadProvider current = provider();
    assertThat(current.kind()).isEqualTo(expectedKind());
    assertThat(current.name()).isNotBlank();
    assertThat(current.preferred()).isIn(true, false);
  }

  @Test
  @Timeout(30)
  void boundedExecutorNeverRunsMoreThanMaxConcurrencyTasksAtOnce() throws Exception {
    int max = 3;
    int tasks = 40;
    ExecutorService executor = provider().newBoundedExecutor(max, "bounded");

    AtomicInteger running = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    CountDownLatch done = new CountDownLatch(tasks);
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < tasks; i++) {
      futures.add(executor.submit(() -> {
        peak.accumulateAndGet(running.incrementAndGet(), Math::max);
        try {
          Thread.sleep(5);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          running.decrementAndGet();
          done.countDown();
        }
      }));
    }

    assertThat(done.await(20, TimeUnit.SECONDS)).as("all tasks completed").isTrue();
    for (Future<?> future : futures) {
      future.get(5, TimeUnit.SECONDS);
    }
    assertThat(peak.get()).isLessThanOrEqualTo(max).isGreaterThan(1);
  }

  @Test
  @Timeout(30)
  void unboundedExecutorRunsEverySubmittedTask() throws Exception {
    int tasks = 200;
    ExecutorService executor = provider().newUnboundedExecutor("unbounded");
    AtomicInteger completed = new AtomicInteger();
    CountDownLatch done = new CountDownLatch(tasks);

    for (int i = 0; i < tasks; i++) {
      executor.submit(() -> {
        completed.incrementAndGet();
        done.countDown();
      });
    }

    assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
    assertThat(completed.get()).isEqualTo(tasks);
  }

  @Test
  @Timeout(30)
  @DisplayName("a task rejected after shutdown surfaces through its Future instead of vanishing")
  void submissionAfterShutdownIsRejectedNotSilentlyDropped() {
    ExecutorService executor = provider().newUnboundedExecutor("rejecting");
    provider().close();
    provider = null;

    assertThatThrownBy(() -> executor.submit(() -> "never runs"))
        .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
  }

  @Test
  @Timeout(30)
  @DisplayName("shutdown terminates the executors this provider created")
  void shutdownTerminatesOwnedExecutors() throws Exception {
    ThreadProvider current = create(5_000L);
    org.junit.jupiter.api.Assumptions.assumeTrue(current != null, "provider unavailable");
    provider = null;
    ExecutorService executor = current.newUnboundedExecutor("owned");
    executor.submit(() -> {
      // Returns at once, so orderly termination is what closes the executor.
    });

    current.close();

    assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    assertThat(executor.isTerminated()).isTrue();
  }

  @Test
  @Timeout(30)
  @DisplayName("an executor whose task ignores orderly shutdown is force stopped after the grace period")
  void shutdownEscalatesToForceStopForTasksThatRefuseToInterrupt() throws Exception {
    long graceMillis = 300L;
    ThreadProvider current = create(graceMillis);
    org.junit.jupiter.api.Assumptions.assumeTrue(current != null, "provider unavailable");
    provider = null;
    ExecutorService executor = current.newUnboundedExecutor("stuck");
    AtomicReference<Throwable> terminated = new AtomicReference<>();
    CountDownLatch started = new CountDownLatch(1);
    Future<?> stuck = executor.submit(() -> {
      started.countDown();
      try {
        // Ignores the orderly shutdown request, so only escalation can end it.
        Thread.sleep(Long.MAX_VALUE);
      } catch (InterruptedException e) {
        terminated.set(e);
        Thread.currentThread().interrupt();
      }
    });
    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

    long begin = System.nanoTime();
    current.close();
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

    assertThat(elapsedMillis).as("grace period was honoured").isGreaterThanOrEqualTo(graceMillis / 2);
    assertThat(stuck.isDone()).as("task was released by force stop").isTrue();
    assertThat(terminated.get()).isInstanceOf(InterruptedException.class);
    assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("executor terminated").isTrue();
  }

  @Test
  @Timeout(30)
  void shutdownIsIdempotentAndRefusesFurtherExecutors() {
    ThreadProvider current = create(1_000L);
    org.junit.jupiter.api.Assumptions.assumeTrue(current != null, "provider unavailable");
    provider = current;
    current.newUnboundedExecutor("first");

    current.close();
    provider = null;

    assertThatCode(current::close).doesNotThrowAnyException();
    assertThatThrownBy(() -> current.newUnboundedExecutor("late"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("shut down");
    assertThatThrownBy(() -> current.newBoundedExecutor(2, "late"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void argumentValidationIsTheSameForBothProviders() {
    ThreadProvider current = provider();
    assertThatThrownBy(() -> current.newBoundedExecutor(0, "bad"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxConcurrency");
    assertThatThrownBy(() -> current.newBoundedExecutor(-1, "bad"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> current.newBoundedExecutor(2, " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namePrefix");
    assertThatThrownBy(() -> current.newUnboundedExecutor(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** Bounded executors on a virtual provider park on a permit; a broken permit release would hang here. */
  @Test
  @Timeout(30)
  void everyTaskOnABoundedExecutorReleasesItsPermitEvenWhenItThrows() throws Exception {
    int max = 2;
    int tasks = 12;
    ExecutorService executor = provider().newBoundedExecutor(max, "throwing");
    CountDownLatch done = new CountDownLatch(tasks);

    for (int i = 0; i < tasks; i++) {
      executor.submit(() -> {
        done.countDown();
        throw new IllegalStateException("task failed on purpose");
      });
    }

    assertThat(done.await(20, TimeUnit.SECONDS))
        .as("a failing task must not starve the remaining ones")
        .isTrue();
  }
}
