# ADR-0029: Response Framing Contract and the 304 Representation-Length Decision

## Status

accepted — 2026-10-01

Decides the final response-framing matrix every status-and-method combination must match; that a framing header is
refused on a `Response` whether one or two of them, and whether or not duplicates agree; and that a `304` through this
framework carries no `Content-Length` — closing ADR-0027 limitation 1 as an accepted decision rather than open debt.
Related: ADR-0018, ADR-0022, ADR-0027, ADR-0028.

## Context

ADR-0027 put two rules in `Response`'s constructor (a bodyless status is refused bytes; the two framing field names
are refused on any status) and one derivation in the adapter (a HEAD states its representation's length). Phase 2.10
was asked to stabilize that contract, which required answering two things ADR-0027 left open:

1. its limitation 1 — «a 304 cannot state the stored representation's length», recorded as design debt with «the fix
   … needs its own decision about where a length-without-a-body comes from»;
2. the matrix the directive's §E.1 states: every status, what body it carries, what `Content-Length` the wire shows,
   and whether `Transfer-Encoding` ever appears — asserted as one table rather than as nine tests a reader must
   assemble.

Both questions were investigated before deciding, per the phase's rule: standard text, JDK source, raw sockets, both
supported JDKs.

## Problem

1. What does HTTP actually permit on a 304, word for word? (§Investigation 1)
2. Can the shipped JDK put a handler-written length on a 304 without a warning or a desynchronised connection?
   (§Investigation 2)
3. Where would the *value* of a 304 representation length come from in this architecture, and who could check it?
   (§Options D3)
4. What is the one matrix every response shape must match, status × body × framing fields? (§Decision)
5. What happens to a duplicated framing header — same value twice, different values twice, any case? (§Decision 3)

## Investigation

**1. The standard.** RFC 9110 §8.6, verbatim: «A server MAY send a Content-Length header field in a 304 (Not Modified)
response to a conditional GET request (Section 3.4 of [RFC9112]); a server MUST NOT send Content-Length in such a
response unless its field value equals the decimal number of octets that would have been sent in the content of a 200
(OK) response to the same request. A server MUST NOT send a Content-Length header field in any response with a status
code of 1xx (Informational) or 204 (No Content).» Two consequences: the field on a 304 is optional (a MAY), and when
present it is not free — it is bound to the octet count a hypothetical 200 would have carried. RFC 9112 §6.1 likewise
forbids `Transfer-Encoding` on 1xx and 204, and RFC 9112 notes that for messages without content the length describes
«the size of the selected representation» — which is the concept ADR-0027 §3 separated from the message body.

**2. The JDK's capability, measured on the bare `com.sun.net.httpserver` with no framework code in the path, Temurin
17.0.20.1+1 and 21.0.12.1+1, outputs diffed identical (`/tmp/p210/probe/304-17.txt`, `304-21.txt`):** a handler that
sets `Content-Length: 1234` as a response field and calls `sendResponseHeaders(304, -1)` gets the field through to the
socket with zero body bytes, no server `WARNING`, and the keep-alive connection still parsing the next request. The
mechanism is in `ExchangeImpl.sendResponseHeaders`: the 1xx/204/304 branch forces `contentLen = -1` and sets
`noContentLengthHeader = (rCode != 304)`, and the 304 response never reaches the `rspHdrs.set("Content-length", …)`
line — so a handler-written field is the only length a 304 can carry, exactly as ADR-0027 §5 measured from the other
side. (Declaring a positive length instead only earns the `rCode = 304: forcing contentLen = -1` warning; the field
still passes through.) So the capability question answers itself: **the JDK can send it.** The design question is
what this framework would put in it.

**3. The current shipped behavior:** a 304 leaves with fields, no length, no body (ADR-0027 §8's table row), the same
on both JDKs, pinned by `ThreeOhFour.aThreeOhFourIsWrittenWithNoBodyAndNoLength` and re-asserted as one row of the
matrix test below.

**4. The duplicate shapes (§E.3), measured and refused:** `Headers.of("Content-Length","5").with("content-length","5")`
keeps two values under one canonicalized name, and two distinct spellings of a name; `Response`'s constructor refuses
either pair before anything is built, as do all case variants of the names — the check runs through the same
case-insensitive lookup `Headers.first` uses everywhere (ADR-0027 §5's refusal, re-pinned with its own test).

## Options Considered

**D1 — keep forbidding a 304 length.** The 304 carries fields, no body, no length — the status framed by the
connection, like 204. Cost: a cache reading a 304 cannot learn the stored representation's size through this framework.

**D2 — an independent representation-length concept** (a `Response`-level metadata object or type). Rejected: it is a
public expansion on a frozen pre-1.0 contract, and the phase's own rule (§B.5's nine questions, which apply to any
public addition, not only to a raw-method one) has no actual consumer to answer question 3 — no code in the framework,
the examples, or the phase's scope reads the value back. A concept with one writer and no reader is the shape §109
warns about.

**D3 — status-specific representation metadata: allow a length on 304 (and state the meaning per status).** Technically
feasible on the JDK (§Investigation 2), but the directive's own conditions are what sink it:

- *200 meaning*: the length of the body — already derived, exactly;
- *204 meaning*: none, forbidden by RFC 9110 §8.6, already refused;
- *304 meaning*: the octet count a 200 to the same request would have carried — **a value this architecture cannot
  see**. The handler that returns the 304 did not compute a 200's body (there may not have been one to compute: the
  point of a 304 is that the stored representation answers the condition), so the only possible source is a number the
  handler asserts about a response it is not sending;
- *HEAD meaning*: the representation's length, derived from bytes the handler actually returned — the asymmetric case
  that makes HEAD work and 304 not;
- *conflicting values*: a 304 with both a non-empty body and a stated length — refused today, and under D3 the
  body-part stays refused, so conflicts reduce to "the assertion contradicts a 200 nobody asked for", which no
  in-process check can resolve;
- *ownership*: the field would be handler-written and adapter-relayed — the exact §D.4-forbidden shape ("adapter
  blindly trusts user Content-Length") wearing a narrower name;
- *validation*: the RFC's condition (equal to the 200's octets) is unverifiable inside one response, so validation
  would mean requiring an ETag/Last-Modified pair and trusting the number anyway.

D3 could become honest if the framework ever grows conditional-request handling — an `If-None-Match` evaluation that
*has* the 200's body in hand and converts it to a 304 — because then the length has a source that is not an assertion.
That is the consumer whose absence D2 already noted; §Consequences records where the decision changes if it arrives.

**D4 — silently widen ADR-0027: let a 304 carry bytes that never go on the wire, purely to derive the length.**
Rejected before it was a real option: it is ADR-0027's refused silent-dropping shape re-imported through the framing
side, and the directive's forbidden list names the concept confusion it trades in.

## Decision

**1. D1 stands, as a decision rather than debt.** A 304 produced through this framework carries fields, no body, no
`Content-Length`, no `Transfer-Encoding` — on every JDK. ADR-0027 limitation 1 is reclassified: it is not a limitation
waiting for a fix but a chosen boundary with a named condition that would reopen it — a real conditional-GET consumer,
which would bring the value with it through a 200-to-304 conversion inside one handler call. Recorded as **DEFERRED
with named trigger** in the phase's debt taxonomy, not FIXED, because deferring a decision you re-opened honestly is
what keeps it auditable.

**2. The framing matrix is this table, and the implementation matches it cell for cell** (asserted as one test,
`FramingMatrix.everyMatrixRowArrivesAsItsRowDefines`, raw socket, both JDKs):

| status | body | `Content-Length` | `Transfer-Encoding` |
| --- | --- | --- | --- |
| 200 | empty | `0` | absent |
| 200 | non-empty | body length | absent |
| 204 | empty | absent | absent |
| 304 | empty | **absent** (D1) | absent |
| HEAD of 200 | suppressed | representation length | absent |
| 404 | body | body length | absent |
| 405 | body | body length | absent |
| 500 | body | body length | absent |

and, by ADR-0028's decision, the HEAD row applies to the exact method token only; every non-exact spelling is one of
the ordinary rows with its bytes and its true length.

**3. A framing header is refused on a `Response` in every duplicate and case shape.** Same value twice
(`Content-Length: 5` + `content-length: 5`), different values twice, and the two names together — one answer for all
of them: `IllegalArgumentException` from the constructor. §E.3's "reject / sanitize / ignore, no ambiguity" resolves
to **reject**, because sanitize and ignore both require the contract to parse framing values, which §2's ownership
rule forbids it to touch, and because the refusal is about the *name* (ADR-0027 §5) rather than any particular value.

**4. Handler ownership of framing: still nothing, adapter ownership: total.** `Content-Length` is written by
`JdkExchangeBridge.send` or not at all; `Transfer-Encoding` is never written — this adapter answers a length it can
count, and declares `-1` (which the JDK frames as `Content-Length: 0`) or nothing (bodyless statuses), so no response
this module writes is ever chunked. §E.2's case-insensitivity requirement is enforced through `Headers`' own
case-insensitive lookup, covering `Content-Length`, `content-length`, `CONTENT-LENGTH`, `cOnTeNt-LeNgTh` and every
other spelling, with refusal tests per spelling group.

## Consequences

- The §E.1 matrix is no longer scattered across nested test classes: one test asserts the whole table against one
  running server, so a future framing change that fixes one row and breaks another cannot pass quietly.
- The 304 question has a written answer with a named reopening condition, so the debt taxonomy entry is DEFERRED-BY-
  DECISION rather than an unexplained silence; ADR-0027's limitations list gains a pointer and no other change.
- D2/D3 stay off the table **on evidence, not taste**: if a conditional-GET phase arrives, its ADR writes the length
  source and validation together, and this record is the one it amends.
- Duplicate framing headers cannot reach a socket from an application: the smallest observable set is one name, and
  that name cannot be set by a handler. The adapter's own writes use `set` for the HEAD length, so even it produces at
  most one line.
- Nothing in this decision changes any wire output that ADR-0027/28 already stabilized; it pins what they produce into
  one asserted table and closes the one open semantic cell (304 × Content-Length) by declining to fill it.

## Compatibility Impact

- Public API: none. No type, method, or field is added or removed; `Response`'s constructor already threw for the
  refused shapes — this record adds one *test* per duplicate spelling pair, not one behavior.
- Wire behavior: unchanged on every status and method.
- Contract text: ADR-0027 §9's "losses" list is now partially decided rather than open; a reader of ADR-0027 should
  follow this record for the 304 row, which its index line will say.

## Tests

- `JdkResponseBodySemanticsTest$FramingMatrix.everyMatrixRowArrivesAsItsRowDefines` — the §E.1 table as eight rows
  against one server on one port, comparing status / exact `Content-Length` value / body text, and asserting
  `Transfer-Encoding` is absent in every row (§E.4). Raw socket via `RawHttp`.
- `ResponseTest$FramingFields.refusesADuplicatedLengthWhetherTheTwoValuesAgreeOrNot` — §E.3: `5`+`5` and `5`+`6`
  under both spellings, plus a duplicated `Transfer-Encoding` pair, all refused (§Decision 3).
- Existing pins re-run, unchanged and still green, as the matrix's per-cell detail: `EmptyBodies` (the first two rows),
  `TwoOhFour`, `ThreeOhFour` (row four's absent length), `HeadResponses` (row five, all spellings after ADR-0028),
  `AdapterAnswers` (rows six-eight, as counts as well as values), and
  `NoServerSideCorrection.noFramingShapeMakesTheServerLogAWarning` (no cell asks the JDK to undo anything).
- Mutation evidence (Stage I): M6 (304 emits body), M7 (handler `Content-Length` accepted) and M8 (handler
  `Transfer-Encoding` accepted) fire on these tests and the ones they name.

## Related Decisions

- **Closes ADR-0027 limitation 1** by deciding it (D1), superseding its «design debt … needs its own decision» note;
  ADR-0027 §5's mechanism analysis is the evidence this record's §Investigation 2 stands on.
- **Depends on ADR-0028** for the HEAD row's exact-token scope; the matrix above quotes that boundary rather than
  restating it.
- **Continues ADR-0022 §6** (`sendResponseHeaders` semantics as measured) and **ADR-0023** (the adapter's own answers
  keep their bodies and single lengths).
- **Constrains the body-abstraction record (ADR-0030)**: any future body type must keep this matrix true — the
  representation-length derivation in the HEAD row is a framing rule, not a body rule, and a `BodyWriter`-shaped
  design that could not state a length without producing bytes would break row five rather than extend it.
