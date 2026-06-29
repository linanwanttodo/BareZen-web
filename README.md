# BareZen-web

A lightweight, modular, plugin-based general-purpose Java Web framework. Package: `com.barezen`.

Design principles: **Simple Core. Composable Everything. Plug and Play. Secure by Default. Built to Extend.**

## Current state

Phase 2.9 (HTTP Runtime — JDK adapter) is implemented. Everything else is design only.

| Module | Phase | State |
| --- | --- | --- |
| `barezen-parent`, `barezen-bom` | 2.1 | build skeleton, enforcer rules, JDK baseline |
| `framework/barezen-core` | 2.1–2.6 | lifecycle engine, thread capability, configuration, dependency injection, assembly boundary, plugin SPI, event bus |
| `framework/barezen-web` | 2.7–2.9 | HTTP contract (`Handler`, `Request`, `Response`, `Headers`, `HttpMethod`), an immutable route table (`Router`, `RouteMatch`) and the server boundary (`HttpServerAdapter`, `HttpServerAdapterProvider`) — still no server, no middleware, no context |
| `framework/barezen-http-jdk` | 2.9–2.9.4 | the first adapter: `com.sun.net.httpserver` behind the contract, with its `ServiceLoader` provider |
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
```

`mvn verify` runs unit tests, ArchUnit architecture rules and an 85% line-coverage gate on `barezen-core`, on
`barezen-web` and on `barezen-http-jdk`. The gate runs on JDK 21 only: on JDK 17 the virtual thread provider is
unreachable by construction, so that half of the bundle is not coverable there without asserting dead code is dead
(ADR-0002, ADR-0010). JDK 17 in
CI proves compatibility, JDK 21 proves compatibility and quality.
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
docs/adr/                   architecture decision records, 0001-0025
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
`/users/a%2Fb` stays one segment and `/users/%2e%2e/admin` is never normalized (ADR-0022 §2–§4). Two honest limitations
are asserted as tests rather than glossed: `com.sun.net.httpserver.Headers` canonicalizes field names
(`X-MixedCase` arrives as `X-mixedcase`) and is hash-backed, so arrival order is gone before the bridge reads it
(ADR-0022 §5); and a HEAD response carries fields and no body and no `Content-Length`, so a HEAD client cannot discover a
representation's size through this adapter (ADR-0022 §7). Errors are responses: 404 names no path, 405 carries a stable
adapter-local `Allow`, a handler's `RuntimeException` is logged with its throwable and answered with `Internal Server
Error` alone, a target the contract refuses or a body that stops arriving is a 400 before anything is committed, and a
write that fails after the status line is logged once at DEBUG and never answered with a second status (ADR-0023 §2–§6).
`OPTIONS *` never reaches the dispatcher — the JDK answers it and logs `No context found for request` — which is recorded
as design debt rather than fixed by changing the frozen `Router` contract (ADR-0023 §9). Discovery is the last piece: the
module registers its provider in `META-INF/services`, `ServiceLoader` finds it, `id()` is `"jdk"`, `create` hands back a
fresh unstarted adapter each call, and nothing in this module calls `ServiceLoader` itself, because discovery may only
ever *discover* — the selection stays in application assembly (ADR-0025 §1–§8). 116 tests, 24 ArchUnit rules over package
boundaries rather than class names, an 85% line-coverage gate on JDK 21, and all eleven mutation probes fired; 2.9.1–2.9.4
are the request/response bridge, the error semantics and the provider, delivered with 2.9 as one runtime
(ADR-0021–ADR-0025).

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
