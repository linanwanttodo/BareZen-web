# ADR-0005: shutdown is best effort and reported, not thrown

Status: accepted
Date: 2026-09-29
Decides: how `stop()` handles participant failures
Related: ADR-0004

## Context

During shutdown every remaining participant still has to get its turn. If the second participant's `stop()`
throws and that aborts the loop, the other seven leak their resources, and the exception tells the operator
nothing about whether the remaining ones were reached. Shutdown also frequently runs from a JVM shutdown hook,
where a thrown exception is printed by the default handler and otherwise discarded.

## Decision

`stop()` calls `stop()` on every entered participant in reverse order, catching `RuntimeException` and `Error`
from each, logging it, and collecting it as a `ShutdownFailure(id, cause)`. The method itself never throws; the
outcome is returned as a `ShutdownReport` and cached, so repeated calls return the same report.

## Consequences

- Shutdown problems are data, not control flow. `barezen-app` decides in Phase 2.6 whether a dirty report means
  a nonzero exit code; core has no opinion about process exit status.
- A participant cannot break another participant's cleanup, which is the property the guarantee exists for.
- Callers who only do `app.stop()` and ignore the return value lose the report. `close()` deliberately has the
  same behaviour as `stop()` (ADR-0012) rather than throwing, so try-with-resources users must read the log, not
  the exception, on a dirty shutdown. That is an accepted trade-off: throwing from `close()` would mask a
  startup exception already in flight.
- Failures at JVM-exit time are logged but cannot be reported to anyone, because the VM may stop before an
  application-level reporter runs. Logging is therefore not optional decoration on this path.
