# ADR-0011: a ThreadProvider is an executor factory with ownership, not a shared registry

Status: accepted
Date: 2026-09-29
Decides: `ThreadProvider` surface, lifecycle and scoping
Related: ADR-0002, ADR-0010

## Context

Two failure shapes were in reach. First, making the provider a registry: `executor("http")`,
`register(name, pool)`, a process-wide singleton. That gives any module a handle to someone else's thread
pool, makes ownership of shutdown ambiguous and recreates the service-locator problem ADR-0006 rules out, one
level down. Second, giving it a general-purpose `callBlocking(...)` helper, which would make the provider the
place where blocking policy lives even though "blocking" is a property of the call site, not of the thread
model.

## Decision

`ThreadProvider` declares exactly: `kind()`, `name()`, `preferred()`,
`newBoundedExecutor(int, String)`, `newUnboundedExecutor(String)` and `close()`. It extends `AutoCloseable` and
`close()` never throws. There is no `callBlocking`. A test asserts the declared method set, so growing the
interface into a bus is a build failure rather than a review comment.

The provider **owns** what it creates: `close()` terminates only the executors that this provider instance
 handed out, and an implementation refuses to create more afterwards. Because ownership is per instance, a
provider is created per subsystem that wants it (HTTP runtime, event bus, scheduler), never shared as a global.

`ThreadPolicy` resolution rules, enforced in one method (`ThreadProviders.create`):
- `AUTO` picks virtual when the runtime supports it, platform otherwise;
- `PLATFORM` always means platform, even on Java 21;
- `VIRTUAL` means virtual or startup fails with `ThreadCapabilityUnavailableException`.

An explicitly requested capability never degrades silently. `AUTO` is the only policy allowed to fall back.

## Consequences

- Bounded concurrency has one meaning on both providers: a fixed platform pool and a semaphore-gated
  per-task virtual executor give the same ceiling, so switching thread model does not change a resource bound.
  A shared conformance test suite proves it for both.
- `close()` cannot run arbitrarily long: orderly shutdown first, then a bounded force stop, then a report. A
  task that survives force stop is logged rather than waited on forever.
- Subsystems must ask for their own provider, which reads as slightly more wiring than a global. That is the
  point: shutting down the HTTP runtime must not stop the event bus.
- `preferred()` exists to keep `AUTO`'s decision observable in logs, not to let callers vote. A platform
  provider on a Java 21 runtime honestly reports `preferred() == false`.
