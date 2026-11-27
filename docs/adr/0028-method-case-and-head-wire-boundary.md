# ADR-0028: HTTP Method Case and the HEAD Wire Boundary

## Status

accepted — 2026-10-01

Decides that `HttpMethod`'s case rule is kept unchanged, that the JDK adapter decides HEAD's wire treatment from the
raw method token with the same exact comparison the server itself uses, and that a method token which is not exactly
`HEAD` receives the ordinary response its representation has. Supersedes ADR-0027 limitation 2.
Related: ADR-0018, ADR-0019, ADR-0022, ADR-0023, ADR-0027, ADR-0029.

## Context

Phase 2.10's premise is that JDK-specific behavior must be measured rather than guessed. The method-token path had
become a place where three components each hold a rule and no single record states which one wins on the wire:

- `HttpMethod.of()` folds the case of the nine standard names and keeps any extension token exactly (ADR-0018 §4);
- `Router` keys on the folded value, so `"head"`, `"Head"` and `"HEAD"` all select the `HttpMethod.HEAD` handler
  (ADR-0019 §2);
- `com.sun.net.httpserver` compares the raw token: `ExchangeImpl.isHeadRequest()` is `"HEAD".equals(method)`, and that
  comparison alone decides whether the server suppresses a response body and whether it writes a `Content-Length` of
  its own (`ExchangeImpl.java:121`, `sendResponseHeaders` branch `:235-246`).

ADR-0027 shipped with the adapter judging suppression from the folded contract value while the server judged framing
from the token. Its limitation 2 recorded what the two-writer outcome looked like. Phase 2.10 measured it again on both
supported JDKs before touching anything.

## Problem

1. Is an HTTP method token case-sensitive, and what exactly does the standard require? (§Investigation 1)
2. What do the shipped adapter and the bare JDK actually send for `HEAD`, `head` and `Head`? (§Investigation 2)
3. Which of the four candidate designs fixes the disagreement without widening the public API, and what does each
   cost? (§Options)
4. Where does the chosen rule leave the contract, the router, the dispatcher and the wire, and is the resulting split
   explainable in one sentence? (§Decision, §Compatibility)
5. What must a later reader never conclude from this record's wording? (§Compatibility)

## Investigation

**1. The normative position.** RFC 9110 §9 defines a method as a case-sensitive token: method names are registered as
uppercase strings, and "case sensitivity for the method identifier is relevant" — `GET` is not `get` as far as the
grammar is concerned. Nothing in the specification asks a server to fold a token's case, and nothing forbids a server
from recognizing a folded spelling as an implementation courtesy. Folding the nine is therefore a design choice
`HttpMethod` already documented (ADR-0018 §4), not a normative requirement — and a wire behavior must never *depend*
on it.

**2. The measured behavior, shipped adapter, raw sockets, Temurin 17.0.20.1+1 and 21.0.12.1+1, outputs diffed
identical modulo `Date`** (`/tmp/p210/probe/mc-17.txt`, `/tmp/p210/probe2/`):

| token | route handler runs | status | `Content-length` on wire | body bytes |
| --- | --- | --- | --- | --- |
| `HEAD /doc` | HEAD | 200 | `5` | 0 |
| `head /doc` | HEAD | 200 | `0` | 0 |
| `Head /doc` | HEAD | 200 | `0` | 0 |
| `GET/get/Get /doc` | GET | 200 | `5` | 5 |
| `POST/post /boom` (GET+HEAD only) | — | 405 | `18` | 18 |
| `unknown` / `PURGE` / `purge` | — | 405 | `18` | 18 |
| empty token (`" /doc…"`) | — | 400 (adapter's) | `11` | 11 |
| no target (`GET /doc` + CRLF) | — | 400 (JDK's HTML) | `40` | 40 |
| control char / quote / non-ASCII token | — | 405 (superseded, see below) | `18` | 18 |

**Amendment (2026-11-27).** This row is historical. After ADR-0018's method-token validation (`9d2609b`), a token
carrying a control character, a quote or a non-ASCII byte is refused as **400** — by the JDK parser before an
exchange exists, or by `HttpMethod.of` before routing — and never reaches the 405 row. The other rows stand.

The `head`/`Head` rows are the defect this record closes, and the mechanism is exact: the adapter suppressed the body
and wrote its derived `Content-Length: 5` into the response fields; the server, failing its `"HEAD".equals` test, took
the ordinary branch, computed `0` for a body it had decided to send, and `set()` replaced the adapter's value with it.
The client received a **false empty-representation claim** — a length of 0 on a non-empty representation — with no
server warning, because the branch that warns is the one not taken. A client or cache that believed it would keep
serving an empty body for a resource that has one.

**3. The other three levels, measured on the same run:** `of()` returns the shared `HttpMethod.HEAD` constant for all
three spellings; `router.match` selects the HEAD handler for all three; the handler ran in every case (call counters).
So routing never depended on case — only framing did, and only because two components applied two different tests to
two different values describing one request.

**4. HTTP/1.0 and keep-alive:** the same suppression/length behavior under HTTP/1.0 (`HEAD` → `Content-length: 5` and
0 bytes; `head` → 5 bytes); on one keep-alive connection, mixed sequences of all six spellings parse frame-for-frame
once each response carries a length that matches its bytes.

## Options Considered

**B1 — make `HttpMethod` strictly case-sensitive.** Rejected: it breaks the documented ADR-0018 §4 contract, makes a
client's spelling a routing key (routes that silently never match), buys nothing for the actual defect — the JDK's
`"HEAD".equals` comparison does not care what the contract type does — and is a public semantic change for a problem
with a non-public fix.

**B2 — the contract keeps folding, and `Request` gains `rawMethod()` so any application can see the token.** Rejected
under the phase's API-expansion rule: the expansion answers none of the nine questions with an actual consumer, the
one component that needs the raw token is the adapter itself, and a public accessor invites application logic that
branches on case — the exact fragility this matrix measures.

**B3 — the adapter keeps the raw token privately and decides HEAD's wire treatment with the server's own exact
comparison.** Chosen. No public type, method or field changes; `JdkRequestDispatcher.writesBody` takes the
`HttpExchange` and applies `!"HEAD".equals(exchange.getRequestMethod())` — the same predicate the server applies at
`ExchangeImpl:121`. Suppression and framing can no longer disagree, because one component's decision is now keyed to
the other component's key.

**B4 — normalize the request line at the edge so a folded HEAD never reaches the JDK's ordinary branch.** Impossible
as stated: the token is parsed before an exchange exists, and rewriting it would mean editing what the client sent
before deciding anything about it (ADR-0018 §2 forbids the contract doing so; the adapter cannot reach the parser).

## Decision

1. **`HttpMethod` keeps its case rule unchanged.** The nine fold, an extension token is exact, blank is refused. The
   javadoc's existing statement is the record; nothing in the contract changes.
2. **HEAD's wire treatment is decided from the raw token, with the server's exact test.** A request whose method token
   is exactly `HEAD` gets HEAD's response: the status and fields a GET would have produced, the representation's length
   derived from the body, and zero body bytes. This applies to every status the adapter can write, including its own
   400, 404, 405 and 500 answers.
3. **A token that is not exactly `HEAD` is treated on the wire as the ordinary request it is.** It routes to whatever
   handler the folded method selects — including the HEAD handler for `head`/`Head` — and its response carries the
   bytes a GET would have carried and the true length of the representation. The previous shape (suppressed bytes with
   a server-computed `Content-length: 0`) claimed an empty representation and is gone.
4. **No public API expansion.** The raw token is read at one call site inside a package-private class; `Request` gains
   no `rawMethod()`; the router gains no concept. The nine-question gate was never opened because no question arose
   that needed asking.

The split this commits to, in one sentence for §B.4's "explainable" proof: **which handler runs is a routing fact and
follows the folded method; which bytes go on the wire is a framing fact and follows the token the server reads.**
Neither level is allowed to speak for the other, and no third level exists.

## Consequences

- A `head /doc` client that expected HEAD semantics from this server now receives a correct ordinary response rather
  than a plausible-looking wrong one. A client that sends `HEAD` sees no change.
- The HEAD-suppression rule is pinned in one place; the dispatcher's 400 paths use the same predicate, so no call site
  carries a hand-passed boolean a future edit could desynchronise.
- The behavior difference between `HEAD` and `head` remains, but it is now an intentional documented boundary of this
  adapter rather than an emergent disagreement between two components. An application that needs case-insensitive HEAD
  handling is served by clients sending `HEAD`, which is what every mainstream client library does (measured:
  `java.net.http` sends uppercase).
- ADR-0027's limitation 2 is superseded; its limitations 1 and 3-7 stand. ADR-0027's probe row F named the old test
  (`aMethodTokenThatIsNotTheUpperCaseHeadIsNotAHeadRequestToTheJdk`); the probe still fires against the rewritten
  successor test `aMethodTokenThatIsNotTheUpperCaseHeadIsAnOrdinaryRequestOnTheWire`, pinned again as mutation F2 in
  Phase 2.10 Stage I.

## Compatibility Impact

- Public API: none. `HttpMethod`, `Request`, `Response`, `Router` surfaces are unchanged; `web` keeps 9 public types,
  `http-jdk` keeps 2.
- Wire behavior: the only changed responses are the non-exact HEAD spellings against a HEAD route, from
  `Content-length: 0` + 0 bytes to `Content-length: 5` + 5 bytes (and equivalents per representation). The old bytes
  were a false statement about the representation; no client could have relied on them without also having been told
  the resource is empty. Pre-1.0 contract (`0.1.0-SNAPSHOT`), so this is recorded rather than gated.
- No configuration, no lifecycle, no threading change. Both supported JDKs behave identically across every row.

## Tests

- `JdkResponseBodySemanticsTest$HeadResponses.aMethodTokenThatIsNotTheUpperCaseHeadIsAnOrdinaryRequestOnTheWire` —
  rewritten from the superseded pin: three spellings, handler ran 3×, exact HEAD gives length + 0 bytes, `head`/`Head`
  give length + the body (ADR-0027's old false-zero shape asserted absent).
- `JdkMethodCaseSemanticsTest` (15 tests) — Stage B.3's full matrix at all four levels: `of()` for every token,
  `Router` keying, a 13-row socket matrix (status, one length, no chunked, body bytes, handler marker, dispatch
  counters), the 405 `Allow` stability across case, adapter answers (404/500) under both HEAD spellings, the empty and
  invalid request-line rows with the component that answers each named, keep-alive orders HEAD→GET / GET→HEAD /
  HEAD→HEAD / GET→GET plus body-bearing mixes read frame-by-frame on one socket, HTTP/1.0, and Stage C.3's large-body /
  many-field / null-body HEAD shapes — all on raw sockets, none through a high-level client.
- Mutation evidence (Stage I): M2 (HEAD writes the body) and M3 (HEAD omits the derived length) fire on these tests.
  The directive's M1 — "lower-case HEAD treated as a normal body response" — *is* this record's decision, so its literal
  form is by construction unfalsifiable here; the falsifiable deviation is its inverse, re-deriving suppression from the
  folded method (the pre-2.10 two-writer state), which the rewritten pin and the `head`/`Head` matrix rows kill. The
  mapping is recorded rather than the mutation being quietly redefined.

## Related Decisions

- **Supersedes ADR-0027 limitation 2** (the false `Content-length: 0` override), with the mechanism quoted there still
  correct; ADR-0027 §§5-6 remain the framing-owner record this one plugs into.
- **Keeps ADR-0018 §4** (fold the nine, extension exact) and **ADR-0019 §2** (routing keys on the method value)
  unchanged; this record's contribution is that neither level may decide framing.
- **Continues ADR-0022/ADR-0023** boundaries: the JDK parser still refuses what it refuses before an exchange exists,
  and the adapter's 400/404/405/500 keep their shapes — only their suppression predicate moved onto the same key the
  server uses.
- **ADR-0029** (this phase) decides the 304 representation-length question and states the framing matrix one row of
  this record's table shares.
