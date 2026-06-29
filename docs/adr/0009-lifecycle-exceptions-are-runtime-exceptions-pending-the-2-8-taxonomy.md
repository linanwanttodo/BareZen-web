# ADR-0009: lifecycle exceptions extend RuntimeException now and are re-parented in Phase 2.8

Status: accepted
Date: 2026-09-29
Decides: `LifecycleException`, `StartupException`, `ThreadCapabilityUnavailableException`
Related: ADR-0004, ADR-0005

## Context

The full exception taxonomy (`BarezenException`, error codes, `Problem` mapping for HTTP) is Phase 2.8. The
lifecycle engine cannot wait for it: rollback semantics (ADR-0004) and best-effort shutdown (ADR-0005) are
defined in terms of the exceptions participants throw. Implementing them with bare `RuntimeException` would mean
every `catch` in the orchestrator also catches programming errors such as `NullPointerException` from unrelated
code, and every plugin would have nothing specific to catch.

## Decision

Phase 2.1 introduces exactly three exception types, all extending `RuntimeException`:

- `com.barezen.core.error.LifecycleException` — the family root for lifecycle failures;
- `StartupException extends LifecycleException` — a participant failed an up phase, after rollback;
- `ThreadCapabilityUnavailableException extends LifecycleException` — a requested runtime capability is absent.

Phase 2.8 re-parents `LifecycleException` onto `BarezenException` and adds error codes. No other phase is
allowed to define a lifecycle failure type outside `com.barezen.core.error`.

## Consequences

- Unchecked exceptions match the call sites: `initialize()` and `start()` do not declare `throws`, so a plugin
  failure cannot be quietly ignored by omitting a catch block, and the orchestrator's single catch is specific
  rather than total.
- Re-parenting in Phase 2.8 changes no signature and no catch block, because the type names and hierarchy stay;
  only the root moves. That is the reason the seam is a class rather than direct `RuntimeException` throws.
- The taxonomy root lives in `core.error`, which currently has one leaf. `RuntimeCapabilities` and the thread
  providers depend on nothing in `kernel`, so `error` stays a leaf package (checked by ArchUnit).
- Error codes are deliberately absent now: inventing them before the HTTP `Problem` mapping exists would freeze
  a numbering scheme with no consumer.
