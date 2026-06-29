package com.barezen.web;

/**
 * How an HTTP runtime is chosen by name: one identity, one factory, no lookup.
 *
 * <p>Phase 2.9 ships exactly one {@link HttpServerAdapter}, on {@code com.sun.net.httpserver}. An application should not
 * have to know that: it should say «the server called jdk» and receive whatever implements the contract, so that
 * swapping in a different adapter is a dependency change and one string rather than a rewrite. This type is that seam
 * (ADR-0025).
 *
 * <p><b>Why this is not a plugin.</b> A {@code Plugin} has an {@code initialize()} phase, registers components, and is
 * started and stopped by {@code LifecycleOrchestrator} in registration order. A provider has one method that returns a
 * value, no phase, no registration, and no side effect beyond the object it hands back. It exists so that a build can
 * <em>name</em> a server, not so that a server can participate in an application's lifecycle — the adapter it creates is
 * started and stopped by whoever built it (ADR-0025 §3, ADR-0024 §2).
 *
 * <p><b>Why this is not a service locator.</b> A locator answers «give me the thing of type T»; this answers «build the
 * adapter this configuration names, from the arguments I am handing you». {@link #create} takes the binding and the route
 * table as parameters, so a reader of a call site sees every input, and it returns exactly one type. There is no
 * {@code require(Class)}, no {@code lookup(String)}, no {@code instance()} and no static holder: nothing here can be
 * asked for an object it was never given, and nothing here stores what it built (ADR-0006, ADR-0025 §4).
 *
 * <p><b>Discovery is not this type's business.</b> {@link HttpServerAdapterProvider} is a {@code ServiceLoader}
 * service interface — implementations register themselves in {@code META-INF/services} — but nothing in this module calls
 * {@code ServiceLoader.load}: the contract's own rule ({@code the_contract_never_reflects_loads_or_scans}) refuses it, and
 * correctly. Deciding which adapter an application runs is composition, and composition belongs to the layer above. What
 * lives here is the shape of the answer, not the search (ADR-0025 §5).
 *
 * <p>Implementations are expected to be cheap to construct and side-effect free until {@link #create} is called, so a
 * caller can list the available adapters without starting anything. They are not required to be thread-safe, because a
 * discovered provider is read during assembly rather than on a request path.
 */
public interface HttpServerAdapterProvider {

  /**
   * The name this adapter is chosen with.
   *
   * <p>Stable across releases and independent of the runtime: {@code "jdk"} describes an implementation family, not the
   * JDK build that happens to be executing. A version in the id would mean an application's configuration breaks when it
   * moves from 17 to 21, which is the opposite of what a capability-detecting framework promises (ADR-0002, ADR-0025 §6).
   *
   * @return a non-blank name, never null
   */
  String id();

  /**
   * Builds — but does not start — the adapter this provider offers.
   *
   * <p>The argument list is short on purpose: a host, a port and a route table are the three things an HTTP runtime needs
   * that an application can also write in a configuration file. Anything else — a thread provider, a logger, a shutdown
   * policy — would make this signature grow with every collaborator the next phase invents, which is how a factory
   * becomes an assembly container. Callers that need those take the adapter's own constructor instead of this method
   * (ADR-0025 §7).
   *
   * @param host the address to bind, in host form; {@code "0.0.0.0"} for every interface, {@code "127.0.0.1"} for loopback
   * @param port the port to bind, {@code 0} to let the operating system choose an ephemeral one — which a caller reads
   *     back from {@link HttpServerAdapter#port()} after {@link HttpServerAdapter#start()}
   * @param router the route table to dispatch with, never null; the adapter stores it and never mutates it, because a
   *     built {@link Router} is immutable (ADR-0019 §13)
   * @return a new adapter in its un-started state, never null, owning its own server, executor and threads
   * @throws IllegalArgumentException if {@code host} is blank or {@code port} is outside {@code 0..65535}
   * @throws NullPointerException if {@code router} is null
   */
  HttpServerAdapter create(String host, int port, Router router);
}
