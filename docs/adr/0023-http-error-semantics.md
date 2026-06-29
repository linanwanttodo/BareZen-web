# ADR-0023: HTTP Error Semantics

Status: accepted
Date: 2026-09-29
Decides: which statuses this adapter produces without an application, who decides each, what the bodies carry and what
they refuse to carry, how a handler failure reaches the log and not the socket, how a committed response stays one
response, and what this module does not answer because the JDK answers it first
Related: ADR-0001, ADR-0005, ADR-0018, ADR-0019, ADR-0020, ADR-0021, ADR-0022

## Context

Phase 2.9 ships one adapter, `barezen-http-jdk`, on `com.sun.net.httpserver`. ADR-0022 decided how an exchange becomes
a `Request` and how a `Response` is written back; this record decides what the adapter writes when there is no
`Response` to write — when the route table has no answer, when the handler throws, when the client sent something the
contract cannot read, and when the response stops reaching the client before it is finished.

The single question: **which statuses can this adapter produce without an application, who decides each, and what does
the client learn from the body?**

The reason the question is not «what error format» is that an error format is a policy, and a policy is a decision with
a consumer. This phase has no error-format consumer: no `GlobalExceptionHandler`, no `Problem` type, no JSON envelope,
no `ErrorCode`. What it has is an adapter that writes bytes to a socket and has to decide, on each failure path, what
those bytes are. The decision is made locally, in the component that holds the socket, and the reason it is made there
is that ADR-0019 §10 pointed at this component and said «the rules about errors belong there».

Two earlier decisions bind everything below. ADR-0018 §9 refused a request attribute bag and an exception taxonomy;
ADR-0019 §10 refused a router that builds a `Response` and pointed the error decision at the adapter. This phase
inherits both refusals and makes them concrete: four statuses, four bodies, four decision paths, and no type in the
contract that names any of them.

## The question, in parts

1. What are the four answers this adapter can produce without an application? (§1)
2. What is the 404 body? (§2)
3. What is 405 and `Allow`? (§3)
4. What is the 500 policy? (§4)
5. What are the 400 cases? (§5)
6. What is commitment? (§6)
7. What is malformed HTTP? (§7)
8. What is the internal error type? (§8)
9. What is `OPTIONS *`? (§9)

## Decision

`JdkRequestDispatcher` is a package-private, final class that implements `com.sun.net.httpserver.HttpHandler`. It holds
one field — the `Router` — and one method that matters: `handle(HttpExchange)`. The method reads a request, routes it,
calls the handler, writes the answer, and decides the status when there is no handler to call. Every status it can
produce is one of five: 200..599 from a handler, 404, 405, 500, or 400. The first is the handler's; the other four are
the adapter's.

```java
public void handle(HttpExchange exchange) {
  try {
    Request request = read(exchange);
    if (request == null) return;
    route(exchange, request);
  } finally {
    exchange.close();
  }
}

private Request read(HttpExchange exchange) {
  try {
    return JdkExchangeBridge.toRequest(exchange);
  } catch (IllegalArgumentException unusableTarget) {
    log.debug("refusing a request target the contract cannot name: {}", unusableTarget.getMessage());
    respond(exchange, true, JdkExchangeBridge.badRequest());
    return null;
  } catch (IOException bodyNeverArrived) {
    log.debug("the request body did not arrive: {}", bodyNeverArrived.getMessage());
    respond(exchange, true, JdkExchangeBridge.badRequest());
    return null;
  }
}

private void route(HttpExchange exchange, Request request) {
  Optional<RouteMatch> found = router.match(request);
  if (found.isEmpty()) {
    Set<HttpMethod> allowed = router.methodsFor(request.path());
    respond(exchange, writesBody(request), allowed.isEmpty()
        ? JdkExchangeBridge.notFound()
        : JdkExchangeBridge.methodNotAllowed(allowValue(allowed)));
    return;
  }
  RouteMatch match = found.get();
  Response response;
  try {
    response = match.handler().handle(request, match.parameters());
  } catch (RuntimeException handlerFailure) {
    log.error("handler failed for {} {}", request.method().text(), request.path(), handlerFailure);
    response = JdkExchangeBridge.internalServerError();
  }
  respond(exchange, writesBody(request), response);
}
```

Four helper methods in `JdkExchangeBridge` build the four answers: `notFound()`, `methodNotAllowed(String)`,
`badRequest()`, `internalServerError()`. Each returns a `Response` with a status, a `Content-Type` of
`text/plain; charset=utf-8`, and a body that is the status text and nothing else. `methodNotAllowed` is the one that
carries a field as well as a body: `Allow`, with the methods the route table named, in the order the adapter declares.

### 1. The four answers, and who decides each

The adapter can produce four statuses without an application: 400, 404, 405, 500. Each is decided by a different
component, and the reason the four are separate is that each names a different party at fault.

**400** is the client's failure to say anything readable. The target is one the contract refuses, or the body never
arrived. Both happen before anything was written, so a status is still available to send. The adapter reports the
client's mistake rather than its own.

**404** is the route table's answer to «nothing lives at this path». `Router.match(Request)` is empty and
`Router.methodsFor(String)` is empty: no route answers this method and no route answers this path. The adapter writes
the status and a body that names neither the path nor a route.

**405** is the route table's answer to «something lives here, but not for this method». `Router.match(Request)` is empty
but `Router.methodsFor(String)` is not: a route answers this path with a different method. The adapter writes the status,
a body, and an `Allow` field listing the methods that would have answered.

**500** is the server's own failure. The handler threw a `RuntimeException`. The adapter logs the exception with the
throwable and writes a body that is the status text and nothing else: no message, no class name, no stack trace, no
internal path, no environment, no request header. The exception goes to the log, where a developer reads it, and not to
the socket, where a client would.

`JdkErrorSemanticsTest` is the phase's acceptance proof of these four answers, asserted on the wire. The socket is the
only way to test any of this: `HttpExchange` is final in `com.sun.net.httpserver`, its `getPrincipal()` cannot be
overridden from outside the module, and there is no setter for response headers. There is no fake exchange in this suite
because there is no way to build one.

Each test names which party failed, because that is the whole design of this section: a 404 and a 405 are the client
asking for something the route table does not hold, a 400 is the client sending something the contract cannot even read,
a 500 is the server's own failure, and an abandoned response is nobody's failure at all. Grouping them into one
`catch (Exception)` would have flattened exactly that distinction, so the tests are written to be unable to pass if it
comes back.

### 2. The 404 body

The body is the status text and nothing else: `Not Found`, as `text/plain; charset=utf-8`. It names neither the path nor
a route.

`aPathWithNoRouteAtAllIsANotFound` sends `GET /does-not-exist` to a server that registered only `/users/{id}`; the
answer is 404, the body is `Not Found`, and the body does not contain `does-not-exist`. A 404 that echoes the target
tells a probe which paths the server distinguishes, so the request line appears nowhere in the answer.

`aTrailingSlashThatIsNotRegisteredIsANotFoundRatherThanARedirect` sends `GET /users/` to a server that registered
`/users`; the answer is 404, not 301. A redirect is a policy this contract has no type for, and 404 is the route table's
honest answer: nothing is registered at three segments (ADR-0019 §6). The body does not contain `/users/`, for the same
reason the 404 body does not contain the target.

Both the empty-match case (no route answers this path at all) and the empty-`methodsFor` case (a trailing slash that
was never registered) answer 404 rather than redirecting. The router's exact semantics (ADR-0019 §6) are the reason:
`/users` and `/users/` are different routes, and a redirect is a policy the contract has no type for. The adapter
inherits the decision and writes a body that names neither.

### 3. 405 and `Allow`

`Router.methodsFor(String)` returns the methods in registration order, as an unmodifiable set. The adapter joins them
with `, ` in one field line, in an order the adapter declares rather than the order the route table returned.

`JdkRequestDispatcher` holds a private static list, `ALLOW_ORDER`, that names the nine methods the contract knows in
the order HTTP names them: `GET`, `HEAD`, `POST`, `PUT`, `DELETE`, `CONNECT`, `OPTIONS`, `TRACE`, `PATCH`. The adapter
walks this list and adds each method that is in the allowed set; a method the contract does not name follows, sorted by
text. The result is stable by construction rather than by registration, so two identical 405s never differ.

`allowListsEveryRegisteredMethodInOneLine` registers `DELETE`, `POST`, `GET`, `PUT` in that order and sends `PATCH`;
the answer is 405 with `Allow: GET, POST, PUT, DELETE`. The order is the HTTP order, not the registration order and not
the hash order. `twoIdentical405sAlwaysCarryTheSameAllowValue` sends the same request twice and asserts the same
`Allow` value both times. `aMethodTheContractDoesNotNameStillAppearsInAllow` registers `PURGE` and `GET`; the answer is
405 with `Allow: GET, PURGE` — the known method first, in HTTP order, and the unknown method after, sorted by text.

`Allow` is a stable value across identical requests but explicitly adapter-local, not sorted and not server-authoritative.
The reason `Router` gets no public ordering API for it is that the order is a fact about how an application was
assembled rather than a fact about HTTP, and a later adapter may sort differently without asking the router to change.
The adapter's order is the adapter's; the router's order is the router's; and the two are not the same.

An unknown method token reaches the dispatcher and answers 405 with the registered set, which proves the set comes from
the route table rather than the request. `HttpMethod.of("PURGE")` is a legal method; a route registered under it is a
legal route; a request that asks for it with a different method is a legal 405. The adapter does not refuse the method;
it refuses the combination of method and path, and the `Allow` field names the methods that would have answered.

### 4. The 500 policy

A handler's `RuntimeException` is logged via SLF4J at `ERROR` with the throwable, and the socket gets `Internal Server
Error` only — no message, no class name, no stack trace, no internal path, no environment, no request headers.

`aThrowingHandlerBecomesAServerErrorWhoseBodyHoldsNoneOfTheFailure` sends `GET /boom` to a handler that throws
`IllegalStateException("secret")`; the answer is 500, the body is `Internal Server Error`, and the body does not contain
`secret`, `IllegalStateException`, `at ` (a stack frame), `/barezen` (an internal path), `user.dir` (an environment
value), or `X-Diagnostic` (a request header the client sent). The assertion is written as one assertion per entry in the
directive's list, and each is the difference between a body that helps a developer and a body that helps an attacker.

`theHandlerFailureIsRecordedWithItsThrowableInTheLog` is the other half of the claim: what the socket does not carry,
the log must. The test uses `CapturedLogs` to record the `ERROR` event from `JdkRequestDispatcher` and asserts that the
event's `getThrowableProxy().getClassName()` is `java.lang.IllegalStateException` and that
`getThrowableProxy().getMessage()` is `secret`. Without this test the non-leaking 500 would be indistinguishable from a
swallowed exception.

A handler that returns 503 itself is written as returned with no `ERROR` log. `aHandlerThatReturnsAnErrorStatusIsWritten
AsReturned` sends `GET /down` to a handler that returns `Response(503, …)`; the answer is 503, the body is `down`, and
the log has no `ERROR` events. A 503 chosen by the application is not a handler failure and must not be replaced or
logged as one.

`oneFailingRequestDoesNotDisturbTheNextOne` sends two requests to a handler that throws on the first and answers on the
second; the first is 500, the second is 200, and the adapter is still running. A handler failure is a per-request
failure, not a server failure, and the server keeps serving.

### 5. The 400 cases

Two failures are possible before anything was written, and both are the client's:

**A target the contract itself refuses.** `Request`'s constructor refuses a path that does not begin with `/` and is not
`*`. The JDK hands over a raw path with no leading slash for a bare `%2F`-style target — probed: sending `GET %2F
HTTP/1.1` through `RawHttp` reaches the adapter as a raw path with no leading `/`, which `Request`'s own validation
refuses with `IllegalArgumentException`. The adapter catches the exception, logs at `DEBUG`, and answers 400.
`aTargetTheContractRefusesIsABadRequestAndNotAServerError` asserts the consequence: the answer is 400, the body is
`Bad Request`, and the refused target is not echoed. A 400 body that repeats it back is a reflection of the request
rather than an answer about it.

**A request body that stops arriving.** An `IOException` while reading the body is the client's failure: the client
declared a `Content-Length` and sent fewer bytes. The adapter catches the exception, logs at `DEBUG`, and answers 400.
`aRequestBodyThatStopsArrivingIsABadRequestBecauseNothingWasCommittedYet` sends a request with `Content-Length: 10` and
a 3-byte body; the answer is 400, the body is `Bad Request`. The distinction probes E and F exist to protect: this
failure happens while the request is being read, so a status is still available to send. A blanket catch that answered
500 here would blame the server for a body the client never finished.

Both happen before anything was committed, so the client's mistake is never answered with a server-failure status.
`aTruncatedRequestIsAnsweredWithoutEverReachingTheHandler` sends a truncated request and asserts that no handler ran:
the 400 cannot have come from application code, it is the bridge's answer. `theServerStillAnswersAfterARefusedRequest`
sends a refused request and then a valid one; the second is 200, and the adapter is still running. A refused request is
a per-request failure, not a server failure.

### 6. Commitment

Three distinguishable failures, and the reason the catch blocks are asymmetric:

**Request-side I/O.** A truncated body is an `IOException` while reading, before anything was written. The adapter
answers 400. §5 is the proof.

**Response-side I/O after `sendResponseHeaders`.** A client that hangs up mid-write is an `IOException` while writing,
after the status line has left. The response is committed and no other status can follow. The adapter logs once at
`DEBUG` — «the response did not reach the client: …» — and never attempts a second status. A blanket `catch (Exception)
→ 500` would try to send a second status on a committed exchange, where the JDK answers `IOException: headers already
sent`, and the original failure would be lost behind a second exception nobody asked for.

`aClientThatHangsUpMidWriteLogsOnceAndNeverProducesASecondStatus` sends a 4 MB body and uses `RawHttp.readThenReset` to
read 16 bytes and then close the socket with `SO_LINGER` of zero, which turns the close into a TCP reset rather than an
orderly FIN. The test asserts that the log contains exactly one `DEBUG` event with the message «the response did not
reach the client», that the log does not contain «headers already sent», that the log has no `ERROR` events, and that
the adapter is still running. The evidence is in the log: exactly one abandoned-response line at `DEBUG`, and no error
— an attempt to answer a committed exchange would have logged or thrown a second failure.

`theServerKeepsServingAfterAResponseWasAbandoned` sends a 4 MB body, reads 8 bytes, and hangs up; the server then
answers `GET /users` with 200. A committed response that stopped reaching the client stays one response, and the server
keeps serving.

**A client RST mid-write.** The same test, probed with a 4 MB body and `setSoLinger(true, 0)`. The reset lands while
the body is still being written, and the adapter's response is the same: one `DEBUG` log, no second status, server
still running.

### 7. Malformed HTTP is the JDK's problem

An illegal header name and a malformed request line never reach the adapter. The JDK writes its own 400 HTML, and the
adapter therefore implements no parser.

`anIllegalHeaderNameNeverReachesTheAdapter` sends `Bad Header Name` as a header name; the answer is 400 with the JDK's
own HTML body, `<h1>400 Bad Request</h1>`. A header name with a space in it is refused by `sun.net.httpserver` before
an exchange exists: the answer arrives with the JDK's HTML body and no `Date` header, which is what distinguishes it
from a response this module wrote. The phase's rule is not to re-implement a parser, so the adapter neither sees nor
improves on this.

`aMalformedRequestLineNeverReachesTheAdapter` sends `garbage line\r\n…` as raw bytes; the answer is 400 with the JDK's
own text, `Bad request line`. The JDK's own parser refuses a malformed request line before an exchange is created.

`aChunkedRequestBodyIsHandledByTheJdkAndGivenToTheContractAsBytes` sends a chunked request body with
`Transfer-Encoding: chunked`; the answer is 200 with the decoded body. Chunked framing is a transfer coding the JDK
decodes; the adapter reads a complete body either way, which is the evidence that this module owns no parser.

The design-debt pointer is that this means the adapter's error surface is only reachable for what the JDK accepts. A
malformed request that the JDK refuses never reaches the adapter, so the adapter cannot answer it with a BareZen-shaped
400. The adapter's 400 cases are the ones the JDK accepts and the contract refuses: a target with no leading slash, and
a body that stops arriving. The rest are the JDK's.

### 8. The internal error type

No `HttpProblem`, no exception taxonomy, no `ErrorCode` is added to the contract. Errors are responses, and only this
module decides them.

ADR-0018 §9 refused a request attribute bag and an exception taxonomy; ADR-0019 §10 refused a router that builds a
`Response`. This phase inherits both refusals and makes them concrete: the adapter writes bytes to a socket, and the
bytes are a status, a body, and sometimes a field. No type in the contract names any of them, and no type in the
contract decides any of them.

An application that wants a different error policy is the reason a second adapter can exist. The adapter is the
component that holds the socket, and the component that holds the socket is the component that decides what the socket
says. An application that wants a JSON error envelope writes a handler that returns a JSON error envelope; an
application that wants a different server writes an adapter. The adapter's error policy is the adapter's; the contract's
error policy is that there is no contract error policy.

### 9. `OPTIONS *` design debt

`OPTIONS *` is the request line that names the server rather than a resource. The JDK answers `OPTIONS *` and `GET *`
itself with a 404 and logs `No context found for request`, so the exchange never reaches `JdkRequestDispatcher` and the
adapter cannot honour or refuse a server-wide `OPTIONS`.

`optionsStarIsAnsweredByTheJdkBeforeTheAdapterSeesIt` sends `OPTIONS *` to a server that registered `OPTIONS /users`;
the answer is 404 with the JDK's own text, `No context found for request`, and the handler's counter is zero. The
assertion is written against what the server actually does, which is a 404 with the JDK's own HTML body — not this
adapter's `Not Found`. That difference is the evidence: a response this module did not write arrived, which is only
possible if the request never got here.

`aRequestForTheAsteriskTargetIsNotSilentlyTurnedIntoADifferentPath` sends `GET *` to a server that registered
`GET /users`; the answer is 404, and the body does not contain `users`. The same finding restated from the contract's
side: no request line in this test family produced a handler call for `*`, and nothing here invents an `Allow` list for
the server as a whole.

`Router` cannot express it. Its `"*"` pattern is a path pattern; `match()` short-circuits `"*"` to empty and
`methodsFor("*")` returns an empty set. A fix needs a Router-contract decision — an explicit server-star concept, or an
adapter that registers a synthetic context — and is left as recorded debt. The minimal adapter-local workaround is
stated: the `*`-target test proves the JDK, not this adapter, answers it, so nothing in the contract is misrepresented.
A later phase that wants `OPTIONS *` answered has to change how requests reach the dispatcher, not just add a route.

## Consequences

- **The four statuses are four decisions, not one.** Each names a different party at fault, and the bodies are written
  to help a developer rather than a probe. A 404 does not echo the target; a 500 does not echo the exception; a 400 does
  not echo the refused target.
- **The 500 policy is a security statement.** The body is the status text and nothing else, because a body that carries
  the exception message, the class name, a stack frame, an internal path, an environment value or a request header is a
  body that helps an attacker. The exception goes to the log, where a developer reads it, and not to the socket, where a
  client would.
- **Commitment is the reason the catch blocks are asymmetric.** A failure before the first byte is a 400; a failure
  after the first byte is a `DEBUG` log and no second status. A blanket `catch (Exception) → 500` would try to send a
  second status on a committed exchange, and the original failure would be lost behind a second exception nobody asked
  for.
- **Malformed HTTP is the JDK's problem.** The adapter implements no parser; the JDK refuses a malformed request line
  and an illegal header name before an exchange is created. The adapter's error surface is only reachable for what the
  JDK accepts.
- **No error type is added to the contract.** Errors are responses, and only this module decides them. An application
  that wants a different error policy writes a handler; an application that wants a different server writes an adapter.
- **`OPTIONS *` is design debt.** The JDK answers it before the adapter sees it, and `Router` cannot express it. A fix
  needs a Router-contract decision, and the debt is recorded rather than silently fixed.

## Alternatives rejected

- **A global error handler.** Refused in §8. A global error handler is a policy with no consumer in this phase, and the
  adapter is the component that holds the socket. An application that wants a different error policy writes a handler;
  an application that wants a different server writes an adapter.
- **An exception taxonomy.** Refused in §8. ADR-0018 §9 refused it; this phase inherits the refusal. Errors are
  responses, and only this module decides them.
- **A JSON error envelope.** Refused in §8. A JSON error envelope is a policy with no consumer in this phase. An
  application that wants one writes a handler that returns one.
- **Echoing the target in a 404.** Refused in §2. A 404 that echoes the target tells a probe which paths the server
  distinguishes.
- **Echoing the exception in a 500.** Refused in §4. A 500 that echoes the exception helps an attacker.
- **Retrying a committed response.** Refused in §6. A second status on a committed exchange is refused by the JDK, and
  the original failure would be lost behind a second exception nobody asked for.
- **Answering `OPTIONS *` as a server-wide capability list.** Refused in §9. The JDK answers it before the adapter sees
  it, and `Router` cannot express it. A fix needs a Router-contract decision, and the debt is recorded rather than
  silently fixed.

## Evidence

| test class | what it proves |
| --- | --- |
| `JdkErrorSemanticsTest` | the four statuses this adapter produces without an application: 404, 405, 500, 400; the `Allow` field; the 500 policy; the 400 cases; the JDK's own parser; the committed response; `OPTIONS *` (§1, §2, §3, §4, §5, §6, §7, §9) |
| `JdkHeaderAndBodyTest` | the HEAD response, which is a 405 if not registered and a bodyless response if registered (ADR-0022 §7, and §3) |
| `JdkHttpServerAdapterEndToEndTest` | the raw target survives the trip, which is the 400 case when it does not (§2, §5) |
| `JdkHttpServerConcurrencyTest` | status codes stay attached to their own requests under concurrent load (§4, §6) |
| `BarezenHttpJdkArchitectureTest` | the bridge and dispatcher are package-private and final, not extension points; the adapter is not a lifecycle participant and not a plugin (§8) |
| `CapturedLogs` | the test fixture that reads what the adapter logged, so the two halves of the 500 policy are both measured (§4) |
| `RawHttp` | the socket-level fixture that speaks HTTP/1.1 and reads what actually left the server (§2, §3, §4, §5, §6, §7) |
