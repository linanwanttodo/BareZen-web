# ADR-0036: Sessions Are Opaque, Server-Side and Revocable; JWT Was Rejected

## Status

accepted — 2026-10-03

Related: ADR-0033 (the module and the transport boundary), ADR-0034 (what a session authenticates into), ADR-0002
(no dependency on a JWT library).

## Context

The directive required the token model to be decided before implementation, not assumed to be JWT. The framework
ships one reference session mechanism, and the choice it makes is the choice that most affects what a small
secure-by-default framework can promise.

## Problem

1. What carries the session: a self-contained token the server re-verifies, or a server-side record the token points
   at?
2. How is a session revoked, rotated and expired, and how is that tested without sleeping or a wall clock?
3. What must the session domain not know, so it stays testable and transport-independent?

## Options Considered

**A — JWT (signed self-contained tokens).** Rejected, four concrete reasons. (1) Revocation: a JWT is valid until its
`exp` unless the server keeps a revocation list keyed by `jti` — at which point the server is already keeping
server-side state, so the self-containment bought nothing and the complexity of the signature and the `jti` denylist
is the price. (2) Claims: permissions inside the token are frozen at issue time, so "rotate this user's rights now"
is unsolvable without the same denylist. (3) Cryptography: signing/verifying (HMAC or RSA, key rotation, `kid`
resolution, algorithm-confusion defence) is a large attack surface for a framework whose whole argument is a small
correct default, and it would be the module's largest dependency-shaped decision. (4) It is the Spring-shaped answer;
the max says stop and redesign when a design starts looking like Spring.

**B — opaque random token + in-memory server-side store, chosen.** The token is 32 bytes from `SecureRandom`,
Base64url without padding; it carries nothing. The session (principal + expiry) lives in a `ConcurrentHashMap` the
token is the key to. Revocation is `store.invalidate(token)` — O(1), immediate, no denylist because the record *is*
the state. Rotation removes the old token and issues a new one for the same principal (mutation SM10 guards that the
old token actually dies). Expiry is checked on lookup and the expired record is retired, so dead sessions do not
accumulate in the map (mutation SM9 guards this).

**C — a pluggable `SessionStore` SPI from the start.** Rejected for now: a second implementation (JDBC, Redis) needs a
consumer and a distribution story this phase does not have; the decision stays reversible because the store is reached
through a concrete type the middleware never references directly, and a later record can widen it. This is a named
reopening trigger, not a hedge.

## Decision

A session is an opaque server-side record. `SessionToken` is `(value, expiresAt)` where `expiresAt` is an `Instant`
(expiry is a fact, not a duration recomputed against a moving clock); `SessionStore` owns creation, lookup, expiry,
invalidation and rotation. `Clock` and `SecureRandom` are injected (`SessionStore.of(clock, random)`); `standard()`
uses the system clock. Time is never read from `Instant.now()` inside the logic, which is what lets the expiry and
boundary tests run against a fixed or advanced clock instead of `Thread.sleep`.

The session domain knows nothing about HTTP: `SessionToken` and `SessionStore` import no `com.barezen.web`, no
`java.net`, no server type (an ArchUnit rule, proven to bite). Binding a token to an `Authorization` header or a
cookie is the edge's job; the domain returns a session for a token value and nothing more.

The store does not persist across restarts, deliberately: a session that survives a restart is a credential, not a
session, and should be modelled as one.

## Consequences

- Positive: revocation and rotation are immediate and exact; the test suite proves them without sleeps.
- Positive: no token cryptography, so no signature-verification bug is possible in this module — the surface is
  "is this random string in my map, and is it live".
- Accepted cost: state is per-server. A request that lands on another instance must re-authenticate; for a framework
  whose reference deployment is one JVM (ADR-0021 §5), that is the correct default, and the trigger below is the day
  it is not.
- Accepted cost: an in-memory map is not an unbounded-safe structure under attack. Token entropy (256 bits) makes
  guessing impractical, and expiry retires records on lookup; a total-capacity policy is a named non-goal, not
  oversight.

## Security implications

`SessionToken.toString` prints the expiry and never the value, and `Credential.toString` prints the scheme and never
the value, so neither a token nor a credential enters a log by the type's own doing (mutation SM13 guards the
credential half). The value is random, not sequential, so a captured token reveals nothing about any other.

## Testing

`SessionTokenTest` (5): expiry arithmetic against a fixed clock, the expiry boundary (at the instant it *is* expired),
refusal of zero/negative duration and null clock, `toString` hiding the value. `SessionStoreTest` (15): create/lookup
round trip, 64-token uniqueness, unknown-token empty, expired-not-found *and* retired (size drops), the expiry
boundary, invalidate idempotence, rotate-replaces-and-kills-the-old, rotate-of-expired returning empty without
creating a replacement, a failed rotation preserving the original session, concurrent rotations leaving one winner, rotate-of-unknown, clear, the null/argument
refusals (including invalid rotate durations before token lookup), `standard()` on the system clock, and the `Session` record's null refusals. The current rotation creates the
replacement before conditionally removing the old key; it is failure-safe but still not a linearizable two-key
operation. Mutations SM9 (expired session stays in the map) and SM10 (rotate copies instead of replacing) each killed
by a single named test, restored byte-identical.

## Non-goals

Distributed/shared sessions, a maximum-live-session cap, refresh tokens, remember-me, and a `SessionStore` SPI with a
second implementation.

## Reopening trigger

When the framework targets a multi-instance deployment, a shared-store `SessionStore` opens as its own record and
must re-answer revocation for that store (the map's `invalidate` becomes an operation the backing store must support)
— which is precisely the problem a JWT was rejected for having no answer to.

## Related Decisions

ADR-0033, ADR-0034, ADR-0037.
