# ADR-0047: An Extension Point Is an Interface the Owning Component Declares, and the First One Contributes Routes to the Table Under Construction

## Status

accepted
Date: 2026-10-06
Decides: what an extension point is in this framework, which package may declare one, what a contributor receives, who runs the contributions and when, where the `Plugin`-to-contributor narrowing lives, and what a contribution may not do
Related: ADR-0016 §6 (the forward sentence this discharges), ADR-0018 §12/§16 (the zero-dependency contract), ADR-0019 §9/§13/§19 (duplicate refusal, the frozen table, the refused name list), ADR-0025 §5 (discovery is not the contract's business), ADR-0041 (the order contributions are applied in), ADR-0042 (who may name whom), ADR-0006 (no service locator), ADR-0037 (default-deny still applies to a contributed route)

## Refines

ADR-0016 §6 refused a generic `ExtensionPoint` token and an `ExtensionRegistry` in core, and closed with a promise: *"the extension mechanism ships when the first extension point does, in the phase that owns it, where the owning component can state its own rules (routes: unique paths, reject duplicates; handlers: append-only, ordered)."* This record is that phase for routes. Every refusal §6 made stands — there is still no token type, no registry, no `Map<String, List<Object>>`, and no `contribute(ExtensionPoint<T>, T)` on `Plugin`. What §6 left unspecified is *whose* interface an extension point is, and the answer was forced by a boundary §6 did not weigh: `barezen-web` ships a jar that declares no compile dependency at all, so the route table's owner can name neither a core token nor a plugin.

It also refines ADR-0016's Consequences entry *"A plugin that needs the router needs it as a constructor parameter ... Phase 3 will feel this as more wiring in one place."* For routes that wiring is gone: a contributor declares routes instead of holding a builder, so the sequence builder → construct plugins → `orchestrator.start()` → `build()` → adapter collapses to builder → contribute → build → start.

## Context

The 2.x line shipped a `Plugin` that can name itself (ADR-0016) and a function that can order plugins (ADR-0041), and shipped nothing a plugin can *do*. README's first sentence says "plugin-based"; the module table says "Discovery, Extension Points — DEFERRED". Both were honest, and the gap is the difference between a plugin as a named lifecycle bean and a plugin as an extension.

Two concrete costs had already appeared in the code:

- The only demonstration of a plugin registering routes was a test-local class holding a `Router.Builder` injected by its constructor and contributing inside `initialize()` (`RouterPluginIntegrationTest`). That shape forces the five-step sequence above, and nothing in the framework helps with it.
- A route-owning plugin's endpoints are invisible to everything else. `OpenApiPlugin` collects metadata the application typed in by hand because `Router` answers one question and ADR-0042 refused to widen it — so routes get written twice.

The shape of the fix was decided before it was designed, by three rules that were already in the build:

| Rule | What it forbids here |
| --- | --- |
| `the_contract_does_not_reach_into_core` + `barezen-web`'s zero compile dependencies | web declaring or receiving a core `ExtensionPoint<T>` token |
| `no_core_type_depends_on_the_plugin_spi` | core holding, running or registering a contribution |
| `no_preemptive_routing_or_middleware_types` (names `Route`, `RouteRegistry`, `RouteParameterMap`, `Middleware`, `Filter`, `Interceptor`) | a route-list contribution, a merge step, or a middleware chain |

## Problem

- Who declares the extension point, given that the owner of the thing contributed to cannot name the framework's plugin concept?
- What does a contributor receive — a noun (a route list) or the operation (the builder)?
- Who runs the contributions, and at what moment relative to `build()` and to the lifecycle?
- What happens when two contributors want one route?
- Where may the one `instanceof` that separates contributors from other plugins sit?
- How is this kept from becoming discovery, a registry, or a lookup — all three of which are later phases or refused outright?

## Options Considered

**A — `ExtensionPoint<T>` token in `com.barezen.core.plugin`, plus `Plugin.contribute(point, item)`.** Refused on the first compile attempt: the component that owns routes is in a module that cannot import it, so the token could only be declared *about* routing by somebody other than routing's owner — which is the registry ADR-0016 §6 refused, wearing a generic type parameter. It would also put a contribution API on `Plugin`, and `Plugin` is a leaf by rule.

**B — `PluginContext` carrying `contribute(...)`.** Refused by ADR-0006, ADR-0013, ADR-0015 and ADR-0016 §5, all of which are about this object specifically. A context is a lookup with a friendlier face, and the first method a caller adds to it is `require(Router.class)`.

**C — give `Router` a route-enumeration answer and merge plugin tables into the application's.** Refused: `Router` answers `match` and `methodsFor` and nothing else (ADR-0019 §13, §19, enforced by `the_route_table_exposes_only_the_questions_it_answers`), and the merge step is a `RouteRegistry`, which the refused-name list rejects by name. This is the honest fix for the OpenAPI double-write, and it stays refused — recorded below as a deferred item with its trigger rather than smuggled in as a side effect.

**D — `@Route` annotations on a plugin's methods, scanned at startup.** Refused by ADR-0018 §13 and by `the_contract_declares_and_reads_no_annotations`: an annotation is a contract with a scanner attached, and the scanner is the discovery this phase is explicitly not doing.

**E — chosen: the owner declares one interface, the contributor receives the owner's own operation, and the framework's contract runs the list.** No token, no context, no registry, no annotation, no enumeration. The contribution target is `Router.Builder` — the type the application already holds — so a contribution buys no power a hand-written registration does not have.

## Decision

### 1. `com.barezen.web.RouteContributor` is the tenth contracted type

```java
@FunctionalInterface
public interface RouteContributor {
  void contributeRoutes(Router.Builder routes);
}
```

One method, taking the table under construction. `the_contract_is_exactly_the_nine_contracted_types` becomes `the_contract_is_exactly_the_ten_contracted_types`, amended the same way 2.8 (five→seven) and 2.9 (seven→nine) amended it: the consumer exists before the type does — `Router.Builder.contributing` takes the list, `RouteContributorTest` and `RouteContributionsTest` call it, and the JDK adapter serves the built table. The old rule name is cited in ADR-0021 and ADR-0025; those records are not rewritten, and the rename is recorded here rather than there.

### 2. `Router.Builder.contributing(Iterable<? extends RouteContributor>)` is the runner

```java
public Builder contributing(Iterable<? extends RouteContributor> contributors)
```

It applies each contributor in iteration order and returns `this`. It is not a third way to register a route — its body is the same `route` authority reached over a list — which is why `the_route_table_exposes_only_the_questions_it_answers` grows one name while the family it exists to refuse (`size`, `routes`, `replace`, `reload`, `unregister`) stays absent. Nulls are refused rather than read as empty: a null list would mean an application silently lost a plugin's endpoints.

The alternative was leaving the loop to every application, which puts an `instanceof` and a cast in the hands of the framework's consumers and gives them a style guide instead of an API.

### 3. The narrowing sits in the plugin namespace

```java
public static List<RouteContributor> of(Iterable<? extends Plugin> plugins)
```

`com.barezen.plugins.routes.RouteContributions.of` is the one `instanceof` in the mechanism, and the only place allowed to hold `Plugin` and `RouteContributor` in a single expression: core cannot name web, web cannot name core, and the framework must not name a plugin (ADR-0042 rule ①). It is a pure function on ADR-0041's model — no state, no registry, no lookup, no lifecycle phase; it returns an immutable list in the caller's order and runs nothing. An application's whole assembly is then:

```java
Router routes = Router.builder()
    .route(HttpMethod.GET, "/health", healthHandler)
    .contributing(RouteContributions.of(PluginRegistration.validate(plugins)))
    .build();
```

### 4. A contribution is assembly-time work, not a phase

Contributions are applied before `build()` and before `LifecycleOrchestrator.start()`. `Plugin`'s four phases are unchanged, `LifecycleParticipant` is unchanged, and there is no `install()` — ADR-0016 §2's reason holds: an `install` phase is `initialize()` with two names and two orders. Because the moment is assembly, a contributor must not start a thread or open a socket: nothing owns a contribution and nothing will stop it (ADR-0003, ADR-0019 §14).

### 5. Collisions fail the assembly; order buys nothing

`Router.Builder.refuseAmbiguous` is the existing refusal (ADR-0019 §9) and it applies unchanged to contributed routes, including contributor-against-contributor. There is no precedence, weight, namespace or priority — two tests pin both orderings of the same collision and both fail, which is the observable difference between "the later plugin wins" and an assembly that says which two plugins disagree.

### 6. What is deliberately not here

Discovery (3.2), per-route security policy, route enumeration, a second extension point, and any change to `Plugin`, `PluginDescriptor`, `PluginRegistration` or `LifecycleOrchestrator`. The framework still has no idea that any of this is a plugin.

## Consequences

- A plugin that owns endpoints no longer needs a `Router.Builder` in its constructor, so ADR-0016's "Phase 3 will feel this as more wiring in one place" is discharged for routing specifically. ADR-0016 §6's forward note is discharged too, one extension point at a time.
- `barezen-web` still ships a jar with zero compile dependencies — verified by the build, not asserted: the new type and the new method name only `java.util`, `java.lang` and `com.barezen.web`.
- `com.barezen.core.plugin` is still a leaf and the plugin SPI is still two types plus `PluginRegistration`; nothing in core names a contributor.
- The mechanism has one real consumer today and it is an application's assembly code plus `RouteContributions`. The three shipped plugins are **not** contributors (`ShippedPlugins.noneOfThePhase213PluginsIsARouteContributor` states this at the type level, because it is true and because inventing a route-shipping plugin to make the feature look used would be this ADR writing its own fiction).
- Contributions are unversioned and unfiltered: any contributor may register any method and path the application itself could. That is the intended equality, and the collision refusal is what makes it safe to allow.
- A contributed route is **not** automatically protected. `SecurityMiddleware` decorates a handler the application wraps (ADR-0033), so a plugin that hands over a bare `Handler` hands over a public endpoint, exactly as the application would if it registered one. See Security implications.
- The OpenAPI double-write remains: routes still get declared twice, once as a route and once as `OperationMetadata`, because the fix is enumeration (option C) and it stays refused. Named here so the reader of README's "plugin-based" line knows which half closed and which did not.
- `docs/adr/README.md`, README's module table (`Extension Points` moves from the DEFERRED row), README's Layout and `CHANGELOG.md` are updated by this phase; ADR-0016/0021/0025/0042 are not rewritten.

## Security implications

- **Nothing can be re-routed after start.** A table is copied at `build()` (ADR-0019 §13), so a contributor that kept its builder cannot change the router an adapter is serving. The mutation probe for "contribution applied to nothing" and the test for a late contribution both confirm the frozen table.
- **A contribution cannot widen the attack surface silently.** Two contributors claiming one route abort the assembly with a message naming both patterns; there is no last-writer-wins path from a plugin to a handler the application registered.
- **Default-deny is unchanged but not enforced by this mechanism.** ADR-0037's refusal is about identity and permission on a request path; a route is a route. Wrapping a contributed handler is the application's composition step, and a plugin that wants its own routes protected must hold the `Authentication`/`Authorization` ports and decorate itself — which ADR-0042 already requires it to do through ports rather than by naming security internals. Attaching per-route policy to a contribution is deferred; its trigger is the first shipped plugin that owns a protected endpoint.
- No new logging, no new configuration key, no environment read, no thread-local, and no reflection anywhere in the phase. `barezen-web` still cannot name a logger.

## Testing

- `RouteContributorTest` — 16 tests in three nested groups: Contributions (5: a contributed route is answered, app and contributed routes share one table, path parameters capture, iteration order, contributing nothing is not an error), Refusals (7: contributor-vs-application collision, contributor-vs-contributor collision both ways, a null handler, a null list, a null element, a contribution after `build()`, an empty contribution adding no entry), Surface (4: one method taking `Router$Builder` returning `void`, an interface carrying only `@FunctionalInterface`, a lambda is a complete contributor, no parameter names a lifecycle type).
- `RouteContributionsTest` — 12 tests: Narrowing (6), Assembly (4, driven through `PluginRegistration.validate` and `LifecycleOrchestrator` so the ordering claim is executed rather than described), ShippedPlugins (2).
- `RouterTest.abuilderHasOneRegistrationMethodOneContributionMethodAndOneBuildMethod` replaces the two-method assertion; `BarezenWebArchitectureTest` passes at 23 rules.
- Module totals on this build: `barezen-web` 232 → 248, `barezen-plugins` 102 → 114, `examples` 25 → 26 (the 26th is
  `aPluginContributesARouteTheServerServes`, which serves the contributed route over a real socket through
  `TestServer`/`JdkHttpServerAdapter` and asserts a `POST` to it is 405), 0 failures, 0 errors.
- Full reactor on JDK 21: `mvn -o -B -ntp clean verify` → BUILD SUCCESS in 9:43, per module core 264 (1 skipped), web 248,
  http-jdk 200, security 99, data 64, plugins 114, testkit 77, examples 26 — **1092 tests, 0 failures, 0 errors**.
  `scripts/check-jdk-baseline.sh` and `scripts/check-class-major.sh` both exit 0, the latter reporting
  `checked=556 unique-majors=[61] expected=61`.
- Observed output of the example this phase changed, run outside Maven by the `java -cp` recipe in
  `docs/examples/README.md`: `startup order -> [user, openapi, auth, reports]` and
  `route table -> Router[GET /health, GET /reports/status]` — the second route exists because a plugin declared it, and
  both are in the frozen table before `app.start()` returns.
- **Architecture spikes**, each added, each proven to bite, each deleted: an eleventh public type in `com.barezen.web` fired `the_contract_is_exactly_the_ten_contracted_types` (violated 1 time, 23 tests 1 failure, build failed); a `Router$Builder.size()` fired `the_route_table_exposes_only_the_questions_it_answers` (violated 1 time, 23 tests 1 failure, build failed) — with the allowed set printed as `[builder, match, methodsFor, toString, route, contributing, build]`, so the amendment did not loosen the rule.
- **Mutations**, each killed and each restored byte-identical (md5 verified after every revert):

| ID | Mutation | Killer | Result |
| --- | --- | --- | --- |
| M1 | `contributing` iterates without applying the contributor | `RouteContributorTest` | 9 failures of 16, build failed |
| M2 | `of` drops the `instanceof` filter and casts every plugin | `onlyContributorsComeBackAndTheirOrderIsTheCallers`, `aPluginThatContributesNoRoutesIsSkippedRatherThanAnError`, `aNullElementIsRefusedRatherThanDropped` | 3 `ClassCastException` errors, build failed |
| M3 | `of` returns the list reversed | `onlyContributorsComeBackAndTheirOrderIsTheCallers` | 1 failure of 12, build failed |
| M4 | `of` skips a null element instead of refusing it | `aNullElementIsRefusedRatherThanDropped` | 1 failure of 12, build failed |
| M5 | `contributing` swallows a duplicate so a later plugin wins | `twoContributorsCannotClaimTheSameRouteEither`, `theContributionOrderDoesNotDecideTheWinnerBecauseBothFail` | 2 failures of 16, build failed |
- JDK: this phase was built and measured on JDK 21. JDK 17 is not installed in this environment (`/usr/lib/jvm` holds 8, 21, 24 and 25), so the JDK 17 half of the compatibility claim is **not** re-verified here, and `git remote -v` is empty so the CI matrix has never run. The emitted major is asserted inside the build by `ClassMajorContractTest`, which is what makes the 61 claim meaningful without a second JDK.

## Non-goals

Plugin discovery, an `ExtensionPoint` token, a `PluginContext`, `Plugin.contribute(...)`, route enumeration, a `Route` type, a route registry or merge step, middleware or filter chains, per-route security policy, contribution priority or weights, uninstalling a contributed route, and any second extension point.

## Reopening trigger

- **A second extension point** opens when a component other than the route table has a plugin that needs to contribute to it — the named candidate is the event bus, which has had no consumer since 2.6 (ADR-0017). Same shape: the owning package declares the interface.
- **Route enumeration** opens when the OpenAPI double-write costs a real project a wrong document — then `Router` grows a third answer and `no_preemptive_routing_or_middleware_types` gets amended with a test that requires it (option C, measured rather than preferred).
- **Discovery** is phase 3.2 and needs its own record against ADR-0016 §7 and ADR-0025 §5.
- **Per-route security policy** opens with the first shipped plugin that owns a protected endpoint.

## Related Decisions

ADR-0016 (the SPI this discharges a sentence of), ADR-0018 §12/§16 (why the token is impossible), ADR-0019 §9/§13/§19 (the refusal, the freeze, the refused names), ADR-0025 §5 (a seam may be a service interface without the contract searching for it), ADR-0041 (the order this phase consumes), ADR-0042 (the fence the narrowing sits on the right side of), ADR-0006 (what this phase must not become).
