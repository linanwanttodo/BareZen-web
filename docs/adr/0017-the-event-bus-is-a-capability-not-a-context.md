# ADR-0017: the event bus is a capability, not a context

Status: accepted
Date: 2026-09-29
Decides: what an event is, how the bus dispatches, what a handler may be given, who owns a subscription, and what
core still refuses to build
Related: ADR-0003, ADR-0004, ADR-0005, ADR-0006, ADR-0013, ADR-0014, ADR-0015, ADR-0016

## Context

Phase 2.6 adds the first horizontal capability to core, and the phase is not really about events. ADR-0016 removed
`PluginContext` on the grounds that a plugin's dependencies arrive through a constructor, and a reviewer was entitled
to ask whether that was a design or an evasion: it is easy to say "no context needed" while nothing has ever needed
one. An event bus is the first feature that a real framework would plausibly want to hand out through a lookup, so it
settles the question either way.

The second reason for the phase is that "small" is not a property you can decide after the fact. Every event system in
the ecosystem began as `subscribe` and `publish` and grew an executor, an annotation, an ordering rule, a dead-letter
queue and a `BeanFactory`, because each of those looked like one small addition to a design that had no written
semantics. So the decisions below are stated as contracts before the implementation is described, and the tests assert
the contracts.

The phase question was: **what does an event bus have to be, and what may it never be handed?**

## Decision

`com.barezen.core.event` contains three public types and one package-private implementation:

```java
public interface EventBus {
  <E> Subscription subscribe(Class<E> eventType, EventHandler<E> handler);
  void publish(Object event);
  static EventBus create() { return new SynchronousEventBus(); }
}

@FunctionalInterface
public interface EventHandler<E> {
  void handle(E event);
}

public interface Subscription {
  void cancel();
}
```

Five sentences that the rest of this record exists to justify:

- **Events are explicit.** There is no `Event` interface; any object is an event.
- **Dispatch semantics are explicit.** Synchronous, exact type, registration order, first failure wins.
- **Lifecycle remains outside EventBus.** No participant, no `close()`, no executor.
- **Assembly remains in the application.** The application creates the bus and hands it out through constructors.
- **No universal extension mechanism exists until a real consumer requires one.** A bus is not an `ExtensionRegistry`.

### 1. There is no Event interface

`public interface Event {}` was evaluated and refused. A marker with no method carries no runtime behaviour, so it
cannot change how the bus dispatches, and it costs every application the obligation to implement it before it can
publish anything. `record UserCreated(String userId)` is already a valid event.

The hierarchy the phase brief names — `Event`, `BaseEvent`, `DomainEvent`, `ApplicationEvent`, `FrameworkEvent` — is
refused for the same reason in one move: each level would exist to be *matched on*, which is inheritance-based routing
(§4), and each level would also become a place to put fields that "every event should have": timestamps, ids, causes,
tenants. Those are envelope decisions, they belong to the components that need them, and an abstract base class is how
a framework starts owning application data.

`publish(Object event)` is therefore the contract, and the argument's class is the routing key. The cost is a small
amount of type freedom at the call site; the gain is that core has no opinion about what an event is.

### 2. Two operations, plus one handle

| Member | Why it exists now |
| --- | --- |
| `subscribe(Class, EventHandler)` | a plugin and a service must be able to say who handles what; without it there is no bus |
| `publish(Object)` | a component must be able to state that something happened without naming its audience |
| `create()` | the application needs a way to obtain a bus that does not name an implementation class it may later replace |
| `Subscription.cancel()` | Phase 2.5's ownership rule requires that a component end its own subscriptions when it stops |

Refused for having no consumer: `unsubscribe(type, handler)` (the handle is that operation, and a by-type variant
cannot say which of two identical registrations to end), `clear()`, `subscribers()`, `subscriberCount()`,
`publishAll()`, `post(Object)` (an alias for `publish` with async intent attached), `register(Object subscriber)`
(a reflective method-name scanner, which is ADR-0014's refusal re-imported under the name of an annotation-free API).

`create()` is a static factory returning the interface rather than `new SynchronousEventBus()` at the call site because
the implementation's name is not application vocabulary; it is not a registry, it holds no state, and it resolves
nothing. It has no parameters, because there is nothing to decide: a bus with an `Executor` argument would be an async
bus with a synchronous signature (§3, §12).

### 3. Dispatch is synchronous

`publish(event)` invokes every live handler for that event and then returns. No executor, no queue, no worker thread.

Three consequences, all desirable. The caller sees a handler's exception in its own stack (§6). The caller sees the
handler's cost, so a slow handler is visible as a slow call rather than as a latency mystery downstream. And
"when does the event get delivered" has one answer, which means the ordering question in §5 has something to be about.

The tempting alternative was to use the `ThreadProvider` that already exists in `concurrent`, on the reasoning that
the framework has threads so the bus should have one. It does not follow: `ThreadProvider` exists because the
*lifecycle engine* needs threads to run phases on, and a bus that took one would need a policy, a shutdown, an
ownership decision and a bounded-queue answer before it had a single consumer that needed any of them.

### 4. Type matching is exact

`subscribe(UserCreated.class, handler)` fires when `event.getClass() == UserCreated.class`, and otherwise does not.

The alternatives were assignable matching (walk supertypes and interfaces) and a declared hierarchy. Both need
multi-bucket fan-out, which means one publish reaches several lists and ordering becomes a question about lists rather
than about registrations; both make `publish` cost more than one lookup; both make the generic cast in the dispatch loop
unsound in a way that needs either a suppression per bucket or a runtime `isInstance` guard; and both invite
`subscribe(ApplicationEvent.class, ...)` in the core of an application that publishes nothing of that type yet.

So a supertype handler receives only its own exact type. That is observable in the test named
`matchingIsExactNotAssignable`: subscribing `Object.class` and `Marked.class` and publishing an `AdminCreated` delivers
to the `AdminCreated` handler only. The cost is real and recorded in Consequences: an application that wants "every
event" must subscribe per type. An interface route works like any other class route, so a component that *wants*
hierarchy can define an interface and implement it — the mechanism is the application's, not the bus's.

### 5. Registration order is invocation order, per event type

First registered, first invoked. `CopyOnWriteArrayList` is the implementation because it preserves insertion order,
which is the property being promised, and not because it is concurrent-safe as a side effect.

This is an explicit *order*, and the brief's warning applies: a design that says "no order guaranteed" must not have a
test that relies on `ArrayList` order. The alternative was refused on those grounds — a contract of "unordered" is
weaker than the contract this implementation already provides for free, and it would have forced the ordering test to
become a set assertion, which proves less about the code.

Ordering is per event type. Cross-type ordering is not a promise, because one publish never waits for another, so two
concurrent publishes can interleave their handler calls; that is the concurrency contract (§7), not an exception to it.

### 6. The first handler failure ends the dispatch

Model A, chosen over collect-and-continue:

```
A throws  ->  publish throws A's exception  ->  B is not invoked
```

The exception propagates **unchanged**: not wrapped, not logged-and-carried-on, not aggregated. There is no `catch`
block in the dispatch path at all, which is the only version of "an exception is never silently lost" that can be
checked by reading the method.

Model B — attempt every handler, then throw an aggregate — was considered and is the more useful system for a bus that
carries independent side effects (audit write, cache invalidation, webhook). It was refused here because it needs four
things that do not exist yet: an aggregate exception type and its `getSuppressed` contract, a rule about whether a
handler after a failure may still mutate shared state, a `ShutdownReport`-shaped answer for callers who do not want to
inspect a list, and a decision about whether an `Error` is collectable at all. ADR-0004 already chose the same
direction for startup: the original failure wins. Choosing aggregation *here* and rollback-there would leave the
framework with two failure philosophies, which is a worse teaching problem than a missing feature.

Publishing with no subscriber is not a failure. An event is a published fact; making it an error would require every
publisher to know which plugins are present, which is the coupling §9 refuses to create.

`publish(null)`, `subscribe(null, handler)` and `subscribe(type, null)` all fail with a `NullPointerException` whose
message names the decision being refused (a null type would be a handler for everything; a null event has no routing
key). Null is refused rather than ignored because an ignored null event is a dropped event with no evidence.

### 7. Concurrency is a contract, not a container

The promise, per operation:

| Operation | Guarantee |
| --- | --- |
| `subscribe` | thread safe; returns a handle no other thread can obtain |
| `publish` | thread safe; many threads may publish at once, and each publish invokes its own handlers in order on its own thread |
| `cancel` | thread safe, idempotent; after it returns, no publish that *starts* afterwards invokes the handler |
| handler call | at most once per subscription per publish; never interrupted |

Two threads publishing concurrently may interleave their handler invocations with each other. That is allowed and is
not a weakening of §5, because §5's order is a property of one publish.

The rule about how to get there: **semantics first, then containers.** `ConcurrentHashMap` is used to route by type
where an order cannot escape (one publish reads one bucket) and `computeIfAbsent` where two threads subscribing the
same new type must land in one list. `CopyOnWriteArrayList` is used for insertion order and for a snapshot iterator,
so a publish in progress is never disturbed by a concurrent subscribe or cancel and never throws
`ConcurrentModificationException`. Neither is cited as the reason the contract holds: the cancellation boundary comes
from `remove` taking effect on later snapshots, and the failure contract comes from the absence of a `catch`. A
`Collections.synchronizedList` would have satisfied the safety claim and broken the iteration property, which is the
combination that a bare "it's concurrent, so it's correct" argument misses.

Cancellation deliberately does not interrupt. A handler already entered runs to completion; the promise is only about
future dispatch. Interrupting a caller's handler would mean the bus owns threads it does not own, and after §3 there
are none to own.

Nested publish is allowed (§8 below) and is exactly what the snapshot iterator makes safe: the inner publish walks the
list as it is now, and a subscription made during it applies to the next publish.

### 8. Reentrancy is allowed, and cycles are the caller's problem

A handler may call `publish`. The inner dispatch completes inside the outer one.

There is no event queue, no recursion guard and no cycle detector. An `A -> B -> A` pair of handlers recurses until the
stack ends with `StackOverflowError`, and the test asserts that is what happens rather than pretending otherwise.
Detecting a cycle would require either a per-thread in-flight set (state the bus does not have and a nested publish
would have to reason about) or a queue, and a queue is the async design arriving sideways. The bug is in the handler
pair; the stack trace names it.

If a later phase changes this decision it needs a new record, because "publish may or may not return before your
handler runs" is a contract change, not an implementation change.

### 9. Lifecycle stays outside the bus

`EventBus` is not a `LifecycleParticipant` and is not `AutoCloseable`.

The bus owns no thread, no socket, no file, no queue. A `stop()` on it would have nothing to do, and a `close()` would
be worse than useless: it would invite an application to close the bus in its shutdown sequence, which makes the
bus's subscription set an owned resource whose release order matters, which puts a capability inside the
application's lifecycle — exactly what ADR-0015 assigned to participants. Subscriptions end when their owners cancel
them, and only they know when.

A plugin therefore does the three-step shape Phase 2.5 predicted:

```
Plugin.initialize()  ->  subscribe, keep the handles
Plugin.stop()        ->  subscription.cancel()
```

The rejected alternative was the bus tracking subscriber identity so it could clean up "when a plugin stops". That is
`EventBus -> Plugin`, and it is the same edge ADR-0016 built a rule to prevent in the opposite direction. A bus that
knows who subscribed is one method away from knowing who they are, and one more from deciding what to do about it.

### 10. Ownership follows creation, unchanged

| Object | Created by | Owned (released) by | Used by |
| --- | --- | --- | --- |
| `EventBus` | the application's assembly code | whichever of `instance(...)` / `factory(...)` the application chose (ADR-0015) | plugins and services |
| `Subscription` handle | `subscribe` | the subscriber, which holds it and cancels it | — |
| event instances | the publisher | nobody; the bus does not store events | handlers, during the call |

`instance(EventBus.class, bus)` leaves the bus with the application; `factory(EventBus.class, s -> EventBus.create())`
leaves it with the assembly (which will not close it, because it is not closeable, and that is a consequence of §9
rather than a loophole). The bus never chooses its own owner.

### 11. Why the bus needs no context, and why there is still no ExtensionRegistry

`PluginContext` had no legal member in ADR-0016. This phase supplies the empirical half of that claim: the bus is a
component plugins genuinely need, and it reached a plugin through a constructor parameter, with nothing added to the
plugin SPI and no change to the lifecycle engine. `new AuthPlugin(eventBus)` needs no lookup.

The tempting reflex this phase had to resist is the one the brief names: an event bus looks like a general extension
mechanism (`Event` is the extension point, `handler` is the contribution), so a team that has just built one builds
`ExtensionPoint<EventHandler<?>>`, `ExtensionRegistry`, `Contribution`. None of them is needed here, and each would
invent semantics with no owner to answer for them: the bus's rules (exact type, registration order, first failure
wins) are already stated, and stated *by the bus*. A generic registry would have to ask each extension point for the
same three answers, and there is no second extension point yet to ask.

So the rule stands as written in ADR-0016: an extension mechanism ships with the first component that owns an
extension point, where the owner can state its own rules. A `Router` will want unique paths and duplicate rejection; a
security chain will want order and a defined terminus. Neither of those is an event-bus rule, and a `Bus`-shaped
abstraction that pretends otherwise is how a framework ends with one registry whose semantics are nobody's.

### 12. Async, distributed and persistent events are a redesign, not a flag

Explicitly out of this contract: async publish, executors, queues, backpressure, retry, persistence, replay, dead
letters, distributed events, ordering guarantees across threads or processes, and event envelopes with ids or
timestamps.

The rule for a future phase: an asynchronous event model arrives as **a different type with its own contract**,
chosen by the application, not as a mode of `EventBus`. `publish(Object)` returning before handlers have run would
change what the method name means for every existing caller, and a boolean or an `Executor` parameter would make
correctness depend on a flag. If a replay or durability requirement appears, it is an event *log* — a component with
an owner, a retention policy and a storage dependency — and that is a new ADR with new dependencies, not an
implementation detail of this one.

## Consequences

- The plugin SPI is unchanged: `Plugin` still declares exactly `descriptor()`, and ADR-0016's "no context" is now
  backed by a real capability rather than by the absence of demand.
- Three public types, one package-private implementation, and one dependency edge that is the empty set: `event`
  depends on no core package at all, and nothing in core depends on `event`. It is a leaf capability that an
  application may or may not wire.
- A handler cannot express "run after that one" except by registering later, and cannot express "run in parallel".
  Both are consequences of the MVP contract, and both are what the §12 rule about a redesign is holding open.
- There is no wildcard subscription. An application that needs "every event" subscribes per type it publishes; if that
  turns out to be a real burden, the honest fix is a bounded, explicitly enumerated fan-out in a later record, not a
  hierarchy walk here.
- A handler's exception reaching `publish` means an event was partially processed: earlier handlers ran, later ones did
  not, and nothing in the bus records which. An application that cannot tolerate that needs either a transaction
  boundary it owns or the async/durable model in §12 — the bus is not a substitute for either.
- `Subscription` must be kept by the subscriber; a dropped handle is an uncancellable subscription. The API could
  have hidden this by requiring a key, and it does not, because a key is how a registry starts.
- Thread safety is at the bus level, not the handler level: a handler shared between concurrent publishes must be
  safe under concurrency, like any object the application wires once. The bus holds no lock around a handler call and
  will not add one.
- Six ArchUnit rules carry the record. `the_event_bus_depends_on_nothing_in_core` forbids the seven other core
  packages in one sentence; `no_core_type_depends_on_the_event_bus` keeps it a leaf;
  `the_event_bus_owns_no_execution_machinery` forbids `Executor`/`Thread`/`Queue`/`Future`/`Timer`/`Scheduler`/
  `Semaphore` from `java.util.concurrent` while leaving the routing collections legal — the one rule here that guards
  a JDK import, because async is the drift that cannot be caught by BareZen package rules;
  `the_assembler_and_the_event_bus_do_not_scan_or_reflect` is Phase 2.3's no-reflection rule widened to `event`, which
  is where `@Subscribe`-style handler discovery would have arrived; the layered rule seals `Event` in both directions;
  and the pre-existing `nobody_exposes_a_type_lookup` covers `eventBus.get(Class)` without this record having to name
  it.
- Each rule was fired against a deliberately violating class and the class was deleted: a `ProbeContext` holding
  `ServiceAssembly`, `Configuration` and `Plugin` fired four rules at once (the standalone-capability rule, the
  DI-leaf rule inherited from Phase 2.3, the plugin-leaf rule inherited from Phase 2.5, and the layered rule); a
  `ProbeAsyncBus` with an `ExecutorService` field and a `LinkedBlockingQueue` fired the execution-machinery rule and
  the layered rule; a `ProbeRegistry` in `kernel` holding an `EventBus` fired the bus-leaf rule and the layered rule;
  a `ProbeScanner` using `getMethods` and `ServiceLoader.load` fired the widened scan rule; a public
  `Object get(Class<?>)` fired `nobody_exposes_a_type_lookup`. Five probes, every new rule seen failing at least once.
- No third-party dependency: still `slf4j-api` alone in compile scope, and the bus does not even use that — the
  implementation is three JDK types and no logging, because a synchronous call stack carries its own trace.
