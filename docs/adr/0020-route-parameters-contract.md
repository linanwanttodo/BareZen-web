# ADR-0020: Route Parameters Contract

Status: accepted
Date: 2026-09-29
Decides: how a path parameter captured by `Router` reaches the `Handler` that needs it; what `Handler.handle` takes;
whether `RouteMatch` keeps holding a `Handler`; that Phase 2.7's frozen signature is amended rather than protected by a
compatibility layer; and which architecture rules had to change because the old ones could not see the failure this
phase was fixing
Related: ADR-0006, ADR-0008, ADR-0014, ADR-0016, ADR-0017, ADR-0018, ADR-0019

## Context

Phase 2.8 built the route table and, in the last section of its record, wrote down the thing it had not solved.
`Router.match(Request)` returns a `RouteMatch` carrying `parameters()`, and `Handler.handle(Request)` takes a request —
so the parameters arrived at the adapter and stopped there. The integration test had to say so out loud in a javadoc
paragraph: the plugin's handler read the identifier by slicing `request.path()`, and the only thing that made the slice
safe was that the router had already proved the segment was one non-empty piece of raw text. ADR-0019 §7 named the two
candidate fixes, observed that both are contract changes, and handed the decision on.

This phase is that decision. It adds no capability, and it is deliberately not a router feature phase: no wildcard, no
index, no resolver, no binder, no exception taxonomy. The single question is:

**How does a route parameter extracted by the `Router` become usable by the `Handler` — with no second parse, no hidden
context, no implicit injection, no service locator, and no parameter framework?**

The constraint that shapes the whole answer is the one ADR-0018 §9 established and ADR-0019 §14 repeated: a request
carries what the client sent and nothing the application owns, and a handler is handed its collaborators by a
constructor. Route parameters sit exactly between those two rules — they were not sent by the client (the client sent a
path; the *pattern* is what made one segment into `id`), and they are not collaborators. So they must arrive per call,
and the only per-call channels this design owns are the arguments of `handle`.

One further fact came out of doing this properly rather than arguing it, and it changes what the phase contributed: the
rejected option that looked most dangerous was **already legal under all nineteen architecture rules**, and the option
that looked obviously wrong produced code that compiled and ran. Both are recorded below with their transcripts, because
"we refused it" is worth less than "here is what happened when we tried it".

## The question, in parts

1. Can `RouteMatch.parameters()` stay as it is, with handlers parsing paths? (§1)
2. Should the handler be handed the whole match? (§3)
3. Should the request carry the parameters? (§4)
4. What is the smallest channel that works? (§2)
5. What is refused even though it would be convenient? (§5)
6. Is this allowed to break Phase 2.7's API? (§6)
7. What must the map guarantee — immutability, order, null, concurrency? (§7)
8. Does a handler still compose, as middleware will need? (§8)
9. Does `RouteMatch` still hold a handler? (§9)
10. What rules and probes hold all of this? (§10, §11)
11. What does the adapter do with it? (§12)

## Decision

`Handler` takes the parameters as a second argument:

```java
@FunctionalInterface
public interface Handler {
  Response handle(Request request, Map<String, String> parameters);
}
```

`RouteMatch` is unchanged — it still holds `handler` and `parameters` — because both halves have the same consumer, the
adapter, and one call site separates them (§9). `Router` is unchanged; it already built the map, and that map is now the
thing passed. `Request`, `Response`, `Headers` and `HttpMethod` are untouched. The single-argument `handle(Request)` is
**removed, not kept beside the new one**: no overload, no default, no `LegacyHandler`, no `RouteHandler` (§6).

The adapter's two lines, which are the whole contract in use:

```java
RouteMatch found = router.match(request).orElseThrow();              // empty -> the adapter answers 404
found.handler().handle(request, found.parameters());                 // routing's answer, delivered
```

### 1. Why the gap could not stay a gap

Because it was not a documentation problem, it was a correctness problem, and three separate things were wrong with the
workaround.

**The router's decision was thrown away twice.** A handler that slices `request.path()` re-implements `lastIndexOf('/')`
and re-derives a value the table computed microseconds earlier. It works for `GET /users/{id}`, and it breaks the moment
a pattern is not a single trailing segment: `/{a}/fixed/{b}` is not reconstructible from a path at all, because nothing
in the path marks which segment the pattern considered literal. `RouterTest.twoParametersArriveByNameAndInPatternOrder`
is that case written down — the values `7` and `3` are in the path, their *names* are not.

**Nothing told anyone which segment was the parameter.** The workaround's safety came entirely from a comment. A handler
written against `path()` cannot be checked against the pattern it is registered under, so a future edit to the pattern
silently changes what the handler parses. `parameters()` is keyed by the declared names, so the pairing is checked by the
map.

**It was the shape that a context arrives under.** Every framework that ended up with a request attribute bag began with
one honest exception: a value computed by the framework that the handler needed and could not get. The workaround was
already a second parse of the raw path to reach a value the router had, which is the moment a developer starts asking
for somewhere to put things.

The directive's principle is the one-line version: *a route parameter extracted by the Router must be usable by the
Handler*. Leaving the gap open meant Phase 2.8 had built a table whose second output no one could consume.

### 2. The channel: the parameter map, as an argument

Option C — `handle(Request, Map<String, String>)` — is what the phase chose, and the case for it is a case about size.

It is the smallest change that closes the gap: one added argument, no added type, no added method, no added concept. The
map is exactly what `Router` already built and exactly what `RouteMatch` already carried, so no new representation of a
captured parameter exists anywhere in the module; there is one type of parameter value, `String`, raw, and the phase that
invented it was 2.8.

It keeps the handler's dependency rule intact. A handler still gets its collaborators from a constructor and its request
data from the call — and a captured parameter *is* request data, in the sense that it is a function of the request and the
pattern, produced fresh per call. Passing it as an argument makes the dependency visible at the signature, which is the
same argument ADR-0014 made for writing constructor calls by hand instead of letting a container inject them.

It is honest about what is known and unknown. A `Map<String, String>` says: keys are the declared names, values are raw
text, there may be none, there is no type, there is no default, there is no required-ness. That is the complete set of
things routing actually decided. A richer parameter object would have to invent semantics nothing has asked for — and
§5 refuses that family by name.

The cost is that `Map<String, String>` is not self-documenting: at a call site, `handle(request, Map.of("id", "42"))`
and `handle(request, queryParameters)` look identical. The mitigation is that there is only one map in the signature and
no other source of one in the contract; the same ambiguity in a `RouteMatch` argument is resolved by the type name, and
that was not enough to pay §3's price.

**Why the parameters are raw, restated for the new argument.** The signature change did not touch encoding semantics,
and ADR-0019 §5 keeps governing them: `/users/a%2Fb` matched by `/users/{id}` delivers `id → "a%2Fb"`, one value holding
an encoded slash, never `a/b`. `RouterTest.anEncodedParameterReachesTheHandlerStillEncodedAndStillOneValue` asserts it
through a dispatch rather than through a match, and `HandlerTest` has the handler answer the body from the map alone.

### 3. Rejected: `handle(Request, RouteMatch)` (option A)

The idea is that routing's answer is a `RouteMatch`, so hand the handler routing's answer. It fails for two independent
reasons, and the first one was only visible after writing the code.

**A match contains the handler.** `RouteMatch` is `(handler, parameters)`. Passing it to a handler gives the handler a
reference to itself — and the contract's own rule says no type in the module may dispatch a handler (ADR-0018 §10), so a
handler holding one has to be trusted not to call it. The concept is circular even where the code does not loop: the
value that says *which handler to call* arrives at the handler, where its only remaining use is to say which handler to
call.

**An unrouted handler becomes uncallable.** A match cannot be constructed without naming a handler, so every caller of a
handler must now either own a router or fabricate a match. The probe built the fabrication and it produced this:

```text
unrouted call forced a fabricated match, names the handler itself: true
self dispatch compiles and answers: 200
decorated answer: 200
```

Three separate facts in four lines. `callUnrouted` had to pass the handler as its own match component, because the
constructor refuses null and there is nothing else to pass. A handler that called `match.handler().handle(request,
match)` compiled and answered — that is a router written inside an application handler, permitted by the contract. And
the decorator had to rebuild a match to pass down, naming `inner` inside a value the wrapper had been handed, which is
the middleware mechanism ADR-0018 §11 and `HandlerTest` treat as "a lambda and nothing more" turned into three lines of
object construction per wrapper.

Under the chosen signature the same wrapper is `(request, parameters) -> inner.handle(request, parameters)` — a pass-
through with no new object, asserted for identity by
`HandlerTest.awrapperPassesTheParametersThroughUntouched`.

There is a fourth, quieter cost: `RouteMatch` would stop being a routing result and become a *capability* a handler holds
— it carries authority to dispatch. Keeping it as the adapter's value is what makes the handler unable to route.

### 4. Rejected: the request carries its own parameters (option B)

`request.routeParameters()`, or a routed-request copy. This is the option that needed a probe rather than an argument,
because it is implementable inside every existing rule — including the immutability ones.

It was implemented in full: a `Map<String, String>` field on `Request`, a private constructor carrying it, a public
accessor, and a `withRouteParameters(Map)` copy returning a new immutable request. Against Phase 2.8's nineteen rules:

```text
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0 -- in com.barezen.web.BarezenWebArchitectureTest
```

**Nineteen green.** Not one rule saw it. `routeParameters` is not a lookup-shaped name, so
`nothing_takes_a_type_or_a_key_to_find_an_object` passed it. `with` is the verb `Headers.with` already uses, so
`no_method_writes_or_mutates` passed it. The field was final and the map unmodifiable, so `every_field_is_final` and the
defensive-copy rules passed it. It dispatched nothing, reflected nothing, reached no server. The rule that should have
caught it was not a naming rule or a mutability rule at all; it was the sentence "a request carries what the client sent",
and no rule in the file expressed that sentence. That is why §10 adds one.

The runtime behaviour was worse than the rule gap. With the same code running:

```text
unrouted request answers: blew up before answering: NullPointerException: Cannot invoke
    "String.getBytes()" because the return value of "java.util.Map.get(Object)" is null
routed request answers: 42
is it the same object? false
---- what a wrapper can now do ----
routed /users/42, inner handler saw: 1
```

Each line is a problem:

- **A request now has two shapes of validity.** An unrouted request — the one a health check, a test, or an adapter
  that has not looked anything up builds — carries an empty map, and a handler written against it fails with an NPE
  *inside the handler*, far from the mistake. The chosen signature does not make a missing parameter impossible to
  notice (`HandlerTest` records that a wrong key is still an ordinary `Map` `null`), but it keeps the two facts
  separate: a `Request` is always a complete record of the wire, and the parameter map is always whatever the caller
  decided to pass. Under B, calling a handler in a test without a router means forging a `Request` that carries
  framework state — a type that can be valid-but-incomplete, which is the thing ADR-0018 §8 spent its immutability
  argument removing.
- **The request stops being a record of the wire.** `Request` exists so a handler sees what the client sent, unchanged;
  `toString()` still printed `Request[GET /users/42, 0 body bytes]` while the object also carried a framework-computed
  value, so the summary and the state had drifted apart by construction.
- **A wrapper can now forge routing's answer.** The last line above is the decisive one: a decorator handed
  `/users/42` called `request.withRouteParameters(Map.of("id", "1"))` and the inner handler served user 1. That is not a
  hypothetical abuse — it is the natural shape of "the wrapper needs to normalize the id", and it becomes invisible
  because the parameters now look like part of the request. With the map as an argument, a wrapper that wants to change
  what routing said has to build a new map and pass it deliberately, in one visible line.
- **Every copy pays and every copy must remember.** Getting parameters onto a request means a copy constructor call at
  the hand-off, and any other `Request` copy in the module must be audited to carry the field — the class does not
  enforce it, the reviewer has to.

The underlying test the directive asked for — «is a route parameter a concept of an HTTP request itself?» — answers no.
The client sent a path. `id` exists only relative to a pattern the client never sees. Putting it on the request is
putting the framework's conclusion into the record of the client's utterance, and once that has been done once, the
second such value has an obvious home too. That is how an attribute bag arrives, and it is why the accessor list in
§10 is closed rather than guarded by names.

### 5. What stayed refused, even though it would have helped

The phase's constraint forbade them and the design agrees with the constraint, for reasons that are specific to this
signature rather than inherited from it:

- **No `HttpContext`, no `Handler` context argument, no `RoutingContext`.** A context is a place to put values whose
  owners have not been decided. Every value in this call has a decided owner: the client's data is in `Request`, the
  pattern's conclusion is in `parameters`.
- **No request attribute bag.** Refused by ADR-0018 §9, and §4 above is what it would look like if it arrived wearing
  an honest name.
- **No `ParameterBinder`, `ParameterConverter`, `PathParameterResolver`, `ParameterRegistry`.** All four would be a
  decision about types the framework cannot make: `{id}` is a string until the handler says otherwise (ADR-0019 §7).
  A binder also implies a place that runs it, which implies a lifecycle, which implies the adapter knowing about a
  container. With the map handed over as an argument, a handler that wants an `int` writes `Integer.parseInt` where
  the failure is visible — and the first application that shows three handlers repeating the same conversion can get a
  helper function, which is what a `Map` enables and a binder prevents.
- **No `@Route`-style annotation, no reflective parameter injection.** `the_contract_never_reflects_loads_or_scans` and
  `the_contract_declares_and_reads_no_annotations` already refuse these; implicit injection would additionally require
  the framework to know which handler parameter wants which route parameter, which is a second naming system beside the
  one `Router` already has.
- **No `RouteResolution`, no `MatchedRoute`, no parameters-only `RouteMatch`.** §9.
- **No exception taxonomy.** A `ClassCastException` from `parameters.get("id")` propagating out of a handler is the same
  unowned question ADR-0018 §9 leaves to an adapter.

### 6. Breaking the frozen signature, on purpose

`Handler` is Phase 2.7's public contract, and `ADR-0018 §6` says «one method, synchronous, returning a value» in words
that a reader can take as a promise about the argument list. It is amended. The amendment is this record, and ADR-0018 is
not edited — accepted records do not change, per `docs/adr/README.md`.

**No compatibility layer.** The available hedges were a default `handle(Request)` that calls the two-argument form with
`Map.of()`; a second interface `LegacyHandler`; and an abstract `handle(Request)` delegating to
`handle(Request, Map)`. All three were refused, and the reason is that each one lets a handler ignore the parameters its
own route declared. A route is `(method, pattern, handler)`; if `handle(Request)` still answers a call, then a handler
registered under `/users/{id}` can silently read nothing, and the framework has no way to notice — the same
fail-silently failure Phase 2.8 §9 refused for duplicate routes. Two live signatures would also mean every wrapper,
adapter and future middleware decides which one to call, which is a dispatch policy inside the contract.

`@FunctionalInterface` stays, and now earns its keep twice: a second abstract member is a compile error, and the single
signature is what a lambda can implement. Two probes confirm the pinning (§10): adding a default `handle(Request)`
**and** a second default member fires `the_handler_signature_is_the_request_and_the_captured_parameters` twice.

The version is unchanged at `0.1.0-SNAPSHOT`. This is the phase where a breaking change costs nothing but honesty; the
phase where it would cost something is the first adapter, and this phase exists to make sure it does not cost anything
there (§12).

**One rule that this change silently voided.** `nothing_in_the_contract_dispatches_a_handler` names the target signature:
`callMethod(Handler.class, "handle", Request.class)`. After the change there was no such method, so the rule matched
nothing and stayed green forever. The counterfactual was run both ways against a real violation — a `Router.match` that
calls `best.handler().handle(request, ...)` — and the difference is the lesson:

```text
old rule text, real violation:  Tests run: 21, Failures: 0   <-- green, measuring nothing
fixed rule text, same violation: Rule 'the contract states the signature and never invokes it…' was violated (1 times)
```

`callMethod` matches one exact signature, not a name — the same trap that made a Phase 2.2 rule list every `Class`
overload by hand. A rule about a call is only as live as the signature it names, and a contract change that edits an
argument list can therefore delete the reach of a rule without failing it. That is now recorded in the rule's own javadoc
and it is the reason for the signature-pinning rule in §10.

### 7. What the map guarantees

- **Never null.** `Router.Registration.captured` returns `Collections.emptyMap()` for a parameterless route and an
  unmodifiable `LinkedHashMap` otherwise; `RouteMatch`'s compact constructor refuses a null map; so the map a handler
  receives is always a map. `HandlerTest.answersAStaticRouteWithAnEmptyMapRatherThanAbsentParameters` pins the consequence:
  a handler never branches on "was there a router here at all". An unrouted call passes `Map.of()`, which is the same
  answer — an obligation stated in `Handler`'s javadoc rather than enforced on the caller, because a signature cannot
  enforce a check and repeating a null-test in every handler is worse than stating the rule once.
- **Immutable to the handler, in three layers.** `Collections.unmodifiableMap` on the map the table built; a defensive
  `LinkedHashMap` copy plus a freeze inside `RouteMatch`; and the wrapper pass-through that never rebuilds it.
  `RouterTest.aHandlerThatEditsItsMapCannotReachTheTableOrTheNextRequest` puts a real `put` through a real dispatch and
  then re-matches the same route to prove the table's own answer is untouched.
- **Keys are exactly the declared names.** Only `parameterNames` from the pattern become keys, so
  `parameters.get("userId")` on a route that declared `{id}` is a missing key, not an invented value. `HandlerTest`
  records the resulting `null` as ordinary `Map` behaviour with the defence upstream, where it belongs.
- **Iteration order is the pattern's order.** `/users/{userId}/posts/{postId}` iterates `userId, postId`, and
  `/{zoo}/{alpha}` iterates `zoo, alpha` — not alphabetical, not hash order, because the construction is a
  `LinkedHashMap` filled by a walk over segments. Asserted in `twoParametersArriveByNameAndInPatternOrder`.
- **Values are raw.** See §2's encoding paragraph.

### 8. Composition, concurrency, immutability across requests

The map is built **per match**, from the arriving path, and nothing stores it. That is the whole concurrency story: two
threads on `/users/1` and `/users/2` never touch one shared parameter structure, so a crossed value is not made unlikely
by a lock — it has no mechanism. `RouterTest.concurrentRequestsOnOneRouteNeverSeeEachOthersParameters` dispatches 600
handler calls over three paths and asserts 200 of each id, exactly.

Composition survives the widened signature unchanged, which matters because composition is this framework's answer to
middleware (ADR-0018 §11): a decorator is `(request, parameters) -> inner.handle(request, parameters)`. Two tests hold
it — the guard that short-circuits, and the identity assertion that the inner handler received the *same* map object the
outer one did.

### 9. Does `RouteMatch` still hold the `Handler`?

It does, and the reason is a consumer count rather than a concept.

The directive invited splitting it: `RouteMatch` as "the parameters the current request matched", `RouteResolution` as
"handler + match". Splitting is right when two values reach two different places at two different times. Here they do
not: one call site separates them, and it is the adapter's.

```java
RouteMatch found = router.match(request).orElseThrow();
found.handler().handle(request, found.parameters());
```

`match()` needs both in the same answer because "no handler" and "no parameters" are both "no match", and a handler needs
only the second half — which is precisely why the *handler* now takes a map rather than the match (§3). Since no consumer
exists for parameters-without-handler and none for handler-without-parameters, a `RouteResolution` type would be a
public type whose second member is only ever read by the object that already holds it. ADR-0019 §19's rule applies:
every public type needs a current consumer, and `RouteResolution` has none.

So `RouteMatch` keeps its two components, and its javadoc now states the adapter's two lines plus the reason a handler is
not handed the match — turning §3's argument into a comment a future reader finds at the type rather than in this record.

`RouteMatchTest` needed no change: it asserts the surface as `(handler, parameters, toString, equals, hashCode)` and the
parameters' defensive copy, unmodifiability, order, raw values and redaction — all still true, now for a second reason.

### 10. Architecture rules: one amended, two new, twenty-one total

| rule | status | what it holds |
| --- | --- | --- |
| `nothing_in_the_contract_dispatches_a_handler` | **amended** | its target is `handle(Request, Map)`; the old parameter list matches no method and would have gone silently green (§6) |
| `the_handler_signature_is_the_request_and_the_captured_parameters` | **new** | `Handler`'s only member is `handle` with exactly `Request` and `java.util.Map`: a third argument is a context arriving one argument at a time, a `RouteMatch` argument hands the handler its own identity (§3), and a default or second member is the compatibility layer §6 refuses |
| `a_request_carries_only_what_the_client_sent` | **new** | an allow-list over `Request`'s public methods — `method, path, query, headers, body`, plus constructors and `toString`. Exists because option B passed all nineteen rules (§4), and it is a *closed set* rather than a name blacklist: the drift is a well-named immutable accessor, not a badly-named one |
| the other 18 | untouched | including `the_contract_is_exactly_the_seven_contracted_types` (no new public type this phase — see §13) |

Probes, each a deliberate violation compiled against the real tree and then reverted. The rule is identified by the
violation, not by a guess:

| # | violation | rule(s) fired |
| --- | --- | --- |
| p19 | `Handler.handle(Request, RouteMatch)` — a handler handed the match, and a default method that dispatches it | `the_handler_signature_is_the_request_and_the_captured_parameters` (1) |
| p19b | the same change made in the *declared* signature rather than a default | compile-level, not rule-level: 100 javac errors across three test files (64 `HandlerTest`, 32 `RouterTest`, 4 `RouterPluginIntegrationTest`), each reading `incompatible types: java.util.Map<java.lang.String,java.lang.String> cannot be converted to com.barezen.web.RouteMatch`. The contract's own applications feel the break at every call site, which is option A's §3 evidence in the form a developer actually meets |
| p20 | full option B: `Request.routeParameters()` + `withRouteParameters(Map)` + a final field + an unmodifiable map | `a_request_carries_only_what_the_client_sent` (2). Against the pre-phase rules: **19/19 green** |
| p21 | `Request.matchName()` — an immutable, plausibly-named accessor for something the framework computed | `a_request_carries_only_what_the_client_sent` (1) |
| p22 | a default `handle(Request)` kept for compatibility **and** a second default member | `the_handler_signature_is_the_request_and_the_captured_parameters` (2) |
| p23 | `Router.match` calling `best.handler().handle(request, …)` | fixed rule text: `nothing_in_the_contract_dispatches_a_handler` (1). Old rule text: **green** (§6) |
| q1 | a non-final `private String path` on `Request` | `every_field_is_final` |
| q2 | `Router implements AutoCloseable` with a `close()` | `the_router_implements_no_interface`, `the_route_table_exposes_only_the_questions_it_answers` |
| q3 | `Request.attribute(String)` | `a_request_carries_only_what_the_client_sent`, `nothing_takes_a_type_or_a_key_to_find_an_object` — the two rules cover different halves, and neither alone is enough |
| q4 | an eighth public top-level type, `ProbeRoute` | `the_contract_is_exactly_the_seven_contracted_types` |
| q5 | baseline, everything restored | 21 green |

One harness lesson repeated from Phase 2.8 and worth recording twice, because it cost a wasted probe run: a probe that
does not compile proves nothing about a rule. p19's first attempt edited the declared signature and the build died in
`HandlerTest` before ArchUnit ran; the version that fires the rule had to be an *addition* (a default method) rather
than a replacement. Probes must be checked for which layer rejected them — javac, or the rule.

### 11. Error semantics: unchanged, and checked

Nothing about registration moved, and no registration test was added or edited: a malformed parameter definition still
fails at `Builder.route`, before any request exists. `x{id}`, `{id}suffix`, `/users/{`, `/users/}` and `/users/{}` are
refused as braces that are not a whole parameter; `{2fa}`, `{user-name}`, `{a b}`, `{id!}`, `{2fa+x}` and `{{id}}` as
non-identifier names; `/{a}/{a}` as a repeated name (`RouterTest`, 9 tests in "invalid patterns", all carried over from
Phase 2.8 unchanged).

Missing and empty keep their meanings: `/users/{id}` against `GET /users` is **not a match** — segment counts differ, so
`match` is empty and `methodsFor("/users")` says `[GET]`, which is a 405 the adapter builds, not a 404 and not a handler
called with an absent parameter. `/users/` never captures an empty `id`: a parameter takes one non-empty raw segment
(ADR-0019 §6), asserted twice on the table side and once, in the delivery group, through dispatch.

### 12. How a later adapter uses this

Phase 2.9 is why this phase had to happen first, and the shape it now inherits is fixed:

```java
// inside the adapter's server callback, having built a Request from the wire
Optional<RouteMatch> found = routes.match(request);
if (found.isEmpty()) {
  Set<HttpMethod> allowed = routes.methodsFor(request.path());
  respond(allowed.isEmpty() ? notFound(request) : methodNotAllowed(request, allowed));   // 404 vs 405, the adapter's
  return;
}
RouteMatch match = found.orElseThrow();
Response answered = match.handler().handle(request, match.parameters());                  // the only dispatch
respond(answered);                                                                        // exceptions: adapter's (§5)
```

The adapter never parses a path. It never learns what a parameter *means*. It moves one map from a match into a call, and
that is the whole routing-to-handler story: no context object to thread through, no request mutation to keep in sync, no
place where a framework value hides inside a client record. A `Router` is not a `LifecycleParticipant` and still owns no
resource (ADR-0019 §14); the adapter owns the port, the threads, the body limit, the error format.

Had this phase been skipped, the adapter would have been written against a contract that had to change underneath it —
which is exactly the reason §10 of the phase directive gave for freezing `Request → Router → RouteMatch → Handler` before
the server exists. It is now frozen with the hole closed.

### 13. Tests

No new public type, so no new API-surface test was required; the surface still measures seven contracted types (§10 q4).

| file | tests | what this phase added |
| --- | --- | --- |
| `HandlerTest` | 24 (was 16) | `Surface` 4→6: the parameter list is pinned to `Request, Map`, and `handle` has exactly one declared method (no overload). `Dispatch` 3→4: a static route yields an empty map, not an absent concept. `Failures` 4→5: a typo'd name is a missing key, with the defence upstream. `Composition` 3→4: a wrapper passes the *same* map object down, asserted by identity. New `Parameters` group of 3, including the test that proves the data source rather than the value — `/users/99` dispatched with `id → 42` answers `42` |
| `RouterTest` | 84 (was 77) | new `Delivery` group of 7: direct delivery, two parameters by name and order, encoded value through a dispatch, static route delivers an empty map, `/users/me` beats `/users/{id}` *and* delivers nothing, a handler's attempted `put` cannot reach the table or the next request, 600 concurrent dispatches with exactly 200 of each id. The other thirteen groups are untouched, which is the point: Phase 2.8's semantics did not move |
| `RouteMatchTest` | 12 | unchanged, deliberately (§9) |
| `RouterPluginIntegrationTest` | 6 | `UserPlugin.readUser` stops slicing the path and reads `parameters.get("id")` — the workaround this phase exists to delete, now the normal case |
| `BarezenWebArchitectureTest` | 21 | 1 amended, 2 new |

The full suite is **213 web tests** on both JDKs. The plugin integration test is the phase's proof in the strongest form
available here: a real `LifecycleOrchestrator`, a real plugin registering in `initialize()`, a real dispatch, and the
store receiving `42` — where `42` came from the table rather than from a substring call.

## Consequences

- **The contract is one argument wider and no larger in any other way.** Seven public types, one signature, six
  operations on the table, and the first deliberate amendment of a Phase 2.7 signature rather than a workaround.
- **`parameters()` has a consumer.** Phase 2.8 built a second output that nothing could read; every path parameter the
  table captures now arrives at the handler that declared it, in pattern order, raw, immutable.
- **A rule can be voided by a signature change, and nobody would have noticed.** The `callMethod` near-miss (§6) is the
  most transferable finding in this record: an ArchUnit rule that names a method that no longer exists is green forever.
  The signature-pinning rule exists to make the next such change fail loudly instead.
- **Naming rules do not protect a concept.** Option B was clean, immutable, well-named and legal under nineteen rules
  (§4). The protection is a closed set of legal accessors on `Request` — and the honest cost of that, stated where it can
  be read: adding a genuine HTTP fact to `Request` (`scheme()`, `authority()`, a `CONNECT` target) must now amend a rule,
  which is slower and exactly the friction one wants for a change to what a request *is*.
- **Phase 2.9 starts from a closed contract.** `Request → Router → RouteMatch → Handler` needs no further change to route
  and serve, so the adapter can be written once.
- **Remaining design debt.** (a) `Map<String, String>` is untyped at the call site; a helper for `int`/`UUID` parsing
  belongs with the first application that repeats it, not here. (b) Query parameters still have no story at all — the
  asymmetry with path parameters is now more visible, and the reason it is still open is the same as ADR-0018 §2's:
  repeated keys, `+`, charset and `;` are a ruleset nobody has a consumer for. (c) A handler still cannot see its own
  pattern, so a handler shared between `/users/{id}` and `/accounts/{id}` must agree on a name; the alternative was
  putting pattern metadata in the match, which ADR-0019 §19 refuses for having no consumer. (d) Wildcard, prefix and
  regex patterns remain absent (§5, ADR-0019 §19). (e) Exceptions, `Allow`, and 404/405 formatting are still adapter work
  (ADR-0018 §9, ADR-0019 §10).
- **The maxim got its exercise again.** The tempting versions of this phase were a `ParameterBinder` that "just works", a
  request that carries its own state, or a compatibility overload so nothing breaks. All three were refused; what is
  shipped is one extra argument, one amended rule, two new ones, and a record of what happened when the rejected designs
  were actually built.
