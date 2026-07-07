# BareZen-web

A lightweight, modular, plugin-based general-purpose Java Web framework. Package: `com.barezen`.

Design principles: **Simple Core. Composable Everything. Plug and Play. Secure by Default. Built to Extend.**

## Current state

Phase 2.9 (HTTP Runtime — JDK adapter), its 2.9.5–2.9.6 contract audit, Phase 2.10 (HTTP semantics stabilization &
contract hardening), Phase 2.11 (Security), Phase 2.12 (Data), Phase 2.13 (Plugin Ecosystem), Phase 2.14 (OpenAPI),
Phase 2.15 (TestKit), Phase 2.16 (Examples / Release Hardening) and Phase 3.1 (the first extension point) are
implemented.
2.10 decided boundaries rather than
adding features:
the HEAD framing boundary (ADR-0028), the asserted framing matrix and the 304 decision (ADR-0029), the response-body
decision that kept `byte[]` (ADR-0030), the request-body limit with its 413 answer (ADR-0031) and the class-major
build contract (ADR-0032). 2.11 added the first capability module since the contract: `framework/barezen-security`, a
`Handler` decorator that authenticates, then authorizes, then answers — with three authentication states (ADR-0034),
PBKDF2 password hashing (ADR-0035), opaque revocable sessions rather than JWTs (ADR-0036) and the 401/403 boundary
(ADR-0037), all attached to the contract without widening it (ADR-0033). 2.12 added persistence as a second capability
module on a boundary that names no driver: `framework/barezen-data`, where `Database(DataSource)` reaches the database
only through a `DataSource` the application supplies (ADR-0038), a `Transaction` the callback receives but can never
decide (ADR-0039), and a migration history table whose one honest promise — recorded if and only if applied — was
rewritten after this build measured that H2 commits DDL implicitly (ADR-0040). 2.13 opened the plugin layer on the SPI
already in core — three plugins sit on the framework and never inside it (ADR-0042) — and 2.14 projects the OpenAPI
plugin's collected metadata into a deterministic JSON document, staying inside the plugins fence because a framework
module naming plugin types is the exact edge ADR-0042 rule 1 refuses (ADR-0043). 2.15 added TestKit as the reactor's
second top module: `framework/barezen-testkit`, a consumer-facing test tool that binds the real JDK adapter to
`127.0.0.1:0`, ships the seven-verb client, the fixtures and the assertions an integration test needs, and depends
downward and never sideways — the isolation ADR-0044 turns into a build fact by rule. 2.16 closed the 2.x line with a
leaf `examples` module: eight runnable programs that use only the public developer API, each proving itself twice —
once by a `main()` that binds a real socket and once by a TestKit-driven test the reactor runs (ADR-0045).

Phase 3.1 opened the extension point ADR-0016 §6 deferred: `com.barezen.web.RouteContributor` is the framework's first
extension point, declared by the component that owns routes because a contract that names no core type cannot receive a
generic extension token (ADR-0047). A contributor receives the `Router.Builder` the application is already holding,
`Router.Builder.contributing` runs the contributors in the caller's order, and
`com.barezen.plugins.routes.RouteContributions.of` is the one `instanceof` separating contributors from other plugins —
the only place `Plugin` and a web type may meet, since core cannot name web and web cannot name core. The sequence ADR-0016
left to the application (builder → construct plugins → `start()` → `build()` → adapter) collapses to builder → contribute
→ build → start, a contributed route colliding with any other is refused by the existing duplicate rule rather than won
by whoever ran last, and there is still no `PluginContext`, no registry, no token, no annotation and no route
enumeration. Phase 3.2 added the search (`PluginDiscovery`, ADR-0048): a plugin announced in `META-INF/services` now arrives from
the classpath instead of being named by the application, in the SPI's own package because the container and the bus
may not scan and an application must not need the shipped plugins to be pluggable. A discoverable plugin is one a
no-argument constructor can build, so the three shipped plugins stay hand-built and the example shows both halves;
the discovered and hand-built sets meet only at `PluginRegistration.validate`, which refuses a duplicate id
whichever side claimed it. Phase 3.3 added the second server, as an integration rather than an adapter (`framework/barezen-http-servlet`,
ADR-0049): one `BarezenServlet` routes the same `Router` inside an existing Tomcat, Jetty or Wildfly deployment, so
"compatible with other projects" stops meaning "replace your server". Measured against a real container before it was
asserted: the bridge states a `Content-Length` the container would otherwise have chunked, so ADR-0029's one framing
matrix now holds across both servers; the routed path is the raw request URI minus the context path, because the
container decodes `getPathInfo()`; and three things the container refuses before this module can see them — an encoded
slash, a body shorter than its declared length, and `TRACE` — arrive as the container's HTML rather than the contract's
plain answer, which is recorded as the difference 3.4 inherits rather than smoothed over.

Phase 3.4 made the framework's own server servable and its own content reachable: HTTPS arrives in the JDK adapter by
naming a keystore (`barezen.http.jdk.tls.keystorePath` and its password) rather than by an `enableTls` flag, and it adds
no public API — a path without a password fails construction, a bad value never falls back quietly, and the password
appears in no message or log line (ADR-0050). Static content arrives as `framework/barezen-content`, one `Handler` per
file, because the router refuses a wildcard and a fall-through would be a middleware chain (ADR-0051). That module is
also the conditional-GET consumer ADR-0029 D1 named as its trigger: measured over a socket, a 200 states its length, a
HEAD states a length it does not send, and a 304 still states nothing — the decision was re-measured with the size finally
knowable in-process and kept, because stating it would need a handler→adapter channel for a field no client here can act
on, and ADR-0027 §5 keeps framing out of handlers' hands.

Phase 3.6 gave the project documentation worth reading (`site/`, ADR-0052): six hand-written pages and one stylesheet,
served at the time by the framework itself through `StaticContent`, with `/openapi.json` from the OpenAPI plugin and
`/health` a lambda. No generator and no template engine — instead two checks that walk the tree and follow every internal
link, which is what caught the first real bug of the phase (`/assets/assets/site.css`). ADR-0016's frontend NON-GOAL asked
for "a real consumer with named rules" before this project would talk about a frontend; what this consumer proved was that
no *templating* capability was needed — and, stated plainly in the record then and corrected in 3.8, that a documentation
folder is not a frontend either.

Phase 3.7 made that site bilingual, in Chinese as well as English (ADR-0053). The language lives in the address — `/` and
`/docs/…` in English, `/zh/` and `/zh/docs/…` in Chinese, one hand-written file per page — so a page is linkable,
cacheable, and decided at assembly rather than at request time. The switcher is two `hreflang` links per page plus a
`<link rel="alternate">` pair in each head; no cookie, no JavaScript, no `Accept-Language` negotiation, because an `ETag`
derived from bytes (ADR-0029) would otherwise become a function of a header. Code blocks, identifiers and ADR numbers stay
byte-identical between the trees, since a translated token is a search that returns nothing. Nothing in the framework
learned a locale: no `Locale` type, no bundle mechanism — ADR-0052's rule that no capability arrives *for* this site held
again, and the cost is named honestly: twelve files are maintained by hand, and the build proves the pairing and the links,
not that the two languages still say the same thing.

Phase 3.8 made those twelve files what they always should have been for a documentation site: **just files** (ADR-0054).
`site/` left the reactor — `Site.java`, `SiteTest.java` and `site/pom.xml` are gone, eleven modules remain — because the
deployment target is GitHub Pages, which serves a folder and runs none of our code, so a module that existed to answer
`GET` for its own resources could not be used where the documentation was going to live. Every address is now relative (a
project page sits under `/<repo>/site/`, where `href="/assets/…"` resolves above the site and 404s) and keeps its `.html`,
which is the one reader-visible cost: no extensionless URLs without JavaScript rewriting, refused for ADR-0052's reason.
The four guarantees `SiteTest` gave moved to `scripts/check-site.sh` — links resolve on disk, every page is reachable, the
two languages pair up, no absolute address. What moved with them: `barezen-content`'s consumer became the eighth example
program (`staticcontent/StaticFileExample`, which also discovered and now pins that `HEAD` is a route you register, not a
`GET` the framework folds in), and three of the four ADR-0029 framing statements are asserted over a socket there instead.

What is left open is left open by name: every deferred item carries a reopening trigger
and a named phase in the ADR that defers it, and nothing beyond what a build measured is claimed as working.

Phase 4.1 built the consumer ADR-0016 had been asking for since 2.5: `app/`, a console application with a sign-in form,
a session and a table of reports (ADR-0055) — the reactor's twelfth module, with a different twelfth than 3.6 had,
since `barezen-app` takes the place the retired `barezen-site` left. It was written **without changing the framework at all**, which is the point —
`AuthPlugin` and `UserPlugin` issue and authorise, `SecurityMiddleware` answers 401 and 403 unchanged, the page and its
stylesheet arrive through `barezen-content`, and the report endpoints are contributed at assembly by an
application-owned `ReportsPlugin` implementing ADR-0047's `RouteContributor`: the first time that extension point was used
by something that is neither an example nor a test. The credential is a bearer token held in one JavaScript variable
rather than a cookie, because `SecurityMiddleware` reads credentials from `Authorization` and no second source exists —
option A in that record is a named reopening trigger, not a gap; the consequence (a reload signs you out) is stated on
the page instead of hidden by `localStorage`. The UI copies the documentation site's design system rather than sharing
its stylesheet, and the page's real deliverable is an answer log that shows each status, header and body as it arrived —
`bob` is seeded with no permissions so that 403 sits next to 401 instead of being described.

| Module | Phase | State |
| --- | --- | --- |
| `barezen-parent`, `barezen-bom` | 2.1 | build skeleton, enforcer rules, JDK baseline |
| `framework/barezen-core` | 2.1–2.6, 3.2 | lifecycle engine, thread capability, configuration, dependency injection, assembly boundary, event bus, and the plugin SPI — `Plugin`, `PluginDescriptor`, `PluginRegistration` (2.13) and since 3.2 `PluginDiscovery`, the search that finds announced plugins and keeps none of them (ADR-0048); the container and the bus still may not scan or enumerate a service |
| `framework/barezen-web` | 2.7–2.9.6, 2.10, 3.1 | HTTP contract (`Handler`, `Request`, `Response`, `Headers`, `HttpMethod`), an immutable route table (`Router`, `RouteMatch`) and the server boundary (`HttpServerAdapter`, `HttpServerAdapterProvider`) — still no server, no middleware, no context; 2.10 tightened `Response`'s constructor rules and asserted the framing matrix; 3.1 added the contract's first extension point, `RouteContributor`, and the `Router.Builder.contributing` runner, so the shipped jar now names ten contracted types and still zero compile dependencies |
| `framework/barezen-http-jdk` | 2.9–2.10, 3.4 | the first adapter: `com.sun.net.httpserver` behind the contract, with its `ServiceLoader` provider; 2.10 added the HEAD framing boundary and the configured request-body limit; 3.4 added HTTPS, turned on by naming a keystore rather than by a flag, with no change to the adapter's public surface (ADR-0050) |
| `framework/barezen-http-servlet` | 3.3 | the second server, as an integration: one `BarezenServlet` routes the same `Router` inside an existing Tomcat, Jetty or Wildfly deployment, with `jakarta.servlet-api` `provided` and the container at test scope only; it is deliberately **not** an `HttpServerAdapter` (ADR-0024 — a servlet owns no port), it states `Content-Length` so ADR-0029's framing matrix holds across both servers, it enforces the same 1 MiB body limit through the container's own init-parameter, and it depends on `barezen-web` and the log facade alone — never on the kernel, security, data, plugins or the other adapter (ADR-0049) |
| `framework/barezen-content` | 3.4 | static content on the contract: `StaticContent.file(path)` is one `Handler` per route — an `ETag` derived from the bytes (no clock, no mtime), `If-None-Match` answered with a body-free 304, a fixed extension table instead of the host's MIME database, and a plain 404 that names no path. One compile dependency (`barezen-web`), no adapter, no wildcard route and no directory listing (ADR-0051); its consumer in shipped code is `examples/staticcontent` since 3.8, and before that it was this repository's own documentation site (ADR-0054) |
| `framework/barezen-security` | 2.11 | security on the contract: a `Handler` decorator (`SecurityMiddleware`), the three-state `AuthenticationResult`, `PasswordHasher` (PBKDF2-HMAC-SHA256), `SessionStore` (opaque, revocable); one compile dependency (`barezen-web`), no server, no JDBC, no logging facade, no thread-local |
| `framework/barezen-data` | 2.12 | persistence through one `javax.sql` boundary: `Database(DataSource)` with zero compile dependencies, `Transaction` whose `commit`/`rollback` are package-private (a repository cannot decide), `DataAccessException` as the single translation point, and a history-table `Migrator`; the framework names no driver, and H2 is test-scope only |
| `framework/barezen-plugins` | 2.13–2.14, 3.1 | the first plugins on the SPI: `AuthPlugin` (fills security's existing `Authentication` port), `UserPlugin` (fills `Authorization` and the plugin-local `PasswordVerifier` port), `OpenApiPlugin` (declared `OperationMetadata` only); 2.14 adds the OpenAPI *generator* beside the collector it projects — schemas as declared data, deterministic JSON, build-time validation, no reflection and no new module (ADR-0043); 3.1 adds `routes.RouteContributions.of`, the pure narrowing from `Plugin`s to `RouteContributor`s, which is the one `instanceof` in the mechanism and the only namespace where `Plugin` and a web type may meet (ADR-0047); `PluginRegistration.validate` orders and refuses; the framework never names a plugin back (ADR-0042), H2 stays test-scope only |
| `framework/barezen-testkit` | 2.15 | a consumer-facing test tool, not a framework service: `TestServer` binds the real JDK adapter to `127.0.0.1:0`, `TestHttpClient` speaks the seven verbs over `java.net.http` with `followRedirects(NEVER)`, `TestFixtures` provides the counting and principal-capturing handlers, `TestDatabase` wraps an application-supplied `DataSource` and owns only the reset, `Assertions` gives the response/count/identity checks (ADR-0044). Five downward compile dependencies (core, web, http-jdk, security, data), no sideways edge to `com.barezen.plugins`, H2 test-scope only, and no class in the shipped reactor may name it — the isolation rule |
| `examples` | 2.16, 3.8 | eight runnable examples on the public API only, one leaf module wired last in the reactor: `HelloWorld`, `RoutingExample`, `RequestResponseExample`, `SecurityExample`, `DatabaseExample`, `PluginExample`, `OpenApiExample`, `StaticFileExample`; `PluginExample` also carries the plugin that announces itself in `META-INF/services` (3.2), and `StaticFileExample` is `barezen-content`'s consumer in shipped code (3.8, ADR-0054). Each has a `main()` that binds a real socket and a TestKit-driven test the build runs; the framework never depends on this leaf, so it cannot drift into the shipped product (ADR-0045) |
| Route enumeration | 3.x | DEFERRED — reopen trigger: the OpenAPI double-write (a route declared once as a route and once as `OperationMetadata`) costing a real project a wrong document; `ADR-0047` records why the merge step stays refused until then |
| Second extension point | 3.x | DEFERRED — reopen trigger: a component other than the route table with a plugin that needs to contribute to it (the named candidate is the event bus, consumerless since 2.6 per ADR-0017) |
| `app` | 4.1 | the console application, the frontend consumer ADR-0016 asked for (ADR-0055): sign in over `POST /api/sessions` for a bearer token, `GET /api/reports` contributed at assembly by an application-owned `ReportsPlugin` (ADR-0047's extension point, used for the first time by something that is neither an example nor a test), the page and its stylesheet served by `barezen-content`, and `/api/document` generated from declared OpenAPI data. A leaf like the examples: public API only, depended on by nothing, absent from the BOM, its own copy of the class-major contract; the application names its own H2 driver at compile scope (ADR-0038's decision made where it belongs) |
| `site/` | 3.6, 3.7, 3.8 | **not a module** (ADR-0054): the project's documentation as twelve static files in two languages — English at `site/index.html` + `site/docs/*.html`, Chinese at `site/zh/…`, one stylesheet, every address relative, deployed as a folder by any static host (GitHub Pages: branch `main`, folder `/site`). It lost a server and with it conditional `GET`, `/health` and a published `/openapi.json`; it gained a shape the deployment target can actually serve. `scripts/check-site.sh` keeps the four guarantees the module's tests used to: links resolve on disk, every page reachable, the two languages paired, no absolute address |
| A frontend for this project | non-goal | **MET in 4.1 by `app/`** (ADR-0055) — and note what "met" means here: ADR-0016's condition was that a frontend *binding inside the framework* needs a real consumer with named rules. The consumer arrived, and **no binding turned out to be needed**: the console is HTML plus `fetch` against the contract's JSON, with no templating, no framework-side locale and no new module type. The framework still has no frontend capability and now has evidence for keeping it that way. 3.6's claim about `site/` stays retracted (ADR-0054); what survived from it is that publishing pages needs no markdown capability |

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
`barezen-web`, on `barezen-http-jdk`, on `barezen-security`, on `barezen-data`, on `barezen-plugins` and on
`barezen-testkit`. The gate runs
on JDK 21 only:
on JDK 17 the virtual
thread provider is unreachable by construction, so that half of the bundle is not coverable there without asserting
dead code is dead (ADR-0002, ADR-0010). JDK 17 in
CI proves compatibility, JDK 21 proves compatibility and quality.
`mvn verify` also asserts what it emitted, not only what it declared: `ClassMajorContractTest` (in every emitting
module) and `scripts/check-class-major.sh` (a CI step after `verify`, on both JDKs) fail if any class file under
`target/classes` or `target/test-classes` has a class-file major other than 61 — the version JDK 17 reads. That is
the half `scripts/check-jdk-baseline.sh` cannot see, because `-Dmaven.compiler.release=21` satisfies the
declared-release script and emits major 65 anyway (ADR-0032).
`.github/workflows/backend-ci.yml` is written to run the same build on both; green on both is the definition of compatible.
**That workflow has never run here**: `git remote -v` is empty, and the build in this environment runs on Maven's JDK 24 with `release 17`
(ADR-0049). What *is* verified on every `mvn verify` is the emitted class-file major, by `ClassMajorContractTest` in each
emitting module and by `scripts/check-class-major.sh` across the tree.

## Guide

Nine short sections, each one the shape a test in this repository already runs. The long-form versions are in
`docs/guide/` (writing a plugin), `docs/testkit/` (testing) and `docs/examples/` (running the eight programs).

### Installation

The BOM carries every framework version, so an application pins one number. Nothing here is published yet: the
repository has no remote and `mvn install` into the local repo is how a sibling project consumes it today (ADR-0046).

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.barezen</groupId><artifactId>barezen-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version><type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency><groupId>com.barezen</groupId><artifactId>barezen-web</artifactId></dependency>
  <dependency><groupId>com.barezen</groupId><artifactId>barezen-http-jdk</artifactId></dependency>
</dependencies>
```

`barezen-web` is the contract and ships with **zero** compile dependencies; `barezen-http-jdk` is the JDK server behind
it. Add `barezen-security`, `barezen-data`, `barezen-content`, `barezen-plugins`, `barezen-http-servlet` or
`barezen-testkit` only for what you use — nothing in the framework depends on any of them (ADR-0042, ADR-0044, ADR-0049,
ADR-0051).

### Quick start

```java
byte[] OK = "ok".getBytes(StandardCharsets.UTF_8);
Router router = Router.builder()
    .route(HttpMethod.GET, "/health", (request, params) ->
        new Response(200, Headers.of("Content-Type", "text/plain"), OK))
    .route(HttpMethod.GET, "/users/{id}", (request, params) ->
        new Response(200, Headers.of("Content-Type", "application/json"),
            ("{\"id\":\"" + params.get("id") + "\"}").getBytes(StandardCharsets.UTF_8)))
    .build();

// Port 0 asks the operating system for a free one; name the port a deployment owns instead.
JdkHttpServerAdapter server = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router);
server.start();
try {
  int port = server.port().getAsInt();
  HttpResponse<String> response = HttpClient.newHttpClient().send(
      HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/users/7")).GET().build(),
      HttpResponse.BodyHandlers.ofString());
  // response.statusCode() == 200, response.body() == {"id":"7"}
} finally {
  server.stop();   // idempotent, never throws, releases only what this adapter created (ADR-0024, ADR-0005)
}
```

There is no `BarezenApplication.run(...)`, no annotation and no classpath scan. Three objects — a `Handler`, a `Router`,
a server — are the whole startup, and ADR-0015 is the record of why the framework does not own the wiring.

### Routing

A route is a method, a raw path pattern and a `Handler`. Patterns are a literal or one `{name}` segment: no wildcard,
no regex, no optional or repeated segment, no prefix match — each is refused by name in ADR-0019 §19 because a matcher
that accepts a prefix turns routing into an ordering question. A duplicate (method + shape) is refused at registration,
never resolved by precedence, so no plugin wins a route by being registered later. A handler receives the request and
the captured parameters as arguments; there is no request-scoped bag, no `getAttribute`, no thread-local.

```java
Router routes = Router.builder()
    .route(HttpMethod.DELETE, "/things/{id}", handler)      // 204: a Response with no body
    .route(HttpMethod.GET, "/files/{path}", other)         // {path} captures ONE segment, encoded as sent
    .build();
Optional<RouteMatch> match = routes.match(request);        // the only question the table answers
Set<HttpMethod> allowed = routes.methodsFor("/things/7");  // empty means 404; non-empty means 405 + Allow
```

Proven by `RouterTest` (232+ tests) and `RoutingExample`.

### HTTP

`Request` carries what the client sent — method, raw path, raw query (absent is distinct from empty), header fields one
per value, body bytes. `Response` carries a status, fields and a `byte[]` body; it refuses `Content-Length` and
`Transfer-Encoding` because framing belongs to the server (ADR-0027 §5), and refuses a body on a status that gets none.
Errors are responses the adapter writes: 404 names no path, 405 carries a stable `Allow`, a handler exception is logged
and answered with the status text alone (ADR-0023), a body over the limit is a 413 (ADR-0031), and the HEAD answer
states the length of the body it does not send (ADR-0028, ADR-0029).

Proven by `JdkResponseBodySemanticsTest`, `JdkErrorSemanticsTest`, `ServletRequestBodyLimitTest` and the framing table in
`RequestResponseExample`.

### Configuration

`Configuration.standard(Path)` layers a properties file, then the environment, then the process's own properties, with a
key's declared default beneath all of them. A value is resolved once, at assembly, and travels as a typed constructor
argument; no component reads `System.getProperty` at request time (ADR-0013). The framework currently defines three keys:
`barezen.runtime.threads`, `barezen.http.jdk.maxRequestBodyBytes`, and the two TLS keys (`...tls.keystorePath` plus
`...Password`, where the presence of the path is the decision and there is no `enableTls` flag — ADR-0050).

```java
Configuration config = Configuration.standard(Path.of("config/application.properties"));
JdkHttpServerAdapter server = new JdkHttpServerAdapter(address, router, config);  // bad values fail HERE, not later
```

Proven by `ConfigurationTest`, `RuntimeConfigTest`, `JdkRequestBodyLimitTest` and `JdkTlsSemanticsTest`.

### Security

`SecurityMiddleware` is a `Handler` decorator, not a filter chain: it authenticates, then authorizes, then calls what it
wrapped, and on every refusal the wrapped handler is never invoked. Authentication answers three states — authenticated,
failed, offered-nothing — because "sent no credential" and "sent a wrong one" are different answers (ADR-0034). Identity
arrives as an argument of the optional `SecuredHandler`, never on a thread (ADR-0033). Sessions are opaque 256-bit
server-side records rather than JWTs, so revocation is one map removal (ADR-0036), passwords hash with PBKDF2-HMAC-SHA256
and the encoded form carries its own iteration count (ADR-0035), and no or failed identity is 401 while a denied identity
is 403 (ADR-0037).

```java
// A handler that needs to know who asked implements SecuredHandler *beside* Handler: the middleware detects it and
// passes the context as a third argument. A bare three-argument lambda is not a Handler, so it cannot be wrapped.
final class ReportHandler implements Handler, SecuredHandler {
  @Override public Response handle(Request r, Map<String, String> p) { throw new IllegalStateException("unreachable"); }
  @Override public Response handle(Request r, Map<String, String> p, SecurityContext ctx) { /* ctx.principal() */ }
}
Handler route = SecurityMiddleware.wrap(authentication, authorization, Permission.of("report:read"), new ReportHandler());
```

Proven by `SecurityMiddlewareTest`, `PasswordHasherTest`, `SessionStoreTest` and the raw-socket
`SecuritySocketSemanticsTest`; see `SecurityExample`.

### Database

`Database` takes a `DataSource` the application supplies — the framework names no driver and no pool (ADR-0038). A
`Transaction` is handed to a callback whose `commit` and `rollback` are package-private, so a repository cannot decide
(ADR-0039); a second `transaction(...)` opens an independent transaction on a second connection, which is the documented
answer to nesting rather than an implicit one. `Migrator` keeps a history table it owns and promises exactly one
sentence: a version is recorded if and only if its `apply` completed — schema integrity after a failure is the product's
answer, because H2 (and others) commit DDL implicitly (ADR-0040).

```java
Database database = new Database(pool);
List<String> names = database.query("select name from users where active = ?", (rs, row) -> rs.getString(1), true);
int updated = database.transaction(tx -> tx.executeUpdate("update users set name = ? where id = ?", name, id));
List<AppliedMigration> applied = Migrator.of(database).migrate(List.of(new AddUsersTable(1L, "users", ddl)));
```

Proven by `DatabaseTest`, `TransactionTest`, `MigratorTest` and `DataArchitectureTest`; see `DatabaseExample`.

### Plugins

A plugin is a `LifecycleParticipant` that can name itself, plus — since 3.1 — an extension point it may implement to
contribute something. `PluginRegistration.validate` orders a set by declared dependencies and refuses a duplicate id or a
cycle; `PluginDiscovery` finds the plugins that announced themselves in `META-INF/services`;
`RouteContributions.of` narrows a plugin list to the ones that contribute routes. A plugin that needs a collaborator
cannot be discovered, because a `ServiceLoader` provider is a no-argument constructor — and the way around that limit is
the service locator ADR-0006 refuses, so the limit stays. `docs/guide/authoring-a-plugin.md` is the long form.

```java
List<Plugin> everything = new ArrayList<>(builtByHand);        // auth, user: each needs a collaborator
everything.addAll(PluginDiscovery.discover());             // reports: announced in META-INF/services
List<Plugin> plugins = PluginRegistration.validate(everything);   // user before auth, whichever order they arrived
Router routes = Router.builder()
    .route(HttpMethod.GET, "/health", health)
    .contributing(RouteContributions.of(plugins))            // the contributors register their own routes
    .build();                                                // frozen here, before anything starts
LifecycleOrchestrator app = LifecycleOrchestrator.builder()
    .register("user", userPlugin).register("auth", authPlugin).build();
app.start();   // initialize all, then start all (ADR-0003); app.stop() reverses it and reports what failed
```

Proven by `PluginRegistrationTest`, `PluginDiscoveryTest`, `RouteContributionsTest`, `RouterPluginIntegrationTest` and
`PluginExample`.

### OpenAPI

Schemas and operations are **declared data the application writes**, not classes the framework reflects: that is what
makes cycle-safety, stable property order and "no scanning" structural properties rather than engineered ones
(ADR-0043). The generator lives inside `barezen-plugins` because a framework module naming plugin types is the edge
ADR-0042 rule ① refuses. OpenAPI 3.0.3 JSON only, no YAML, deterministic to the byte, with six malformed-document
categories refused at `build()`.

```java
ApiDocument document = ApiDocument.builder()
    .info("Users", "1.0").server("https://api.example.com").schema("User", user)
    .operation(Operation.builder(new OperationMetadata(HttpMethod.GET, "/users/{id}", "Fetch one user", List.of("users")))
        .parameter(Parameter.path("id", Schema.integer()))
        .response(ApiResponse.json("200", "the user", Schema.ref("User"))).build())
    .build();
Handler openapi = document.handler();   // the application mounts it: routes.route(GET, "/openapi.json", openapi)
```

Proven by `ApiDocumentTest`, `OpenApiJsonTest` (golden bytes) and `OpenApiExample`. The known gap: a route is declared
once as a route and once as an `OperationMetadata`, because `Router` exposes no route enumeration — ADR-0047 records that
as the unfinished half with its trigger, rather than widening the table to hide it.

### TestKit

`TestServer` binds the real JDK adapter on `127.0.0.1:0`, `TestHttpClient` speaks the seven verbs over
`java.net.http` with `followRedirects(NEVER)`, and nothing in the framework may depend on any of it (ADR-0044). See
`docs/testkit/quick-start.md` and the four worked guides beside it.

## Layout

```
pom.xml                     barezen-parent: owns maven.compiler.release=17, versions, enforcer
barezen-bom/                version contract consumed by applications
framework/barezen-core/     kernel: lifecycle, thread capability, runtime capability, configuration,
                            assembly (di), plugin SPI (descriptor, validator, discovery since 3.2), events, errors
framework/barezen-web/      HTTP contract and route table: handler, request, response, headers, method, router,
                            route match, server adapter contract, and the contract's first extension point
                            (RouteContributor, ADR-0047) — no shipped dependency, no server
framework/barezen-http-jdk/ the JDK adapter: the only module that may name com.sun.net.httpserver, and the only
                            one that registers a ServiceLoader provider; it is also the only place TLS is decided, by
                            the presence of a configured keystore (ADR-0050)
framework/barezen-http-servlet/
                            the second server as an integration: BarezenServlet routes a Router inside an existing
                            servlet container (ADR-0049); jakarta.servlet is provided, Tomcat is test-only, and no
                            other framework module may name a container API
framework/barezen-content/  static content on the contract: one Handler per file, an ETag from the bytes, a body-free
                            304 to a matching If-None-Match, and a fixed type table (ADR-0051) — no directory route
framework/barezen-security/ security on the contract: the Handler decorator, the three-state authentication result,
                            PBKDF2 hashing and the opaque session store — one compile dependency (barezen-web),
                            no server, no JDBC, no logging facade, no thread-local
framework/barezen-data/     persistence behind one javax.sql boundary: Database(DataSource) with zero compile
                            dependencies, the package-private-decider Transaction, DataAccessException as the one
                            translation point, and the history-table Migrator — no driver named, H2 test-scope only
framework/barezen-plugins/  the first plugins on the SPI: AuthPlugin, UserPlugin, OpenApiPlugin — each driven by the
                            one existing lifecycle engine, each filling a port the framework already owned; the
                            2.14 OpenAPI generator projects the collected metadata into a deterministic JSON
                            document inside this same fence (ADR-0043); 3.1 adds routes/RouteContributions, the pure
                            narrowing from Plugins to the contract's RouteContributors (ADR-0047); the framework names
                            no plugin back (ADR-0042), H2 test-scope only
framework/barezen-testkit/  a consumer-facing test tool (Phase 2.15): TestServer binds the real JDK adapter to
                            127.0.0.1:0, TestHttpClient speaks the seven verbs over java.net.http, TestFixtures
                            hands out the counting and principal-capturing handlers, TestDatabase wraps an
                            application-supplied DataSource and owns only the reset, Assertions gives the
                            response/count/identity checks; five downward compile dependencies, no sideways edge
                            to com.barezen.plugins, H2 test-scope only, and the reactor names nothing here
                            (ADR-0044)
app/                      the console application (Phase 4.1, ADR-0055): Console.wiring()/assemble()/
                            router()/start()/seed(), a bearer session over AuthPlugin and UserPlugin, a
                            ReportsPlugin that contributes /api/reports at assembly, and its own page
                            served by barezen-content; a leaf, public API only, not in the BOM
site/                     the project's documentation (Phases 3.6, 3.7, 3.8): index.html, docs/*.html,
                            zh/index.html, zh/docs/*.html and assets/site.css — twelve static files, every
                            address relative, deployed as a folder; not a module, served by no code of ours,
                            checked by scripts/check-site.sh (ADR-0052, ADR-0053, ADR-0054)
examples/                   eight runnable programs (Phases 2.16, 3.8) on the public API only, one leaf module wired last:
                            hello-world, routing, request/response, security, database, plugin, openapi,
                            static-content — each a
                            router()/wiring() factory, a self-verifying main() on 127.0.0.1:0, and a TestKit test the
                            reactor runs; nothing in the framework depends on this leaf (ADR-0045)
docs/adr/                   architecture decision records, 0001-0055
docs/testkit/              quick start and the four worked guides (integration, security, database, openapi)
docs/examples/             the run-a-main() classpath and what each example demonstrates
docs/guide/                  the long-form developer guide (authoring a plugin, and the rest as it is written)
docs/txt/                   the public API reference (`BAREZEN-WEB-PUBLIC-API-REFERENCE.md`) plus the archived
                            phase prompts and audit specs the phases were executed against
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

## Phase 2.11 in one paragraph

`framework/barezen-security` is the first module built *on* the contract rather than *as* part of it, and the whole
phase turns on not widening the contract to fit security. **Security is a decorator** (ADR-0033):
`SecurityMiddleware.wrap(authentication, authorization, delegate)` takes a `Handler` and returns a `Handler`, so an
application composes it exactly where it composes a route, and the adapter holds no security policy. **The context is
an argument, never ambient state** (ADR-0033): a handler that must know who is asking implements `SecuredHandler`
beside `Handler` and the middleware delivers the request-scoped `SecurityContext` to that half; `handle`'s signature
gains nothing, `Request` gains nothing, and the two designs the contract forbids — a context parameter and a
`ThreadLocal` — are refused with an ArchUnit rule each, every rule fired by a deliberately violating class before it
was deleted. **Authentication has three states** (ADR-0034): authenticated, failure-with-a-server-side-reason and
anonymous, sealed, because "offered nothing" and "offered something wrong" are the difference between a login prompt
and a refusal and a boolean collapses them into one wrong answer. **The status boundary is fixed and the handler is
never reached by a refusal** (ADR-0037): 401 for no valid identity, 403 for a denied one, 500 for a security
collaborator that throws, and every refusal test counts the delegate's invocations at zero; default-deny is mechanical
because both `wrap` overloads refuse a null collaborator, and the middleware asks exactly one permission per request —
`Permission.ANY` by default, the route's named permission through the four-argument overload. **Passwords hash with
PBKDF2-HMAC-SHA256 at 600,000 iterations**, 16-byte salt, 32-byte key, encoded self-describing so the count lives
inside the hash and can rise without invalidating anything (ADR-0035); bcrypt was refused as a dependency the enforcer
posture would not take quietly and Argon2 as a JDK-absent library, both named as the reopening conditions; a malformed
hash verifies `false` rather than throwing, comparison is `MessageDigest.isEqual`, and the module cannot name a logger
at all, so a password has no path to a log line. **Sessions are opaque and revocable, not JWTs** (ADR-0036): a 256-bit
random token keys a server-side record, revocation is one removal, rotation kills the old token, expiry is an injected
`Clock` checked on lookup with the dead record retired, and JWT was refused on four concrete grounds — starting with
the one a self-contained token cannot answer: revoke this user right now. Neither a credential, a token value nor a
hash appears in any `toString`. The module ships twelve top-level public types on one compile dependency
(`barezen-web`); `barezen-core` is not on its classpath, the JDK adapter is test-scope only and used by the one test
that proves the decorator survives a real keep-alive connection where three identities arrive back to back and none
bleeds into the next. 99 tests (7 ArchUnit rules, 4 class-major contract tests, 6 socket-level integration tests),
98.96% line coverage on JDK 21 — the two uncovered lines are the `catch` that a JDK without PBKDF2 would take, which
is no JDK — fourteen mutation probes (SM1–SM14) covering the authenticated gate, the 401/403/500 mapping, the context
delivery, the permission question, the bearer comparison, the expiry boundary, session retirement and rotation, the
iteration guard, salt randomness and credential disclosure: every one killed by a named test and the source restored
byte-identical. What 2.11 leaves named rather than built: a shared/distributed session store (ADR-0036, reopens with
a multi-instance deployment), an Argon2 path (ADR-0035, reopens with a GPU/ASIC offline threat model), per-state
challenge responses like `WWW-Authenticate` (ADR-0037, non-goal with a trigger), and a route→permission table the
framework owns (ADR-0033, refused: the policy is the application's).

```java
Authentication tokens = Authentication.bearer("Bearer", sessionToken, new Principal("ada"));   // any Authentication
Authorization canRead = Authorization.of(Map.of(new Principal("ada"), Set.of(Permission.of("user:read"))));

// The decorator returns a Handler, so it is registered where a handler is registered.
Router routes = Router.builder()
    .route(HttpMethod.GET, "/users/{id}",
        SecurityMiddleware.wrap(tokens, canRead, Permission.of("user:read"), readUser))
    .route(HttpMethod.GET, "/health", health)          // unwrapped: public, and visible as such in the table
    .build();

// A handler that needs to know who is asking implements SecuredHandler beside Handler;
// readUser above may be either — the middleware delivers the context when the handler asks for it.
```

## Phase 2.12 in one paragraph

`framework/barezen-data` is persistence designed against measurements rather than against the JDBC specification's
promises, and both of this phase's measurements changed the design before it shipped. **The boundary is
`Database(DataSource)`** (ADR-0038): the module's compile dependencies are none — it speaks `javax.sql` from the JDK
and receives a finished `DataSource` the application's assembly code built from its own configuration (ADR-0013's
hand-over, never a framework-side read), so an application swaps databases by swapping an object and the test database
is structurally unreachable from production because nothing in `src/main` may name `org.h2..`, a `DriverManager`
overload or a `Class.forName`. Every `SQLException` leaves the module as one unchecked `DataAccessException` naming the
operation and the SQL with the driver cause preserved — while a runtime exception thrown by the application's own
transaction callback stays itself, because wrapping a business failure in a persistence exception would report a
database failure that never happened. **A transaction is an object the callback receives and never decides**
(ADR-0039): `commit`/`rollback` are package-private, which turns «不要让每个 repository 自己 commit» from a review rule
into a compile error — guarded further by a reflection probe that fails if the visibility itself is ever widened —
and the decision table is total and recorder-sequenced: returns → commit, throws → rollback (and the JDBC call
sequence `[setAutoCommit:false, commit, rollback, setAutoCommit:true]` is asserted when a commit fails), the
connection closes on every path, a `Transaction` is dead outside the callback that received it. Nesting gets the
explicit answer rather than a discovered one: a second `transaction(...)` call opens an independent transaction on a
second connection — pinned, not documented-and-hoped. **The migration history table is the framework's authority**
(ADR-0040), and that sentence is the product of a falsified draft: the first contract said a failed migration leaves
no trace, and standalone probes on this build measured the opposite — a `CREATE TABLE` inside a rolled-back
transaction survives on the same connection and in a new one, because H2 commits DDL implicitly — while the DML
control rolled back honestly (`control DML rows after rollback: 0`). The rewritten promise is exactly one sentence
about the table the framework owns — *a version is recorded if and only if its `apply` completed* (the history row
shares the change's transaction) — with schema integrity after a failure stated as the product's answer, recovery
defined as re-run-and-repair, and `IF NOT EXISTS`-style idempotent DDL written into `Migration`'s contract because the
retry re-runs the version that never got recorded. Six run states are tested each (fresh, existing, already applied,
duplicate refused before database contact, failed, and the out-of-order refusal — where a first-draft bug that would
have refused every normal up-to-date startup was caught by `anUpToDateListIsNotAnInversionJustBecauseItsFirstVersionIsLow`);
no-ambient and no-driver rules are seven ArchUnit rules in this module, each proven to bite by a deliberately
violating class (`EvilDataProbe`, deleted after its `Tests run: 7, Failures: 7`). The module ships eight public
top-level types (`Database`, `Transaction`, `TransactionCallback`, `RowMapper`, `DataAccessException`, `Migrator`,
`Migration`, `AppliedMigration`), one package, zero compile dependencies, and one test-scope driver fetched from
Central (`com.h2database:h2:2.2.224`) — the only dependency addition in the whole 2.x line, justified here by ADR-0038.
64 tests (8 boundary, 11 lifecycle, 13 transaction, 21 migrator, 7 ArchUnit, 4 class-major), 92.21% line coverage on
JDK 21 above the 85% gate, and sixteen mutation probes (M1–M16) covering every mutation the directive named — delete
rollback, delete commit, skip a migration, reversed version order, leak the connection, leak the statement, commit
after an exception, repository bypass re-opened by making commit public — plus eight more boundary and negative probes:
every one killed by a named test and the source restored byte-identical (SHA-256 verified). What 2.12 leaves named
rather than built: streaming `RowMapper` reads (ADR-0038, reopens with a consumer), ambient/propagated transactions
(ADR-0039, refused and re-openable by a real join-the-outer-unit pattern), down-migrations, checksums and concurrent
runs (ADR-0040, non-goals with triggers), and pooling — the application's decision, made at the `DataSource` seam the
pooled world already agrees on.

```java
// The application owns the DataSource; the framework never builds one and never names a driver.
Database database = new Database(pool);                  // any javax.sql.DataSource

// One statement, one borrowed connection:
long orders = database.query("SELECT COUNT(*) FROM orders", rs -> rs.getLong(1)).get(0);

// A unit of work: the service opens it, the repositories receive it, and only the boundary can decide it.
OrderReceipt receipt = database.transaction(tx -> {
  tx.executeUpdate("INSERT INTO orders (id, total) VALUES (?, ?)", order.id(), order.total());
  tx.executeUpdate("INSERT INTO audit (ref) VALUES (?)", order.id());
  return new OrderReceipt(order.id(), orders + 1);   // commit is package-private: a repository cannot decide this
});

// At startup, whenever the application assembles: safe to run every time.
Migrator.of(database).migrate(List.of(                   // validate → sort → apply ascending → record
    new CreateTable(1, "creates orders", "CREATE TABLE IF NOT EXISTS orders (...)"),
    new CreateTable(2, "creates audit",  "CREATE TABLE IF NOT EXISTS audit (...)")));

// A Migration is the application's own three-method implementation — no framework helper exists, on purpose:
record CreateTable(long version, String description, String sql) implements Migration {
  @Override public void apply(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) { s.execute(sql); }   // defensive DDL: retries must complete
  }
}
```

## Phase 2.13 in one paragraph

`framework/barezen-plugins` is the plugin concept used for real for the first time, and its design constraint was the
maxim read directly: *the moment a plugin system needs its own lifecycle, registry, or context, it has become the
container ADR-0016 refuses* — so this phase adds a participant, not an engine. The three plugins are ordinary
`LifecycleParticipant`s driven by the existing `LifecycleOrchestrator` (no `install`, no `PluginContext`, and
`PluginCompositionTest` asserts the exact `initialize → start → stop` sequence, because "stop skipped" is a named
mutation and a registry whose cleanup can be skipped is a leak, not a lifecycle). **The direction rule is the phase's
actual content** (ADR-0042): `AuthPlugin` implements security's *existing* `Authentication` port and `UserPlugin`
implements `Authorization`, so the arrow is `Security ← SPI ← User Plugin` — and it is not merely unbroken but
demonstrably *unwidened*, because `com.barezen.security`'s public surface gained nothing in this phase and the first
ArchUnit rule (`the_framework_does_not_know_plugins_exist`) fails the build if a framework type ever names a plugin
back. Two design facts fall out of measurements rather than taste. `Credential(scheme, value)` holds a single value
(ADR-0034), which makes a `username:password` login credential a delimiter bug waiting for a username containing `:`
— so auth is *split in two*: `issueSession(username, password)` verifies and returns the token-bearing `Session`,
while `authenticate(Credential)` answers a returning request by store lookup and never consults a password
(`tokenAuthenticates` proves it with a second plugin sharing the store whose verifier rejects everything). And the
user plugin reaches auth's collaborator only through a plugin-local `PasswordVerifier` port handed over at assembly —
auth's four declared fields are asserted to be exactly `SessionStore`/`PasswordVerifier`/`String`/`Duration`, which is
what makes "plugin A mutates plugin B's state" unbuildable rather than discouraged. `OpenApiPlugin` stops at metadata
(Stage 2.13-E): the application *declares* operations beside the routes it registers, and the plugin stores, refuses a
duplicate `(method, path)`, and hands out a **frozen, path-then-method-sorted** snapshot — no scanner and no
generator, because enumerating routes means widening the web contract for a doc tool (ADR-0019's one-question rule)
and generating a document is 2.14's module's decision. On the core side, `PluginRegistration.validate` is the pure
static verifier ADR-0016 §4 predicted but did not write (ADR-0041): duplicate id, missing dependency and cycle are
refused all-or-nothing *before* assembly, unrelated plugins keep the caller's relative order, and the type holds
nothing, looks nothing up and runs no phase — a first draft that refused "ambiguous" order and sorted ties
alphabetically was measured against ADR-0016's "ordering belongs to the assembler" and reversed before it shipped.
The fence was again tested rather than trusted: the JDBC rule was written with `java.sql..` forbidden wholesale, which
**fired on honest code** — `UserRepository`'s `RowMapper` reads a `ResultSet` because `Database.query`'s own public
seam hands one out (ADR-0038's signature) — and the corrected rule names that exception instead of loosening the
fence. Seven architecture rules were each proven to bite by two temporarily-added violating classes (violation counts
2/2/1/2/1/1/1, both spikes deleted, suite re-run green), and the spike itself corrected rule 2's shape: a custom
condition that reports its own violations must sit under `classes().should(...)`, because `noClasses()` inverted its
events and the rule passed silently. 53 tests (8 auth, 13 user, 11 openapi, 10 composition, 7 ArchUnit, 4
class-major), 96.40% line coverage on JDK 21 above the 85% gate, plus core's 15 `PluginRegistrationTest` cases, and
six mutation probes (M1 duplicate-ID accepted, M2 dependency check bypassed, M3 skip registration, M4 stop skipped,
M5 plugin exception swallowed, M6 plugin state leaked) — every one killed by a named test and the source restored
byte-identical (SHA-256 verified). What 2.13 leaves named rather than built: the OpenAPI *document* (2.14, consuming
`OperationMetadata`), `PasswordVerifier`'s promotion into security (this ADR's trigger: a consumer outside the plugin
namespace), plugin discovery (ADR-0016 §7, unchanged), versioned and optional dependencies (ADR-0041 non-goals), and a
`Migrator`-based plugin schema (the path exists; this plugin's schema is one restart-idempotent decision).

```java
// Assembly, in the application's own code: build, validate the declared graph, register under descriptor ids.
Database database = new Database(pool);                       // the app's DataSource, as in 2.12
UserRepository users = new UserRepository(database, PasswordHasher.standard());
UserPlugin user = new UserPlugin(users);                      // fills Authorization (ADR-0042's direction)
SessionStore sessions = SessionStore.of(Clock.systemUTC(), new SecureRandom());
AuthPlugin auth = new AuthPlugin(sessions, user, "Bearer", Duration.ofMinutes(30));
                                                              // ^ auth holds the user plugin as a PasswordVerifier,
                                                              //   never as a type it can mutate
OpenApiPlugin openapi = new OpenApiPlugin();                  // declared metadata only; 2.14 renders it
openapi.add(HttpMethod.POST, "/sessions", "Log in", List.of("auth"));

LifecycleOrchestrator.Builder builder = LifecycleOrchestrator.builder();
for (Plugin plugin : PluginRegistration.validate(List.of(auth, user, openapi))) {
  builder.register(plugin.descriptor().id(), plugin);         // refuses a duplicate id; validate() refused
}                                                             // the missing edge, the cycle, and the collision first

LifecycleOrchestrator app = builder.build();
app.start();                                                  // one engine: initialize all, then start all

// The login route's two halves, kept two methods on purpose (Credential holds ONE value):
String token = auth.issueSession("ada", "hunter2")            // verifies via PasswordVerifier, returns Optional<Session>
    .map(session -> session.token().value())                  //   both halves the caller needs: token AND principal
    .orElse(null);                                            //   null here is a wrong password or unknown user, indistinct
AuthenticationResult result = auth.authenticate(new Credential("Bearer", presentedToken));
                                                              // lookup only — a returning request never re-checks a password
app.stop();
```

## Phase 2.14 in one paragraph

`com.barezen.plugins.openapi` gained the document the 2.13 collector was left waiting for, and the phase's first
decision was forced by measurement rather than taste: the directive's "2.14's module" would be a `barezen-openapi`
module of framework code naming `OperationMetadata` and `OpenApiPlugin` — the exact edge ADR-0042's first ArchUnit
rule (`the_framework_does_not_know_plugins_exist`) exists to refuse — so the generator ships **inside the plugins
package**, and the deviation is recorded in ADR-0043 as the phase's only departure from the directive's wording. The
second decision is the one the maxim is about: schemas are **declared data, never reflected classes**, and the
directive's four conditions for reflection ("deterministic, cycle-safe, no arbitrary classpath scanning, stable
property ordering") are satisfied *structurally* instead of engineered — a `Schema.REFERENCE` node carries a name and
nothing else, the emitter prints the name without recursing, so `User.friends = array($ref User)` is one string deep
and no walk of the document can loop, while property order is literally the order the application wrote. The model is
six types (`Schema`, `Parameter`, `ApiResponse`, `RequestBody`, `Security`, and `Operation` built *from* an
`OperationMetadata` — the one-way projection, because a generator that could write back to the collector lets docs
redefine the API), and `ApiDocument` owns every ordering decision once — paths by template, operations by method
text, components and schemes by name, tags distinct-sorted, servers in declaration order — so `JsonWriter`, the
framework's only JSON emitter and package-private by choice, can be dumb: it writes members in exactly the order
added, which is what makes byte-determinism a property of the code rather than a hope about a library's map policy.
Validation is build-time and total: the six Stage 2.14-F refusal categories each fire at a named site (blank required
field, duplicate `(method, path)`, colliding derived `operationId`, no-response operations and 204/304-with-body per
ADR-0029, every `$ref` resolved recursively against declared components, security requirements naming undeclared
schemes), and `Operation.build` cross-checks path parameters against the route pattern in both directions. Serving is
the framework's own request path: `document.handler()` is a `com.barezen.web.Handler` that answers the bytes rendered
once at assembly — the application mounts it on its router, and no second serving mechanism exists (ADR-0016). The
subset is exactly the named concepts — 3.0.3, path/query/header parameters, one media type, one bearer scheme
(ADR-0036's session as it is actually implemented); cookie, oauth2, YAML, `oneOf` and every other grammar the spec
allows stay non-goals with a named reopening trigger. Two generator bugs fell out of testing rather than review: the
duplicate-path guard called `List.add` — which always returns `true`, so the check could never fire — and reference
resolution stopped at the top node, letting `array($ref Missing)` through; both were fixed from the failing
assertions, not loosened. 49 new tests (19 document, 22 model, 7 writer, 1 README example executed verbatim) on
top of 2.13's 53 — 102 in the module, all
green — every rendering assertion pinned to **golden bytes** (the full document asserted as one exact string, from
`openapi` to `tags`), eight mutations (M1 drop endpoint, M2 drop response, M3 drop parameter, M4 change HTTP method,
M5 wrong path parameter, M6 wrong schema type, M7 non-deterministic ordering, M8 invalid security requirement) all
killed by a named test with the sources restored byte-identical, and the seven architecture rules unchanged and
still green, because the fence already covered the whole `com.barezen` namespace.

```java
// The 2.13 example, continued: the same collector, projected into a served document — public API only.
// This snippet is executed as-is by ReadmeDocumentExampleTest.
OpenApiPlugin openapi = new OpenApiPlugin();
openapi.add(HttpMethod.GET, "/users/{id}", "Fetch one user", List.of("users"));

// The application declares the shapes the collector cannot know (ADR-0043's declared data).
Schema user = Schema.object(List.of(
    new Schema.Property("id", Schema.integer(), true),
    new Schema.Property("name", Schema.string(), true)));

ApiDocument document = ApiDocument.builder()
    .info("Demo", "1.0")
    .schema("User", user)
    .securityScheme(Security.Scheme.bearer("sessionToken"))
    .operations(openapi.operations().stream()                      // the frozen snapshot; one-way projection
        .map(metadata -> Operation.builder(metadata)
            .parameter(Parameter.path("id", Schema.integer()))
            .security(Security.Requirement.of("sessionToken", List.of()))
            .response(ApiResponse.json("200", "the user", Schema.ref("User")))
            .build())
        .toList())
    .build();                                                     // the six refusals run here, or nothing ships

Router router = Router.builder()
    .route(HttpMethod.GET, "/openapi.json", document.handler())   // the rendered bytes, on the app's own router
    .build();

Request request = new Request(HttpMethod.GET, "/openapi.json", null, Headers.empty(), null);
RouteMatch match = router.match(request).orElseThrow();           // the adapter's own call shape
Response response = match.handler().handle(request, match.parameters());
// response.body() is exactly document.toJson()'s bytes
```

## Phase 2.15 in one paragraph

`framework/barezen-testkit` is the reactor's second top module, and the phase's first decision had already been tested
by 2.14's opposite answer: 2.14 could *not* leave the plugins fence because a framework module naming plugin types is
the edge ADR-0042 rule 1 refuses, but TestKit's dependencies all point *downward* — core, web, http-jdk, security, data —
so a new module is legal, and ADR-0044 records exactly that asymmetry (the one thing the phase adds to the directive's
plan). The isolation claim "a test tool nothing in the shipped framework depends on" is made a build fact by the ArchUnit
rule `nothing_in_the_framework_depends_on_testkit`, spike-proven with a class mis-declared into `com.barezen.core` that
reached for a testkit type and was refused. `TestServer` **wraps** `JdkHttpServerAdapter` on `127.0.0.1:0` rather than
faking it — a fake server proves only the fake works, so every framing decision of 2.9–2.10 is exercised for real — reads
its OS-assigned port back through `port()` (empty is a broken adapter, stated as an `IllegalStateException`), and its
`close()` is idempotent and is the only path that releases the port and the adapter's owned `ThreadProvider`. The client
is `java.net.http.HttpClient` — zero new runtime dependencies — with `followRedirects(NEVER)` so a `3xx` arrives as the
status the handler actually returned. `TestFixtures.PrincipalCapturingHandler` implements both `Handler` and
`SecuredHandler` (its plain half is a tripwire, matching `SecurityHttpEndToEndTest`), and every fixture holds its state
*per instance* — two tests prove a second counting or principal handler opens at zero, which is the phase's "fixture
state shared" and "security context leaked" mutations run as ordinary assertions. `TestDatabase` wraps an
application-supplied `DataSource` and owns only the reset; it names no driver, and the ArchUnit driver-ban is written
against `org.h2..` and friends while leaving the `javax.sql` port legal on purpose. `Assertions` is deliberately thin —
status/header/body/JSON-path, a count, an identity — because a JSON reader big enough to *navigate* a body is the only
library cost worth paying, and it is package-private and order-preserving. Two bugs were caught before shipping rather
than discovered by a green build: `PrincipalCapturingHandler`'s first draft implemented only `Handler`, so its `record`
method could never be reached by the middleware, and `TestResponse` built its case-insensitive header map with
`Map.copyOf`, which silently drops the comparator the javadoc promised — both fixed from the contract, not from a
relaxed assertion. 77 tests green on JDK 17 in the module (13 assertions, 14 fixtures, 17 reader, 7 database, 15
end-to-end over a real socket, 4 class-major, 6 architecture, 1 README example executed verbatim), seven mutations — the directive's six (stop omitted, port
not released, fixture state shared, assertion ignored, security context leaked, database not reset) plus a wrong-echo
handler — all killed with a real build failure and every source restored byte-identical, and four worked guides shipped
under `docs/testkit/` (quick start, first integration test, security, database, OpenAPI).

```java
// A TestKit integration test end to end — public API only. Executed as-is by ReadmeTestKitExampleTest.
Router router = Router.builder()
    .route(HttpMethod.GET, "/health", TestFixtures.textHandler("ok"))                 // a plain framework Handler
    .route(HttpMethod.GET, "/who",
        SecurityMiddleware.wrap(TestFixtures.authenticationFor("t0ken", new Principal("ada")),
            Authorization.allowAll(), TestFixtures.principalCapturingHandler()))
    .build();

try (TestServer server = TestServer.start(router)) {                                   // the real JDK adapter, :0 port
  TestHttpClient client = server.client();                                             // java.net.http, NEVER-redirect
  Assertions.assertThat(client.get("/health")).statusIs(200).bodyIs("ok");
  Assertions.assertThat(client.request(HttpMethod.GET, "/who")
      .header("Authorization", "Bearer t0ken").send()).statusIs(200).bodyIs("hello, ada");
  Assertions.assertThat(client.get("/who")).statusIs(401);                             // anonymous never reached the delegate
}                                                                                       // close() released the port here
```

## Phase 3.1 in one paragraph

`com.barezen.web.RouteContributor` is the framework's first extension point, and ADR-0047 is the record of why it is an
interface rather than a mechanism: `barezen-web` ships a jar declaring no compile dependency at all (ADR-0018 §12), so a
generic `ExtensionPoint<T>` token in core could only have been declared *about* routing by somebody other than routing's
owner — which is the `ExtensionRegistry` ADR-0016 §6 refused, wearing a type parameter. The owner therefore declares one
interface taking its own `Router.Builder`, `Router.Builder.contributing` runs the list in the caller's order and returns
`this`, and `com.barezen.plugins.routes.RouteContributions.of` is the single `instanceof` in the whole mechanism because the
plugin namespace is the only one allowed to hold `Plugin` and a web type in one expression (core cannot name web, web cannot
name core, and the framework must not name a plugin — ADR-0042 rule ①). `Plugin`, `PluginDescriptor`,
`PluginRegistration` and `LifecycleOrchestrator` are unchanged: a contribution is assembly-time work, not a fifth lifecycle
phase, and there is no `install()` for ADR-0016 §2's reason. A contributed route is refused by the existing duplicate rule
whichever side registered first, so no plugin wins a route by running later and the tests pin both orderings of the same
collision; a contribution arriving after `build()` cannot reach the frozen table an adapter is serving. The contracted-type
allow-list and the route-table method allow-list were each amended with a consumer already calling them, and each was then
spike-proven to still bite (an eleventh web type: 1 violation, build failed; a `Router$Builder.size()`: 1 violation, build
failed); five mutations M1–M5 (contribution applied to nothing, filter dropped, order reversed, null element skipped,
duplicate swallowed) were each killed by a real failure and each source restored md5-identical. Discovery (3.2), route
enumeration, per-route security policy and a second extension point stay open by name with their triggers in ADR-0047, and
the OpenAPI double-write is recorded there as the honest unfinished half of this line rather than fixed by widening the
route table.

```java
// A plugin that owns an endpoint contributes it instead of holding a builder, and announces itself so an application
// does not have to name its constructor. Both files are verbatim from examples/: ReportsPlugin.java (public, no-arg
// constructor, listed in META-INF/services/com.barezen.core.plugin.Plugin) and PluginExample.java.
public class ReportsPlugin implements Plugin, RouteContributor {
  public ReportsPlugin() {}                                        // required by ServiceLoader
  @Override public PluginDescriptor descriptor() { return new PluginDescriptor("reports", List.of("user")); }
  @Override public void contributeRoutes(Router.Builder routes) {
    routes.route(HttpMethod.GET, "/reports/status", (request, ignored) ->
        new Response(200, Headers.of("Content-Type", "text/plain"), "reports ready".getBytes()));
  }
}

// The two sources meet at the validator: hand-built plugins (each needs a DataSource) plus discovered ones.
List<Plugin> plugins = PluginExample.assemble(wiring, PluginDiscovery.discover());
Router routes = Router.builder()
    .route(HttpMethod.GET, "/health", (request, ignored) ->
        new Response(200, Headers.of("Content-Type", "text/plain"), "ok".getBytes()))
    .contributing(RouteContributions.of(plugins))                  // the narrowing, once (ADR-0047)
    .build();                                                      // frozen before anything starts

// PluginExampleTest, the same table on the real JDK adapter:
try (TestServer server = TestServer.start(routes)) {
  Assertions.assertThat(server.client().get("/reports/status")).statusIs(200).bodyIs("reports ready");
  Assertions.assertThat(server.client().request(HttpMethod.POST, "/reports/status").send()).statusIs(405);
}
```

## Phase 3.2 in one paragraph

`PluginDiscovery` is the search ADR-0016 §7 refused, and this is the separate record §7 said discovery would need
(ADR-0048). It supersedes the refusal and keeps the concern: a container that discovers has stopped being told what
exists, so `core.di` and `core.event` still may not scan, load a class by name or enumerate a service —
`the_assembler_and_the_event_bus_do_not_scan_or_reflect` was spike-proven against this exact call again in this phase (a
`ServiceLoader.load` placed in `ServiceAssembly` fired 1 violation and failed the build). Two static methods return the
plugins announced by `META-INF/services/com.barezen.core.plugin.Plugin`, in provider-configuration order, as a fresh
immutable list every call: nothing is cached, so there is no registry, no `byId`, no instance held past the call, and no
lifecycle phase run on what arrived. The limit is stated rather than designed around — a `ServiceLoader` provider is a
public no-argument constructor, so a plugin that needs a `DataSource` or a `SessionStore` cannot be discovered and stays
the application's to build; that is ADR-0016 §1, and the way around it is the service locator five records refuse. The
two sources meet at one place, `PluginRegistration.validate`, which honours declared dependencies across the boundary and
refuses a duplicate id whether it arrived from a jar or from `new`, and a provider that cannot load aborts the call with
the JDK's own message naming it rather than being skipped. `examples/` carries both halves: `auth`, `user` and `openapi`
constructed by hand, `reports` announced in a services file, contributing its route (ADR-0047) and served over a real
socket by the test. Eleven discovery tests plus two example tests, mutations M1–M4 killed — and M1, the cache, is the one
worth reading, because turning the result into a registry leaked across tests in the same JVM and that is precisely the
failure the no-state rule exists to prevent.

## Phase 3.3 in one paragraph

`framework/barezen-http-servlet` is the second server, and ADR-0049 is the record of why it is not a second *adapter*:
ADR-0024 decided an adapter is one instance owning one bound port with `start`, `stop`, `isRunning` and `port`, and a
servlet owns none of those — the container bound the port, decides when it is ready and destroys it. So `BarezenServlet`
implements nothing the framework offers (rule: `the_servlet_is_neither_a_participant_nor_an_adapter`) and does one thing:
route a `Router` inside a Tomcat, Jetty or Wildfly context that already exists. The public surface is one class, one
declared public method and two constants; `ServletBridge` is package-private because a second public way to build a
`Request` would be a way around the contract's validation. The route table arrives by constructor for embedding, or by one
`ServletContext` attribute read once in `init()` for the deployments where the container instantiates the class the
descriptor names — one key of one type, read once, is ADR-0014's explicit registration and not the lookup ADR-0006
refuses, and a servlet that gets neither fails to initialise naming both remedies.

Every framing claim was measured against a real container before it was written, and the first measurement changed the
design: Tomcat frames a body with no stated length as `Transfer-Encoding: chunked` where the JDK adapter emits
`Content-Length`, so the bridge states the length and ADR-0029's single framing matrix now genuinely covers two servers —
204 and 304 state nothing, `HEAD` keeps the length while the container suppresses the entity, and a lowercase `head` token
gets the body its representation has, which is ADR-0028's asymmetry arriving from a different cause. The routed path is
the raw `getRequestURI()` minus the context path, because the container decodes `getPathInfo()` and would route a string
the client did not send. The request-body limit is the same 1 MiB as the JDK adapter's, resolved once from the container's
own init-parameter so this module needs no dependency on the kernel — which also means the same client cannot send more
content by picking a different server.

What the container decides before this module can see anything is recorded rather than smoothed over: an encoded slash, a
body shorter than its declared length and `TRACE` each come back as Tomcat's own HTML error page — one of them carrying an
exception report — and `Content-Type` whitespace is re-written on the way out. The application's own refusals are the
contract's plain answers, asserted to carry no server signature; replacing the container's page is deployment policy and is
named as 3.4's work. A miswired deployment does come down (its requests get the container's 404, never a stale router),
but `start()` returns unless the container is told to fail the context, so the phase claims no fail-fast it cannot grant.
58 tests run against an embedded Tomcat or against `init()` directly; one spike class fired six of the eight architecture
rules and the two that could not fire are javac-enforced and say so; M1–M5 and M7 were killed and M6 turned out not to
compile, which is itself the structural fact the ADR records.

## Phase 3.4 in one paragraph

Two capabilities, each with the boundary that shaped it. **TLS** is decided by the presence of a configured keystore
(`barezen.http.jdk.tls.keystorePath` plus `barezen.http.jdk.tls.keystorePassword`, both with no default), resolved once
in the constructor that already took a `Configuration`, so `JdkHttpServerAdapter`'s public surface grows by nothing and
there is no `enableTls` that could be `true` with nothing behind it (ADR-0050). A keystore named without a password fails
the deployment naming both keys — the JDK's own failure cannot tell "no password" from "wrong password", and the operator
deserves to know which one was sent. The password appears in no exception message and no log line, both asserted;
protocol, cipher suites and mTLS are deliberately not configurable, so no weaker default can arrive here by accident; and
`start()`, `stop()`, `isRunning()` and `port()` behave identically over an encrypted socket, because TLS is a property of
the wire rather than of who owns the lifecycle (ADR-0024).

**Static content** could not be the thing people expect, because `Router` answers one question — method plus raw path, to
a handler — and its pattern language is a literal or a single `{name}` segment by refusal: `PathPattern`, `PathMatcher`
and `RouteRegistry` are named in ADR-0019 §19 precisely because a prefix match turns routing into an ordering problem,
and a static fall-through is a middleware chain wearing a different name (ADR-0033). So `framework/barezen-content`
serves one file per route, which has a security dividend nobody had to code: no request ever supplies a path, so there
is no traversal to defend against. The `ETag` is eight bytes of the content's SHA-256 — a clock is not an argument here,
so the tag survives a restart, a second machine and a filesystem with no mtime — and `Last-Modified` is refused rather
than deferred, because it would import the very thing the phase keeps out and add a second validator that answers this one
worse. Types come from a fixed table because `Files.probeContentType` reads the host, and two deployments answering one
file differently is a wire-format bug (ADR-0018 §12's rule about behaviour that depends on a platform).

The part worth more than either feature: ADR-0029 D1 deferred the 304-length question until "a real conditional-GET
consumer" arrived, and this is that consumer — the first component that genuinely knows the size of the representation it
is declining to send. Measured over a socket, the matrix is 200 with `Content-Length: 14`, HEAD with `14` and no body, and
304 with no length at all. **The decision stands**: RFC 9110 §8.6.1 permits that length rather than requiring it, the only
way to state it would be a new handler→adapter channel for a fact the handler cannot be trusted to assert (ADR-0027 §5
keeps framing fields away from handlers for exactly this reason), and no client in this framework acts on it. A trigger
met is not a decision reversed; it is the moment the decision gets tested against the thing it was waiting for.

## Phase 3.6 in one paragraph

`site/` is the answer to a row that had said NON-GOAL since 2.5. ADR-0016's refusal was conditional — *"a frontend
binding needs a real consumer with named rules"* — and the consumer has now been built rather than the binding: six
hand-written pages and one stylesheet, served by `StaticContent` through a `Router` on the JDK adapter, with
`/openapi.json` generated from declared data and `/health` a lambda. Nothing in the framework changed to make it work,
which is the finding: the capability the site needed was the capability 3.4 had already earned for its own reasons, and a
template engine would have been a dependency with one consumer.

The form was chosen for what it can be tested against. A generator would put a build step between a page's source and a
reader's browser, and the failure mode of a generated site is silent drift — a template that renders something nobody
re-read. Here the three failure modes that remain are loud and mechanical: a file under `web/` with no route, a link to
a page that is not there, and (since 3.7) a page whose counterpart in the other language is missing or whose `hreflang`
link points somewhere its twin does not live. All three are build failures, and the first one caught a real bug on the
module's opening run, when `web.relativize(asset)` produced `/assets/assets/site.css` and every page would have loaded
without its stylesheet.

Two limitations are in the record rather than in a workaround. The site is served from `target/classes`, because
`StaticContent` needs a `Path` and classpath-resource serving is ADR-0051's named deferral; and the OpenAPI document
covers only the two JSON endpoints, because `ApiResponse` can express a JSON body or no body and describing an HTML page
as bodiless would be a claim about bytes that never arrive. And the honest negative: the site registers no plugin,
contributes no routes and discovers nothing, so ADR-0047 and ADR-0048 still have their only real consumers in
`examples/` and in the tests — a website is proof about serving, not about extension.

Said plainly in 3.6, and no longer true: `app/` gave ADR-0047 a consumer in a running application in 4.1, so the
extension point is no longer exercised only by examples and tests (ADR-0055).

**Corrected by 3.8.** Two of the claims above were wrong in ways that only a deployment exposed. Serving the pages from
`target/classes` through our own adapter was not a capability worth having — it was a server that cannot be deployed where
the documentation goes (ADR-0054) — and calling this consumer the thing that met ADR-0016's frontend condition overstated
it: a folder of prose is not an application UI. Both are retracted there rather than smoothed over here.

## Phase 3.8 in one paragraph

`site/` stopped being a module. The reason is a deployment target: GitHub Pages serves a folder and runs none of our code,
so a `Site.main()` that binds a port and routes twelve files was a server standing between the documentation and the place
it is published — and the local preview it enabled (`/docs/plugins`, an `ETag` reload) was a shape the deployed site could
never have, which is the preview/deploy divergence this project keeps refusing (ADR-0054 option B). The files moved up to
`site/`, every address became relative (a project page lives under `/<repo>/site/`, so `/assets/site.css` would resolve
above the site and 404), and page URLs keep the `.html` their files have. `SiteTest`'s four structural guarantees became
`scripts/check-site.sh`, which checks them against a filesystem instead of a route table; its five socket-level assertions
were not all simply dropped — `barezen-content` gained an eighth example program, `staticcontent/StaticFileExample`, that
registers one route per file and asserts over a real socket the 200-with-a-length, the body-free 304 and the HEAD that
states a length it does not send. Writing that program against the contract also surfaced a fact the site had been hiding:
`HEAD` is not a folded `GET` (ADR-0028), so a GET-only file answers 405 to a HEAD probe — which the pages that used to be
served here would have done too, and nobody sent one, because nothing tested it.

## Phase 4.1 in one paragraph

`app/` is the consumer ADR-0016 asked for, and the finding is what it cost: **nothing** (ADR-0055). The console signs in
over `POST /api/sessions` and gets an opaque bearer token from `AuthPlugin`; `GET /api/session` hands the identity to a
`SecuredHandler` as a third argument because ADR-0033 refuses a thread-local; `/api/reports` is not written by the
application at all — an application-owned `ReportsPlugin` contributes it at assembly through ADR-0047's `RouteContributor`,
and a test asserts the route is in the frozen table before any lifecycle phase has run, which is the promise that makes a
contributor unable to open a socket. The page, its script and its stylesheet are three named routes served by
`barezen-content` (ADR-0051) for `GET` **and** `HEAD`, because 3.8 learned that a GET-only file refuses a HEAD probe. The
document at `/api/document` comes from declared `OperationMetadata` (ADR-0043) and deliberately omits the HTML page,
because `ApiResponse` can describe a JSON body or no body.

Two decisions in that record are worth reading as refusals. The credential is a bearer header rather than a cookie:
`SecurityMiddleware.credentialFrom` reads `Authorization` and is private by design, so a cookie session would need a new
framework seam plus CSRF protection — that is option A, kept as a named reopening trigger rather than built because a
demo page wanted it; and the token lives in one JS variable rather than `localStorage`, because a token stored there is a
token any script on the origin can read. The page's UI is honest about the consequence: reload and you are signed out, in
text, next to the reason.

The second refusal is the stylesheet. The console copies the documentation site's design system — the same six tokens,
band header, measure, tables and notes box — instead of sharing one file, because a static folder and a served application
are two deployment shapes and a shared CSS would make every docs edit an application change. The ~110 duplicated lines are
named in the record rather than left to be rediscovered. What the console adds, the docs page had no reason to carry: a
form, buttons, and an answer log where the status column is the only coloured thing on the page. `bob` is seeded with no
permissions precisely so that 403 sits next to 401 instead of being prose about them, and 16 tests (12 over a real socket,
plus this module's copy of the class-major contract) pin the answers the page shows.
