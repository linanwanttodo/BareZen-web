package com.barezen.testkit;

import com.barezen.core.config.Configuration;
import com.barezen.http.jdk.JdkHttpServerAdapter;
import com.barezen.web.Router;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.Objects;

/**
 * A real BareZen HTTP server on a random loopback port, for integration tests that must exercise the whole stack.
 *
 * <p>A {@code TestServer} wraps {@link JdkHttpServerAdapter} rather than reimplementing it. That is deliberate: an
 * integration test that used a fake server would only prove the fake works. Binding the real adapter to
 * {@code 127.0.0.1} on port {@code 0} means the operating system hands back an unused port, so parallel test classes
 * never fight over a fixed number, and the bytes that cross the socket are the bytes the production adapter produces.
 *
 * <p>Use it in a try-with-resources so the port is always released:
 *
 * <pre>{@code
 * try (TestServer server = TestServer.start(router)) {
 *   TestResponse response = server.client().get("/health");
 *   Assertions.assertThat(response).statusIs(200);
 * }
 * }</pre>
 *
 * <p>{@link #close()} is idempotent and releases the port and the adapter's threads, so a test that closes early and
 * then hits the end of its block does not double-stop. Closing does not swallow a real stop failure — if the adapter
 * cannot shut down, that is a genuine problem and it surfaces.
 */
public final class TestServer implements AutoCloseable {

  /** The port the adapter is asked to bind; {@code 0} tells the OS to pick one that is free. */
  private static final int RANDOM_PORT = 0;

  private final JdkHttpServerAdapter adapter;
  private final HttpClient sharedClient;
  private final int port;
  private final String baseUri;
  private volatile boolean closed;

  private TestServer(Router router) {
    this(router, Configuration.inMemory(Map.of()));
  }

  private TestServer(Router router, Configuration configuration) {
    Objects.requireNonNull(router, "the router the test server serves; a server with no routes answers 404 to everything"
        + " and is usually a mistake, not a policy");
    Objects.requireNonNull(configuration, "the adapter configuration; null would silently restore defaults");
    this.adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", RANDOM_PORT), router, configuration);
    this.adapter.start();
    this.port = this.adapter.port()
        .orElseThrow(() -> new IllegalStateException("a started adapter must report the port it bound; it did not, so"
            + " the server is running but unreachable and no test can talk to it"));
    this.baseUri = "http://127.0.0.1:" + port;
    this.sharedClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  /**
   * Start a server that routes through {@code router} on a fresh loopback port.
   *
   * @param router the application's router, served exactly as it would be in production
   * @return a started server; close it to release the port
   */
  public static TestServer start(Router router) {
    return new TestServer(router);
  }

  /**
   * Start a server with an explicit adapter configuration, for tests that need a non-default policy such as a small
   * request-body limit. The snapshot is handed to the real adapter; the test server does not read configuration itself.
   *
   * @param router the application's router, served exactly as it would be in production
   * @param configuration the adapter configuration, never null; an empty snapshot keeps the adapter defaults
   * @return a started server; close it to release the port
   */
  public static TestServer start(Router router, Configuration configuration) {
    return new TestServer(router, configuration);
  }

  /**
   * The port the OS assigned.
   *
   * @return the bound port, always positive once started
   */
  public int port() {
    return port;
  }

  /**
   * The absolute base the client resolves paths against.
   *
   * @return {@code http://127.0.0.1:<port>}
   */
  public String baseUri() {
    return baseUri;
  }

  /**
   * A client wired to this server, sharing one underlying HTTP connection pool.
   *
   * @return a client whose paths resolve against {@link #baseUri()}
   */
  public TestHttpClient client() {
    return new TestHttpClient(sharedClient, baseUri);
  }

  /**
   * Stop the server and release its port and threads. Idempotent.
   *
   * <p>Closing a server twice is not an error; closing it is the only way to release the port, so a test that forgets
   * to close leaks a bound socket until the JVM exits — which is why try-with-resources is the intended shape.
   */
  @Override
  public void close() {
    if (!closed) {
      closed = true;
      adapter.stop();
    }
  }

  /** @return whether the underlying adapter is still serving */
  public boolean isRunning() {
    return adapter.isRunning();
  }

  @Override
  public String toString() {
    return "TestServer[" + baseUri + "]";
  }
}
