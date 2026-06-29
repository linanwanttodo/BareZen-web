# ADR-0021: JDK HTTP Server Adapter — why this server, why this module, why this boundary

Status: accepted
Date: 2026-09-29
Decides: why the first HTTP adapter is built on `com.sun.net.httpserver` rather than Netty, Jetty, Undertow or Vert.x;
why the adapter lives in its own module rather than inside `barezen-web`; why the contract module declares no dependency
on the adapter; what the adapter's dependency surface is; how the address and port are chosen; and why the JDK's filter
chain is not used
Related: ADR-0001, ADR-0002, ADR-0003, ADR-0006, ADR-0008, ADR-0009, ADR-0011, ADR-0012, ADR-0013, ADR-0015, ADR-0016,
ADR-0017, ADR-0018, ADR-0019, ADR-0020

## Context

Phase 2.9 is the phase ADR-0018 §12 and ADR-0020 §12 reserved: the contract has been frozen across two phases, the
route table delivers a handler and a parameter map, and the only thing missing is something that actually binds a port
and moves bytes. The pressure to fail this phase is the same pressure every framework feels at the same moment — a
server library is one dependency away, and the adapter is the place where a choice stops being reversible. The question
is not which server to wrap; it is whether the wrapping can be done without eroding any of the boundaries the previous
eighteen records drew, because an adapter is the one component that *has* to touch a server, and the temptation is to
let that touch spread.

Three earlier decisions shape everything below. ADR-0001 makes `slf4j-api` the kernel's only third-party compile
dependency; nothing here asks to widen that, and the adapter turns out to need no additional third-party dependency at
all. ADR-0002 makes JDK 17 the baseline with newer features as a runtime capability, which is the rule that lets this
adapter run on virtual threads on JDK 21 and on platform threads on JDK 17 with no branch in its own file — the
capability detection lives in `ThreadProviders`, not here. ADR-0018 makes the contract a server-free zone: five types,
zero compile dependencies, no `com.sun.net.httpserver` on the classpath, so the adapter is the first module that is
allowed to name a server, and the rules that keep every other module from naming one have to be written somewhere that
can see both sides of the edge.

The phase question was: **what is the smallest adapter that serves the contract end-to-end, and what must it refuse to
become?**

## The question, in parts

1. Why `com.sun.net.httpserver`, and why not Netty, Jetty, Undertow or Vert.x? (§1)
2. What is the adapter's dependency surface, and how is it proven? (§2)
3. Why are boundaries stated by package rather than by class name? (§3)
4. What is the adapter *not* given — no reflection, no scanning, no context, no `com.sun` type in a public signature?
   (§4)
5. How are the address and port chosen, and why? (§5)
6. Why one context at `"/"` and no JDK `Filter` chain? (§6)

## Decision

`framework/barezen-http-jdk` is a new module whose shipped surface is two public types:

```java
public final class JdkHttpServerAdapter implements HttpServerAdapter {
  public JdkHttpServerAdapter(InetSocketAddress address, Router router);
  public JdkHttpServerAdapter(InetSocketAddress address, Router router, ThreadProvider threads);
  public void start();
  public void stop();
  public boolean isRunning();
  public OptionalInt port();
}

public final class JdkHttpServerAdapterProvider implements HttpServerAdapterProvider {
  public String id();
  public HttpServerAdapter create(String host, int port, Router router);
}
```

`JdkExchangeBridge` and `JdkRequestDispatcher` are package-private and final. The module depends on `barezen-web`,
`barezen-core`, `org.slf4j` and `com.sun.net.httpserver`, and on nothing else — no server library, no JSON library, no
test library, no framework, no Spring, no Netty, no Jetty, no Undertow, no Vert.x, no Servlet. `barezen-web` declares
no compile dependency on this module, and `barezen-core` depends on neither. The contract stays server-free; the
adapter is a leaf.

### 1. The one-instance-one-server lifecycle, and why the JDK server is the right first adapter

The adapter wraps `com.sun.net.httpserver.HttpServer`, the JDK's own HTTP server, and the reason is not that it is the
best server — it is that it is the *free* server, in the sense ADR-0001 cares about: it adds no third-party compile
dependency, it is already on the classpath of every JDK the framework supports, and it is good enough to serve the
contract end-to-end. The contract's claim is that `Request` goes in, `Response` comes out, and nothing between them is
a server concept; `JdkHttpServerAdapterEndToEndTest` asserts that claim through real sockets, with `RawHttp` sending
bytes and reading bytes back, and every test in that file — `getRootIsAnsweredByTheHandlerRegisteredForIt`,
`aStaticRouteReachesItsOwnHandler`, `aPathParameterReachesTheHandlerAsACapturedValue`,
`twoParametersArriveAtTheNamesTheirPatternDeclared`, `theMostLiteralRouteWinsOnASocketThatCouldMatchBoth`, and the
seven raw-semantics tests — proves that what crosses the boundary is the contract and nothing else. The raw-semantics
group is the stronger half: `anEncodedSlashStaysOneSegmentAndIsNotDecoded` sends `/users/a%2Fb` and reads back
`a%2Fb`, which a bridge that called `getPath()` instead of `getRawPath()` would have split into two segments and
answered 404; `percentEscapesAreNotFoldedIntoTheCharacterTheyName` sends `/users/%41BC` and reads back
`parameter:%41BC`, which is the only answer that does not silently re-route a request to a resource nobody named
(ADR-0019 §4). These are not cosmetic assertions; they are the contract's encoding semantics running under a real
socket, and they pass because the bridge reads the raw forms the JDK parser preserved.

`JdkHttpServerConcurrencyTest` adds the other half of the lifecycle claim: sixteen client threads, one hundred requests
each, three routes, and a per-request answer built from the captured parameter and a per-request header marker. The
test asserts 3200 correct answers and zero mismatches, which is the property that only a socket test can show — a
shared mutable field somewhere in the exchange-to-handler chain would be invisible to a single-threaded test and
produce a wrong answer here. The second concurrency test, `statusCodesStayAttachedToTheirOwnRequests`, mixes 200, 404
and 500 on the same route under the same load, and the assertion is that a status never detaches from its own request.
Both tests are the reason the dispatcher holds nothing but the route table and the bridge is a static-method utility:
there is no per-request state to leak because there is no place to put it.

One instance of the adapter corresponds to one server lifecycle, `NEW → RUNNING → STOPPED`, with no path back. A
second `start()` throws `IllegalStateException` naming the state and telling the caller to build a new adapter;
`start → stop → start` fails for the same reason. The reason is the port: a restarted server may be bound to a
different ephemeral port, so an application holding this object would be holding the address of a server that no
longer exists. `aSecondStartFailsAndTheRunningServerKeepsServing` asserts that the refusal does not disturb the running
server — `isRunning()` stays true, the port stays the same, and the next request still answers — which is the property
probe G guards: relaxing the start guard to `state == State.STOPPED` lets that test fail, because the running server
would be replaced with a different instance on a possibly different port. `startAfterStopFailsBecauseTheInstanceIsSpent`
asserts the other half, and probe H guards it: relaxing the guard to `state == State.RUNNING` lets that test fail,
because the spent instance would silently bind a new server the caller's reference does not name. A restart is a new
adapter, built by the code that built this one (ADR-0024 §5).

### 2. The dependency surface is an allow-list, not a blacklist

The adapter module's dependency surface is stated as an allow-list in
`BarezenHttpJdkArchitectureTest.the_adapter_depends_only_on_the_contract_core_slf4j_and_the_jdk`:
`java.lang..`, `java.io..`, `java.net..`, `java.util..`, `java.nio.charset..`, `java.time..`, `com.barezen.web..`,
`com.barezen.core..`, `com.barezen.http..`, `org.slf4j..` and `com.sun.net.httpserver..`. Everything else is refused
by one sentence. An allow-list is the right shape for the reason ADR-0018 §12 gives: a Netty import and a
`MessageDigest` are both leaks, and only one of them is on a blacklist anyone would think to write.

`no_server_framework_or_container_appears_here` names the frameworks explicitly — Spring, Netty, Jetty, Undertow,
Vert.x, Servlet (`jakarta` and `javax`), Jackson, Apache, Hibernate, JUnit, AssertJ, ArchUnit itself, logback — and
refuses them in shipped source. The rule is redundant with the allow-list and written anyway: a reviewer asking
"could this module quietly acquire Spring?" should find the answer in one sentence rather than by working out what an
allow-list excludes. No test library appears in shipped source either, which is why `Executors.newFixedThreadPool` in
the concurrency test lives in `src/test` and not in a utility the adapter exports.

The allow-list is what makes the module a leaf. Removing `barezen-http-jdk` from a build removes a server and changes
no contract, no plugin and no persistence module — a property `no_other_barezen_module_may_depend_on_an_http_adapter`
enforces across the whole `com.barezen` namespace. The rule is written over packages that do not exist yet
(`com.barezen.security..`, `com.barezen.data..`, `com.barezen.api..`) because a rule over classes that exist cannot
see the class that was just added, and the failure this guards against arrives months later as a "temporary" import
inside a persistence module.

### 3. Boundaries are stated by package, never by class name

The most important rule in this module's architecture test is
`only_an_http_adapter_module_may_name_the_jdk_http_server`: no class outside `com.barezen.http..` may depend on
`com.sun.net.httpserver`. The rule is written over *packages* rather than over class names, and the reason is the
difference between a boundary and a list. A rule of the form "only `JdkHttpServerAdapter` may import that package"
would have to be edited every time an adapter is added — a Jetty adapter in `com.barezen.http.jetty` would fail a
class-name rule and pass a package rule without an edit, which is the property a boundary should have. The condition
form, `the_jdk_server_is_reached_only_from_inside_the_adapter`, reads the same edge from the dependency side: any
class that touches `com.sun.net.httpserver` must live inside `com.barezen.http`, so the rule is two sentences about the
same edge rather than one sentence about a list of names.

Five rules together hold the direction `core ← web ← adapter`: `the_dependency_direction_is_core_then_web_then_adapter`
forbids `barezen-core` from naming either the contract or an adapter; `the_contract_does_not_know_about_the_adapter`
forbids `barezen-web` from naming the adapter or the JDK server — the compile classpath already refuses this, because
`barezen-web` declares no dependency on this module, and the rule is the reviewable statement of what the pom implies;
`plugins_neither_name_a_server_nor_become_one` keeps the plugin SPI out of the server business, so a plugin registers
routes through the contract and works unchanged under any adapter; `no_other_barezen_module_may_depend_on_an_http_adapter`
extends the same refusal to security, data and api modules that do not exist yet; and
`an_adapter_is_not_a_lifecycle_participant` keeps the adapter out of the orchestrator's lifecycle, which is ADR-0024 §2
and is why the application's assembly writes a `ServerParticipant` of its own rather than registering the adapter
directly.

The reason these cross-module rules live in this module's test rather than in `BarezenWebArchitectureTest` is
structural: `barezen-web` declares no compile dependency on `barezen-core`, so a web test cannot name a core type, and
a rule about "an adapter is not a `LifecycleParticipant`" needs both types on the classpath. This module's test
classpath is the only place in the reactor holding all three modules at once — it is the one test class that can see
both sides of each edge. Two probes put a `com.barezen.web` main source naming `com.sun.net.httpserver` and were
measured, not assumed. A class with a method taking an `HttpServer` failed three rules in
`BarezenWebArchitectureTest` — `the_contract_depends_only_on_java_lang_and_java_util`, `no_jdk_http_server`, and
`the_contract_is_exactly_the_nine_contracted_types`, which refused the probe itself as a tenth type. A class with a
`static HttpServer hold` field failed seven: those three plus `every_field_is_final` in the same test, and in this
module's test `only_an_http_adapter_module_may_name_the_jdk_http_server`,
`the_jdk_server_is_reached_only_from_inside_the_adapter` and `the_contract_does_not_know_about_the_adapter`. Both
builds failed. That is the phase's title claim made mechanical: the contract cannot name a server, the refusal is
distributed across the modules rather than concentrated in one file, and the proof is a build failure rather than a
passing suite with a comment.

### 4. What the adapter is not given

The adapter takes an address and a `Router` and nothing else. `the_adapter_discovers_nothing_it_was_not_given` refuses
`java.lang.reflect..`, `java.lang.invoke..`, `java.net.URLClassLoader..`, `java.util.concurrent.atomic..` and
`java.util.ServiceLoader` in shipped source — no reflection, no classpath scanning, no dynamic classloader, no
`ServiceLoader` use inside the adapter, no request-scoped mutable holder. `the_adapter_reads_no_environment_and_no_properties`
refuses `java.lang.System`, so no `System.getProperty` and no `System.getenv` — the adapter has two constants, a
backlog and a stop grace, and both are chosen rather than configured (ADR-0003, ADR-0024 §6); a `System.getProperty`
here would be a configuration system smuggled in through a constant's declaration site.

`the_jdk_types_never_appear_in_a_public_signature` reads the signatures of the module's public classes rather than
their imports: an `HttpExchange` or an `HttpServer` in a public member would put the JDK back where an application can
read it, which is the thing the adapter was written to hide. The bridge and the dispatcher are package-private and
final, asserted by `the_bridge_and_the_dispatcher_are_not_extension_points` — a package-private but open class would
still be subclassable by the next file added to this package, which is how a dispatcher becomes an override point for
an error format. An application that wants different error text writes a `Handler`; an application that wants a
different server writes a different adapter module.

The module's public surface is exactly two types, asserted by `the_module_exposes_exactly_two_public_types`:
`JdkHttpServerAdapter` and `JdkHttpServerAdapterProvider`. A third public type is an ADR-sized decision, and the rule
is a name rule rather than a count because "these two and no others" is the correct form for an artifact's published
API. No request attribute bag, no `HttpContext`, no per-request holder of any kind reaches into this module —
ADR-0018 §9's refusal, applied one layer deeper.

### 5. The address and port choice: loopback, ephemeral, reported

Every test in this module binds `127.0.0.1` on port `0` and reads the number back from `port()`. No test names a port.
The reason is stated in `JdkHttpServerAdapterEndToEndTest`'s class documentation and in
`JdkHttpServerLifecycleTest.startBindsReportsAndServes`: port 0 asks the operating system for an ephemeral port, which
is how two servers share a machine and how a test starts a server without choosing a number that might be in use on
the developer's workstation. `port()` is an *output* of starting rather than an input, and it is read from
`server.getAddress().getPort()` rather than remembered from the constructor — which is the only way an address built
with port 0 gets a usable answer. `aNewAdapterClaimsNothingAndBindsNothing` asserts that `port()` is empty before
`start()`, and `stopIsIdempotentAndNeverThrows` asserts that it is empty again after `stop()` — a server that is not
listening has no port to name, and reporting `0` instead would read like "port 0", the value that means "ask the OS".

The loopback default is a security rule rather than a convenience: a server bound to `0.0.0.0` answers every interface
on the machine, and a developer's workstation has at least one interface that is not localhost. An adapter that
defaults to loopback cannot accidentally be reached from the network; an application that wants every interface passes
`0.0.0.0` explicitly, which is the same rule ADR-0013 applies to configuration — the dangerous choice is the one that
has to be written down.

The backlog is `0`, which the JDK documents as "use the platform default" — for this server, fifty. A backlog is a
tuning number with no consumer in this phase, so it is the JDK's rather than a constructor parameter nobody can
justify. The choice is recorded as a constant, `DEFAULT_BACKLOG`, so a later phase that has a consumer can replace it
without the value being buried in a call.

### 6. One context at `"/"`, and no JDK filter chain

The adapter registers one context, at `"/"`, and dispatches every request through `JdkRequestDispatcher`, which asks
the `Router` and writes the answer. `no_filter_chain_is_built_here` refuses any class named `Filter` in shipped
source, and the reason is not aesthetic: a `com.sun.net.httpserver.Filter` is a second dispatch mechanism the contract
has no type for. The contract owns `Handler`, `Request`, `Response` and `Router`; it does not own a filter, an
interceptor, a chain or a middleware type (ADR-0018 §10, §11). If the adapter built a JDK filter chain, a later
middleware phase would have to *delete* it rather than reuse it — the filter would be a mechanism the contract cannot
name, sitting between the exchange and the dispatcher, and the contract's own composition rule — `Handler → Handler` —
would have to work around it.

The single context at `"/"` is the whole dispatch story: the JDK hands every request to the dispatcher, the dispatcher
asks the route table, the route table answers with a handler and a parameter map or with nothing, and the dispatcher
writes 200-through-599 from a handler, 404 when nothing lives at the path, 405 when something lives there for a
different method, 500 when a handler threw, and 400 when the exchange reached the handler but no `Request` could be
built from it. Five answers, one dispatch, no second mechanism. `no_middleware_shaped_types_appear_anywhere_yet`
refuses `Filter`, `Interceptor`, `Middleware`, `HttpContext`, `RequestContext` and `HandlerChain` across the whole
`com.barezen..` namespace — the adapter included — so the vocabulary stays empty until a phase can justify one of
those names with a consumer.

## Consequences

- **The framework now serves HTTP, on a server that adds no third-party dependency.** The claim is mechanical: the
  module's pom names `barezen-web`, `barezen-core`, `slf4j-api` and the JDK, and the allow-list rule proves nothing
  else is reached.
- **The contract is still server-free.** `barezen-web` declares no dependency on this module, and five architecture
  rules prove no module outside `com.barezen.http..` names `com.sun.net.httpserver`. A Jetty or Netty adapter is
  another module, not a rewrite.
- **Bodies are fully buffered in both directions, and the limit is unwritten.** The adapter reads the whole body into
  a `byte[]` and writes the whole body from one; an upload or a large export is served by copying it, and the maximum
  body size that should refuse a request before a handler runs is recorded as this phase's design debt rather than
  invented here. A streaming body needs a lifecycle, a close policy and an executor; the same argument that put them
  outside the event bus (ADR-0017) puts them in a later adapter phase.
- **The dependency direction is proved, not stated.** `core ← web ← adapter` is enforced by five rules analyzed over
  the whole `com.barezen` namespace from the one test classpath that holds all three modules. A probe that put a
  `static HttpServer` field in `barezen-web` main source failed seven rules across two modules and the build; the
  contract's server-free claim is a build failure rather than a test failure.
- **The adapter is a leaf, and deleting it changes no contract.** `no_other_barezen_module_may_depend_on_an_http_adapter`
  names the reserved packages — security, data, api — that do not exist yet, and the rule is written now because a
  rule over classes that exist cannot see the class that was just added.
- **The port is an output, not an input.** Every test binds port 0 and reads the number back; two servers can run in
  the same build on the same machine; a loopback default keeps the developer's workstation from being reachable from
  the network by accident.
- **No filter chain, no middleware vocabulary, no second dispatch mechanism.** The contract's composition rule —
  `Handler → Handler` — is the only chain in the web layer, and the adapter does not build a parallel one.
- **Design debt carried forward, named rather than absorbed:** no maximum body size, so an unbounded upload is a
  denial of service; no access log; no `OPTIONS *` answer beyond a 404; no static file handler; no TLS; no
  configuration of the backlog or the stop grace; no streaming body; no way for a handler to see its own pattern; no
  query parameter parsing. Each stays unwritten because nothing in this phase calls it.

## Alternatives rejected

**Netty.** A third-party compile dependency, which ADR-0001 refuses for anything that is not the kernel's one allowed
dependency. The adapter would also have had to choose a version, which is a compatibility promise to keep, and the
contract's server-free claim would have been harder to prove — a Netty `Channel` in a signature is a different leak
than a JDK `HttpExchange`, but it is the same kind of leak.

**Jetty.** Same dependency argument as Netty, plus a pom that names a server the framework does not need. The adapter
would have been written against Jetty's `Handler` and `Server`, and a second adapter on the JDK server would have had
to reproduce every decision in a different shape — which is the test of whether the contract is right, and the test
this phase is not for.

**Undertow and Vert.x.** Same argument. Each is one dependency away, and each would have made the framework a wrapper
around somebody else's HTTP rather than the owner of its own contract.

**Putting the adapter inside `barezen-web`.** Tempting, because a build with one fewer module is appealing after six
phases of adding them. Refused because the contract's server-free claim is proved by the *absence* of a dependency in
the pom — if the adapter lives in the same module, the pom has to name the JDK server, and the allow-list that keeps
the contract clean has to make an exception for the adapter's own package. A separate module makes the boundary a
compile-time fact rather than a rule about packages inside one jar.

**A `Filter` chain around the dispatcher.** A JDK `Filter` is a second dispatch mechanism the contract has no type
for. Building one here would have made a later middleware phase delete it rather than reuse it, and the contract's
composition rule — `Handler → Handler` — would have had to work around a mechanism it cannot name.

**A `server()` accessor that hands the JDK `HttpServer` to the application.** An application that held the server
could start a second context, replace the executor or stop it out from under the adapter. There is no accessor, and
`no_method_offers_a_second_lifecycle_shape` refuses `server`, `getServer`, `httpServer` and the rest of the pool
vocabulary by name.

**Configuring the backlog or the stop grace through `System.getProperty`.** A `System.getProperty` here would be a
configuration system smuggled in through a constant's declaration site. The adapter is configured by the arguments it
is constructed with, and the two constants are chosen rather than configured because neither has a consumer yet.

## Evidence

Test classes and what they assert:

| file | tests | what this phase ships |
| --- | --- | --- |
| `JdkHttpServerAdapterEndToEndTest` | 12 | the contract end-to-end through real sockets: routing and parameter delivery (5) and the raw target surviving the trip — encoded slash, encoded dot-segment, plus, percent escapes, trailing slash, raw query (7) |
| `JdkHttpServerConcurrencyTest` | 2 | 1600 concurrent requests with zero mismatches; status codes stay attached to their own requests under load |
| `JdkHttpServerLifecycleTest` | 21 | the lifecycle matrix — `NEW → RUNNING → STOPPED`, idempotent stop, close-is-stop, bind-failure path, executor ownership, the thread model, the assembly integration, the adapter-is-not-a-participant proof |
| `BarezenHttpJdkArchitectureTest` | 24 | the allow-list, the package rules, the direction rules, the lifecycle-field rule, the no-filter rule, the two-public-type rule, the no-restart-vocabulary rule, the no-environment rule |

Mutation probes that fired:

| probe | change | consequence |
| --- | --- | --- |
| A | a `com.barezen.web` main source naming `com.sun.net.httpserver` | method-shaped mutant (`portOf(HttpServer)`) fired three web rules — `the_contract_depends_only_on_java_lang_and_java_util`, `no_jdk_http_server`, `the_contract_is_exactly_the_nine_contracted_types`; field-shaped mutant (`static HttpServer hold`) fired seven rules across two modules, adding `every_field_is_final` in web and `only_an_http_adapter_module_may_name_the_jdk_http_server`, `the_jdk_server_is_reached_only_from_inside_the_adapter`, `the_contract_does_not_know_about_the_adapter` in this module; both builds failed |
| B | a `barezen-core` class file naming `com.barezen.web.Request`, injected into `target/classes` after a Maven `dependencies`-route probe was itself refused as a reactor cycle | core's `barezen-core must not acquire any third party dependency beyond slf4j-api` and `core must stay free of HTTP, SQL, security, plugin, Spring and test framework concepts` both fired, 245 tests with 2 failures, build failed |
| G | relaxing the start guard to `state == State.STOPPED` | `aSecondStartFailsAndTheRunningServerKeepsServing` failed — the running server was replaced with a different instance on a possibly different port |
| H | relaxing the guard to `state == State.RUNNING` | `startAfterStopFailsBecauseTheInstanceIsSpent` failed — the spent instance silently bound a new server the caller's reference did not name |
| J | `if (ownsThreadProvider)` → `if (true)` | `aBorrowedProviderSurvivesStopAndIsStillUsable` failed — the adapter closed a provider it did not create, and the borrower's executor was gone |
