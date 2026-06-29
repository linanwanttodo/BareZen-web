# ADR-0022: Exchange Request-Response Bridge

Status: accepted
Date: 2026-09-29
Decides: how a `com.sun.net.httpserver.HttpExchange` becomes a `Request` and how a `Response` is written back to one;
which properties of the wire are preserved, which are lost to the JDK's own parser, and which are refused on purpose;
the framing rules that make `Content-Length`, chunked and `HEAD` correct on this server; and the line of code no socket
test can reach
Related: ADR-0002, ADR-0018, ADR-0019, ADR-0020, ADR-0021, ADR-0023

## Context

Phase 2.9 ships one adapter, `barezen-http-jdk`, on `com.sun.net.httpserver`. ADR-0021 §3 froze the boundary that keeps
that package inside this module and out of every other one; ADR-0021 §4 named the two public types that face an
application (`JdkHttpServerAdapter` and `JdkHttpServerAdapterProvider`) and the package-private types that face the JDK
(`JdkExchangeBridge` and `JdkRequestDispatcher`). This record is about the first of those two internal types and the
claim it has to make good on.

The single question: **translate what `com.sun.net.httpserver` hands over into what the contract names, in both
directions, without decoding, normalising, reordering or inventing anything the client did not send and the handler did
not return.**

The reason that sentence is load-bearing is that the JDK's view of a request is not the contract's view. `URI.getPath()`
decodes; `URI.getRawPath()` does not. `com.sun.net.httpserver.Headers` is case-insensitive and canonicalises names to
first-upper-rest-lower; it is backed by a hash table, so field arrival order is gone before the bridge reads it.
`sendResponseHeaders(code, 0)` means chunked, not empty; `sendResponseHeaders(code, -1)` means `Content-Length: 0`. A
`HEAD` response has its body and `Content-Length` suppressed whatever is declared. Each of those is a measured fact
probed on JDK 17 and 21, and each is the reason the bridge is a class rather than three lines in the caller.

Two earlier decisions bind everything below. ADR-0018 §2 fixed `Request` as a raw record of what the client sent;
ADR-0019 §4 and §5 fixed the router as a raw-segment matcher that refuses to decode, normalise or fold. The bridge is
where those two promises meet the JDK, and the JDK does not keep them by default — so the bridge has to choose, method
call by method call, which half of the JDK's API preserves the contract's claim and which half breaks it.

## The question, in parts

1. What is the whole claim the bridge has to keep? (§1)
2. What happens at the opaque-target boundary, and why is one branch unreachable? (§2)
3. Why `getRawPath()` and not `getPath()`? (§3)
4. What is the query string's story? (§4)
5. What are the honest header limits, and how are they recorded? (§5)
6. What are the response framing rules? (§6)
7. What happens for `HEAD`? (§7)
8. What does the bridge refuse to do? (§8)
9. Why is the bridge package-private and final, and what is the one line no socket test reaches? (§9)

## Decision

`JdkExchangeBridge` is a package-private, final class with only static methods. `toRequest(HttpExchange)` reads an
exchange into a `Request`; `send(HttpExchange, boolean, Response)` writes a `Response` to one. The boolean is whether to
write the body — `false` for `HEAD`, decided by the caller because `Response` deliberately has no `noBody()` and the
adapter owns that rule (ADR-0018 §3). No other type in this module, and no application, calls either method.

```java
static Request toRequest(HttpExchange exchange) throws IOException {
  HttpMethod method = HttpMethod.of(exchange.getRequestMethod());
  URI target = exchange.getRequestURI();
  String rawPath = target.getRawPath();
  if (rawPath == null) {
    throw new IllegalArgumentException("the request target \"" + target + "\" carries no path; …");
  }
  Headers headers = toHeaders(exchange.getRequestHeaders());
  byte[] body = readBody(exchange);
  return new Request(method, rawPath, target.getRawQuery(), headers, body);
}

static void send(HttpExchange exchange, boolean writeBody, Response response) throws IOException {
  byte[] body = response.body();
  int declared = (writeBody && body.length > 0) ? body.length : -1;
  // … copy headers one value per add …
  exchange.sendResponseHeaders(response.status(), declared);
  if (!writeBody || body.length == 0) return;
  try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
}
```

Four helper methods — `toHeaders`, `notFound`, `methodNotAllowed`, `badRequest`, `internalServerError` — build the four
answers this adapter produces without an application (ADR-0023 §1, §2, §4, §5). `readBody` reads the whole body and
closes the stream, because an exchange whose body was not drained is a connection the JDK has to recover from.

### 1. The whole claim

A raw `Request` goes in, the bytes a handler returned come out, with no decoding, normalising or content negotiation
anywhere on the way.

`JdkHttpServerAdapterEndToEndTest` is the phase's acceptance proof of this claim, measured on the wire through
`RawHttp` rather than through `java.net.http.HttpClient`. The fixture exists because a Java HTTP client folds repeated
response fields, normalises field-name case, refuses some method tokens and will not send the request lines this phase
needs to send on purpose. Every assertion about a header's spelling, its order, or the existence of two `Set-Cookie`
lines is an assertion about the wire, and the wire is what the fixture reads.

The claim is bidirectional. On the way in, `RawHttp` sends a target, field lines and a body; the handler sees the same
target, the same field values and the same body bytes. On the way out, the handler returns a `Response`; the socket
reads back the same status, the same field values (modulo the JDK's own canonicalisation, §5) and the same body bytes.
`anEncodedSlashStaysOneSegmentAndIsNotDecoded`, `anEncodedDotSegmentIsNotResolved`, `aPlusInAPathIsAPlusAndNotASpace`,
`percentEscapesAreNotFoldedIntoTheCharacterTheyName`, `aQueryIsHandedOverRawAndAbsentStaysDistinctFromEmpty` and
`aBodyReadByTheHandlerIsTheBodyTheClientSent` are the request side; `theContentLengthIsTheBodyLengthInBytes`,
`twoValuesForOneNameLeaveAsTwoLines`, `twoSetCookieValuesLeaveAsTwoLines` and
`aResponseWrittenToADeliberatelyWideBodyStillArrivesWithAMatchingLength` are the response side.

What the bridge does not do is as important as what it does. It does not decode percent-escapes, resolve dot-segments,
redirect a trailing slash, split cookies, read `Forwarded`, or impose a body-size limit. The first five would change
what the client asked for; the last is recorded as this phase's design debt rather than patched by an invented policy
(§8). The bridge is a translation, not a policy.

### 2. The opaque-target boundary

`URI.getRawPath()` returns `null` only for an opaque target — a request line such as `GET urn:x:things HTTP/1.1` that
names a resource by scheme-specific part rather than by path. For every target that begins with `/`, including every
target this adapter will ever be asked to serve, `getRawPath()` returns the raw path text.

The JDK refuses a bare `%2F`-style target before an exchange reaches a handler. Probed: sending `GET %2F HTTP/1.1`
through `RawHttp` reaches the adapter as a raw path with no leading `/`, which `Request`'s own validation refuses with
`IllegalArgumentException` — the reachable case of the bridge's boundary check. The JDK's own parser, not this adapter,
decides what an opaque target is; the bridge's `rawPath == null` branch is therefore a boundary check with no reachable
input from a socket. `JdkExchangeBridge.toRequest` records this explicitly:

```java
if (rawPath == null) {
  // An opaque request target ("urn:x") carries no path at all. Probed behaviour is that the JDK refuses one before
  // an exchange reaches a handler (ADR-0022 §2), so this line is a boundary check rather than a branch with a known
  // input; ADR-0022 §9 records it as the module's one line a socket test cannot reach.
  throw new IllegalArgumentException(…);
}
```

`JdkErrorSemanticsTest.aTargetTheContractRefusesIsABadRequestAndNotAServerError` asserts the reachable half: the
adapter answers 400, the body is `Bad Request`, and the refused target is not echoed. The unreachable half — an opaque
target actually reaching the bridge — is recorded in §9 as the module's one line a socket test cannot cover.

### 3. The probe that chose `getRawPath()` over `getPath()`

`java.net.URI` decodes on the way to `getPath()`. The probe in this section sent `/users/a%2Fb` and read back
`path=[/users/a/b]` beside `rawPath=[/users/a%2Fb]`. Choosing the decoded half routes an encoded slash as two segments
and reaches a different resource than the one the client named, which breaks ADR-0019 §4 and §5 raw-segment semantics.

`anEncodedSlashStaysOneSegmentAndIsNotDecoded` is the socket-level proof. The test registers `/users/{id}` and
`/users/{a}/{b}`; a bridge that used `getPath()` would see `/users/a/b`, match the two-parameter route, and answer
`two:{a=a, b=b}`. Instead the answer is `200` with body `a%2Fb` — one segment, captured as the raw text, matching the
one-parameter route. The comment in the test names the failure: «probe D in ADR-0022 §9».

Three smaller consequences of the same line, each asserted by its own test:

- **`/users/%2e%2e/admin` stays three segments.** `anEncodedDotSegmentIsNotResolved` captures `%2e%2e` as the second
  parameter; `URI.normalize()` would fold it to `/admin` before routing, which is the difference between serving a
  resource and serving a traversal attempt.
- **`+` in a path is a plus sign, not a space.** `aPlusInAPathIsAPlusAndNotASpace` captures `a+b`; `+`-for-space is a
  form-encoding convention for query strings and has never applied to paths.
- **`%41BC` is not `ABC`.** `percentEscapesAreNotFoldedIntoTheCharacterTheyName` registers `/users/ABC` as a literal
  route and `/users/{id}` as a parameter route; `/users/%41BC` reaches the parameter route with the captured value
  `%41BC`, because raw matching does not fold the escape into the character it names.

### 4. Raw query, and absent stays distinct from empty

`getRawQuery()` returns `null` for `/x` and `""` for `/x?`. The bridge hands both to `Request`'s constructor, which
stores the raw text and expresses the difference as `Optional<String>` through `query()`. Absent and empty are different
requests: `/search` gives an empty `Optional` while `/search?` gives `Optional.of("")`. A handler that treats those as
one is a handler that cannot tell «no filter given» from «filtered by nothing».

`aQueryIsHandedOverRawAndAbsentStaysDistinctFromEmpty` asserts all three cases on the wire: `/users?id=42&name=a+b`
returns the raw query `id=42&name=a+b` (plus preserved, not decoded to space); `/users?` returns the empty string;
`/users` returns `<none>`, the handler's marker for absence.

Parsing the query into a map is refused. A query parameter map is a parse of one field with rules of its own — repeated
keys, `+` for space, charset, `;` as a separator, whether `a=1&a=2` is one value or two — and each of those is a
decision that would be forced on every application by putting it in this type. It belongs to a small helper with the
first application that needs to read a parameter, where the rules can be stated and overridden (ADR-0018 §2). No
`Query` API exists in this phase.

### 5. The honest header limits

`com.sun.net.httpserver.Headers` is case-insensitive and canonicalises each name to first-upper-rest-lower. A client
that sent `X-mixedCase` arrives as `X-mixedcase`. The map is hash-backed, so the order the field lines arrived in is
already gone before the bridge reads it. `Headers.names()` therefore reports the JDK's order — `Accept-encoding` before
`X-mixedcase` — while the request sent `X-mixedCase` first.

Three properties survive: case-insensitive matching, per-name value order, and empty-vs-absent. The bridge does not
break any of them, and the two it cannot preserve are recorded as limitations rather than claimed as behaviour.

**Case-insensitive matching.** `aNameSentInAnotherCaseIsFoundByAnyCase` sends `x-mixedCase: value` and reads it back
through `first("X-MixedCase")`, `first("X-mixedcase")` and `first("X-MIXEDCASE")`; all three return `"value"`. The
spelling the client used is not a condition for finding the value, which is the property a handler actually depends on.

**Per-name value order.** `everyValueOfARepeatedFieldArrivesInOrder` sends three `Accept` lines and reads back three
values in the order sent: `text/html`, `application/json`, `text/plain`. Nothing was folded into one comma-joined value
and nothing was dropped.

**Empty-vs-absent.** `anEmptyValueIsPresentAndDistinctFromAnAbsentField` sends `X-Empty:` with nothing after the colon;
`values("X-Empty")` returns `[""]` while `values("X-Never-Sent")` returns `[]`. A bridge that skipped empty values would
make the first field indistinguishable from the second.

**The two limitations, asserted so they stay visible.** `theNameArrivesCanonicalizedRatherThanAsTheClientSpelledIt`
sends `X-mixedCase` and asserts that `names()` contains `X-mixedcase` and does not contain `X-mixedCase`. HTTP field
names are case-insensitive so no correct client is harmed, but the phase forbids claiming full preservation, and this is
the half that is lost. `theOrderTheFieldsArrivedInIsNotTheOrderTheHandlerSeesThemIn` sends `X-mixedCase`,
`Accept-Encoding`, `Z-Last` in that order and asserts that `X-mixedcase` appears after `Accept-encoding` in the list
the handler sees. Nothing in this contract depends on request header order — HTTP defines no meaning for it — so this
is recorded rather than worked around.

Both limitation tests are written as assertions rather than comments because an unasserted limitation is a claim that a
later reader cannot check: if the JDK ever starts preserving field order and name case, these tests fail and the ADR
has to be amended, which is the correct outcome for a documented restriction.

The same canonicalisation happens on the way out. `theResponseNameCaseIsCanonicalizedAndTheOrderIsTheJdks` writes
`X-MixedCase` from a handler and reads `X-mixedcase` on the wire. Values are untouched; only names are canonicalised.

### 6. Response framing rules

`sendResponseHeaders(code, length)` with `length > 0` writes that exact `Content-Length` and throws
`IOException: insufficient bytes written to stream` if fewer bytes arrive. The length passed is always `body.length` and
the array written is that same array — the two cannot disagree, because `Response` copies its body and nothing re-reads
it in between. `theContentLengthIsTheBodyLengthInBytes` sends `héllo` (six UTF-8 bytes, five characters) and asserts
`Content-Length: 6` beside a six-byte body; `aResponseWrittenToADeliberatelyWideBodyStillArrivesWithAMatchingLength`
sends 70,000 bytes and asserts `Content-Length: 70000` beside a 70,000-byte body. The declared length and the written
bytes are the same value, and the JDK's own exception is the proof that a mismatch cannot silently succeed.

`length == 0` means *chunked* to this JDK, not «empty body». Probed: a 200 with `sendResponseHeaders(200, 0)` sent
`Transfer-encoding: chunked`. So an empty body is declared as `-1`, which writes `Content-Length: 0` and no body.
`anEmptyBodyIsDeclaredAsZeroBytesAndNotChunked` asserts the consequence: `Content-Length: 0`, no `Transfer-Encoding`
field, empty body. A hand-rolled chunked response would arrive with a transfer-encoding field instead.

The bridge therefore computes:

```java
int declared = (writeBody && body.length > 0) ? body.length : -1;
```

Two conditions collapse to `-1`: a `HEAD` request (`writeBody` is false, §7), and an empty or null body (`body.length`
is zero). Both write `Content-Length: 0` — or, for `HEAD`, no `Content-Length` at all, because the JDK suppresses it
(§7).

`aNullBodyIsAlsoZeroBytes` sends a 204 with a null body; the JDK forbids a body for 204 and writes no `Content-Length`
at all; the adapter's `-1` declaration is what keeps that legal rather than throwing.

Duplicate values leave as separate lines. `twoValuesForOneNameLeaveAsTwoLines` builds a response with two `X-Test`
values and asserts two lines on the wire, `countOf("X-Test") == 2`. `twoSetCookieValuesLeaveAsTwoLines` sends two
`Set-Cookie` values and asserts two lines — the case that a folded header actually breaks, since a cookie value can
legally contain a comma. A bridge that joined values with `, ` would have produced one line that a `Set-Cookie` cannot
legally be.

Response names are canonicalised and re-ordered by the JDK on the way out, which is recorded with the same honesty as
the request side (§5). No hand-rolled chunked encoder exists here; the bridge writes a complete body against a declared
`Content-Length` and lets the JDK handle the framing.

### 7. HEAD

`writesBody` is `false` for `HEAD`, decided by `JdkRequestDispatcher.writesBody(Request)` because `Response` has no
`noBody()` and the adapter owns the rule (ADR-0018 §3). The JDK suppresses both the body and `Content-Length` of a HEAD
response whatever it is handed, and logs `WARNING: being invoked with a content length for a HEAD request` when a
positive length is declared. Writing the body anyway would produce exactly the bytes the JDK then discards.

`aHeadResponseHasFieldsAndNoBodyAndNoLength` asserts the consequence: a HEAD route returns a 200 with `Content-Type`,
`ETag` and a body; the client reads the status and the fields, an empty body, and — the recorded limitation — no
`Content-Length` at all. A HEAD client therefore cannot learn a representation's size through this adapter.

This is recorded as a limitation rather than as working behaviour. A real fix belongs with a server that implements
HEAD's semantics rather than in a bridge that hides the JDK's. The adapter's half is correct: it declares no body and
writes nothing, which is the behaviour HTTP's HEAD needs for correctness of framing. What it cannot provide is the size
discovery that HEAD is supposed to offer.

A HEAD route must be registered explicitly or the answer is 405. `aRegisteredHeadIsAnsweredWithoutABody` registers both
`GET /doc` and `HEAD /doc`; a HEAD request answers 200 with an empty body. The router matches on the method the client
sent, so a HEAD request is not served by a GET registration (ADR-0019 §2).

### 8. What the bridge refuses to do

The bridge does not percent-decode, does not resolve dot-segments, does not redirect a trailing slash, does not split
cookies, does not read `Forwarded`, and does not impose a request body size limit. Each refusal is a decision, not an
omission.

**No percent-decoding.** The contract is raw (ADR-0018 §2, ADR-0019 §4, §5). Decoding is a decision about one resource,
made by whoever owns it, not by the object that carries the bytes. §3 is the socket-level proof.

**No dot-segment resolution.** `URI.normalize()` folds `/a%2Fb` to `/a/b` and turns `/%2e%2e/x` into `/../x` — both
probed directly, which is why the contract keeps raw text in the first place. A bridge that normalised would resolve a
traversal attempt on the framework's behalf and hand the handler a path that no longer contains the bytes the client
sent. §3 is the proof.

**No trailing-slash redirect.** `/users` does not match `/users/`, and the answer is 404 rather than 301. A redirect is
a policy this contract has no type for; ADR-0019 §6 decided exact semantics, and the bridge inherits the decision.
`aTrailingSlashIsADifferentResourceAndNeitherRedirects` asserts the 404 on the wire.

**No cookie splitting.** The bridge copies field lines one value at a time; it does not parse `Set-Cookie`, does not
join values, and does not read `Cookie`. No cookie logic lives here — the fields are opaque to this module, which is why
a cookie phase can add meaning without changing the bridge. `twoSetCookieValuesLeaveAsTwoLines` is the proof.

**No `Forwarded` handling.** The bridge copies headers; it does not read `Forwarded`, `X-Forwarded-For`, or any other
proxy field. A proxy-aware adapter would read them; this one does not, and the reason is that reading them is a policy
about trust and about which field to believe, which is a decision with no consumer in this phase.

**No request body size limit.** The bridge reads the whole body into a `byte[]` with `readAllBytes()`, which is
unbounded. This is the phase's design debt: an unbounded read into memory is a denial of service, and the reason it is
recorded as debt rather than patched is that ADR-0018 §7 already accepted this as a memory posture — the whole body is
in memory, and a body too large for that is the case a streaming contract is written for. A limit belongs to an adapter,
which is the layer that knows what it can afford to read; this phase's adapter does not yet impose one, and the debt is
stated rather than hidden.

### 9. Package-private bridge and dispatcher, final, not extension points

`JdkExchangeBridge` is package-private and final. `JdkRequestDispatcher` is package-private and final. Neither is an
API an application calls; neither is an extension point an application overrides. `BarezenHttpJdkArchitectureTest
.the_bridge_and_the_dispatcher_are_not_extension_points` asserts both properties: package-private and final together,
because a package-private but open class would still be subclassable by the next file added to this package, which is
how a dispatcher becomes an override point for an error format.

An application that wants different error text writes a `Handler`; an application that wants a different server writes
an adapter. The bridge and the dispatcher are the JDK side of a boundary whose *shape* is already public — `Request`'s
and `Response`'s constructors. Making the bridge public would offer a second way to build a request, one that bypasses
the contract's own validation, which is what ADR-0018 §2 refuses, and a second way to write a response, which is what
ADR-0018 §3 refuses.

The one line no socket test reaches is the `rawPath == null` branch in `toRequest`. An opaque target such as
`GET urn:x:things HTTP/1.1` is the only input that reaches it, and the JDK refuses such a target before an exchange is
created — probed: `OPTIONS *` is answered by the JDK itself with a 404 and the text `No context found for request`,
and the exchange never reaches `JdkRequestDispatcher` (ADR-0023 §9). The branch is a boundary check, not a reachable
path, and it is recorded here as the module's one line a socket test cannot cover.

## Consequences

- **The bridge is a translation, not a policy.** It preserves what the client sent and what the handler returned, and
  the two limitations it cannot preserve (name case, field order) are recorded as limitations rather than claimed as
  behaviour.
- **The router's raw-segment semantics depend on one method call.** `getRawPath()` rather than `getPath()` is the
  difference between `/users/a%2Fb` matching `/users/{id}` with one captured segment and matching `/users/{a}/{b}` with
  two. The probe is in §3; the test is `anEncodedSlashStaysOneSegmentAndIsNotDecoded`.
- **The response framing is the JDK's, measured rather than assumed.** `sendResponseHeaders(code, 0)` means chunked;
  `-1` means `Content-Length: 0`; a positive length must match the body or the JDK throws. The bridge computes
  `declared` from those rules and the tests assert the consequence on the wire.
- **HEAD is a limitation, not a feature.** A HEAD client cannot learn a representation's size through this adapter. The
  fix belongs with a server that implements HEAD's semantics, not with a bridge that hides the JDK's.
- **The body-size limit is design debt.** An unbounded `readAllBytes()` into a `byte[]` is the cost ADR-0018 §7 accepted;
  this phase records it as debt rather than inventing a limit with no consumer.
- **The bridge and dispatcher are not extension points.** Package-private and final, because an application that wants
  different error text writes a `Handler` and an application that wants a different server writes an adapter.

## Alternatives rejected

- **Using `getPath()` instead of `getRawPath()`.** Refused in §3. The decoded form routes an encoded slash as two
  segments and breaks the router's raw-segment semantics.
- **Parsing the query into a map.** Refused in §4. A query parameter map is a parse with rules of its own, and each
  rule is a decision forced on every application by putting it in the bridge.
- **Working around the JDK's header canonicalisation.** Refused in §5. The bridge cannot preserve what the JDK has
  already lost, and a workaround would be a second parse of the request line — the same drift the raw-segment rule
  refuses.
- **Joining repeated response values with `, `.** Refused in §6. A `Set-Cookie` value can legally contain a comma;
  joining values would produce one line that the field cannot legally be.
- **Imposing a body-size limit in the bridge.** Refused in §8. A limit is a policy with no consumer in this phase;
  ADR-0018 §7 accepted the memory posture, and the debt is stated rather than patched.

## Evidence

| test class | what it proves |
| --- | --- |
| `JdkHttpServerAdapterEndToEndTest` | the raw target survives the trip: encoded slash, encoded dot-segment, plus, percent escapes, trailing slash, raw query, absent-vs-empty (§1, §2, §3, §4) |
| `JdkHeaderAndBodyTest` | header limits on the way in and the way out: case-insensitive matching, value order, empty-vs-absent, canonicalisation, field order, repeated values, `Set-Cookie`, `Content-Length`, empty body, null body, HEAD, wide body (§5, §6, §7) |
| `JdkErrorSemanticsTest` | the four statuses this adapter produces without an application, the truncated body, the refused target, the JDK's own parser, the committed response (ADR-0023, and §2, §5, §8) |
| `JdkHttpServerConcurrencyTest` | 1600 concurrent requests each get their own parameter, body and header back; status codes stay attached to their own requests (§1, under concurrency) |
| `BarezenHttpJdkArchitectureTest` | the bridge and dispatcher are package-private and final; the JDK types never appear in a public signature; the module exposes exactly two public types (§9) |
