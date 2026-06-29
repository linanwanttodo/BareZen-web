# ADR-0025: HTTP Adapter Provider — discovery, `id()`, `create(host, port, router)`, `ServiceLoader`, no lookup

Status: accepted
Date: 2026-09-29
Decides: that a server choice needs a discovery shape and what that shape may contain; the one contract type Phase 2.9
adds to `com.barezen.web`; why a provider is neither a plugin nor a service locator; that `ServiceLoader` registers and
selects nothing here; what `id()` names; what `create` validates and refuses to do; that no provider holds a server or a
mutable static; and that the `Provider` suffix is admitted under two new rules rather than by deleting the old one
Related: ADR-0006, ADR-0009, ADR-0014, ADR-0015, ADR-0016, ADR-0018, ADR-0019, ADR-0021, ADR-0024

## Context

ADR-0014 settled dependency injection: a service is registered by an explicit factory call, and the framework refuses
reflective discovery — no annotation scanning, no bean container, no classpath magic. ADR-0016 settled plugins: a plugin
is a `LifecycleParticipant` that can name itself, and the same decision refused a `PluginContext`, a registry, and any
discovery mechanism, because "a plugin that can be found is a plugin that can be started by somebody else's schedule".
ADR-0018 §12 then put the HTTP server outside the contract entirely: the contract names no server, and an adapter lives
in its own module.

Phase 2.9 shipped that adapter, and immediately created a tension the earlier decisions did not resolve. An application
that wants a server writes `new JdkHttpServerAdapter(address, router)` — which means its assembly code imports
`com.barezen.http.jdk`, which means the server choice has moved from a configuration value into source, and swapping the
adapter is a rewrite rather than a dependency change. That is precisely the coupling ADR-0018 §12 was written to keep
out of the contract, now reintroduced in the application.

The resolution cannot be a container. A framework that discovers an adapter and hands it out is one step from a
framework that decides which adapter to run, stores the running server, and starts it on a schedule of its own
choosing — which is ADR-0016's refused shape and ADR-0006's refused locator, arriving with a plausible reason this time.
So the shape had to be as small as possible while still being a real seam: one name, one creation, no lookup, no
storage, and no code in this framework that performs the search.

The phase question was: **what may a discovery shape contain so that naming a server is a configuration value without
making the framework a service locator?**

## The question, in parts

1. Why does an adapter need a discovery shape at all, when the assembly can call `new`? (§1)
2. What does the contract gain, and how many public types is that? (§2)
3. Why is a provider not a plugin? (§3)
4. Why is a provider not a service locator, and what does that cost the `Provider` suffix? (§4)
5. Who calls `ServiceLoader`, and why is it not this framework? (§5)
6. What is an `id`, and why is it a string rather than a class? (§6)
7. What does `create` take, validate, and refuse to do? (§7)
8. Can a provider be a singleton, or hold a server, or keep a mutable static? (§8)
9. What can a provider deliberately not do? (§9)
10. The `Provider` suffix was banned. What was amended, and what was not? (§10)

## Decision

`com.barezen.web` gains one interface, `HttpServerAdapterProvider`, with exactly two members:

```java
public interface HttpServerAdapterProvider {
  String id();
  HttpServerAdapter create(String host, int port, Router router);
}
```

`framework/barezen-http-jdk` implements it as `JdkHttpServerAdapterProvider` — a public final class with a public
no-argument constructor, one `static final String ID = "jdk"`, no other field, and registered in
`META-INF/services/com.barezen.web.HttpServerAdapterProvider` containing exactly one line. `create` validates its
arguments and returns a fresh, un-started `JdkHttpServerAdapter`; it stores nothing, binds nothing, starts nothing, and
cannot be asked for an object it was not handed. No class in either module calls `ServiceLoader.load`: the discovery
mechanism is the JDK's, the selection is the application assembly's, and what lives in the framework is the shape of the
answer, not the search.

### 1. Why a discovery shape exists when `new` already works

Because `new` works only where the class name is visible, and a class name in source is a build-time commitment. An
application that says «run the server called jdk» in a configuration file and receives `HttpServerAdapter` has made the
server choice a dependency and a string; an application that writes `new JdkHttpServerAdapter(...)` has made it a type,
and a type cannot be changed by the person deploying the application. The seam is worth having for that reason alone.

The seam must not become a policy engine, and the way to guarantee that is to make the interface incapable of
expressing one. `ServiceLoader` selection by priority, an `available()` list a framework iterates to choose the "best"
adapter, a `preferred()` flag — each of those would put a decision inside the framework that §5 insists belongs to the
assembly. So there is no such member, and there is no rule needed to refuse them later, because a provider cannot
recommend itself to anyone.

`the_module_exposes_exactly_two_public_types` in `BarezenHttpJdkArchitectureTest` is the counter that keeps this
honest: the adapter module publishes `JdkHttpServerAdapter` and `JdkHttpServerAdapterProvider` and nothing else, so the
discovery shape arrives as an implementation of an existing contract rather than as a new layer with its own vocabulary.

### 2. What the contract gains, and at what price in public surface

`HttpServerAdapterProvider` is the ninth type in `com.barezen.web` and the third public type added by Phase 2.9, after
`HttpServerAdapter` and `JdkHttpServerAdapter` (§2's count is the argument ADR-0018 §16 uses: a new contract type needs a
consumer that already exists, not one written for it).

The consumer does already exist. `the_contract_is_exactly_the_nine_contracted_types` was amended to count nine, and the
javadoc records the reason rather than the number: an application that wants to start a server needs a type to hold, and
a build that wants to name one needs a type to register against. `no_container_shaped_type_names` was amended at the
same time, in the minimal way, and §10 keeps that amendment company.

Nothing about the direction changed, which is the part worth stating because it is the part a reader will suspect.
`barezen-web` still declares no compile dependency: the provider interface mentions only `String`, `int`, `Router` and
`HttpServerAdapter`, all of them types already in the contract. `BarezenHttpJdkArchitectureTest` still proves
`core ← web ← adapter` in one direction: the adapter may know core and web, and web is allowed to know nothing. A
provider inside the contract that named a server type would have reversed the arrow, and `no_jdk_http_server` plus
probe A (ADR-0021 §3) would fail the build.

### 3. Why a provider is not a plugin

A `Plugin` (ADR-0016) has an `initialize()` phase, participates in startup and shutdown order, and can register
components. A provider has no phase, takes no part in any order, and registers nothing — its only effect is the object it
returns. The difference is not cosmetic: a plugin is started by `LifecycleOrchestrator`, so a plugin that created a
server would let the orchestrator's schedule decide when a port opens; a provider is called by whoever is assembling,
and the adapter it hands back is started by whoever holds it (ADR-0024 §2).

`an_adapter_is_not_a_plugin` is the mechanical form of that sentence: no class in `com.barezen.http..` implements
`com.barezen.core.plugin.Plugin`. The rule is written as an architecture rule rather than left to review because the two
shapes look alike in a file listing — a class with a name and a factory method — and the difference is that one of them
can be discovered and started by somebody else. An adapter is created by the application's assembly with an address and
a route table, not discovered and started by a plugin manager.

That the same module also holds `an_adapter_is_not_a_lifecycle_participant` (ADR-0024 §2) is not redundancy: refusing
the plugin interface and refusing the participant interface close two different doors. A type could implement
`LifecycleParticipant` without being a plugin, and that would still have handed the orchestrator a server to start on
its own schedule.

### 4. Why a provider is not a service locator — and what that costs the name

A locator answers «give me the thing of type T». This answers «build the adapter these arguments name». The difference is
enforced rather than asserted, in four places:

`a_provider_offers_only_an_id_and_a_creation` (web) reads every declared method of `HttpServerAdapterProvider` and
allows `id` and `create`, and nothing else. A third member is a lookup, and the failure message says so: «a provider
that can be asked for an object it was never handed is a service locator wearing a factory's name».

`the_provider_implementation_declares_nothing_else` (adapter) allows the implementation `id`, `create` and `toString`,
because discovery hands a human a candidate list and a summary that names the id is a diagnostic rather than an
operation. `theSummaryNamesTheIdAndNothingElse` is the test behind that allowance, and it asserts the exact value
`JdkHttpServerAdapterProvider[jdk]` while refusing `null`, `httpserver` and `Router` — a summary must not leak the
bridge's class names into a list an operator reads.

`a_provider_never_looks_anything_up` (adapter) refuses any dependency on a `*Locator`, a `*Registry`,
`com.barezen.core.di.ServiceAssembly`, `com.barezen.core.di.Services`, or `LifecycleOrchestrator`. A provider that
reached for the container or the orchestrator would be selecting rather than offering, and it would be selecting from
inside the framework, which §5 forbids.

`nothing_takes_a_type_or_a_key_to_find_an_object` (web, unchanged since ADR-0018) is the general rule, and it is a
closed allow-list rather than a blacklist: no method declared in `com.barezen.web` may take a `Class`, and none may carry
a lookup-shaped name from `service`, `require`, `find`, `get`, `lookup`, `resolve`, `instance`, `attribute`, `context`
and the rest of that list. It catches `require(Class)` and `lookup(String)` the moment somebody adds them. It does not
catch `available()` or `adapters()`, and §1's refusal of those rests on the method allow-list above instead — a
distinction worth stating, because a reader who assumes the general rule covers every locator shape will add a member
that the general rule misses.
`aProviderIsNotAServiceLocator` asserts the same thing through reflection, listing what the type does not have, because
a rule over declared methods and a test over `getMethods()` fail for different mistakes — the rule sees a new member
added to the interface, the test sees one inherited or defaulted in a way the rule's scope misses.

`JdkHttpServerAdapter` is created and then owned by the caller, which is the half a locator never gets right: whoever
calls `create` holds the adapter, closes it, and can see that exactly one lifecycle belongs to it (ADR-0024 §4). The
provider keeps no reference, so it cannot close the server it built, restart it, or hand it to a second caller.

### 5. Who calls `ServiceLoader`, and why it is not this framework

The registration file is the only mechanism, and it is inert until somebody reads it:

```
# framework/barezen-http-jdk/src/main/resources/META-INF/services/com.barezen.web.HttpServerAdapterProvider
com.barezen.http.jdk.JdkHttpServerAdapterProvider
```

Nothing in `barezen-web` calls `ServiceLoader` — `the_contract_never_reflects_loads_or_scans` (ADR-0018 §12) has refused
it since Phase 2.7, and correctly: choosing a runtime is composition, and composition is the application's (ADR-0015). Nothing
in the adapter module calls it either: `the_adapter_discovers_nothing_it_was_not_given` lists `java.util.ServiceLoader`
beside `java.lang.reflect..`, `java.lang.invoke..`, `java.net.URLClassLoader..` and `java.util.concurrent.atomic..`, so
an adapter cannot go looking for collaborators it was not handed.

That leaves discovery where the framework can only *discover* — provide a well-formed registration for the JDK's own
`ServiceLoader` to read — and never select. The application's assembly iterates, compares the ids it was configured
with, and calls `create` on one; the framework's contribution ends at the shape. `discoveryIsRepeatableAndCostsNothing`
shows the mechanism behaving: two loads hand out two distinct provider instances with the same id, and creating an
adapter from one leaves `isRunning()` false and `port()` empty, so listing the available adapters binds nothing.

### 6. What an id is

`"jdk"`. It describes an implementation family — the `com.sun.net.httpserver` server — not the JDK build that happens to
be executing. A version in the id would mean an application's configuration breaks when it moves from 17 to 21, which is
the opposite of what a capability-detecting framework promises (ADR-0002): the same `"jdk"` adapter runs requests on
virtual threads on 21 and on platform threads with the prefix `barezen-http-jdk-` on 17, and both facts are asserted in
`JdkHttpServerLifecycleTest.ThreadModel` without either appearing in a name an operator has to keep current.

`anIdIsStableAcrossInstances` asserts `id()` is the same on two instances and equals the literal, which is the property a
configuration value depends on. The id is deliberately **not** a registry key: there is no `forId(String)`, no
`byId(String)`, no map anywhere from a name to a provider. Adding one would have made the framework the thing that owns
the set of available servers, and §5 refuses that ownership. Selecting by id is the assembly's loop, and the assembly's
loop is a place where an application can also print a useful error naming what it found.

`JdkHttpServerAdapterProvider.ID` is a `private static final String`, and that is the only field the class has. It is
allowed by `no_provider_holds_a_server` because a string is not a server, which is the distinction the rule's javadoc
bothers to spell out.

### 7. What `create` takes, and what it refuses before the socket ever sees a value

`create(String host, int port, Router router)` — three arguments, and they are the three an application can write in a
configuration file. A thread provider, a logger, a shutdown policy and a backlog are not arguments, and the reason is
stated in §4's terms: a factory signature that grows with every collaborator the next phase invents has become an
assembly container, and an assembly container in the contract would be ADR-0015's rejected component back with a
factory's name. A caller that needs a borrowed `ThreadProvider` takes the adapter's own constructor overload
(ADR-0024 §4) — which is available and honest, because it names the thing it changes.

Validation happens at `create` rather than at `start`, on purpose:

- `null` host and `null` router are `NullPointerException`s with messages that name the consequence («an adapter with no
  routes answers 404 to everything, which is a misassembly rather than a running server»).
- A blank host is `IllegalArgumentException`, and the message carries the two strings the caller probably meant —
  `"0.0.0.0"` for every interface and `"127.0.0.1"` for loopback — because `""` is the value that silently means
  "bind everywhere" on some platforms, which is a security decision made by an omitted character rather than by the
  application. `aBlankHostIsRefusedRatherThanBoundToEverywhere` asserts both `""` and `"   "`.
- A port outside `0..65535` is `IllegalArgumentException` naming the offending value. Left alone it would reach
  `InetSocketAddress` as "port out of range" — a bind-time message about a number the configuration wrote. The
  provider's own message is the difference between «your config is wrong» and «the kernel refused you», and
  `aPortOutsideTheLegalRangeIsRefusedBeforeTheSocket` pins `-1`, `65536`, and the two legal edges.
- An unresolvable host is **not** a configuration error. `anUnknownHostNameIsNotAConfigurationError` asserts that
  `no-such-host.invalid` produces an adapter whose `start()` fails: DNS is a runtime condition, and a provider that
  pretended to know it would be doing I/O in a factory and reporting the wrong blame for a name that stopped resolving
  after deployment.

The address is built resolved (`new InetSocketAddress(host, port)`) rather than `createUnresolved`, because the JDK's
server refuses an address whose lookup never ran with `SocketException: Unresolved address`. The choice buys no error
handling — a host that does not exist lands in the same deferred failure — it only avoids inventing a message for the
common case.

Port 0 works through the provider exactly as it does through the constructor, which `createBuildsAnAdapterForTheHostAndPortNamed`
shows by starting the created adapter, reading the ephemeral port back from `port()`, and serving `GET /users/9` over a
real socket. The SPI inherits the rule from ADR-0021 §5: the port is an output, not an input.

### 8. No singleton, no stored server, no mutable static

`create` returns a new adapter every call. There is no `instance()`, no `getInstance()`, no `current()`, and no cached
adapter — which is what makes `twoCreatedAdaptersAreIndependentServers` possible: one provider, two `create` calls, two
distinct ephemeral ports, two servers answering concurrently, and stopping one leaves the other running. A provider that
secretly returned one shared server would give the same port twice, and the test would say so.

The rules behind it are three, and they cover the three ways a value can become global:

- `no_server_component_is_stored_by_the_contract` (web): no field of type `HttpServerAdapter` or
  `HttpServerAdapterProvider` anywhere in `com.barezen.web`. A contract type that stores the runtime it describes is a
  singleton holder, and a singleton holder is how a test ends up with a server it never asked for.
- `no_provider_holds_a_server` (adapter): the same refusal for the implementation, scoped to provider types, and written
  to allow the `static final String` id while refusing a server, a provider or a route table.
- `nothing_in_this_module_is_a_mutable_global` (adapter): every static field in `com.barezen.http..` is `final`. A
  mutable static would be a value one request could hand to the next without either asking, and that is the half a
  lifecycle monitor cannot protect.

`discoveryAloneHoldsNoAdapterAndStartsNothing` and `aProviderRegistersNoRoutesAndHoldsNoRouter` are the observable
versions: reading ids through discovery reaches no adapter, and no `Router` is held or answerable through a provider —
the route table can only arrive as a `create` argument, which is the only way one reaches an adapter at all.

### 9. What a provider cannot do

By construction, and each refusal has a rule or a test rather than a comment behind it:

- **It cannot choose a thread policy.** The policy belongs to the adapter's constructor overload (ADR-0024 §4); a
  provider that took a `ThreadPolicy` would have made discovery the place runtime characteristics are decided.
- **It cannot start a server.** `create` returns before any socket exists. `aStreamedProviderCanBeSkippedWithoutLoadingIt`
  is the sharpest form of this: `ServiceLoader.stream()` reports `type()` without instantiating, so a caller can list
  adapter class names without the provider class being loaded, let alone run.
- **It cannot register a route.** It holds no `Router` (§8), so it cannot add to one either; routing is assembled by the
  application and handed in (ADR-0019 §13, ADR-0016).
- **It cannot be where a running server lives.** No field of the refused types (§8), so the only reference to an adapter
  is the caller's.
- **It is public only because `ServiceLoader` requires it.** A discovered service needs a public type with a public no-argument
  constructor, and `theProviderTypeOffersNothingButAnIdAndACreation` asserts exactly one constructor exists. Every other
  type this phase introduced stayed package-private on purpose: the bridge, the dispatcher and the adapter's state
  machine are not extension points (ADR-0022 §9, ADR-0021 §4).

The last two items are the same claim seen from two sides, and they are worth keeping separate in prose: the provider's
*visibility* is a JDK requirement, and the *absence of capability* is this framework's choice.

### 10. The naming amendment, stated plainly

ADR-0018 §16 froze a vocabulary, and `no_container_shaped_type_names` grew out of it with a forbidden-suffix list ending
in `Provider`, `Factory`, `Registry`, `Container`, `Locator`, `Context`, `Manager`, `Session` and seventeen others —
twenty-five suffixes in all. Phase
2.9 needed a type ending in `Provider`.

The amendment is one entry in a list, and the important half is what did *not* change:

```java
List<String> providerNamesAllowedDespiteTheSuffix = java.util.List.of("HttpServerAdapterProvider");
```

`"Provider"` stays in `forbiddenSuffixes`. Deleting the suffix would have made the next `XProvider` type pass silently,
which is the failure mode the list was written against; exempting one name means every other `...Provider` in
`com.barezen.web` still violates the rule, and `the_contract_is_exactly_the_nine_contracted_types` still refuses a tenth
type. What polices the exempted name is not the exemption but the two rules written for it —
`a_provider_offers_only_an_id_and_a_creation` and `no_server_component_is_stored_by_the_contract` — so the name is
allowed while the *members* are what the design refuses. That is the honest form of the amendment: a factory-shaped
creator is exactly what an adapter needs, a locator is not, and the difference is a member list rather than a word.

Not amended: the frozen contract types themselves, the immutability rules (`every_field_is_final`,
`no_method_writes_or_mutates`), `no_jdk_http_server`, `no_server_or_web_framework`,
`the_contract_never_reflects_loads_or_scans`, and `nothing_takes_a_type_or_a_key_to_find_an_object`. Phase 2.9 also did
not touch `HttpServerAdapter`'s signature, and the `Handler` signature is unchanged since ADR-0020.

Two probes keep the amendment from being a rubber stamp:

- **A second `...Provider` type that keeps a lookup method.** A `com.barezen.web.TempLookupProvider` with
  `Object lookup(String)` failed three web rules — `no_container_shaped_type_names` (the suffix, for a type not on the
  exemption list), `nothing_takes_a_type_or_a_key_to_find_an_object` (`lookup` is on its name list), and
  `the_contract_is_exactly_the_nine_contracted_types` (a tenth type) — with 3 failures out of 215 tests and the build
  failed. The exemption did not generalize, which is the claim the paragraph above makes.
- **The registration file itself.** With `META-INF/services/…HttpServerAdapterProvider` removed and `target/classes`
  cleaned, six tests failed: `theJdkProviderIsFoundOnTheClasspath`, `theDiscoveredProviderIsTheJdkType`,
  `discoveryIsRepeatableAndCostsNothing`, `twoCreatedAdaptersAreIndependentServers` (three `NoSuchElementException`s from
  an empty `ServiceLoader`), plus `theServiceFileNamesOneImplementationOfTheContract` and
  `aStreamedProviderCanBeSkippedWithoutLoadingIt`. Discovery is tested as a mechanism that can be absent, not as a
  mechanism assumed to work. The first version of this probe passed for the wrong reason — a stale `target/classes/META-INF`
  from an earlier build — so the transcript names the `rm -rf target/classes/META-INF` step explicitly.

## Consequences

- **A server becomes a configuration value.** `jdk` in a config plus a classpath entry is now the whole story; the
  application assembly holds `HttpServerAdapter`, not `JdkHttpServerAdapter`.
- **The contract is nine types, and one of them is allowed a banned suffix.** The count and the exemption are both rules
  with named violations, so the next phase inherits the argument rather than a comment saying "nine".
- **Discovery may only discover.** Nothing in the framework calls `ServiceLoader`, and no provider selects, orders,
  prefers or caches. Any future "just pick a good server" convenience is a violation of a written rule, not a taste
  judgement.
- **A provider is stateless and cheap, and that is checkable.** Three field rules and one method rule make the singleton
  form impossible rather than discouraged.
- **`create`'s three arguments are a ceiling, not a starting point.** Adding a parameter is a contract change with a
  public-API consequence; callers needing more take the constructor.
- **Design debt carried forward, named rather than absorbed:** there is no `HttpServerAdapterProvider` selection helper,
  so every application writes its own loop over `ServiceLoader` and its own "unknown server id" error; there is no
  second provider to prove the loop generalises, so the SPI is exercised by exactly one implementation; and nothing binds
  a configuration key to an id, because a configuration system that chose a server would be a framework that chose a
  server. Each stays unwritten because nothing in this phase calls it, and ADR-0015 already says whose job it is.

## Alternatives rejected

**A `ServerRegistry` holding providers by id.** Rejected outright: a registry answers «what servers exist» and «give me
`jdk`», which is a locator with a discovery mechanism attached (ADR-0006, ADR-0014). The id would have become a key, and
a key implies a map, and a map implies something that keeps it.

**`Optional<HttpServerAdapter> createById(String id)` on the contract.** Rejected because it puts the search inside the
type being searched for, and because an `Optional` return invites the caller to run *no* server silently. The assembly
loop that §5 leaves to the application fails loudly, and it can name what it found.

**Annotation-based registration (`@ServerAdapter`, a scan for implementations).** Rejected by ADR-0014 and by
`the_contract_never_reflects_loads_or_scans`; it also needs a scanning dependency in a module whose entire dependency
posture is `java.base` (ADR-0018 §12).

**A `Plugin` that registers an adapter.** Rejected for §3's reason: a plugin participates in a lifecycle, so the
orchestrator would have owned a port's opening and closing, and ADR-0024 §2 spends a section on why that boundary is not
 negotiable. A discovered adapter that *also* registered itself would have been discovered twice.

**`preferred()` / a priority int on the provider.** Rejected because it moves selection from the assembly into the
framework while looking like a harmless comparator. Capability detection already has a home — ADR-0002, in core, where it
decides threads rather than servers — and a server chosen by self-declared preference is a server nobody configured.

**Dropping the `Provider` suffix instead of amending the rule.** Tempting and refused: names should say what a thing is,
and a type that offers an id and a creation for a runtime is what the word "provider" means in the JDK's own SPI
vocabulary (`ThreadProvider`, ADR-0011, already uses it). Renaming to dodge a lint rule would have taught the rule to lie
— the same argument ADR-0019 §20 used when it took `Router` back rather than calling it a `PathTable`.

**Making the provider a `LifecycleParticipant` so it could start the server it built.** Rejected twice over: ADR-0024 §2
refuses it for an adapter, and §4's ownership rule requires the caller to hold the created object. A participant that
creates and starts would have made `close()` ambiguous — does it close the provider, or the adapter, or both?

## Evidence

Test classes and what they assert:

| file | tests | what this phase ships |
| --- | --- | --- |
| `JdkHttpServerAdapterProviderTest$Discovery` | 4 | `ServiceLoader` finds `"jdk"` and only it; two loads give two instances; discovery starts nothing; two created adapters are two independent servers on two ports |
| `JdkHttpServerAdapterProviderTest$Contract` | 5 | the id is stable and equals `"jdk"`; the summary names the id and nothing else; `create` builds a working server including port 0; the interface declares exactly `id`/`create`; the implementation adds no locator vocabulary |
| `JdkHttpServerAdapterProviderTest$Arguments` | 4 | null host and null router are NPEs; blank host is refused with the two strings the caller meant; out-of-range ports are refused naming the value; an unknown host is not a configuration error |
| `JdkHttpServerAdapterProviderTest$Registration` | 3 | the service file names one implementation of the contract; a streamed provider can be skipped without loading it; this module's own registration loads without `ServiceConfigurationError` |
| `JdkHttpServerAdapterProviderTest$Limits` | 2 | reading ids holds no adapter and starts nothing; a provider registers no routes and holds no `Router` |
| `BarezenHttpJdkArchitectureTest` | 24 | `an_adapter_is_not_a_plugin`, `a_provider_never_looks_anything_up`, `the_provider_implementation_declares_nothing_else`, `no_provider_holds_a_server`, `nothing_in_this_module_is_a_mutable_global`, `the_adapter_discovers_nothing_it_was_not_given`, `the_module_exposes_exactly_two_public_types` |
| `BarezenWebArchitectureTest` | 23 | `a_provider_offers_only_an_id_and_a_creation`, `no_server_component_is_stored_by_the_contract`, `no_container_shaped_type_names` with the one-name exemption, `the_contract_is_exactly_the_nine_contracted_types` at nine, `nothing_takes_a_type_or_a_key_to_find_an_object` |

Mutation probes that fired:

| probe | change | consequence |
| --- | --- | --- |
| I | `META-INF/services/com.barezen.web.HttpServerAdapterProvider` deleted, `target/classes` cleaned first | six failures in `Discovery` and `Registration` — 3 assertion failures and 3 `NoSuchElementException`s; the first run of this probe had passed on a stale `target/classes/META-INF`, which is why the cleanup step is in the transcript |
| K (§10) | a `com.barezen.web.TempLookupProvider` with `Object lookup(String)` | three web rules fired — `no_container_shaped_type_names`, `nothing_takes_a_type_or_a_key_to_find_an_object`, `the_contract_is_exactly_the_nine_contracted_types` — 3 failures of 215 tests, build failed; the exemption did not generalize |
| A | a `com.barezen.web` main source naming `com.sun.net.httpserver` | recorded in full in ADR-0021 §3: three web rules for a method-shaped mutant, seven rules across two modules for a `static HttpServer` field mutant, both builds failed — which is also §2's claim that the provider interface did not open a hole in the direction |
