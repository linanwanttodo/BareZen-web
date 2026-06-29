# ADR-0010: the runtime capability seam is a three-field snapshot plus one confined MethodHandle probe

Status: accepted
Date: 2026-09-29
Decides: shape of `RuntimeCapabilities` and the extent of newer-JDK probing
Related: ADR-0002

## Context

Once a framework has "capabilities", the pull is to make `RuntimeCapabilities` a registry: a map of feature
flags, a `has(String)` method, maybe a plugin extension point for new ones. That turns a diagnostic snapshot
into a second configuration system whose flags nobody owns and whose defaults nobody remembers, and it invites
modules to branch on capabilities that have no test matrix entry.

The same drift applies to the probe itself. `MethodHandles.lookup()` reaching a Java 21 API is the mechanism
that makes ADR-0002 possible, and it is also the mechanism by which a codebase quietly becomes
JDK-21-only.

## Decision

`RuntimeCapabilities` is an immutable record of exactly three fields — `javaFeature`, `virtualThreads`,
`vendor` — plus `detect()` and the one capability instantiation method
`newVirtualThreadPerTaskExecutor()`. It is a snapshot, not a registry: there is no `has(String)`, no mutable
field map, no extension point, and no method a plugin can contribute to. A field is added only when a module
in the build has a real branch on it, which today means exactly one branch.

Detection and instantiation live in the same seam on purpose: `VirtualThreadProbe` is the only class in the
framework that performs a `MethodHandle` lookup, `com.barezen.core.runtime` is the only package allowed to, and
the flag and the handle are resolved from the same probe. A `RuntimeCapabilities` that reports
`virtualThreads == false` cannot produce the executor, so detection and use cannot disagree.

Scope that is explicitly **not** in this seam: `ScopedValue` and `StructuredTaskScope` are outside the MVP and
outside JDK 17, and an ArchUnit rule fails the build if either is referenced.

## Consequences

- One grep or one ArchUnit rule answers "where does this framework touch a newer JDK API".
- A new capability requires code that consumes it, a test on both JDKs, and a field addition, in that order.
  Speculative flags cannot land.
- The probe returns `null` on a failed lookup rather than throwing, so a vendor VM without the API degrades to
  platform threads instead of failing at class init. An explicit `ThreadPolicy.VIRTUAL` still fails loudly
  (ADR-0011).
- When the baseline eventually moves to 21, this ADR is retired by deleting `VirtualThreadProbe`, replacing the
  lookup with a direct call, and dropping the ArchUnit confinement rule. The rest of core does not change.
