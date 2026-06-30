# ADR-0026: `OPTIONS *` Semantics — the target this server never delivers, and what "not supported" is pinned by

Status: accepted
Date: 2026-09-30
Decides: that `OPTIONS *` is not supported by this adapter and why that is a measurement rather than a choice; the exact
JDK mechanism that refuses the target before a context, a filter or a handler exists; that the Router is not widened to
answer it; that `OPTIONS *` is not treated as `OPTIONS /` and not made into a `"*"` route; what the server's own answer
looks like on the wire and how a test tells it apart from ours; and that the refusal covers every method token naming `*`,
not only `OPTIONS`
Related: ADR-0018, ADR-0019, ADR-0021, ADR-0022, ADR-0023, ADR-0025, ADR-0027

## Context

`OPTIONS * HTTP/1.1` is the request line that asks about the server instead of about a resource: HTTP defines `OPTIONS`
with an origin-form or absolute-form target, and allows `*` as the target for a server-wide question (RFC 9110). The two
forms are not the same request, and a client can send either — `OPTIONS /` asks about the root resource, `OPTIONS *` asks
about the server. ADR-0023 §9 recorded this adapter's behaviour as **design debt**: «`OPTIONS *` never reaches the dispatcher — the JDK answers it and logs `No context found for
request`». That sentence was written from a single socket test and it was accurate about the symptom. Phase 2.9.5 was
opened to decide whether the symptom is a gap we ought to fill, and the directive's first instruction was «首先不要修改代码»
— investigate before touching anything.

The investigation had to answer six questions with real sockets rather than with reasoning (§Problem below), and it was
worth doing because the two candidate answers have opposite costs. If the asterisk could be handled reliably at the
adapter level, supporting it is a few dozen lines and a decision about what a server-wide `Allow` means. If it cannot,
then any code written to "support" it is a lie about a request path that never arrives, and the honest deliverable is a
documented limitation with tests that fail when the JDK changes.

What the measurements showed is that the second case is the only case. The JDK server does not deliver a target of `*` to
any handler, on either supported JDK, and there is no public API that would make it do so. That reframes the phase: the
work is not implementing `OPTIONS *` but establishing, in code, that it is not reachable — and making sure nothing in the
contract quietly pretends otherwise.

## Problem

1. Why does the JDK never deliver `OPTIONS * HTTP/1.1` to the context registered at `/`? (§1)
2. Is there any stable public API that could make it deliver it? (§2)
3. Would adapter-level synthetic handling be reliable if we tried it anyway? (§3)
4. Should `OPTIONS *` be supported at all? (§4)
5. If it were, where would the code live? (§5)
6. How is non-support expressed so that it stays true, and so that a reader cannot mistake the server's 404 for ours?
   (§6)

## Decision

**`OPTIONS *` is not supported by this adapter, and the non-support is a tested statement rather than a comment.** No code
in `com.barezen.http.jdk` answers the asterisk, none will, and the reason is recorded here with the line numbers that
produce it. Three consequences follow, and each is enforced:

- **The resource chain never sees the asterisk.** No handler is called, no route matches, and `Router` is not consulted
  for a server-wide method set. This is pinned by a counting test, not by an assertion about status codes (§6).
- **`OPTIONS *` is not `OPTIONS /`.** A target naming the server and a target naming the root resource are two different
  requests with two different answers on this server, and the test suite asserts both, in the same file, so the
  distinction cannot silently collapse later.
- **`"*"` stays out of the route table.** `Router` refuses `"*"` as a registered pattern, `match()` returns empty for a
  request whose path is `"*"`, and `methodsFor("*")` returns an empty set. Those three refusals are kept as *contract
  statements* — an answer for a second adapter whose server does deliver the target — not as live code paths on this
  server, and their javadoc says so. The value itself stays expressible: `Request.checkedPath` admits `"*"` beside any
  path beginning with `/` — `RequestTest$Path.acceptsTheAsteriskTargetOfAnOptionsStarRequest` — because refusing it there
  would leave the contract unable to name the request an adapter is asking about, and
  `RouterTest$StaticRoutes.aRequestForTheAsteriskTargetMatchesNoRoute` pins the other half: expressible, unroutable.

### 1. The mechanism, measured and quoted

`ServerImpl` parses the request line and resolves the target before it knows anything about contexts. On Temurin
**17.0.20.1+1** (and the same code on **21.0.12.1+1**, eleven lines further down the file):

```
ServerImpl.java:764   String uriStr = requestLine.substring(start, space);      // "*"
ServerImpl.java:773   uri = new URI (uriStr);                                   // parses; no exception
ServerImpl.java:832   ctx = contexts.findContext (protocol, uri.getPath());     // path == "*"
ServerImpl.java:833   if (ctx == null) {
ServerImpl.java:834     reject (Code.HTTP_NOT_FOUND, requestLine, "No context found for request");
ServerImpl.java:835     return;                                                 // no exchange, no handler, no filter
```

On 21.0.12.1+1 the same steps sit five lines lower: `:769` for the substring, `:778` for `new URI`, `:837` for the lookup,
`:838-840` for the null check and `reject`, `:841` for the `return`.

Two facts make the lookup fail, and both are structural:

- **`new URI("*").getPath()` is `"*"`, not `"/"`.** Measured directly, on both JDKs: `* → path=* startsWithSlash=false`
  (the target is hierarchical, not opaque, so no exception is thrown — the server gets a usable path and that path simply
  is not a path). `/ → path=/`, `/users/42 → path=/users/42`, and the absolute form
  `http://h:1/p → path=/p` all begin with `/`, which is why those are routed normally.
- **Every registered context path must begin with `/`, and matching is prefix matching.**
  `ContextList.java:54-68` walks the list and keeps only contexts where `path.startsWith(cpath)`, longest prefix winning;
  `HttpContextImpl.java:59-60` refuses registration otherwise:
  `if (path == null || protocol == null || path.length() < 1 || path.charAt(0) != '/') throw new
  IllegalArgumentException("Illegal value for path or protocol");`. So a context that could be a prefix of `"*"` would have
  to start with something other than `/`, which the constructor forbids. `/` is not a prefix of `*`, and nothing
  registrable is.

`reject` → `sendReply` (`ServerImpl.java:932-970`) is therefore the only writer this request ever has, and it writes
`<h1>404 Not Found</h1>No context found for request` — 50 bytes — with `Connection: close` and no `Date`, because it does
not go through `sendResponseHeaders` at all.

**This is not an `OPTIONS` rule.** The refusal happens after the method token is parsed and before anything method-shaped
exists, so any token gets the same answer. Measured with ten tokens — `OPTIONS`, `GET`, `HEAD`, `TRACE`, `POST`, `PUT`,
`DELETE`, `PATCH`, `CONNECT` and the extension token `PURGE` — sent to this adapter's server, whose one JDK context sits at
`/` with routes registered at `/` and `/users/{id}`: ten byte-identical 404s written by `sendReply`, and the route
handlers' counter stayed at zero throughout. A limitation phrased as "the JDK does not support `OPTIONS *`" would be
narrower and therefore misleading; the correct sentence is "the JDK does not deliver any request whose target is `*`".

### 2. No public API can change that

The whole `HttpServer` surface was enumerated reflectively on both JDKs. What exists is
`create()`/`create(addr, backlog)`, `bind`, `start`, `stop(int)`, `getAddress`, `setExecutor`, `getExecutor`,
`createContext(String)`, `createContext(String, HttpHandler)`, `removeContext(HttpContext)`, `removeContext(String)`.
JDK 21 adds one overload, `create(addr, backlog, path, handler, Filter...)` (`@since 18`), and its implementation is
`HttpServer.create(addr, backlog)` followed by `server.createContext(path)` — so it inherits the constructor's `/` rule
and refuses `*` exactly as `createContext` does.

Measured attempts at 方案A's building blocks (a server with a context at `/`, both JDKs, same output):

```
createContext("*")    -> java.lang.IllegalArgumentException: Illegal value for path or protocol
createContext("")     -> java.lang.IllegalArgumentException: Illegal value for path or protocol
createContext("* ")   -> java.lang.IllegalArgumentException: Illegal value for path or protocol
removeContext("*")    -> java.lang.IllegalArgumentException: cannot remove element from list
```

There is no pre-context hook either: `Filter` lives on a `HttpContext`, so a filter can only run for a request that found
a context — the failure happens at the lookup the filter would have been attached past. `HttpExchange` is created by
`ServerImpl` after `connection.setContext(ctx)`, so there is nothing to intercept between the lookup and the handler.

The one public mechanism that could change the answer is `com.sun.net.httpserver.spi.HttpServerProvider`, and it does so
by replacing the entire server implementation — `provider` is selected at `HttpServer.create` time and the JDK's own
`ServerImpl` is simply not used. That is not "handling `OPTIONS *` in the adapter"; that is writing a new HTTP server,
which ADR-0021 §1 already refuses for this module.

There is also no client worth testing this with: `java.net.http.HttpClient` refuses to build the request at all —
`HttpRequest.newBuilder(new URI("*"))…send(...)` fails with `IllegalArgumentException: URI with undefined scheme` on both
JDKs. Every test in this area must open a plain `Socket` and write the request line by hand, which is how §6's evidence was
produced.

### 3. Why adapter-level synthetic handling would not be reliable

方案A was the directive's preferred option and it was tested seriously before being rejected. To answer `OPTIONS *` in the
adapter, this module would need the request. It cannot get it: the dispatcher is installed as the handler of a context at
`/`, and §1 is the lookup that fails. Every variant of A reduces to one of these, and each was measured or reasoned from
the quoted source:

- **Register a context whose path could match `*`.** Refused by the constructor; there is no path beginning with `/` that
  is a prefix of `"*"`.
- **Intercept before the lookup.** No hook exists (§2); a `Filter` runs only after a context was found.
- **Read the raw socket instead.** The socket is owned by `ServerImpl`'s connection reader; nothing in the public API
  exposes it, and the request bytes are consumed into a `String requestLine` inside a package-private class
  (`ExchangeImpl`, `ServerImpl`) before any user code runs.
- **Replace the server via SPI.** Rejected in §2 on ADR-0021 grounds.

So a synthetic handler would be code that cannot run on this adapter, tested only by a socket that proves the opposite.
That is the definition of the «假装支持» the directive forbids, and the reason B was chosen over the preferred A.

### 4. What a server-wide `Allow` would even say

Suppose the request did arrive. The answer would need a method list for the server as a whole, and there is no honest
source for one. `Router.methodsFor(String)` takes a path and validates that it starts with `/`; `methodsFor("*")` returns
an empty set on purpose (§Decision). A `Set` assembled from every registered route across every path would be a
**server-wide method registry**, which does not exist in this framework and which the directive explicitly forbids
constructing «凭空». The phase directive's own §25 wording is the rule: «如果没有可靠的 server-wide method registry：不要凭空构造». Building
one — a mutable per-route accumulator consulted at answer time — would also reintroduce exactly the shared mutable state
ADR-0019 §13 and the adapter's `nothing_in_this_module_is_a_mutable_global` rule refuse.

The headerless answer matters as much: a synthetic `OPTIONS *` reply would have to decide about `Server`, `Date`,
`Content-Type`, `Content-Length`, CORS fields and a framework banner. None of those is derivable from an existing
decision in this codebase, and the directive forbids adding them «凭感觉». Declining the feature is what keeps that list
from being invented.

### 5. Where the code would have gone if the answer had been yes

Recorded so that a later phase does not re-litigate placement: the only correct home for server-level HTTP behaviour is
the adapter (`JdkRequestDispatcher`), never the contract. `Request`, `Router`, `Handler` and `Response` would not gain a
concept for the asterisk, because the core must not know HTTP (ADR-0018 §1, ADR-0019 §1). Had the target been deliverable,
a synthetic response would have been built in the dispatcher from data it already had, and `Router` would still have
refused `"*"` as a pattern. This section is empty of code by design, not by oversight.

### 6. How non-support is expressed, and how our 404 is told apart from theirs

A 404 is not evidence of anything by itself: the server's answer and a hypothetical adapter answer share a status code.
The distinction is made by **who wrote the response**, which leaves a fingerprint on the wire. Two different pieces of JDK
code emit the two header spellings, and the tests read them:

| writer | path | length field | `Date` | body |
| --- | --- | --- | --- | --- |
| `ServerImpl.sendReply` (no context found) | before any exchange | `Content-Length:` — uppercase `L`, the literal string at `ServerImpl.java:949` | **absent** | `<h1>404 Not Found</h1>No context found for request` |
| `ExchangeImpl.sendResponseHeaders` + `HeaderWriter` (a response we wrote) | through an exchange | `Content-length:` — canonicalised, lowercase `l` | **always present**, set unconditionally at `ExchangeImpl.java:218` | our own text, e.g. `Not Found` |

`JdkOptionsStarTest` asserts both halves of that pair, so "the server answered, not us" is falsifiable rather than
inferred: the asterisk's reply must carry the uppercase spelling, no `Date`, the server's HTML body, and `Connection: close`
— while a request that reaches the adapter at the same status code on the same port must carry the lowercase spelling, a
`Date`, and no HTML.

## Consequences

- **`OPTIONS *` is a documented limitation of the JDK adapter, not of the contract.** ADR-0023 §9's "design debt" label is
  withdrawn: debt implies a fix that was deferred, and §3 shows there is nothing to fix in this module. The index and the
  phase paragraph in the README are amended to say what was measured.
- **The resource chain stays clean under the asterisk.** Handler invocation is counted, not assumed, and the count is zero
  for every method token (§Testing evidence).
- **Nothing grew.** No public type, no public method, no new rule, no Router signature change. The `Router` branch for a
  `"*"` path, the `"*"`-pattern refusal and the `methodsFor("*")` empty set already existed; what changed is their
  documentation, which now names the measurement instead of gesturing at a request that cannot arrive.
- **A second adapter inherits a stated contract, not an accident.** `Router` answering empty for `"*"` is the behaviour an
  adapter on a server that *does* deliver the target gets: no route, therefore a 404 from that adapter's own error policy.
  That is a decision, and §Decision records it.
- **Connection loss after the asterisk is visible in tests.** `sendReply` writes `Connection: close` and closes, so a
  pipelined follow-up on that connection never gets an answer; a test pins that too (§Limitations).

## Alternatives

**方案A — adapter-level synthetic handling** (the directive's preference). Judged on §3's measurements: unreachable,
because the request is answered before an exchange exists and no public API moves that point. Rejected on evidence, not on
taste. Had a JDK version started delivering the target, A would become viable and the `Router` branch would then be live
code rather than a contract statement — which is precisely why the branch stays.

**方案B — explicit non-support with real tests and this record.** Adopted. It satisfies the directive's condition
«不得假装 404 就代表"支持"» by testing what the 404's writer is, and it keeps the surface at zero growth.

**方案C — anything else.** Only admissible if A and B are both insufficient; they are not, so no C was pursued. The two
C-shaped ideas that came up during investigation were rejected before implementation rather than after: replacing the
server through `HttpServerProvider` (§2), and reading the raw connection inside the JDK's package to reconstruct the
request line (§3, and it would need `--add-opens jdk.httpserver/…`, which no test in this build passes).

## Rejected alternatives

Each of these would produce a plausible-looking 404 or 200 for `OPTIONS *` while misrepresenting the request. All are
refused, and the first three are forbidden by name in the phase directive:

- **Treating `OPTIONS *` as `OPTIONS /`.** It would route a server-wide question to the root resource's handler, so
  `OPTIONS *` answers with the root's `Allow` — a value about one resource presented as a value about the server. Rejected:
  a wrong answer is worse than no answer, and the two requests are separately tested here so a future change cannot merge
  them quietly.
- **Registering a `"*"` route.** `Router.builder().route(OPTIONS, "*", …)` is refused by `parsed()` with a message naming
  the reason (`RouterTest$RefusedPatterns.anAsteriskIsARequestTargetNotAPattern`), and probe B in §Testing evidence shows
  that refusal is load-bearing. A pattern is a path shape; the asterisk is a target, and a table that conflated them would
  answer a resource question about a non-resource.
- **Deriving `Allow` from `router.methodsFor("*")`.** It returns an empty set by design, so the tempting "fix" is to make
  it mean "all methods", which would turn a per-path question into a global one inside the contract. Rejected, and the
  phase directive's §25 rule against constructing a registry applies.
- **A synthetic `Response` built in the dispatcher for a `"*"` path.** Dead code on this server: §1 guarantees no socket can
  reach it, so it could only be tested through the contract, and a branch that no transport can exercise is the kind of
  surface this phase exists to remove (ADR-0025 §9's reasoning about unexercised extension points applies).
- **Widening `HttpServerAdapter` or `Router` to expose a server-wide method set.** Rejected: no consumer exists, and the
  widening would be for a request the shipped adapter never receives (§4).
- **Silently accepting the JDK's 404 and calling it done.** Rejected by the directive: a 404 without a measurement is how
  ADR-0023 §9 ended up labelled debt when it was actually a boundary.

## Testing evidence

`JdkOptionsStarTest` — 10 tests, every one on a plain `Socket`, none through `java.net.http.HttpClient` (§2's refusal
makes the client unusable here). `JdkErrorSemanticsTest$StarTarget` keeps its two tests and now delegates the finding to
this class.

| test | what it pins |
| --- | --- |
| `AsteriskTarget.theServerAnswersAndEveryResourceHandlerStaysUncalled` | body is exactly `<h1>404 Not Found</h1>No context found for request`; status line `HTTP/1.1 404 Not Found`; two registered handlers' counters both 0 |
| `AsteriskTarget.theAnswerCarriesNoDateAndNoAllowAndExactlyOneLength` | no `Date`, no `Allow`, no `Server`; `Content-Length` present with exactly one line, value `50`, uppercase-`L` spelling; `Connection: close` |
| `AsteriskTarget.noRegisteredMethodOrRouteChangesThatAnswer` | eight methods registered at `/` and the answer, writer and counters are unchanged — registration cannot turn this into a routed request |
| `AsteriskTarget.anyMethodNamingTheAsteriskGetsTheSameServerWrittenAnswer` | ten method tokens incl. the extension token `PURGE` all receive the identical byte-level answer — the limitation is about targets, not methods |
| `AsteriskTarget.theConnectionCarryingTheAsteriskIsClosedRatherThanKeptAlive` | pipelined `OPTIONS *` + `GET /users` on one connection yields exactly one status line; the second request is never answered |
| `PathTargets.optionsSlashReachesTheHandlerAtTheRoot` | `OPTIONS /` → 200, `Allow: GET, OPTIONS`, handler counter 1 — the contrast case for §Decision's second bullet |
| `PathTargets.optionsOnARegisteredParametrizedPathIsAFourOhFiveWithAllow` | `OPTIONS /users/42` with only `GET /users/{id}` → 405 + `Allow: GET`, 0 handler calls |
| `PathTargets.optionsOnAnUnregisteredPathIsThisAdaptersFourOhFourNotTheServers` | `OPTIONS /does-not-exist` → 404 with body `Not Found`, no `No context found` text, `Date` present — same status, different writer |
| `PathTargets.anAbsoluteFormTargetIsResolvedToAPathAndRouted` | `GET http://127.0.0.1:PORT/users/42` is resolved and routed, and its reply carries `\r\nContent-length: 2\r\n` plus `\r\nDate: ` and **not** `Content-Length:` — the control showing the asterisk fails at lookup, not parsing |
| `InvocationCounter.matchingAnAsteriskRequestCallsNoHandlerAndNamesNoMethods` | the contract-side counter: a `Request` with path `"*"` handed to an application's route table produces `match` empty, `methodsFor("*")` empty, and invoking whatever match returned leaves the count at **0** |

The invocation counter is stated twice on purpose. The socket version would keep reading zero even if this module grew
synthetic handling — the request is refused before a context is looked up, so no change to this adapter's routing can make
a socket call a resource handler for `*`. The counter that *can* fail is the one taken where the asterisk is expressible:
the route table a second adapter would dispatch through.

Mechanism evidence, produced outside the build and quoted here rather than summarised:

| probe | output |
| --- | --- |
| URI resolution, both JDKs | `* → path=* startsWithSlash=false`; `/`, `/users/42`, `http://h:1/p → startsWithSlash=true`; `urn:x:y → path=null opaque=true` |
| 方案A building blocks, both JDKs | `createContext("*")`/`("")`/`("* ")` → `IllegalArgumentException: Illegal value for path or protocol`; `removeContext("*")` → `IllegalArgumentException: cannot remove element from list`; `createContext("//")` accepted, and a prefix of `*` it is not |
| `HttpServer` public methods, 17 vs 21 | identical except `create(InetSocketAddress,int,String,HttpHandler,Filter...)`, present only on 21 (`@since 18`), whose body calls `server.createContext(path)` |
| `java.net.http` client with a `*` target | `IllegalArgumentException: URI with undefined scheme` — sockets are mandatory |
| ten method tokens against `*` | ten identical `sendReply` 404s, zero handler entries |

Mutation probes that fired (each applied to a source file, run, and reverted — all five mutated files verified
byte-identical afterwards by `md5sum`):

| probe | change | failing tests |
| --- | --- | --- |
| A | `match()`'s `"*"` branch deleted, so the asterisk falls through to segment matching (`segmentsOf("*")` is `[""]`, which is the same shape as `"/"`) | `JdkOptionsStarTest$InvocationCounter.matchingAnAsteriskRequestCallsNoHandlerAndNamesNoMethods` — «Expecting AtomicInteger(1) to have value: 0»: the root resource's handler answers a server-wide target, with no `Allow` naming the server |
| B | `parsed()`'s `"*"` refusal deleted | `RouterTest$RefusedPatterns.anAsteriskIsARequestTargetNotAPattern` — registration succeeds, so `"*"` becomes a pattern |
| C | `match()` special-cases `"*"` as `"/"` (the forbidden conflation) | the same counter test, reading 1 — this is precisely the «假装支持» shape, and it is the reason the counter exists |

Probes A and C fire the same test for different reasons, which is worth keeping: one shows that *no branch* silently
routes the asterisk to the root by accident of segment splitting, the other shows that *a well-meant branch* does it on
purpose. Only a counted handler call distinguishes either from the honest answer; a status-code assertion passes for all
three.

## Limitations

Recorded as limits, not as features.

1. **The limit is targets, not methods.** No request with a `*` target reaches this adapter — `OPTIONS`, `GET`, `PURGE`,
   all the same. An application that needs server-wide capability discovery over this adapter gets nothing from it, and a
   monitoring agent that sends the asterisk gets the JDK's 404 rather than an answer.
2. **The connection carrying the asterisk is closed.** `sendReply` writes `Connection: close` and closes the socket, so a
   pipelined request behind it is silently unanswered. This is the JDK's behaviour and this adapter cannot influence it;
   clients that reuse a connection after a `*` request must reconnect.
3. **No server-wide `Allow` exists anywhere in this framework.** §4's reason stands: the route table answers per-path
   questions only, and nothing accumulates a whole-server method set. A future phase that wants server capability discovery
   has to decide where that list legitimately comes from before it can answer the request.
4. **Nothing here is delivered to a handler, so nothing here can be exercised through one.** The `Router` branch for
   `"*"`, its pattern refusal and `methodsFor("*")` are untestable through a socket on this server; they are tested through
   the contract, and §3's note about dead-but-stated code is the honest framing.
5. **`removeContext(String)` and the 21-only `create(...)` overload give no additional room.** They were probed
   individually rather than assumed (§2), and both come back to the same constructor rule.
6. **A different server may deliver the target.** `HttpServerProvider` exists and a Netty- or Jetty-shaped adapter would
   have its own parser; whether it delivers `*` is that adapter's fact to measure and document, and this record is written
   so that its ADR has a place to disagree.

## Relation to previous ADRs

- **Supersedes ADR-0023 §9's framing.** That section recorded the same socket behaviour as design debt and guessed that a
  fix «needs a Router-contract decision — an explicit server-star concept, or an adapter that registers a synthetic
  context». Both of the named fixes are now ruled out by measurement (§3), so what remains is a boundary, and the label
  changes. ADR-0023 §1–§8 are untouched; its 404/405/500/400 decisions still stand, and `JdkErrorSemanticsTest` still owns
  them.
- **Confirms ADR-0019 §2 and §10.** Routing stays one question — method and raw path to a handler. The asterisk is not a
  path, so `Router` keeps its `"*"` refusals without growing a second concept, which is what ADR-0019 §1's minimal-surface
  argument predicted.
- **Depends on ADR-0022 §2.** The bridge's `getRawPath()` choice and this finding are two readings of the same JDK parse:
  `URI` resolves a target to a path, and a target that is not a path stops at the lookup before the bridge ever runs.
- **Uses ADR-0021 §1/§4 as the boundary.** Server-level HTTP behaviour may only live in the adapter — which is why §5
  places a hypothetical handler there, and why §3's rejection is about reachability rather than about layering.
- **Consistent with ADR-0025 §9.** Capability that no transport can exercise is not made public; the asterisk gets a
  documented limit instead of an extension point.
- **Reads as the negative case for ADR-0027.** That record changes wire behaviour where the contract was wrong; this one
  changes nothing, and the pair together is the phase's answer to «contract audit, not feature growth».
