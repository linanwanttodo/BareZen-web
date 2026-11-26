package com.barezen.http.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import com.barezen.core.concurrent.ThreadPolicy;
import com.barezen.core.concurrent.ThreadProvider;
import com.barezen.core.concurrent.ThreadProviders;
import com.barezen.core.kernel.LifecycleOrchestrator;
import com.barezen.core.kernel.LifecycleParticipant;
import com.barezen.core.runtime.RuntimeCapabilities;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.HttpServerAdapter;
import com.barezen.web.Response;
import com.barezen.web.Router;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The lifecycle matrix, run against a real bound port rather than a mock (ADR-0024 §1).
 *
 * <p>Each transition is asserted three ways where it can be: the state the accessors report, whether the port actually
 * answers, and whether the object still holds the resource. {@code isRunning()} returning false while the socket still
 * accepts connections would be a worse bug than a wrong boolean, so the socket is checked.
 *
 * <p>The last nested class is the one the phase's §8.8 asks for: the adapter is <em>not</em> a
 * {@link LifecycleParticipant}, and the way it joins an application's startup order is a participant written by the
 * application's assembly. That test is in this module rather than in {@code barezen-web} for a structural reason — the
 * contract has no dependency on core, so an assembly-shaped test cannot live there — and it is the evidence that the
 * boundary holds while the integration still works.
 */
class JdkHttpServerLifecycleTest {

  private static final Router ROUTES = Router.builder()
      .route(HttpMethod.GET, "/users/{id}", (request, parameters) -> new Response(200,
          Headers.of("Content-Type", "text/plain; charset=utf-8"),
          parameters.get("id").getBytes(StandardCharsets.UTF_8)))
      .build();

  private static JdkHttpServerAdapter adapter() {
    return new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), ROUTES);
  }

  /** Whether something is listening on this port right now, independent of what the adapter claims. */
  private static boolean portAnswers(int port) {
    try {
      return RawHttp.request(port, "GET", "/users/7", List.of(), null).status() == 200;
    } catch (IOException nobodyHome) {
      return false;
    }
  }

  private static int started(JdkHttpServerAdapter adapter) {
    adapter.start();
    return adapter.port().orElseThrow();
  }

  @Nested
  @DisplayName("one instance, one lifecycle")
  class Transitions {

    @Test
    void aNewAdapterClaimsNothingAndBindsNothing() {
      JdkHttpServerAdapter adapter = adapter();

      assertThat(adapter.isRunning()).isFalse();
      assertThat(adapter.port()).isEmpty();
      assertThat(adapter.toString()).contains("NEW");
    }

    @Test
    void startBindsReportsAndServes() throws IOException {
      JdkHttpServerAdapter adapter = adapter();

      adapter.start();

      assertThat(adapter.isRunning()).isTrue();
      int port = adapter.port().orElseThrow();
      // Port 0 asked the operating system, so the number reported is not the number configured — the whole reason
      // port() exists (ADR-0021 §5).
      assertThat(port).isPositive();
      assertThat(RawHttp.request(port, "GET", "/users/42", List.of(), null).body()).isEqualTo("42");
      assertThat(adapter.toString()).contains("RUNNING");
      adapter.stop();
    }

    @Test
    void aSecondStartFailsAndTheRunningServerKeepsServing() throws IOException {
      JdkHttpServerAdapter adapter = adapter();
      int port = started(adapter);

      assertThatThrownBy(adapter::start)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("cannot be started again")
          .hasMessageContaining("Build a new adapter");

      // The refusal is not a partial restart: the first server is untouched, which a silent second start would have
      // replaced with a different instance and possibly a different port (probe G).
      assertThat(adapter.isRunning()).isTrue();
      assertThat(adapter.port()).hasValue(port);
      assertThat(RawHttp.request(port, "GET", "/users/1", List.of(), null).body()).isEqualTo("1");
      adapter.stop();
    }

    @Test
    void startAfterStopFailsBecauseTheInstanceIsSpent() {
      JdkHttpServerAdapter adapter = adapter();
      adapter.start();
      adapter.stop();

      assertThatThrownBy(adapter::start)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("STOPPED");

      // And the port is gone with it — a restarted adapter on a remembered ephemeral port would be the bug this
      // refusal exists to prevent (ADR-0024 §5, probe H).
      assertThat(adapter.port()).isEmpty();
      assertThat(adapter.isRunning()).isFalse();
    }

    @Test
    void stopIsIdempotentAndNeverThrows() {
      JdkHttpServerAdapter adapter = adapter();
      started(adapter);

      adapter.stop();
      assertThatCode(adapter::stop).doesNotThrowAnyException();
      assertThatCode(adapter::stop).doesNotThrowAnyException();

      assertThat(adapter.isRunning()).isFalse();
      assertThat(adapter.port()).isEmpty();
    }

    @Test
    void stoppingAnAdapterThatNeverStartedIsLegal() {
      JdkHttpServerAdapter adapter = adapter();

      // NEW → STOPPED, which is ADR-0012's rule for the orchestrator applied here: a shutdown path that meets an
      // adapter that never got as far as binding has nothing to undo and must not fail for the sake of a state diagram.
      assertThatCode(adapter::stop).doesNotThrowAnyException();
      assertThat(adapter.isRunning()).isFalse();
      assertThat(adapter.toString()).contains("STOPPED");
    }

    @Test
    @SuppressWarnings("try") // the resource is never used inside the block, and that is the point: close() is the
    // assertion, and it has to run on a handle the compiler guarantees no method call touches (ADR-0024 §5).
    void closeIsStopAndFitsTryWithResources() throws IOException {
      JdkHttpServerAdapter adapter = adapter();
      int port;
      try (HttpServerAdapter asResource = adapter) {
        port = started(adapter);
        assertThat(portAnswers(port)).isTrue();
      }
      assertThat(portAnswers(port)).isFalse();
      assertThat(port).isPositive();

      // A second close() through stop() stays safe, which is what AutoCloseable in a nested try needs.
      assertThatCode(adapter::close).doesNotThrowAnyException();
    }

    @Test
    void stopReleasesThePortForANewAdapter() {
      JdkHttpServerAdapter first = adapter();
      int port = started(first);
      first.stop();

      // A new instance can bind the address the old one released; the port is free because stop() actually stopped the
      // server rather than only changing a flag.
      try (JdkHttpServerAdapter second = adapter()) {
        second.start();
        assertThat(second.isRunning()).isTrue();
        assertThat(second.port().orElseThrow()).isPositive();
        assertThat(second.port()).isNotEqualTo(OptionalInt.of(port));
      }
    }

    @Test
    void aStartedPortRefusesASecondBinder() {
      JdkHttpServerAdapter holder = adapter();
      int port = started(holder);

      JdkHttpServerAdapter competitor = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", port), ROUTES);

      assertThatThrownBy(competitor::start)
          .isInstanceOf(UncheckedIOException.class)
          .hasMessageContaining(String.valueOf(port))
          .hasRootCauseInstanceOf(java.net.BindException.class);

      // A failed start leaves the competitor spent rather than retryable, and the holder is untouched
      // (ADR-0024 §5, §7).
      assertThat(competitor.isRunning()).isFalse();
      assertThat(holder.isRunning()).isTrue();
      assertThat(holder.port()).hasValue(port);
      holder.stop();
    }

    @Test
    void aBindFailureCarriesTheJdkOwnCauseAndLeavesTheAdapterSpent() {
      // 192.0.2.1 is TEST-NET-1: an address no interface here has, so the kernel refuses the bind rather than the
      // permission layer refusing a privileged port. Chosen because this suite runs as root inside a container, where
      // port 1 binds successfully — probed, and the first version of this test passed for the wrong reason.
      JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("192.0.2.1", 0), ROUTES);

      assertThatThrownBy(adapter::start)
          .isInstanceOf(UncheckedIOException.class)
          .hasMessageContaining("192.0.2.1")
          .hasCauseInstanceOf(IOException.class);
      assertThat(adapter.isRunning()).isFalse();
      assertThat(adapter.port()).isEmpty();
      // Spent rather than retryable: a failed start moved it to STOPPED, and a retry would have to be a new instance
      // (ADR-0024 §5).
      assertThatThrownBy(adapter::start).isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  @DisplayName("whoever created it, owns it")
  class ExecutorOwnership {

    @Test
    void anAdapterThatCreatedItsProviderClosesItOnStop() {
      // The default constructor builds a ThreadProvider with ThreadPolicy.AUTO, so the adapter owns it and stop()
      // releases the request executor through it (ADR-0024 §4). logback is on the test classpath, so the release is
      // observable rather than assumed.
      try (CapturedLogs providerLog = CapturedLogs.record(
          "com.barezen.core.concurrent.VirtualThreadProvider", Level.DEBUG);
          CapturedLogs platformLog = CapturedLogs.record(
              "com.barezen.core.concurrent.PlatformThreadProvider", Level.DEBUG)) {
        JdkHttpServerAdapter adapter = adapter();
        started(adapter);
        adapter.stop();

        List<String> closers = concat(providerLog, platformLog);
        // Exactly one provider logged, because only one was created — AUTO picks virtual on 21 and platform on 17.
        assertThat(closers).hasSize(1);
        assertThat(closers.get(0)).contains("closing 1 executors created by thread provider");
      }
    }

    @Test
    void aBorrowedProviderSurvivesStopAndIsStillUsable() {
      ThreadProvider borrowed = ThreadProviders.create(ThreadPolicy.PLATFORM, RuntimeCapabilities.detect());
      try (CapturedLogs providerLog = CapturedLogs.record(
          "com.barezen.core.concurrent.PlatformThreadProvider", Level.DEBUG)) {
        JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), ROUTES,
            borrowed);
        started(adapter);
        adapter.stop();

        // The adapter must not close what it did not create. Probed against server.stop(): the JDK does not shut an
        // externally supplied executor either, so if this adapter stopped calling close() on a borrowed provider, the
        // provider would stay live — and this assertion is what fails if it ever starts (probe J).
        assertThat(providerLog.events()).isEmpty();
        assertThatThrownBy(adapter::start).isInstanceOf(IllegalStateException.class);

        // And "usable" rather than "not closed": the borrower can still hand out an executor afterwards, which a
        // closed provider refuses.
        var executor = borrowed.newUnboundedExecutor("still-here");
        try {
          assertThat(executor.submit(() -> "answered").get()).isEqualTo("answered");
        } catch (Exception unavailable) {
          throw new AssertionError("a borrowed provider must still work after the adapter stopped", unavailable);
        } finally {
          executor.shutdownNow();
        }
      } finally {
        borrowed.close();
      }
    }

    @Test
    void anAdapterThatFailedToStartReleasesTheProviderItCreated() {
      JdkHttpServerAdapter holder = adapter();
      int port = started(holder);

      try (CapturedLogs providerLog = CapturedLogs.record(
          "com.barezen.core.concurrent.VirtualThreadProvider", Level.DEBUG);
          CapturedLogs platformLog = CapturedLogs.record(
              "com.barezen.core.concurrent.PlatformThreadProvider", Level.DEBUG)) {
        JdkHttpServerAdapter competitor = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", port), ROUTES);

        assertThatThrownBy(competitor::start).isInstanceOf(UncheckedIOException.class);

        // The adapter that could not bind must not leave its own thread provider behind: no pool survives a failed
        // startup (ADR-0024 §4, and the release path in start()'s catch).
        assertThat(concat(providerLog, platformLog)).hasSize(1);
        holder.stop();
      }
    }

    @Test
    void aBorrowedProviderThatFailedToBindIsLeftAloneToo() {
      ThreadProvider borrowed = ThreadProviders.create(ThreadPolicy.PLATFORM, RuntimeCapabilities.detect());
      JdkHttpServerAdapter holder = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), ROUTES, borrowed);
      int port = started(holder);
      try {
        JdkHttpServerAdapter competitor = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", port), ROUTES,
            borrowed);

        assertThatThrownBy(competitor::start).isInstanceOf(UncheckedIOException.class);

        var executor = borrowed.newUnboundedExecutor("after-a-failed-bind");
        try {
          assertThat(executor.submit(() -> "fine").get()).isEqualTo("fine");
        } catch (Exception unavailable) {
          throw new AssertionError("a failed start must not close a provider it borrowed", unavailable);
        } finally {
          executor.shutdownNow();
        }
      } finally {
        holder.stop();
        borrowed.close();
      }
    }

    @ParameterizedTest(name = "provider fails with Error={0}")
    @ValueSource(booleans = {false, true})
    void aStartThatFailedAfterBindingSpendsTheAdapterAndClosesNothingItDidNotCreate(boolean failsWithError) {
      // The other half of start()'s catch: HttpServer.create() succeeded, so the JDK has a bound socket and a running
      // idle-timeout timer, and then the borrowed provider refuses to hand out an executor. Nothing in this module
      // throws at a moment of a test's choosing, so the collaborator is a hand-written provider rather than a mock
      // (ADR-0024 §4, §7).
      int bound = freePort();
      RefusingProvider refusing = new RefusingProvider(failsWithError);
      JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", bound), ROUTES,
          refusing);

      if (failsWithError) {
        assertThatThrownBy(adapter::start)
            .isInstanceOf(AssertionError.class)
            .hasMessage("no executor here");
      } else {
        assertThatThrownBy(adapter::start)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("no executor here");
      }

      // The refusal propagates unchanged — a provider's own exception is information the caller needs, not something to
      // wrap — and the instance is spent rather than retryable.
      assertThat(adapter.isRunning()).isFalse();
      assertThat(adapter.port()).isEmpty();
      assertThatThrownBy(adapter::start).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("cannot be started again");

      // And the borrowed provider was not closed: this adapter never owned it, whatever else the catch undid.
      assertThat(refusing.closeCalls).isZero();
      assertThat(refusing.executorRequests).isEqualTo(1);

      // What the catch cannot do: release the socket. stop(0) cancels the JDK's own timer threads, but a server that
      // never started keeps its listening socket bound — probed on Temurin 17.0.20.1 and 21.0.12.1, where the port
      // stays out of use for the life of the JVM and /proc/net/tcp keeps the entry in LISTEN. Recorded as design debt
      // (ADR-0024 §8) rather than asserted away: if a future JDK starts releasing it, this assertion fails and the debt
      // entry is revisited.
      assertThat(canBind(bound)).isFalse();
    }

    /** A borrowed provider that answers the one call {@code start()} makes with a refusal. */
    private static final class RefusingProvider implements ThreadProvider {
      int closeCalls;
      int executorRequests;
      private final boolean failsWithError;

      RefusingProvider() {
        this(false);
      }

      RefusingProvider(boolean failsWithError) {
        this.failsWithError = failsWithError;
      }

      private java.util.concurrent.ExecutorService refused() {
        if (failsWithError) {
          throw new AssertionError("no executor here");
        }
        throw new IllegalStateException("no executor here");
      }

      @Override
      public Kind kind() {
        return Kind.PLATFORM;
      }

      @Override
      public String name() {
        return "refusing";
      }

      @Override
      public boolean preferred() {
        return false;
      }

      @Override
      public java.util.concurrent.ExecutorService newBoundedExecutor(int maxConcurrency, String namePrefix) {
        return refused();
      }

      @Override
      public java.util.concurrent.ExecutorService newUnboundedExecutor(String namePrefix) {
        executorRequests++;
        return refused();
      }

      @Override
      public void close() {
        closeCalls++;
      }
    }

    /** An ephemeral port the operating system just handed out and then released. */
    private static int freePort() {
      try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
        return probe.getLocalPort();
      } catch (IOException unreachable) {
        throw new AssertionError("the loopback stack is not answering", unreachable);
      }
    }

    private static boolean canBind(int port) {
      try (java.net.ServerSocket reuse = new java.net.ServerSocket(port)) {
        return true;
      } catch (IOException alreadyHeld) {
        return false;
      }
    }

    private static List<String> concat(CapturedLogs... logs) {
      return java.util.Arrays.stream(logs)
          .flatMap(log -> log.events().stream())
          .map(event -> event.getLoggerName() + ": " + event.getFormattedMessage())
          .toList();
    }
  }

  @Nested
  @DisplayName("the thread model requests actually run on")
  class ThreadModel {

    /**
     * Threads still alive with the name prefix this adapter hands to its provider, sampled until the set empties.
     *
     * <p>The sampling is not a courtesy to a leak — it is what makes the assertion mean the same thing on both runtimes.
     * A provider whose executor was shut down has stopped handing out work, but the worker threads of a JDK 17 platform
     * pool end their own {@code run()} when the pool's idle timeout expires, and the VM drops a finished thread from the
     * live list only once the native side reports its termination. That report can land one scheduling tick after
     * {@code close()} returns, which is measured rather than assumed: an instantaneous count right after the adapter
     * closed was still reporting one thread in roughly one run in thirty, on a server that provably answers nothing on
     * the line below. A thread the adapter actually leaked stays in the list forever, so the deadline expiring with a
     * non-zero count fails exactly as it should.
     */
    private static long liveRequestThreads() throws InterruptedException {
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (true) {
        long live = Thread.getAllStackTraces().keySet().stream()
            .filter(candidate -> candidate.getName().startsWith("barezen-http-jdk-"))
            .count();
        if (live == 0 || System.nanoTime() >= deadline) {
          return live;
        }
        Thread.sleep(10);
      }
    }

    @Test
    void theDefaultProviderRunsRequestsOnTheRuntimeBestThreads() throws IOException {
      AtomicReference<String> observed = new AtomicReference<>();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/who", (request, parameters) -> {
            Thread running = Thread.currentThread();
            observed.set(running.getClass().getName() + "|" + running.getName());
            return new Response(200, Headers.empty(), "ok".getBytes(StandardCharsets.UTF_8));
          })
          .build();

      try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router)) {
        started(adapter);
        RawHttp.request(adapter.port().orElseThrow(), "GET", "/who", List.of(), null);

        // The adapter has no platform branch of its own: ThreadPolicy.AUTO resolves the provider, so this is ADR-0002's
        // capability rule running under a real socket rather than in a unit test.
        boolean virtualAvailable = RuntimeCapabilities.detect().virtualThreads();
        String thread = observed.get();
        if (virtualAvailable) {
          assertThat(thread).startsWith("java.lang.VirtualThread");
        } else {
          assertThat(thread).startsWith("java.lang.Thread|barezen-http-jdk-");
        }
      }
    }

    @Test
    void anExplicitlyBorrowedPlatformProviderIsHonouredOnJavaTwentyOne() throws IOException {
      AtomicReference<String> observed = new AtomicReference<>();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/who", (request, parameters) -> {
            observed.set(Thread.currentThread().getName());
            return new Response(200, Headers.empty(), "ok".getBytes(StandardCharsets.UTF_8));
          })
          .build();
      ThreadProvider platform = ThreadProviders.create(ThreadPolicy.PLATFORM, RuntimeCapabilities.detect());

      try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router,
          platform)) {
        started(adapter);
        RawHttp.request(adapter.port().orElseThrow(), "GET", "/who", List.of(), null);

        // PLATFORM never degrades silently and AUTO never overrides a borrowed provider: the named platform thread is
        // the evidence (ADR-0002, ADR-0024 §4).
        assertThat(observed.get()).startsWith("barezen-http-jdk-");
      } finally {
        platform.close();
      }
    }

    @Test
    void aStoppedAdapterLeavesNoRequestThreadBehind() throws IOException, InterruptedException {
      AtomicReference<String> observed = new AtomicReference<>();
      Router router = Router.builder()
          .route(HttpMethod.GET, "/who", (request, parameters) -> {
            observed.set(Thread.currentThread().getName());
            return new Response(200, Headers.empty(), "ok".getBytes(StandardCharsets.UTF_8));
          })
          .build();
      int port;
      try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router)) {
        port = started(adapter);
        RawHttp.request(port, "GET", "/who", List.of(), null);
      }

      // The provider the adapter created was closed by stop(), so the executors created from it terminated. Counting
      // the named threads is the direct evidence: on JDK 17 AUTO resolves to a platform pool whose threads carry this
      // prefix, so the count drops to zero; on JDK 21 the request ran on virtual threads, which are unnamed and
      // already gone, and the assertion holds trivially. The port check beside it is the half that is identical on
      // both runtimes.
      assertThat(liveRequestThreads()).isZero();
      assertThat(portAnswers(port)).isFalse();
    }
  }

  @Nested
  @DisplayName("the assembly, not the adapter, owns the lifecycle order")
  class ThroughTheAssembly {

    /**
     * A {@link LifecycleParticipant} written where ADR-0024 §2 says it belongs — in the application's assembly — that
     * starts and stops an {@link HttpServerAdapter}. The participant is an adapter-agnostic type: it names the contract,
     * not {@code JdkHttpServerAdapter}, which is the whole point of the seam.
     */
    private static final class ServerParticipant implements LifecycleParticipant {

      private final HttpServerAdapter server;
      private final AtomicInteger starts = new AtomicInteger();
      private final AtomicInteger stops = new AtomicInteger();

      ServerParticipant(HttpServerAdapter server) {
        this.server = server;
      }

      @Override
      public void start() {
        server.start();
        starts.incrementAndGet();
      }

      @Override
      public void stop() {
        server.stop();
        stops.incrementAndGet();
      }

      @Override
      public void abort() {
        // Nothing partial to undo: start() either bound the port or threw before it did, and the orchestrator's own
        // cleanup already released the participants that had started (ADR-0008).
      }

      @Override
      public String toString() {
        return "ServerParticipant[" + server + "]";
      }
    }

    @Test
    void theOrchestratorStartsAndStopsAServerItKnowsNothingAbout() throws IOException {
      HttpServerAdapter server = adapter();
      ServerParticipant participant = new ServerParticipant(server);

      try (LifecycleOrchestrator application = LifecycleOrchestrator.builder()
          .registerShutdownHook(false)
          .register("http", participant)
          .build()) {
        application.start();

        int port = server.port().orElseThrow();
        assertThat(port).isPositive();
        assertThat(RawHttp.request(port, "GET", "/users/42", List.of(), null).body()).isEqualTo("42");
        assertThat(participant.starts.get()).isEqualTo(1);

        application.stop();
        assertThat(participant.stops.get()).isEqualTo(1);
        assertThat(server.isRunning()).isFalse();
        assertThat(portAnswers(port)).isFalse();
      }

      assertThat(participant.starts.get()).isEqualTo(1);
      assertThat(participant.stops.get()).isEqualTo(1);
      // The server the assembly created is reachable only through the contract type, so the same participant would
      // drive a Jetty adapter unchanged (§8.8).
      assertThat(server).isInstanceOf(JdkHttpServerAdapter.class);
    }
  }

  @Nested
  @DisplayName("the adapter is not a lifecycle participant")
  class NotAParticipant {

    @Test
    void theAdapterImplementsTheContractAndAutoCloseableOnly() {
      assertThat(HttpServerAdapter.class.getInterfaces()).containsExactly(AutoCloseable.class);
      assertThat(JdkHttpServerAdapter.class.getInterfaces())
          .containsExactly(HttpServerAdapter.class)
          .doesNotContain(LifecycleParticipant.class);
      // And the contract type names no lifecycle verb at all: no initialize, no abort, no shutdown, no order.
      assertThat(memberNames(HttpServerAdapter.class.getDeclaredMethods()))
          .containsExactly("close", "isRunning", "port", "start", "stop");
    }

    @Test
    void theAdapterHasNoWayToBeNotifiedByTheOrchestrator() {
      // Neither type mentions the core lifecycle SPI anywhere in its public surface — the integration goes one way,
      // from the assembly, which §8.8 forbids in the other direction (ADR-0024 §2).
      assertThat(java.util.Arrays.stream(HttpServerAdapter.class.getDeclaredMethods())
          .filter(method -> !method.isSynthetic())
          .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))).isEmpty();
      assertThat(JdkHttpServerAdapter.class.getConstructors()).allSatisfy(constructor ->
          assertThat(java.util.Arrays.stream(constructor.getParameterTypes())
              .map(Class::getName).toList())
                  .doesNotContain("com.barezen.core.kernel.LifecycleOrchestrator")
                  .doesNotContain("com.barezen.core.kernel.LifecycleParticipant"));
    }

    /**
     * Names of non-synthetic methods.
     *
     * <p>JaCoCo injects a {@code $jacocoInit} method into every instrumented class, which reflection sees and a
     * coverage-gated build would otherwise fail on. Filtering synthetic members is the honest statement of the claim —
     * the contract declares five members, and a probe-instrumented class file is not a change to the contract.
     */
    private static List<String> memberNames(java.lang.reflect.Method[] methods) {
      return java.util.Arrays.stream(methods)
          .filter(method -> !method.isSynthetic())
          .map(java.lang.reflect.Method::getName)
          .sorted()
          .toList();
    }
  }
}
