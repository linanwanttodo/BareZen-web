# ADR-0051: Static Content Is One Handler Per File, Because the Router Refuses a Wildcard — and the Conditional GET Consumer That Arrived Leaves ADR-0029 D1 Standing

## Status

accepted
Date: 2026-10-06
Decides: what a static file is in this framework and where it lives, how a file becomes routable without widening the route table, what an `ETag` is derived from and why there is no `Last-Modified`, what a 304 answers now that a real conditional-GET consumer exists, and which files may not be served
Related: ADR-0019 §5/§19 (raw segments, the refused-name list that names `PathPattern` and `PathMatcher`), ADR-0018 §12 (web names no IO, so file serving lives elsewhere), ADR-0029 D1 (the 304 length decision, and the trigger this phase meets), ADR-0023 §2 (a 404 names no path), ADR-0030 (a response body is `byte[]`, with named reopening conditions), ADR-0011/ADR-0013 (no clock in a decision that has an argument, no configuration read at request time)

## Context

Two things in this repository were waiting for the same consumer. ADR-0029 D1 decided a `304` carries no
`Content-Length`, and recorded the reason as *"the only in-process source for RFC 9110 §8.6's bound value would be an
unverifiable handler assertion"* — with a named trigger: *a real conditional-GET consumer*. And README's module table
carried static file serving as a NON-GOAL inherited from the 2.9 phase brief, in a framework that had no way to serve a
stylesheet at all.

Both were about to be needed by the same thing: an official website for the framework, served by the framework, which is
also the "real consumer with named rules" ADR-0016 said a frontend needed before this project would talk about one.

The design question turned out to be a routing question. Every static-file server in existence wants `/assets/*`, and
`Router`'s patterns are a literal path or one `{name}` segment — no wildcard, no prefix, no multi-segment capture — because
ADR-0019 §19 refuses `PathPattern`, `PathMatcher` and the rest of that family by name, and the phase's argument still
holds: a matcher that accepts a prefix is a second routing table with its own precedence rules.

## Problem

- How a file becomes reachable without widening the route table's pattern language.
- Whether a handler reading from disk belongs in a module that may name IO at all.
- What the validator is derived from, and what that costs in testability.
- Whether the 304 length decision survives contact with a component that genuinely knows the stored representation's size.
- What the answer is when the file is not there.

## Options Considered

**A — a wildcard route (`/assets/*`) in `Router`.** Refused here and named as this record's reopen trigger instead: it
turns the router's one answer («method plus raw path, to a handler») into an ordering question, because a request can
then match two routes and something has to decide which. That something is a precedence rule, which ADR-0019 §13 already
refused for a different reason and the phase brief refused for all of them.

**B — a `Handler` chain that falls through to static content.** Refused: a fall-through is a middleware chain (ADR-0033's
refusal, ADR-0018 §16's forbidden names), and it would make the 404 an artefact of ordering rather than of the table.

**C — serve whole directories with one handler and a containment check.** Refused on the pattern problem rather than the
security one: a directory handler still needs the wildcard route to be reachable, and it adds traversal to defend against
a path the application never had a way to supply.

**D — chosen: one file, one route, registered by the application.** `StaticContent.file(path)` is a `Handler`; an
application that knows its own asset list registers one route per file, in about two lines of `Files.list`. The router
stays the one-question component, the containment question disappears because no request supplies a path, and a site with
thirty files is a deployment that can enumerate them.

## Decision

### 1. A new capability module, `framework/barezen-content`, depending on `barezen-web` alone

`barezen-web` may name `java.lang` and `java.util` and nothing else — no `java.io`, no `java.nio.file` — and that rule is
the contract's own (ADR-0018 §12), so a component that reads from disk cannot live there. A capability module is how the
repository has handled every similar case: security and data each got a module with the one dependency their job needs.
Nothing in the framework depends on `barezen-content`; an application that serves no files does not carry it.

### 2. The public surface is one class and three statics

```java
public static Handler file(Path file);                      // type guessed from the extension
public static Handler file(Path file, String contentType);  // type stated
public static String contentTypeOf(String name);            // the table itself
```

`FileHandler` is a private nested class: a handler that reaches the disk is not something to subclass, and every knob it
might want (a type, a cache policy) is either an argument here or the application's own handler. The `file(Path)` and
`file(Path, String)` pair is not two ways to say one thing — one derives the type, the other overrides the derivation, and
deriving is the case that would otherwise need a second call at every site.

### 3. A route to a file that does not exist is refused at assembly

`StaticContent.file(...)` requires an existing regular file: not a directory (that would be a listing), and not a path
that may exist later (that is a 404 a client has to discover rather than a deployment that can be fixed at once). What
happens *after* assembly is different and expected: a file deleted later answers the contract's plain 404 — the words
`Not Found`, no path, no reason, no stack — because ADR-0023 §2's rule about not teaching a probe applies to a handler
exactly as it applies to an adapter.

### 4. The `ETag` is a hash of the bytes, and there is no `Last-Modified`

A strong tag, the first eight bytes of the content's SHA-256, computed from what the request is about to be answered with.
It changes exactly when the representation does — no restart, no clock, no filesystem that reports no mtime, and no two
machines disagreeing about a timestamp's granularity. `Last-Modified` is refused rather than deferred: it would import a
clock into the answer (ADR-0011's rule about time being an argument), and `If-Modified-Since` would add a second
validator whose rules overlap this one and answer it less precisely. The content's own type table is fixed for the same
reason `Files.probeContentType` is banned by rule: a host MIME database makes the wire format depend on the machine.

### 5. The conditional GET consumer arrived, and D1's answer is unchanged — measured, not re-asserted

`If-None-Match` matching the tag answers `304` with the tag and no body. The open question D1 left was the length, and this
is the first component in the repository that genuinely knows the stored representation's size — the bytes it would have
sent are in hand. Over a real socket, measured:

| Request | Status | `Content-Length` | Body |
| --- | --- | --- | --- |
| `GET /assets/app.css` | 200 | `14` | the file |
| `GET` with a matching `If-None-Match` | 304 | absent | empty |
| `HEAD /assets/app.css` | 200 | `14` | empty |

**The 304 stays length-free.** RFC 9110 §8.6.1 permits a `Content-Length` on a 304 where it would have been sent in the
200 — permits, not requires — and the reasons D1 gave still outweigh the new ability: `Response` refuses the framing
field to a handler by design (ADR-0027 §5), so stating it would mean a channel from this handler to the adapter that
carries "the size of the body I did not send", which is a new contract argument made for a field no client here needs. A
client that has the tag has the representation; the length adds nothing it can act on, and a wrong one is a cache
poisoning. The decision is therefore *re-measured and kept*, which is what D1's trigger asked for: not a re-assertion in
the absence of a consumer, and not a change because a consumer made the value cheap.

### 6. A served file is fully in memory, which is ADR-0030's decision rather than an oversight here

`Response` carries a `byte[]`, so a file is read whole. Right for a stylesheet, a favicon and a documentation page; wrong
for a video. The trigger stays the one ADR-0030 names (a consumer, an owner for a post-return writer, and a framing
matrix that survives it) and this phase does not invent a streaming body to dodge it, because that body would break the
`Content-Length` table above — including the HEAD row that a static file now depends on.

## Consequences

- The router is unchanged: no new pattern syntax, no precedence, no fall-through. If a deployment's asset set really is
  dynamic, that is the trigger for A, and A needs an ordering decision with it.
- `barezen-content` joins the BOM and the reactor. Its dependency direction is one arrow — to `barezen-web` — and a rule
  enforces it.
- Cache policy (`Cache-Control`, `max-age`, CDN behaviour) is not this module's to set. A handler could add the field
  today by building its own `Response`, so the deferral is about ownership, not capability.
- The site in 3.6 is this module's first real consumer, which is the honest order: the capability was written because a
  deployment needed it, and the deployment is in this repository.

## Security implications

- No path is taken from a request, so there is no traversal to defend: the file is named in the application's own
  assembly code, and the only reachable files are the ones it registered. That is a consequence of decision 4 and D, and
  it is stated so that a future wildcard proposal has to answer it.
- A 404 names no path and no reason (ADR-0023 §2); the file that went missing is not announced to the client.
- Types come from a fixed table with `application/octet-stream` as the fallback rather than a guess at text, so an
  unrecognised upload cannot be served as something a browser will execute — and the same table means two deployments
  never answer one file differently.
- SHA-256 is used as an identity of content, not as a security decision; nothing here is a MAC or a signature.
- No IO exception reaches the socket; the failure becomes a 404 and the detail stays with the application's own logging.

## Testing

- `StaticContentTest` — 22 tests. Serving (5): bytes, guessed type, tag shape; a stated type overruling the extension;
  the tag changing when the bytes do; identical bytes producing identical tags; a deleted file answering the plain 404.
  Revalidation (5): a matching tag answers 304 with the tag and no body; a weak client validator matches; a list matches
  any member and a stale list does not; `*` matches; an absent or blank validator is a plain GET. Types (2): all 21
  mapped extensions, and unknown/nameless/trailing-dot/hidden names falling to binary. Refusals (4): a directory, a
  missing path, a blank type, and nulls. Wire (2): the table above over a real socket, and the missing file's 404 over
  the same socket.
- `BarezenContentArchitectureTest` — 6 rules: the module depends on the contract only; it names no server, no `java.net`
  and no host MIME type; no static mutable state; no environment or property read; no thread-local; one public type.
- **Spikes**, each added, proven, deleted: a public probe class holding a static non-final field, calling
  `System.getProperty` and `java.net.URI` fired four rules (violations 2/1/1/1, 6 tests 4 failures, build failed); a
  second probe holding a `ThreadLocal` fired `no_thread_local` (3 violations, build failed). `this_module_depends_on_the_contract_only`
  could not be fired from inside this module because core, security, data, plugins and both adapters are not
  dependencies of it — javac enforces that half, as ADR-0018 argued for its own version.
- **Mutations**, each killed against the module's own run (28 tests), each file md5-restored: C1 tag derived from a
  constant rather than the content (1 failure — `theTagChangesWhenTheBytesDo`); C2 never honour `If-None-Match` (5);
  C3 echo the path in the 404 (2, one of them on the socket); C4 unknown extensions typed as text (1);
  C6 answer 304 without carrying the tag forward (2). C5 — reading the file once at construction instead of per request
  — is written as a field mutation the module does not have, and the same property is covered by C1's killer; it is
  recorded as not expressible rather than as killed.
- Full reactor after this phase: `mvn -o -B -ntp clean verify` → BUILD SUCCESS in 10:38 — **1203 tests, 0 failures, 1 skipped**
  (core 275, web 248, http-jdk 212, servlet 58, security 99, **content 28**, data 64, plugins 114, testkit 77, examples 28);
  all nine coverage gates met; `check-jdk-baseline.sh` and `check-class-major.sh` exit 0 at `checked=606 unique-majors=[61]`.
  This is the phase that added the content module and TLS, and the reactor grew from 10 to 11 modules.

## Amendment (2026-11-25): classpath resources

The jar-packaging trigger is now met by `StaticContent.resource(ClassLoader, name)` and its explicit-content-type
overload. The loader and resource name are assembly inputs; names must be relative and contain no empty, `.` or `..`
segments. Missing resources fail assembly, while read failures keep their cause in `IllegalStateException`. Classpath
bytes are read once and frozen because a jar resource is not a live deployment file; the existing ETag, conditional
GET, MIME table and one-route-per-resource rules are shared with `file(...)`. No classpath scanning, directory route,
request-supplied resource name or remote loader was added.

## Non-goals

Wildcard or prefix routes, directory listing, `Last-Modified`/`If-Modified-Since`, `Cache-Control` or any CDN policy,
range requests, compression, streaming a large file, classpath scanning, and a static handler that takes a path from
the request.

## Reopening trigger

- **A dynamic asset set** opens the wildcard-route decision (option A), and it arrives with a precedence question the
  route table currently refuses to ask.
- **A file too large to buffer** opens ADR-0030's streaming conditions, and its first test must be the framing table in
  decision 5 surviving the change.
- **A deployment behind a cache** opens `Cache-Control`; the shape is an argument to `file(...)`, not a module-wide
  default, so that one deployment's policy is not another's bug.
- **304 with a length** reopens only if a client is shown to need it — and then through the framing question ADR-0027 §5
  keeps out of handlers' hands, not by letting this module write a header the contract forbids.

## Related Decisions

ADR-0019 (the pattern language this stays inside), ADR-0018 §12 (why IO lives outside the contract), ADR-0029 D1 (the
decision this re-measured and kept), ADR-0023 §2 (the 404's shape), ADR-0030 (the buffered body and its conditions),
ADR-0027 §5 (framing fields stay the writer's), ADR-0016 (the frontend NON-GOAL that named a real consumer as its
trigger — 3.6 is that consumer).
