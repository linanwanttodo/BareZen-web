# ADR-0037: 401 Means No Valid Identity and 403 Means Wrong One; Default Deny, Missing Policy Is 403

## Status

accepted — 2026-10-03

Related: ADR-0034 (the three states this record turns into statuses), ADR-0033 (the decorator that enforces them).

## Context

The web layer already fixes 404 and 405 as the *no-route* answers (ADR-0023). This record fixes the *unauthorized*
answers, because security middleware is where they arrive and because a framework that gets them wrong gets them
wrong forever.

## Problem

1. What separates a request that gets 401 from one that gets 403, and how is that separation guaranteed by code and
   not by convention?
2. What happens when an application builds a wrapped route but supplies no permission table — does a missing
   authorization open the route or close it?
3. What happens when the authentication or authorization component itself fails — does the client learn, and if so
   how?

## Options Considered

**A — one status for all refusals.** Rejected: 401 and 403 are different questions with different answers. A client
with a valid token whose permissions are insufficient needs 403 so the client can stop sending requests and tell the
user. A client with no token at all needs 401 so it can offer a login. Collapsing them makes every authorization
failure look like a missing credential and prompts a login dialog on a permissions bug.

**B — default allow with explicit public routes.** Rejected: the inverse of this record's decision. A default-allow
framework requires every protected route to be marked protected; one missing mark is a hole no test catches because
the system looks like it works. The max says stop and redesign when the design starts looking like Spring, and
Spring Security's `.anyRequest().permitAll()` footgun is exactly why.

**C — default deny, three-status matrix.** Chosen, stated below.

## Decision

The mapping is:

- no credential offered, a credential that does not verify, or a malformed `Authorization` field that cannot form a
  credential → 401 `Unauthorized`, the handler is never called;
- an authenticated identity the `Authorization` denies → 403 `Forbidden`, the handler is never called;
- an `Authentication` or `Authorization` implementation that throws → 500 `Internal Server Error`, the handler is
  never called.

A missing collaborator is impossible rather than safe-by-default: both `wrap` overloads throw on null, so no route
can be wired without the application having *chosen* an authentication and an authorization. The choices themselves
are where the denial lives, and each choice denies something. `Authentication.anonymous()` yields 401 for every
request; a `Authorization.of(grants)` table denies every principal it does not list and every permission the list
does not grant (both halves are tested); `Authorization.denyAll()` denies everything. The two-argument
`wrap(authentication, delegate)` is not a permissive default — it is the explicit statement "this route requires
authentication and nothing more", and the authentication half still refuses every anonymous and failed request with
401.

Amended on 2026-11-25: `Authorization.of(grants)` snapshots both the map and every permission set. Copying only the
map leaves a mutable `Set` reachable from the caller, so adding a permission after assembly can turn a denial into an
allow, and clearing one can revoke a grant without an explicit policy change. Separate tests pin both directions.

The middleware always asks the policy exactly one question, and the question is the route's. `wrap(authentication,
authorization, delegate)` asks `Permission.ANY` — "may this principal do anything here"; `allowAll()` answers yes,
`denyAll()` answers no, and an `of(grants)` table answers by whether it lists the principal for `ANY`. A route that
needs a named permission uses the four-argument `wrap(authentication, authorization, required, delegate)` and the
middleware asks `required` instead, so a "grace may read, nobody may write" application writes exactly that table and
nothing lies in between. This exists because an earlier draft asked a fixed internal permission no caller could name:
a policy then could not distinguish two routes and the required-permission half of the design was unreachable.

A 500 on a broken collaborator is chosen over a 401 deliberately: an authentication component that throws is a server
bug, not a client mistake, and charging it to the client (401) teaches the client to retry with different credentials
forever. The 500 is the correct signal for monitoring and the correct signal for the client.

## Consequences

- Positive: every refusal path is enumerable and tested, so no state reaches a handler without the decorator having
  said yes first. The handler-call counter is zero in every refusal test.
- Positive: the default-deny choice is mechanical — a route without an explicit allow gets a 403 — so an operator
  mistake is a 403 that shows up in logs, not a silent open route.
- Accepted cost: a framework user who wants to allow anonymous access to a route must not wrap it in the middleware
  at all, rather than opting it in. That is correct: the decorator is the opt-in surface, and the route table is the
  place to look for what is protected.

## Security implications

The five states (allowed, anonymous, failure, denied, broken) and the three statuses they produce are fixed by this
record and by no other. A new state added to `AuthenticationResult` must also state which status it produces and must
name the test that checks the handler is never called.

## Testing

`SecurityMiddlewareTest` (19 tests) has the five states as its first @Nested group and the required-permission matrix
(granted, ungranted, ANY-by-default, null refusal) as its last; `SecurityHttpEndToEndTest` (6 tests) repeats the
statuses over a real socket. Each refusal asserts the handler-call counter is zero. Mutations SM1 (the gate), SM2
(401-as-403), SM3 (the inverted allow test), SM4 (500 charged to the client), and SM14 (the route's required
permission replaced by ANY) each target one decision of this record, each killed by a named test, and the source
restored byte-identical after every probe.

## Non-goals

`WWW-Authenticate` challenge headers, rate limiting on 401, and any distinction between "token expired" and "token
wrong" in the wire response. The `Failure.reason` is kept server-side and never reaches the client; the client sees
401 and nothing more.

## Reopening trigger

If a future `AuthenticationResult` state requires a status other than 401 or 403 (for example a staged or
multi-factor state that carries a 401 with a `WWW-Authenticate` challenge), that state's record must amend this
record's table and prove the handler is still never called.

## Related Decisions

ADR-0033, ADR-0034, ADR-0036.
