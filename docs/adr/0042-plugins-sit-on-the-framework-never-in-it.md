# ADR-0042: Plugins Sit On the Framework, Never In It — Direction Rules for the Auth, User, and OpenAPI Plugins

## Status

accepted
Date: 2026-10-04
Decides: which SPI ports the first three plugins implement and who may name whom; what `Security` must not learn about plugins; where `PasswordVerifier` lives and why; what the OpenAPI plugin may and may not do in 2.13; how "plugin A cannot mutate plugin B's state" is enforced mechanically
Related: ADR-0016 (the plugin SPI), ADR-0041 (the registration verifier this layer presumes), ADR-0033 (security middleware three-state model), ADR-0034/0035/0036 (thread-free identity, hashing, opaque sessions), ADR-0037 (default-deny and status codes), ADR-0038 (driver-free data boundary), ADR-0018 §12 (the adapter holds no policy), ADR-0019 (the web contract's one-question rule)

## Context

Phase 2.13 names three plugins — Authentication, User, OpenAPI — and three invariants in its own words: reuse the existing lifecycle (no second system); an Auth plugin implements an Authentication provider *without making core Security plugin-specific*; the arrow is `Security ← SPI ← User Plugin`, never `Security → User Plugin`; the OpenAPI plugin produces metadata only, no document. Two further isolation claims a review cannot verify but a build can: *plugin A cannot mutate plugin B's state*, and *a plugin's failure does not corrupt the registry* (with "stop skipped" and "plugin exception swallowed" as named mutations).

The hazard is ADR-0016's, one level up: a plugin ecosystem becomes Spring the day a framework module imports a plugin class "just for the interface." A port a plugin implements must already exist *below* the plugin — that is what "implements a port" means — so the moment a port is co-designed with a plugin, the arrow has reversed. Concretely, `com.barezen.security` meeting `AuthPlugin` would require security to know a plugin exists, and every later security change would then ask "does the auth plugin still compile," which is plugin-coupling wearing an inheritance metaphor.

The three plugins therefore had to be placed against four existing seams — `Authentication`/`Authorization` (ADR-0033), `Credential`'s shape (ADR-0034), `SessionStore`'s opaque sessions (ADR-0036), `Database`'s driver-free boundary (ADR-0038) — plus one new seam (password verification at login), and every placement had to be testable.

## Problem

- Which module owns `PasswordVerifier`, the port login needs before a session exists? Reusing `Authentication` is impossible: `Credential(scheme, value)` holds *one* value (ADR-0034's bearer shape), so a login credential would be a `username:password` string split on a delimiter — a username containing `:` then authenticates as the wrong user. A security bug dressed as parsing.
- How is "core Security unchanged" enforced rather than promised?
- How is "plugin A cannot mutate plugin B's state" enforced when both live in one module, where Java visibility offers only package-private?
- How far does the OpenAPI plugin go before 2.14 — enumerate routes, or collect declared metadata only?
- What makes this layer's lifecycle a second system? An `install()`/`uninstall()` pair, a `PluginContext`, or `start(PluginRegistry)` would each be one. Reuse means the plugins are drivable by `LifecycleOrchestrator` as ordinary participants.

## Options Considered

**A — extend security with a plugin-aware `LoginCredential` (username+password).** Refused: security would know what a login form is (ADR-0033's "one job" broken at the type level) and it is the first back-edge these rules exist to catch.

**B — put `PasswordVerifier` in `com.barezen.security`.** Rejected on a subtler ground: its only consumer today is a plugin. A security interface no security type implements or consumes is metadata about a plugin's design smuggled into the framework — the same refusal ADR-0016 §6 makes for extension points ("the mechanism ships when the first extension point does, in the phase that owns it").

**C — split the plugins into three Maven modules.** Rejected for no design gain: the rules that matter are package-scoped and ArchUnit reads packages, not artifacts; three poms would buy no enforcement and would break the "one test classpath sees all five lower modules at once" property rule 1 depends on.

**D — let the OpenAPI plugin scan the `Router`.** Refused by ADR-0019's one-question rule: `Router` exposes no route enumeration, and widening it to serve documentation puts a doc feature inside a request-handling contract. The chosen alternative — call `add(method, path, ...)` beside `router.route(method, path, handler)` — makes drift visible at one reading location.

**E — plugins-as-ordinary-participants, verified by ADR-0041's `validate`, ports only between them (chosen).**

## Decision

### 1. The layer

| Type | Implements | Package | Compile deps |
| --- | --- | --- | --- |
| `AuthPlugin` | `Plugin`, `Authentication` | `com.barezen.plugins.auth` | core SPI, security ports, own `PasswordVerifier` |
| `UserPlugin` | `Plugin`, `Authorization`, `PasswordVerifier` | `com.barezen.plugins.user` | core SPI, security ports, own `PasswordVerifier`, barezen-data `Database` |
| `OpenApiPlugin` | `Plugin` | `com.barezen.plugins.openapi` | core SPI, `com.barezen.web.HttpMethod` |
| `PasswordVerifier` | *(the port)* | `com.barezen.plugins.auth` | nothing — one functional interface |

The ports `AuthPlugin`/`UserPlugin` implement — `Authentication`, `Authorization` — predate this phase and are unchanged by it. This is the headline, measured: security's public surface gained nothing in 2.13, and the framework drives a plugin through those ports without the word "plugin" appearing below this module (rule 1 keeps it so). `PasswordVerifier` is *plugin-local*, next to its only consumer, because it is not yet a framework port; a second consumer outside the plugin namespace would promote it into `com.barezen.security` with a migration story in a different record.

### 2. Two methods, not one credential

`AuthPlugin` maps its two public verbs to the two HTTP questions:

- `authenticate(Credential)` — the *returning request*: a bearer token looked up in `SessionStore`; never touches a password; wrong scheme / null / unknown / expired → `AuthenticationResult.anonymous()`.
- `issueSession(username, password)` — the *first login*, called by an application's login route: verifies the pair through `PasswordVerifier`, returns `Optional<SessionStore.Session>` (the opaque token *and* the principal). Unknown user and wrong password are indistinguishable empty results (ADR-0037; "the username exists" is free recon).

The split is forced by `Credential`'s single-value shape (see Problem), and keeping it split means a password never travels through a type that could delimiter it. `tokenAuthenticates` proves the seam mechanically: issue through a plugin whose verifier accepts, then re-authenticate through a second plugin sharing the store whose verifier rejects everything — the token still authenticates, because `authenticate` never consults a password.

### 3. The declared edge is by id, not by type

`AuthPlugin` declares `List.of("user")`; `UserPlugin` declares `List.of()`. The auth plugin never names `UserPlugin` — no import, no field type, no signature — and its four declared fields are exactly `SessionStore`, `PasswordVerifier`, `String`, `Duration`, asserted directly. The collaborator the assembly story calls "the user plugin" arrives as the `PasswordVerifier` the application extracted from it. So: auth compiles, runs, and unit-tests with a lambda and no database; "plugin A mutates plugin B state" needs a mutable surface auth does not have (`PasswordVerifier` has one read-only method); swapping user for an LDAP-backed plugin is an application-level assembly change that leaves auth's tests green.

### 4. The OpenAPI plugin is a collector, full stop

- **Input:** the application calls `add(method, path, summary, tags)` while assembling, using the same `HttpMethod` and pattern it handed `Router.builder()`. It does not read the router (Option D).
- **Storage:** a private `LinkedHashMap`, key `METHOD\u0000path` (NUL cannot appear in a method token or path, so the composite is collision-free).
- **Refusal:** a second `add` for one `(method, path)` throws and names both summaries — a spec listing one path twice is a spec that lies.
- **Output:** `operations()` sorted by path then method, wrapped in `List.copyOf`. The sort lives here, not on insertion order, because 2.14's determinism ("same set, same document, whatever the assembly order") starts at the metadata source; freezing the list is what the isolation gate measures — mutating a snapshot must not reach the plugin.
- **Must not do here:** generate a document, serve `/openapi.json`, name JSON, know OpenAPI's grammar, render HTML. That is 2.14's surface and module; the javadoc says so where someone would otherwise add a convenience method.

`OperationMetadata` is a record validated at construction — path non-blank starting with `/`, summary non-blank, tags non-null non-blank and copied. A malformed operation is refused where written, not where a later generator would crash.

### 5. Lifecycle: no second system, asserted

No `install()`, no `PluginRuntime`, no `PluginContext`. `Plugin extends LifecycleParticipant` (ADR-0016) and `LifecycleOrchestrator` drives the four phases — and 2.13 *tests* that it does (`PluginCompositionTest`):

- `everyPluginDrivenThroughEveryPhase` records initialize→start→stop per participant, in registration order, reversed on stop. Skipping stop is mutation M4's anchor; caught.
- `failedStartupRollsBackAndIsNotSwallowed` asserts a throwing `initialize()` surfaces as `StartupException` naming the participant and cause, the entered set rolled back exactly once, and a downstream plugin never touched. Swallowing the exception is mutation M5's anchor; caught.
- `realPluginsBootAndShutDown` runs the actual triple (user, auth, openapi) through one orchestrator on real H2: create a row, issue a session, authenticate a returning request, consult `Authorization`. One lifecycle engine, no plugin-specific framework path.

### 6. The rules that make direction a build fact

`BarezenPluginsArchitectureTest` ships seven rules over the whole `com.barezen` namespace:

1. `the_framework_does_not_know_plugins_exist` — nothing outside `com.barezen.plugins..` may depend inside it. This is `Security ← SPI ← User Plugin` made permanent; a framework change naming a plugin class fails the build.
2. `plugins_do_not_name_each_others_concrete_types` — a plugin package may not depend on a concrete `*Plugin` class in a *sibling* package. `PasswordVerifier` (interface) passes; `UserPlugin` (class) fails. Mechanical form of "plugin A cannot mutate plugin B's state": you cannot reach a neighbour's internals if you cannot name its type.
3. `no_sql_or_driver_reaches_into_a_plugin` — `java.sql.*`, `javax.sql.*`, and driver namespaces are forbidden, with one *measured* exception, `java.sql.ResultSet`, because `Database.query` hands one to the caller's `RowMapper` as its public read seam (ADR-0038's own signature). A smuggled `Connection`, `PreparedStatement`, or `DataSource` still fails.
4. `no_thread_local` — ADR-0034's rule continues up the stack.
5. `no_system_property_or_environment_reads` — every plugin parameter is a constructor argument.
6. `no_static_mutable_state` — mutable state lives in the instance; static-final descriptors stay legal.
7. `no_adapter_dependency` — plugins use the web contract, never a server (ADR-0018 §12).

Each rule was proven to bite by two temporary violating classes — `SpikeProbe` (ThreadLocal, `System.getProperty`, a static mutable field, a `javax.sql.DataSource` field, a call through `UserPlugin`'s concrete type, `com.sun.net.httpserver.HttpServer.create`) and `PluginNameProbe` (a non-plugin class naming `UserPlugin`) — observing **all seven fire (counts 2/2/1/2/1/1/1)**, then deleting both and re-running green. Rule 2 needed a real fix the spike discovered: `noClasses().should(customCondition)` inverted the events of a condition that reports its own violations, so the correct form is `classes().should(...)` — recorded because measurement, not review, found it.

## Consequences

- A plugin-specific type in security is now a build failure, not a review item; the required direction (`plugins → security`) is unchanged.
- `PasswordVerifier` staying plugin-local means an external login verifier must today implement a plugins-module interface — the honest cost of not putting an unexercised port in the framework.
- `UserPlugin.initialize()` runs `CREATE TABLE IF NOT EXISTS` on its own `Database`, so the plugin needs a `DataSource` at construction (fits ADR-0016). A versioned multi-plugin schema would move this to `Migrator` and ADR-0040's history table; that path exists and reopens this choice, it is not blocked.
- The OpenAPI plugin serves one caller today (2.14's generator); everything else is deferred rather than half-built.
- The plugins test classpath is the reactor's widest, which is what lets rule 1 read security and plugins in one import graph.

## Security implications

- Direction rule 1 means the framework cannot be bent to trust a specific plugin's principal; identity still flows through ADR-0033's three states, unchanged.
- The `authenticate`/`issueSession` split keeps a plaintext password out of `Credential` entirely — no delimiter to get wrong, no field that could be logged by a `toString` built for bearer tokens.
- Failed logins are uniform empties. No user row is written on the reject path; an unknown username is still
  verified against a dummy hash so the password-hash step is not skipped (tested with a counting port).
- Snapshots and `List.copyOf` returns mean no holder — a sibling plugin, a handler, a test — can mutate another's state through an accessor.
- No static mutable state and no thread-local close the two leaks by which one request's identity reaches another.

## Testing

All green on this build:

- `AuthPluginTest` 8, `UserPluginTest` 13, `OpenApiPluginTest` 11, `PluginCompositionTest` 10 (Stage 2.13-F: registration-as-gate 4, lifecycle 3, isolation 3), `BarezenPluginsArchitectureTest` 7 (spike-proven), `ClassMajorContractTest` 4 (major 61 on both JDKs).
- Stage 2.13-G: 6 mutations (M1 duplicate-ID accepted, M2 dependency check bypassed, M3 skip registration, M4 stop skipped, M5 plugin exception swallowed, M6 plugin state leaked), each killed by a targeted run, each source restored byte-identical (sha256). GATE PASS.

## Non-goals

- No document generation, no `/openapi.json`, no HTML (2.14's module).
- No route enumeration added to the web contract for a doc tool (ADR-0019).
- No `PluginContext`, no plugin lookup, no second lifecycle (ADR-0016, ADR-0041).
- No external plugin loading (ADR-0016 §7 discovery refusal stands).
- No promoting `PasswordVerifier` to security until a non-plugin consumer exists.

## Reopening trigger

- A login verifier or authorization source *outside* `com.barezen.plugins` arrives: promote `PasswordVerifier` to `com.barezen.security` with a migration note.
- 2.14 needs route enumeration the collector cannot provide: reopen Option D against the web contract's one-question rule rather than quietly widening `Router`.
- A second `ResultSet`-adjacent JDBC need appears in a plugin: that is ADR-0038's seam being wrong, not this rule being too tight — fix the data boundary, don't widen rule 3.
- A plugin schema grows beyond one restart-idempotent file: move `UserPlugin`'s DDL to `Migrator` and ADR-0040.

## Related Decisions

- ADR-0016 — the SPI; these three plugins are its first real consumers.
- ADR-0041 — the registration verifier that makes the id-declared edge enforceable before assembly.
- ADR-0033/0034/0036/0037 — the security ports and disclosure rules the plugins implement without altering.
- ADR-0038 — the driver-free boundary rule 3 continues up the stack; its `RowMapper` is rule 3's one measured exception.
