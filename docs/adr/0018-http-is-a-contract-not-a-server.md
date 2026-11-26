# ADR-0018: HTTP is a contract, not a server

Status: accepted
Date: 2026-09-29
Decides: what the web layer's core abstraction is, what a request and a response carry, how a handler fails, where
the server goes, and what `barezen-web` is forbidden to become
Related: ADR-0001, ADR-0002, ADR-0006, ADR-0007, ADR-0008, ADR-0014, ADR-0015, ADR-0016, ADR-0017

## Context

Phase 2.7 adds the first thing a web framework is actually for. Everything shipped so far — lifecycle, threads,
configuration, assembly, plugin SPI, events — is a component an application could in principle use without serving a
byte of HTTP. This phase decides what it looks like when they do, and the decision that matters is not which fields a
request has. It is whether BareZen's web layer can define HTTP at all without naming a server, because if it cannot,
the framework is a wrapper around somebody else's HTTP and the modular plugin design above it is decoration.

The pressure to fail this is ordinary and comes from the right direction. The JDK ships `com.sun.net.httpserver`, so
"we need no dependency" and "we need no adapter module" are the same sentence, and a build with one fewer module is
tempting after six phases of adding them. Jetty and Netty are one pom away and both would run the same handler. Every
web framework in the ecosystem has a `Router`, and a contract with `Request` and `Handler` but no `Router` looks
unfinished to a reviewer who has never been asked to stop. ADR-0007 already decided a `RequestContext` belongs here
rather than in core, which is a decision about location, not about whether the type exists — so this record has to
settle that too, and §9 shows the answer was already implied by ADR-0006.

Two earlier decisions constrain everything below. ADR-0001 makes `slf4j-api` the kernel's only third-party compile
dependency; nothing here asks to widen that, and the web contract turns out to need no third-party dependency at all,
not even SLF4J. ADR-0002 makes JDK 17 the baseline with newer features as a runtime capability, which rules out
borrowing `java.net.http`'s `BodyPublisher` as a shape, because that type belongs to a client.

The phase question was: **what does BareZen have to know about HTTP, and what may it never be handed?**

## The fourteen questions, answered before the code

§4 of the phase directive requires these settled first. Each answer below is the decision; the reasoning follows in
the numbered sections.

1. **Is `Request` immutable?** Yes, completely, with copies on the way in and on the way out (§8).
2. **How does `Response` express status, headers and body?** Three accessors: `int status()`, `Headers headers()`,
   `byte[] body()`. No write method of any kind (§3).
3. **Does a header allow duplicate values?** Yes — a field is a line, and a name may repeat (§5).
4. **Is the name case-insensitive?** Yes for matching; the spelling that was given is the spelling that is kept (§5).
5. **Is the method a string, an enum, or a value type?** A value type with nine named values (§4).
6. **How are URI, path and query represented?** Raw, percent-encoded, as the client sent them. No `URI` (§6).
7. **Is the request body `byte[]`, an `InputStream`, or something else?** `byte[]`, in memory, copied (§7).
8. **Is the response body written or returned?** Returned (§3).
9. **Is the handler synchronous?** Yes (§6).
10. **May it throw a checked exception?** No checked exception is declared, and nothing catches one (§8, §9).
11. **Is `Middleware` in this phase?** No, and §11 shows it needs no new type to exist later.
12. **How does the contract avoid depending on a concrete server?** It declares no compile dependency at all, so a
    server type is not on its classpath (§12).
13. **How is it proven that no Servlet, JDK HttpServer or Netty type leaks in?** Two ArchUnit rules and a mutation
    probe (§12, §14).
14. **What is a `Handler`?** `Response handle(Request)`. One method (§6).

## Decision

`framework/barezen-web` is a new module whose entire shipped surface is five public types:

```java
@FunctionalInterface
public interface Handler {
  Response handle(Request request);
}

public final class Request {
  public Request(HttpMethod method, String path, String query, Headers headers, byte[] body);
  public HttpMethod method();
  public String path();
  public Optional<String> query();
  public Headers headers();
  public byte[] body();
}

public final class Response {
  public Response(int status, Headers headers, byte[] body);
  public int status();
  public Headers headers();
  public byte[] body();
}

public final class Headers {
  public static Headers empty();
  public static Headers of(String name, String value);
  public Headers with(String name, String value);
  public Optional<String> first(String name);
  public List<String> values(String name);
  public List<String> names();
}

public final class HttpMethod {
  public static final HttpMethod GET, HEAD, POST, PUT, DELETE, CONNECT, OPTIONS, TRACE, PATCH;
  public static HttpMethod of(String method);
  public String text();
}
```

Nothing else. `Headers.Entry` is a private nested record and is the only other compiled class. The module has **no
compile-scope dependency, including none on `barezen-core`**, and the allowed direction `barezen-core ← barezen-web`
is reserved for the modules that will need it (§12).

### 1. What the HTTP core is responsible for

Carrying a request to a handler and a response back, in a form that does not belong to a server. That is the whole
responsibility, and the list of what is therefore *not* the contract's business is longer and more useful:

- **choosing which handler runs** — routing (§10);
- **deciding what happens in what order** — a chain, a pipeline, middleware (§11);
- **decoding a path or parsing a query** — an application's rules about its own resources (§6);
- **interpreting a header field** — cookies, content type parameters, `If-Match` etags, quality weights (§5);
- **deciding how an error reaches a client** — an adapter's or a component's (§9);
- **writing bytes, setting Content-Length, keeping a connection alive** — a server's (§3, §12);
- **holding anything the application owns** — a bus, a configuration, a container (§9).

A responsibility is only refused when something else is named to carry it, so each of the above names its owner in
its section. What is left to the contract is the two transfers a handler genuinely performs, which is why the module
is five types and not fifteen: a contract that also routed, parsed, negotiated and reported would be a framework, and
an adapter could not replace it without replacing those too.

### 2. The final model of Request, Response and Headers

`Request` is the client's message. `Response` is the application's answer. `Headers` is the one structure in the phase
worth building carefully, because it is the only place HTTP's own rules are richer than a Java type's defaults (§5).

Three shapes are shared by all of them and are the phase's contribution rather than a style preference:

- **construction validates, afterwards nothing does.** A `Request` that exists has a non-null method, a path that is a
  request target, and headers whose names are tokens and values hold no control character. Raw path and query text
  contains no ASCII space or control character: those belong to request-line framing and must be percent-encoded.
  Refusal messages name the field, index and escaped code point rather than repeating the rejected character. No accessor re-checks,
  because there is nothing left to check.
- **absence is typed, not defaulted.** An absent query is `Optional.empty()`, an absent header is `Optional.empty()`,
  no body is an empty array. Nothing returns `null` from a read, and nothing turns a missing value into a plausible
  one: a handler that wants a default asks for one, in its own code, where a reader can see which default it chose.
- **a summary never carries payload.** `toString()` on all three prints structure — method, path, status, byte count,
  field *names* — and never a body byte or a field value (§8).

`Request` and `Response` are classes rather than records because both need copy-on-construct and validation before
storage, and a compact constructor that copies a `byte[]` twice and then refuses a path is harder to read than the
twelve lines that do it. They stay final, so an accessor means the same thing in every implementation of the contract,
which is the property a handler reads on.

### 3. Response is produced, not written

The alternative is the servlet model: a handler is handed a response object with an output stream and writes into it.
That choice has one consequence that decides the question — once bytes are on the wire, nothing after the write can
change them. So a writing response makes "an after component" impossible: any component that wraps a handler to
modify the answer, or to add a security header, or to record the status for a metric, is too late if the inner handler
already wrote. The phase's own acceptance claim is that a capability can be added without a universal context, and
`Handler -> Handler` composition (§11) is how that stays true in the web layer. Returning a `Response` is what makes
composition possible at all.

The cost is stated: a handler cannot stream. A large generated export or a file download must be fully in memory, and
the response body is copied twice before it is written (§7, §8). That is the same trade `Request` makes and the same
reason: streaming cannot be defined without deciding who owns the connection, which belongs to an adapter (§12). What
the contract must never do is offer both, because a handler that can either return a response or write one has to be
asked, every time, which happened — and an adapter cannot answer that question without a rule of its own.

There is no `ok()`, `notFound()`, `redirect()`, `json()` or `text()` factory on `Response`. Each is a status plus a
`Content-Type` plus a body convention, and a convention on the contract is a rule no handler can decline. The pair
`302` with `Location`, and the rule that `204` must not carry a body, are HTTP semantics worth enforcing — but
enforcing them belongs to the component that will also have to explain them, which is a router or an adapter, not a
value type. The one convenience that survived this test is `Headers.of(name, value)`, because it does not encode an
HTTP rule; it avoids a caller writing a list of one entry.

`HEAD` is the case the shape cannot express: HTTP wants the headers a `GET` would produce and no body. The contract
does not resolve it. An adapter holds the method, so an adapter decides whether to write the body a handler returned —
which is a server question, and `Response.noBody()` would only move it into the wrong layer while adding a second way
to answer. A handler that wants to be efficient about `HEAD` reads `request.method()` and returns what it means to
send; both routes work and neither needs a contract feature.

### 4. The method is a value type, not an enum

Nine names HTTP defines, plus anything else a client sends. An `enum` fails on the second half: a closed set has
nowhere to put `PROPFIND` or `PURGE`, and an `UNKNOWN` constant that discards the text leaves a handler holding a
request it cannot describe to anybody. A `String` fails on the first half: `"Get".equals(method)` is a check that
never matches, quietly, and the spelling a client used is not the developer's choice.

So `HttpMethod` is a final class holding a name, with nine shared values, and `of(String)` normalises once at the
edge:

- the input must be a non-blank RFC 9110 token before any case normalisation: whitespace, controls, non-ASCII and
  separators are refused, while every legal `tchar` punctuation character remains available to extension methods;
- one of the nine, in any case, returns that shared value, and `text()` reads upper case;
- anything else keeps the exact spelling and compares exactly, because a method name is a case-sensitive token — so
  `of("PROPFIND")` and `of("propfind")` are two methods, which is what the specification says and the only rule that
  does not need a list of exceptions.

A probe written before this decision used a mutable `enum` constant — `UNKNOWN.text = method` — and it is worth
recording because the bug is instructive: it is global mutable state reachable from every thread in the process, so
two concurrent requests with different unknown methods would have read each other's text. The enum was replaced the
same hour, and `HttpMethodTest` now asserts the shared values are returned by identity (`isSameAs`), which is what
lets an application write `==` against `HttpMethod.GET` and mean it.

The class carries no behaviour beyond its name: no `requiresBody()`, no `isIdempotent()`, no `safe()`. Those are an
HTTP semantics table the contract would own, and their first consumer is a router — which is not in this phase (§10).
A handler that needs to switch on a method switches on `text()`, which is the one real cost of not using an enum, and
it is cheaper than losing an unknown method's name.

**Amendment (2026-11-27): validate the method token at the public parse boundary.** `of(String)` previously refused
only blank input. It now applies the RFC 9110 `token` grammar before standard-name case folding, using the same visible
ASCII and separator rules as a header field name. The refusal names the offending character as an escaped code point
when it is a control or non-ASCII character, so CR/LF input is not copied into a log-bound exception message.
`HttpMethodTest` enumerates space, CR/LF, tab, non-ASCII, slash and comma; all six were accepted before the change. A
reverse control accepts the full legal punctuation set and `M-SEARCH`. The declared method surface is unchanged because
the check is inline in `of`, and the surface reflection test still passes.

### 5. Header semantics

A field is a name and a value on one line. Two HTTP rules follow, and both are load-bearing:

- **a name may repeat.** This is why the obvious representation is wrong. A `Map<String, String>` keeps one value;
  folding values into a comma-joined string corrupts exactly the fields that repeat, because a `Set-Cookie` value
  legitimately contains commas and semicolons a client reads per line. So `Headers` stores field *lines* in arrival
  order, `values(name)` returns them all, and `first(name)` is the accessor for the fields that have one.
- **a name matches case-insensitively, and is spelled as it arrived.** Matching uses
  `equalsIgnoreCase`; output keeps the developer's spelling, because a header set is written to a socket and
  lower-casing it there silently changes the message. `names()` returns each name once, in first-appearance order,
  with the case it was given. Matching is exact-on-name after case folding — no prefix, no substring, so `X-Trace`
  never answers for `X-Trace-Id`.

**Absent and empty are different facts.** A field sent as `X-Cursor:` exists with an empty value; a field never sent
does not exist. `first` returns `Optional.of("")` for the first and `Optional.empty()` for the second — conflating
them is how "no cursor" and "cursor at the start" become one request. Absence is `Optional` because core already has
that rule for expected absence (`Configuration.get`, `Services.find`); a `null` would force every caller to choose
between trusting this type and checking again. `values` returns an immutable list, empty when the field is absent.

**Two refusals, and both are security rules.** A name must be an RFC 9110 token — no space, colon, comma, slash or
other separator — and a value may hold no control character. A CR or LF in a value ends the field and starts a line,
which is HTTP response splitting, and the values that carry these characters are precisely the ones built from request
data: a file name in `Content-Disposition`, an echo of `Origin`, an id spliced into a location. Enforcing both at
construction is what "secure by default" means for this type: an application cannot accidentally get it wrong, and a
handler that writes a header out of user input gets an exception naming the field, the character, its code point and
its index. The message escapes the control characters it reports, because a rejection message is a channel for the
same content it refuses; a probe found the first draft printed a two-digit `\u0D`, which is no escape any parser
accepts.

**Validation order is part of that safety rule.** The name is checked before a null value is refused, so the
value-error message may include the name only after it is known to be a safe ASCII token. Otherwise
`Headers.of("X\r\nInjected", null)` would put the rejected name into a `NullPointerException` before token validation
ran. `HeadersTest.validatesANameBeforeANullValueCanEchoIt` pins both the exception type and the absence of raw CR/LF.

**No map, no interpretation, no builder.** `Map<String,String>` cannot represent a repeated field and
`Map<String,List<String>>` offers the interpretation this type refuses. Field-specific rules — cookie parsing,
`Content-Type` parameters, comma-splitting, quality-weight sorting for negotiation — are per-field semantics, and a
field rule inside the generic container can be neither overridden nor tested alone; they arrive as small types with
their first consumer. There is no `set` that replaces, because `with` plus construction covers changing a header and
two ways to "put a field" is two meanings for one word. There is no builder type: `with` returns a new instance, so a
handover cannot be edited behind it, and a header set holds a handful of entries.

The surface grew exactly one member after the directive's candidate model. `Request` must expose headers, `Headers`
must be readable and must be extendable by a component that adds one field; `first` and `values` answer two different
questions, and `names()` is what `toString` and any future writer needs. Nothing else — no `has`, no `size`, no
`entries` view — has a caller in this phase.

### 6. Handler semantics: synchronous, one method, no chain

`Response handle(Request)`. Synchronous, because the alternatives are not compatible with the contract's job:

- `CompletableFuture<Response>` puts threading, and therefore an executor and a lifecycle, inside a signature every
  application implements. The question "does BareZen's web layer own threads?" would be answered by a type that
  cannot be implemented without answering it. ADR-0017 applied the same rule to the event bus and named the same
  consequence: an asynchronous model is a different type with its own contract, not a flag on this one.
- `void handle(Request, Response)` — the writing shape — is refused by §3.
- A chain (`handle(request, next)`) is middleware and a router at once, and §11 refuses it.

One method, annotated `@FunctionalInterface`, with no default: a second abstract member would mean every handler in
every application implements both, and the annotation is what makes that a compile error rather than a breaking
release. `Handler` is a plain Java interface, not a `LifecycleParticipant`: a handler is an object an application
constructs and calls, and it owns nothing that needs starting. The interface *is* the extension point of the web
layer, which is the honest answer to "where do plugins hook in" — and precisely why it needs no `ExtensionPoint`
machinery around it (§13).

Who calls a handler is deliberately unanswered. An adapter will, and later a router; nothing in the contract does,
enforced by a rule that no type in `com.barezen.web` may call `Handler.handle` (§14). A contract type that dispatched a
handler would be a router wearing a value type's clothes, and the file listing would not show it.

### 7. The request body is `byte[]`

**In memory, complete, copied in and out.** The costs are named, not conceded quietly: an upload cannot be served
through this contract without buffering it, and buffering an unbounded body is a denial of service.

The reason is ownership. A stream can be read once and only while whatever opened it stays open, so an
`InputStream` body would force the contract to answer: who closes it; what happens when a handler returns without
reading it; what happens when a handler hands it to another thread; what happens when a wrapper calls the inner
handler twice. Not one of those has an answer that does not depend on a server, and a contract that carried a stream
would be a contract that assumes a server. The same argument refuses the speculative shapes the directive names —
`BodyPublisher`, `BodyReader`, `BodyWriter`, `StreamContext`: no handler in this phase consumes them, and
`java.net.http.HttpRequest.BodyPublisher` additionally belongs to a client and to a newer JDK baseline than the one
this framework compiles against (ADR-0002).

A `byte[]` is also what an *adapter* can always produce, which is the practical test for the MVP set: JDK
`HttpServer` reads the body to a byte array, servlet containers expose a stream an adapter can drain, Netty hands you
buffers. Every server can supply this; not every server can supply a live stream that outlives the handler call.

`null` and a zero-length array are both "no body". On the wire they differ (`Content-Length: 0` versus a missing
header), and the distinction is a header-writing job, so this contract has no way to act on it. Bytes are never
interpreted: no charset is applied anywhere, which is what lets one type carry JSON, an image and an archive.
Empty, small, binary and large all arrive through the same door, and large is the known limitation (§15).

The limits that make this honest — a maximum body size, rejected before a handler runs — belong to an adapter, which
is the layer that knows what it can afford to read. The contract cannot choose a number for every application, and a
default in a value type is a limit nobody can raise.

### 8. Immutability, defensively copied

Every field in every contract type is `final`; a rule enforces it (§14). Arrays are copied on construction and copied
on every read.

Three concrete threats, each of which an earlier draft of this module would have had:

- **the adapter's buffer.** A server reuses its read buffer for the next request. A `Request` that aliased it would
  change after the handler already answered — a wrong body in a wrong log line, invisible in review. Copied on the
  way in, and tested by filling the caller's array and re-reading.
- **the handler's own array.** A handler that reuses a scratch buffer, which is the obvious optimisation, would
  otherwise be editing a response it had already returned. Copied on the way in and on the way out.
- **a wrapper's edit.** A `Request` one component could write would mean whichever component ran next was answering a
  different request than the one that arrived. This is the servlet `setAttribute` failure, and it is why §11's
  ordering question cannot be asked here.

The cost of copying out on every read is one array copy per access; a handler reads a body once and keeps it. The
alternative — returning the internal array — would make the immutability claim false in a way no test of the public
API could detect. `Headers` and `HttpMethod` need no copying on read: they hand back immutable lists, and a name is a
`String`.

`toString()` prints method, path and body size; or status, body size and field count; or field names and nothing else.
Never a value or a byte. `Authorization` sits on a request, a bearer token on a custom field, and an error response
repeats what the client sent. A summary lands in a log line and an exception message, where nobody will choose to
redact it later.

### 9. Exceptions: propagate, and who owns the answer

A handler may throw, and nothing in the contract catches. The same identity rule ADR-0017 applies to `publish`:
whatever was thrown is what the caller gets, with no wrapper inventing a message about it. An `Error` travels the
same path — there is no `catch` anywhere in the module, so there is no code path where a `StackOverflowError` becomes
a `500` inside the contract.

No checked exception is declared on `handle`. A checked one would be caught or declared by every wrapper, and a
wrapper has nothing better to do with an `IOException` three handlers down than pass it on; a signature that forces
every component to restate a failure it cannot handle is a signature that teaches applications to swallow.

Two things are excluded, with the reason for each:

- **no error type.** No `HttpException`, no `HttpErrorHandler`, no exception hierarchy. An exception from a handler is
  the handler's own failure, and a contract exception type would be a translation layer with nothing to translate
  *to*. Phase 2.8 owns the framework-wide exception taxonomy; the web layer will contribute to it there rather than
  starting a private one here.
- **no global handler, no error middleware, no JSON error envelope, no logging, no metrics.** Deciding how a failure
  reaches a client means choosing a status, a body format and a log policy for every application. It belongs to an
  adapter, which is the component that must write *something* when a handler threw, or to a component an application
  wraps a handler with — which needs no framework type to exist (§11).

A handler that returns `404` and a handler that throws are both legal, and the contract does not prefer them. `404` is
not an error here; it is a response. Absence is expressed by a status, not by an exception, which is why there is no
`NotFoundException` to catch.

**This is also the section that refuses the service locator.** A handler that lets its exception escape needs
somewhere for that exception to be turned into an answer, and the historical answer is a context object on the request
that holds everything. So the contract forbids it in both directions: `Request` and `Response` carry data that came
from the client or was decided by the handler, and nothing the application owns. No `request.service(Class)`, no
`request.attribute(name)`, no `request.context()`, no `request.eventBus()`, no `request.router()`, no `request.config()`.
No `EventBus`, `Configuration`, `ServiceAssembly` or `LifecycleOrchestrator` is reachable from `com.barezen.web` — in
this build it is not even on the classpath (§12). A handler that needs a collaborator takes it in a constructor, which
is ADR-0016's rule for plugins and ADR-0006's rule for contexts, applied to the layer those ADRs were written to
protect: `Request` is the most tempting place in the framework to put a lookup, because every handler already holds
one. `HandlerTest` proves the alternative works — a handler with an injected collaborator answers a request while
holding one `String` field, and a wrapper short-circuits without any chain object.

### 10. No Router, and what that costs

The names `Router`, `Route`, `RouteRegistry`, `PathPattern` and `RouteMatcher` do not appear in this phase, in source
or in this record's design, and a rule refuses types carrying those names (§14).

The reason is not that routing is unimportant — it is the first thing every application needs. It is that a router
cannot be *designed* before the layer that will own the path is known, and this phase is not that layer. Routing is
decisions about: match against the raw path or a decoded one (§6 says the contract stores raw), whether `/a/` and `/a`
are the same resource, what a `{id}` segment decodes to, whether a registered `GET /a` implies `HEAD /a`, how `405`
differs from `404`, whether a prefix route owns its remainder, and how specificity resolves `/orders/new` against
`/orders/{id}`. Every one of those is a real rule, and none is answerable by asking what a handler needs. A `Router`
written now is an unverifiable guess that the next phase either reproduces or replaces.

The second reason is the one this framework cares about: `Handler.handle(Request)` is the whole extension surface, and
a router is its only consumer. If the router arrived with the contract, the contract would be tested by something
inside the same phase, which is a way to add an abstraction without ever having to justify it. ADR-0015 and ADR-0017
used the same discipline — the assembly responsibility stayed with the application because naming it in the framework
would have made the framework a container; the event bus stayed a leaf because nothing else needed it to be more.
A router arrives with the application that needs a route matched, in the phase that can prove the rules, and it will
find `Handler` already in the shape the router needs — which is the test of whether the contract is right.

What is lost this phase, honestly: no way to bind a handler to a path, so `barezen-web` cannot serve an application on
its own. That is the intended state. The contract is the dependency both an adapter and a router will share; being
unable to route is not a gap in the contract, it is the boundary holding.

### 11. No Middleware, because middleware needs no type

The directive allows middleware only if it can answer six questions: which component consumes it now; why it cannot
wait; what it does to the `Handler` contract; what the ordering rule is; how it short-circuits; whether it may modify
`Request` or `Response`. Answering them:

1. **No consumer exists yet.** There is no router to register a middleware with, no adapter to run one, no security
   component that needs to sit before anything. The phase's own candidate list has four types and every one of them is
   a value or a signature.
2. **It can wait, because the mechanism already exists.** `Handler` → `Handler` is middleware: a component that holds
   a handler, calls it, and returns a different response. `HandlerTest` builds two — one that adds a header, one that
   answers `401` without calling the inner handler at all. Both work today, on the frozen signature.
3. **It would change nothing about the contract** — which is the point. Ordering, short-circuiting, and the ability to
   see a response before it is written all follow from returning rather than writing (§3). A `Middleware` type would
   add a vocabulary word (`next`, `chain`, `order`) without adding a capability.
4. **The ordering question has no answer that is not invented now.** Ordering only matters between middlewares; with
   one consumer there is nothing to order, and a rule written for zero cases becomes a compatibility promise to keep.
5. **Short-circuiting is a return.** A guard that answers `401` returns a `Response` and does not call `next`. No
   chain type is required to not-call something.
6. **It may modify neither.** `Request` is immutable (§8), so a middleware cannot rewrite the request — which removes
   the entire question "does the inner handler see the original or the modified request", along with the servlet
   model's long habit of answering it inconsistently. A component that needs to add information to a request builds a
   new `Request` with the fields it chooses.

The temptation this refuses is specific and well documented: a `Context` that middlewares write into as they pass
down, so an inner handler can read what an outer one learned. That is a service locator with a lifecycle (ADR-0006),
and it is exactly the shape §9 forbids. The alternative that keeps the design — pass values through constructors, or
have the wrapper build a new `Request` — is what the tests show working.

A `Middleware` type may still arrive, when there are several components to order and a router to hold them. It will
wrap `Handler`, not replace it, and no existing type changes when it does.

### 12. No concrete server: the dependency direction, and how it is proven

Allowed direction: `barezen-core ← barezen-web`. Never the reverse.

`barezen-web` declares **no compile dependency at all**. Not on a server, and not on `barezen-core`. That is stronger
than what the phase asked for and it is the fact that makes the phase's claim mechanical: `com.barezen.core` is not on
this module's classpath, so a contract type cannot receive a `LifecycleOrchestrator`, hold an `EventBus`, or ask a
`Configuration` for a value — not because a rule refuses it, but because naming a type from an undeclared dependency
means editing the pom, which is where a reviewer sees an ADR-sized decision. It also means the web contract pulls in
no logging facade, so §9's refusal to log is structural rather than a promise.

The direction stays unexercised this phase because nothing in the contract has a use for core yet. The edge will be
real the moment `barezen-http-jdk` exists, because an HTTP server adapter is a `LifecycleParticipant`: a server owns a
port, so it starts and stops, and that is the application's business (ADR-0003). This record reserves the direction so
the choice is made here rather than by whoever builds the adapter. ADR-0008's rule — no module directory until a phase
fills it in — is why `barezen-http-jdk` is not created now, and creating it would force a `Router` to exist so the
adapter had something to dispatch.

**How the leak is prevented, and how that prevention was tested.** `com.sun.net.httpserver` compiles fine under
`--release 17` — probed directly, on both JDKs, so this is not a platform protection; it is only what a build rule can
enforce. Two ArchUnit rules and one allow-list:

- `the_contract_depends_only_on_java_lang_and_java_util` — an allow-list of `java.lang..`, `java.util` and
  `com.barezen.web`. It refuses `java.net`, `java.io`, `java.nio`, `java.security`, `java.text`,
  `java.util.concurrent`, SLF4J, and every server library, in one sentence. An allow-list because the drift is not
  only "someone added Netty": `java.nio.file.Files` for a static file handler, `MessageDigest` to compute an ETag, or
  `java.text.Normalizer` on a path are JDK types that would make the contract's behaviour depend on a platform, and
  none of them appear on any blacklist anyone would think to write.
- `no_jdk_http_server` and `no_server_or_web_framework` name `com.sun.net.httpserver`, `sun..`, `jdk..`, Jetty, Netty,
  Undertow, Vert.x, Tomcat, Servlet (`jakarta`/`javax`), Spring, Helidon and Quarkus explicitly. Separately from the
  allow-list on purpose: the realistic drift is one commit after this phase, in the same package, where someone adds
  `HttpServer` because it is free. A developer who breaks that rule reads the reason at the moment it matters.

The probe (§14) put a class in `com.barezen.web` holding an `ExecutorService`, a `CompletableFuture` return, an
`HttpExchange` field, a Netty `Channel`, a Servlet request, a `URI`, a `Path`, a `MessageDigest`, a `ServiceLoader`
loop, a `Class.forName`, an `InputStream` read, plus `service(Class)`, `attribute(String)`, `setPath`, `send`, a
non-final field, an `HttpContext`, a `Router`, a `@Route` annotation and a type that called `Handler.handle`. All
fourteen rules fired. Two of them did not fire on the first attempt, and the reasons are recorded because both are
general: the Netty and Servlet references were locals assigned `null`, which leaves no bytecode at all, so the
server-framework rule passed until the references became fields; and the core-reach rule could not be probed without
temporarily adding the dependency, because an undeclared dependency cannot be imported — the proof then required the
pom edit plus a `ProbeCoreReach` holding an `EventBus` and a `LifecycleOrchestrator`, after which both poms were
restored byte-identical.

### 13. Why no universal ExtensionPoint, and no HttpContext

Phase 2.6 refused `ExtensionPoint` and `ExtensionRegistry` on the grounds that an event bus is not a general extension
mechanism. This phase has the same argument available in stronger form, because it now owns the framework's real
extension point: `Handler` is a plain Java interface, and an application implements it. A generic
`ExtensionPoint<E>` would be machinery for discovering implementations of an interface the framework can already be
handed explicitly, which is what ADR-0014 refuses for services and ADR-0016 refuses for plugins. Every generic
extension design also needs a lookup, and a lookup needs a place to live — which is how a `Context` arrives.

`HttpContext` and its relatives are refused for the same reason ADR-0006 refused `PluginContext`: not because the name
is ugly, but because the object needs members, and its members are exactly the list §9 forbids. There is no field of
a per-request context that is not either (a) data the client sent, which belongs on `Request`, (b) a value one
component passes to another, which belongs in a call or a constructor, or (c) a component the application owns, which
belongs in neither. ADR-0007 placed `RequestContext` in `barezen-web` rather than core, and this record is where that
placement is tested: the answer is that the module is the right place for a type that does not need to exist, and
ADR-0007 stays accepted — it decided location, and nothing here contradicts it.

Discovery is refused on the same grounds as ADR-0014 and ADR-0016: no `ServiceLoader`, no scanning, no annotation
reading, no class-path walking, no package enumeration. `Handler` is a `@FunctionalInterface`, and that annotation is
the only one the contract carries or declares — it is checked by the compiler and read by nobody at runtime. A rule
refuses defining an annotation type and carrying a component-describing one, because `@Route("/x")` looks like one
small convenience and behind it sits a scanner, a registry, a startup that discovers components, and the loss of the
compile-time check that makes `Handler` worth having.

The final clause of the phase directive names the failure this section exists to catch: if making HTTP core work
required a Context, a Registry, a Manager or a Container, the right move was to stop extending the API and re-examine
the boundary. The boundary held. Five types, no lookup, no discovery, no context, and one interface an application
implements.

### 14. How a future adapter plugs in

The shape, stated now so the next phase is a composition rather than a redesign:

```java
// barezen-http-jdk, a later phase: depends on both modules, owns neither.
final class JdkHttpServerAdapter implements LifecycleParticipant {   // owns a port: it starts and stops

  private final HttpServer server;                                    // com.sun.net.httpserver, in THAT module
  private final Handler handler;                                      // built by the application, passed in

  @Override public void start() { server.start(); }
  @Override public void stop() { server.stop(0); }

  // On the server's thread: translate, call, translate back. Nothing here is a BareZen concept.
  private void serve(HttpExchange exchange) throws IOException {
    Request request = new Request(                                    // the contract is the only bridge
        HttpMethod.of(exchange.getRequestMethod()),
        exchange.getRequestURI().getRawPath(),                        // raw: the contract keeps the encoding (§6)
        exchange.getRequestURI().getRawQuery(),
        fromJdkHeaders(exchange.getRequestHeaders()),
        exchange.getRequestBody().readAllBytes());                    // the adapter reads the body (§7)
    try {
      Response response = handler.handle(request);
      exchange.sendResponseHeaders(response.status(), response.body().length);
      exchange.getResponseBody().write(response.body());              // the adapter decides what to write (§9)
    } catch (RuntimeException failure) {
      writeAnError(exchange, failure);                                // the adapter chooses the error format
    } finally {
      exchange.close();
    }
  }
}
```

and, in the application's assembly code:

```java
Handler reports = new ReportHandler(userService);                     // collaborators via constructors, always
Handler secured = request -> authorized(request)
    ? reports.handle(request)
    : new Response(401, Headers.empty(), null);                       // "middleware" as a lambda, today

JdkHttpServerAdapter http = new JdkHttpServerAdapter(8080, secured);

ServiceAssembly assembly = ServiceAssembly.builder().instance(Handler.class, secured).build();
LifecycleOrchestrator app = LifecycleOrchestrator.builder()
    .threadPolicy(RuntimeConfig.from(configuration).threads())
    .register("http", http)                                           // an adapter is a participant
    .build();
```

Two properties to notice, because they are what the phase was for. **Nothing between the application and the socket is
a BareZen concept except `Request`, `Response`, `Headers` and `Handler`** — the JDK's `HttpExchange` is confined to the
adapter, which is why a second server is another module and not a rewrite. And **the adapter, not the contract, holds
the JDK-specific decisions**: the body read, the exception catch, the error format, `Content-Length`, and what to do
with a `HEAD` request. A Jetty adapter would make four different choices in the same shape, and `Handler` would not
change.

A router arrives the same way, holding a `Map` of paths to `Handler` and implementing `Handler` itself.

## Consequences

- **The web layer is now definable without a server**, which was the phase's claim. The claim is mechanical: the
  module's dependency list is empty, and the build proves it compiles and tests with no server on the classpath.
- **`barezen-web` cannot serve anything yet.** No routing, no adapter, no port. The contract is the shared dependency
  both a router and an adapter will need, and being unable to serve is the boundary holding rather than a gap to fill
  in the same commit.
- **Bodies are fully buffered in memory, in both directions.** The honest limitation of the MVP set: an upload or a
  large export is served by copying it whole, twice, and the size limit belongs to an adapter that does not exist yet.
  A streaming contract needs a lifecycle, a close policy, and an executor; the same argument that put them outside the
  event bus (ADR-0017) puts them in an adapter — so the streaming body and the first adapter arrive together or not at
  all, and writing one without the other would make the contract's shape depend on one server.
- **`Optional` on `query()` and `first()`, arrays copied twice, `HttpMethod` not an enum** — three decisions that will
  be argued with. All three have a section above and a test asserting the behaviour they buy.
- **Every value stays raw**, so an application decodes a path or a query in its own code with its own rules. The cost
  is a little parsing in every application; the alternative is a framework deciding what `%2F` means for resources it
  has never seen.
- **The immutability of `Request` removes the ordering question from the web layer permanently.** No component can
  change what a later one reads, so no component needs to know what ran before it. This is the property that lets
  middleware arrive later without a contract change, and it is worth the wrapper that rebuilds a `Request` instead of
  editing one.
- **Phase 2.6's conclusions are frozen and nothing here erodes them.** `EventBus` did not move into `barezen-web`, is
  not reachable from it, and is not mentioned in any signature. `Plugin` gained no web member. ADR-0016's and
  ADR-0017's prohibitions are unchanged; §9 is where the web layer inherits them and §13 is where they were re-tested
  against a new kind of component. ADR-0007 is refined, not superseded: it decided where a request context lives, and
  this record decides that no such type is needed, in that module or anywhere else.
- **Design debt carried forward, named rather than absorbed:** no way to bind a path to a handler (§10); no way to
  serve (§12); no `Content-Type` or charset helper, so every application writes the header by hand (§3); no parsed
  query parameters (§6); no error-handling component, so an exception escapes into a server thread whose behaviour is
  a platform default (§9); no `Request` field for scheme, remote address or protocol version, because a handler's
  normal decisions do not need them — `Remote-Addr` is an untrusted header, and the real forms of those facts come
  from a socket an adapter owns. The last two become visible as soon as redirects, conditional requests, client
  identity, a router and streaming exist. Each stays unwritten because nothing in this phase calls it.
