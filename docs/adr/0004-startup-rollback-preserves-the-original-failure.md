# ADR-0004: a failed startup unwinds itself, and the startup failure is always what the caller sees

Status: accepted
Date: 2026-09-29
Decides: rollback semantics of `LifecycleOrchestrator.start()`
Related: ADR-0003, ADR-0005

## Context

If participant 4 of 9 fails during startup, participants 1 to 3 have already allocated resources: file handles,
pool connections, bound ports. Two failure modes matter more than the ordering itself: leaking those resources
because the caller is expected to clean up after an exception, and losing the reason startup failed because a
cleanup error replaced it.

## Decision

1. `start()` performs rollback before throwing. Every participant that completed `initialize()` receives
   `stop()` in reverse order. The participant whose `initialize()` threw receives `abort()` **instead**, because
   at that moment it is in a half-built state that its own `stop()` was never written to handle.
2. After a failed `start()` the entered set is empty and the state is `FAILED`. There is nothing left for the
   caller to release, so calling `stop()` afterwards is legal and a no-op.
3. The reported exception is `StartupException` carrying the original cause, the failed participant id and the
   phase (`INITIALIZE` or `START`). Rollback failures never replace it: they are attached as suppressed
   exceptions and exposed through `rollbackFailures()`, and the state is still `FAILED`.
4. A failure inside `abort()` is handled the same way. It cannot demote the initialize failure it follows.

## Consequences

- Participants may allocate in `initialize()` without a try/catch rollback of their own; the orchestrator owns
  unwinding. `abort()` only has to release what `initialize()` managed to acquire before it failed, which is a
  narrower question than "am I started".
- `stop()` must be safe on a participant that was initialized but never started (ADR-0003 makes that state
  reachable).
- Callers reading only the top-level exception see the actionable failure. Rollback noise is available but
  secondary, which matches how these failures are actually triaged: fix the startup cause first.
- Startup is not retried. A partially started application is considered broken, not recoverable, so the
  assembler decides whether a retry is meaningful (for example a port still being free a second later).
