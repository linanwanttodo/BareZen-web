package com.barezen.core.event;

/**
 * Delivers an object to the handlers that asked for its type, on the thread that published it.
 *
 * <p>Two operations, and one handle type to end a subscription. The bus does not know who subscribed, who publishes,
 * or that a plugin exists; it resolves nothing, reads no configuration and owns no thread. Those are the
 * application's facts, and a bus that carried them would be a context (ADR-0017).
 *
 * <p>The dispatch contract, in full:
 *
 * <ul>
 *   <li><b>Synchronous.</b> {@code publish(event)} invokes every live handler for that event and only then returns.
 *       There is no executor, no queue and no worker, so the caller can see the handler's failure, its side effects
 *       and its cost in its own stack. An asynchronous bus would have to answer ordering, failure reporting,
 *       backpressure and replay before it had a single real consumer to answer them for.
 *   <li><b>Exact type matching.</b> A handler registered for {@code UserCreated.class} receives an event when
 *       {@code event.getClass() == UserCreated.class} and otherwise does not. There is no supertype or interface
 *       matching, so publishing one event cannot fan out to a hierarchy nobody enumerated, and the cost of a publish
 *       is one map lookup. A subscriber that wants several types subscribes several times.
 *   <li><b>Registration order is invocation order</b> within one event type: first registered, first invoked, since
 *       that is the only order a reader can determine from the call sites. Ordering across different event types is
 *       not a thing, because one publish never waits on another.
 *   <li><b>The first handler failure ends the dispatch.</b> A handler that throws has its exception propagate out of
 *       {@code publish} unchanged — not wrapped, not logged and carried on, not collected — and the handlers
 *       registered after it are not invoked for that event. This is the most predictable rule available: one
 *       failure, one exception, one stack, and no aggregate type to define. It is also the rule ADR-0004 chose for
 *       startup, where the original failure always wins.
 *   <li><b>No nulls.</b> {@code publish(null)} and either null argument of {@code subscribe} fail immediately.
 *       A null event has no type, and type is the entire routing decision, so accepting one would mean inventing a
 *       bucket for it.
 *   <li><b>Concurrent publishing is supported.</b> Two threads may publish at the same time, and a thread may
 *       publish from inside a handler. One publish invokes its handlers in order; nothing serializes two publishes,
 *       and nothing serializes a handler against a subscription change. A handler must therefore be safe to call
 *       concurrently if the application publishes concurrently — which is the same obligation any shared component
 *       has, and the bus adds no lock that would hide it.
 *   <li><b>No lifecycle.</b> The bus is not a {@code LifecycleParticipant} and has no {@code close()}: it owns no
 *       thread, socket, file or queue, so a start or stop hook would be a place for the application's shutdown order
 *       to leak into a capability. Subscriptions end when their owners cancel them, which is what
 *       {@link Subscription} is for.
 * </ul>
 *
 * <p>Obtained with {@link #create()}. An application registers it with {@code ServiceAssembly} like any other
 * collaborator and passes it to whatever needs it:
 *
 * <pre>
 *   ServiceAssembly services = ServiceAssembly.builder()
 *       .factory(EventBus.class, s -> EventBus.create())
 *       .factory(AuthPlugin.class, s -> new AuthPlugin(s.require(EventBus.class)))
 *       .build();
 * </pre>
 */
public interface EventBus {

  /**
   * Registers a handler for one exact event type.
   *
   * @param eventType the class an event must be an exact instance of to reach this handler, never {@code null}
   * @param handler what to do with it, never {@code null}
   * @return the handle that ends this subscription; the caller owns it and is expected to hold on to it, because a
   *     dropped subscription cannot be cancelled and a subscriber that cannot cancel is a component that leaks a
   *     handler into a bus that outlives it
   * @throws NullPointerException if either argument is {@code null}
   */
  <E> Subscription subscribe(Class<E> eventType, EventHandler<E> handler);

  /**
   * Delivers {@code event} to the handlers registered for its exact class, in registration order, on this thread.
   *
   * <p>Returns normally when nothing is listening: an event nobody subscribes to is a published fact, not an error,
   * and making it one would force every publisher to know its audience.
   *
   * @param event the event to deliver, never {@code null}
   * @throws NullPointerException if {@code event} is {@code null}
   * @throws RuntimeException whatever the first failing handler threw, unwrapped, after which no later handler for
   *     this event runs
   */
  void publish(Object event);

  /**
   * Creates an empty bus.
   *
   * <p>No arguments, because there is nothing to decide yet: threads, ordering policies and failure aggregation are
   * all absent from this design rather than defaulted here, so a future variant that needs them is a different bus
   * chosen by the application, not a flag on this one.
   *
   * @return a new bus with no subscriptions, safe for concurrent use
   */
  static EventBus create() {
    return new SynchronousEventBus();
  }
}
