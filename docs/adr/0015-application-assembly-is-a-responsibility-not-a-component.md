# ADR-0015: application assembly is a responsibility of the application, not a framework component

Status: accepted
Date: 2026-09-29
Decides: who combines Configuration, DI, Runtime and Lifecycle into a running application, who owns each object, and in which order the two release paths run
Related: ADR-0003, ADR-0004, ADR-0005, ADR-0006, ADR-0011, ADR-0012, ADR-0013, ADR-0014
Supersedes: ADR-0013 decision item 6 (the `LifecycleOrchestrator.Builder.configuration(...)` entry point)

## Context

Phase 2.3 ended with the correct architecture and one honest gap, recorded in its own report: `ServiceAssembly`
builds objects and closes what it created, `LifecycleOrchestrator` drives participants, and **nothing in the design
was responsible for calling `assembly.close()`**. A `main` that assembled services, registered the participants and
started them had no owner for the objects it did not register as participants. Nothing broke, because nothing tried
to start an application yet — but "who puts these layers together" had no answer, and the phase after this one
(`barezen-app`) would have answered it by accident.

That is the shape of the problem this phase addresses. It is not a feature phase and it does not add capability. It
answers one question:

> BareZen 到底由谁负责把 Configuration、DI、Runtime、Lifecycle 组装成一个完整应用？

Two answers were available and both were rejected.

**"A framework type does it."** An `Application` holding a container and an orchestrator looks harmless and is the
center of every framework that grew from a kernel into a platform. It needs a builder, the builder grows a method
per subsystem (`router()`, `sql()`, `security()`, `events()`, `metrics()`, `plugins()`), it needs somewhere to keep
the assembled objects so its methods can return them, and that place is an `ApplicationContext`. At which point the
framework knows the application's structure and `require(Type.class)` has come back through a different door
(ADR-0006, ADR-0014).

**"The DI container does it."** Then the container creates objects, starts objects, and releases objects — three
roles, two of which ADR-0003 already gave to the lifecycle engine. The container becomes a second lifecycle manager
and the two disagree about shutdown order the first time a participant's cleanup needs assembled infrastructure.

Reaching for either type is the symptom the phase brief names: *如果一个设计开始越来越像 Spring：停下来重新设计。*

## Decision

**Application Assembly is a responsibility, and it belongs to the application's own code.** BareZen provides no
`Application`, `ApplicationBuilder`, `ApplicationContext`, `ApplicationRuntime`, `ApplicationBootstrap`,
`ApplicationFactory` or `ApplicationManager`. `barezen-app` remains a planned module, uncreated. The framework's
layers stay siblings, and only the application's assembly code is allowed to know more than one of them.

```
Configuration ──┐
                ├──► application assembly code ──► ServiceAssembly ──► LifecycleOrchestrator
Runtime ────────┘            (in the application)      (DI)                 (Lifecycle)
```

### Ownership

| Object | Created by | Owned (released) by | Driven (lifecycle) by |
| --- | --- | --- | --- |
| `Configuration` snapshot | application | nobody; immutable | — |
| `RuntimeCapabilities` | `detect()` | nobody; value | — |
| `ServiceAssembly` | application | application (calls `close()`) | — |
| factory-registered service | `ServiceAssembly` | `ServiceAssembly` | — |
| `instance`-registered object | caller | caller | — |
| participants' `initialize`/`start`/`stop`/`abort` | — | caller or assembly, per above | `LifecycleOrchestrator`, exclusively |
| owned `ThreadProvider` | `LifecycleOrchestrator` | `LifecycleOrchestrator` | — |

DI does not implement `LifecycleParticipant`, does not start anything, and cannot be registered as a participant.

### Startup order

1. Load configuration (`Configuration.standard(...)`).
2. Detect runtime capabilities (`RuntimeCapabilities.detect()`).
3. Build the `ServiceAssembly`.
4. Create the application's services — which is `build()` itself; there is no separate step to skip.
5. Create the `LifecycleOrchestrator`, handing it the finished participants.
6. `start()`.
7. `RUNNING`.

There is no step in which the lifecycle engine creates an object, and no step in which DI starts an application.

### Shutdown order

1. `LifecycleOrchestrator.stop()` — participants are released, in reverse registration order, and may still use
   assembled infrastructure while they do.
2. `ServiceAssembly.close()` — what DI created is released, in reverse creation order.

Reversing these is a leak with a stack trace attached: a participant that drains a queue during `stop()` fails
against a pool that has already been closed, and the failure names the participant, not the ordering mistake.

### Failure ownership

Responsibility is one directional; neither rollback mechanism calls the other.

- **`build()` fails:** the assembly releases what it had already created, in reverse creation order, before the
  factory's own exception propagates. A failure during that cleanup is attached to the original and the original to
  it (ADR-0004), so neither is lost. This was not true before this phase: a partial build leaked its objects and
  handed the caller no `ServiceAssembly` to close, because none was ever returned.
- **`start()` fails after `build()` succeeded:** the orchestrator rolls back every entered participant and reports
  `FAILED`; the application then closes the assembly. Rollback inside the lifecycle engine is complete on its own
  side and does not reach into DI.

### Two public methods removed

- `LifecycleOrchestrator.threadKind()` — added in Phase 2.2 so a test could read which provider the builder
  resolved, and used by nothing else. A getter whose only caller is a test is a public API serving the build, and
  "which threads am I on" is a question about the provider, which already answers it. What the policy decided is
  observable where it matters: an unsatisfiable request fails `build()`.
- `LifecycleOrchestrator.Builder.configuration(Configuration)` — the seam Phase 2.2 left open. It made `kernel`
  depend on `config`, and ADR-0013's own rule is that a component must not receive a configuration handle it can
  interrogate later. An application that has resolved its settings hands over the resolved value:
  `RuntimeConfig.from(configuration).threads()` then `.threadPolicy(policy)`. That one line **is** the assembly
  responsibility this record assigns, so the framework may not take it away from the application.

Both removals are breaking for a caller that used them. Within the repository the only callers were tests, and they
now resolve the policy in the application's position, which is what an application does.

## Consequences

- Phase 2.4's code output is two deleted methods, one bug fix, one new ArchUnit rule and tests. That is the intended
  outcome; inventing an assembly type to have something to show would have been the failure mode this record exists
  to prevent.
- Every application repeats the same eight lines of ordering. That is the cost of having no `Application` object, and
  it is the price worth paying: those eight lines are the application's structure, stated where its author can read
  them.
- A `barezen-app` module may still appear later — as a `main` with argument parsing, a banner and config file
  discovery, all of which is convenience on top of the ordering above. It must not become the owner of the graph.
- `ApplicationState` is unchanged. `NEW/STARTING/RUNNING/STOPPING/STOPPED/FAILED` describes one orchestrator's
  lifecycle, which is the only lifecycle the framework has; an `Application` type would have needed a second state
  machine to be confused with.
- No new internal type: the two layers plus local variables express the assembly. A `BarezenApplication` holding
  `assembly` and `orchestrator` would own them, which is exactly the ownership this record assigns to the
  application instead.
- Three rules keep the boundary from eroding, each checked against a deliberately violating class before it was
  trusted: `kernel` may not depend on `config`; the layered rule now allows `Config` to be reached only by
  `Concurrent`, so the chain reads `Config → Concurrent → Kernel` with no shortcuts; `di` remains a leaf that
  nothing in core depends on.
- There is deliberately no `App` layer in the layered rule. The assembly code lives in the application, outside
  `com.barezen.core`, so a layer for it would describe a package that does not exist and would quietly license the
  framework type this record refuses.
- `RuntimeConfig` stays the only seam from configuration to the thread layer, and `kernel` may now not know about
  it: the resolved `ThreadPolicy` crosses the boundary instead of the record. This narrows ADR-0013 item 6, which
  named `Builder.configuration(...)` as the chain's entry point; the entry point is the application.
