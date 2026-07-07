# Changelog

All notable changes to BareZen-web. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
headings, and the project tracks the phases it names rather than a semver release history — the current version is
`0.1.0-SNAPSHOT`, no release artifact has been published, and no release date is claimed here.

Dates are taken from `git log`, not from a plan.

Two claims inherited from the 2.x entries are not true of this environment and were corrected in Phase 3.3 (ADR-0049):
the 2.15/2.16 lines say tests were green "on JDK 17 and JDK 21", and no entry can show a CI run. JDK 17 is not installed
here, `git remote -v` is empty so `.github/workflows/backend-ci.yml` has never executed, and the builds that produced the
numbers below ran on Maven's JDK 24 with `maven.compiler.release=17`. The bytecode claim survives because it is asserted
inside the build (`ClassMajorContractTest`, `scripts/check-class-major.sh`), not by a matrix that never ran.

## [Unreleased]

The whole 2.x line and the first phase of the 3.x line live under `0.1.0-SNAPSHOT` on `main`. Once a release is actually
cut, the phases below get grouped under its tag with the real release date; until then this section is the honest state.
Phase 3.1 is the first phase after the 2.x line closed, and it is the phase ADR-0016 §6 deferred the extension mechanism
to.

### Phase 3.4 — HTTPS and Static Content (2026-10-06)

- TLS on the framework's own server, turned on by naming a keystore and never by a flag:
  `barezen.http.jdk.tls.keystorePath` with `...keystorePassword`, both with no default, resolved once at construction.
  A path without a password fails the construction naming both keys; a blank path reads as no path. `Tls` is
  package-private, so `JdkHttpServerAdapter`'s public surface is unchanged and no fourth constructor was added
  (ADR-0050).
- The password appears in no message this code can write and in no log line, asserted rather than reviewed; protocol,
  ciphers and mTLS are deliberately unconfigured, so no weaker default can arrive by accident. `isRunning()`, `port()`
  and `stop()` behave exactly as they do in plaintext (ADR-0024).
- `framework/barezen-content`: `StaticContent.file(path)` is one `Handler` per route. An `ETag` of eight bytes of the
  content's SHA-256 (no clock, no mtime, survives a restart), `If-None-Match` answered with a body-free 304 that carries
  the tag forward, weak validators and comma lists handled, a fixed extension table rather than the host's MIME database,
  unknown types falling to `application/octet-stream`, and a plain 404 naming no path when the file is gone (ADR-0051).
- One file per route, deliberately: the router refuses a wildcard by name (ADR-0019 §19) and a static fall-through would
  be a middleware chain (ADR-0033). The consequence is that no request ever supplies a path, so traversal is structurally
  absent rather than defended against.
- **ADR-0029 D1 re-measured and kept.** Its named trigger — a real conditional-GET consumer — arrived, and this is the
  first component that genuinely knows the stored representation's size. Measured over a socket: 200 states
  `Content-Length: 14`, HEAD states 14 and sends nothing, 304 states nothing. Stating it would need a channel from
  handler to adapter for a field no client here can act on, so the decision stands on new evidence rather than on the
  old absence of one.
- 12 TLS tests (keystore generated per run by the running JDK's `keytool`, nothing committed) + 22 content tests + 6
  new architecture rules; spikes fired 4 content rules (2/1/1/1) and `no_thread_local` (3); mutations T1–T4 and
  C1–C4, C6 killed with every file md5-restored, C5 recorded as not expressible rather than as passing.
- No existing rule, contract or public type needed amending; the content module's "depends on the contract only" rule is
  javac-enforced for its upper half, which is stated rather than counted as a passing guard.

### Phase 3.3 — A Second Server, as a Servlet (2026-10-06)

- `framework/barezen-http-servlet`: `BarezenServlet` routes the same `Router` inside an existing Tomcat, Jetty or Wildfly
  deployment — the first integration that makes "adopt BareZen" mean "add a jar" instead of "replace your server"
  (ADR-0049).
- It is deliberately **not** an `HttpServerAdapter`: ADR-0024's contract is one instance owning a bound port, and a servlet
  owns none. One public class, one declared public method (`init`), two constants; `ServletBridge` is package-private for
  ADR-0022 §9's reason.
- The route table arrives by constructor or by one `ServletContext` attribute read once in `init()` — the declarative
  deployment needs a no-arg constructor, and one key of one type read once is ADR-0014's explicit registration, not the
  lookup ADR-0006 refuses.
- Measured against a real container before asserted: Tomcat frames an unstated body as `Transfer-Encoding: chunked` where
  the JDK adapter emits `Content-Length`, so the bridge states the length and ADR-0029's single framing matrix now holds
  across two servers; 204/304 state nothing; HEAD's suppression and the lowercase-`head` asymmetry reproduce from a
  different cause; the routed path is raw `getRequestURI()` minus the context path.
- Same 1 MiB request-body limit as the JDK adapter (claim refused before the read, counted past the bound), resolved once
  from the container's init-parameter so this module needs no dependency on the kernel. A bad or negative value fails the
  deployment; `0` is a policy.
- Recorded rather than smoothed over: three refusals never reach the servlet (encoded slash, a body short of its declared
  length, `TRACE`) and arrive as Tomcat's own HTML — including an exception report — and `Content-Type` whitespace is
  re-written by the container. A miswired deployment comes down (its requests get the container's 404), but `start()`
  does not throw unless the container is configured to fail the context, so no fail-fast is claimed.
- 58 module tests (18 contract semantics over real sockets, 7 body-limit, 10 assembly with no container, 4 deployment
  including a `/app` context, 8 ArchUnit rules, 7 surface, 4 class-major). One spike class fired 6 of the 8 rules
  (violations 1/3/1/1/1/1); the two cross-module rules can only be enforced by javac here and say so. Mutations M1–M5 and
  M7 killed, M6 shown not to compile, every file md5-restored. Coverage gate met at 85%.
- Docs corrected against `mvn -version`: this build runs on Maven's JDK 24 with `release 17`, not on JDK 21 as ADR-0047 and
  ADR-0048 first said; JDK 17 is not installed here and there is no git remote, so the CI matrix has never executed. The
  bytecode claim rests on `ClassMajorContractTest` and `scripts/check-class-major.sh`, which run inside the build.

### Phase 3.2 — Plugin Discovery (2026-10-06)

- `com.barezen.core.plugin.PluginDiscovery`: `discover()` and `discover(ClassLoader)` read
  `META-INF/services/com.barezen.core.plugin.Plugin` and return a fresh immutable list in provider-configuration order.
  It keeps nothing, runs no lifecycle phase, and answers no lookup — the search is a function from a classpath to a list
  (ADR-0048).
- Supersedes ADR-0016 §7's refusal while keeping its concern as a build fact: `the_assembler_and_the_event_bus_do_not_scan_or_reflect`
  still forbids `ServiceLoader.load` in `core.di` and `core.event`, spike-proven again in this phase (1 violation, build
  failed). No architecture rule needed amending.
- A discoverable plugin is one a public no-argument constructor can build, so a plugin needing a `DataSource` stays the
  application's to construct. None of the three shipped plugins is discoverable; `examples/.../ReportsPlugin.java` is, and
  it is announced by a real services file so the tests run against a live `ServiceLoader` rather than a mock.
- Discovered and hand-built plugins meet only at `PluginRegistration.validate`, which honours declared dependencies across
  the boundary and refuses a duplicate id whichever side claimed it.
- A provider that cannot be loaded aborts the call with the JDK's `ServiceConfigurationError` naming it — booting without
  an integration nobody can see missing was refused as the undebuggable failure (ADR-0004's cause-preserving rule).
- 11 `PluginDiscoveryTest` + 2 new `PluginExampleTest` tests (core 264 → 275, examples 26 → 28); mutations M1–M4 (cache the
  list, skip a broken provider, ignore the caller's loader, drop the null check) all killed and every source md5-restored. M1
  leaked across tests, which is the registry failure mode the no-state rule exists to prevent.

### Phase 3.1 — The First Extension Point (2026-10-06)

- `com.barezen.web.RouteContributor` is the framework's first extension point and the tenth contracted type: one method
  taking the `Router.Builder` the application already holds, declared by the component that owns routes because
  `barezen-web` names no core type and could not receive a generic extension token (ADR-0047). ADR-0016 §6's forward
  sentence — the mechanism ships with the first extension point, in the phase that owns it — is discharged here.
- `Router.Builder.contributing(Iterable<? extends RouteContributor>)` runs the list in the caller's order and returns
  `this`; it is `route` over a list rather than a third way to register, so the route-table allow-list grew one name and
  the family it refuses (`size`, `routes`, `replace`, `reload`) is still absent. A contributed route colliding with any
  other is refused by ADR-0019 §9's existing rule whichever side registered first.
- `com.barezen.plugins.routes.RouteContributions.of` narrows `Plugin`s to contributors — the one `instanceof` in the
  mechanism, and the only namespace where `Plugin` and a web type may meet (ADR-0042 rule ①). A pure function on
  ADR-0041's model: no state, no registry, no lookup, no phase.
- `Plugin`, `PluginDescriptor`, `PluginRegistration` and `LifecycleOrchestrator` are unchanged, and there is no
  `PluginContext`, no token, no annotation and no route enumeration. The builder → plugins → `start()` → `build()` →
  adapter sequence ADR-0016 left to applications collapses to builder → contribute → build → start.
- `PluginExample` gained `ReportsPlugin`, a plugin that owns an endpoint, and `PluginExampleTest` serves its contributed
  route over a real socket through the real JDK adapter — the example-level proof, not a test-local fixture.
- 16 `RouteContributorTest` + 12 `RouteContributionsTest` (web 232 → 248, plugins 102 → 114); both amended allow-lists
  spike-proven to still bite (1 violation each, build failed); five mutations M1–M5 killed and every source restored
  md5-identical.
- Left open by name with their triggers in ADR-0047: plugin discovery (3.2), route enumeration (the OpenAPI double-write's
  real fix), a second extension point, and per-route security policy.

### Phase 2.16 — Examples / Release Hardening (2026-10-04)

- `examples/` as one leaf Maven module wired last in the reactor, containing seven runnable programs on the public
  developer API only: `HelloWorld`, `RoutingExample`, `RequestResponseExample`, `SecurityExample`, `DatabaseExample`,
  `PluginExample`, `OpenApiExample`.
- Each example splits a package-private `router()` / `wiring()` factory from a `public static main(String[])` that
  binds a real socket and self-verifies, plus a TestKit-driven test the reactor runs — 25 example tests, all green
  on JDK 17 and JDK 21.
- ADR-0045 records the shape of the leaf: "public API only" enforced by the compiler rather than by review; the
  example's H2 compile dependency (framework rule holds where it was set — ADR-0038 binds framework modules, an
  application names its own driver); the four-line `UserRepository.PasswordHashing` adapter as the composition seam
  the framework deliberately keeps in place.
- `docs/examples/README.md`: build-and-test invocation, the `java -cp` recipe for running each `main()`, the observed
  output of all seven, and a reading order.
- README current state and layout updated for the 2.x completion; the module table's two placeholder rows replaced
  with named debt classifications (DEFERRED with a reopen trigger, NON-GOAL).
- `examples/contract/ClassMajorContractTest` so the emitted-major contract holds inside `mvn verify` for every
  emitting module, not only in the after-the-fact shell guard.

### Phase 2.15 — TestKit / Developer Experience (2026-10-04)

- `framework/barezen-testkit` as the reactor's second top module: `TestServer` binds the real JDK adapter to
  `127.0.0.1:0`; `TestHttpClient` speaks the seven verbs over `java.net.http.HttpClient` with
  `followRedirects(NEVER)`; `TestFixtures` hands out the counting and principal-capturing handlers; `TestDatabase`
  wraps an application-supplied `DataSource` and owns only the reset; `Assertions` gives the response / count /
  identity checks.
- Six ArchUnit rules enforce the isolation: nothing in the framework depends on testkit (the isolation rule itself),
  testkit never names plugins, no driver is named in main sources (`javax.sql` allowed on purpose), no thread-local,
  no system-property or environment reads, no static mutable state.
- ADR-0044 records why a new module is legal here when 2.14's generator was forced to stay inside `barezen-plugins`
  — testkit's dependencies all point downward, so no sideways edge is drawn at the reactor top.
- `docs/testkit/`: quick start, first integration test, testing security, testing database, testing OpenAPI.
- 77 testkit tests, seven mutations (the directive's six plus a wrong-echo handler) all killed and restored
  byte-identical.

### Phase 2.14 — OpenAPI (2026-10-04)

- The OpenAPI generator lives *inside* `com.barezen.plugins.openapi`, not as a new module: a framework module naming
  plugin types is the exact edge ADR-0042 rule ① forbids, so the placement was measured against the fence, not
  preferred (`ADR-0043`).
- Schemas are declared data the application writes, not classes the framework reflects — cycle safety, stable
  property order, and "no scanning" are structural properties. OpenAPI 3.0.3 JSON only, no YAML (two determinism
  proofs for one model); `operationId` derived rather than declared, so a duplicate fails loudly instead of silently
  colliding.
- `ApiDocument.build()` refuses six malformed-document categories up front.
- Golden-bytes determinism: the same declared document renders the same bytes every time.
- Two generator bugs caught by the tests first (a `List.add` returning always-true where `Set.add` was meant; a
  `$ref` walk that stopped at nesting). 102 plugin-module tests, eight mutations M1–M8 killed and byte-restored.

### Phase 2.13 — Plugin Ecosystem (2026-10-04)

- `framework/barezen-plugins` on the SPI already in core: `AuthPlugin` fills security's existing `Authentication`
  port, `UserPlugin` fills `Authorization`, `OpenApiPlugin` collects declared `OperationMetadata` only, generating no
  document. The framework never names a plugin back — the direction is one way (`ADR-0042`).
- `PluginRegistration.validate` is a pure static verifier: refuses duplicate id, missing dependency, and cycle
  all-or-nothing, and returns the set ordered so every dependency precedes its dependent (`ADR-0041`).
- Seven ArchUnit rules hold the boundary; every rule spike-proven to bite (violation counts 2/2/1/2/1/1/1) before
  the spikes were deleted. Six mutations M1–M6 killed.

### Phase 2.12 — Data (2026-10-04)

- `framework/barezen-data`: `Database(DataSource)` with zero compile dependencies, a `Transaction` whose
  `commit` / `rollback` are package-private so a repository cannot decide, `DataAccessException` as the single
  translation point, and a `Migrator` with a history table the framework owns (`ADR-0038`).
- Transaction semantics measured, not asserted: a first-draft promise that a failed migration leaves no trace was
  falsified by a standalone probe — H2 commits DDL implicitly — and rewritten to one honest sentence about the table
  the framework owns (`ADR-0040`).
- Nesting gets an explicit answer: a second `transaction(...)` opens an independent transaction on a second
  connection, pinned by tests, not documented-and-hoped (`ADR-0039`).

### Phase 2.11 — Security (2026-10-04)

- `framework/barezen-security`: `SecurityMiddleware` is a `Handler` decorator, three authentication states
  (`Authenticated`, `Failure`, `Anonymous` — `ADR-0034`), opaque revocable sessions rather than JWTs (`ADR-0036`),
  PBKDF2-HMAC-SHA256 password hashing (`ADR-0035`), and the 401/403 boundary — identity is delivered to a
  `SecuredHandler` as a parameter, never a thread-local (`ADR-0033`).
- Attached to the web contract without widening it — `barezen-web`'s shipped jar still declares zero compile
  dependencies. 99 security tests (7 ArchUnit rules, 4 class-major, 6 socket-level integration), fourteen mutation
  probes SM1–SM14 covering the authenticated gate, the 401/403/500 mapping, context delivery, permission question,
  bearer comparison, expiry boundary, session retirement and rotation, iteration guard, salt randomness and
  credential disclosure.

### Phase 2.10 — HTTP semantics stabilization & contract hardening (2026-10-01)

- The HEAD framing boundary: folded method decides the handler, raw token decides the wire bytes (`ADR-0028`).
- One asserted framing matrix — 200/204/304/HEAD/404/405/500 × length × chunked, `Transfer-Encoding` never written,
  a duplicated framing header refused whether its values agree or not (`ADR-0029`).
- Response body stays `byte[]` — the streaming design was measured against three named reopening triggers rather
  than skipped (`ADR-0030`).
- The request-body limit and its 413 answer (`ADR-0031`).
- The class-major contract: `scripts/check-jdk-baseline.sh` guards the declared release and
  `scripts/check-class-major.sh` guards the emitted bytes, so `-Dmaven.compiler.release=21` cannot quietly produce
  major-65 bytecode that a JDK 17 deployment cannot load (`ADR-0032`).

### Phases 2.1 – 2.9.6

Earlier phases are recorded as individual ADRs in `docs/adr/` (0001–0027) and in the per-phase paragraphs of
`README.md`; a `0.1.0-SNAPSHOT` line has one rolling history, so this file summarises the recent phases by the
decisions they made rather than by a semver release cadence.
