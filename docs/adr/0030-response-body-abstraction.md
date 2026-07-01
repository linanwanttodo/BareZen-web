# ADR-0030: Response Body Abstraction — `byte[]` Stays, and What Would Make It Change

## Status

accepted (decision: no change now) — 2026-10-01

Answers the phase's ten Body-Evolution questions (§Problem) and places the streaming question in a named future
phase with named triggers, instead of leaving it as an open design question. `Response(status, Headers, byte[])` is
kept unchanged; nothing is added to the public surface by this record.
Related: ADR-0018, ADR-0027, ADR-0029, ADR-0031.

## Context

`Response` has carried its body as a `byte[]` since ADR-0018 §7 accepted the buffering cost, and ADR-0027 §3 kept it
under a name ("complete value, or nothing") while refusing to widen it. Phase 2.10 was asked to stabilize HTTP
semantics, and a stabilization phase is the point at which a body abstraction either gets a consumer or gets a
decision not to grow one. This record is the second answer, written out because the phase's rule forbids leaving the
question unknown (the same rule that made the request-side limit a decision — ADR-0031 — applies here in the opposite
direction: no abstraction without a consumer).

The directive also names what is forbidden as a motivation: reactive types, a `PublisherBody`, an `AsyncBody`, a
`BodyWriter`, Netty. Introducing one «为了先进» is the exact failure mode the phase's maxim exists to catch.

## Problem

The ten questions, in the directive's order:

1. Does `byte[]` continue?
2. Is streaming actually needed?
3. What is the real consumer?
4. Does the JDK `HttpServer` support it naturally?
5. Is backpressure needed?
6. Is an async handler needed?
7. Is cancellation needed?
8. Does executor ownership change?
9. Is a breaking API change needed?
10. Should this enter Phase 2.11 or 3.x?

## Investigation

**1-3. Who asks for it.** No type in `com.barezen.web` writes a response body incrementally today, no adapter needs
one, and no named application consumer exists in this repository. The two shapes streaming would serve are large
generated responses (a file download, an export) and slow-producer responses (a stream of events); neither has a
handler to write against, because the framework has no file, template, or serialization layer — a handler that could
produce such a body in the framework as shipped produces a `byte[]` in one step. The pressure on the other side of
the body question — the unbounded *request* read — has just been resolved by ADR-0031, which removes the
memory-exhaustion argument as a reason to rush an abstraction: what remains is a cost (a large response is buffered)
and costs do not decide APIs, consumers do.

**4. The JDK's side.** `com.sun.net.httpserver` is stream-native on responses: `sendResponseHeaders(code, 0)` means
chunked and the handler writes to a live `OutputStream` (measured and recorded in ADR-0022 §6, which is exactly why
this framework declares `-1`/`body.length` instead of `0` — it *chooses* the fixed-length framing). So the server
would not be the constraint; an adapter could reach the live stream today by bypassing `Response`, which is the
sign the abstraction is missing a *contract* answer, not a *capability*.

**5. Backpressure** is what a live stream inherits from socket flow control — a slow client slows the handler's
writes instead of filling the heap. In the `byte[]` design backpressure is moot by construction: everything is in
memory before a byte goes out, and ADR-0031's §Decision 5 analysis (a stalled client stalls the drain, not the
buffer) already states that price on the request side. Neither answer needs an abstraction; each needs only honesty
about which one you bought.

**6-8. Async handler, cancellation, executor ownership** arrive as one package, and the package is the reason this
record says no rather than «not yet»: a `BodyWriter` runs after `Handler.handle` has returned, so *something* must
own the continuation — the adapter's executor (ADR-0024 §4's ownership map), the client's connection lifetime
(cancellation: who interrupts a writer whose socket died?), and the thread that ADR-0011 deliberately made
explicit. ADR-0018 §7 refused the stream body on ownership grounds («not one of those answers is independent of a
server»); for responses the same refusal applies with more force, because the handler *produces* rather than merely
releases. No answer here is available without re-opening ADR-0011, ADR-0018 and ADR-0024 together — which no
consumer in this repository asks for.

**9. The API cost is breaking.** `Response`'s `body()` is `byte[]` and its constructor takes one; ADR-0027 kept both
shapes deliberately. Every streaming alternative changes one of: the constructor, the accessor, or `Handler`'s
signature. In 0.x a breaking change is allowed when evidence justifies it — the B.5 gate — and evidence is what
§1–§3 shows absent.

**10. ADR-0029's constraint on any future design.** The framing matrix must stay true after a body abstraction:
row five (HEAD) states the representation's length without sending its bytes. A live-writer design either knows the
length before writing (file-backed: stat it; generated: you are buffering again) or cannot state it (chunked, no
length) — which silently deletes the HEAD row this framework made correct twice (ADR-0022 §7 → ADR-0027 §6). Any
proposal that cannot answer that row is not an extension of the matrix but a break of it.

## Options Considered

**O1 — add a streaming body type now.** Rejected: no consumer (§Investigation 1–3), breaking (§9), the ownership
package unowned (§6–8), and a matrix constraint most shapes fail (§10). Also the phase's forbidden-motivation list.
**O2 — keep `byte[]`, call streaming out of scope forever.** Rejected: the cost is real (a 10 MB response is
buffered three times — ADR-0027 §Consequences 6's arithmetic still stands) and a framework that bans its own future
turns a decision into a religion.
**O3 — keep `byte[]`, document the limitation, name the trigger and the phase.** Chosen. This is O2 minus the
religion: the abstraction has a defined entry condition, so the next phase can argue from evidence rather than from
taste.

## Decision

1. **`Response`'s `byte[]` continues unchanged through Phase 2.10.** No new type, no constructor, no accessor, no
   marker interface.
2. **The limitation stays written where it is already written** (ADR-0018 §7's cost, ADR-0022 §7's shape,
   ADR-0027 §Consequences 6's copy count) and gains this record as its decision statement.
3. **Reopening triggers, named.** A streaming-response design belongs to a phase that has, *before* writing API:
   (a) a real consumer — at least one named handler shape that cannot produce its `byte[]` in one step within the
   default memory posture (the file-download/export case is the expected first claimant, and it must arrive with a
   use site, not a benchmark); (b) an answer to the ownership package (§6–8) that does not change `ThreadProvider`
   or `LifecycleParticipant` semantics without their own ADRs; (c) a framing design that keeps every row of
   ADR-0029's matrix true, including HEAD's stated length. Failing any one of the three, the question returns here.
4. **Placement:** not Phase 2.11 (whose subject is stabilization's tail — a body abstraction is the opposite of a
   tail). A consumer appearing in the 3.x line makes the proposal on-time, not early; nothing in 2.10–2.11 should
   pre-shape `Response` for it. This record deliberately does not sketch the API.
5. **The forbidden list is reaffirmed as a scope rule for that future phase**, not as a technical verdict: reactive
   types, `PublisherBody`, `AsyncBody`, `BodyWriter`, Netty do not enter by default — the future phase inherits the
   same B.5 evidence bar as if they had never been mentioned.

## Consequences

- The body question stops being an open design question and becomes a conditional one: every future argument for
  streaming is now an argument about §Decision 3's three items, with receipts.
- The buffering cost of large generated responses stays accepted, with a number on it (three copies: contract-in,
  contract-out, adapter-write) and no new mitigation — the request-side counterpart is mitigated (ADR-0031), the
  response side intentionally is not, because a response is bytes the server already chose to produce.
- Nothing in the code changes; this record's only artifacts are itself and its index row. A decision to write no
  code is recorded at the same standard as one that writes some, per the phase's «先调查，再设计，再实现，再验证».

## Compatibility Impact

None. No public type, constructor, method, configuration key, dependency, or wire shape is added, changed, or
deprecated. `Response`'s constructor rules (ADR-0027 §3–§5, ADR-0029 §Decision 1) are untouched. Class-file major
and module structure unaffected.

## Tests

This record decides against code, so it adds no behavior tests — but the decision is pinned where it is observable:

- `JdkResponseBodySemanticsTest` keeps the shapes the `byte[]` contract makes true (the framing matrix,
  ADR-0029 §Decision 1; HEAD's derived length; the no-body status refusals), which is the surface a streaming type
  would have to keep passing before it could exist.
- `Response`'s own suite keeps `refusesEveryStatusThatCarriesNoBodyWhenBytesWereGiven` and the copy-on-construct/
  copy-on-read immutability tests: §Decision 1 means those exact tests continue to define the contract's body model.
- ADR-0029's matrix test is the machine-checkable form of §Decision 3(c): a future body design that cannot pass it
  is rejected by that test, not by this file's prose.

## Related Decisions

- **Answers ADR-0018 §7's forward note** («a streaming contract is written for» the too-large-body case): on the
  request side the answer became a limit (ADR-0031), not a stream; on the response side this record states when the
  stream gets written.
- **Constrained by ADR-0029**, which reserved this record's name and set the matrix rule §Decision 3(c) enforces.
- **Depends on ADR-0011/ADR-0024** for why §6–8's ownership package is expensive: thread ownership is explicit and
  mapped, and a post-return writer breaks that map.
- **Contrasts with ADR-0031** in method, not outcome: the request-side unknown became a default because the risk was
  present and the mitigation cheap; this unknown stays a non-feature because the need is absent and the mitigation
  expensive. Both stopped being «unknown» in writing.
