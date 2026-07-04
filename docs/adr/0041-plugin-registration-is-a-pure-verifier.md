# ADR-0041: Plugin Registration Is a Pure Verifier With No Powers; It Refuses Duplicate Ids, Absent Dependencies, and Cycles, and Otherwise Leaves Ordering to the Assembler

## Status

accepted
Date: 2026-10-04
Decides: who verifies `PluginDescriptor.dependencies()`, where that verification lives, what it may hold and do, and what "an ordered plugin set" means when the declarations do not decide the order
Related: ADR-0016 (the plugin SPI this amends), ADR-0003 (one lifecycle engine, ordering is the assembler's), ADR-0006 (no service locator), ADR-0015 (the orchestrator drives objects it did not create), ADR-0004 (all-or-nothing failure reporting), ADR-0042 (who may name whom once a plugin set is validated)

## Refines

ADR-0016 §4 ("Dependencies are data; the application acts on them") — that decision's *refusals* stand unchanged: no registry, no resolver that holds instances, no lookup surface, no second lifecycle. Its *assignment* of the verification job ("nobody, until a later phase") is exercised by this record: Phase 2.13 is the later phase, the gap §4 recorded as a known limitation is now filled, and it is filled with the smallest shape §4 itself projected ("a `PluginOrdering`-style function ... which can read every descriptor at once"), narrowed to a pure static function rather than an application-layer type because there is no `barezen-app` module to hold one.

## Context

`PluginDescriptor.dependencies()` is a `List<String>` ADR-0016 shipped with one stated consumer: "the application, when it decides the order it registers participants in." That sentence has always been a promise with no mechanism behind it, and ADR-0016's own Consequences section says so plainly — *"An application that registers `user` before `auth` despite the declaration gets a plugin that fails in `initialize()`, which is a startup failure with a stack trace."* Wrong order surfaces loudly, but it surfaces as an unrelated crash inside one plugin's setup, after half the application has already been constructed and registered.

Phase 2.13 assembles the first real plugin set (auth, user, openapi) where the declaration carries actual weight: `AuthPlugin`'s constructor receives the user plugin through the `PasswordVerifier` port, and ADR-0042 forbids the auth plugin from learning its collaborator by any other route. Three failure modes are now *silent until runtime*, and the phase's mutation list names them:

1. a registration that simply never calls the verifier — the declarations go unread and the app boots in whatever order the caller typed;
2. two plugins claiming one id, which ADR-0016 left to `LifecycleOrchestrator.register()` — a refusal that arrives after half an application is assembled, and which reports only the second claimer;
3. a declared dependency that nothing provides, or a chain that closes on itself.

The honest design question is not "should these be caught" (the phase's mutation gates demand they are) but **what shape of code catches them without becoming the thing ADR-0016 refused**. A dependency verifier is the exact point where a plugin system turns into a container, because the useful-looking next feature of every verifier is a lookup.

## Problem

Give the framework dependency-graph truth without giving it power. Concretely, the design must answer:

- where the check lives (which package, which module) so that it can see the SPI but nothing sees it back;
- what it does with the instances it is handed — hold them, expose them, run them?
- what an *ordered result* is owed when the declarations under-determine it;
- when a refusal happens — before assembly or during registration;
- how "the check was actually wired" is measurable, since an un-called verifier is precisely failure mode 1.

## Options Considered

**A — no change: leave ordering to the assembler and trust the declaration.** ADR-0016's literal §4 read. Refused: it makes the declared dependency a *comment*. The phase's gate "dependency check bypassed" cannot be phrased at all if nothing checks, and ADR-0016's own consequence ("fails in initialize() with a stack trace") is a worse error message than one naming both ids: `AuthPlugin`'s initialize does not read the graph, so a wrong order surfaces as a `NullPointerException` in whoever's collaborator was null first.

**B — a `PluginRegistry` in core holding instances, with lookups (`byId`, `dependentsOf`).** Refused by ADR-0016 §9 and by three existing core ArchUnit rules; refused here additionally on its observable behaviour: a registry that answers `lookup("user")` during startup *is* the service locator, one `get` call at a time. Nothing about validating a declaration requires holding instances after the call returns.

**C — ordering inside `LifecycleOrchestrator.register()` (topological insertion at register time).** Refused: it makes the lifecycle engine a graph library, violates ADR-0003's "registration order is startup order — deciding that order belongs to the assembler", and would reorder participants that never opted in. The orchestrator drives objects; it must not second-guess the order it was handed.

**D — per-plugin `void initialize(PluginRegistry)`-style self-declaration.** Refused by ADR-0016 §5 (no context parameter, no lookup surface) and by ADR-0042's own isolation rule — a plugin handed the set can mutate a neighbour's registration.

**E — a pure static function over the whole set (chosen).** One call in, one ordered list out; nothing stored, nothing run, nothing reachable afterwards.

## Decision

`com.barezen.core.plugin.PluginRegistration` — one public type, one public method, no state:

```java
public final class PluginRegistration {
  private PluginRegistration() {}
  public static List<Plugin> validate(List<Plugin> plugins);
}
```

### 1. What it refuses, and where the refusal is reported

| Refusal | Message names | Why all-or-nothing |
| --- | --- | --- |
| duplicate id | *both* implementation classes and the claimed id | one id carries registration, failure reports and rollback (ADR-0004); two owners of one name cannot be told apart afterwards |
| missing dependency | the plugin and the absent id | a declared dependency nobody provides is an optional dependency wearing a false name; the alternative was a boot that continues and crashes inside `initialize()` of an innocent third plugin |
| cycle | every plugin id stuck in the unplaceable set | "which two?" has no honest answer — the set as a whole is what no order satisfies, so the message reports the whole set |
| null list / null entry / null descriptor | the offending position or implementation class | the refusal report is built from ids, and a null has none |

Self-dependencies and blank ids are refused earlier, inside `PluginDescriptor`'s constructor (ADR-0016 §3) — this verifier does not re-implement them and its tests assert the *ordering* of those two refusals (the descriptor fires first).

### 2. What it returns, and what the returned order means

Every plugin appears after everything it transitively depends on, **and unrelated plugins keep the caller's relative order**. The second clause is the amendment's load-bearing part. The first draft of this verifier treated "two independent plugins could go either way" as an ambiguity to be refused, and sorted ties by name to make the output canonical. That was wrong on two counts, recorded here so nobody re-invents it:

- ADR-0003/0016 assigned ordering to the assembler *twice over* — "registration order is startup order" and "deciding that order belongs to the assembler." A verifier that re-sorts ties overrules the assignment; an application that registered `openapi` before `auth` on purpose (say, a route-ordering concern no dependency expresses) would silently get the opposite.
- Refusing ambiguity would push real deployments toward *declaring* edges that don't exist just to pin the order, and phantom dependencies are exactly the metadata lie this ADR's three refusals exist to catch. The guarantee the phase needs is "every declared edge is honoured," not "every pair has a defined order."

The test `diamondOrdersByConstraintNotByName` pins the consequence: input `[d, c, b, a]` over the diamond a←(c,b), c←d... yields `[a, c, b, d]` — constraint-satisfying, caller-preserving, *not* the alphabetical list a sorting implementation would emit. `callerOrderSurvivesBetweenUnrelatedPlugins` pins the unrelated-pair case directly.

### 3. Powers: none, and that is a tested property

`validate` does not register, store, return-a-handle-to, or execute anything. `theVerifierDoesNotRunAnything` drives a probe whose four lifecycle methods record their calls and asserts the recording stays empty across a full validate. `noSecondLifecycleSurface` asserts the type's shape by reflection: every method static, the constructor private, no fields, and `PluginRegistration` is not itself a `LifecycleParticipant` — the fence that keeps `com.barezen.core.plugin` from depending on `kernel` (ADR-0016 §2) could not survive an ordering helper that *participated*.

The returned list is `List.copyOf(...)`: a caller that mutates its ordered result cannot reach anything, because there is nothing left to reach (see ADR-0042 §4 for the same argument one layer up).

### 4. Who is required to call it — and how that requirement is measured

The framework cannot compel an application's assembly code to call anything, and pretending otherwise would be this ADR's own metadata lie. The wiring is enforced at the level where it is checkable:

- `PluginCompositionTest` (this phase's integration layer) builds its orchestrator *exclusively* through `validate` → `register(descriptor().id(), plugin)`, so the composition the examples and 2.15+ will copy has the verifier in its spine;
- the same test pins what happens when the check is bypassed anyway — `LifecycleOrchestrator.register()`'s own duplicate-id refusal is asserted as the *second* line of a two-line defence (validate catches the collision before assembly, `register()` catches it after);
- Stage 2.13-G's mutation M3 is the mechanical answer to failure mode 1: replacing `validate`'s body with "return the input list" must turn the core suite red, and it does (`dependencyPrecedesDependent`, `transitiveChainHolds`, `diamondOrdersByConstraintNotByName` and the three refusal tests all fall).

## Consequences

- Declared dependency edges stop being comments the moment this function is called: a wrong-order or missing-plugin assembly now fails *before* any plugin is constructed badly, with both names in the message.
- The consequence ADR-0016 predicted ("a plugin that registers user before auth gets a stack trace") is superseded by a refusal that names the edge.
- The verifier can validate a set it will never start: an application can check a candidate plugin list at assembly time, print the order, and hand it to the orchestrator, because validation holds nothing.
- Core gains one public type whose entire javadoc is about *not* becoming a registry — which is also the honest place to record what would make a registry necessary (see Reopening trigger).
- `PluginRegistration` is subject to the existing `com.barezen.core.plugin` fence rules unchanged: it touches `Plugin` and `PluginDescriptor` and no type in `di`, `config`, `concurrent`, or `kernel`. The plugins-module rule `the_framework_does_not_know_plugins_exist` (ADR-0042) additionally guarantees nothing downstream can grow the call sites; only assembly code calls this, and assembly code is the application's.
- Ordering ambiguity is no longer a refusal, so the "caller's order beats the declarations between unrelated plugins" case is now a *guarantee to test* rather than an error to report; both tests above exist because the design changed direction mid-phase and the old test was the evidence.

## Security implications

- The duplicate-id refusal closes an identity-confusion hole at assembly: a second plugin claiming `auth` would otherwise receive the first one's failure reports, rollback, and (in a UI that lists participants) its name.
- Cycle and missing-dependency refusals are availability-adjacent rather than confidentiality-relevant: both are boot-time refusals whose alternative was a partial boot — some plugins initialized, their `stop()`s run in a rollback order derived from a graph the declarations denied.
- Holding no instances means holding nothing to leak: no credential store, no session store, and no user table passes through `validate`, and none of them survives the call inside this type's state — because its state is the absence of state.
- The returned list is unmodifiable, so a component that receives the ordered set cannot re-order it before the orchestrator reads it — the list a reviewer inspected is the list that registers.

## Testing

Counts as run on this build, all green:

- `PluginRegistrationTest` — 15 tests in core: Registrable (8: empty set, single plugin, dependency-before-dependent, transitive chain, the diamond pinned by constraint, caller order between unrelated plugins, unmodifiable result, no-lifecycle-execution probe), Refusals (4: duplicate, missing, cycle, self-declared-earlier), InputShape (2: nulls, null descriptor), PackageDiscipline (1: reflection over the type's own shape).
- `PluginCompositionTest` — 10 tests (Stage 2.13-F): the edge drives registration order (3+1 in Registration), lifecycle completeness incl. rollback-not-swallowed (3 in Lifecycle), isolation by port-only reach and snapshot immutability (3 in Isolation), plus the two-line duplicate defence.
- `BarezenPluginsArchitectureTest` — 7 rules, each proven to bite by two temporarily-added violating main classes (`SpikeProbe`, `PluginNameProbe`), observed violation counts 2/2/1/2/1/1/1 across the seven rules, both spikes then deleted and the suite re-run green.
- Stage 2.13-G — 6 mutations over the phase's named failure modes (skip registration, duplicate ID accepted, dependency check bypassed, stop skipped, plugin exception swallowed, plugin state leaked); each killed by a targeted test run and each source restored byte-identical (sha256 verified). Full table in the phase report.

## Non-goals

- **No versioned dependencies.** Still refused for ADR-0016 §3's reason: there is no constraint and no solver; this record adds neither.
- **No optional dependencies.** `validate` answers every declaration with "provided or refused," which is the semantic a null-valued optional dependency would erase.
- **No discovery** (`ServiceLoader`, scanning). Unchanged from ADR-0016 §7.
- **No contribution mechanism.** `validate` orders *plugins*; it does not mediate what they contribute. When `barezen-web` grows a real extension point, that phase declares its own rules (ADR-0016 §6), and nothing in this record pre-shapes them.
- **No enforcement of the call.** Framework assembly code that skips `validate` fails its own tests, not a framework assertion; that is the ceiling for an API this shape, and §4 says so instead of hiding it.

## Reopening trigger

Revisit when any of the following is true, each of which changes the answer rather than the effort:

1. **A second caller of the same set appears** (e.g. 2.14's document generator wanting the plugin list): at that point `List<Plugin>` + `validate` may want the ordered result *cached*, and a cached-ordered-set-with-lookup is the registry this record refuses — the right move then is to re-derive ordering per call (cheap, side-effect-free) or to move the list behind an explicit application-owned value, not to give `PluginRegistration` a field.
2. **Plugins arrive at runtime** (a hot-add story): validate-over-a-list assumes the whole set at once, exactly as designed; incremental re-validation has different correctness needs and different failure reporting, and should re-open the whole shape.
3. **Version ranges or optional edges gain a real consumer**: the descriptor fields, not this function, are the ones that change first.

## Related Decisions

- ADR-0016 — the record amended; its §4 gap is what this fills, its §5/§6/§9 refusals are the constraints this stays inside.
- ADR-0042 — the direction rules (who may name whom) that make "validate a set" a meaningful boundary instead of a convenience; the sibling-type rule that keeps a validated set from mutating itself.
- ADR-0004 — rollback and failure reporting are per-participant-id, which is why a duplicate id is a refusal rather than a last-write-wins.
- ADR-0003/0015 — the ordering assignment this verifier honours rather than takes.
