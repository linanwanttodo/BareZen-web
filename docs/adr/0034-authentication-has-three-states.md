# ADR-0034: Authentication Has Three States, Not Two, and Carries Its Refusal Reason

## Status

accepted — 2026-10-03

Related: ADR-0033 (the decorator that consumes this result), ADR-0037 (the statuses the three states map to).

## Context

An authentication attempt can succeed, can fail because a credential did not verify, or can be skipped because no
credential was offered. A boolean "authenticated?" and a nullable principal collapse two of these into one, and the
collapse is what turns an honest 401 into a wrong one.

## Problem

1. Can "offered nothing" and "offered something wrong" share a representation without the answer being wrong?
2. Does a refusal carry any information, and to whom is that information allowed to go?

## Options Considered

**A — `boolean authenticated` + `Principal principalOrNull`.** Rejected: the caller must ask "is there a principal?"
and then, when there is not, cannot tell whether to prompt for a credential (401) or refuse an identity it already
knows (403). This is the exact confusion the HTTP statuses exist to separate.

**B — `Optional<Principal>` only.** Rejected: an empty Optional is "nobody", which erases the failure state entirely —
a bad token and no token both read as "anonymous".

**C — a sealed interface with three cases.** Chosen: `Authenticated(Principal)`, `Failure(String reason)`,
`Anonymous`. The compiler enumerates them at every call site, and the middleware cannot handle two and forget the
third.

## Decision

`AuthenticationResult` is a sealed interface with the three cases above. `Anonymous` is an enum singleton — absence of
a claim is one fact, not many objects. `Failure` carries a reason string chosen by the application, never the
credential itself. The convenience accessor `maybePrincipal()` returns `Optional<Principal>` and is empty for failure
and anonymous; it is named apart from `Authenticated`'s record component `principal` because a record's component
accessor is final and an interface cannot offer the same fact twice under one name.

## Consequences

- Positive: the 401/403 boundary is representable, which is what ADR-0037 needs to answer the right status.
- Positive: a `switch` over the sealed type is exhaustive, so adding a state later is a compile error at every
  consumer, not a silent default.
- Accepted cost: three types to explain. They are explained by two statuses HTTP already distinguishes; the naming
  `maybePrincipal` is the one wart, and its reason is stated in the Javadoc.

## Security implications

The `Failure.reason` is the application's own text and must not be echoed to the wire: the middleware answers 401 with
the fixed body `Unauthorized` and keeps the reason server-side. A reason is for logs and diagnostics, not for telling
an attacker which half of a credential they got wrong.

## Testing

`AuthenticationResultTest` (8 tests): each state's `isAuthenticated`/`maybePrincipal`, the anonymous singleton, and the
null refusals through both the convenience factories and the public record constructors. `AuthenticationTest` (8
tests) proves the three producers yield the three states, a different scheme
yields Failure, and a null credential yields Anonymous. Amended on 2026-11-25: the authentication scheme is compared
case-insensitively as HTTP requires, while the credential value remains exact and case-sensitive; the tests pin both
halves of that rule. Amended on 2026-11-27: `Credential` and `Authentication.bearer` require the scheme to be an
RFC 9110 token. Blank, separators, controls and non-ASCII schemes are refused; legal token punctuation remains valid.
The middleware test uses an authenticator that trusts every credential to prove a malformed scheme still cannot
reach it.

## Non-goals

Multi-factor, partial-authentication, and expired-but-renewable are separate states a later phase would add to the
`sealed` list — which is the point of sealing: adding one forces a review of every consumer.

## Reopening trigger

If a state is needed that is neither of these three (a challenge response, a staged authentication), it is added to the
`permits` list with its own ADR stating why the existing three cannot carry it.

## Related Decisions

ADR-0033, ADR-0037.
