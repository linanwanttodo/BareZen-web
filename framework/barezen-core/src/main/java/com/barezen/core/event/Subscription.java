package com.barezen.core.event;

/**
 * A handle on one subscription, and the only way to end it.
 *
 * <p>It exists for exactly one moment: a component that subscribed must be able to stop receiving events when it is
 * stopping, without the bus having to know that the component is a plugin, a participant, or anything else (ADR-0017).
 * The alternative — the bus noticing when an owner goes away — is an {@code EventBus -> Plugin} dependency, and it is
 * the same mistake as a lifecycle engine that inspects what it drives.
 *
 * <p>The contract:
 *
 * <ul>
 *   <li>{@link #cancel()} is idempotent and safe to call from any thread, including concurrently with
 *       {@code publish()};
 *   <li>after {@code cancel()} returns, no {@code publish()} that starts afterwards invokes this handler;
 *   <li>it does not interrupt work already in progress: a handler that had already been entered runs to completion,
 *       and a handler entered concurrently with the cancel may still run for that one event. Cancelling is a
 *       statement about future dispatch, not about the current call, which is what keeps cancellation free of
 *       interrupt semantics.
 * </ul>
 *
 * <p>There is no {@code isCancelled()}, no {@code close()} and no {@link AutoCloseable}: {@code cancel()} is already
 * idempotent, so a synonym would be a second name for one operation, and a subscription holds no resource that
 * try-with-resources would protect — no thread, no socket, no queue.
 */
public interface Subscription {

  /**
   * Ends this subscription. Calling it again, or calling it concurrently with dispatch, is legal and has no further
   * effect.
   */
  void cancel();
}
