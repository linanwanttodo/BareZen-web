package com.barezen.http.jdk;

import com.barezen.web.HttpServerAdapter;
import com.barezen.web.HttpServerAdapterProvider;
import com.barezen.web.Router;
import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * The {@code "jdk"} {@link HttpServerAdapterProvider}, registered in {@code META-INF/services} so that
 * {@code ServiceLoader.load(HttpServerAdapterProvider.class)} finds it (ADR-0025).
 *
 * <p>An instance is stateless and holds nothing: {@link #create} builds a fresh {@link JdkHttpServerAdapter} with its
 * own thread provider and keeps no reference to it. That is what makes the ownership rule in ADR-0024 §4 visible from
 * here — the adapter created by this method closes what it created, and nobody else can reach it through this provider
 * afterwards. There is no {@code instance()}, no static field and no cache, so a caller that discovers this provider
 * twice and creates twice gets two servers, which is the point of a factory that is not also a registry
 * (ADR-0025 §4, §8).
 *
 * <p>Public because {@code ServiceLoader} must be able to instantiate it, and no other reason: a discovered service
 * needs a public type with a public no-argument constructor. It declares no lifecycle, opens no socket and registers no
 * route, so discovering it costs nothing beyond loading a class (ADR-0025 §9).
 */
public final class JdkHttpServerAdapterProvider implements HttpServerAdapterProvider {

  /** The id ADR-0025 §6 settles on: an implementation family, not a JDK build. */
  private static final String ID = "jdk";

  @Override
  public String id() {
    return ID;
  }

  /**
   * Builds an un-started {@link JdkHttpServerAdapter} on {@code com.sun.net.httpserver}.
   *
   * <p>The host and port become an {@link InetSocketAddress} here, which is the type that names a binding, and the
   * adapter is handed that rather than two loose values. Nothing is bound until {@link HttpServerAdapter#start()}:
   * a caller that lists the available adapters, or builds one to inspect it, has opened no port by the time this method
   * returns (ADR-0025 §7, §9).
   *
   * @param host the host to bind, in host form; {@code "0.0.0.0"} for every interface, {@code "127.0.0.1"} for loopback
   * @param port the port to bind, {@code 0} for an ephemeral one read back from {@link HttpServerAdapter#port()}
   * @param router the route table to dispatch with, never mutated
   * @return a new adapter in its un-started state, never null
   * @throws IllegalArgumentException if {@code host} is blank or {@code port} is outside {@code 0..65535}. Both are
   *     checked here rather than left to the socket, because "the configuration names port 70000" is a fact about the
   *     configuration, and a configuration error reported as a bind failure arrives with the wrong blame attached
   * @throws NullPointerException if {@code host} or {@code router} is null
   */
  @Override
  public HttpServerAdapter create(String host, int port, Router router) {
    Objects.requireNonNull(host, "the host to bind; with none there is no address to name for a server");
    Objects.requireNonNull(router, "the route table to serve; an adapter with no routes answers 404 to everything,"
        + " which is a misassembly rather than a running server");
    if (host.isBlank()) {
      throw new IllegalArgumentException("the host must not be blank; use \"0.0.0.0\" to bind every interface and"
          + " \"127.0.0.1\" to bind loopback only");
    }
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("the port must be between 0 and 65535; got " + port
          + ". 0 is a legal value and means \"ask the operating system\", which HttpServerAdapter.port() then reports");
    }
    // Resolved rather than InetSocketAddress.createUnresolved(host, port): the JDK's server refuses an address whose
    // lookup never ran with "SocketException: Unresolved address", so an unresolved one would arrive at start time as a
    // bind failure whose message says nothing about a host name. A host that does not exist still lands in the same
    // unresolved address and the same deferred failure — this choice buys no error handling, it only avoids inventing
    // one for the common case (ADR-0025 §7).
    return new JdkHttpServerAdapter(new InetSocketAddress(host, port), router);
  }

  @Override
  public String toString() {
    return "JdkHttpServerAdapterProvider[" + ID + "]";
  }
}
