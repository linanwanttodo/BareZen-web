# ADR-0006: PluginContext is not a service locator

Status: accepted (decided in Phase 2.1, implemented in Phase 2.4/2.5)
Date: 2026-09-29
Decides: the member set of `PluginContext` and how plugins obtain collaborators
Related: ADR-0003, ADR-0007

## Context

A lifecycle or plugin context object tends to grow a `require(Class)` method "just for convenience". Once it
exists, dependencies stop being visible in constructors, the dependency graph cannot be validated before
startup, missing collaborators surface as a runtime failure inside a plugin, and the framework effectively
becomes a bean container it never set out to be.

## Decision

`PluginContext` has exactly these members:

- `config()`
- `descriptor()`
- `events()`
- `contribute(ExtensionPoint<T>, T)`
- `lifecycle()`
- `scope()`

It has no `require()`, `find()`, `get()`, `register()`, `capability()`, `router()`, `sql()`, `metrics()`,
`requestContext()` or `service()`. A plugin obtains its collaborators as constructor parameters; the assembler
in `barezen-app` resolves them from the plugin dependency graph before instantiation. The context is a place to
*publish* contributions and to read configuration, never a place to look objects up.

## Consequences

- A plugin's dependencies are readable from its public constructor, which is what makes the Phase 2.5
  topological ordering possible at all: `provides`/required types are declared once, in a signature.
- Reflection stays a startup-only concern of the assembler. No runtime lookup path exists to reflect over.
- `ServiceRegistry` remains framework-internal plumbing for the assembler; it is not reachable from a plugin
  through the context.
- Convenience is paid for with more wiring code inside `barezen-app`. That is the intended location: one
  assembler with a graph, rather than N plugins each reaching into a shared bag.
- Phase 2.1 deliberately implements none of this. `LifecycleParticipant` is a plain object with constructor
  dependencies, which keeps the kernel free of a context type that later phases might shape wrong.
