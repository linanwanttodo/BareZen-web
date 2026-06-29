package com.barezen.core.event;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The bus {@link EventBus#create()} returns: subscriptions in a concurrent map, dispatch on the caller's thread.
 *
 * <p>The two containers here are chosen against the contract in {@link EventBus}, not borrowed for their reputation,
 * and each is doing exactly one job:
 *
 * <ul>
 *   <li>{@code ConcurrentHashMap} routes by event type. It is not used for ordering and cannot be: only one list is
 *       ever iterated per publish, so the map's unspecified key order can never become a dispatch order. What it
 *       provides is a lock-free read on the publish path and an atomic {@code computeIfAbsent} on the subscribe path,
 *       so two threads subscribing the same new type get one list rather than two.
 *   <li>{@code CopyOnWriteArrayList} holds the handlers of one type in insertion order, which is what makes
 *       "registration order is invocation order" a guarantee instead of an accident. Its iterator walks a frozen
 *       snapshot, so a publish in progress is neither disturbed by a later subscribe nor left with a
 *       {@code ConcurrentModificationException}, and it never mutates during iteration.
 * </ul>
 *
 * <p>Neither container is trusted to supply semantics on its own. Two properties come from the code, not from the
 * types: cancellation takes effect through {@code remove}, which is what defines "no publish starting after
 * {@code cancel()} returns invokes this handler"; and failure behaviour comes from the absence of any {@code catch}
 * block below, which is the only implementation of "an exception is never lost" that can be checked by reading.
 */
final class SynchronousEventBus implements EventBus {

  private final ConcurrentMap<Class<?>, CopyOnWriteArrayList<Registration<?>>> subscriptions =
      new ConcurrentHashMap<>();

  @Override
  public <E> Subscription subscribe(Class<E> eventType, EventHandler<E> handler) {
    Objects.requireNonNull(eventType,
        "the event type a handler subscribes to; a null type would be a handler for everything, which is not"
            + " something this bus can route");
    Objects.requireNonNull(handler,
        "the handler for event type '" + eventType.getSimpleName() + "'; a null handler would make a subscription"
            + " that cannot be cancelled and cannot be invoked");
    CopyOnWriteArrayList<Registration<?>> registrations =
        subscriptions.computeIfAbsent(eventType, type -> new CopyOnWriteArrayList<>());
    Registration<E> registration = new Registration<>(handler, registrations);
    registrations.add(registration);
    return registration;
  }

  @Override
  public void publish(Object event) {
    Objects.requireNonNull(event,
        "the event to publish; an event has no identity without a type, and type is the whole routing decision, so"
            + " there is no handler this could be delivered to");
    Class<?> eventType = event.getClass();
    List<Registration<?>> registrations = subscriptions.get(eventType);
    if (registrations == null) {
      return;
    }
    // No catch anywhere below. Model A - the first failure ends dispatch and the exception propagates unchanged -
    // is implemented by not handling anything, so there is no block that could log, wrap, swallow or reorder it.
    for (Registration<?> registration : registrations) {
      registration.deliver(event);
    }
  }

  /**
   * One subscription: the handler, the list it is registered in, and the handle that removes it from that list.
   *
   * <p>Equality is identity, deliberately. Two subscriptions with the same handler object for the same type are two
   * entries the caller subscribed twice and can cancel separately, so {@link #cancel()} must not remove "the first
   * registration of this handler"; it removes this one.
   */
  private static final class Registration<E> implements Subscription {

    private final EventHandler<E> handler;
    private final List<Registration<?>> registrations;

    Registration(EventHandler<E> handler, List<Registration<?>> registrations) {
      this.handler = handler;
      this.registrations = registrations;
    }

    @Override
    public void cancel() {
      // Removal from a copy-on-write list is atomic and takes effect on snapshots taken after it returns, which is
      // exactly the promised boundary: a publish already iterating may still reach this handler, a publish that
      // starts later will not. Idempotent because the second removal finds nothing.
      registrations.remove(this);
    }

    /**
     * The unchecked cast is safe because of two facts in this class: {@code subscribe} ties the handler's type
     * parameter to the class it registered under, and routing on {@code event.getClass()} means the only events that
     * reach this method were published under that same class. Supertype matching would break the second fact, which
     * is one of the reasons exact matching is the rule (ADR-0017).
     */
    @SuppressWarnings("unchecked")
    void deliver(Object event) {
      handler.handle((E) event);
    }
  }
}
