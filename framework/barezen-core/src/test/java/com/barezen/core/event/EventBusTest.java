package com.barezen.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The dispatch contract, as tests.
 *
 * <p>Phase 2.6 claims a capability can be added without a context, so what is verified here is semantics rather than
 * features: when {@code publish} returns, in what order handlers run, what a failing handler does to the rest of the
 * dispatch, what {@code cancel()} promises, and how much of that survives two threads. Each was decided in ADR-0017
 * before the implementation was written, and the concurrency tests assert the ordering and at-most-once properties the
 * contract promises rather than throughput, which no contract makes a claim about.
 */
class EventBusTest {

  private record UserCreated(String userId) {}

  private record UserDeleted(String userId) {}

  private interface Marked {}

  /** A subtype of an interface, so exact matching can be distinguished from assignable matching. */
  private record AdminCreated(String userId, String level) implements Marked {}

  private final EventBus bus = EventBus.create();

  /** Single-threaded log; the concurrency tests use their own counters instead. */
  private final List<String> log = new ArrayList<>();

  private void record(String what) {
    log.add(what);
  }

  private static List<String> declaredMethodNames(Class<?> type) {
    return Arrays.stream(type.getDeclaredMethods())
        .filter(method -> !method.isSynthetic())
        .map(java.lang.reflect.Method::getName)
        .toList();
  }

  @Nested
  @DisplayName("the public surface")
  class Surface {

    @Test
    @DisplayName("the package is three public types and the bus implementation, and nothing else")
    void thePackageIsSmallOnPurpose() throws URISyntaxException {
      // An absence check that does not need Class.forName: every source file in the package compiles into this
      // directory, so the compiled listing is the type list. This is how "there is no Event marker interface, no
      // ExtensionPoint, no EventManager, no EventRegistry, no PluginContext" is verified (ADR-0017).
      Path classes = Path.of(EventBus.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path pkg = classes.resolve("com/barezen/core/event");
      List<String> compiled;
      try (Stream<Path> files = Files.list(pkg)) {
        compiled = files.map(path -> path.getFileName().toString().replace(".class", ""))
            .filter(name -> !name.contains("$"))
            .sorted()
            .toList();
      } catch (java.io.IOException failure) {
        throw new AssertionError("could not list " + pkg, failure);
      }

      assertThat(compiled).containsExactly("EventBus", "EventHandler", "Subscription", "SynchronousEventBus");
      assertThat(compiled).doesNotContain("Event", "BaseEvent", "ApplicationEvent", "ExtensionPoint", "ExtensionRegistry",
          "Contribution", "EventManager", "EventRegistry", "AsyncEventBus");
    }

    @Test
    @DisplayName("EventBus is subscribe and publish, and carries nothing else")
    void theBusExposesTwoOperations() {
      List<String> declared = declaredMethodNames(EventBus.class);

      assertThat(declared).containsExactlyInAnyOrder("create", "publish", "subscribe");
      // Not a LifecycleParticipant, and no second engine of its own: the bus owns no thread, socket, file or
      // queue, so it has nothing to start or stop (ADR-0017).
      assertThat(EventBus.class.getInterfaces()).isEmpty();
      assertThat(declared).doesNotContain("initialize", "start", "stop", "abort", "close", "register", "unsubscribe",
          "clear", "subscriberCount", "publishAsync", "post");
      // A bus that resolved objects would be a context. The verbs are absent, and nobody_exposes_a_type_lookup
      // catches the shape if one arrives with a Class parameter.
      assertThat(declared).doesNotContain("require", "get", "find", "service", "lookup", "resolve", "bean");
    }

    @Test
    @DisplayName("Subscription is one method and is not AutoCloseable")
    void theHandleIsOneMethod() {
      assertThat(declaredMethodNames(Subscription.class)).containsExactly("cancel");
      // cancel() is already idempotent, and a subscription holds no resource: close() would be a synonym for it and
      // AutoCloseable would invite try-with-resources on a handle whose owner outlives the call (ADR-0017).
      assertThat(Subscription.class.getInterfaces()).isEmpty();
      assertThat(Subscription.class.getMethods()).extracting(java.lang.reflect.Method::getName)
          .containsExactly("cancel");
    }

    @Test
    @DisplayName("EventHandler is a single abstract method with no configuration")
    void theHandlerIsAFunctionShape() {
      assertThat(declaredMethodNames(EventHandler.class)).containsExactly("handle");
      assertThat(EventHandler.class.isAnnotationPresent(FunctionalInterface.class)).isTrue();
      assertThat(EventHandler.class.getMethods()).hasSize(1);
      // No scheduling or filtering knobs, each of which has no consumer in this phase.
      assertThat(declaredMethodNames(EventHandler.class)).doesNotContain("priority", "order", "name", "async",
          "executor", "filter", "retry", "timeout", "metadata");
    }

    @Test
    @DisplayName("nothing is handed to a subscriber or a handler except the event")
    void noContextTravelsWithAnyPhase() {
      // The Phase 2.5 test generalised: a parameter that is not an event is where a context would re-enter. Order
      // across getMethods() is unspecified, so the set of parameter types is what is assertable.
      List<String> parameters = parameterTypesOf(EventBus.class, Subscription.class);

      assertThat(parameters).containsExactlyInAnyOrder(
          "java.lang.Class", "com.barezen.core.event.EventHandler", "java.lang.Object");
      assertThat(parameters).noneMatch(name -> name.startsWith("com.barezen.core.")
          && !name.startsWith("com.barezen.core.event."));
    }

    private List<String> parameterTypesOf(Class<?>... types) {
      return Arrays.stream(types)
          .flatMap(type -> Arrays.stream(type.getMethods()))
          .filter(method -> method.getDeclaringClass() != Object.class)
          .flatMap(method -> Arrays.stream(method.getParameterTypes()))
          .map(Class::getName)
          .toList();
    }
  }

  @Nested
  @DisplayName("dispatch")
  class Dispatch {

    @Test
    @DisplayName("a handler receives the event its type asked for")
    void aHandlerReceivesItsEventType() {
      bus.subscribe(UserCreated.class, event -> record("created:" + event.userId()));

      bus.publish(new UserCreated("123"));

      assertThat(log).containsExactly("created:123");
    }

    @Test
    @DisplayName("every handler for the type receives the same instance")
    void allHandlersReceiveTheSameInstance() {
      List<Object> seen = new ArrayList<>();
      UserCreated event = new UserCreated("123");
      bus.subscribe(UserCreated.class, seen::add);
      bus.subscribe(UserCreated.class, seen::add);

      bus.publish(event);

      // No copy, no wrapper: the bus hands over what it was given, which is what makes a handler's mutation of a
      // mutable event visible to the handlers after it.
      assertThat(seen).containsExactly(event, event);
    }

    @Test
    @DisplayName("publishing an event nobody subscribed to returns normally")
    void anEventNobodyListensToIsNotAnError() {
      // A publisher that had to know its audience would have to be told which plugins exist, which is the coupling
      // this bus refuses (ADR-0017).
      bus.publish(new UserCreated("123"));

      assertThat(log).isEmpty();
    }

    @Test
    @DisplayName("a handler is not invoked for a different event type")
    void anotherTypeDoesNotReachTheHandler() {
      bus.subscribe(UserCreated.class, event -> record("created"));

      bus.publish(new UserDeleted("123"));

      assertThat(log).isEmpty();
    }

    @Test
    @DisplayName("any object can be an event, because there is no Event interface to implement")
    void anyObjectIsAnEvent() {
      bus.subscribe(String.class, value -> record("string:" + value));

      bus.publish("hello");

      assertThat(log).containsExactly("string:hello");
    }

    @Test
    @DisplayName("a handler for a supertype or an interface does not receive a subtype")
    void matchingIsExactNotAssignable() {
      bus.subscribe(Object.class, value -> record("object"));
      bus.subscribe(Marked.class, value -> record("marked"));
      bus.subscribe(AdminCreated.class, value -> record("admin"));

      bus.publish(new AdminCreated("1", "root"));

      // Routing on event.getClass() only: one map lookup, no hierarchy walk, and a handler subscribed to Object
      // receives an Object and nothing else (ADR-0017).
      assertThat(log).containsExactly("admin");
    }

    @Test
    @DisplayName("dispatch runs on the publishing thread and publish returns after the handlers")
    void dispatchIsSynchronousOnTheCallersThread() {
      List<String> trace = new ArrayList<>();
      String publisher = Thread.currentThread().getName();
      bus.subscribe(UserCreated.class, event -> {
        trace.add(Thread.currentThread().getName());
        trace.add("handler");
      });

      bus.publish(new UserCreated("123"));
      trace.add("after publish");

      assertThat(trace).containsExactly(publisher, "handler", "after publish");
    }

    @Test
    @DisplayName("a handler subscribed to one type is untouched by another type's publish")
    void bucketsAreIndependent() {
      AtomicInteger created = new AtomicInteger();
      AtomicInteger deleted = new AtomicInteger();
      bus.subscribe(UserCreated.class, event -> created.incrementAndGet());
      bus.subscribe(UserDeleted.class, event -> deleted.incrementAndGet());

      bus.publish(new UserCreated("1"));
      bus.publish(new UserCreated("2"));

      assertThat(created.get()).isEqualTo(2);
      assertThat(deleted.get()).isZero();
    }
  }

  @Nested
  @DisplayName("handler ordering")
  class Ordering {

    @Test
    @DisplayName("registration order is invocation order")
    void firstRegisteredIsFirstInvoked() {
      bus.subscribe(UserCreated.class, event -> record("a"));
      bus.subscribe(UserCreated.class, event -> record("b"));
      bus.subscribe(UserCreated.class, event -> record("c"));

      bus.publish(new UserCreated("123"));

      assertThat(log).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("ordering is per event type, so interleaved subscription keeps each type's own order")
    void orderingIsPerEventType() {
      bus.subscribe(UserCreated.class, event -> record("created-1"));
      bus.subscribe(UserDeleted.class, event -> record("deleted-1"));
      bus.subscribe(UserCreated.class, event -> record("created-2"));
      bus.subscribe(UserDeleted.class, event -> record("deleted-2"));

      bus.publish(new UserDeleted("1"));
      bus.publish(new UserCreated("1"));

      // The routing map cannot contribute an order of its own, because one publish reads one bucket.
      assertThat(log).containsExactly("deleted-1", "deleted-2", "created-1", "created-2");
    }

    @Test
    @DisplayName("the same handler object subscribed twice is invoked twice")
    void subscribingTwiceIsSubscribingTwice() {
      EventHandler<UserCreated> handler = event -> record("x");
      bus.subscribe(UserCreated.class, handler);
      bus.subscribe(UserCreated.class, handler);

      bus.publish(new UserCreated("123"));

      assertThat(log).containsExactly("x", "x");
    }
  }

  @Nested
  @DisplayName("handler failure")
  class Failure {

    private final RuntimeException boom = new IllegalStateException("handler b failed");

    @Test
    @DisplayName("the first failure ends the dispatch and later handlers do not run")
    void theFirstFailureEndsDispatch() {
      bus.subscribe(UserCreated.class, event -> record("a"));
      bus.subscribe(UserCreated.class, event -> {
        record("b");
        throw boom;
      });
      bus.subscribe(UserCreated.class, event -> record("c"));

      assertThatThrownBy(() -> bus.publish(new UserCreated("123")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("handler b failed");

      assertThat(log).containsExactly("a", "b");
    }

    @Test
    @DisplayName("the handler's own exception propagates unchanged, not wrapped")
    void theExceptionIsTheHandlersOwn() {
      bus.subscribe(UserCreated.class, event -> {
        throw boom;
      });

      Throwable thrown = catchThrowable(() -> bus.publish(new UserCreated("123")));

      // Model A implemented by handling nothing: exception identity is the evidence that nothing wrapped,
      // collected or reordered it (ADR-0017).
      assertThat(thrown).isSameAs(boom);
    }

    @Test
    @DisplayName("an Error is not swallowed either")
    void anErrorAlsoEndsTheDispatch() {
      bus.subscribe(UserCreated.class, event -> record("a"));
      bus.subscribe(UserCreated.class, event -> {
        record("b");
        throw new AssertionError("handler b broke");
      });
      bus.subscribe(UserCreated.class, event -> record("c"));

      assertThatThrownBy(() -> bus.publish(new UserCreated("123"))).isInstanceOf(AssertionError.class);

      assertThat(log).containsExactly("a", "b");
    }

    @Test
    @DisplayName("a failing handler does not poison its own subscription")
    void theBusStaysUsableAfterAFailure() {
      AtomicInteger attempts = new AtomicInteger();
      bus.subscribe(UserCreated.class, event -> {
        if (attempts.incrementAndGet() == 1) {
          throw boom;
        }
        record("recovered");
      });

      assertThatThrownBy(() -> bus.publish(new UserCreated("1")));
      bus.publish(new UserCreated("2"));

      assertThat(log).containsExactly("recovered");
    }

    @Test
    @DisplayName("a failure raised in a nested publish reaches the original publisher")
    void aNestedFailureReachesTheOuterPublisher() {
      EventBus inner = EventBus.create();
      inner.subscribe(UserDeleted.class, event -> {
        throw boom;
      });
      inner.subscribe(UserCreated.class, event -> inner.publish(new UserDeleted("1")));

      // Synchronous dispatch means the caller's stack is the failure's path, all the way through nesting.
      assertThatThrownBy(() -> inner.publish(new UserCreated("1"))).isSameAs(boom);
    }
  }

  @Nested
  @DisplayName("null arguments")
  class Nulls {

    @Test
    @DisplayName("a null event fails and delivers nothing")
    void aNullEventIsRefused() {
      bus.subscribe(UserCreated.class, event -> record("created"));

      assertThatThrownBy(() -> bus.publish(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("no handler this could be delivered to");

      assertThat(log).isEmpty();
    }

    @Test
    @DisplayName("a null event type fails")
    void aNullTypeIsRefused() {
      assertThatThrownBy(() -> bus.subscribe(null, event -> record("never")))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("a null type would be a handler for everything");

      assertThat(log).isEmpty();
    }

    @Test
    @DisplayName("a null handler fails, naming the type it was offered for")
    void aNullHandlerIsRefused() {
      assertThatThrownBy(() -> bus.subscribe(UserCreated.class, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("UserCreated");

      assertThat(log).isEmpty();
    }
  }

  @Nested
  @DisplayName("subscription lifecycle")
  class SubscriptionLifecycle {

    @Test
    @DisplayName("after cancel a later publish does not invoke the handler")
    void cancelStopsFutureDispatch() {
      Subscription subscription = bus.subscribe(UserCreated.class, event -> record("created"));
      bus.publish(new UserCreated("1"));

      subscription.cancel();
      bus.publish(new UserCreated("2"));

      assertThat(log).containsExactly("created");
    }

    @Test
    @DisplayName("cancel is idempotent")
    void cancelTwiceIsStillOneCancellation() {
      Subscription subscription = bus.subscribe(UserCreated.class, event -> record("created"));

      subscription.cancel();
      subscription.cancel();

      bus.publish(new UserCreated("1"));
      assertThat(log).isEmpty();
    }

    @Test
    @DisplayName("cancelling one handle leaves the others and their order intact")
    void cancellingOneLeavesTheRest() {
      bus.subscribe(UserCreated.class, event -> record("a"));
      Subscription middle = bus.subscribe(UserCreated.class, event -> record("b"));
      bus.subscribe(UserCreated.class, event -> record("c"));

      middle.cancel();
      bus.publish(new UserCreated("1"));

      assertThat(log).containsExactly("a", "c");
    }

    @Test
    @DisplayName("two handles for the same handler object cancel independently")
    void handlesAreIdentityBased() {
      EventHandler<UserCreated> shared = event -> record("shared");
      bus.subscribe(UserCreated.class, shared);
      Subscription second = bus.subscribe(UserCreated.class, shared);

      second.cancel();
      bus.publish(new UserCreated("1"));

      // Removing "the first registration of this handler" would have emptied the type; identity is what makes
      // one handle mean one subscription.
      assertThat(log).containsExactly("shared");
    }

    @Test
    @DisplayName("a handler cancelled during a dispatch still finishes that dispatch")
    void cancelDoesNotInterruptTheCurrentDispatch() {
      AtomicReference<Subscription> second = new AtomicReference<>();
      bus.subscribe(UserCreated.class, event -> {
        record("a");
        second.get().cancel();
      });
      second.set(bus.subscribe(UserCreated.class, event -> record("b")));
      bus.subscribe(UserCreated.class, event -> record("c"));

      bus.publish(new UserCreated("1"));
      assertThat(log).containsExactly("a", "b", "c");

      log.clear();
      bus.publish(new UserCreated("2"));
      assertThat(log).as("the next publish walks a snapshot without b").containsExactly("a", "c");
    }

    @Test
    @DisplayName("a subscription made during a dispatch applies to the next publish")
    void aSubscriptionMadeMidDispatchAppliesToTheNextPublish() {
      bus.subscribe(UserCreated.class, event -> {
        record("a");
        bus.subscribe(UserCreated.class, other -> record("late"));
      });

      bus.publish(new UserCreated("1"));
      assertThat(log).containsExactly("a");

      log.clear();
      bus.publish(new UserCreated("2"));
      assertThat(log).containsExactly("a", "late");
    }

    @Test
    @DisplayName("each subscription is invoked at most once per publish")
    void deliveryIsAtMostOncePerPublish() {
      AtomicInteger count = new AtomicInteger();
      bus.subscribe(UserCreated.class, event -> count.incrementAndGet());

      bus.publish(new UserCreated("1"));
      bus.publish(new UserCreated("2"));

      assertThat(count.get()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("nested publish")
  class Reentrancy {

    @Test
    @DisplayName("a handler may publish, and the inner publish completes inside the outer one")
    void aHandlerMayPublish() {
      EventBus nested = EventBus.create();
      nested.subscribe(UserDeleted.class, event -> log.add("deleted-handler"));
      nested.subscribe(UserCreated.class, event -> {
        log.add("create-before");
        nested.publish(new UserDeleted("1"));
        log.add("create-after");
      });

      nested.publish(new UserCreated("1"));

      // No queue and no worker to hand the inner publish to, so its handlers are the caller's own stack frames.
      assertThat(log).containsExactly("create-before", "deleted-handler", "create-after");
    }

    @Test
    @DisplayName("the bus keeps no per-thread dispatch state that nesting could disturb")
    void nestingDoesNotSkipTheOuterDispatch() {
      EventBus nested = EventBus.create();
      nested.subscribe(UserCreated.class, event -> nested.publish(new UserDeleted("1")));
      nested.subscribe(UserDeleted.class, event -> log.add("deleted"));
      nested.subscribe(UserCreated.class, event -> log.add("second-created-handler"));

      nested.publish(new UserCreated("1"));

      assertThat(log).containsExactly("deleted", "second-created-handler");
    }

    @Test
    @DisplayName("an event cycle recurses until the stack ends; nothing detects it")
    void thereIsNoCycleDetector() {
      AtomicInteger depth = new AtomicInteger();
      EventBus loop = EventBus.create();
      loop.subscribe(UserCreated.class, event -> {
        depth.incrementAndGet();
        loop.publish(new UserDeleted("1"));
      });
      loop.subscribe(UserDeleted.class, event -> loop.publish(new UserCreated("1")));

      // Chosen in the ADR rather than answered with recursion detection, which would need a queue or a
      // scheduler (ADR-0017). The caller owns the cycle; the stack is what reports it.
      assertThatThrownBy(() -> loop.publish(new UserCreated("1"))).isInstanceOf(StackOverflowError.class);

      assertThat(depth.get()).isGreaterThan(10);
    }
  }

  @Nested
  @DisplayName("concurrency contract")
  class Concurrency {

    private final int threads = 4;
    private final int perThread = 25;

    @Test
    @DisplayName("concurrent publishers deliver every event to every handler exactly once")
    void concurrentPublishesLoseNothingAndDuplicateNothing() throws Exception {
      AtomicInteger first = new AtomicInteger();
      AtomicInteger second = new AtomicInteger();
      bus.subscribe(UserCreated.class, event -> first.incrementAndGet());
      bus.subscribe(UserCreated.class, event -> second.incrementAndGet());
      int published = threads * perThread;

      runConcurrently(() -> {
        for (int j = 0; j < perThread; j++) {
          bus.publish(new UserCreated("x"));
        }
      });

      assertThat(first.get()).isEqualTo(published);
      assertThat(second.get()).isEqualTo(published);
    }

    @Test
    @DisplayName("each publish invokes its own handlers in registration order, even under concurrency")
    void orderingHoldsWithinEachPublish() throws Exception {
      // The promise is per publish, not global: two concurrent publishes may interleave their handler calls, and
      // that is legal (ADR-0017). What may never happen is one publish seeing its own handlers out of order. Since
      // dispatch runs on the publishing thread, the log tagged with its own thread recovers each publish's sequence.
      List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
      bus.subscribe(UserCreated.class, event -> calls.add(Thread.currentThread().getName() + ":a"));
      bus.subscribe(UserCreated.class, event -> calls.add(Thread.currentThread().getName() + ":b"));
      bus.subscribe(UserCreated.class, event -> calls.add(Thread.currentThread().getName() + ":c"));

      runConcurrently(() -> {
        for (int j = 0; j < perThread; j++) {
          bus.publish(new UserCreated("x"));
        }
      });

      assertThat(calls).hasSize(threads * perThread * 3);
      // Each publishing thread's slice is exactly a,b,c repeated perThread times - no thread ever sees b before a.
      for (int i = 0; i < threads; i++) {
        String worker = "event-test-" + i;
        List<String> slice = new ArrayList<>();
        for (int j = 0; j < perThread; j++) {
          slice.add(worker + ":a");
          slice.add(worker + ":b");
          slice.add(worker + ":c");
        }
        assertThat(calls).filteredOn(call -> call.startsWith(worker + ":")).containsExactlyElementsOf(slice);
      }
    }

    @Test
    @DisplayName("subscribing while other threads publish never throws and every subscription lands")
    void subscribingDuringConcurrentPublishingIsSafe() throws Exception {
      AtomicInteger delivered = new AtomicInteger();
      List<Future<?>> futures = new ArrayList<>();
      ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
      CyclicBarrier start = new CyclicBarrier(threads + 1);
      try {
        futures.add(pool.submit(() -> {
          await(start);
          bus.subscribe(UserDeleted.class, event -> delivered.incrementAndGet());
        }));
        for (int i = 0; i < threads; i++) {
          futures.add(pool.submit(() -> {
            await(start);
            bus.publish(new UserDeleted("x"));
          }));
        }
        finish(futures, pool);
      } finally {
        pool.shutdownNow();
      }

      // Whether the racing publishes saw the new subscription depends on which snapshot each walked, which the
      // contract leaves open. What it fixes is delivery after the race settled.
      assertThat(delivered.get()).isBetween(0, threads);
      delivered.set(0);
      bus.publish(new UserDeleted("settled"));
      assertThat(delivered.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("cancelling while other threads publish never throws and takes effect for later publishes")
    void cancellingDuringConcurrentPublishingIsSafe() throws Exception {
      AtomicInteger deliveries = new AtomicInteger();
      Subscription subscription = bus.subscribe(UserCreated.class, event -> deliveries.incrementAndGet());
      List<Future<?>> futures = new ArrayList<>();
      ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
      CyclicBarrier start = new CyclicBarrier(threads + 1);
      try {
        futures.add(pool.submit(() -> {
          await(start);
          subscription.cancel();
        }));
        for (int i = 0; i < threads; i++) {
          futures.add(pool.submit(() -> {
            await(start);
            for (int j = 0; j < perThread; j++) {
              bus.publish(new UserCreated("x"));
            }
          }));
        }
        finish(futures, pool);
      } finally {
        pool.shutdownNow();
      }

      // A publish already walking the old snapshot may still reach the handler, and a cancel that lands first may
      // deliver nothing: both are inside the promise, so only the boundary after it is assertable.
      int afterTheRace = deliveries.get();
      assertThat(afterTheRace).isBetween(0, threads * perThread);
      bus.publish(new UserCreated("after"));
      assertThat(deliveries.get())
          .as("cancel() has returned, so no publish starting after it invokes this handler")
          .isEqualTo(afterTheRace);
    }

    @Test
    @DisplayName("concurrent subscribes of one new type all land in the same bucket")
    void twoThreadsSubscribingTheSameNewTypeBothArrive() throws Exception {
      AtomicInteger seen = new AtomicInteger();
      List<Future<?>> futures = new ArrayList<>();
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      CyclicBarrier start = new CyclicBarrier(threads);
      try {
        for (int i = 0; i < threads; i++) {
          futures.add(pool.submit(() -> {
            await(start);
            bus.subscribe(AdminCreated.class, event -> seen.incrementAndGet());
          }));
        }
        finish(futures, pool);
      } finally {
        pool.shutdownNow();
      }

      bus.publish(new AdminCreated("1", "root"));

      // computeIfAbsent is what makes this deterministic: a racing put would have dropped a bucket and the
      // subscription that came with it.
      assertThat(seen.get()).isEqualTo(threads);
    }

    /** Runs the same body on {@code threads} named threads at once; every task's exception fails the test. */
    private void runConcurrently(Runnable body) throws Exception {
      List<Future<?>> futures = new ArrayList<>();
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      CyclicBarrier start = new CyclicBarrier(threads);
      try {
        for (int i = 0; i < threads; i++) {
          int index = i;
          futures.add(pool.submit(() -> {
            // Named by the test rather than by the pool, so the ordering assertion above can address each slice.
            Thread.currentThread().setName("event-test-" + index);
            await(start);
            body.run();
          }));
        }
        finish(futures, pool);
      } finally {
        pool.shutdownNow();
      }
    }

    /** Every task completes without an exception, or the test fails with the task's own cause. */
    private void finish(List<Future<?>> futures, ExecutorService pool) throws Exception {
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("the race finished").isTrue();
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    }

    private static void await(CyclicBarrier barrier) {
      try {
        barrier.await(30, TimeUnit.SECONDS);
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }
  }
}
