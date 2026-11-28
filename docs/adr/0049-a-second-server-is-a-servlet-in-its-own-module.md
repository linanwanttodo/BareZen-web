# ADR-0049: A Second Server Is a Servlet in Its Own Module — The Container Owns the Lifecycle, the Contract Owns the Answer, and the Framework Owns Neither

## Status

accepted
Date: 2026-10-06
Decides: how BareZen routing runs inside an existing servlet container, which module owns the container API, whether a servlet is an `HttpServerAdapter`, how a deployed servlet receives its route table, what the two servers must and must not answer identically, and what the container decides before this module can see anything
Related: ADR-0018 §12 (the contract exists so a second server is possible), ADR-0021 (one module per server implementation), ADR-0022 (raw path, raw query, absent-versus-empty, one field line per value), ADR-0023 (the six answers), ADR-0024 (an adapter is one instance with one lifecycle — the reason a servlet is not one), ADR-0027/§0028/§0029 (framing, HEAD, the matrix), ADR-0031 (the request-body limit), ADR-0033 (security composes at the handler), ADR-0013 (configuration resolved once at assembly), ADR-0001 (the dependency baseline, restated below)

## Context

The framework's stated purpose is a web framework other projects can adopt, and by the end of 2.16 there was exactly one way to run it: `com.sun.net.httpserver`, reached through `barezen-http-jdk`. That server is in the JDK, which was the right first choice for its hermeticity and the wrong final choice for a deployment: no TLS, no HTTP/2, no connection-level tuning, and — decisive for this record — an existing Java web application cannot adopt it without replacing the server it already runs. A Spring Boot, Tomcat, Jetty or Wildfly deployment has a container, a context path, a deployment descriptor and an operations policy about all three.

ADR-0018 §12 anticipated this in one sentence: *"a Jetty or Netty adapter implements this same four-sentence interface and nothing else in this module changes."* Measured against the repository, that sentence needs one correction: the four-sentence interface (`start`, `stop`, `isRunning`, `port`) describes a component that owns a bound port, and a servlet owns nothing. The container instantiates it, initialises it, calls it and destroys it. So the second server arrives not as a second `HttpServerAdapter` but as a second *kind* of integration, and what it must not do is stretch the contract until the word "adapter" means anything with a `service` method.

Everything below was measured through an embedded Tomcat 10.1.34 before it was asserted. The first measurement changed the design: bodies written without a stated length left the container as `Transfer-Encoding: chunked`, where the JDK adapter emits `Content-Length`, so the framing ADR-0029 asserts as one matrix was not one matrix across two servers until this module stated the length.

## Problem

- Which artifact may name a container API, so that the contract, the security decorator and the plugins stay server-agnostic.
- Whether a servlet implements `HttpServerAdapter` — and if not, what it is.
- How a servlet instantiated by a container receives a route table without becoming a service locator.
- Which request fields survive the container's own decoding, and what that makes impossible to claim.
- What an application that switches servers must be able to rely on staying identical.
- What the container decides before this module has a chance to answer anything.

## Options Considered

**A — `barezen-http-tomcat`: an `HttpServerAdapter` that starts an embedded Tomcat.** Kept, but not here. It satisfies ADR-0024's four sentences, and it puts a container at compile scope in a shipped framework artifact: a deployment that never uses it carries Tomcat's CVE surface, and every container version becomes a framework version. Its trigger is a deployment that wants a production server without owning one; named below as 3.3b.

**B — target `javax.servlet`.** Refused: that namespace ended with Java EE 8; Jakarta EE 9+ is `jakarta.servlet`, and every container still deployed speaks it. Supporting both would double the bridge for a market that has already moved.

**C — a `BarezenFilter` in the chain rather than a servlet.** Refused: a filter that terminates a request has to call the container's dispatcher or write the response itself, which is a servlet wearing a `doFilter` signature, and a filter that *doesn't* terminate cannot be the only integration for an application whose container path is BareZen end to end.

**D — a Spring Boot starter / JAX-RS binding.** Refused by ADR-0001's posture and by the enforcer, which already bans Spring at compile scope in this build. A container is the abstraction; a framework binding on top of the container API is the accidental complexity the project's name argues against.

**E — chosen: `barezen-http-servlet`, one public class, `jakarta.servlet-api` at provided scope, Tomcat at test scope only.**

## Decision

### 1. One module, one public class, one public method

`framework/barezen-http-servlet`, package `com.barezen.http.servlet`. `BarezenServlet` is the only public type: one declared public method (`init`), one protected override (`service`), two `public static final String` constants. `ServletBridge` is package-private for ADR-0022 §9's reason — a second public way to build a `Request` or write a `Response` would be a way around the contract's validation.

### 2. A servlet is an integration, not an adapter

`BarezenServlet` implements nothing: not `HttpServerAdapter`, not `LifecycleParticipant`, not `AutoCloseable`. ADR-0024 decided an adapter is one instance that owns a bound port, starts once, stops idempotently and reports `port()`. A servlet cannot answer any of those honestly — the container bound the port, the container decides when the servlet is ready, and `port()` would be a guess read from somebody else's connector. `the_servlet_is_neither_a_participant_nor_an_adapter` is the rule, spike-proven (1 violation when the probe implemented `Handler`).

The routing contract still holds in both worlds, which is what the module *is* for: `Router`, `Handler`, `Request`, `Response`, and the six answers of ADR-0023, unchanged.

### 3. The route table arrives by constructor or by one context attribute

```java
new BarezenServlet(router)                       // programmatic embedding
new BarezenServlet() + ServletContext attribute  // web.xml / @WebServlet
```

The second exists because a container instantiates the class it is given, so a constructor-only servlet cannot be deployed declaratively at all. The attribute is not a service locator, and the difference is checkable: the container owns the bag, this class reads **one** key of **one** type, **once**, in `init()` — no `getAttributeNames()` walk, no lookup during a request, no `require(Class)`, nothing retained that a later caller can ask for. ADR-0006 refuses a component that goes shopping mid-request; this is ADR-0014's explicit registration arriving through the container's own door.

A servlet that gets neither fails to initialise with a message naming the attribute and both remedies, because a route table that arrives as `null` is a deployment that answers 404 forever and looks healthy doing it.

### 4. The raw request URI, minus the context path, is the routed path

`getRequestURI()` is the undecoded target; `getPathInfo()` and `getServletPath()` are decoded by the container and shaped by the servlet mapping, so using them would route a different string than the client sent (ADR-0019 §4/§5). The context path is removed because it is the deployment's namespace, not part of the resource — measured both ways: a router registering `/doc` is reached at `/app/doc` under a `/app` context, and a router registering `/app/doc` matches nothing there and answers the contract's own 404.

Query stays raw with `null` distinct from `""`, headers are copied one line per value (`setHeader` would keep only the last, which is how a second `Set-Cookie` disappears), and the body is read from `getInputStream()` and never through `getParameter*` — parsing a form would be the container consuming the contract's bytes.

### 5. Framing is stated, not left to the container's default

Measured: writing a body without a length yields `Transfer-Encoding: chunked` on Tomcat where the JDK adapter emits `Content-Length`. So `ServletBridge.send` calls `setContentLengthLong(body.length)` before writing, and 204/304/empty answers state nothing at all — the same rule ADR-0029 asserts as one matrix, which now actually holds across two servers.

HEAD is where the two containers agree by different mechanisms. On Tomcat, `HEAD /doc` with the handler returning five bytes yields `Content-Length: 5` and no entity: one component decides suppression and length, so they cannot disagree. The lowercase `head` token still gets the body (the parser compares exactly), which reproduces ADR-0028's asymmetry from a different cause — and both halves are pinned by tests rather than by reasoning.

### 6. The body limit is the same number, configured the container's way

`barezen.maxRequestBodyBytes` as a servlet init-parameter, defaulting to the same 1 MiB the JDK adapter enforces, resolved once in `init()` into a `long` field: a client cannot send more content by choosing a different server, and an unparsable (including blank) or negative value fails the deployment naming the parameter (ADR-0013, ADR-0031). Declared lengths are refused on the claim before a byte is read; undeclared ones are counted to limit + 1.

`Configuration` is deliberately *not* used: this module has no dependency on `barezen-core`, and a declarative deployment cannot hand a constructor a snapshot. A kernel-free adapter is also what keeps the artifact droppable into a war.

### 7. Three refusals belong to the container, and this module cannot see them

Measured on Tomcat 10.1.34, these never reach `BarezenServlet`:

| Client sends | Who answers | What arrives |
| --- | --- | --- |
| `%2F` in the path | Tomcat's URI parser | its own HTML 400, `Content-Length: 842`, signed `Apache Tomcat/10.1.34` |
| a body shorter than its declared length | Tomcat's parser | its own HTML 400, 2172 bytes, including an *Exception Report* |
| `TRACE` | Tomcat's method policy | its own HTML 405, `Allow: OPTIONS` |

The contract's no-leak guarantee (ADR-0023 §4) covers what this application answers, and it is enforced by catching inside the servlet rather than declaring an error page; the container's page is the deployment's to replace. That is a real difference from the JDK adapter, where a bare `%2F` reaches the handler and gets the plain-text 400. Recorded as the deployment-hardening work 3.4 picks up as `<error-page>`/error-representation policy, not papered over here.

One byte-level difference of the same family, measured: Tomcat re-emits `Content-Type` as `text/plain;charset=utf-8` where the application wrote `text/plain; charset=utf-8`. Every other field line goes out verbatim. So "the same bytes from either server" is true of status, framing and every value except that one parameter's whitespace, and the claim is written that way.

### 8. A failed initialisation aborts nothing unless the container is told to

`failCtxIfServletStartFails` (a Tomcat setting) takes the *context* down when a load-on-startup servlet refuses, and even then `start()` returns: measured, requests then get the container's 404, not the contract's. Without it Tomcat logs and marks the servlet unavailable. Either way the application is visibly down rather than quietly serving a stale router — but the fail-fast is the deployment's switch, not the framework's guarantee, and no test here claims one.

## Consequences

- The reactor has eight framework artifacts and the BOM lists the servlet module, so an application chooses a deployment model in its pom and nowhere in its code.
- **The dependency baseline is restated, not relaxed.** ADR-0001 binds `barezen-core` (slf4j-api only) and nothing in this phase changes it. `barezen-web` still ships a jar with zero compile dependencies — Tomcat's API is invisible to it, which is why the same `Router` works in either server. `jakarta.servlet-api` is `provided` in exactly one module, and the container appears at test scope so the claims are measured against a real one.
- `ALLOW_ORDER` now exists twice, in `JdkRequestDispatcher` and in `BarezenServlet`. Neither can own it: it is routing knowledge, so `Router` would have to expose it and `barezen-web` cannot host a helper type without amending its contracted-type list (ADR-0047). A third adapter makes it two duplicates and a decision; ADR-0019 §13's refusal of a route registry is why the order is copied rather than centralised today.
- `RawHttp` exists twice as well, for the same structural reason: a test fixture in one module's `src/test` is not consumable without publishing a test-jar, and a test-jar of socket helpers would put test code on a consumer's classpath, which is the boundary ADR-0044 exists to keep.
- The 500 path in this module catches inside the servlet rather than declaring a container error page, so the response body is the contract's and the exception goes only to the log. `HttpServlet`'s own dispatch is unreachable by construction: delegating to `super.service` does not compile without declaring `ServletException`, which the answer model has no place for.
- Discovery, contributions and the SPI are untouched: this module depends on `barezen-web` and nothing else from the framework, so it is not a plugin, does not become one, and does not know that plugins exist (ADR-0042 rule ①).

## Security implications

- An unbounded request body is the failure this module could have inherited from a container that does not limit raw streams. It does not: the 1 MiB default is enforced in framework code (claim and count), and a limit of 0 is accepted as a policy while a negative one is refused as a misassembly.
- Handler exceptions never reach the socket: measured as a 500 whose entire response — status line, field lines and body — contains no exception class, no message and no server signature.
- Container-side error pages *can* carry an exception report (the truncated-body case above). That is the deployment's surface, and the honest statement of who owns what is in Decision 7; the reopen trigger is 3.4's error-representation work.
- No thread-local, so a container thread pool cannot carry one request's identity into the next; state travels as arguments (ADR-0033, ADR-0034).
- No environment or system-property read; the single knob is visible in the deployment descriptor.
- No new dependency in any consumer's runtime scope: `provided` means the container supplies the classes, and shipping them inside a war is the deployment failure that scope exists to prevent.

## Testing

58 tests in this module, all against a real Tomcat on an OS-assigned port or against `init()` directly:

- `ServletContractSemanticsTest` — 18. Responses (4): a body with its length stated and no `Transfer-Encoding`; two values of one field staying two lines; 204 and 304 carrying neither a length nor a body. Head (3): the stated length surviving suppression; a `head` token getting the body its representation has; a HEAD with no HEAD route refused without a body. Routing (5): 404 naming no path, 405 with `Allow: GET, HEAD`, an unregistered verb still reaching the router, a captured parameter, `%20` surviving as the client sent it. Requests (4): absent versus empty versus present query, a declared body, a chunked body, two request header values reaching the handler. Failures (2): the 500 that leaks nothing anywhere in the message, and two requests on one connection answering independently.
- `ServletRequestBodyLimitTest` — 7: claim over the limit, claim exactly at it, bodiless request passes, limit 0 refuses every body but the head, counted overflow on a chunked body, chunked under the limit, and the refusal being the contract's plain answer rather than the container's page.
- `BarezenServletAssemblyTest` — 11, no container: the constructor path, the attribute path, a null router, a missing router naming both remedies, a wrong-typed attribute naming the class it found, an unparsable limit failing the deployment with its cause, a blank configured limit failing rather than falling back, a negative limit refused, `0` accepted, whitespace tolerated, and the default taken when nothing is configured.
- `ServletDeploymentTest` — 4: a container-instantiated servlet serving the context attribute's router; a miswired deployment serving nothing of the route table (measured, see Decision 8); a `/app` context matching `/doc`; and a router that baked the context path into its own patterns answering the contract's 404.
- `BarezenHttpServletArchitectureTest` — 8 rules. `BarezenServletSurfaceTest` — 7. `contract.ClassMajorContractTest` — 4 (`checked=23 unique-majors=[61]`).
- **Architecture spike**: one temporary public class implementing `Handler`, holding a `ThreadLocal` and a static non-final field, calling `System.getProperty` and naming `com.sun.net.httpserver` fired **six of the eight rules** — violations 1/3/1/1/1/1, 8 tests 6 failures, build failed. The two that did not fire are the cross-module rules (`no_other_module_names_the_servlet_api`, `this_integration_depends_on_the_contract_and_the_facade_only`): nothing on this module's classpath can violate them, because core, security, data, plugins and testkit are not dependencies here. That is stated rather than presented as a passing guard — those two rules are javac-enforced, exactly as ADR-0018 argued for `the_contract_does_not_reach_into_core`.
- **Mutations**, each killed against the full module, each file md5-restored:

| ID | Mutation | Killer | Result |
| --- | --- | --- | --- |
| M1 | do not state the content length | 20 framing assertions across three test classes | 20 failures of 58, build failed |
| M2 | `setHeader` instead of `addHeader` | `twoValuesOfOneFieldStayTwoLines` | 1 failure of 58, build failed |
| M3 | `readAllBytes()` with no bound | `aChunkedBodyPastTheLimitIsRefusedAsItIsCounted` | 1 failure of 58, build failed |
| M4 | route on `getServletPath() + getPathInfo()` (decoded) | `anEncodedPathSegmentIsSeenAsTheClientSentIt` | 1 failure of 58, build failed |
| M5 | answer a handler failure as a 404 | `aHandlerThrowingIsA500ThatCarriesNothingOfTheFailure` | 1 failure of 58, build failed |
| M6 | delegate non-POST verbs to `super.service` | not expressible | does not compile without declaring `ServletException`, which is recorded as the structural fact it is |
| M7 | keep the context path in the routed path | `aRouteIsMatchedInsideTheContext...`, `aRouterThatNamedTheContextPathItselfMatchesNothing` | 2 failures of 58, build failed |
- Coverage gate met at 85% line on this module's own `mvn verify`.
- Full reactor with the new module: `mvn -o -B -ntp clean verify` → BUILD SUCCESS in 9:40 — core 275 (1 skipped), web 248,
  http-jdk 200, **servlet 58**, security 99, data 64, plugins 114, testkit 77, examples 28 = **1163 tests, 0 failures,
  0 errors**; every module's coverage check met; `check-jdk-baseline.sh` and `check-class-major.sh` both exit 0 at
  `checked=589 unique-majors=[61]`. No existing module's rule set needed changing: the servlet module is not on the
  plugins, testkit or http-jdk classpaths, so ADR-0021's "only one module names `com.sun.net.httpserver`" and ADR-0044's
  isolation rules still pass with the same counts.
- **JDK**: measured on the JDK this build runs (Maven on `JAVA_HOME=/usr/lib/jvm/jdk-24.0.2`, emitting major 61 under `release=17`), and ADR-0047/0048's "JDK 21" lines are wrong for the same reason — corrected in this phase's docs commit. Neither JDK 17 nor JDK 21 ran this build: 17 is not installed here, and `git remote -v` is empty so the CI matrix has never executed. `ClassMajorContractTest` and `scripts/check-class-major.sh` are what the bytecode claim rests on.

## Amendment (2026-11-28): a blank configured limit is not an absent limit

The original `init()` guard used `limit != null && !limit.isBlank()`, so an empty or whitespace-only init-parameter
silently took the 1 MiB default. That contradicted Decision 6 and ADR-0013: a configured value that cannot be parsed
must fail deployment rather than look unconfigured. Only `null` now means absent. Empty and whitespace-only values go
through `Long.parseLong(limit.trim())` and fail with `ServletException` naming the parameter and retaining the original
`NumberFormatException`. `BarezenServletAssemblyTest` grows from 10 to 11 tests; the focused class runs 11/11 green.
Absent, `0`, negative and numeric-with-surrounding-whitespace behaviour is unchanged.

## Non-goals

Embedding a container *for* the application (3.3b), TLS and HTTP/2 inside this module (the container's, or 3.4's), static resource serving, `<error-page>` policy, WebSockets, sessions and cookies, Spring or JAX-RS bindings, a `Filter` form of the integration, a servlet that is also a `LifecycleParticipant`, and any second public type in the package.

## Reopening trigger

- **3.3b, a container-managed adapter** (`HttpServerAdapter` over embedded Tomcat or Jetty) opens when a deployment needs a production server and does not already run one. Its cost is a compile-scope container in a shipped artifact, so it needs its own decision about CVE ownership, not an extension of this one.
- **A third adapter** opens the `ALLOW_ORDER` duplication question and the `RawHttp` fixture question at the same time; two duplicates is the point where a home for them beats copying.
- **Error representation** opens in 3.4, where the container's HTML page in Decision 7 becomes a policy an application can set.
- **Servlet 6.1** opens when a container that requires it is a real consumer; the bridge names nothing that changed between 6.0 and 6.1.

## Related Decisions

ADR-0018 §12 (the sentence this is the second half of), ADR-0021 (one module per server), ADR-0022 (the read rules, re-derived against a different container), ADR-0023 (the six answers), ADR-0024 (why a servlet is not an adapter), ADR-0027/§0028/§0029 (framing and HEAD across two servers), ADR-0031 (the limit and its default), ADR-0033 (security stays at the handler), ADR-0013 (one resolution at `init()`), ADR-0044 (why the fixtures are duplicated rather than shipped), ADR-0001 (the baseline, restated and unchanged).
