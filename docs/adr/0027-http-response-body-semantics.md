# ADR-0027: HTTP Response Body Semantics — what `Response(status, Headers, byte[])` may be handed, and who writes `Content-Length`

Status: accepted
Date: 2026-09-30
Decides: that the existing three-field `Response` is sufficient and gains no type, method or mode flag; that a status which
HTTP says carries no message body is refused one at construction rather than dropped at the socket; that
`Content-Length` and `Transfer-Encoding` are not response fields an application may set; that a `HEAD` response states the
representation's length while sending no bytes; what `sendResponseHeaders(status, len)` and `sendResponseHeaders(status,
-1)` each do on the shipped JDK, measured; and the two losses this decision accepts as limitations rather than works
around
Related: ADR-0018, ADR-0019, ADR-0022, ADR-0023, ADR-0026

## Context

ADR-0018 §3 gave `Response` three fields — a status, header fields, a body — and ADR-0018 §7 accepted the cost of holding
that body in memory. ADR-0022 §6 then decided the framing rules for writing one: `Content-Length` is derived, an empty
body is declared as zero rather than left to chunked, and the body is written once. That was the first working version, and
it left one limitation standing: ADR-0022 §7 recorded that «a HEAD response carries fields and no body and no
`Content-Length`, so a HEAD client cannot discover a representation's size through this adapter».

Phase 2.9.6 opened the shape itself. The question was not "add a body abstraction" but whether one was needed:
`Response(status, Headers, byte[])` has to express a normal response, an empty response, a `HEAD`, a `204` and a `304`, and
those five have different relationships to the bytes that follow the header block. The pre-audit state treated them as one
concept — "no body" — and the consequences were measurable: a handler could hand a `204` a body, which the JDK discarded
with a `WARNING` in the server's log and no word to the client; a handler could set `Content-Length` itself, which on this
server produced either a duplicated field line or a response a real Java client refused outright; and `HEAD` produced a
response with no length, which is not what `HEAD` means.

The directive's constraint was that the audit must not become an abstraction: keep `Response(status, Headers, byte[])`
unless a new public type is proved necessary, and never let an adapter silently swallow bytes a developer explicitly
wrote.

## Problem

1. Is the existing three-field shape sufficient for the five cases, or does something need a type? (§1)
2. What do "no bytes", "no body allowed", and "no body sent but a length known" each mean, and where are they the same
   thing and where not? (§2, §3)
3. `204` with a body: create-allowed, adapter-drops, adapter-refuses, or contract-refuses? (§4)
4. `304` likewise, and what does its answer cost? (§4, §9)
5. May an application write `Content-Length` or `Transfer-Encoding` on a response? (§5)
6. `HEAD`: which of the nine measured questions does each answer move? (§6)
7. What does a `1xx` status actually carry on this server? (§7)
8. What do `sendResponseHeaders(status, len)` and `(status, -1)` do, byte for byte, on the shipped adapter and on the bare
   JDK? (§8)
9. What does this decision lose, and where is the loss recorded? (§9)

## Decision

**`Response(status, Headers, byte[])` is kept unchanged in shape. Two rules are added inside its constructor, and one
behaviour is added to the adapter's write. No new public type, method or field exists after this phase.**

- `1xx`, `204`, `205` and `304` responses are refused a non-empty body at construction (`IllegalArgumentException`), because the
  alternative — writing it and having the server discard it — is a silent disagreement between what a handler returned and
  what a client received.
- `Content-Length` and `Transfer-Encoding` are refused as response fields on any status, because they describe the write
  rather than the answer, and only the component doing the writing can state them truly.
- A `HEAD` response carries the fields a `GET` would have produced **and** the representation's length, with no bytes. The
  adapter derives that length from the body it was handed and writes it into the response fields, declaring `-1` to the
  server so the server neither computes a second one nor warns.

### 1. The shape is sufficient, so the directive's §13 four-part proof was never triggered

The directive allows a new public type only with an ADR proving four things about it. None was needed, and the reason is
worth stating precisely, because "we considered it and did not need it" is the claim that usually hides an unexamined
design.

Every one of the five cases the phase listed is already expressible as a triple:

| case | as a `Response` | on the wire |
| --- | --- | --- |
| normal | `(200, fields, "hello")` | `Content-length: 5` + 5 bytes |
| empty | `(200, fields, new byte[0])` or `(200, fields, null)` | `Content-length: 0` + 0 bytes |
| `204` | `(204, fields, null)` | no length, no bytes |
| `205` | `(205, fields, null)` | `Content-length: 0` + 0 bytes on the shipped JDK adapter |
| `304` | `(304, fields, null)` | no length, no bytes |
| `HEAD` | the same triple the `GET` would return | length of that body, 0 bytes |

What is *not* expressible is a body that is not in memory, and that gap was priced and accepted in ADR-0018 §7 — this
phase neither reopened it nor papered over it. `Response` therefore gained:

- two private static helpers (`checkBodyAllowed`, `checkCarriesNoFramingField`), one private predicate (`carriesNoBody`)
  and one private constant array (`FRAMING_FIELDS`);
- nothing public: the constructor's declared exceptions grew a clause each, and `ResponseTest$Surface` still asserts the
  type exposes exactly `status()`, `headers()` and `body()`.

The forbidden vocabulary was not reintroduced either. There is no `BodyMode`, `ResponseBody`, `BodyPresence`,
`EmptyBody`, `NoBody`, `Representation`, `StreamingBody` or `Optional<byte[]>`, no `noBody()` factory, and no
`Handler`-signature change. `byte[0]` and `null` remain one fact to this contract, which is §3's first distinction and the
reason a `noBody()` convenience would have been a second way to say something already said.

### 2. Where the enforcing lives, and why not in the adapter

The four candidate places for the `204` decision were create-allowed / adapter-drops / adapter-refuses /
contract-refuses, and they differ in who learns about the mistake:

- **create-allowed** keeps the API permissive and moves the failure to the socket, where it is invisible.
- **adapter-drops** is the forbidden «静默吞掉»: the handler's bytes disappear in a component the developer never called,
  on a status where HTTP says they must. Measured as the JDK's own behaviour (§8), adopted here as the thing to avoid.
- **adapter-refuses** puts a contract rule in the module that knows HTTP — which is where wire knowledge belongs
  (ADR-0021 §1) — but the refusal lands after the handler returned, so the only honest form is a `500`, blaming the
  application's bug on the client's connection. And a second adapter would have to reimplement it.
- **contract-refuses** lands the exception on the line the developer wrote, with the status and the byte count in the
  message, before anything is committed and before a socket exists.

`contract-refuses` was chosen for both rules, with one deliberate duplication: the adapter keeps its own
`mayCarryABody(status)` predicate rather than asking `Response` for the set. The two lists are the same bodyless status set and
could be shared, but sharing them would mean either a contract type that answers wire questions or an adapter that reaches
into a contract's internals. They are pinned to each other by tests on both sides instead —
`refusesEveryStatusThatCarriesNoBodyWhenBytesWereGiven` in the contract, `aTwoOhFourIsWrittenWithNoBodyAndNoLength` and
`aHeadOfATwoOhFourStatesNoLength` here — and probes D, E and I in §Testing evidence each break one side and fail the
matching tests.

### 3. Three concepts, kept apart

Collapsing these was the bug the phase was written against, and a test that asserted only "no bytes arrived" passes for
all three and distinguishes none:

1. **`body == byte[0]`** — a body of no bytes. A `200` may carry one and does: it reaches the client as
   `Content-length: 0` and nothing else. `null` and `byte[0]` are the same fact to the contract
   (`keepsAnEmptyBodyAndNoBodyAsTheSameFactToTheContract`), which is the honest direction of that statement: the contract
   cannot see which the handler wrote, and the wire shows neither.
2. **a status that forbids a message body** — `1xx`, `204`, `205`, `304`. Here a `byte[0]` is the *correct* answer and a non-empty
   one is a contradiction, so the rule is about the pair, not about the array.
3. **a `HEAD` response's representation length** — no bytes on the wire and a real length. "No body" and "no length" are
   different statements, and `HEAD` is where the first is true and the second is false.

`200` + empty and `204` + empty both arrive with zero bytes and mean different things: the first says "this resource's
current representation is empty", the second says "there is no representation and that is the answer". The distinction is
carried entirely by the status, which is why `byte[0]` needs no wrapper and why the adapter's `declared` value is a
function of both fields rather than of either alone.

### 4. Bodyless statuses: refused at construction

`carriesNoBody(status)` is `100..199 || 204 || 205 || 304` and a
non-empty body alongside one is an `IllegalArgumentException`. An empty body stays legal for all four.

The evidence for refusing rather than dropping is the measured JDK behaviour, not a preference. With a bare
`sendResponseHeaders(204, 5)` (the shape a handler-written body produces when the adapter passes the length through), the
server logs:

```
WARNING: sendResponseHeaders: rCode = 204: forcing contentLen = -1
```

forces the length to `-1`, writes no `Content-length` at all, and discards the bytes. `ExchangeImpl.sendResponseHeaders`
lines 220-233 are the branch, and the same code is on both supported JDKs. The developer sees a `204` and a green
pipeline; nobody is told the handler's response was thrown away. A `304` is the same shape under a different status
(`refuses304BecauseItsBodyWouldNeverArrive`), and the interim codes are refused too, even though a `1xx` answer through
this adapter is not a working `100 Continue` flow — §7 records what they do send.

There is one asymmetry worth naming: `Response` refuses bytes on these statuses, so it never has to decide what to do with
them, and `refuses204RatherThanLetTheServerDropTheBodySilently` states the reason in the test name rather than in a
comment.

### 5. `Content-Length` and `Transfer-Encoding` are not a handler's fields

`FRAMING_FIELDS` is refused on any status and in any case (`refusesBothNamesInAnyCaseAndOnAnyStatus`), including an empty
value (`refusesAnEmptyValueForThoseNamesToo`), with a message naming the status, the field and the value. `A Request` may
still carry them: refusing a field the client sent would be editing what the client asked for, which ADR-0018 §2 forbids.

What a handler could otherwise produce was measured on the **bare** JDK server with no BareZen code in the path, on both
JDKs, byte-identical output (the last row is the same probe without a handler-written field, included because it is §4's
mechanism seen from the framing side):

| handler writes | on the wire | a real client |
| --- | --- | --- |
| `Content-Length: 5`, then `sendResponseHeaders(204, -1)` | `Content-length: 5` survives, 0 bytes follow | `java.net.http.HttpClient`: `IOException: unexpected content length header with 204 response` |
| `Content-Length: 9` on a `304` (`sendResponseHeaders(304, -1)`) | one `Content-length: 9` line, no body | status 304, 0 bytes — a 304 that claims a length it cannot fill |
| `Content-Length: 9` on a `HEAD`, declared `-1` | `Content-length: 9` passes through untouched | — |
| `Content-Length: 9` on a `200` **and** declares 5 | the server's `set()` **replaces** it: exactly one line, `5`, with 5 bytes | status 200, 5 bytes |
| `Transfer-Encoding: chunked` on a `200` declared 5 | **both** `Transfer-encoding: chunked` and `Content-length: 5`, body identity-encoded | status 200, 5 bytes |
| nothing, and declares `sendResponseHeaders(204, 5)` | no `Content-length` line at all, no body | status 204, 0 bytes — plus the server `WARNING` quoted in §4 |

The first row is a client that fails the response; the second and third are the duplicate-or-contradictory-length shape;
the fifth is request-smuggling-by-field-disagreement waiting for a proxy downstream; the sixth is §4's silent drop, shown
here to make the point that a *declared* length and a *field* length are two different leaks. None of them is visible to the handler
that caused it, which is why the fields are refused rather than discouraged: a developer adding a length "because a
response should have one" cannot know which status-and-method combination is the one that breaks.

Note the mechanism that makes rows 1-3 and row 4 differ: `sendResponseHeaders` calls
`rspHdrs.set("Content-length", …)` only on the non-`HEAD`, non-`304`, non-`1xx`/`204` branch (`ExchangeImpl.java:261-263`),
and `set` replaces while the bridge's own copy uses `add` per value. So a handler-written length survives exactly in the
cases where the server writes none of its own — precisely the cases where a stale number is most likely to be wrong. The
same `set` is what rewrites this adapter's `HEAD` length when the method token is not the exact uppercase one, which
limitation 2 records with its measurement.

### 6. `HEAD`: fields plus the representation's length, never bytes

The nine questions the phase asked, each answered with a measurement rather than a rule:

| # | question | answer on the shipped adapter, both JDKs |
| --- | --- | --- |
| 1 | same route as the `GET`? | **No.** `Router` matches on the method the client sent, so `HEAD /doc` needs its own registration or the answer is `405` (ADR-0019 §2, `aRegisteredHeadIsAnsweredWithoutABody` unchanged since ADR-0022) |
| 2 | does a handler execute? | **Yes, its own** — `aHeadRequestRunsItsOwnHandlerAndStatesTheLengthWithoutSendingIt` counts one `GET` call and one `HEAD` call for two requests, each on its route |
| 3 | does the `GET` body arrive normally? | **Yes** — same test asserts `hello` for the `GET` and nothing for the `HEAD` |
| 4 | does the `HEAD` socket carry absolutely no body? | **Yes** — `RawHttp` reads raw bytes, not a parsed body: 0 for a `HEAD` of 200/404/405/500/204/304 alike |
| 5 | is the status the same as the `GET`'s? | **Yes for the same answer**: a `HEAD` of an unregistered path is 404, of a wrong method 405, of a failing handler 500 — and `aHeadOfA404AndAHeadOfA500BothStateTheirLengthAndSendNothing` pins both |
| 6 | how are header fields handled? | copied one line per value exactly as for a `GET` (ADR-0022 §5), plus the derived `Content-Length`; `Allow` is present on a `HEAD` 405 (`aHeadOfA405CarriesAllowAndTheLengthOfTheBodyItDoesNotSend`) |
| 7 | should `Content-Length` exist? | **Yes, and now it does** — the representation's length, closed here rather than left as ADR-0022 §7's limitation |
| 8 | does the JDK handle `HEAD` automatically? | **Partly**: it suppresses the bytes (`ExchangeImpl.java:235-246`), and it *discards* a declared length with `WARNING: being invoked with a content length for a HEAD request` — which is why the length is written into the fields and `-1` declared instead |
| 9 | can BareZen express the representation length? | **Yes, through the existing triple** — the handler returns the body it would have returned for the `GET`, and the adapter decides both consequences |

The write (`JdkExchangeBridge.send`):

```java
int declared = (writesBody && statusCarriesABody && body.length > 0) ? body.length : -1;
…copy the handler's fields with add(…)…
if (!writesBody && statusCarriesABody) {
  outgoing.set("Content-Length", Integer.toString(body.length));   // HEAD's representation length
}
exchange.sendResponseHeaders(response.status(), declared);
if (!writesBody || body.length == 0) { return; }                    // HEAD writes no bytes
```

`writesBody` is the dispatcher's `!request.method().equals(HttpMethod.HEAD)`, unchanged since ADR-0022 §7. `HEAD` of a
status that carries no body writes no length either, because the contract already refused bytes and a made-up number would
be worse than silence — `aHeadOfATwoOhFourStatesNoLength`, `aHeadOfAThreeOhFourStatesNeitherALengthNorABody`.

Measured result (raw socket, JDK 17 and JDK 21, byte-identical modulo `Date`, zero server `WARNING`s — the last of those is
`noFramingShapeMakesTheServerLogAWarning`, which installs a JUL handler on the `com.sun.net.httpserver` logger and asserts
the list stays empty across twelve shapes):

```
HEAD /hello -> HTTP/1.1 200 OK, Content-type: text/plain, Content-length: 5, raw body bytes = 0
HEAD /empty -> HTTP/1.1 200 OK, Content-type: text/plain, Content-length: 0, raw body bytes = 0
```

### 7. `1xx`: refused bytes, and a status line that is the JDK's

A `1xx` response through this adapter carries fields and no length and no bytes (`anInterimStatusCarriesNoLengthAndNoBytes`
— `103` with a `Link` field arrives with the field, `Content-Length` absent, body empty), and a code the JDK's table has no
text for leaves an empty reason phrase: `aCodeTheJdkDoesNotNameArrivesWithAnEmptyReasonPhrase` asserts the exact line
`HTTP/1.1 103 `. Bytes are refused (§4). No `100 Continue` handshake exists and nothing here pretends otherwise: this
section is a measurement of what the shape currently does, recorded so that a later phase adding `Expect: 100-continue`
support reads a number rather than an assumption.

### 8. `sendResponseHeaders(status, len)` versus `(status, -1)` — the measured table

Both were run through **the shipped adapter** and read on a raw socket, on Temurin 17.0.20.1+1 and 21.0.12.1+1; the two
outputs differ only in their `Date` values (verified by `diff`, seven lines, all `Date`).

| case | status line | length field | TE | raw body bytes |
| --- | --- | --- | --- | --- |
| `GET /hello` → `200`, body `hello` | `HTTP/1.1 200 OK` | `Content-length: 5` | — | 5 |
| `GET /empty` → `200`, `byte[0]` | `HTTP/1.1 200 OK` | `Content-length: 0` | — (not chunked) | 0 |
| `GET /n204` → `204`, no body | `HTTP/1.1 204 No Content` | **absent** | — | 0 |
| `GET /n304` → `304`, `ETag: "7"` | `HTTP/1.1 304 Not Modified` | **absent** | — | 0 |
| `HEAD /hello` → `200`, body `hello` | `HTTP/1.1 200 OK` | `Content-length: 5` | — | **0** |
| `GET /boom` → `500` | `HTTP/1.1 500 Internal Server Error` | `Content-length: 21` | — | 21 |
| `GET /none` → `404` | `HTTP/1.1 404 Not Found` | `Content-length: 9` | — | 9 |

`Content-length: 0` rather than chunked for an empty `200` is the `declared = -1` branch (`len == -1` →
`Content-length: 0`, no body, `ExchangeImpl.java:257-260`); passing `0` would have taken the chunked branch
(`:248-255`) and put a `Transfer-encoding` on a response with nothing to frame, which is what ADR-0022 §6 pinned and probe G
still proves.

Connection behaviour was checked on one keep-alive connection with a seven-response mixed chain — `GET 204`, `GET 304`,
`GET 200`, `GET empty`, `HEAD 200-with-5`, `HEAD empty`, `HEAD 404`, then a final `GET` — each frame boundary read by hand:

```
keep-alive GET  /n204  -> 204 No Content  | Date, X-reason | body bytes=0
keep-alive GET  /n304  -> 304 Not Modified| Date, Etag      | body bytes=0
keep-alive GET  /hello -> 200 OK          | Date, Content-type, Content-length: 5 | body bytes=5
keep-alive GET  /empty -> 200 OK          | Date, Content-type, Content-length: 0 | body bytes=0
keep-alive HEAD /hello -> 200 OK          | Date, Content-type, Content-length: 5 | body bytes=0
keep-alive HEAD /empty -> 200 OK          | Date, Content-type, Content-length: 0 | body bytes=0
keep-alive HEAD /none  -> 404 Not Found   | Date, Content-type, Content-length: 9 | body bytes=0
keep-alive final       -> 200 OK          | Date, Content-type, Content-length: 5 | body bytes=5
```

The chain framing correctly because the two no-length statuses (`204`, `304`) are framed by the connection rather than by a
counted body — HTTP's own rule — and a client that expects one needs no help from us. `aRealClientAcceptsATwoOhFourAndKeepsTheConnection`
and `aRealClientReadsAEmptyThreeOhFourAndKeepsTheConnection` assert the same sequence through `java.net.http.HttpClient`
with HTTP/1.1 forced, which is the client that failed the handler-written length in §5.

### 9. What this costs

Two losses, both accepted rather than worked around, and neither described as a feature. See **Limitations** below for the
full list; the two that come from this decision specifically are that a `304` can no longer state the stored
representation's length, and that a lowercase `head` method token is not a `HEAD` to the JDK.

## Consequences

- **`Response`'s constructor now throws for two shapes that used to "work".** An application that set `Content-Length` on
  a response, or handed bytes with a `204`, gets `IllegalArgumentException` at the line that did it. This is a deliberate
  narrowing of a pre-1.0 contract (`0.1.0-SNAPSHOT`), with no compatibility layer: §25's own list of forbidden shapes
  list includes legacy compat, and a shim here would mean keeping the silent-drop path alive.
- **The adapter owns framing, completely.** `Content-Length` is written by `JdkExchangeBridge.send` or not at all, and
  `JdkRequestDispatcher`'s class javadoc says the one field it writes is `Allow` — because that value comes from the route
  table rather than from the bytes.
- **`HEAD` is now useful.** A client can discover a representation's size through this adapter, which ADR-0022 §7 said it
  could not. A `HEAD` route is still required explicitly, and `HEAD` of an error still states that error body's length — a
  property some clients will not expect and which is pinned by tests.
- **Three concepts have three names in the code and three assertions in the tests.** A later reader can tell "empty body",
  "status forbids a body" and "HEAD sends no bytes but has a length" apart because each has a test that fails if the
  others are collapsed into it.
- **The JDK is never asked to correct us.** Every shape the adapter produces passes through `sendResponseHeaders` in the
  branch the JDK considers right, so the server's `WARNING` path is unreachable from this module — asserted, not assumed,
  by `noFramingShapeMakesTheServerLogAWarning`, which walks twelve shapes (204, 304, 103, empty, full, 404, and four HEAD
  variants) with a JUL handler attached and requires the captured list to stay empty. The warnings that path can still be
  reached through are the ones in §5's bare-JDK probe, and each now arrives only from a shape the contract refuses.
- **Design debt carried forward, named rather than absorbed:** the body-size limit ADR-0022 §8 refused is still unwritten;
  `Request` still accepts a `Content-Length` a handler may read or ignore, because refusing a field the client sent is
  §5's excluded case; and the `304` length loss below is expressiveness debt, not a bug.

## Alternatives

**Keep `byte[]`, add a factory or a constant for "no body".** Considered first and dropped at §3's reasoning: with
`null` already meaning "no body" and `byte[0]` the same fact, a `noBody()` would be a third spelling, and the phase's rule
is that a shape needing two spellings of the same fact is a shape with one too many.

**Keep the shape and enforce nothing.** The pre-phase state. It left `204`-with-bytes, `304`-with-bytes, a
handler-written `Content-Length`, a handler-written `Transfer-Encoding` and a length-less `HEAD` all silently available, and
§5's first three rows are the measured consequences.

**Move both rules into the adapter.** Rejected at §2's third option: a `500` for an application bug, and no rule for the
next adapter.

**Answer `HEAD` by reusing the `GET` route.** Rejected: it changes routing (ADR-0019 §2's matching is on the method the
client sent) and it would make `Router` decide a wire behaviour. Adapter-local `HEAD`→`GET` dispatch is a decision for a
later phase with a consumer, not an audit fix.

## Rejected alternatives

- **`Optional<byte[]>` as the body type.** Rejected: it makes "absent" a value a caller must unwrap, keeps `byte[0]` and
  `null` distinguishable when §3 says they are one fact to the contract, and changes a public constructor signature for a
  distinction no test can use.
- **A `BodyMode` / `BodyPresence` enum, or `EmptyBody` / `NoBody` / `ResponseBody` / `Representation` / `StreamingBody`
  types.** Each names one of §3's three concepts as a type, which is exactly the collapse the phase was written against —
  and `StreamingBody` in particular re-opens ADR-0018 §7's priced cost rather than this phase's question. All are on the phase directive §2's
  forbidden-name list.
- **Adapter drops a `204` body.** The forbidden «静默吞掉用户明确提供的 body», and §5's first bare-JDK row is what it looks like:
  the bytes vanish, the server logs a warning nobody reads, and the client's response is a different response.
- **Adapter refuses a `204` body with a `500`/`400`.** §2's third option: wrong blame, wrong party, and unshareable.
- **Let `HEAD` declare its length through `sendResponseHeaders` instead of writing the field.** Measured: the JDK takes the
  HEAD branch at `ExchangeImpl.java:235-246`, sets `contentLen = 0`, logs
  `WARNING: being invoked with a content length for a HEAD request`, and the declared length is discarded. This is probe F's
  shape (one test pair fires) and probe F2's, which is the stronger version and fires eight.
- **Make `304` special: allow bytes so the length can be stated.** Rejected as the exact ambiguity the phase removes —
  "a body is refused, except where a body is secretly a length" — and the consequence of refusing it is recorded as a
  limitation instead of hidden.
- **Set `Content-Length` on a `304` by hand in the adapter.** §5's row 2 is that shape measured: the JDK writes no length of
  its own for a `304` (`noContentLengthHeader = (rCode != 304)` is about *suppressing* the derived one), so a
  handler-visible-but-actually-wrong number reaches the client. HTTP permits the field on a `304`; this framework has no
  honest source for the value, so it states none.

## Testing evidence

`ResponseTest` — 25 tests, of which 11 are new: `BodylessStatuses` (5) over the table `{100, 101, 103, 199, 204, 205, 304}` and
`FramingFields` (6). `JdkResponseBodySemanticsTest` — 25 new tests in seven nested classes, all on a real socket, some also
through `java.net.http.HttpClient`. `JdkHeaderAndBodyTest`'s HEAD test was renamed and strengthened to
`aHeadResponseHasFieldsAndTheRepresentationLengthButNoBody`, asserting `Content-Length` is exactly `["5"]`.
`JdkHttpServerConcurrencyTest.mixedShapesUnderConcurrencyStayOnTheirOwnRequests` adds the framing dimension to the
concurrency suite (directive §19's "OPTIONS \*/HEAD/GET/204/304 must not pollute").

| test | what it pins |
| --- | --- |
| `BodylessStatuses.refusesEveryStatusThatCarriesNoBodyWhenBytesWereGiven` | all six bodyless statuses × non-empty body → IAE |
| `BodylessStatuses.acceptsTheSameStatusesWithNoBodyAtAll` | the same six with `null` and with `byte[0]` → constructed |
| `BodylessStatuses.refuses204RatherThanLetTheServerDropTheBodySilently` / `refuses304BecauseItsBodyWouldNeverArrive` | the two statuses named in the decision, each refusing for its own measured reason |
| `BodylessStatuses.stillAllowsABodyForEveryStatusThatHasAPlaceForOne` | 200/201/206/301/400/404/405/500 still take bytes — the refusal is a set, not a default |
| `FramingFields.refusesContentLengthOnAResponse` / `refusesTransferEncodingOnAResponse` / `refusesBothNamesInAnyCaseAndOnAnyStatus` | the two fields, any case, any status |
| `FramingFields.refusesAnEmptyValueForThoseNamesToo` | `Content-Length: ""` is still a framing field |
| `FramingFields.letsEveryOtherFieldThroughIncludingItsOwnLengthsAndTypes` | `ETag`, `Content-Type`, `Content-Location`, `X-Length` pass — the refusal is two names, not a category |
| `FramingFields.namesTheStatusAndTheFieldRatherThanGuessingAtADrop` | the message carries the status, the field and the value |
| `EmptyBodies.anEmptyByteArrayArrivesAsZeroLengthAndNoBytes` / `aNullBodyArrivesIdentically` / `aNonEmptyBodyArrivesWithItsLengthAndItsBytes` | §3's concept 1 on the wire: `Content-length: 0` vs `: 5`, no chunked |
| `TwoOhFour.aTwoOhFourIsWrittenWithNoBodyAndNoLength` + `aHandlerCannotGiveATwoOhFourABody` + `aRealClientAcceptsATwoOhFourAndKeepsTheConnection` | §4's `204` triple: no length, refusal at construction, and a keep-alive client sequence that completes |
| `ThreeOhFour.…(4 tests)` | §4's `304` triple plus `anUnconditionalGetNeverBecomesAThreeOhFourByItself` — a `304` is an answer this adapter writes only when a handler returns it |
| `HeadResponses.…(8 tests)` | §6's rows 1-8: own handler, own route, length with zero bytes, `405` keeps `Allow`, `204`/`304` state neither, keep-alive usable after a `HEAD`, and the lowercase-token asymmetry |
| `JdkHttpServerConcurrencyTest.mixedShapesUnderConcurrencyStayOnTheirOwnRequests` | 8 threads × 50 rounds × 9 shapes = 3600 assertions that a `200`'s bytes never appear on a `204`, a `HEAD` never leaks a body, and a parametrized path's captured value never bleeds across — the framing dimension of §19's "must not pollute" |
| `Informational.…(3 tests)` | §7: no length, no bytes, empty reason phrase for a code the JDK does not name |
| `AdapterAnswers.…(3 tests)` | the adapter's own 400/404/405/500 each carry exactly one length — a *count*, which is the property a handler-written field used to break |
| `NoServerSideCorrection.noFramingShapeMakesTheServerLogAWarning` | a JUL handler on `com.sun.net.httpserver` sees zero WARNING-or-above records across twelve shapes, including a `HEAD` of a `200` that carries bytes and a `HEAD` of each bodyless status. The three warnings measured on the bare JDK (`rCode = 204: forcing contentLen = -1` ×2, `being invoked with a content length for a HEAD request` ×1) each come from a declaration this module no longer makes |

Mechanism evidence produced outside the build (`/tmp/p295/final/`, both JDKs, outputs diffed):
`WireTable.java` — the §8 table and the keep-alive chain; `JdkFraming.java` — the §5 table on a bare JDK server, including
the `java.net.http` failure and the `head`/`HEAD` pair. `ExchangeImpl.isHeadRequest()` is `private boolean`
(`sun/net/httpserver/ExchangeImpl.java:121`), so §6's row 8 is proven behaviourally (5 raw bytes on the `head` token, 0 on
`HEAD`) plus the source line; the reflection attempt returns `NoSuchMethodException` and is reported that way rather than
smoothed over. `--add-opens` was tried and the correct module name is `jdk.httpserver`; it does not help, because the member
is private rather than closed.

Mutation probes that fired (each applied, run, reverted; the five mutated files verified byte-identical afterwards):

| probe | change | failing tests |
| --- | --- | --- |
| D | `checkBodyAllowed` call removed from `Response`'s constructor | `ResponseTest$BodylessStatuses`: `refusesEveryStatusThatCarriesNoBodyWhenBytesWereGiven`, `refuses204RatherThanLetTheServerDropTheBodySilently`, `refuses304BecauseItsBodyWouldNeverArrive`; `JdkResponseBodySemanticsTest`: `TwoOhFour.aHandlerCannotGiveATwoOhFourABody`, `ThreeOhFour.aHandlerCannotGiveAThreeOhFourABody`, `Informational.aHandlerCannotSendBytesWithAnInterimStatus` — 6 across two modules |
| E | `304` dropped from `carriesNoBody` | `refusesEveryStatusThatCarriesNoBodyWhenBytesWereGiven`, `refuses304BecauseItsBodyWouldNeverArrive`, `ThreeOhFour.aHandlerCannotGiveAThreeOhFourABody` — 3 |
| F | bridge treats `HEAD` like `GET`: declares the length and hands the bytes | `HeadResponses.aMethodTokenThatIsNotTheUpperCaseHeadIsNotAHeadRequestToTheJdk`, `NoServerSideCorrection.noFramingShapeMakesTheServerLogAWarning` — 2 |
| F2 | dispatcher's `writesBody` returns `true` for every method (the stronger form of F) | 8: the five `HeadResponses` length tests, `aMethodTokenThatIsNotTheUpperCaseHead…`, `NoServerSideCorrection…`, `JdkHeaderAndBodyTest$ResponseHeaders.aHeadResponseHasFieldsAndTheRepresentationLengthButNoBody`, `JdkHttpServerConcurrencyTest.mixedShapesUnderConcurrencyStayOnTheirOwnRequests` |
| G | `declared` passes `body.length` for an empty body (the `-1` branch gone) | `JdkHeaderAndBodyTest$ResponseHeaders.anEmptyBodyIsDeclaredAsZeroBytesAndNotChunked`, `EmptyBodies.anEmptyByteArrayArrivesAsZeroLengthAndNoBytes`, `EmptyBodies.aNullBodyArrivesIdentically` — 3 |
| H | dispatcher answers a post-commitment `IOException` with a second status | `JdkErrorSemanticsTest$CommittedResponses.aClientThatHangsUpMidWriteLogsOnceAndNeverProducesASecondStatus` — 1 (ADR-0023 §6's regression, kept in the phase's probe set because directive §14 lists it) |
| I | adapter's `mayCarryABody` allows `204` (the two lists disagree) | `HeadResponses.aHeadOfATwoOhFourStatesNoLength` — 1 |

No probe failed to fire, so the directive's «如果某个 mutation 无法击穿测试，必须补测试或解释不可测原因» required no
explanation. F versus F2 is recorded as two rows rather than one because they pierce different numbers of tests: the
bridge-local mutant is caught only where HEAD behaviour differs from the JDK's own suppression, while the dispatcher-level
mutant, which makes HEAD a `GET` all the way down, is caught everywhere the shape is asserted. That difference is the
evidence that the tests assert behaviour rather than the implementation of one class.

## Amendment (2026-11-27): 205 Reset Content is bodyless too

RFC 9110 §15.3.6 says that "a server MUST NOT generate content in a 205 response". The original bodyless predicate
covered 1xx, 204 and 304 but omitted 205. `Response.carriesNoBody` and the JDK bridge's `mayCarryABody` now include 205,
so a non-empty 205 is refused at construction and a valid empty 205 is written without entity bytes.

`ResponseTest` adds 205 to its existing bodyless table; the test failed before the predicate changed because no exception
was thrown. `JdkHeaderAndBodyTest.aResetContentResponseCarriesNoBody` sends a real GET and measures status 205, the
application header, `Content-Length: 0`, and an empty body. Zero length is truthful for this JDK's 205 write; unlike 204
and 304, the adapter does not claim that the field must be absent.

Reference: [RFC 9110 §15.3.6](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.3.6).

## Limitations

1. **A `304` cannot state the stored representation's length.** HTTP allows a `304` to carry `Content-Length` describing
   what a `200` would have sent; this framework cannot, because the only way to get that number into the adapter today is
   to hand it bytes it must not write — §4's refused shape. This is the price of one uniform rule, taken deliberately, and
   it is **design debt** for a phase that has a consumer for conditional responses: the fix is not "allow bytes" and not
   "let the handler set the field" (§5), so it needs its own decision about where a length-without-a-body comes from.
2. **A method token that is not exactly `HEAD` is not a `HEAD` to the JDK — and the response it gets is worse than the
   one ADR-0022 §7 described.** `HttpMethod.of()` folds the case of the nine known names, so `head /doc` reaches the
   dispatcher as `HEAD`: no bytes are written and the length the representation has is set into the fields. The server's
   `isHeadRequest()` compares `"HEAD".equals(method)` exactly (`ExchangeImpl.java:121`), so it takes the *non*-HEAD branch,
   sees the declared `-1`, and `set()`s its own `Content-length: 0` over ours. Measured on the shipped adapter, both JDKs:

   ```
   HEAD /doc -> Content-length: 5, raw body bytes = 0      (the answer HTTP asks for)
   head /doc -> Content-length: 0, raw body bytes = 0      (a zero-length representation claim)
   Head /doc -> Content-length: 0, raw body bytes = 0
   ```

   The body is right in all three cases and the length is wrong in two: a client that asks for a `head` learns the
   representation is empty. No server warning is logged, because the branch that warns is the one not taken.
   `aMethodTokenThatIsNotTheUpperCaseHeadIsNotAHeadRequestToTheJdk` pins it as a limit of this server rather than a
   behaviour to build on; the honest workaround is to send `HEAD`, and an application cannot enforce that.
3. **A `HEAD` of a `204`, a `304` or a `1xx` states no length either.** Correct by §3's construction — those statuses
   carry no body and the contract refused bytes — but a client reading a `HEAD` of a resource whose `GET` would answer `204`
   learns nothing about size.
4. **`HEAD` still needs its own route.** No `GET`→`HEAD` fallback exists, so a `HEAD` of a `GET`-only resource is `405`.
   Recorded because §6's answer to question 1 is the one a client least expects.
5. **No `100 Continue` handshake.** §7 measures what a `1xx` response does today; it is not an interim-response
   implementation, and nothing claims it is.
6. **The body-size limit remains unwritten.** A `200` with a 10 MB body is copied three times through this contract
   (in, out, and the adapter's write) and the phase neither added a limit nor changed that. ADR-0022 §8's design debt and
   ADR-0018 §7's priced cost are still the record.
7. **Field spelling and order are the JDK's, not ours.** `Headers` canonicalises names and is hash-backed (ADR-0022 §5), so
   the `Content-length`/`Content-Length` case pair in §5 and §8 is the server's spelling; the adapter writes the canonical
   name and the server decides how it prints.

## Relation to previous ADRs

- **Closes ADR-0022 §7.** That section recorded «a HEAD client cannot discover a representation's size through this
  adapter» as a limitation and said a real fix belongs with a server that implements HEAD's semantics. It does not: the
  fix was in this module's write, and §6's measurement supersedes the claim. ADR-0022 §2-§5 and §8-§9 are untouched — the
  request-side findings, the honest JDK header limits, and the package-private boundary all stand, and §6's field copy is
  the same `add`-per-value loop ADR-0022 §5 pinned.
- **Extends ADR-0022 §6.** That section decided `Content-Length` is derived and that an empty body is declared as `-1`; this
  phase adds the `HEAD` derivation to the same method and keeps the rest of the rule unchanged. Probe G still fires the
  tests ADR-0022 §6 wrote.
- **Answers the question ADR-0018 §3 left open.** «A response with a mutable body and a mutable status is a response that
  can be written to the client twice» — and nothing there said what a response may *contain*. §4 and §5 are the two rules
  the frozen shape needed, added inside the constructor so the type's public surface, its accessors and its immutability
  rules (`every_field_is_final`, `no_method_writes_or_mutates`) are all unchanged.
- **Keeps ADR-0023's error semantics intact.** 404 names no path, 405 carries an adapter-local `Allow`, a handler
  exception is logged and answered with the status text alone, and a committed response stays one response. §14's
  directive §14's regressions were re-run rather than re-reasoned: `JdkErrorSemanticsTest`'s 22 tests pass on both JDKs, and probe H is
  drawn from this phase's probe set precisely to prove the commitment rule is still load-bearing.
- **Is the mirror image of ADR-0026.** That record changed no behaviour and pinned a boundary with sockets; this one
  changed two refusals and one write, and pinned them with the same kind of socket evidence. Neither introduced a type, and
  together they are the phase's reading of §27: existing API first, minimal surface, correct wire, no hidden magic.
- **Leaves ADR-0019's boundary alone.** `Router` gained no method and no concept for `HEAD`, `204` or a length; the route
  table still answers exactly two questions (`match`, `methodsFor`) and the adapter still decides what to do with both.
