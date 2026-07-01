# BareZen-web

A lightweight, modular, plugin-based general-purpose Java Web framework. Package: `com.barezen`.

Design principles: **Simple Core. Composable Everything. Plug and Play. Secure by Default. Built to Extend.**

## Current state

Phase 2.9 (HTTP Runtime — JDK adapter), its 2.9.5–2.9.6 contract audit and Phase 2.10 (HTTP semantics stabilization &
contract hardening) are implemented. 2.10 decided boundaries rather than adding features: the HEAD framing boundary
(ADR-0028), the asserted framing matrix and the 304 decision (ADR-0029), the response-body decision that kept `byte[]`
(ADR-0030), the request-body limit with its 413 answer (ADR-0031) and the class-major build contract (ADR-0032). What
it left open it left open by name: every deferred item carries a reopening trigger and a named phase in the ADR that
defers it. Everything beyond these phases is design only.

| Module | Phase | State |
| --- | --- | --- |
| `barezen-parent`, `barezen-bom` | 2.1 | build skeleton, enforcer rules, JDK baseline |
| `framework/barezen-core` | 2.1–2.6 | lifecycle engine, thread capability, configuration, dependency injection, assembly boundary, plugin SPI, event bus |
| `framework/barezen-web` | 2.7–2.9.6, 2.10 | HTTP contract (`Handler`, `Request`, `Response`, `Headers`, `HttpMethod`), an immutable route table (`Router`, `RouteMatch`) and the server boundary (`HttpServerAdapter`, `HttpServerAdapterProvider`) — still no server, no middleware, no context; 2.10 tightened `Response`'s constructor rules and asserted the framing matrix |
| `framework/barezen-http-jdk` | 2.9–2.10 | the first adapter: `com.sun.net.httpserver` behind the contract, with its `ServiceLoader` provider; 2.10 added the HEAD framing boundary and the configured request-body limit |
| Discovery, Extension Points | later | not created |
| Security, Data, OpenAPI, Frontend | 4+ | not created |

A module directory appears in the tree only when the phase that fills it in runs (ADR-0008).

## Requirements

- Build and run: **JDK 17 or later**. JDK 21 is the recommended development and production runtime.
- Maven **3.9+**.

JDK 17 is a compatibility baseline, not a development constraint. Virtual threads are a runtime capability
selected at startup, never a build variant, and there are no `barezen-jdk17`/`barezen-jdk21` modules
(ADR-0002). Building on JDK 21 produces bytecode that still runs on JDK 17.

## Build

```bash
mvn verify
./scripts/check-jdk-baseline.sh
./scripts/check-class-major.sh
```

`mvn verify` runs unit tests, ArchUnit architecture rules and an 85% line-coverage gate on `barezen-core`, on
`barezen-web` and on `barezen-http-jdk`. The gate runs on JDK 21 only: on JDK 17 the virtual thread provider is
unreachable by construction, so that half of the bundle is not coverable there without asserting dead code is dead
(ADR-0002, ADR-0010). JDK 17 in
CI proves compatibility, JDK 21 proves compatibility and quality.
`mvn verify` also asserts what it emitted, not only what it declared: `ClassMajorContractTest` (in every emitting
module) and `scripts/check-class-major.sh` (a CI step after `verify`, on both JDKs) fail if any class file under
`target/classes` or `target/test-classes` has a class-file major other than 61 — the version JDK 17 reads. That is
the half `scripts/check-jdk-baseline.sh` cannot see, because `-Dmaven.compiler.release=21` satisfies the
declared-release script and emits major 65 anyway (ADR-0032).
`.github/workflows/backend-ci.yml` runs the same build on both; green on both is the definition of compatible.

## Layout

```
pom.xml                     barezen-parent: owns maven.compiler.release=17, versions, enforcer
barezen-bom/                version contract consumed by applications
framework/barezen-core/     kernel: lifecycle, thread capability, runtime capability, configuration,
                            assembly (di), plugin SPI, events, errors
framework/barezen-web/      HTTP contract and route table: handler, request, response, headers, method, router,
                            route match, server adapter contract — no shipped dependency, no server
framework/barezen-http-jdk/ the JDK adapter: the only module that may name com.sun.net.httpserver, and the only
                            one that registers a ServiceLoader provider
docs/adr/                   architecture decision records, 0001-0032
scripts/                    build guards
```

Dependency direction is fixed: `core` ← `framework` ← `plugins` ← `app`. Lower layers never import higher
ones, and ArchUnit enforces it inside core today and across modules as they are created. `barezen-web` goes one step
further than the rule requires: the jar it ships declares no compile dependency at all, not even on `barezen-core`, so
the HTTP contract and the route table cannot reach the kernel (ADR-0018). Phase 2.8 added one test-scope dependency on
`barezen-core` so a plugin can be assembled in a test against the real lifecycle engine; it is scope `test`, so it never
reaches the shipped artifact and the compile-boundary claim is still enforced by javac itself — a main-source import of
core does not compile (ADR-0019). Phase 2.9 added two more public types to the same jar — `HttpServerAdapter` and
`HttpServerAdapterProvider` — and neither names a server, so the shipped jar still has zero compile dependencies: an
adapter is allowed to know core and web, and web is allowed to know nothing (ADR-0021 §3, ADR-0025 §2).

## Phase 2.1 in one paragraph

`LifecycleOrchestrator` drives a fixed set of `LifecycleParticipant` components: all participants are
initialized before any is started (ADR-0003), a failed startup rolls itself back and reports the original
failure (ADR-0004), shutdown is best effort and returns a report instead of throwing (ADR-0005), and
`stop()`/`close()` are legal and idempotent from every state including `NEW`, which makes an application safe
to hold in try-with-resources (ADR-0012). `ThreadProvider` is an executor factory that owns what it creates —
not a thread pool registry — and resolves from a `ThreadPolicy` plus a three-field `RuntimeCapabilities`
snapshot, with the only Java 21 reference in the codebase confined to one `MethodHandle` probe (ADR-0010,
ADR-0011).

## Phase 2.2 in one paragraph

`Configuration` is an immutable snapshot assembled from ordered `ConfigurationSource`s and read through typed
`ConfigurationKey<T>`s, so a value's type is checked at compile time and a value that cannot be converted fails
with its key, its raw text and the source that supplied it rather than falling back to a default. Precedence is
positional and fixed — key default &lt; `application.properties` &lt; environment variables &lt; system
properties, with environment names mapped by the one rule `.` to `_` then uppercase — and only
`com.barezen.core.config` reads the process environment or the property table, while nothing in core writes
them; ArchUnit enforces both. A group
of keys binds to a record by hand (`RuntimeConfig.from(configuration)`): no annotations, no scanning, no
reflection over components. Configuration resolves values and nothing else, so it is not a service locator, and
the one setting core has today follows a single chain,
`Configuration → RuntimeConfig → ThreadPolicy → ThreadProviders.create(...)` (ADR-0013). The chain ends where an
application picks up the resolved policy — `threadPolicy(RuntimeConfig.from(configuration).threads())` — because
`kernel` may not depend on `config` (ADR-0015).

```java
Configuration configuration = Configuration.standard(Path.of("config/application.properties"));
LifecycleOrchestrator app = LifecycleOrchestrator.builder()
    .threadPolicy(RuntimeConfig.from(configuration).threads())   // barezen.runtime.threads
    .register("http", httpRuntime)
    .build();
```

`barezen.runtime.threads` accepts `auto` (default), `platform` or `virtual`. An unsupported explicit value fails
startup; `auto` is the only policy allowed to fall back.

## Phase 2.3 in one paragraph

`ServiceAssembly` is the whole container: four public types and seven operations, no new dependency, no reflection,
no scanning. An application registers each object either as an `instance(...)` it built itself, or as a
`factory(...)` whose body writes the constructor call —
`services -> new UserService(services.require(UserRepository.class))` — so a dependency stays readable at the line
that states it, and the `Services` view a factory is handed stops answering once `build()` returns, which is what
keeps it from being stored and used as a locator later (ADR-0014). `build()` creates everything eagerly,
which is what moves a missing dependency, a duplicate registration of one type and a cycle out of the first request
and into startup; a cycle is reported as the path it went through (`A -> B -> A`) rather than as a
`StackOverflowError`. Scope is application-wide and singleton-only, `@Primary`, qualifiers and named bindings do not
exist, and two implementations of one interface are two distinct registered types the assembly chooses between in
code. Ownership follows creation: the assembly releases what its factories built, in reverse creation order, so a
dependent always goes before what it depends on, and it never touches an object the caller supplied — which is why a
`LifecycleParticipant` is registered as an instance, leaving `LifecycleOrchestrator` the only lifecycle engine
(ADR-0003, ADR-0005). No type outside `com.barezen.core.di` depends on it and no public method outside it may take a
`Class` under a lookup-shaped name, so the assembled graph cannot be reached as a service locator — not by a
component today, and not by a future `PluginContext` (ADR-0006, ADR-0013).

```java
ServiceAssembly assembly = ServiceAssembly.builder()
    .instance(DataSource.class, dataSource)                        // caller built it, caller owns it
    .factory(UserRepository.class, services ->
        new JdbcUserRepository(services.require(DataSource.class)))
    .factory(UserService.class, services ->
        new UserService(services.require(UserRepository.class)))
    .build();

// A participant is registered as an instance, so its stop() has exactly one owner: the orchestrator. Its
// collaborators come out of the assembly, through a constructor.
SearchService search = new SearchService(assembly.require(UserService.class));

LifecycleOrchestrator app = LifecycleOrchestrator.builder()
    .threadPolicy(RuntimeConfig.from(configuration).threads())
    .register("search", search)
    .build();
```

## Phase 2.4 in one paragraph

Phase 2.4 adds no capability and no public type; it settles who is responsible for what is already there.
`ServiceAssembly` creates objects, `LifecycleOrchestrator` drives them, and **application assembly is a
responsibility held by the application's own code** — BareZen ships no `Application`, `ApplicationBuilder`,
`ApplicationContext` or `ApplicationBootstrap`, and `barezen-app` stays an uncreated planned module (ADR-0015). So
the order is stated rather than provided: load configuration, detect runtime capabilities, build the assembly,
create the orchestrator with the finished participants, `start()`. Shutdown runs the two release paths in the same
direction — `orchestrator.stop()` first, because a participant may still use assembled infrastructure while it is
cleaning up, then `assembly.close()` — and neither mechanism reaches into the other on a failure: a `build()` that
throws releases what it had already created before the factory's own exception propagates, while a `start()` that
throws rolls back the participants and leaves the assembly for the application to close (ADR-0004, ADR-0005). Two
public methods went as part of settling this. `threadKind()` existed only so a test could read the resolved thread
provider back, and a getter whose only caller is a test is not application programming; what the policy decided is
observable where it matters, because an unsatisfiable request fails `build()`.
`Builder.configuration(Configuration)` saved an application one line — the line that is the assembly responsibility
itself — while making `kernel` depend on `config`, so an application now hands over a resolved
`ThreadPolicy` instead. Three ArchUnit rules hold the boundary: `kernel` may not depend on `config`, `Config` is
reachable only from `Concurrent`, and `di` stays a leaf nothing in core depends on.

```java
// The application's own assembly code: the only place allowed to know about both layers.
ServiceAssembly assembly = ServiceAssembly.builder()
    .factory(ConnectionPool.class, services -> new ConnectionPool())
    .factory(Reporter.class, services -> new Reporter(services.require(ConnectionPool.class)))
    .build();

LifecycleOrchestrator app = LifecycleOrchestrator.builder()
    .threadPolicy(RuntimeConfig.from(configuration).threads())
    .register("reporter", assembly.require(Reporter.class))
    .build();

app.start();
// ... serve ...
app.stop();     // participants first, while the pool is still open
assembly.close();  // then what the assembly created
```

## Phase 2.5 in one paragraph

A plugin is a `LifecycleParticipant` that can name itself: `com.barezen.core.plugin` ships two types — an interface
with one method and a two-component record — and nothing else. `Plugin` inherits all four phases from the kernel and
adds `descriptor()`, so there is one lifecycle path, driven by the same `LifecycleOrchestrator` as everything else;
there is no `install()` beside `initialize()`, no `uninstall()` beside `stop()`, and no second engine. Dependencies
arrive through the constructor — `new AuthPlugin(router, credentialStore, passwordHasher, tokenIssuer)` — never
through a `PluginContext`, because every member such a context would carry either reopens the lookup surface ADR-0006
and ADR-0014 refuse, hands a plugin the configuration handle ADR-0013 resolves at assembly time, reaches the
orchestrator ADR-0015 gave to the application, or has no target yet. `PluginDescriptor(id, dependencies)` is metadata
the application reads to pick its registration order; core verifies and orders nothing, since nothing in core holds a
set of plugins — and that is recorded as a limitation rather than answered by a half-resolver. No discovery
(`ServiceLoader`, scanning, `META-INF`), no `PluginManager`/`PluginRegistry`/`PluginContainer`, no extension point and
no registry — an extension mechanism arrives with the first component that owns an extension point, where the real
consumer can state its own rules. Ownership is unchanged from Phase 2.4: the application creates a plugin and releases
it, the orchestrator only drives it, and `stop()` is the single release hook. Three new ArchUnit rules keep the shape —
the SPI may reach `LifecycleParticipant` and no other kernel type, may not reach `di`/`config`/`concurrent`/`runtime`,
and stays a leaf no core type depends on — while the layered rule now lets `Kernel` be reached by `Plugin` and by
nothing else. Each was verified against a deliberately violating `PluginContext` and a `PluginRegistry` probe that were
then deleted (ADR-0016).

```java
// The application decides which plugins exist, in what order, and what they are given.
Plugin auth = new AuthPlugin(router, credentialStore, passwordHasher, tokenIssuer);
Plugin users = new UserPlugin(userRepository, tokenIssuer);   // descriptor: id "user", dependencies ["auth"]

LifecycleOrchestrator app = LifecycleOrchestrator.builder()
    .threadPolicy(RuntimeConfig.from(configuration).threads())
    .register(auth.descriptor().id(), auth)     // registration order is the startup order
    .register(users.descriptor().id(), users)   // read after "auth" because it declares that dependency
    .build();
```

## Phase 2.6 in one paragraph

`com.barezen.core.event` is the first horizontal capability added to core, and it is the test Phase 2.5's design was
waiting for: a component plugins genuinely need, delivered without a context. Three public types — `EventBus`
(`subscribe`, `publish`), `EventHandler` (one method, no priority or executor knobs) and `Subscription` (one method,
`cancel()`) — plus a package-private synchronous implementation. There is no `Event` interface: any object is an event,
and `event.getClass()` is the routing key. The dispatch contract is stated rather than implied — publish is synchronous
on the caller's thread, type matching is exact with no supertype walk, registration order is invocation order within an
event type, the first handler failure ends that dispatch and propagates unchanged, `null` fails immediately at either
method, and a handler may publish again. The bus is not a `LifecycleParticipant` and is not `AutoCloseable`: it owns no
thread, socket, file or queue, so it has nothing to start or stop, and a subscription ends when its owner cancels it
which is why `Plugin.stop()` calls `cancel()` on the handles it kept from `initialize()`. The bus does not know who
subscribed, so `EventBus ↛ Plugin` and `EventBus ↛ anything in core` hold, and the reverse edges hold too — the package
is a leaf in both directions. No `ExtensionPoint`, no `ExtensionRegistry`, no async mode, no handler discovery: an
event bus is not a general extension mechanism, and an asynchronous model would be a different type with its own
contract rather than a flag on this one (ADR-0017).

```java
// The application creates the bus and hands it to whoever needs it. Nothing looks anything up.
EventBus events = EventBus.create();
Plugin audit = new AuditPlugin(events);                       // subscribes in initialize, cancels in stop
BillingService billing = new BillingService(events);          // publishes

ServiceAssembly services = ServiceAssembly.builder()
    .instance(EventBus.class, events)
    .instance(BillingService.class, billing)
    .build();

LifecycleOrchestrator app = LifecycleOrchestrator.builder()
    .threadPolicy(RuntimeConfig.from(configuration).threads())
    .register(audit.descriptor().id(), audit)
    .build();

app.start();
services.require(BillingService.class).charge("123");         // publish -> audit's handler, on this thread
```

## Phase 2.7 in one paragraph

`framework/barezen-web` is the web layer's HTTP contract, and it has **zero dependencies in the jar it ships** — not on
Jetty, Netty or Servlet, and not on `barezen-core` either (Phase 2.8 later put core on the *test* classpath only).
Five public types: `Handler.handle(Request, Map<String, String>) → Response` — Phase 2.8.1 added that second argument,
and Phase 2.7 shipped `handle(Request)` (ADR-0020) — `Request`
(method, raw path, raw query, headers, body), `Response` (status, headers, body), `Headers` (case-insensitive names,
repeated fields kept as repeated fields, no `Map` in sight) and `HttpMethod` (nine shared constants, unknown methods
keep the client's spelling). Everything is immutable and validated at construction, so a `Request` a handler received
is the `Request` it passes on. No `Router` yet: binding a path to a handler means deciding trailing slashes, case,
`%2F`, priority and capture names, and none of those had a consumer at the time. No `Middleware`: the six questions a
middleware type must answer are answered by decorating `Handler` with a lambda, which `HandlerTest` does for both
timing and a `401` short-circuit. No `HttpContext`, no `request.service(...)`, no attributes, no `EventBus` inside a
`Request` — a contract that can look things up stops being a contract. And nothing serves: `com.sun.net.httpserver`
lives in the `barezen-http-jdk` adapter to come, which ADR-0018 expected to implement `LifecycleParticipant`, own the
port, read the body, catch the exception and choose the error format. Phase 2.9 kept three of those four and refused the
first: an adapter is not a participant (ADR-0024 §2). Fourteen ArchUnit rules held that line at the time, each verified
by a mutation probe that made it fail (ADR-0018); Phase 2.8 amended two of them and added five, and Phase 2.8.1 amended
one more and added two (ADR-0019, ADR-0020).

```java
// An application writes handlers; the contract is all it needs to see.
Handler reports = (request, ignored) -> request.headers().first("Authorization").isPresent()
    ? new Response(200, Headers.of("Content-Type", "application/json"), json.toByteArray(UTF_8))
    : new Response(401, Headers.empty(), null);    // null body = an empty body, not a missing one

Handler timed = (request, parameters) -> {          // "middleware" as decoration, no new type
  long started = System.nanoTime();
  Response answered = reports.handle(request, parameters);
  return new Response(answered.status(),
      answered.headers().with("X-Elapsed-Nanos", Long.toString(System.nanoTime() - started)),
      answered.body());
};
```

## Phase 2.8 in one paragraph

`Router` answers one question — given an HTTP method and a raw path, which `Handler` should run — and it never answers
the request itself: it imports no `Response`, so a route table cannot decide a status, write a header or catch an
exception. A route is `(HttpMethod, pattern)`; a pattern is matched segment by segment against the path as the client
sent it, so `%2F` stays one segment, `%2e%2e` is never normalized, a trailing slash is exact rather than redirected, and
`{id}` captures one non-empty raw segment with no decoding and no converter. Literal segments beat parameters, which
makes the more specific route a total order — so `GET /users` registered twice fails at registration with the message
naming both patterns and the shape they share, and registration order never decides a winner. `build()` freezes the
table with `List.copyOf`, which is why there is no `built` flag, no `unregister`, no hot reload and no synchronisation
in the matching path: concurrency is answered by immutability, not by locks. `RouteMatch` exists for one reason — a
captured parameter has nowhere else to go — and it carries the handler plus an unmodifiable map of raw values. That
left the map's only reader outside the contract: `Handler.handle(Request)` could not receive it, so a handler re-derived
its id from `request.path()`. Phase 2.8.1 closed it (ADR-0020). A `Router` is not a
`LifecycleParticipant` and not a container: it owns no thread, socket or file, and a plugin takes `Router.Builder` in
its constructor, which is why `barezen-core` is on the test classpath and nowhere in the shipped jar. `Router` is also
the first name ADR-0018 forbade that this phase allowed, so two of its fourteen rules were amended rather than deleted
and five new rules hold the new line — nineteen in total, each fired by at least one mutation probe (ADR-0019).

```java
Router routes = Router.builder()
    .route(HttpMethod.GET, "/users", listUsers)              // most literal segments win
    .route(HttpMethod.POST, "/users", createUser)
    .route(HttpMethod.GET, "/users/{id}", readUser)          // one non-empty raw segment, undecoded
    .build();                                               // immutable from here on

Request request = new Request(HttpMethod.GET, "/users/42", null, Headers.empty(), null);
routes.match(request)                                        // empty -> 404, non-empty -> dispatch
    .ifPresent(match -> match.handler().handle(request, match.parameters()));
Set<HttpMethod> allowed = routes.methodsFor("/users");       // empty -> no route, non-empty -> 405
```

## Phase 2.8.1 in one paragraph

Phase 2.8 shipped a route table whose second output nobody could read: `match()` returned captured parameters, and
`Handler.handle(Request)` had no argument to receive them, so the integration test sliced `id` back out of
`request.path()`. That is not a style problem — `/{a}/fixed/{b}` is not reconstructible from a path at all, because
nothing in the path marks which segments the pattern considered literal, and a handler written against `path()` cannot be
checked against the pattern it was registered under. The fix is one argument:

```java
Response handle(Request request, Map<String, String> parameters);   // what the client sent + what routing decided
```

`RouteMatch` keeps holding a `Handler`, because both halves have the same consumer — the adapter — and one call site
separates them; handing the match to a handler would hand it its own identity, and a probe showed a handler could then
call `match.handler().handle(...)` and compile. `handle(Request)` was **removed** rather than kept as a default, because
an overload lets a handler registered under `/users/{id}` silently read nothing, which is the fail-silently failure
Phase 2.8 refused for duplicate routes. There is no `LegacyHandler`, no `RouteHandler`, no binder, no converter, no
context, no attribute bag, and `RouteMatch` is unchanged.

Two findings are worth more than the change itself. The rejected alternative — `request.routeParameters()` with a
`withRouteParameters(...)` copy — was implemented fully, immutable and well-named, and **passed all nineteen architecture
rules**; the rule that should have refused it was the sentence "a request carries what the client sent", which nothing in
the file expressed, so `a_request_carries_only_what_the_client_sent` is now a closed allow-list over `Request`'s public
methods. And editing the argument list had **silently voided** `nothing_in_the_contract_dispatches_a_handler`, whose
`callMethod` target named `handle(Request)` and therefore matched nothing: a probe of a router that really does dispatch a
handler stayed green under the old rule text and fired under the corrected one. Twenty-one rules now, and 213 web tests,
including a delivery group that dispatches 600 concurrent requests over three paths and gets 200 of each id. Encoding
semantics are untouched — `/users/a%2Fb` still delivers `id = "a%2Fb"` — and the contract is now frozen with the hole
closed, which is what makes the 2.9 adapter safe to write once (ADR-0020). Phase 2.9 amended two more web rules and
added two: the contracted-types rule now counts nine, and the container-shaped-name rule keeps its ban on the `Provider`
suffix while exempting exactly one name, policed by the two new rules rather than by a deletion (ADR-0025 §10).
Twenty-three rules and 215 web tests now, plus one more forbidden package — `com.barezen.http..` — in core's
`core_has_no_web_or_persistence_concepts`.

## Phase 2.9 in one paragraph

`framework/barezen-http-jdk` is the server, and it is still an adapter rather than a layer: two public types —
`JdkHttpServerAdapter` (`start`, `stop`, `isRunning`, `port`, `close`) and `JdkHttpServerAdapterProvider` (`id`,
`create`) — with the bridge, the dispatcher and the adapter's state machine all package-private and final (ADR-0021 §4,
§6). It binds `127.0.0.1` on port 0 and reports the ephemeral port, registers one context at `/` with no JDK `Filter`
chain, and takes its threads from a `ThreadProvider` instead of constructing a pool, so `stop()` can close exactly the
executor it created and leave a borrowed provider running (ADR-0021 §5, ADR-0024 §4). A second `start()` and a
`start→stop→start` both fail, because one instance is one server lifecycle and a restarted server may hold a different
port; `stop()` is idempotent, never throws and waits the JDK's own 3 seconds for in-flight exchanges rather than sleeping
(ADR-0024 §5, §6). The exchange bridge hands the contract what the wire actually said — `getRawPath()` never
`getPath()`, `getRawQuery()` with absent kept distinct from empty, the body read fully into a `byte[]` and closed — so
`/users/a%2Fb` stays one segment and `/users/%2e%2e/admin` is never normalized (ADR-0022 §2–§4). Two measured JDK
facts shape what the adapter promises: `com.sun.net.httpserver.Headers` canonicalizes field names
(`X-MixedCase` arrives as `X-mixedcase`) and is hash-backed, so arrival order is gone before the bridge reads it
(ADR-0022 §5); and the JDK reads a request method case-sensitively while the contract folds the nine standard names,
so HEAD suppression keys on the exact raw token the server itself compares — `HEAD` gets HEAD's response (fields, the
representation's length, no bytes) and a spelling like `head` gets the ordinary response its representation has, which
is the intentional boundary ADR-0028 records in place of the false `Content-length: 0` ADR-0027 §9 had measured.
Errors are responses: 404 names no path,
405 carries a stable adapter-local `Allow`, a handler's `RuntimeException` is logged with its throwable and answered with
`Internal Server Error` alone, a target the contract refuses or a body that stops arriving is a 400 before anything is
committed, and a write that fails after the status line is logged once at DEBUG and never answered with a second status
(ADR-0023 §2–§6). Discovery is the last piece: the module registers its provider in `META-INF/services`, `ServiceLoader`
finds it, `id()` is `"jdk"`, `create` hands back a fresh unstarted adapter each call, and nothing in this module calls
`ServiceLoader` itself, because discovery may only ever *discover* — the selection stays in application assembly
(ADR-0025 §1–§8). 152 tests, 24 ArchUnit rules over package boundaries rather than class names, an 85% line-coverage gate
on JDK 21, and every mutation probe fired; 2.9.1–2.9.4 are the request/response bridge, the error semantics and the
provider, delivered with 2.9 as one runtime (ADR-0021–ADR-0025).

## Phase 2.9.5–2.9.6 in one paragraph

A contract audit, not a feature phase: two questions opened, `OPTIONS *` and what `Response(status, Headers, byte[])` is
allowed to hold, and the code was measured before it was written. **`OPTIONS *` is not supported, and that is now a
measurement rather than a label.** `ServerImpl` resolves the target with `new URI("*")`, whose path is `"*"` with no
leading slash, looks for a context whose path is a prefix of it, and finds none — because `HttpContextImpl`'s constructor
refuses any path that does not start with `/` — so it writes its own 404 and closes the connection before a filter, an
exchange or a handler exists. There is no public API that changes that: `createContext("*")` is
`IllegalArgumentException: Illegal value for path or protocol`, `removeContext("*")` refuses, JDK 21's
`create(addr, backlog, path, handler, filters)` overload (the only surface difference between the two JDKs, `@since 18`)
calls `createContext` internally and refuses the same way, and `Filter` can only run after a lookup succeeds. ADR-0023 §9
called this design debt; debt implies a deferred fix, and there is none, so ADR-0026 replaces the label with the mechanism
and with ten socket tests that fail if it changes. They pin the fingerprint of *who wrote the answer* rather than the
status code — the server's reply carries `<h1>404 Not Found</h1>No context found for request`, an uppercase-`L`
`Content-Length` and no `Date`, while anything through an exchange carries the canonicalized lowercase spelling and a
`Date` — plus the finding that this is not an OPTIONS rule: ten method tokens naming `*`, including `PURGE`, get the
identical answer, and a counted route table shows a resource handler called zero times. No synthetic handling exists,
`OPTIONS *` is not `OPTIONS /`, `"*"` is not a route, and `methodsFor("*")` returns nothing; `Router` was not widened.

**Response body semantics moved, because three concepts had been collapsed into one.** An empty `byte[]`, a status that
forbids a body, and a HEAD's representation length are three facts, and the pre-audit shape treated them as one: a handler
could hand a `204` bytes, which the JDK discarded while logging `rCode = 204: forcing contentLen = -1` and telling nobody,
and could set `Content-Length` itself, which on the bare JDK makes `java.net.http.HttpClient` fail the response with
`unexpected content length header with 204 response` and leaves two length lines on a `304` or a HEAD. Both are now
refused in `Response`'s constructor — `1xx`/`204`/`304` get no bytes, and `Content-Length`/`Transfer-Encoding` are not
response fields on any status — because a silent disagreement between what a handler returned and what a client received is
the failure this contract exists to prevent, and the earliest place to name it is the line the developer wrote. A HEAD now
states the length of the body it does not send, derived by the adapter and declared as `-1`, which closes ADR-0022 §7's
limitation without a server-side warning: the twelve-shape wire table is byte-identical on 17.0.20.1 and 21.0.12.1 modulo
`Date`, and a JUL handler on the server logger captures none. `byte[]` stayed, and no `BodyMode`, `NoBody`,
`Representation`, `StreamingBody` or `Optional<byte[]>` was added, so the directive's four-part proof for a new public type
was never triggered (ADR-0027). What the uniform rule costs is recorded rather than hidden: **a `304` through this
framework cannot state the stored representation's length**. Phase 2.10 decided that as the answer rather than deferring
it — the value RFC 9110 §8.6 binds such a header to is a 200 this framework never computes, so the forbid stays and the
question has a named reopening trigger (ADR-0029, D1).

227 web tests and 152 adapter tests, both green on JDK 17 and JDK 21 with `mvn -o -B -ntp clean verify`; 23 and 24 ArchUnit
rules, unchanged and unweakened; line coverage 98.9% and 99.4%; ten mutation probes (A–I plus a stronger F) and every one
of them broke a named test, with the mutated sources verified byte-identical after each revert; public API surface
unchanged — the two rules are private statics inside `Response` and the HEAD length is derived inside a package-private
bridge.

```java
// Application assembly is the only place that knows both the contract and the adapter.
Router routes = Router.builder()
    .route(HttpMethod.GET, "/users/{id}", readUser)
    .build();

HttpServerAdapter server = new JdkHttpServerAdapter(
    new InetSocketAddress("127.0.0.1", 0), routes, threads);   // threads borrowed: stop() will not close them
server.start();
int port = server.port().getAsInt();
// ... serve ...
server.close();                                                  // close() == stop(): idempotent, never throws
```

## Phase 2.10 in one paragraph

A stabilization phase, so its product is boundaries rather than features, and each one was measured before it was
decided. **Method case is two facts, not one** (ADR-0028): which handler runs follows the folded `HttpMethod`, while
which bytes reach the wire follows the raw token the JDK compares exactly — so `HEAD` gets HEAD's response (fields,
the representation's length, no bytes) and a spelling like `head` gets the ordinary response its representation has,
which replaced ADR-0027 §9's false `Content-length: 0` claim with a named boundary. **The framing matrix is now one
asserted table** (ADR-0029): 200/204/304/HEAD/404/405/500 × length × chunked, `Transfer-Encoding` never written, a
duplicated framing header refused whether its values agree or not. The same record decides the `304` question Phase
2.9.5 left open: a `304` carries no body, and therefore no `Content-Length` either — not because HTTP forbids the
header on that status (RFC 9110 §8.6 explicitly allows a `304` to state one), but because the only in-process source
for the length it would state is an unverifiable handler assertion. What that costs is named as design debt with a
reopening trigger (a real conditional-GET consumer that needs to state the stored representation's size), not as a bug
and not as a limitation without an owner. **The request body has a limit** (ADR-0031):
`barezen.http.jdk.maxRequestBodyBytes`, a `Long` key declared in the module that enforces it, resolved through the
existing `Configuration` at construction, **default 1 MiB** — and the boundary is a boundary: `0` is a legal value that
accepts no body at all, a negative one is rejected at construction, and an adapter with no configuration runs the same
safe default, so the effective ceiling a server enforces is never a value nobody chose.
A declared `Content-Length` above it is refused on the
claim — before the counting read and before the stream is touched, so a 64 MiB claim costs no read, no allocation and
no wait (4 ms measured); an undeclared chunked body is counted and stopped at the first byte past the bound, the
buffer growing to what actually arrived. The answer is `413`, framed like every other adapter answer: the JDK's own
phrase on the status line (`Request Entity Too Large`), the RFC §15.5.14 words `Content Too Large` as a
`text/plain; charset=utf-8` body of 17 bytes, no `Transfer-Encoding`, no `Retry-After` — a configured limit is not a
temporary condition — and nothing path-shaped or limit-numbered, which goes to DEBUG. Because the refusal passes the
same `writesBody` predicate as every other answer, HEAD over the limit states those 17 bytes and sends none, so the
matrix holds for a sixth status for free. Connection recovery stays the JDK's and both outcomes are measured: a
surplus the 64 KiB drain can finish leaves the keep-alive connection alive for the next request, a surplus past it
closes the socket after the answer — and no `Connection: close` is written, because that would punish the first case.
Two limits are stated rather than smoothed over. An adapter built through the `ServiceLoader` provider takes no
configuration — `create(host, port, router)` is frozen by ADR-0025 §7 — so the provider path deliberately runs the safe
default, and **unconfigured is never unbounded**. And a client that declares a large body, sends nothing, and never
half-closes stalls the JDK's request parse before this module exists for that request; that stall, and the carrier
thread it occupies, belong to the server's own `sun.net.httpserver.maxReqTime`, which this framework does not name
because the adapter reads no environment and no properties (ArchUnit, ADR-0013) — a deployment that needs it sets it
where the JVM starts. **The response-body question got an answer instead of a sketch** (ADR-0030): `byte[]` continues,
because there is no consumer, no owner for a writer that runs after `handle` returns, and no live-writer design can
state HEAD's length without re-breaking ADR-0029's matrix; streaming therefore has three named reopening triggers (a
real consumer with a use site, an ownership answer that does not move `ThreadProvider` or `LifecycleParticipant`
semantics, a framing design that keeps every matrix row true) and a named phase — 3.x, when the consumer arrives, with
the forbidden-motivation list inherited as a scope rule rather than a technical verdict.

The one new public API is a constructor — `JdkHttpServerAdapter(InetSocketAddress, Router, Configuration)` — proven
under the phase's nine-question gate (ADR-0031 §Compatibility Impact): an additive overload, the two existing
constructors unchanged, the same nine contract types plus this module's two, and its consumer is the assembly code
ADR-0013/0015 already made responsible for a resolved snapshot. A key that is present but malformed throws
`ConfigurationException` from that constructor — before a port is bound — naming the key, the value and the source.
The behavior change is the point of it: requests over 1 MiB that previously succeeded now receive 413, which is the
vulnerability ADR-0022 §8 recorded as unbounded `readAllBytes()` closing. The build contract around it is now machine-
checked rather than inspected: in addition to `scripts/check-jdk-baseline.sh`, which has always guarded the *declared*
compiler release, every emitted class file in the reactor must carry major version **61**, on a JDK 17 build and on a
JDK 21 build alike — a foreign major fails `mvn clean verify` from inside the build, and
`scripts/check-class-major.sh` sweeps the whole tree afterwards as a CI step (ADR-0032). 232 web tests, 200 adapter
tests and 249 core tests, green on both JDKs with `mvn -o -B -ntp clean verify` (the core count skips ten
virtual-thread capability cases on JDK 17, where the capability cannot be exercised, and one on JDK 21); 21, 23 and 24
ArchUnit rules, unchanged and unweakened; line coverage measured on the JDK 21 run at 94.38% core, 98.88% web and
98.17% adapter — the adapter figure sits below its 99.4% pre-phase baseline because the limit's growth and refusal
paths added lines the earlier suite never reached; the limit itself pinned by 28 raw-socket tests, because a rule
about framing cannot be tested through a client that rewrites framing.

```java
// Configuration reaches the adapter the way ADR-0013 says it must: as an argument, resolved by the application.
Configuration configuration = Configuration.standard(Path.of("config/application.properties"));

JdkHttpServerAdapter server = new JdkHttpServerAdapter(
    new InetSocketAddress("127.0.0.1", 8080), routes, configuration);   // barezen.http.jdk.maxRequestBodyBytes,
                                                                        // 1 MiB if the key is absent
server.start();
// A key that is present but not a number throws ConfigurationException from the constructor above — before a port is
// bound — naming the key, the value and the source, so a misconfigured limit is a startup error and not a surprise.
```

What the phase leaves is a list of named boundaries rather than a list of tasks: a `304` that cannot state a stored
representation's length (ADR-0029, reopens with a real conditional-GET consumer), the lowercase-`head` asymmetry
(ADR-0028, reopens when a client is measured to need it, which no mainstream client does), a stalled `Content-Length`
claim the limit cannot reach because the JDK's parser owns the wait (ADR-0031, whose knob is
`sun.net.httpserver.maxReqTime`, set where the JVM starts), the fixed three-second stop grace and the bind-never-started
window (ADR-0024), the JDK's header canonicalization and lost arrival order (ADR-0022 §5), `byte[]` as the response body
with streaming deferred to 3.x (ADR-0030), and `OPTIONS *`, which this adapter never sees because the server's context
lookup refuses it (ADR-0026; ordinary `OPTIONS /` is routed and answered normally). Each carries its trigger in the
record that defers it.
