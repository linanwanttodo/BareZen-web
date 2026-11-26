# ADR-0031: The Request-Body Limit — One Key, One Default, Two Enforcement Surfaces, Status 413

## Status

accepted — 2026-10-01

Closes the debt ADR-0022 §8 recorded («No request body size limit… stated rather than hidden») and the carry-forward
notes of ADR-0027 §Consequences and ADR-0018 §7, which placed a limit with the adapter «which is the layer that knows
what it can afford to read». This record is that adapter reading its own bill.
Related: ADR-0013, ADR-0018, ADR-0022, ADR-0025, ADR-0027, ADR-0029.

## Context

Until this phase the bridge called `readAllBytes()` on the request-body stream: an unbounded read into a `byte[]`.
The phase directive (Stage G) ruled «keep it unknown» off the table — the decision had to be G1 (implement, with the
existing `Configuration` layer, a default, an over-limit status, and known/unknown-length behavior) or G2 (defer
with a written why/risk/mitigation/phase). Every number in this record comes from a socket, on both supported JDKs,
before and after the change.

## Problem

1. What does the unbounded read actually cost on the shipped server? (§Investigation 1)
2. Which request shapes can carry bytes that the limit must refuse, and which shapes does the JDK decide before an
   exchange exists? (§Investigation 2–3)
3. Where does the configuration live, given «no second config system, nothing in core»? (§Decision 1–2)
4. What is the over-limit answer — status, phrase, body, framing? (§Decision 3)
5. When is a declared `Content-Length` refused, when is an unknown length counted, and what does each cost the
   connection? (§Decision 4–5)
6. Is the one new public API justified under the phase's B.5 nine-question gate? (§Compatibility Impact)

## Investigation

**1. The cost, measured on the shipped unbounded server** (JDK 21, `/tmp/p210/probe3/limit-unlimited-21.txt`): a
chunked POST delivering 1 MiB + 64 B returned `200 len=1048640` — the handler ran with all of it buffered, which is
the denial-of-service shape ADR-0022 §8 named without a number on it. Nine shapes were probed (A–I) and every one of
them is now a test or a §Decision row.

**2. Two framing surfaces, both pre-existing, decide what the limit can even see** (`ServerImpl.java:797-833`, both
JDKs, diffed identical):

| shape | who answers | measured |
| --- | --- | --- |
| `Content-Length: abc` | the JDK's parser | 400, `text/html`, before any exchange (`:824-833`) |
| `Content-Length` + `Transfer-Encoding` | the JDK's parser | 400, `text/html` (`:797-801`) |
| no `Content-Length`, no `Transfer-Encoding`, bytes after the blank line | the JDK's framing | **`clen = 0`** — the bytes are not content; arrives `len=0` under any limit (probe C/D, re-measured post-decision) |
| chunked | this module's bounded read | counted, refused at the first byte past the limit |
| known `Content-Length` | this module's claim check | refused on the claim before the counting read |
| truncated body under the limit + FIN | the read itself | `IOException` → the existing 400 (unchanged semantics) |

**3. A parse completes before an exchange exists.** `Request.headers()` peeks one byte past the blank line
(`Request.java:139`) over a stream whose reads block until data or EOF (`Request.java:315`); so a huge claim that
sends neither a body byte nor a FIN reaches no handler at all (measured: silence until the client's own timeout),
while the same claim plus FIN plus nothing answered 413 in **4 ms** post-decision. The limit cannot fix that and
does not try — it is the JDK's framing, pre-existing, named here so no one mistakes the 4 ms for the whole story.

**4. Placement of the declared-length refusal, measured both ways.** An earlier build of this feature ran the claim
check inside a try-with-resources around the body stream. The stream's `close()` drains up to
`sun.net.httpserver.drainAmount` (default 64 KiB, `ServerConfig.java:53`, `LeftOverInputStream.java:65-69`) and the
drain's reads block on a client that is waiting for its answer — measured: a claimed-64 MiB request that sent five
bytes and waited sat unanswered for the full 9 s of the probe timeout. The shipped build refuses the claim **before
touching the stream** and leaves every release to the dispatcher's `finally` (`exchange.close()`, which runs after
the answer is on the wire); the same request answers 413 immediately. Opening the stream costs nothing either way —
`ServerImpl` materializes it before the handler runs anyway (`:899` on 17, `:903` on 21).

**5. The connection after a refusal, both outcomes, measured:** the dispatcher answers, then `exchange.close()`
drains the leftover. (a) surplus the 64 KiB drain can finish → `isEOF()`, keep-alive survives, and the next request
on the same socket gets a normal `200 len=5` (probe6 C1). (b) surplus past the drain → not-at-EOF marks the
connection for closing (`ServerImpl.java:455-462`) and the socket ends after the 413 — measured as a reset on a
flooded socket (probe6 C2). Either way the surplus is never parsed as a second request: no desynchronization is
possible from this path. No `Connection: close` is written on the refusal: the framing answer belongs to the server
and clients that stay well-behaved keep their pooled connection (a), while misbehaving ones lose it (b) without the
framework having to choose for them.

**6. RFC 9110 §15.5.14**, verbatim: «The 413 (Content Too Large) status code indicates that the server is refusing to
process a request because the request content is larger than the server is willing or able to process. The server MAY
terminate the request, if the protocol version in use allows it; otherwise, the server MAY close the connection.»
The RFC renames the status Content Too Large; the JDK's own status table still spells the reason phrase
`Request Entity Too Large` (`Code.java:97-99`). A `Retry-After` is prescribed only «if the condition is temporary» —
a configured limit is not, so none is written.

**7. The growth path was unreachable until a later phase's automation provoked it.** This record shipped with every
one of its tests passing on both JDKs, and no test sent a body larger than the counting read's first buffer (8 KiB).
Worse, the loop only grew when a read landed *exactly* on the buffer's end, and the JDK's streams clamp a read to the
current chunk or the declared remainder — so a body between 8 KiB and the limit reached neither the grow step nor a
legal read: it asked for `buf.length` bytes at offset `size` instead of `buf.length - size`, which runs past the
array. Measured on JDK 21 with the 1 MiB default, before and after the one-line fix: an 8 000-byte chunked body
answered `200 len=8000`; a 9 000-byte chunked body (eight whole chunks plus one) closed the connection with **no HTTP
answer at all**, the server logging `IndexOutOfBoundsException` from `java.io.BufferedInputStream.implRead` through
`ChunkedInputStream.readImpl:130` into `BodyLimit.readBounded`; the same shape with a declared `Content-Length`
failed the same way, because the clamping differs only in which stream does it. A suite that only ever uploads small
bodies cannot see this, which is why the class-major automation added in Stage H (ADR-0032) and the coverage report's
list of never-executed lines — not a failing request test — are where it surfaced. §Decision 5's «grown to what
actually arrived» is now true for every body up to the limit, and
`aDeclaredBodyPastTheFirstBufferArrivesWhole` / `aChunkedBodyPastTheFirstBufferArrivesWhole` hold it, both measured
red before the fix and green after.

## Options Considered

**G2 — defer with written risk.** Rejected: the directive rules «unknown» out, and §1 priced the risk on the shipped
server (1 MiB buffered by a handler that never asked for it). A limit needs no new subsystem (it reuses the existing
configuration layer and one already-decided status surface), which is the precondition deferral would need to be
honest about; without it deferral is just the bug with paperwork.

**Limit in `core` or `web`.** Rejected by the phase's own rules and by architecture: core must not carry HTTP policy
(ADR-0018 §12's direction of dependency), and `barezen-web` keeps no compile dependency on core's `Configuration`
(ADR-0018 §12, test-scope only in 2.9) — a key there could not be resolved by the module that enforces it. The
adapter is where ADR-0018 §7 put this decision already.

**Reading `System.getProperty` in the adapter** (JDK-style knobs like `sun.net.httpserver.maxReqTime`). Rejected:
breaks the shipped ArchUnit rule that an adapter reads no environment and no properties, and starts the second
configuration system the directive forbids. The provider path (`create(host, port, router)`, frozen by ADR-0025 §7)
therefore stays on the default — §Decision 2.

**A constant-only default with no configuration at all.** Rejected: G1 requires «配置必须使用已有 Configuration»; a
tunable number is the difference between a policy and a bug report.

**Refusing every body with no `Content-Length`.** Rejected by §2's measurement: the JDK frames that shape as
zero-length and the handler sees no bytes; inventing a 400/413 there would refuse the server's own framing, and
would break clients (including the JDK's own `HttpClient` on some shapes) that send bodiless POSTs legitimately.

**400 instead of 413** (folding the refusal into the existing catch). Rejected twice: semantically (a limit is not a
malformed request — the request was fine, the size was not), and mechanically — `Exceeded` deliberately does not
extend `IOException`, because the dispatcher's existing `catch (IOException) → 400` would swallow it into the wrong
status (§Decision 3).

**Writing `Connection: close` on every 413.** Rejected: punishes the (a) case §5 measured — a drainable surplus and
a client that keeps its connection legitimately — and the JDK's own recovery rule already closes what it must close.

## Decision

1. **The limit exists, as one key, in the module that enforces it.**
   `BodyLimit.MAX_REQUEST_BODY_BYTES` = `ConfigurationKey.of("barezen.http.jdk.maxRequestBodyBytes", Long.class, …)`
   declared in `com.barezen.http.jdk` (package-private record `BodyLimit` holds the resolved value). `Long` because
   the unit is bytes and an `int` type would cap a deployment at 2 GiB by accident rather than by policy. Resolution
   goes through the existing `Configuration` layer (ADR-0013) — no second config system.
2. **The default is 1 MiB (1024 × 1024) and every construction path runs under a limit.** The configuration-taking
   constructor resolves from the snapshot and fails at construction — before any bind — naming key, value and source
   if a present value is not a number (a snapshot without the key takes the key's default). The two older
   constructors, and the provider path whose argument list ADR-0025 §7 froze, resolve to that same default:
   **unconfigured is never unbounded** (negative values are a misassembly, refused with `IllegalArgumentException`;
   zero is a policy — no request body is accepted at all — and is legal).
3. **The over-limit status is 413, and it is framed like every adapter answer.** The status line carries the JDK's
   own phrase (the status line is the server's; rewriting it would edit an answer this framework does not own —
   ADR-0023's rule), the body is the RFC §15.5.14 words `Content Too Large`, `text/plain; charset=utf-8`,
   `Content-Length: 17`, no `Transfer-Encoding`, nothing path-shaped, nothing limit-numbered (the limit goes to the
   DEBUG log). The refusal predicate is the same `writesBody(exchange)` every other answer passes through, so a HEAD
   over the limit states `Content-Length: 17` and sends no bytes (measured) — the framing matrix (ADR-0029) holds
   for the sixth status for free.
4. **A known `Content-Length` is refused on the claim, before the counting read and before the stream is touched**:
   no read, no allocation, no wait for the claimed bytes, answer in milliseconds (§Investigation 4–5). A truncated
   body that is *over* the limit is a 413 too — the claim was already false twice over, and the limit fault is the
   one known before reading; a truncated body *under* the limit stays the arrival failure it was (400).
5. **An unknown (chunked) length is counted, and the count stops at the first byte past the bound** — the endless
   stream gets its 413 mid-body without being finished. The buffer is grown to what actually arrived (bounded by
   limit + 1 so `== limit` and `> limit` are distinguishable), never to the limit in advance. At `Long.MAX_VALUE`
   the probe capacity saturates because no successor exists; wrapping it negative would skip the read and silently
   return an empty body. Release and connection
   recovery are the JDK's: no `close()` runs before the answer (§Investigation 4), the dispatcher's `finally` does,
   and §Investigation 5's drain rule decides keep-alive versus close.

## Consequences

- The 1 MiB-buffered chunked request (probe H) becomes `413` with 64 KiB-plus-backlog as its worst memory cost —
  the counting buffer is bounded by the limit, the drain by `drainAmount`.
- **Residual, named, not solved here:** a client that declares a large body, sends nothing, and never half-closes
  stalls the JDK's parse before this module exists for the request (§Investigation 3), and a stalled declared body
  occupies a carrier thread — the server's own request timeout (`sun.net.httpserver.maxReqTime`, default −1 =
  forever, `ServerConfig.java:46`) is the knob and it is deliberately unnamed by this framework (it is a
  `System.getProperty` read, which the adapter's rules forbid). A deployment that needs it sets it where the JVM is
  started; the limit's own scope is bytes and refusals, not the JDK's timeouts.
- A body that arrives with neither framing header stays a 413's non-event: it is zero-length by the server's
  framing (§2) and no limit is involved.
- The debt sentences in ADR-0022 §8 and ADR-0027's carry-forward list are now *decided*, and the README states the
  default; nothing about the request body is unknown anymore, which is what Stage G asked for in one line.
- Memory posture unchanged for accepted traffic: a body ≤ 1 MiB is still fully buffered — that is ADR-0018 §7's
  priced cost with the bound ADR-0018 §7 said belonged to the adapter.

## Compatibility Impact

Public surface before: 9 types in `com.barezen.web`'s contract plus this module's two (`JdkHttpServerAdapter`,
`JdkHttpServerAdapterProvider`). After: **the same types** — one *constructor* added to the adapter, nothing else.
No type, method, dependency, class-major, or wire shape changed except the new refusal this decision exists to
produce. The ArchUnit rules all still hold unmodified: the adapter reads no environment and no properties (the
`Arrays.copyOf` growth is why not even `System.arraycopy` appears), JDK types stay out of public signatures
(`Configuration` is a core type), and the module exposes exactly two public types.

**B.5, the nine questions for the one new public API:**

1. **Exact signature:** `public JdkHttpServerAdapter(InetSocketAddress address, Router router, Configuration configuration)`.
2. **Why existing API insufficient:** the two shipped constructors take an address, a router and (optionally) a
   borrowed `ThreadProvider` — there is no channel through which a resolved snapshot can reach the adapter, and the
   frozen `create(host, port, router)` (ADR-0025 §7) is deliberately the configuration-file-shaped subset, not a
   configuration channel.
3. **Actual consumer:** the assembly code that already resolves a `Configuration` — ADR-0013/0015 make passing
   resolved values the application's job, and this is the argument it has been looking for — plus
   `JdkRequestBodyLimitTest`, which starts servers under explicit limits through it.
4. **Why package-private insufficient:** the consumer is by definition outside `com.barezen.http.jdk` (assembly is
   the application's); a package-private seam could only be reached by reflection or by moving assembly into the
   adapter, which is the container this framework refuses to be.
5. **Alternatives rejected:** sysprop reading (§Options); constant-only default (§Options); widening
   `HttpServerAdapterProvider.create` (a contract change decided against by ADR-0025 §7 itself — «a factory
   signature that grows with every collaborator has become an assembly container»); a public `BodyLimit` type or
   setter (more surface, mutable, against ADR-0013); a second builder type (a sixth owned thing claimed through a
   seventh class).
6. **Compatibility impact:** additive overload; existing constructors keep their exact behavior and signatures. The
   real behavior change is intentional and belongs to the decision, not to the API: requests over 1 MiB that
   previously succeeded on the older constructors now receive 413 — that is the vulnerability closing. Callers who
   want a different number use the new constructor; the provider path runs the safe default by design (ADR-0025's
   frozen list predates this feature and this record does not reopen it).
7. **Tests:** 28 socket tests in `JdkRequestBodyLimitTest` — the boundary under/exactly/over in both framings, the
   claim refusal, truncated under (400) and over (413), the parser-owned shapes pinned as the JDK's own answers, the
   keep-alive-after-drain and close-after-overflow pair, bodies larger than the counting read's first buffer on both
   framing surfaces (§Investigation 7), the HEAD-over-limit framing row (§Tests), resolution (configured, absent-key,
   default value, unconstructed adapter enforcing 1 MiB, bad value, negative, zero, null).
8. **Documentation:** this record; the constructor's own javadoc (why-argument, why-not-System, fails-before-bind);
   the adapter class doc's five-owned-things list; the README's phase text; the index row below.
9. **ADR:** this one.

## Tests

All on raw sockets (a limit about framing cannot be tested through a client that rewrites framing — `RawHttp`),
green on both JDKs via the full reactor:

- `JdkRequestBodyLimitTest` — the whole decision, 29 tests in five nested groups, per §Compatibility Impact item 7.
  The two buffer-crossing tests (§Investigation 7) were run against the unfixed reader and both failed with
  `SocketException: Connection reset` — the mutation is recorded rather than asserted away, and the fix is the one
  `room` expression in `readBounded`.
- `JdkResponseBodySemanticsTest.FramingMatrix` keeps every pre-existing row green against the rebuilt dispatcher
  signature. The sixth-status claim (§Decision 3) was probe-measured when this record was accepted and had **no test
  behind it**: Stage I's audit found the gap, and
  `JdkRequestBodyLimitTest.KnownLength.aHeadOverTheLimitStatesTheRefusalLengthAndSendsNoneOfIt` is the pin that closes
  it — the 64 MiB claim over a HEAD answers 413 with `Content-Length: 17`, no `Transfer-Encoding`, and no body bytes.
  It is written on the half-close instrument because a HEAD answer has no body to terminate a read, which is the
  client's shape rather than the server's.
- `BarezenHttpJdkArchitectureTest` (24 rules) passes unmodified, including the no-environment rule the new read
  path had to satisfy — its one `System.arraycopy` draft was the rule's own counterexample, and the shipped
  `Arrays.copyOf` is that rule's evidence.

**Amendment (2026-11-27): saturate the largest limit's probe capacity.** `limit + 1` wrapped negative for
`Long.MAX_VALUE`, so `readBounded` skipped its loop and returned an empty array even for a non-empty stream. The
capacity now saturates at `Long.MAX_VALUE`. `theLargestLongLimitDoesNotOverflow` uses a stream that rejects
zero-length reads and requires the real input byte to be returned; it failed against the old code with `expected [0]
but was []`. The ordinary exact-limit and one-byte-over tests remain unchanged.

## Related Decisions

- **Decides ADR-0022 §8's debt** (the unbounded `readAllBytes()`), with ADR-0027 §Consequences 6's copy-count and
  ADR-0018 §7's forward note («a maximum body size… belongs to an adapter») as the instructions it follows.
- **Uses ADR-0013's machinery verbatim** — key, snapshot, resolve-at-assembly, fail-at-construction — which is why
  no new configuration vocabulary appears in this module.
- **Keeps ADR-0029's matrix true** for a status that did not exist when the matrix was written, via the same
  `writesBody` predicate (ADR-0028's exact-token rule) every other answer passes through.
- **Pairs with ADR-0030** as the two halves of Stage F/G: the request side had a present risk and a cheap
  mitigation, so it decided; the response side has an absent consumer and an expensive abstraction, so it decided
  not to — and both answers are now written down instead of unknown.
- **Leaves the JDK's own knobs untouched** — `maxReqTime`, `drainAmount`, the 8 KiB read buffer are the server's
  defaults, named in §Consequences rather than borrowed or shadowed.
