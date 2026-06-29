# ADR-0012: NEW to STOPPED is a legal transition, so an orchestrator is safe in try-with-resources

Status: accepted
Date: 2026-09-29
Decides: whether `stop()`/`close()` may be called before `start()`
Related: ADR-0004, ADR-0005

## Context

The natural state machine treats `stop()` as an error in `NEW`: nothing was started, so stopping is a category
mistake. In practice the opposite is true. The overwhelmingly common shape is

```java
try (LifecycleOrchestrator app = LifecycleOrchestrator.builder() ... .build()) {
  app.start();
}
```

and if `start()` throws — bad configuration, port taken — control leaves the block and `close()` runs on an
application that is in `NEW` or `FAILED`. Making that an error means the cleanup path throws, which either
masks the startup failure or forces every caller into a nested try/catch around a resource that was never
acquired.

## Decision

`stop()` is legal from every state and idempotent:

- `NEW` → `STOPPING` → `STOPPED`, with no participant callback executed, because no participant was entered;
  the owned `ThreadProvider` is still closed, since it exists and holds resources from the moment it is built;
- `STARTING` → returns without touching the entered set, because the startup path owns rollback (ADR-0004) and
  a reentrant or concurrent `stop()` must not cut into it;
- `RUNNING` → normal shutdown;
- `STOPPED`, `FAILED`, `STOPPING` → returns the cached `ShutdownReport`, invoking nothing again.

There is no `INITIALIZED` state: nothing in the build distinguishes "all initialized" from "running" well
enough to be worth a branch, and adding it would create transitions that later phases would have to reason
about for no consumer.

## Consequences

- `NEW → STOPPED` is the only path on which a participant's `stop()` is never called, which is exactly the
  behaviour a participant expects: no `initialize()` happened, so there is nothing to release.
- `close()` and `stop()` share one implementation; `close()` discards the report (ADR-0005).
- `start()` after `stop()` throws, because a stopped orchestrator has already closed its thread provider and
  its participants have already been told to release their resources. Restarting is a rebuild, not a rewind.
- The state table is tested directly, including a reentrant `stop()` from inside a participant's
  `initialize()`, so the STARTING short-circuit is verified rather than asserted in prose.
