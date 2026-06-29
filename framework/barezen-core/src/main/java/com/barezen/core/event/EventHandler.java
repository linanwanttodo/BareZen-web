package com.barezen.core.event;

/**
 * Receives one kind of event on the thread that published it.
 *
 * <p>One method and no configuration: no {@code priority()}, no {@code async()}, no {@code executor()}, no
 * {@code filter()}. Ordering comes from when a subscription was made, threading comes from whoever called
 * {@code publish}, and a handler that needs to defer its work already owns an executor and can hand the event to it.
 * A hook with those knobs on it would be a scheduler without a schedule to run (ADR-0017).
 *
 * <p>The contract a handler owes the bus:
 *
 * <ul>
 *   <li>it accepts an event of exactly the type it subscribed with, because {@link EventBus} dispatches on
 *       {@code event.getClass()} and never widens;
 *   <li>it is safe to call concurrently if the application publishes concurrently, since the bus keeps no lock of
 *       its own around a handler call;
 *   <li>it is invoked once per subscription per publish, because the bus walks the live subscriptions of one event
 *       type and replays nothing.
 * </ul>
 *
 * <p>Throwing is allowed and is not swallowed: whatever a handler throws leaves
 * {@link EventBus#publish(Object)} unchanged, and no handler registered after it runs for that event. The bus keeps
 * no catch block, so there is nothing here that could log a failure and carry on (ADR-0017).
 *
 * @param <E> the exact event type this handler receives
 */
@FunctionalInterface
public interface EventHandler<E> {

  /**
   * Handles one published event.
   *
   * @param event the published event, never {@code null}
   */
  void handle(E event);
}
