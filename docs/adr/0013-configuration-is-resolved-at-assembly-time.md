# ADR-0013: configuration is resolved once at assembly time and travels as typed values

Status: accepted
Date: 2026-09-29
Decides: where configuration is read, who may read it, and what crosses a module boundary afterwards
Related: ADR-0006, ADR-0009, ADR-0010, ADR-0011

## Context

Phase 2.2 adds the first setting with more than one possible source: `barezen.runtime.threads`, which can come
from a key's default, a packaged `application.properties`, an environment variable or a `-D` flag. The cheap
version of this is a static accessor — `Config.get("barezen.runtime.threads")` readable from anywhere — and it
is the version every Java web framework has shipped at some point. It costs almost nothing to write and buys:

- a component whose behaviour depends on process state a caller cannot see or control, so the same object means
  two things in two deployments;
- a test that cannot construct the component under test without first arranging global state;
- an untraceable decision, because whoever reads a key decides independently when it is read;
- a service locator by another name, which is what ADR-0006 already refuses for `PluginContext`.

The thread layer is the first consumer, and it is also the clearest case: `ThreadProvider` chooses an executor
implementation. If it read `System.getenv` itself, "which threads does this application run on" would have no
single answer, and the Phase 2.1 rule that an explicitly requested policy never degrades would live in whichever
class happened to look up the key.

## Decision

**Configuration is read at assembly time and converted to typed values before any component is built.** A
component receives a value object, never a configuration handle it can interrogate later.

1. **One package owns the inputs.** `com.barezen.core.config` is the only code in core that reads the process
   environment or the property table. Everywhere else, a configuration value arrives as a parameter.
2. **A snapshot, not a view.** `Configuration` is immutable, has no write API, and each source copies what it
   found when constructed. There is no reload, no watch, no remote source and no config centre.
3. **Typed keys, not string lookups.** `ConfigurationKey<T>` carries name, type and default. Resolving a key by
   string and casting is not available, so a wrong type is a compile error rather than a `ClassCastException` at
   startup.
4. **Hand-written binding.** `RuntimeConfig.from(configuration)` reads the keys it needs and returns a record.
   There is no `@ConfigurationProperties`, no annotation scan, no reflection over components and no generic
   binder.
5. **`Configuration` resolves values and nothing else.** No method returns a collaborator. `get(SomeService.class)`
   does not exist, and a test pins the declared method surface so a later phase cannot add to it quietly.
6. **The decision point is the assembler.** `Configuration → RuntimeConfig → ThreadPolicy → ThreadProviders.create(...)`
   is the whole path, with `LifecycleOrchestrator.Builder.configuration(...)` as the entry point. `ThreadProvider`
   and `ThreadProviders` accept a policy and a `RuntimeCapabilities` snapshot; neither can see a `Configuration`.
7. **A value that cannot be honoured fails, and says only what it knows.** `virtual` on a runtime without virtual
   threads is a startup failure, never a downgrade to `auto`. Its message names the request and the runtime — not
   the key or the file it came from, because a lifecycle failure must not depend on the API that supplied it. The
   layer that resolved the value is the layer that reports the source.

Precedence is fixed and positional: key default &lt; `application.properties` &lt; environment variables &lt;
system properties. Environment names follow one rule, `.` to `_` then uppercase.

Three types the phase sketch listed are deliberately **not** created. A `ConfigurationValue` wrapper would carry
a string plus its source, and the only thing that needs the source is a failure message, which the read path
already has; a `ConfigurationBinder` interface would exist for one implementation that is four lines of
`RuntimeConfig.from`; a `ConfigurationValidator` has no consumer until a key exists whose valid range is narrower
than its type. Each gets a record when a second implementation or a real validator appears, not before.

## Consequences

- Core keeps no new dependency: `java.util.Properties` reads the file. YAML is not supported in this phase, and a
  format that needs one will get a `ConfigurationSource` in a module that owns the parser rather than a parser
  inside core (ADR-0001).
- Resolving a value happens once, so a test builds a component's behaviour by passing a value. No test in core
  arranges global state except `StandardConfigurationTest`, which exists precisely to prove the production wiring
  reads it.
- Two consequences are guards rather than habits: only `com.barezen.core.config` may call `System.getenv` or
  `System.getProperties`, and the layered ArchUnit rule makes `Config → Concurrent → Kernel` one directional, so
  `concurrent` cannot reach back for a snapshot.
- A component cannot pick up a configuration change at runtime, which is intended: a running application's
  configuration is the one that was validated at startup.
- Later phases that want a value add a key to a typed config record and pass the record in. The number of places
  that read global state stays one.
- Error messages one layer down must describe the request and the runtime, not the config source. A caller
  debugging a startup failure reads the source names from the `ConfigurationException` raised during binding.
