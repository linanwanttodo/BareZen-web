# ADR-0024: Server Lifecycle and Executor Ownership — start, stop, close, restart, executor, ThreadProvider, ownership

Status: accepted
Date: 2026-09-29
Decides: the adapter's state machine and what guards it; why the adapter is not a `LifecycleParticipant`; who creates a
`ThreadProvider` and who owns it; how the adapter obtains its executor and what `stop()` releases; why there is no
restart; what `stop(3)` means and why it is bounded; what happens when a bind fails; and why the lifecycle vocabulary is
closed
Related: ADR-0002, ADR-0003, ADR-0005, ADR-0006, ADR-0008, ADR-0009, ADR-0011, ADR-0012, ADR-0013, ADR-0015, ADR-0016,
ADR-0017, ADR-0018, ADR-0019, ADR-0020, ADR-0021

## Context

Phase 2.9 shipped an adapter that binds a port and serves requests, and the adapter is the first component in the
framework that owns a socket, a thread pool and a lifecycle. The question is not whether the adapter starts and stops —
every server does — but *who* starts it, *who* owns the threads it runs on, *what* happens when a bind fails, and
*whether* a second start after a stop is a restart or a bug. The pressure to answer these questions loosely comes from
the right direction: an application's assembly code has to wire the adapter into a `LifecycleOrchestrator`, and the
orchestrator owns the application's startup and shutdown order; the adapter owns an HTTP runtime. They overlap on one
verb and diverge on everything else, and the boundary has to be stated in a way that survives a Jetty adapter, a
borrowed thread provider, a bind failure and a developer who wants to call `restart()`.

Three earlier decisions shape everything below. ADR-0003 makes startup a two-phase affair — initialize all, then start
all — and the adapter's `start()` is the second phase, not the first. ADR-0011 makes `ThreadProvider` a factory with
ownership, not a registry: executors handed out are owned by the provider, and `close()` closes exactly those executors
this provider created. ADR-0012 makes `NEW → STOPPED` legal and idempotent, which is the rule that lets a shutdown path
meet an adapter that never started and not fail for the sake of a state diagram.

The phase question was: **what is the adapter's lifecycle, who owns what, and what must it refuse to become?**

## The question, in parts

1. What is the state machine, and what guards it? (§1)
2. Why is the adapter not a `LifecycleParticipant`? (§2)
3. Who creates a `ThreadProvider`, and who owns it? (§3)
4. How does the adapter obtain its executor, and what does `stop()` release? (§4)
5. Why is there no restart? (§5)
6. What does `stop(3)` mean, and why is it bounded? (§6)
7. What happens when a bind fails? (§7)
8. What does a failed start actually release, and what does the JDK refuse to release? (§8)

## Decision

The adapter's lifecycle is `NEW → RUNNING → STOPPED`, guarded by one `lifecycle` monitor object, with `state` and
`server` as the module's only non-final instance fields. The adapter is not a `LifecycleParticipant`; the application's
assembly writes a participant of its own that calls `start()` and `stop()`. Whoever creates a `ThreadProvider` owns it:
an adapter that built its own provider closes it on `stop()`; an adapter that was handed a provider leaves it running.
The adapter obtains its executor by `threads.newUnboundedExecutor("barezen-http-jdk")` and never constructs
`Executors`, `ThreadPoolExecutor`, `ForkJoinPool` or `Thread` itself. A second `start()` throws
`IllegalStateException` naming the state and telling the caller to build a new adapter; `start → stop → start` fails
for the same reason. `stop(3)` gives in-flight exchanges three seconds, which is the JDK's own wait rather than a
`Thread.sleep`; `stop()` is idempotent, never throws, and `close()` is exactly `stop()`. A bind failure wraps the
`IOException` in `UncheckedIOException`, marks the adapter `STOPPED` (spent), and closes an owned provider.

### 1. The state machine, and what guards it

The adapter's state is one of three values — `NEW`, `RUNNING`, `STOPPED` — stored in a private enum `State` and guarded
by one `lifecycle` monitor object. `state` and `server` are the module's only non-final instance fields, asserted by
`BarezenHttpJdkArchitectureTest.the_mutable_state_is_the_lifecycle_and_nothing_else`: the rule checks that every
non-final field in the module is private, belongs to `JdkHttpServerAdapter`, and is named `state` or `server`. A
non-final field on the dispatcher or the bridge would be a request's value surviving into the next request, which is
the failure ADR-0019 §13 refuses for the router and ADR-0021 §1 refuses for the adapter.

`JdkHttpServerLifecycleTest.Transitions` runs the lifecycle matrix against a real bound port rather than a mock. Each
transition is asserted three ways where it can be: the state the accessors report, whether the port actually answers,
and whether the object still holds the resource. `isRunning()` returning false while the socket still accepts
connections would be a worse bug than a wrong boolean, so the socket is checked. `aNewAdapterClaimsNothingAndBindsNothing`
asserts the `NEW` state: `isRunning()` is false, `port()` is empty, and `toString()` contains `NEW`.
`startBindsReportsAndServes` asserts the `RUNNING` state: `isRunning()` is true, `port()` reports a positive number,
and a request through the socket answers. `stopIsIdempotentAndNeverThrows` asserts the `STOPPED` state: `isRunning()`
is false, `port()` is empty, and a second `stop()` does nothing. `stoppingAnAdapterThatNeverStartedIsLegal` asserts
`NEW → STOPPED` is legal and idempotent, which is ADR-0012's rule applied here: a shutdown path that meets an adapter
that never got as far as binding has nothing to undo and must not fail for the sake of a state diagram.

The reflection assertion in `JdkHttpServerLifecycleTest.NotAParticipant` pins the contract's surface:
`HttpServerAdapter` extends only `AutoCloseable`, and its declared methods are exactly `close`, `isRunning`, `port`,
`start` and `stop`. No `initialize`, no `abort`, no `shutdown`, no order — the contract type names no lifecycle verb
at all, which is the whole point of the seam.

### 2. Why the adapter is not a `LifecycleParticipant`

`LifecycleOrchestrator` owns the application's startup and shutdown order; `HttpServerAdapter` owns an HTTP runtime.
They overlap on one verb — `start()` — and diverge on everything else. A `LifecycleParticipant` has `initialize()`,
`abort()` and a registration order the orchestrator controls; an adapter has a bound port, a thread pool and a request
path. So `HttpServerAdapter` does not extend the core lifecycle SPI, and `JdkHttpServerAdapter` does not implement it:
the application assembly decides *when* to start the server and registers a participant of its own that calls
`start()` and `stop()`.

`BarezenHttpJdkArchitectureTest.an_adapter_is_not_a_lifecycle_participant` enforces the rule: any class assignable to
`HttpServerAdapter` must not be assignable to `LifecycleParticipant`. The rule is written over both modules at once,
from the one test classpath that holds all three, because the contract side of the same decision — `HttpServerAdapter`
extends nothing but `AutoCloseable` — cannot be expressed in `BarezenWebArchitectureTest`, which has no core type on
its classpath.

`JdkHttpServerLifecycleTest.ThroughTheAssembly` proves the integration works. The test defines a private
`ServerParticipant` that implements `LifecycleParticipant`, holds an `HttpServerAdapter` (the contract type, not
`JdkHttpServerAdapter`), and calls `start()` and `stop()` on it. The participant is registered with a
`LifecycleOrchestrator`, which starts and stops it in registration order. The test asserts that the orchestrator starts
the server, the server serves requests, and the orchestrator stops the server — and that the same participant would
drive a Jetty adapter unchanged, because the participant names the contract, not the implementation. The reason the
test lives in this module rather than in `barezen-web` is structural: the contract has no dependency on core, so an
assembly-shaped test cannot live there.

The alternative — making the adapter a participant — would have let the orchestrator restart a server on its own
schedule, which is the failure the rule exists to prevent. An adapter owns a port; the orchestrator owns the
application's phases. Joining them by inheritance would have made the adapter's lifecycle a function of the
orchestrator's registration order, and a restart would have been a second `start()` call from a participant that had
already stopped — which is the bug §5 refuses.

### 3. Who creates it, who owns it (谁创建，谁拥有)

The rule is stated as a proverb rather than a policy: *whoever created it, owns it*. An adapter that was constructed
with `new JdkHttpServerAdapter(address, router)` builds its own `ThreadProvider` with `ThreadPolicy.AUTO` and closes it
on `stop()`. An adapter that was constructed with `new JdkHttpServerAdapter(address, router, threads)` was handed a
provider and never closes it, because the provider has other work running on it.

`JdkHttpServerAdapter` stores the decision in a private final field `ownsThreadProvider`, set by the private
four-argument constructor. The default constructor passes `true`; the three-argument constructor passes `false`. The
field is read by `start()`'s catch block and by `stop()`: if the adapter built the provider, it closes it; if it was
handed one, it leaves it alone. The rule is the same one ADR-0011 states for `ThreadProvider` itself: executors handed
out are owned by the provider, and a provider is scoped per subsystem that wants ownership — one for the HTTP runtime,
one for the event bus, and so on — rather than shared as a process-wide singleton.

`JdkHttpServerLifecycleTest.ExecutorOwnership` asserts the rule five ways. `anAdapterThatCreatedItsProviderClosesItOnStop`
captures the log output of `VirtualThreadProvider` and `PlatformThreadProvider` and asserts that exactly one provider
logged "closing 1 executors created by thread provider" after `stop()`. `aBorrowedProviderSurvivesStopAndIsStillUsable`
asserts the other half: a provider handed to the adapter is not closed, and the borrower can still call
`newUnboundedExecutor("still-here")` afterwards and submit work. `anAdapterThatFailedToStartReleasesTheProviderItCreated`
asserts that a bind failure closes the provider the adapter built, so a failed startup does not leave a thread pool
behind. `aBorrowedProviderThatFailedToBindIsLeftAloneToo` asserts that a bind failure leaves a borrowed provider alone,
so the borrower can still use it after the adapter's `start()` threw.
`aStartThatFailedAfterBindingSpendsTheAdapterAndClosesNothingItDidNotCreate` asserts the same rule on the other failure
side — the bind worked and the provider refused — where the adapter closes nothing it did not create (§8).

### 4. The executor: obtained from the provider, never constructed here

The adapter obtains its executor by `threads.newUnboundedExecutor("barezen-http-jdk")` inside `start()`, after the
`HttpServer` is created and before it is started. The executor is handed to the JDK server by `created.setExecutor(...)`,
and the JDK server dispatches every request to it. The adapter never constructs `Executors`, `ThreadPoolExecutor`,
`ForkJoinPool`, `ScheduledThreadPoolExecutor` or `Thread` itself, asserted by
`BarezenHttpJdkArchitectureTest.the_adapter_creates_no_thread_of_its_own`. Every executor this server runs on comes
from a `ThreadProvider` — created here when the adapter owns one, borrowed otherwise — and `Executors`,
`ThreadPoolExecutor`, `ForkJoinPool` and a bare `java.lang.Thread` are each a thread that `stop()` would not know
about, which is the exact failure the ownership rule exists to prevent.

An adapter-owned provider is closed by `stop()`, and the release is observable through core's DEBUG log "closing N
executors created by thread provider '…'" — `anAdapterThatCreatedItsProviderClosesItOnStop` captures that log line and
asserts it. A borrowed provider survives `stop()` and can still create a working executor, asserted by
`aBorrowedProviderSurvivesStopAndIsStillUsable`, which is the guard that mutation probe J would catch: changing
`if (ownsThreadProvider)` to `if (true)` makes that test fail, because the adapter closes a provider it did not create,
and the borrower's executor is gone.

A failed `start()` releases an owned provider and leaves a borrowed one usable. The catch block in `start()` calls
`threads.close()` if `ownsThreadProvider` is true, so a startup that could not bind does not leave a thread pool
behind. `anAdapterThatFailedToStartReleasesTheProviderItCreated` asserts the release by capturing the log;
`aBorrowedProviderThatFailedToBindIsLeftAloneToo` asserts the other half by submitting work to the borrowed provider
after the competitor's `start()` threw.

The thread model requests actually run on is asserted by `JdkHttpServerLifecycleTest.ThreadModel`.
`theDefaultProviderRunsRequestsOnTheRuntimeBestThreads` captures the thread that served a request and asserts it is a
virtual thread on JDK 21 or a platform thread with the prefix `barezen-http-jdk-` on JDK 17 — the capability rule from
ADR-0002, running under a real socket rather than in a unit test. `anExplicitlyBorrowedPlatformProviderIsHonouredOnJavaTwentyOne`
asserts that a borrowed platform provider is honoured even on JDK 21, so `AUTO` never overrides an explicit choice.
`aStoppedAdapterLeavesNoRequestThreadBehind` counts the live threads with the prefix and asserts the count is zero
after `stop()`, so the provider the adapter created was closed and the executors created from it terminated.

### 5. No restart: a second start throws, and the reason is the port

A second `start()` throws `IllegalStateException` naming the state and telling the caller to build a new adapter. The
message reads: "this adapter is RUNNING and cannot be started again. One instance is one server lifecycle: a restarted
server may hold a different ephemeral port, so the caller's reference would name a server that no longer exists. Build
a new adapter to restart (ADR-0024 §5)". `start → stop → start` fails for the same reason: the adapter is `STOPPED`,
and the message reads "this adapter is STOPPED and cannot be started again".

The reason is the port. An adapter that bound port 0 was given an ephemeral port by the operating system, and a
restarted server may be bound to a different one. An application holding this object would be holding the address of a
server that no longer exists — a stale reference that names a port the kernel will not give back, or that another
process has already taken. The refusal is not a partial restart: `aSecondStartFailsAndTheRunningServerKeepsServing`
asserts that the first server is untouched, `isRunning()` stays true, the port stays the same, and the next request
still answers. Probe G guards this: relaxing the start guard to `state == State.STOPPED` lets that test fail, because
the running server would be replaced with a different instance on a possibly different port.

`startAfterStopFailsBecauseTheInstanceIsSpent` asserts the other half: after `start → stop`, a second `start()` throws
`IllegalStateException` containing "STOPPED", and the port is gone with it. Probe H guards this: relaxing the guard to
`state == State.RUNNING` lets that test fail, because the spent instance would silently bind a new server the caller's
reference does not name. A restart is a new adapter, built by the code that built this one, and
`no_method_offers_a_second_lifecycle_shape` refuses `restart`, `shutdown`, `shutdownNow`, `awaitTermination`,
`startAsync`, `stopAsync`, `server`, `destroy`, `close`, `getServer` and `httpServer` by name — the vocabulary of a
thread-pool facade, not an adapter.

### 6. `stop(3)`: the JDK's own wait, bounded rather than infinite

`stop()` calls `server.stop(STOP_GRACE_SECONDS)` with `STOP_GRACE_SECONDS = 3`, which is the JDK's own in-flight wait:
the JDK blocks up to N seconds for exchanges still writing, then abandons them. It is not a `Thread.sleep` added here;
it is the JDK's own mechanism for giving a handler that is mid-response a chance to finish. The value is chosen rather
than configured: `0` would abandon a request that was mid-handler, and a larger number would let one stuck handler
dominate a shutdown that is supposed to be best effort (ADR-0005). The framework has no shutdown-timeout configuration
yet, and this record records the constant as the value to replace when it does rather than leaving the choice buried in
a call.

`stop()` is idempotent and never throws. A second call does nothing, and a partially stopped server is still stopped.
`stopIsIdempotentAndNeverThrows` asserts that three calls in a row do not throw, and `stoppingAnAdapterThatNeverStartedIsLegal`
asserts that `NEW → STOPPED` is legal. `close()` is exactly `stop()`, declared so an adapter fits try-with-resources:
`closeIsStopAndFitsTryWithResources` asserts that a try-with-resources block stops the server, and a second `close()`
through `stop()` stays safe. After `stop()` the port refuses connections: `stopReleasesThePortForANewAdapter` asserts
that a new adapter can bind the address the old one released, and `aStartedPortRefusesASecondBinder` asserts that a
competitor trying to bind the same port while the holder is running fails with `BindException`.

No `awaitTermination`, `shutdownNow`, `startAsync` or `stopAsync` appears in the contract or the implementation.
Waiting is a policy belonging to the assembly, which has a deadline; the adapter's `stop()` is the mechanism, and the
assembly decides how long to wait for it. `no_method_offers_a_second_lifecycle_shape` refuses the pool vocabulary by
name, so a developer looking for a way to wait for in-flight requests finds the answer in the assembly's shutdown
timeout, not in the adapter's API.

### 7. The bind failure path: `UncheckedIOException`, spent, owned provider closed

`HttpServer.create` throws `IOException` when the address cannot be bound — "Address already in use" is the common
case, "Cannot assign requested address" is the one this repository's tests reach. The adapter wraps it in
`UncheckedIOException` so `start()` stays free of checked exceptions, keeps the JDK exception as the cause, marks the
adapter `STOPPED` (spent), and closes an owned provider. The message reads "cannot start the JDK HTTP server on
<address>", and the original `IOException` is still the cause, because "address in use" is information an operator
needs.

`aBindFailureCarriesTheJdkOwnCauseAndLeavesTheAdapterSpent` asserts the path: the adapter tries to bind `192.0.2.1`,
which is TEST-NET-1, an address no interface here has, so the kernel refuses the bind rather than the permission layer
refusing a privileged port. The test asserts that `start()` throws `UncheckedIOException` containing "192.0.2.1" with
cause `IOException`, that `isRunning()` is false, that `port()` is empty, and that a second `start()` throws
`IllegalStateException` — the adapter is spent, not retryable. `aStartedPortRefusesASecondBinder` asserts the same
path from the other side: a competitor trying to bind a port the holder already bound fails with `UncheckedIOException`
and root cause `BindException`, and the holder is untouched.

This repository's container runs as root, so privileged-port tests are meaningless: port 1 was observed to bind
successfully, and the first version of the bind-failure test passed for the wrong reason. The tests bind `192.0.2.1`
instead, which is probed to fail with `BindException: Cannot assign requested address` — a kernel refusal rather than a
permission refusal, which is the failure the adapter's catch block is written for.

A failed start releases an owned provider and leaves a borrowed one usable. The catch block in `start()` calls
`threads.close()` if `ownsThreadProvider` is true, so a startup that could not bind does not leave a thread pool
behind. `anAdapterThatFailedToStartReleasesTheProviderItCreated` asserts the release by capturing the log;
`aBorrowedProviderThatFailedToBindIsLeftAloneToo` asserts the other half by submitting work to the borrowed provider
after the competitor's `start()` threw.

### 8. What a failed start releases, and what the JDK refuses to release

There are two different failures inside `start()`, and the catch block does a different amount of work in each.

**The bind itself failed.** `HttpServer.create(address, backlog)` binds in its constructor — `ServerImpl` opens a
`ServerSocketChannel` and calls `socket.bind(addr, backlog)` before returning — so a refused bind throws
`BindException` from `create()` and no `HttpServer` exists. There is nothing to release. This is the path §7 describes,
and it is the one the tests reach with `192.0.2.1`.

**The bind succeeded and something after it failed.** `create()` returned, so the JDK already holds a listening socket
and has started its `idle-timeout-task` timer, and then `threads.newUnboundedExecutor("barezen-http-jdk")` refuses.
The catch calls `created.stop(0)`. That call is not a no-op and not a lie: `ServerImpl.stop()` closes the channel it
owns, cancels the timers and drains the connection sets, and the timers do go away — probed by counting threads on both
runtimes, where ten created-and-stopped servers leave zero `idle-timeout-task` threads behind.

What it does not do is release the port. Measured on Temurin 17.0.20.1 and Temurin 21.0.12.1, identically:

| sequence | port rebindable afterwards | `/proc/net/tcp` entry | JDK timer threads |
| --- | --- | --- | --- |
| `create(addr, 0)`, never `start()`, `stop(0)` | **no** | still `0A` (LISTEN) | cancelled |
| `create(null, 0)`, `bind(addr, 0)`, never `start()`, `stop(0)` | **no** | still `0A` | — |
| `create(addr, 0)`, `start()`, `stop(0)` | **yes** | `06` (TIME_WAIT) | cancelled |

`stop()` does close the channel — `try { schan.close(); } catch (IOException e) {}` — and the kernel keeps the listener
anyway until `start()` has run at least once, so this is not something the adapter can fix by calling `stop()` harder or
longer. Reordering the adapter to `create(null, 0)` and then `bind()` was probed and refused on both counts: a
bound-but-never-started server holds the port exactly as stubbornly, and a failed `bind()` still leaves the socket file
descriptor behind, which is strictly worse than what `create()` does.

The consequence for the adapter's design is that the failure is bounded rather than eliminated. `RefusingProvider`
exists only in a test; a real `ThreadProvider` hands out an executor or throws something that means the JVM is out of
resources. A failed start therefore costs one port the JVM will not give back and one timer thread that `stop(0)` does
cancel — not a thread pool, and not a server that answers requests.
`aStartThatFailedAfterBindingSpendsTheAdapterAndClosesNothingItDidNotCreate` asserts the half the adapter controls: the
provider's exception propagates unwrapped, the adapter is spent, a second `start()` is refused, and a borrowed provider
is not closed. It then asserts `canBind(port)` is **false**, pinning the JDK's behaviour: a future runtime that starts
releasing the socket makes that test fail, and the debt entry below is revisited rather than quietly resolved.
Amended on 2026-11-26: the same cleanup and STOPPED transition applies when startup throws `Error`, not only
`RuntimeException`; the original `Error` is rethrown unchanged after cleanup.

**Design debt, named:** a `start()` that fails after binding cannot give the port back on the JDKs this phase was
verified on. The mitigation is that the framework never creates an adapter whose `start()` can fail this way except out
of memory, and an application that treats a failed start as fatal — which is what §5's "spent" rule pushes it toward —
does not see the leak twice.

## Consequences

- **The adapter's lifecycle is `NEW → RUNNING → STOPPED`, with no path back.** A second `start()` throws, and the
  reason is the port — a restarted server may hold a different ephemeral port, so the caller's reference would name a
  server that no longer exists. A restart is a new adapter.
- **The adapter is not a `LifecycleParticipant`.** The orchestrator owns the application's phases; the adapter owns an
  HTTP runtime. The application's assembly writes a participant of its own that calls `start()` and `stop()`, and the
  same participant would drive a Jetty adapter unchanged.
- **Who creates it, owns it.** An adapter that built its own `ThreadProvider` closes it on `stop()`; an adapter that
  was handed a provider leaves it running. The rule is stated as a proverb and enforced by a boolean field.
- **The adapter never constructs a thread.** Every executor comes from a `ThreadProvider`, and `Executors`,
  `ThreadPoolExecutor`, `ForkJoinPool` and `Thread` are refused by an architecture rule. `stop()` knows about every
  thread the adapter created, because the provider owns them.
- **`stop(3)` is the JDK's own wait, bounded rather than infinite.** The adapter gives in-flight exchanges three
  seconds, which is the JDK's own mechanism for letting a handler finish. Waiting is a policy belonging to the
  assembly; the adapter's `stop()` is the mechanism.
- **A bind failure wraps the `IOException` in `UncheckedIOException`, marks the adapter spent, and closes an owned provider.**
  The original exception is still the cause, because "address in use" is information an operator needs. A failed start
  does not leave a thread pool behind.
- **The lifecycle vocabulary is closed.** No `restart`, no `shutdown`, no `awaitTermination`, no `startAsync`, no
  `server()` accessor. The adapter is started, stopped and asked; a second lifecycle shape is a second lifecycle owned
  from the wrong place.
- **Design debt carried forward, named rather than absorbed:** no shutdown-timeout configuration, so the three-second
  grace is a constant rather than a policy; no `awaitTermination`, so an assembly that wants to wait for in-flight
  requests has to build the wait itself; no restart, so an application that wants to rebind a port has to build a new
  adapter. Each stays unwritten because nothing in this phase calls it.

## Alternatives rejected

**Making the adapter a `LifecycleParticipant`.** Tempting, because the orchestrator already drives participants, and an
adapter is a component that starts and stops. Refused because the orchestrator owns the application's phases and the
adapter owns an HTTP runtime; joining them by inheritance would have made the adapter's lifecycle a function of the
orchestrator's registration order, and a restart would have been a second `start()` call from a participant that had
already stopped — which is the bug §5 refuses. The application's assembly writes a participant of its own, and the same
participant would drive a Jetty adapter unchanged.

**A `restart()` method.** A restart is a new adapter, because a restarted server may hold a different ephemeral port. A
`restart()` method would have to rebind the port, which is the same as building a new adapter, and the caller's
reference would name a server that no longer exists. The refusal is stated in the message: "Build a new adapter to
restart (ADR-0024 §5)".

**`awaitTermination`, `shutdownNow`, `startAsync`, `stopAsync`.** Waiting is a policy belonging to the assembly, which
has a deadline; the adapter's `stop()` is the mechanism. A thread-pool facade would have turned the adapter into a
second lifecycle owned from the wrong place, and the application's shutdown timeout would have been buried in the
adapter's API rather than stated in the assembly.

**Configuring the stop grace through `System.getProperty` or a constructor parameter.** The framework has no
shutdown-timeout configuration yet, and a `System.getProperty` here would be a configuration system smuggled in through
a constant's declaration site. The three-second grace is a constant, `STOP_GRACE_SECONDS`, recorded as the value to
replace when the framework has a configuration system rather than leaving the choice buried in a call.

**A `server()` accessor that hands the JDK `HttpServer` to the application.** An application that held the server
could start a second context, replace the executor or stop it out from under the adapter. There is no accessor, and
`no_method_offers_a_second_lifecycle_shape` refuses `server`, `getServer`, `httpServer` and the rest of the pool
vocabulary by name.

**Letting a failed start leave the adapter retryable.** A failed start marks the adapter `STOPPED` (spent), because a
bind failure may have left the adapter in a state its own code did not finish setting up — a partially created server,
a partially started executor, a partially bound port. A retry would have to undo the partial state, which is the same
as building a new adapter. The refusal is stated in the message: "Build a new adapter to restart (ADR-0024 §5)".

## Evidence

Test classes and what they assert:

| file | tests | what this phase ships |
| --- | --- | --- |
| `JdkHttpServerLifecycleTest.Transitions` | 10 | the lifecycle matrix — `NEW → RUNNING → STOPPED`, idempotent stop, close-is-stop, bind-failure path, the port refuses a second binder |
| `JdkHttpServerLifecycleTest.ExecutorOwnership` | 5 | the ownership rule — an adapter that created its provider closes it, a borrowed provider survives, a failed start releases an owned provider and leaves a borrowed one usable, a start that bound and then lost the executor spends the adapter and closes nothing it borrowed |
| `JdkHttpServerLifecycleTest.ThreadModel` | 3 | the thread model — the default provider runs requests on the runtime's best threads, a borrowed platform provider is honoured, a stopped adapter leaves no request thread behind |
| `JdkHttpServerLifecycleTest.ThroughTheAssembly` | 1 | the assembly integration — the orchestrator starts and stops a server it knows nothing about, through a participant the assembly wrote |
| `JdkHttpServerLifecycleTest.NotAParticipant` | 2 | the adapter is not a participant — the contract extends only `AutoCloseable`, and the implementation implements only the contract |
| `BarezenHttpJdkArchitectureTest` | 24 | the lifecycle-field rule, the no-restart-vocabulary rule, the no-thread-construction rule, the adapter-is-not-a-participant rule |

Mutation probes that fired:

| probe | change | consequence |
| --- | --- | --- |
| G | relaxing the start guard to `state == State.STOPPED` | `aSecondStartFailsAndTheRunningServerKeepsServing` failed — the running server was replaced with a different instance on a possibly different port |
| H | relaxing the guard to `state == State.RUNNING` | three tests failed: `startAfterStopFailsBecauseTheInstanceIsSpent` — the spent instance silently bound a new server the caller's reference did not name — plus `aBindFailureCarriesTheJdkOwnCauseAndLeavesTheAdapterSpent` and `aBorrowedProviderSurvivesStopAndIsStillUsable`, which both depend on a failed or completed start leaving the instance spent |
| J | `if (ownsThreadProvider)` → `if (true)` | `aBorrowedProviderSurvivesStopAndIsStillUsable` failed — the adapter closed a provider it did not create, and the borrower's executor was gone |
