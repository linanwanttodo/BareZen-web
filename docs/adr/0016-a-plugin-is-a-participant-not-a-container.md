# ADR-0016: a plugin is a participant, not a container

Status: accepted
Date: 2026-09-29
Decides: what a Plugin is, how many types the plugin SPI needs, where its lifecycle comes from, and what it may never be handed
Related: ADR-0003, ADR-0004, ADR-0005, ADR-0006, ADR-0013, ADR-0014, ADR-0015
Refines: ADR-0006 (the member list of `PluginContext`) — that record's boundary survives, its proposed members do not

## Context

Phase 2.5 introduces the plugin concept, and the phase brief sets the test it must pass: «Plugins are explicit
participants, not mini application contexts.» The trap is that the trap is invisible. A plugin SPI grows the way
every container grows — one sensible convenience at a time:

- a plugin needs to know which routes to register, so `install(PluginContext)` is added;
- the context needs the router, so `context.router()` is added;
- a plugin needs a pool size, so `context.config()` is added;
- a plugin needs to start after another plugin, so `PluginManager` is added to order them;
- the manager needs the objects to hand out, so it grows a lookup.

Each step is defensible alone. The endpoint is `ApplicationContext`, which ADR-0006 already refuses and ADR-0015
just spent a phase removing from the lifecycle engine. The framework's own principle — *Explicit Dependencies > Magic
Injection* — says the whole chain is one mistake wearing five hats.

So the phase question was not "what does a plugin API contain" but **what is the smallest thing that is still
recognisably a plugin**, and whether it can be built without adding a second anything to a kernel that already has
exactly one lifecycle engine, one container, and one configuration chain.

## Decision

**A plugin is a `LifecycleParticipant` that can name itself.** The SPI is two types:

```java
public interface Plugin extends LifecycleParticipant {
  PluginDescriptor descriptor();
}

public record PluginDescriptor(String id, List<String> dependencies) {}
```

`com.barezen.core.plugin` is public surface, contains two types, and depends on one type outside its own package:
`LifecycleParticipant`.

### 1. Plugin

A plugin declares one method and inherits four phases (`initialize`, `start`, `stop`, `abort`), all with empty
defaults already. It receives no argument at any phase, and holds nothing.

`new AuthPlugin(router, credentialStore, passwordHasher, tokenIssuer)` — not `new AuthPlugin(context)` followed by
`context.require(Router.class)`. The constructor is where a dependency lives, for the same reason ADR-0014 gives: it
is the only place a reader can see what a component needs without executing it.

### 2. Plugin lifecycle — one path, not two

**There is no `install()`, no `start(PluginContext)`, no `uninstall()`, and no `abort()` on `Plugin`.** The
alternatives were considered and each fails the same test:

- `install()` vs `initialize()` is the same moment with two names. Two names means two orders, and ADR-0003 chose
  the order: all initialize, then all start. An `install` phase either duplicates `initialize` or runs beside it,
  and the framework ends with the two-engine disagreement ADR-0015 was written to prevent.
- `start(PluginContext)` / `stop(PluginContext)` take a context parameter. That parameter is the lookup surface.
  Removing the parameter removes the type it would have been.
- `uninstall()` overlaps `stop()`, which the orchestrator already calls in reverse registration order with failure
  reporting (ADR-0005). Two release hooks means a caller has to know which one owns the resource.
- `abort()` is already on `LifecycleParticipant`, with the exact rollback semantics ADR-0004 defines.

`Plugin extends LifecycleParticipant` rather than adapting to one. The worry this raises is polluting the core
abstraction, and the answer is which direction the dependency goes: `plugin → kernel`, never `kernel → plugin`. The
orchestrator's method signature takes a `String id` and a `LifecycleParticipant`; it can drive a plugin without
knowing the word "plugin" exists, and `no_core_type_depends_on_the_plugin_spi` keeps that true. The kernel has been
widened by nothing; the SPI has been narrowed by inheritance.

### 3. PluginDescriptor — two fields, each with a consumer today

| Field | Who uses it now |
| --- | --- |
| `id` | the application's registration key; the string a startup failure is reported against; the thing a duplicate identity is detected on |
| `dependencies` | the application, when it decides the order it registers participants in |

Refused, each because nothing reads it:

- **`name`** — nothing renders it. It is `id` with a space in it until a console exists to show it.
- **`version`** — there is no constraint and no solver, so the jar already records the fact better than a field no
  code compares. A version enters this API when a version *requirement* exists, in the same record.
- **optional dependencies** — they are the reason `null` becomes a legal dependency entry. `null` is refused here
  precisely so an optional-dependency engine cannot arrive as a missing value (ADR-0014's "ambiguity is refused
  rather than resolved by rule" is the same instinct).
- **capabilities, provides, ordering weights** — each is a resolver asking to be written.

Two rules are enforced at construction because they cost two lines and would otherwise cost a graph walk: no blank
or null id among dependencies, and no plugin depending on itself. The dependency list is copied on construction, so
it cannot change after the application has already read it to pick an order.

### 4. Dependencies are data; the application acts on them

**Who verifies and orders plugin dependencies in this phase: nobody.** The metadata exists, `List<String>` is read by
the application, and `LifecycleOrchestrator.register()` order is the mechanism — ADR-0003 already decided ordering is
the assembler's job, and ADR-0015 assigned that job to the application. A resolver would need missing-dependency
detection, cycle reporting, and ordering across *plugins*, and right now a plugin's dependency list cannot be
validated at all, because nothing in core holds a set of plugins.

That gap is recorded as a known limitation, not papered over with a half-resolver. When a second phase needs an
ordered plugin set, it gets a `PluginOrdering`-style function in the application layer (or in `barezen-app`), which
can read every descriptor at once — not a framework-internal registry.

### 5. No PluginContext, and why that is the honest answer to ADR-0006

ADR-0006 lists six members for a `PluginContext`: `config()`, `descriptor()`, `events()`, `contribute(...)`,
`lifecycle()`, `scope()`. This phase ships none of them, and the reason is not that ADR-0006 was wrong about the
boundary — it was exactly right that the context must not be a locator. It was written before three things were
decided:

- `config()` contradicts ADR-0013. A plugin handed a configuration handle can go back for another key after startup,
  which is the property ADR-0013 exists to remove and ADR-0015 removed from the orchestrator for the same reason.
  The chain is `Configuration → typed value → constructor`, so a plugin receives the resolved value, not the bag.
- `lifecycle()` contradicts ADR-0015. A plugin holding the orchestrator can register components, drive someone
  else's phases, or hand itself a second engine.
- `events()`, `contribute(...)` and `scope()` have no targets yet. There is no event bus yet (it is one of the later
  plugin-model phases, 2.6–2.7), no extension point to contribute to, and DI has exactly one scope (ADR-0014).

**ADR-0006's prohibitions are adopted in full; its member list is superseded.** A type whose every permitted member
either violates another ADR or has no consumer is not a minimal context — it is a bag with a launch plan. Creating
`PluginContext` now would mean creating the object whose entire content is "the framework will give you things
later".

### 6. No ExtensionPoint, no ExtensionRegistry

Conceptually a plugin contributes to a framework capability. In core today there is no such capability to contribute
to: no router, no event bus, no security chain, no metrics. A `contribute(ExtensionPoint<T>, T)` method needs a
declaration of `ExtensionPoint` by the component that owns it — which is Phase 3 (`barezen-web`) and the event model
phase, not here.

The alternative — a generic `Map<String, List<Object>>` registry in core — answers all ten of the phase brief's
registry questions with guesses, since it has no real consumer to answer them: who creates it, who owns it, whether
duplicates are legal, when registration closes, whether reading another plugin's contribution is allowed, whether
ordering matters, whether removal exists, whether it is thread safe. A registry whose semantics are invented rather
than derived is precisely the "new universal registry" the brief warns against.

**So the extension mechanism ships when the first extension point does**, in the phase that owns it, where the
owning component can state its own rules (routes: unique paths, reject duplicates; handlers: append-only, ordered).

### 7. No discovery

`new AuthPlugin(...)` in the application's own code. No `ServiceLoader<Plugin>`, no classpath scan, no package scan,
no annotation scan, no `META-INF` reading, no dynamic class loader.

Discovery and the plugin model are two questions, and a phase that answers both at once gets the wrong answer to the
first, because SPI discovery is a mechanism that invites the container to decide what components exist — the exact
thing ADR-0014 refuses for DI. Plugin discovery, if it comes, is a separate record with a separate phase; nothing
here blocks it, and nothing here pre-shapes it.

### 8. Ownership, unchanged

| Object | Created by | Owned (released) by | Driven by |
| --- | --- | --- | --- |
| `Plugin` instance | application assembly code | the application | `LifecycleOrchestrator` |
| a plugin's collaborators | as for any object (ADR-0015) | per ADR-0015 | — |

`stop()` is a plugin's release hook; there is no second one. A plugin registered with an orchestrator is an
`instance(...)` registration if it is also assembled through DI, so the two release paths stay disjoint.

### 9. No PluginManager, PluginRegistry, PluginRuntime, PluginContainer

The phase brief names these as the danger list, and the pattern is in the names: each is an object that holds a set
of plugins, and an object that holds a set of components eventually hands them out. Nothing in this design needs one,
because every job one would do is already assigned — creating plugins (the application), ordering them (the
application, via registration order), driving them (the orchestrator), identifying them (the descriptor).

## Consequences

- The plugin SPI is two types, one method, one record component pair, and one dependency edge. The question "why is
  each plugin type public" has a shorter answer than any design that had a `PluginContext`: two types, both with a
  caller outside core today — an application implements `Plugin` and constructs a `PluginDescriptor`.
- A plugin cannot be described in a file; it can only be written down in code that runs. That is the point of
  Explicit Assembly, and it is what the missing discovery mechanism would have to justify itself against.
- A plugin that needs the router needs it as a constructor parameter, so the application must construct the router
  before the plugin. Phase 3 will feel this as more wiring in one place; the alternative was a lookup.
- `PluginDescriptor.dependencies()` is metadata with no validator. An application that registers `user` before
  `auth` despite the declaration gets a plugin that fails in `initialize()`, which is a startup failure with a stack
  trace — wrong order surfaces loudly, it just does not surface as "your dependency graph disagrees".
- `core_has_no_web_or_persistence_concepts` already forbade `com.barezen.plugin..`; that is the *future* plugin
  modules package, not this one, and it stays as written — core must not reach forward into modules that do not
  exist.
- Three new ArchUnit rules plus the layered rule hold the boundary, each checked against a deliberately violating
  class:
  a `PluginContext` exposing `configuration()`, `services()`, `lifecycle()` and `require(Class)` fired five rules at
  once, including the two pre-existing service-locator rules written in Phase 2.3; a `PluginRegistry` inside
  `kernel` fired the leaf rule and the layer rule. Both probes were then deleted.
- `Plugin → LifecycleParticipant` means the SPI is not usable without the kernel. That is intended: a plugin *is* a
  kind of participant. It also means `kernel` cannot be split from `plugin` at build time; no module boundary is
  drawn here, only a package one.
