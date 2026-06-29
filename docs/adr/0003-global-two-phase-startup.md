# ADR-0003: startup has two global phases, initialize all participants then start all participants

Status: accepted
Date: 2026-09-29
Decides: `LifecycleOrchestrator.start()` ordering
Related: ADR-0004, ADR-0006

## Context

A component that makes work observable to the outside world, such as an HTTP runtime binding a port, must not
do so before every earlier component has registered its routes, validators and background tasks. If each
component initialized and started in one step, an HTTP runtime appearing early in the order could accept a
request for a route whose plugin had not registered it yet, producing 404s or a half-configured response
during the startup window.

An alternative would be a per-component two-phase call interleaved across components
(`a.init, a.start, b.init, b.start`), or a priority field allowing components to declare readiness.

## Decision

`LifecycleOrchestrator.start()` runs `initialize()` on every participant in registration order, and only then
runs `start()` on every participant in the same order. Registration order is startup order; reverse
registration order is shutdown order. There is no priority field, because deciding the order is the assembler's
job (`barezen-app`, from the plugin dependency graph in Phase 2.5), not something a component can vote on.

`initialize()` is where a component allocates and registers; it must not accept work. `start()` is where it
becomes observable.

## Consequences

- The ordering guarantee is structural rather than documentation: a route registered during `initialize()`
  cannot be served before all `initialize()` calls have returned.
- Startup is not parallelised. A slow participant delays the whole application. That is accepted for now: a
  parallel variant would need per-subsystem timeouts and a failure model that can unwind partially started
  components concurrently, which the MVP does not require.
- Components that need another component's *started* state are wrong: they should depend on it through the
  constructor and, if a started neighbour is genuinely required, the assembler must order it earlier.
- Two phases mean `stop()` must tolerate a participant that was initialized but never started (ADR-0004).
