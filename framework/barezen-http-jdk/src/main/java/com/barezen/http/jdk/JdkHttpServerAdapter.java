package com.barezen.http.jdk;

import com.barezen.core.concurrent.ThreadPolicy;
import com.barezen.core.concurrent.ThreadProvider;
import com.barezen.core.concurrent.ThreadProviders;
import com.barezen.core.config.Configuration;
import com.barezen.core.runtime.RuntimeCapabilities;
import com.barezen.web.HttpServerAdapter;
import com.barezen.web.Router;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link HttpServerAdapter} on {@code com.sun.net.httpserver} — the JDK's own HTTP server, which is why this module
 * exists and why nothing else in the framework is allowed to name that package (ADR-0021).
 *
 * <p>The adapter owns exactly five things and disowns everything else:
 *
 * <ul>
 *   <li><b>the {@code HttpServer}.</b> Created here, stopped here, never handed out. There is no {@code server()}
 *       accessor, because an application that held the server could start a second context, replace the executor or
 *       stop it out from under this type;
 *   <li><b>the request executor</b>, created from a {@link ThreadProvider} when the adapter starts and released
 *       according to who owns that provider (next bullet);
 *   <li><b>the {@link ThreadProvider}, if the adapter created it.</b> {@code new JdkHttpServerAdapter(address, router)}
 *       builds one with {@link ThreadPolicy#AUTO}, so on JDK 21 requests run on virtual threads and on JDK 17 on
 *       platform threads — the capability rule from ADR-0002, with no branch in this file. The adapter closes a provider
 *       it created and never closes one it was handed: <em>whoever created it, owns it</em>, and a borrowed provider
 *       has other work running on it (ADR-0011, ADR-0024 §4);
 *   <li><b>the routing decision's HTTP consequences.</b> {@link Router} answers which handler and with what
 *       parameters; {@code 404}, {@code 405}, {@code 400}, {@code 413} and {@code 500} are written here, because the
 *       component that writes bytes is the component that has to choose a status (ADR-0019 §10, ADR-0023 §1);
 *   <li><b>the request-body limit.</b> Resolved once, at construction, from the configuration snapshot an application
 *       hands this adapter — or from the key's own default when none is handed — and enforced by the bounded read on
 *       the request path (ADR-0031 §Decision).
 * </ul>
 *
 * <p><b>One instance, one lifecycle.</b> {@code NEW → RUNNING → STOPPED}, with no path back: a second {@link #start()}
 * throws, as does {@code start → stop → start}. The reason is the port — a restarted server may be bound to a
 * different ephemeral port, so an application holding this object would be holding the address of a server that no
 * longer exists. A restart is a new adapter, built by the code that built this one (ADR-0024 §5). {@link #stop()} is
 * idempotent and never throws, and stopping an adapter that never started is legal, which is the same rule ADR-0012
 * states for the orchestrator.
 *
 * <p>Instances are not a shared resource: an application creates one per server it runs. All lifecycle methods are
 * safe to call from any thread; the state transitions are serialised on one lock, and the request path never takes it.
 *
 * @see JdkExchangeBridge for the request and response translation
 * @see JdkRequestDispatcher for routing and error semantics
 */
public final class JdkHttpServerAdapter implements HttpServerAdapter {

  private static final Logger log = LoggerFactory.getLogger(JdkHttpServerAdapter.class);

  /**
   * The backlog argument of {@code HttpServer.create(address, backlog)}. {@code 0} is not "zero connections allowed":
   * the JDK documents it as "use the platform default", which for this server is 50. A backlog is a tuning number with
   * no consumer in this phase, so it is the JDK's rather than a constructor parameter nobody can justify (ADR-0021 §5).
   */
  private static final int DEFAULT_BACKLOG = 0;

  /**
   * Seconds {@code HttpServer.stop(delay)} gives to exchanges already in progress.
   *
   * <p>Chosen rather than configured, and not extreme: {@code 0} would abandon a request that was mid-handler, and a
   * larger number would let one stuck handler dominate a shutdown that is supposed to be best effort (ADR-0005). The
   * framework has no shutdown-timeout configuration yet, and ADR-0024 §6 records this constant as the value to replace
   * when it does rather than leaving the choice buried in a call.
   */
  private static final int STOP_GRACE_SECONDS = 3;

  /** What {@link #start()}, {@link #stop()} and {@link #isRunning()} agree about. */
  private enum State { NEW, RUNNING, STOPPED }

  private final InetSocketAddress address;
  private final Router router;
  private final ThreadProvider threads;
  private final BodyLimit bodyLimit;
  private final Tls tls;

  /** {@code true} when this adapter built {@link #threads} and therefore owns the executors created from it. */
  private final boolean ownsThreadProvider;

  private final Object lifecycle = new Object();

  private State state = State.NEW;
  private HttpServer server;

  /**
   * A server with its own threads, unconfigured.
   *
   * <p>The thread provider is created here with {@link ThreadPolicy#AUTO} and closed by {@link #stop()}, so an
   * application that never starts an adapter holds nothing back: creating the provider starts no threads, and
   * {@code ThreadProviders} only resolves a capability it has already detected (ADR-0002). The request-body limit
   * resolves through the existing configuration layer to the key's declared default, which is the secure value —
   * an adapter built without a snapshot is an <em>unconfigured</em> one, never an <em>unbounded</em> one (ADR-0031
   * §Decision).
   *
   * @param address the host and port to bind; port {@code 0} asks the operating system for an ephemeral port, which
   *     {@link #port()} then reports
   * @param router the route table to dispatch with, never null, never mutated
   * @throws NullPointerException if {@code address} or {@code router} is null
   */
  public JdkHttpServerAdapter(InetSocketAddress address, Router router) {
    this(address, router, ThreadProviders.create(ThreadPolicy.AUTO, RuntimeCapabilities.detect(),
        ThreadProviders.DEFAULT_CLOSE_TIMEOUT), true, BodyLimit.unconfigured(), null);
  }

  /**
   * A server that runs its requests on somebody else's threads, unconfigured.
   *
   * <p>The provider is <em>borrowed</em>: this adapter creates one executor from it and never closes either, because
   * the executors a provider hands out belong to it (ADR-0011) and the provider itself belongs to whoever built it. A
   * test or an application that wants the threads gone calls {@code threads.close()} after {@link #stop()}.
   *
   * @param address the host and port to bind, port {@code 0} for an ephemeral one
   * @param router the route table to dispatch with, never null
   * @param threads the provider to create the request executor from, never null, never closed by this adapter
   * @throws NullPointerException if any argument is null
   */
  public JdkHttpServerAdapter(InetSocketAddress address, Router router, ThreadProvider threads) {
    this(address, router, threads, false, BodyLimit.unconfigured(), null);
  }

  /**
   * A server with its own threads, configured from a resolved snapshot — the one constructor that reads
   * {@code barezen.http.jdk.maxRequestBodyBytes}.
   *
   * <p>Configuration arrives resolved rather than read here, which is ADR-0013's rule (configuration is resolved at
   * assembly time) applied to an adapter, and the reason this module passes ArchUnit's ban on reading
   * {@code java.lang.System}: an adapter is configured by the arguments it is constructed with (ADR-0021 §23). The
   * bad-value failure lands at construction, naming the key, the value and the source — before a port is bound, so a
   * misconfigured limit is a startup error rather than a runtime surprise.
   *
   * @param address the host and port to bind, port {@code 0} for an ephemeral one
   * @param router the route table to dispatch with, never null, never mutated
   * @param configuration the resolved snapshot this adapter reads its HTTP options from, never null; a snapshot
   *     without the body-limit key is legal and takes the key's default (ADR-0031 §Decision)
   * @throws NullPointerException if any argument is null
   * @throws com.barezen.core.error.ConfigurationException if the key is present but not a valid number
   */
  public JdkHttpServerAdapter(InetSocketAddress address, Router router, Configuration configuration) {
    this(address, router, ThreadProviders.create(ThreadPolicy.AUTO, RuntimeCapabilities.detect(),
        ThreadProviders.DEFAULT_CLOSE_TIMEOUT), true, BodyLimit.from(configuration),
        Tls.from(configuration));
  }

  private JdkHttpServerAdapter(InetSocketAddress address, Router router, ThreadProvider threads,
                               boolean ownsThreadProvider, BodyLimit bodyLimit, Tls tls) {
    this.address = Objects.requireNonNull(address,
        "the address to bind; a server with nowhere to listen cannot be started");
    this.router = Objects.requireNonNull(router,
        "the route table to dispatch with; without one every request is a 404, which is a broken application dressed"
            + " up as a working server");
    this.threads = Objects.requireNonNull(threads,
        "the thread provider; requests have to run on somebody's threads, and the somebody has to be named");
    this.ownsThreadProvider = ownsThreadProvider;
    this.bodyLimit = Objects.requireNonNull(bodyLimit,
        "the request-body limit; an adapter that reads a body without a bound is the shape ADR-0031 removes");
    // null is a decision, not an absence: it means no keystore was configured, so this adapter serves plaintext, which
    // is every deployment's starting state (ADR-0050).
    this.tls = tls;
  }

  /**
   * Binds and starts serving.
   *
   * <p>Returns once the port is bound and the server accepts connections; requests are answered on the provider's
   * threads from then on. A failure to bind leaves the adapter {@code STOPPED} rather than retryable, and releases the
   * provider it owns, so a startup that could not bind does not leave a thread pool behind.
   *
   * @throws IllegalStateException if this adapter was already started, or already stopped
   * @throws UncheckedIOException if the address could not be bound — an {@code IOException} from the JDK's bind, wrapped
   *     rather than re-declared so that {@link HttpServerAdapter#start()} stays free of checked exceptions and the
   *     original "address already in use" is still the cause (ADR-0024 §7, ADR-0009)
   */
  @Override
  public void start() {
    synchronized (lifecycle) {
      if (state != State.NEW) {
        throw new IllegalStateException("this adapter is " + state + " and cannot be started again. One instance is"
            + " one server lifecycle: a restarted server may hold a different ephemeral port, so the caller's reference"
            + " would name a server that no longer exists. Build a new adapter to restart (ADR-0024 §5)");
      }
      HttpServer created = null;
      try {
        created = Tls.openServer(address, DEFAULT_BACKLOG, tls);
        if (created instanceof HttpsServer secure && tls != null) {
          tls.apply(secure);
        }
        // The executor is created from the provider rather than passed in, so that ownership of the threads stays
        // wherever ADR-0024 §4 puts it: with this adapter when it built the provider, with the provider's owner when
        // it did not.
        created.setExecutor(threads.newUnboundedExecutor("barezen-http-jdk"));
        created.createContext("/", new JdkRequestDispatcher(router, bodyLimit));
        created.start();
      } catch (IOException | RuntimeException startupFailure) {
        if (created != null) {
          created.stop(0);
        }
        if (ownsThreadProvider) {
          threads.close();
        }
        state = State.STOPPED;
        throw startupFailure instanceof IOException bindFailure
            ? new UncheckedIOException("cannot start the JDK HTTP server on " + address, bindFailure)
            : (RuntimeException) startupFailure;
      }
      server = created;
      state = State.RUNNING;
      if (tls == null) {
        log.info("JDK HTTP server listening on {} with {}", created.getAddress(), router);
      } else {
        // The keystore is named and no credential is: ADR-0050's rule that a log line may say which certificate file
        // is in use and never what opened it.
        log.info("JDK HTTPS server listening on {} with {} (keystore {})", created.getAddress(), router,
            tls.keystore());
      }
    }
  }

  /**
   * Stops serving, releases what this adapter created, and never throws.
   *
   * <p>Order matters: the server stops first so no new exchange arrives, then the owned provider is closed. In-flight
   * exchanges get {@value #STOP_GRACE_SECONDS} seconds, which is the JDK's own wait rather than a sleep added here.
   *
   * <p>A borrowed provider is left running, and so is the executor created from it — that executor belongs to the
   * provider, and the provider belongs to the application (ADR-0011, ADR-0024 §4).
   */
  @Override
  public void stop() {
    HttpServer stopping;
    synchronized (lifecycle) {
      // NEW → STOPPED is legal and idempotent, which is ADR-0012's rule applied here: a shutdown path that meets an
      // adapter that never started has nothing to undo and must not fail.
      if (state == State.STOPPED) {
        return;
      }
      state = State.STOPPED;
      stopping = server;
      server = null;
    }
    if (stopping != null) {
      stopping.stop(STOP_GRACE_SECONDS);
    }
    if (ownsThreadProvider) {
      // Closes exactly the executors this provider created — the request pool — and touches nothing else.
      threads.close();
    }
    log.info("JDK HTTP server stopped");
  }

  /**
   * {@code true} between a successful {@link #start()} and a {@link #stop()}.
   *
   * @return whether this adapter is currently serving
   */
  @Override
  public boolean isRunning() {
    synchronized (lifecycle) {
      return state == State.RUNNING;
    }
  }

  /**
   * The port actually bound.
   *
   * <p>Read from the server rather than remembered from the constructor, which is the only way an {@code address}
   * built with port {@code 0} gets a usable answer — a test binds port 0 and needs the number the operating system
   * chose (ADR-0021 §5).
   *
   * @return the bound port while running; empty before {@link #start()} and after {@link #stop()}, because a server
   *     that is not listening has no port to name
   */
  @Override
  public OptionalInt port() {
    synchronized (lifecycle) {
      if (state != State.RUNNING || server == null) {
        return OptionalInt.empty();
      }
      return OptionalInt.of(server.getAddress().getPort());
    }
  }

  @Override
  public String toString() {
    return "JdkHttpServerAdapter[" + address + ", " + state() + "]";
  }

  /** Read under the lifecycle lock, like every other accessor: a summary written to a log line has to agree with {@link #isRunning()}. */
  private State state() {
    synchronized (lifecycle) {
      return state;
    }
  }
}
