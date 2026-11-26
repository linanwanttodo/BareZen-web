# ADR-0019: Router Semantics

Status: accepted
Date: 2026-09-29
Decides: how a `Request`'s method and path become a `Handler`, what a route is, what two routes colliding means, where
the router lives, what it may never become, and what ADR-0018's own architecture rules had to be amended to let it exist
Related: ADR-0001, ADR-0002, ADR-0003, ADR-0006, ADR-0007, ADR-0009, ADR-0012, ADR-0013, ADR-0014, ADR-0015, ADR-0016,
ADR-0017, ADR-0018

## Context

Phase 2.7 froze an HTTP contract of five types and one signature, and refused to route. `Handler` says
`Response handle(Request)`; nothing in the framework can answer the question «which handler for this request», so an
application could define a handler and had no way to reach it. ADR-0018 §10 named that refusal and §14 stated the shape
a router would arrive in — «holding a `Map` of paths to `Handler` and implementing `Handler` itself». This phase does
the one thing that was missing. It does not touch the contract, and the fact that it did not have to is the evidence
that Phase 2.7's shape was right.

The single question: **give an HTTP `Request` an explicit, testable, composable `path + method → Handler` routing
mechanism.**

The way this fails is not a bad matcher. It is a router that grows. A route table is the most natural place in a web
framework to put the next convenience: a priority field, because two routes collided; a context, because a handler
wanted the match; a registry, because two plugins registered the same path; a scanner, because writing registrations
is tedious; middleware, because wrapping a handler looks like routing. Each of those is available in the ecosystem and
each is a step towards Spring, which the project's maxim tells us to stop at rather than to tune. So the phase's real
output is a set of semantics small enough to state completely, and rules that fail a build when the set grows.

Two earlier decisions bind everything below. ADR-0018 froze the contract's surface at five types with an allow-list
rule, and put the name `Router` itself on the forbidden-name list; this phase therefore has to amend ADR-0018's own
rules, which is §20 — a decision taken here rather than in silence, because ADR-0018 is accepted and cannot be
re-edited. ADR-0016 decided a plugin is a participant, not a container, and that dependencies arrive through a
constructor; a router is the first framework component a plugin will want, so §15 decides how it gets one without a
`PluginContext`.

## The ten semantic questions, answered before the code

§3 of the phase directive requires these settled. Each answer is the decision; the reasoning is in the section named.

1. **How is a path matched?** On raw `/`-separated segments, compared as the bytes arrived, with an exact segment
   count and case-sensitive literals (§4).
2. **How is a method matched?** On the `HttpMethod` value, as part of the route's identity — never on its text (§3).
3. **Is a static path a special case?** No: a pattern of all literal segments is a static path, and it is matched by
   the same rule as any other (§4).
4. **What is a path parameter?** A segment that is exactly `{name}`, capturing one non-empty raw segment (§7).
5. **What happens on a duplicate route?** Registration refuses it, by shape rather than by spelling (§9).
6. **What happens on a method mismatch?** `match` is empty and `methodsFor` is not; the adapter turns that pair into
   `405` (§10).
7. **What happens when no route matches?** Both answers are empty, which the adapter turns into `404`. No status code,
   error body or exception type appears in this module (§10).
8. **Does registration order matter?** Not for the winner, not for duplicates, and not for `methodsFor` beyond the
   order the set is reported in (§8, §9, §13).
9. **Is a route immutable once registered?** Yes, and the immutability is in the data: the table is copied at build and
   nothing reachable can change it (§13).
10. **Is `Router` thread-safe?** It does not need to be, because it is immutable: written by a builder, read by any
    number of threads after `build()` copies (§13).

## Decision

`framework/barezen-web` gains two public types and keeps its compile classpath empty:

```java
public final class Router {

  public static Builder builder();

  /** Which handler this request is for, and what the pattern captured. */
  public Optional<RouteMatch> match(Request request);

  /** Which methods have a route that would answer this path. */
  public Set<HttpMethod> methodsFor(String path);

  public static final class Builder {
    public Builder route(HttpMethod method, String pattern, Handler handler);
    public Router build();
  }
}

public record RouteMatch(Handler handler, Map<String, String> parameters) {}
```

An application's assembly code, and a plugin registering into the table the application built:

```java
Router.Builder routes = Router.builder();
LifecycleOrchestrator app = LifecycleOrchestrator.builder()
    .register("users", new UserPlugin(routes, userStore))   // the router arrives as a constructor argument
    .build();
app.start();                                                // initialize() registers GET /users/{id}, POST /users
Router router = routes.build();                             // frozen; handed to the adapter, which owns the socket

Optional<RouteMatch> found = router.match(request);
found.ifPresentOrElse(match -> write(router, match.handler().handle(request)),
    () -> write(router, notFoundOrMethodNotAllowed(router, request)));
```

Two public types, one private record inside the table, no new dependency in the shipped jar, and no sixth or eighth
name in the package: §19 and §20 hold that line.

### 1. What a router is responsible for, and what it is not

`Request → Handler`. That is the whole job, and it is why `match` returns a value instead of answering.

The line matters more than it looks. A router that could build a `Response` would soon build the wrong one on purpose:
`return new Response(404, ...)` is one keystroke away from `return Optional.empty()`, and behind it sit a status
policy, an error format, a `Content-Type`, and eventually a `GlobalExceptionHandler` — which §10 defers and ADR-0018
§9–§10 refused twice. So the router answers two questions with data («which handler», «which methods would have
answered») and the component that called it — the server adapter, which is also the component that must write bytes to
a socket — makes every decision about what the client is told. The rules about paths belong here; the rules about
errors belong there.

`nothing_in_the_contract_dispatches_a_handler`, inherited from Phase 2.7 and still green, is the mechanical half of
this: the router stores handlers and never invokes one. `RouterTest.theRouterNeverCallsTheHandlerItRegistered` proves
it from the other side with a handler whose only behaviour is to throw.

### 2. Where the router lives

`barezen-web`, never `barezen-core`.

Routing is a statement about HTTP, and core's rules already refuse HTTP concepts — `core_has_no_web_or_persistence_concepts`
names `com.barezen.web..` as forbidden — so the direction is not merely preferred, it is the only one that compiles.
The router is also useless without the contract: it takes a `Request` and hands back a `Handler`, both of which live
in this package. Putting routing in core would mean putting the contract in core too, which is the layering ADR-0018
§1 was written to prevent.

### 3. Route identity: a method and a pattern

A route is identified by the pair `(HttpMethod, shape)`. Not the path, not the pattern's text.

`GET /users` and `POST /users` are therefore two routes with two handlers, which is what makes a REST-style
collection resource expressible at all. The method is compared as a value: `HttpMethod.of("get") IS HttpMethod.GET`,
so a client's casing of a standard method cannot smuggle a second route past the duplicate rule
(`RouterTest.aStandardMethodParsedInAnyCaseIsTheSameRouteKey`), while two non-standard spellings that differ in case
stay two methods and two routes, because that is what ADR-0018 §4 decided about `HttpMethod` and a router inherits a
decision rather than relitigating it.

Identity includes the method because that is the only definition under which `POST /users` and `GET /users` can coexist
*and* `GET /users` twice can be refused. A path-only identity gets the second half and loses the first.

### 4. Path matching: raw segments, exact count, no decoding

`/users/{id}` is split into `["users", "{id}"]`; a request path is split the same way; the two match when the segment
counts are equal and every pair of segments matches, where a literal segment equals the arrived text and a `{name}`
segment accepts any non-empty arrived text. Four properties follow from that, all of them chosen rather than incidental:

- **Raw.** Nothing is decoded and nothing normalised — no `URI.normalize()`, no `URLDecoder`, no charset decision.
  Phase 2.7 fixed the contract as a raw one (§5 is this phase's half of the bargain). Raw does not mean invalid:
  registration refuses literal spaces, tabs, CR/LF and DEL before any diagnostic can echo them, because `Request`
  cannot produce such a path; percent-encoded text such as `%20` remains a valid literal pattern.
- **Exact segment count.** There is no prefix match, so `GET /users` cannot serve `/users/42`, and no trailing wildcard,
  so nothing reaches a handler "under" a route nobody registered.
- **Case-sensitive literals.** `/Users` and `/users` are two resources, because in HTTP a path is case-sensitive
  (RFC 9110 says the scheme and host are not, and says nothing that would make a path equal to a differently-cased
  path). A framework that folded case would be deciding an application's URL space for it.
- **Total over shapes.** Matching compares shapes, so priority (§8) is defined on the same objects and duplicate
  detection (§9) too. One notion of "what a pattern looks like", used three ways.

**What routing reads: the method and the path, and nothing else.** No header, no query, no body. The tempting
extensions are named by what they would be: reading `Accept` is content negotiation, reading `X-API-Version` is
version routing, reading a query parameter is routing on a filter, reading a body is a router that cannot answer until
it has buffered the request. Each is a design with rules of its own and each has no consumer here;
`routing_decides_on_method_and_path_only` refuses the first three mechanically, and the fourth is refused by the same
rule. A pattern carrying `?` is rejected at registration for the same reason — it would match a resource and a filter
as though they were one thing.

### 5. Percent-encoding: `%2F` and `%2e%2e`, decided explicitly

Phase 2.7's §5 contract test says `Request.path()` keeps what the client sent. A router is the first thing to act on
that, so this phase must say what it does rather than leave it to an implementation.

**`/users/a%2Fb` matches `/users/{id}` and captures `id = "a%2Fb"`.** The decision was between that and capturing
`"a/b"`. Capturing the decoded form would make a one-segment path produce a two-segment value: the router would have
routed `/users/a%2Fb` to the same resource as `/users/a/b` after proving they are different paths by segment count —
and `RouterTest.anEncodedAndADecodedPathAreDifferentPaths` shows the two do not match each other at all. It would also
be a framework deciding that `%2F` is not data, when an application whose identifiers are file paths uses `%2F`
precisely because it means a slash inside one identifier. The value is handed to the handler undecoded, where the
`id`'s owner can decide; `Handler` has been doing the application's parsing all along.

**`/a/%2e%2e/b` keeps three segments and captures `x = "%2e%2e"`.** Nothing here calls `URI.normalize()`, and that is
a security statement as much as a consistency one. `java.net.URI` folds `/a%2Fb` to `/a/b` and turns `/%2e%2e/x` into
`/../x` — both probed directly, which is why the contract keeps raw text in the first place. A router that normalised
would resolve a traversal attempt on the framework's behalf and hand the handler a path that no longer contains the
bytes the client sent, so the handler cannot even tell that an attempt was made. Reporting `%2e%2e` and staying silent
about its meaning is the only answer correct for every application: a resource owner can reject it, a file-backed one
can map it to a parent, and neither decision is routing.

Two smaller consequences of the same line: lowercase escapes are preserved rather than upper-cased (`a%2fb` captures
`a%2fb`), and `+` in a path is a plus sign rather than a space — `+`-for-space is a form-encoding convention for query
strings and has never applied to paths.

A literal pattern is compared against raw text too, which means `/a/../b` as a pattern matches `/a/../b` as a path and
does **not** match `/a/%2e%2e/b`. Some will call that a missed normalisation. It is the point: one comparison,
consistent in both directions, with no hidden transformation on either side of it.

### 6. Trailing slash, empty segments, and `OPTIONS *`

**Exact semantics.** `/users` does not match `/users/`, and `/users/` is a route a developer may register explicitly.
No automatic match, no automatic redirect, no configuration flag called `normalizeTrailingSlash`.

The reason to choose exact rather than flexible is that flexible has no single meaning. `/users` → `/users/` is a
301, so does the router write a response it is not allowed to build (§1)? Or does it match both silently, which makes
two URLs into one resource and breaks any cache key or link that distinguishes them? Or does it depend on a setting,
which moves the decision out of the code and into deployment? Exact semantics is the only option that answers the
question without a response type, without a redirect policy, and without a flag. The cost is stated plainly in
§24's debt: an application that wants both spellings registers both, twice as many lines, and means it.

An empty segment is therefore significant, which handles the rest: `/a//b` is a registrable pattern that matches only
`/a//b`, `//` is a different shape from `/`, and `{id}` refuses an empty segment — `/users//` matches nothing rather
than producing a user whose identifier is the empty string. Matching it would hand a handler an identifier it cannot
validate when the honest answer is that the path names no resource there; a doubled slash is a malformed path, not a
name.

**`OPTIONS *`** has a path of `*`, which is what ADR-0018 §2 permits and what no pattern can name, because `*` is not
a path. `match` answers empty and `methodsFor("*")` answers an empty set, and registering a `*` pattern is refused at
registration with a message saying why: an `OPTIONS *` request asks about the server rather than a resource, so there
is nothing for a handler to be registered against. Deciding the reply — `Allow`, or a `400` — belongs to the adapter.

### 7. Path parameters

A parameter segment is a segment that is exactly `{name}`. `name` is a letter followed by letters, digits or
underscores. It captures the raw text of exactly one non-empty segment. Captured values are handed back as
`RouteMatch.parameters()`, a map keyed by the declared names, in the order the pattern declared them.

Each rule and the failure it prevents:

- **Exactly `{name}`, whole segment only.** `prefix{id}` and `{id}suffix` are refused. A parameter with a prefix or a
  suffix makes matching have to say how much of the segment the parameter captured and how much was literal, which is
  a template language, not a route.
- **Identifier names, and not repeated within one pattern.** `/{a}/{a}` is refused because parameters come back as one
  map keyed by name; reusing a name would either lose the first value or need a list under a key that says nothing
  about holding one. A name that is not an identifier (`{2fa}`, `{user-name}`, `{a b}`) would be a key the application
  can only read by quoting it, which is a sign the name is not the application's to choose. `{}` is refused earlier
  still, as a literal segment holding braces, because a two-character pair of braces is not a parameter at all.
- **One segment, non-empty, undecoded.** See §5 and §6.
- **No invented names, no missing concept.** The keys present are exactly the names the pattern declared —
  `parameters().get("userId")` on a route that declared `{id}` returns `null` rather than a guess.

The phase's constraint here was to define all of this without creating `PathParameterResolver`, `ParameterBinder`,
`ParameterConverter` or `ParameterRegistry`, and none exists: extraction is four lines inside the table, storage is
`Map<String, String>`, and a converter would be a decision about types the framework cannot make. `{id}` is a string
until the handler says otherwise.

**The honest gap, and why it is a gap.** `Handler.handle(Request)` takes a request, and a `RouteMatch` is what
`Router.match` returns — so the parameters reach the adapter, not the handler. `RouterPluginIntegrationTest` therefore
has its plugin's handler re-derive the identifier from the path rather than read `id` from a match it was never given,
and the derivation is safe only because the router already proved that segment is a single non-empty raw segment.
Closing the gap means changing the frozen contract, and both candidate fixes are contract decisions, not router ones:
a `Handler` overload that takes the match (a second signature every application implements), or parameters stashed on
the `Request` (the servlet `setAttribute` that ADR-0018 §8 exists to refuse). §24 records it as the phase's first debt,
for ADR-0020 to settle with an adapter in hand.

### 8. Matching priority: the most literal pattern wins

When two registered routes both answer a request, the winner is decided by the first position at which one pattern is
literal and the other is a parameter, in favour of the literal one. `/users/new` beats `/users/{id}`. `/{a}/fixed/{b}`
and `/fixed/{c}/fixed` differ at position 0, so the second wins whatever the rest looks like.

That is the entire priority rule: no order field, no weight, no specificity score, no annotation. It is defensible
because it reads as a lexicographic comparison of shapes with `literal < parameter`, which is a **total order** over
shapes — and a total order needs no tie-breaker, because a tie means two routes with the same shape under the same
method, and §9 refused the second one at registration. `Priority` has a test for each of those three properties,
including `registrationOrderDoesNotChangeTheWinner`, which builds the same pair in both orders and asserts identical
answers.

"Most literal wins" is chosen over the two alternatives people reach for. Longest-prefix wins (`/users/{id}` beating
`/users/new` because it is registered first, or because it is longer) makes a wildcard swallow a route written for it.
First-registered wins makes an application's behaviour depend on which plugin the assembler happened to register
earlier, which is the failure mode §9 refuses to allow twice in the same table and refuses to reintroduce through the
priority rule.

### 9. Duplicate routes: refused, by shape

`GET /users` twice **fails at registration** with an `IllegalArgumentException` naming both patterns and the shape they
share. So does `GET /users/{id}` followed by `GET /users/{userId}`, because the collision is about what a pattern
answers, not about how it is spelled — comparing patterns as text would let a rename create two owners of one
resource, which is exactly how two plugins come to serve the same endpoint without either noticing.

Silent replacement is refused because it loses a handler: the first plugin's route disappears and nothing reports it.
Last-one-wins is refused because it makes a running application's routes depend on plugin order, turning an
architecture question into a coincidence. Both alternatives also fail at the wrong time — the first request that
happens to reach the route, months later. Refusing at registration puts the failure on the line the developer wrote,
during assembly, which is the same argument that makes `ServiceAssembly.build()` create everything eagerly rather than
on first lookup (ADR-0014).

Refusal is also local. A refused registration leaves the table untouched and the builder usable
(`RouterTest.anInvalidPatternLeavesTheTableUntouched`), and two independent `Router`s may hold the same route, because
nothing here merges tables — merging is where a route registry with precedence rules arrives, and there is no merging
method to call (`twoIndependentTablesMayOverlap`). `RouterPluginIntegrationTest` shows the failure arriving where a
framework would otherwise never see it: a second plugin's duplicate surfaces as a `StartupException` naming the
plugin, because the orchestrator ran the `initialize()` that registered it.

`POST /users` and `GET /users` are not duplicates (§3). `/users` and `/users/` are not duplicates, because they are
different segment counts (§6). `/users/{id}` and `/users/new` are not duplicates, because they are different shapes —
both answer `/users/new`, and §8 decides that one rather than refusing it at registration, since a static route
overriding a parameter route is the normal case, not an accident.

### 10. No route, and a method mismatch: 404 versus 405, as two questions

Both facts are needed and neither is the router's to state:

| situation | `match(request)` | `methodsFor(path)` |
| --- | --- | --- |
| `GET /users` registered, `GET /unknown` requested | empty | empty |
| `GET /users`, `POST /users` registered, `DELETE /users` requested | empty | `[GET, POST]` |
| `GET /users` registered, `GET /users` requested | present | `[GET]` |

The two queries together are the `404`/`405` decision, handed to the adapter as data. `methodsFor` returns the methods
in registration order as an unmodifiable set, which is what an `Allow` header needs — and the order is the only place in
this design where registration order is observable at all, and it carries no meaning beyond "as listed".

The alternative considered was one richer result: `match` returning something like
`Matched | MethodNotAllowed(methods) | NotFound`. It was refused for three reasons. It is a third public type, and
every public type needs a consumer (§19); it makes a routing call answer an error question, which §1 assigns to the
adapter; and it forces the caller to branch on three states when it is asking two questions — "who handles this" and
"what would have handled it", which are separately useful (an adapter that logs unrouted paths wants the second for
every request, including the ones that matched). `Optional` plus `Set` is the same information in two types the
contract already knows how to hand back.

No `Problem`, no `ErrorCode`, no `GlobalExceptionHandler`, no JSON error envelope, no status constant appears in this
module. `askingAboutAPathNeverProducesAResponse` asserts the absence from the inside (nothing here reaches for
`Response`'s constructor) and `the_router_never_answers_a_request_itself` (§20) asserts it as an architecture rule.

`methodsFor(String)` takes a bare path rather than a `Request`, so it re-applies the rule `Request.path()` already
applies to that field: a path that does not begin with `/` is refused rather than silently matching nothing. A silent
empty answer here would be a wrong `404`, which is the least diagnosable failure a web layer can produce.

### 11. `RouteMatch`, and how the table stores a route

A bare `Handler` was the candidate return value, and for a parameterless route it would have been enough. The moment
`/users/{id}` exists, matching has also decided that `42` is `id` — and a caller cannot recover that, because it never
sees the pattern. That is the whole justification for the type's existence, and it is checked the way §8 of the
directive asked: `RouteMatch` exists because parameters are unreconstructable by the caller, and nothing else is in it.

Two components, therefore, and no third. The matched pattern, a route name, a handler class, a priority, an attribute
map: each is metadata with no consumer today (§19). There is deliberately no `parameter("id")` accessor — reading
`parameters()` shows a handler what the route actually captured instead of trusting a name typed from memory, and the
names the pattern declared are the only keys present, so a wrong name shows up as a missing key rather than a wrong
value.

`RouteMatch` is a record; `Router.Registration` — the private row of the table — is a record holding `List`s. The two
choices are the same rule applied to different contents, and it is the rule ADR-0018 §2 established when it made
`Request` a class: a record whose components include an array gets an `equals` that compares references and a
`toString` that prints `[B@1a2b3c`. A route wants to compare by value (so two matches of the same request are equal,
which is what lets a test assert an answer instead of an identity) and a `Registration` holds a split path, so both
use `List`. `RouteMatch` holds no arrays at all, which is where a record is simply the honest shape: immutable,
value-equal, and its components are the entire state.

### 12. The registration API: one method and a builder

`Router.builder().route(method, pattern, handler).build()`. The directive asked for exactly one of
`router.get/post/...` versus `router.route(HttpMethod, path, handler)`; this is the second, with a builder rather than
registration on the live router.

Why `route` and not nine method-named shortcuts. `get(...)`, `post(...)`, `put(...)`, `delete(...)`, `patch(...)`,
`head(...)`, `options(...)`, `trace(...)`, `method(...)` would be nine ways to say one thing, nine documents to keep in
agreement, and — the reason that decides it — they would name `HttpMethod` twice, once as a value type the contract
deliberately built (ADR-0018 §4, which exists because non-standard methods must be registrable) and once as Java
method names, which cannot cover `PURGE` and would leave `method(HttpMethod.of("PURGE"), ...)` as an awkward door
beside a set of elegant ones. `ServiceAssembly.Builder` made the same call when it kept `instance` and `factory` and
added nothing else.

Why a builder rather than registering on a live router. §13's immutability is the reason, and it is a strong one: a
`Router` with a public `route(...)` is a `Router` a request thread can race. `Router.Builder` makes the register phase
a distinct object with a distinct lifetime, which is also the shape `ServiceAssembly.Builder` and
`LifecycleOrchestrator.Builder` already give an application — build, freeze, hand out. The builder is the only cost,
and it is a nested class with two methods rather than a separate type in the surface list.

`route` returns the builder so an application writes one registration per line; `build()` copies the list and hands
back an immutable table. Nothing is lazy, and nothing needs to be: unlike `ServiceAssembly`, a router has no factories
to run twice, so ADR-0014's second-build question does not arise and a builder may be used again after building — what
it changes is only ever the next table.

### 13. Thread safety and immutability: the freeze is in the data

The register phase writes to a `List` inside a builder. `build()` copies it with `List.copyOf`. After that, no caller
of a `Router` can reach anything writable, because there is nothing writable to reach: `Router`'s only field is final,
`match` and `methodsFor` return fresh values (`Optional`, an unmodifiable `Set`) over a private record that hands out
no collections, and `RouteMatch` copies and freezes the map it was built with.

So `Router` is not thread-safe; it does not need to be. Concurrent matching cannot race a registration because there
is no such thing as a registration after `build()` — the race does not exist to lose, rather than being managed with a
lock or a volatile. `Concurrency` runs 8 threads × 500 repeats over six distinct questions and asserts every answer
equals the single-threaded answer.

This is also why the first draft of `Router` was thrown away: it had a mutable table and a `synchronized route(...)`,
which works but breaks `every_field_is_final` — Phase 2.7's rule, which this phase must not weaken — and replaces a
property of the data with a promise about the code. Two consequences of the copy are worth naming precisely, because
both were found by tests: `List.copyOf` rather than `Collections.unmodifiableList`, so a builder still in use cannot
change a router an adapter already holds (`aBuiltRouterDoesNotSeeRoutesRegisteredAfterwards`); and `RouteMatch`'s
defensive copy, because a caller-built match is the one place a shared map could otherwise arrive.

What is therefore absent, each because the design above removes its need: hot reload, dynamic route replacement,
runtime unregister, distributed route sync, and a `built` flag to police double registration. `Router` exposes no
mutator and no inspection method (`the_route_table_exposes_only_the_questions_it_answers`, §20).

### 14. Not a lifecycle participant, and not a container

A router owns no thread, no socket, no executor, no queue and no file handle. There is nothing to start and nothing to
stop, so there is no phase to be in, and `LifecycleParticipant` is not implemented. `LifecycleOrchestrator` drives the
plugins that register routes; the table itself is a value the application passes around.
`the_router_implements_no_interface` is the mechanical half — a router that became `AutoCloseable` "for symmetry" has
acquired a resource it does not have, and the rule that stops that is a dependency statement, not a paragraph.

Nor is it a container, which is the more likely drift because a router genuinely does hold objects:

- no `router.service(X.class)`, `router.config(...)`, `router.eventBus(...)`, `router.resolve(...)`;
- no `RouterContext`, no `router.context()`, no attribute bag, no way to look an object up by type or by name;
- handlers are built by the application and handed in — the router never builds one, and cannot, because it holds
  `method + shape → Handler` and nothing else.

The distinction §15 has to hold in practice: a router may be *created* by application assembly and *injected into* a
plugin, which is ordinary constructor injection; what it must never become is a thing a plugin asks for things from.
ADR-0006 refused that for a `PluginContext` and ADR-0018 §9 refused it for a request; this is the same refusal with a
different component attached. `nothing_takes_a_type_or_a_key_to_find_an_object`, inherited from Phase 2.7, is what
makes a `Router` method that resolves by `Class` or by name fail the build rather than the review.

### 15. Plugin registration: constructor injection, no `PluginContext`

`RouterPluginIntegrationTest` is the phase's integration proof, and it is deliberately tiny:

```java
Router.Builder routes = Router.builder();
UserStore store = new UserStore();
UserPlugin plugin = new UserPlugin(routes, store);          // the router arrives as a constructor argument

try (LifecycleOrchestrator app = LifecycleOrchestrator.builder()
    .registerShutdownHook(false)
    .register(plugin.descriptor().id(), plugin)
    .build()) {
  app.start();                                              // initialize() registers the routes
  Router router = routes.build();                           // the application freezes the table
  router.match(get("/users/42")).ifPresent(m -> m.handler().handle(get("/users/42")));
}
```

The chain the directive asked for — application assembly → Router → Plugin → plugin registers a route — is those five
lines, and what makes it a proof rather than an example is what is *not* there:

- no `PluginContext`, and nothing named that exists in core to have a `require(...)` on it — asserted by
  `coreHandsOutNoContextForAPluginToLookThingsUpIn`, so the test fails if a `PluginContext` arrives later;
- no `Configuration`, `EventBus` or `ServiceAssembly` field on the plugin (`thePluginTakesOnlyWhatItRegistersInto`);
- no `Router` lookup by the plugin, and no `Router` registration as a participant — the application builds it;
- routes registered in `initialize()` rather than in the constructor, because `initialize()`'s documented purpose is
  "complete internal wiring without becoming observable", and a plugin built during assembly is not necessarily wired
  yet (ADR-0003). Before startup the table answers nothing, which the test asserts, so an application that routes an
  unbuilt application sees an unbuilt application.

Two plugins share one table without meeting each other (`twoPluginsShareOneTableWithoutMeetingEachOther`), which is
composition rather than coordination: neither holds a reference to the other, and neither can see the other's routes
except through the collision rule. That is the point of §9's duplicate refusal — two plugins that both own
`GET /users` get a `StartupException` naming the plugin whose registration collided (ADR-0016's plugin id, which is
what a failure is reported against), not a silent precedence order derived from the assembler's line order.

The dependency this test needs is `barezen-core` at **test scope**, and it is the only respect in which this phase
changes Phase 2.7's headline claim. Stated precisely: `barezen-web`'s compile classpath is still **empty**,
`barezen-web-0.1.0-SNAPSHOT.jar` still ships with no dependencies and no core type reference, and the two rules that
read the package (`the_contract_depends_only_on_java_lang_and_java_util`,
`the_contract_does_not_reach_into_core`) import with `DoNotIncludeTests` and so still measure only the shipped surface.
A main source file that imports `com.barezen.core` does not compile — probed: `package com.barezen.core.config does not
exist`, with core present at test scope. So the edge is real but reversed-safe, and the sentence "zero dependencies" in
README and ADR-0018 now means *of the shipped artifact*, which is what it always meant. It is recorded here rather than
in ADR-0018 because that record is accepted and cannot be edited.

A logging binding was added for the same test and then removed. `barezen-core` logs startup, so the web module's test
JVM now prints SLF4J's three-line "no providers were found" notice to stderr and defaults to a no-op logger. Nothing in
`barezen-web` logs, so a binding would only give the contract's tests an implementation to write to; a three-line
notice is the better price. This is a change from ADR-0018's §12 note about there being no binding to satisfy, and the
reason it is mentioned at all is that a reader who sees the warning should know it is deliberate.

### 16. Why no Middleware

`Middleware`, `MiddlewareChain`, `Pipeline`, `Filter`, `Interceptor`: none exist, because wrapping a handler needs no
new type — it needs a lambda that closes over one:

```java
Handler secured = request -> authorized(request) ? inner.handle(request)
                                                  : new Response(401, Headers.empty(), null);
```

That was already true when ADR-0018 §11 said it, and a router does not change it: a wrapped `Handler` is still a
`Handler`, so `route(method, pattern, secured)` registers it like any other. Every argument for a chain — ordering,
per-route application, early exit, wrapping the response — is answerable in this shape by construction rather than by
configuration, and the answers are visible at the registration line instead of in a registry.

A `Middleware` interface would add the things this phase is trying not to have: a second abstraction with its own
order semantics (§8 already had to define ordering once; a chain and a router disagreeing is a bug class), a place for
mutable request state (which ADR-0018 §8 removes from the design), and a strong argument for the `Router` to dispatch,
since a chain has to call the next element. §1 says it may not.

### 17. Why no generic ExtensionPoint, and no Router Context

Phase 2.6 refused `ExtensionPoint`/`ExtensionRegistry` on the grounds that an event bus is not a general extension
mechanism, and §15 of this phase is the case that was predicted: a real extension need arrived — "a plugin contributes
routes" — and it needed **no general mechanism**. The router has one specific contribution API, `route(...)`, used
through constructor injection. A generic `ExtensionPoint<T>` would have been a type with no consumer, since the
"generic" part is precisely the part nobody uses.

No `RouterContext`, `RequestContext` or `HttpContext` either, and §1 is why: a context is where a match's parameters
would be smuggled to a handler, which would turn the contract's one signature into an object bag, and ADR-0007
placed a request context in this layer without ever approving one (ADR-0018 §13 finished that argument). §7 names the
resulting gap — a handler cannot see its parameters today — and refuses to close it with a context. A context that
only the router can fill in is not a context, it is a hidden second argument, and the honest way to give handlers
their parameters is to change `Handler`, which is ADR-0020's decision to make, not this one's to dodge.

### 18. Why no dynamic discovery, scanning, or annotation routing

`the_contract_never_reflects_loads_or_scans`, inherited from Phase 2.7, already refuses reflection,
`Class.forName`, `ClassLoader.getResources` and `ServiceLoader` in this package, and the probe re-ran it for a router
that reached for discovery (`p10`, §22). Three more rules keep the router inside the same line:
`the_contract_declares_and_reads_no_annotations` means there is no `@Route` to scan;
`no_preemptive_routing_or_middleware_types` means there is no registry to scan into; and a route table assembled in
code is checkable by a compiler and a review, which a discovered route is not.

The temptation is strongest exactly here, because annotations are how the rest of the ecosystem writes controllers and
a reviewer who has seen Spring will read `route(GET, "/users/{id}", handler)` as boilerplate. The trade is not close
for this framework's goals: scanning buys fewer typed lines, and costs the compile-time reference that makes
`handler` a name a reader can jump to, an ordering question at startup, a classpath question at runtime, and a
framework that knows about application classes rather than the reverse. ADR-0014 chose explicit factory registration
over classpath discovery for the same reasons in a different layer, and ADR-0016 made a plugin a participant whose
dependencies are visible in a constructor. A scanned route would contradict both.

So: registration is explicit, in application code, once per route, and the only way to make a route exist.

### 19. Types with consumers, and the names refused in advance

Every public type in the module has a caller today:

| type | its consumer, now |
| --- | --- |
| `Router` | the application assembly and the adapter: it answers `Request → Handler` (§1, §15) |
| `Router.Builder` | every route registration in the above, and the only way to obtain a `Router` (§12) |
| `RouteMatch` | `Router.match`'s return value; an adapter reads `handler()` and `parameters()` (§11) |

`Router.Registration` is private, and deliberately not a public `Route`: a route is a thing a table holds, and no
caller talks about one. `RouterTest.thereIsNoPublicRouteType` asserts exactly one public nested class, so the table's
rows cannot be promoted into the surface by accident.

The names this phase refuses until a test proves otherwise, enforced by `no_preemptive_routing_or_middleware_types`
over top-level **and member** classes: `Route`, `RouteDefinition`, `RoutePattern`, `PathPattern`, `PathMatcher`,
`RouteMatcher`, `RouteRegistry`, `RouteTree`, `RouteNode`, `RouteParameter`, `RouteParameterMap`, `RouteMatchResult`,
`RoutingContext`, `Middleware`, `MiddlewareChain`, `Pipeline`, `Filter`, `Interceptor`, `HandlerChain`,
`ParameterBinder`, `ParameterConverter`, `PathParameterResolver`, `ParameterRegistry`.

Checking member classes is the part that earns its keep, because the realistic drift is internal and reasonable: a
`Router$RouteNode` for a trie appears with no new public type and no API change, and that is exactly where a path
matcher, a prefix tree and a wildcard grow. The probe put one there and the rule found it (`p7b`).

`Router` plus `RouteMatch` is, as the directive put it, the acceptable end state — and it is also the actual one.

### 20. What Phase 2.7's rules had to be amended, and how

ADR-0018 is accepted, so its file is unchanged and the amendment lives here. Its architecture test contained two rules
that, left as they were, refuse this phase:

1. **`the_contract_is_exactly_five_types`** — an allow-list of five names, which `Router` and `RouteMatch` violate by
   existing. Now `the_contract_is_exactly_the_seven_contracted_types`, listing seven. Renamed rather than left saying
   "five" for a boring reason: a rule whose name disagrees with its list is a rule nobody trusts when it fails. Still
   an allow-list, so an eighth type fails the build — which is the property worth keeping, not the count.
2. **`no_container_shaped_type_names`** — ADR-0018 §16 put `Router` on its exact-name ban list, when routing was not
   in that phase. The entry is removed; `Route`, `RouteRegistry`, `PathPattern`, `RouteMatcher`, `Middleware`,
   `HandlerChain`, `Filter` and `Interceptor` stay, and §19's new rule covers the same ground over member classes. The
   alternative — naming the type something that slips past the list (`Routes`, `PathRouter`, `Dispatcher`) — would
   have let a rule from a previous phase design this one. The failure was observed before the amendment
   (`com.barezen.web.Router is named Router, which is a name this phase refuses`) and is the evidence.

Five rules were added, all aimed at the router rather than the contract, taking the module's total to nineteen:

| rule | the drift it refuses |
| --- | --- |
| `the_router_never_answers_a_request_itself` | `Router`/`RouteMatch` depending on `Response`: a 404 built in the routing layer (§1, §10) |
| `the_route_table_exposes_only_the_questions_it_answers` | a public mutator or inspection method: `unregister`, `replace`, `reload`, `size`, `routes` (§12, §13) |
| `the_router_implements_no_interface` | `implements LifecycleParticipant` / `AutoCloseable`: a phase or a resource (§14) |
| `routing_decides_on_method_and_path_only` | reading `headers()`, `query()` or `body()` while routing: negotiation, versioning, buffering (§4) |
| `no_preemptive_routing_or_middleware_types` | `Route`, `PathMatcher`, `RouteRegistry`, `RouteNode`, `Middleware`… with no consumer yet (§16, §19) |

Everything else Phase 2.7 froze still holds with the router in the package, unchanged and unreweakened: the JDK
allow-list, the no-server rules, the no-core rule, the service-locator rule, the no-reflection rule, the
no-execution-machinery rule, `every_field_is_final` (which is why `Router`'s table is final rather than synchronized —
§13), `no_method_writes_or_mutates`, `nothing_in_the_contract_dispatches_a_handler`,
`every_contract_type_is_final_or_an_interface` and the annotation rule.

**Mutation probes.** Nineteen rules, and each one that this phase could make fail, was made to fail. A violation was
applied to a main source file, `mvn -o test -Dtest=BarezenWebArchitectureTest` was run, the failing rule set was
recorded, and the file was restored byte-identically from a snapshot taken beforehand (`cmp` verified). Twenty-one
probes:

| probe | violation | rule(s) that fired |
| --- | --- | --- |
| p1 | an eighth top-level type, `ProbeRoute` | `the_contract_is_exactly_the_seven_contracted_types` |
| p2 | `public final class Middleware` with a `Handler next` field | `no_container_shaped_type_names`, `…_exactly_the_seven…`, `no_preemptive_routing_or_middleware_types` |
| p2b | `public final class HttpContext` holding a `Request` | same three, via the `Context` suffix |
| p3 | `match` returning a `404` response instead of `Optional.empty()` | `the_router_never_answers_a_request_itself` |
| p4 | `public void unregister(HttpMethod, String)` | `the_route_table_exposes_only_the_questions_it_answers` |
| p4b | `public int size()` | `the_route_table_exposes_only_the_questions_it_answers` |
| p5 | `Router implements AutoCloseable` | `the_router_implements_no_interface`, `the_route_table_exposes_…` |
| p6 | routing on `request.headers().first("X-Version")` | `routing_decides_on_method_and_path_only` |
| p7 | `public final class PathMatcher` | `no_preemptive_routing_or_middleware_types`, `…_exactly_the_seven…` |
| p7b | `private static final class Router$RouteNode` with a children list | `no_preemptive_routing_or_middleware_types` (member class, as intended) |
| p8 | `java.net.URI` + `normalize()` on the request path | `the_contract_depends_only_on_java_lang_and_java_util` |
| p9 | `import com.sun.net.httpserver.HttpServer` and a `bind()` returning one | `no_jdk_http_server`, allow-list, table-surface rule |
| p10 | a `builder(Supplier<Class<?>>)` calling `Class.forName` | `the_contract_never_reflects_loads_or_scans`, allow-list |
| p11 | a `java.util.concurrent.Executor` field | `the_contract_owns_no_execution_machinery`, allow-list |
| p12 | non-final `private List<Registration> registrations` | `every_field_is_final` |
| p13 | `public void clearParameters()` on `RouteMatch` | `no_method_writes_or_mutates` |
| p14 | `public class Router` (not final) | `every_contract_type_is_final_or_an_interface` |
| p15 | `best.handler().handle(request)` inside `match` | `nothing_in_the_contract_dispatches_a_handler` |
| p16 | `Request.attribute(String)` | `nothing_takes_a_type_or_a_key_to_find_an_object` |
| p17 | `@Deprecated` on `Router` | `the_contract_declares_and_reads_no_annotations` |
| p18 | `Router.builder(Configuration)` after moving `barezen-core` to compile scope | `the_contract_does_not_reach_into_core`, allow-list; `framework/barezen-web/pom.xml` restored byte-identical, and a repeat of the probe with only the test-scope declaration produced `package com.barezen.core.config does not exist` |

Two probes were failures of the probe rather than of a rule, and both are recorded because they are the traps that make
an architecture test quietly useless. A probe that added a second public top-level class to an existing file does not
compile at all — javac allows one public top-level type per file — so those probes had to become separate files, which
is also the realistic shape of the drift. And p3's first draft used a lambda parameter named `request`, which shadowed
the enclosing parameter; it looked like a passing rule and was a build error. `no_server_or_web_framework` (Jetty,
Netty, Servlet, Spring) was probed in ADR-0018 §12 and is not re-probed here: nothing in this phase touches those
types, and its `allowEmptyShould(true)` means an unused ban cannot start failing on its own.

The final `mvn -o clean verify` is green on JDK 17 and JDK 21: 245 core tests and 196 web tests, 19 ArchUnit rules,
the JDK 21 coverage gate met, `scripts/check-jdk-baseline.sh` reporting `OK: JDK 17 baseline intact`, class files at
major version 61, and `dependency:tree` showing `barezen-core:jar:…:test` as the only BareZen artifact on the web
module's classpath.

### 21. Printing, and what a route table never names

`Router.toString()` prints `Router[GET /users, POST /users, GET /users/{id}]`: every route, in registration order, and
nothing else. A route table is the single most useful thing an operator wants in a startup log line, and the two things
excluded are excluded on purpose. A handler's `toString()` is whatever the application wrote — a class name, an
identity hash, or something that repeats request data — so it is not printed, not even as a class name, because a
lambda's reads `$$Lambda$147/0x…` which names nothing and would put a runtime type query inside a contract that has
none. The *shape* is not printed either, because the pattern is what the developer wrote and what an operator needs;
the shape is the internal key §9 compares.

`RouteMatch.toString()` prints `RouteMatch[1 parameter: id]` — the names, never the values, never the handler. A match
is where a routing log line ends up, so it follows the rule `Request.toString()`, `Response.toString()` and
`Headers.toString()` already follow: what the client sent stays out, because a path parameter can be an email address,
a token or someone's name, and putting it in a summary puts it in the log. `RouterTest.aSummaryNeverNamesAHandler` and
`RouteMatchTest.printingNamesTheParametersButNeverTheirValuesOrTheHandler` assert both halves, including a
`password=hunter2` value that must not appear.

There is no logging anywhere in this module, and adding none is not a gap: ADR-0001 makes `slf4j-api` core's single
third-party dependency and MVP has no observability plan; a router that logged an unrouted path would need a facade,
and the allow-list rule that keeps it off the classpath is the same one that keeps `java.net` off it.

### 22. Tests, and what each group proves

196 web tests, 95 of them new. `RouterTest` is organised by the semantics it pins down, because the point of the phase
is a set of decisions rather than a set of methods:

`Surface` (8) — the public shape: four `Router` methods, two `Builder` methods, one way to build, one public nested
class, chaining, an empty table, and the two hand-off proofs (a match's handler is callable; the router never calls
one). `StaticRoutes` (5), `MethodSeparation` (5), `Duplicates` (7), `NoRoute` (7), `Parameters` (8), `Priority` (5),
`Encoding` (7), `TrailingSlash` (5), `RefusedPatterns` (9), `Immutability` (5), `Concurrency` (3), `Printing` (3).
`RouteMatchTest` (12) holds the type to two components, checks the defensive copy, the frozen map, the value equality,
and the printing rule. `RouterPluginIntegrationTest` (6) is §15.

Three bugs were found by tests rather than by reading, and the tests are named for what they caught:
`captured()` zipped parameter names against path indices, so `/a/{x}/b/{y}` captured `a` as `x`
(`twoParametersAreCapturedAtTheirOwnPositions`); `shape()` skipped empty segments when joining, so `/` and `//` shared
the shape `""` and registering `//` after `/` was refused as a duplicate despite having different segment counts
(`theRootAndATrailingSlashPatternAreDistinctRoutes`); and a first draft of `match` compared a candidate against
`best.handler() == null ? best : best`, an expression that always evaluated to `best` and could not have failed a
test. A `RouterTest` written against an earlier mutable design also asserted `IllegalStateException` behaviours that
the builder redesign removed; those assertions were deleted rather than reworded.

### 23. What a first HTTP server adapter now has to do

ADR-0018 §14's adapter shape survives unchanged, with one clause filled in: the adapter holds a `Router` instead of a
`Handler`.

```java
private void serve(HttpExchange exchange) throws IOException {
  Request request = new Request(HttpMethod.of(exchange.getRequestMethod()),
      exchange.getRequestURI().getRawPath(),                   // raw: §5 depends on this line
      exchange.getRequestURI().getRawQuery(), fromJdkHeaders(exchange.getRequestHeaders()),
      exchange.getRequestBody().readAllBytes());

  Optional<RouteMatch> found = router.match(request);
  if (found.isEmpty()) {
    Set<HttpMethod> allowed = router.methodsFor(request.path());
    respond(allowed.isEmpty() ? 404 : 405, allowed);           // §10's two answers, becoming §1's decision
    return;
  }
  respond(found.orElseThrow().handler().handle(request));       // the adapter dispatches; the router never did
}
```

The adapter remains a `LifecycleParticipant` — it owns a port, so it starts and stops (ADR-0003) — still in a module
of its own (`barezen-http-jdk`, ADR-0008), still the only place `com.sun.net.httpserver` is named, still the owner of
the body limit, the exception catch, the error format, `Content-Length` and `HEAD`. A second server is a second module
against the same `Router`.

### 24. Consequences and remaining design debt

- **An application can now route.** Contract plus table is enough to serve a request through an adapter that does not
  exist yet; `barezen-web` still owns no socket, so nothing in the repository can accept a connection. That is the
  boundary holding, not a gap.
- **ADR-0018's surface claim is now two types wider and one sentence softer.** Seven types instead of five (§20);
  "zero dependencies" means of the shipped jar, with core present at test scope only (§15). Both are recorded here
  because the accepted record cannot be edited.
- **A handler cannot see its path parameters.** §7's gap, §17's reason for not closing it with a context. The fix is a
  contract decision (ADR-0020), and the integration test currently re-derives the id from the path to have something to
  run at all. This is the phase's debt and the most likely reason a future phase has to revisit `Handler`.
- **No wildcard, no regex, no optional segment, no prefix match, no repeated segment.** All refused for having no
  consumer (§4, §19). The first one that a real application needs will need a semantic decision of its own — most
  likely a suffix wildcard for static files, which changes the segment-count rule and therefore needs an ADR rather
  than a patch.
- **`/users` and `/users/` are two routes and both must be written down** (§6). An application that wants a redirect
  needs the adapter to own it, which needs a decision about `Location` and about whether the redirect is permanent;
  that pair belongs with the first adapter, not here.
- **`methodsFor` scans the whole table**, as does `match`: O(routes) per request, no trie, no prefix tree. Fine for a
  framework whose route count is a developer's typing, and `RouteNode` (§19) is the type a later phase adds if a test
  ever shows otherwise. The matcher's shape was chosen so a trie is a private replacement, not an API change.
- **`405` cannot yet name its allowed methods to a client.** The data exists; the response is the adapter's (§10, §23).
- **Priority is over shapes only.** Two routes of the same shape under the same method cannot exist, so no tie can
  reach the comparison (§8). A future host-based or version-based rule would need a different order and a different
  ADR, not a fifth field on a route.
- **No middleware, no extension point, no context, no discovery** (§16, §17, §18) — each with a section explaining
  what would have to be true for it to appear, and none with a test that asks for it today.

**Amendment (2026-11-27): reject route patterns the request contract cannot express.** `Builder.route` now
checks raw target characters before leading-slash, query and parameter diagnostics. Space, tab, CR/LF and DEL are
refused with an index and escaped code point, never the raw pattern in the message. The check is registration-time
because such a route can never match a valid `Request` and would put unsafe text into startup logs. `%20` and other
percent-encoded literals remain valid. `RouterTest` enumerated raw space, tab, CR/LF and DEL; all four were accepted
before the change, and a reverse control registers and matches `/users/a%20b`.

## Consequences

- **Routing is a web capability, and it is now one.** `barezen-web` holds a contract and a table; `barezen-core` holds
  neither, and its rules make that structural.
- **Every routing semantic is a test.** Ten questions answered at the top of this record, each with a named test group:
  raw segments, exact counts, case-sensitive literals, `%2F`, `%2e%2e`, trailing slash, duplicate shapes, priority,
  `404`/`405`, concurrent matching, immutability, plugin registration.
- **Phase 2.7's rules survived the phase that contradicted them.** Two were amended with the reason recorded, five were
  added, and the twelve that were left alone still hold over a package that now contains a router — including
  `every_field_is_final`, which is the reason the table is immutable rather than merely thread-safe.
- **The maxim got its exercise.** A route table is the most obvious place for this framework to start looking like
  somebody else's, and it stopped: `Router` and `RouteMatch` are the end state the directive named, with no registry,
  no context, no annotation, no chain, and nineteen rules to fail the commit that adds one.
