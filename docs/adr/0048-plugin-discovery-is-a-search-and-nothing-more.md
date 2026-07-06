# ADR-0048: Plugin Discovery Is a Search the SPI Offers and Nothing Else — It Finds Announced Plugins, Decides Nothing About Them, and Keeps None of Them

## Status

accepted
Date: 2026-10-06
Decides: whether the framework searches for plugins at all, which package may hold the search, what a searchable plugin must be, how discovered and hand-built plugins meet, what a broken provider does, and what discovery is forbidden to become
Related: ADR-0016 §7 (the refusal this record supersedes), ADR-0014 (explicit factory registration, the reason §7 was written), ADR-0025 §5 (a service interface may exist without the contract searching), ADR-0041 (the validator where the two sources merge), ADR-0042 (who may name whom), ADR-0047 (the contribution mechanism discovery feeds), ADR-0006 (no service locator), ADR-0004 (the original failure survives)

## Supersedes

ADR-0016 §7 refused discovery: *"No `ServiceLoader<Plugin>`, no classpath scan, no package scan, no annotation scan, no `META-INF` reading, no dynamic class loader,"* on the ground that *"SPI discovery is a mechanism that invites the container to decide what components exist — the exact thing ADR-0014 refuses for DI"*, and left the door open: *"Plugin discovery, if it comes, is a separate record with a separate phase."*

This is that record and that phase. **§7's concern is kept as a build fact; only its refusal is lifted.** The container (`core.di`) and the bus (`core.event`) still may not scan, load a class by name or enumerate a service — `the_assembler_and_the_event_bus_do_not_scan_or_reflect` still forbids `ServiceLoader.load` in both overloads there, and this phase spike-proved it. What changed is that a plugin's *arrival* no longer has to be an act of the application's assembly code.

## Context

By the end of 2.16 a plugin could be named (ADR-0016), ordered and dependency-checked (ADR-0041), could sit on the framework rather than in it (ADR-0042), and — as of ADR-0047 — could contribute routes. Every one of those still required an application to write `new SomethingPlugin(...)` and name the type. That is an SPI with no ecosystem: a third-party jar cannot be adopted, only imported and hand-wired.

README's module table carried the row honestly (`Discovery | 3.x | DEFERRED`), and the framework had already built the precedent for the shape of the answer. ADR-0025 §5 kept `ServiceLoader` out of `barezen-web` — *"deciding which adapter an application runs is composition, and composition belongs to the layer above. What lives here is the shape of the answer, not the search"* — which is an argument about *who decides*, not about whether a search may exist. The search exists in the JDK (`ServiceLoader` + `META-INF/services`), costs no parser, no dependency and no scanning library, and does not decide anything if the decision stays with the validator and the orchestrator.

## Problem

- Which package may hold the search without a rule catching it and without the framework becoming plugin-aware.
- What a plugin must be to be searchable — and what that makes impossible.
- Where the discovered set meets the hand-built set, and what a collision means.
- What to do with a jar that names a provider it cannot deliver.
- What discovery must not grow into: a registry, a lookup, a lifecycle, or a manifest language.

## Options Considered

**A — keep refusing discovery (ADR-0016 §7 literal).** Refused by the phase's own premise: the project's stated goal is a pluggable framework other projects can adopt, and "adopt" requires a component arriving without being named. Recorded here so the reversal is attributable rather than quiet.

**B — the search inside the DI container (`ServiceAssembly` auto-registering providers).** Refused: it is precisely the drift §7 named, and it is already forbidden by `the_assembler_and_the_event_bus_do_not_scan_or_reflect` — spike-proven in this phase (1 violation, 21 tests 1 failure, build failed). A container that discovers has stopped being told what exists, and ADR-0014's whole claim is that a reader of the assembly sees every component.

**C — annotation or package scanning (`@BarezenPlugin`, classgraph-style).** Refused: needs a scanning dependency or a classloader walk, contradicts ADR-0018 §13 and `the_contract_declares_and_reads_no_annotations`, and buys nothing the services file does not — both say "this class implements the interface", one of them without a parser.

**D — a `PluginRegistry` that loads once and hands out.** Refused: ADR-0016 §9's danger list, and the observable difference from ADR-0047's pure narrowing is that a registry answers «give me the auth plugin», which ADR-0006 refuses outright. Holding instances is also what makes the second call in `everyCallReadsTheClasspathAgainSoNothingIsStored` impossible to write.

**E — a `barezen-plugin.yaml` manifest the framework parses.** Refused: a format, a parser, a validation surface and a second registration path, all to say what `PluginDescriptor` already says in code. ADR-0016 §3's line holds — *"This is a description, not a manifest format... nothing parses a file"* — and this phase did not need a file parsed: the JDK's provider-configuration file is the manifest, and nothing in BareZen reads it, `ServiceLoader` does.

**F — chosen: `PluginDiscovery`, a pure static function in the SPI's own package.**

## Decision

### 1. `com.barezen.core.plugin.PluginDiscovery`, two methods, no state

```java
public static List<Plugin> discover();               // the thread context loader
public static List<Plugin> discover(ClassLoader loader);
```

Returns the plugins announced by `META-INF/services/com.barezen.core.plugin.Plugin`, in provider-configuration order, as a fresh immutable list. Empty when nothing is announced — a plugin-free application is the common case, not an error.

### 2. The search lives in `core.plugin`, and only there

`barezen-web` may not search (ADR-0025 §5, `the_contract_never_reflects_loads_or_scans`) and `core.di`/`core.event` may not search (spike-proven again in this phase). Inside `com.barezen.core.plugin` the call is legal by the letter of every rule and honest by their intent: it is the one core package whose subject is components arriving from elsewhere.

The alternative placement was `barezen-plugins`, and it was rejected on the user-facing arrow: an application that wanted a pluggable framework would have had to depend on the shipped auth/user/openapi plugins to get discovery. Placement kept the dependency pointing the way ADR-0042 draws it.

### 3. A discoverable plugin is one that needs nothing

`ServiceLoader` instantiates through a public no-argument constructor, so a searchable plugin cannot take a `DataSource`, a `SessionStore` or a `PasswordVerifier`. Those stay the application's to construct, exactly as ADR-0016 §1 required, and the phase states the limit rather than designing around it: the way around it is a lookup, and the lookup is what five ADRs refuse. A future phase that wants collaborator-injection has to answer ADR-0014 with an explicit factory, not with a context.

None of the three shipped plugins is discoverable, and `PluginExample` shows both halves: `auth`/`user`/`openapi` built by hand, `reports` announced in a services file and found.

### 4. The two sets meet at `PluginRegistration.validate`, nowhere else

```java
List<Plugin> plugins = PluginRegistration.validate(concatenated(built, discovered));
```

Declared dependencies are honoured across the boundary (a discovered plugin may depend on a hand-built one and vice versa), and a duplicate id is refused whichever side it came from — a jar cannot shadow an application's own plugin, and an application cannot double-register one it also happens to have on its classpath. Ordering, refusal and the all-or-nothing report stay ADR-0041's; discovery adds no precedence of its own.

### 5. A broken provider aborts the call

`ServiceConfigurationError` propagates with the JDK's message, which names the provider class. Skipping it and continuing would boot an application that quietly lacks an integration — the support ticket nobody can reproduce — and ADR-0004 already decided an assembly failure keeps the original cause and reports it.

### 6. Nothing is kept

Each call re-reads the classpath and instantiates new objects. There is no cache field, no static list, no `Optional<Plugin> byId`, no way to ask for a plugin that was found earlier, and no phase run on anything found. M1 in the mutation table is the cache this design refuses, and it dies on exactly that test.

## Consequences

- The core plugin SPI is four types — `Plugin`, `PluginDescriptor`, `PluginRegistration`, `PluginDiscovery` — all leaves, none holding anything. `Plugin` and `PluginDescriptor` are still the two types ADR-0016 defined, unchanged in every signature.
- No rule had to be amended. `the_plugin_spi_reaches_only_the_participant_interface` measures dependencies on *kernel* types and `java.util.ServiceLoader` is not one; `no_core_type_depends_on_the_plugin_spi` is satisfied because the new class is inside the plugin package; the layered rule is untouched.
- Discovery has no framework-side caller and never will by design: the application calls it, so the classpath stays a decision somebody made when they wrote the pom.
- `barezen-core` gains a test-scope provider configuration (`src/test/resources/META-INF/services/`), which is what lets the primary tests run against a real `ServiceLoader` rather than a mock of one.
- The README row `Discovery | 3.x | DEFERRED` becomes a shipped row; the `Route enumeration` and `Second extension point` rows stay deferred with their triggers.
- Embedding is the reason for the `ClassLoader` overload: a servlet context's loader is not the thread context loader, and 3.3 will need it. It has a consumer today (`PluginDiscoveryTest$Loaders`), which is the standard every public method is held to.

## Security implications

- **Discovery grants a classpath entry the right to add routes.** Before this phase an application's route table was exactly what its own code registered; now any jar announcing a `RouteContributor` plugin can add one. What limits it: no wildcard or prefix patterns exist (ADR-0019 §19), a colliding route aborts the assembly rather than being won, the table is frozen before any lifecycle runs so nothing can be added later, and a contributed route is no more protected and no less protected than a hand-registered one — the security decorator still composes at the handler (ADR-0033, ADR-0037).
- **The containment boundary is the classpath, and it was always there.** A jar that can be loaded can also run code in its static initialiser; discovery does not widen the trust surface, it makes an existing one visible. The framework does not load classes from URLs, does not accept a directory argument, and does not offer a remote or reloadable loader — those are the shapes that would turn "drop a jar" into "download a jar".
- No new configuration key, no environment or property read, no reflection, no logging, and no thread-local. `ServiceLoader` is the only new JDK surface.

## Testing

- `PluginDiscoveryTest` — 11 tests in three groups. Search (4): announced providers arrive in provider-configuration order; a provider needs a public no-arg constructor, asserted on both fixtures; the discovered graph orders through `PluginRegistration.validate`; a found plugin is an instance of the caller's own `Plugin` (same class loader). Limits (5): two calls return equal ids but different instances; no lifecycle phase ran (`phases()` empty); the returned list is immutable; a jar and a `new` may not claim one id; a null loader is refused. Loaders (2): a loader that names no providers yields an empty list rather than an error; a services file naming an absent class raises `ServiceConfigurationError` with the provider named.
- `PluginExampleTest` — 3 → 5 tests: the announced plugin arrives from the classpath (`containsExactly("reports")`), the merged set starts in dependency order `[user, openapi, auth, reports]`, the discovered plugin's contributed route serves 200 over a real socket with a 405 on the wrong verb, and the duplicate-id refusal fires across the discovery boundary.
- **Architecture spike**, added, proven, deleted: a `ServiceLoader.load(String.class)` call inside `core.di.ServiceAssembly` fired `the_assembler_and_the_event_bus_do_not_scan_or_reflect` (violated 1 time, 21 tests 1 failure, build failed). The container still cannot search; only the SPI can.
- **Mutations**, each killed, source md5-restored:

| ID | Mutation | Killer | Result |
| --- | --- | --- | --- |
| M1 | cache the discovered list (become a registry) | `everyCallReadsTheClasspathAgainSoNothingIsStored`, `announcedProvidersComeBackInProviderConfigurationOrder`, `aJarAndANewMayNotBothClaimOneId`, `aBrokenProviderFailsTheCallRatherThanBeingSkipped`, `theDiscoveredGraphOrdersLikeAnyOther`, `discoveryRunsNoLifecyclePhaseOnWhatItFound` | 4 failures + 2 errors, build failed |
| M2 | skip a `ServiceConfigurationError` and carry on | `aBrokenProviderFailsTheCallRatherThanBeingSkipped` | 1 failure of 11, build failed |
| M3 | ignore the caller's loader and use the context one | `aLoaderThatNamesNoProvidersIsAnEmptyResultAndNotAnError`, `aBrokenProviderFailsTheCallRatherThanBeingSkipped` | 2 failures of 11, build failed |
| M4 | drop the `requireNonNull(loader)` | `aNullLoaderIsRefusedRatherThanSilentlyReplacedByTheContextLoader` | 1 failure of 11, build failed — `ServiceLoader.load(type, null)` does not refuse it, so the explicit check is load-bearing, not decorative |
- M1's spread is the finding worth reading: a cache does not merely fail its own test, it leaks across tests in the same JVM (the empty-loader call poisoned the cached list and two later tests then read zero plugins). That is the failure mode the no-state rule exists to prevent.
- Module totals on this build: `barezen-core` 264 → 275, `examples` 26 → 28, 0 failures, 0 errors.
- Full reactor on JDK 21: `mvn -o -B -ntp clean verify` → BUILD SUCCESS in 9:26 — core 275 (1 skipped), web 248, http-jdk
  200, security 99, data 64, plugins 114, testkit 77, examples 28 = **1105 tests, 0 failures, 0 errors**; every module's
  coverage check met; `check-jdk-baseline.sh` and `check-class-major.sh` both exit 0 at
  `checked=563 unique-majors=[61]`.
- Observed `PluginExample` output, run outside Maven: `announced on the classpath -> [reports]`,
  `assembled order -> [user, openapi, auth, reports]`, `route table -> Router[GET /health, GET /reports/status]` — the
  plugin arrived from a services file, not from a constructor call in the application.
- JDK 21 only, as in ADR-0047: no JDK 17 in this environment and no remote, so the CI matrix claim is still not executed
  here.

## Non-goals

Deciding which plugins exist (the application's pom does), ordering them (ADR-0041), constructing anything with collaborators, loading from URLs or directories, reload or hot-swap, a plugin lifecycle of its own, version constraints, optional dependencies, and any registry, manager, runtime or context object (ADR-0016 §9 stands in full).

## Reopening trigger

- **A discovered plugin that needs a collaborator** opens the injection question. Its trigger is a real third-party plugin needing a `DataSource`; the answer must be an explicit factory the application registers (ADR-0014), not a lookup surface on this type.
- **A version requirement between plugins** opens `PluginDescriptor.version()` — ADR-0016 §3 admits a version only when a *requirement* exists, in the same record.
- **Provider ordering promises** open if an application ever depends on services-file order rather than declared dependencies; nothing here promises it, and the test pins only provider-configuration order.

## Related Decisions

ADR-0016 §7 (superseded here, its concerns intact), ADR-0014 (why the container may not search), ADR-0025 §5 (the placement precedent), ADR-0041 (where the merged set is decided), ADR-0042 (the arrow placement kept), ADR-0047 (what a discovered plugin can do), ADR-0006 and ADR-0016 §9 (what it must not become).
