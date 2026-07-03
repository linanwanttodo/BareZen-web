# ADR-0033: Security Is a Handler Decorator on the Contract; the Context Arrives as an Argument

## Status

accepted — 2026-10-03

The module this record places: `framework/barezen-security`, package `com.barezen.security`, one compile dependency
(`barezen-web`), zero others. Related: ADR-0018 (the contract ADR-0018 protects from this module), ADR-0020 (the
signature this module reuses), ADR-0021 (the adapter this module must not reach).

## Context

Phase 2.11 adds authentication and authorization. The web contract has one extension surface, `Handler.handle(Request,
Map)`, and ADR-0018 §9 forbids it from growing a context parameter, an attribute bag or a service accessor. The
question this record answers is where security lives when the contract cannot be changed and the framework has no
filter chain, no interceptor stack and no servlet to attach one to.

## Problem

1. Where does a security decision attach, given the contract's signature is frozen at two arguments?
2. How does a handler that legitimately needs to know who is asking learn it, without a context slot on the contract
   and without a static or thread-local?
3. What must the security module not depend on, so that the decision survives a second adapter?

## Options Considered

**A — a context parameter on `Handler.handle`.** Rejected: ADR-0018 §9 names this exact growth ("What this type must
never grow: a `Context`"), and every handler, wrapper and adapter in the framework would carry a security concern
they do not have. The alternative ADR-0020 chose for parameters — one more argument, because routing had nowhere to
put its answer — does not transfer: routing's parameters came from the client's request line and belong to the
request's story; a security context is the server's decision about the request, and it belongs to one module.

**B — a mutable attribute on `Request`.** Rejected: `Request` is a record of what the client sent, immutable by
contract; a server decision smuggled onto it makes an immutable fact mutable in meaning, and ADR-0020 §4 already
refused a per-request place-to-put-things for the same reason.

**C — `ThreadLocal<SecurityContext>` with a static accessor.** Rejected: this is the form Spring Security and JAX-RS
security contexts take, and it is the form this framework's max forbids ("if a design starts looking more and more
like Spring: stop and redesign"). It is also not merely inelegant — a server thread answers many requests, so the
leak is real, and it is exactly the leak a keep-alive connection exposes. The E2E test in this phase proves the
absence of the leak on a reused connection; a ThreadLocal design could pass every unit test and fail that one.

**D — a second interface the middleware hands the context to: `SecuredHandler`.** Chosen. A handler that needs the
identity implements `SecuredHandler` next to `Handler`; the middleware detects it (`instanceof`) and calls
`handle(Request, Map, SecurityContext)`; a plain `Handler` is called exactly as before and never sees a context. The
contract stays two-argument, the decision stays request-scoped, and the mechanism is the same one the contract
already uses for a caller that knows more than the signature carries: the adapter matched a handler and now decides
which half of the object to call, the way `RouteMatch` decided which handler to call.

## Decision

Security attaches as a decorator — `SecurityMiddleware.wrap(...)` takes a `Handler` and returns a `Handler` — so an
application composes it where it composes a route: `Router.builder().route(GET, "/secure", SecurityMiddleware.wrap(auth, authz, handler))`.
The middleware is not part of the adapter; the adapter turns bytes into `Request` and `Response` into bytes and holds
no security policy (the same rule ADR-0021 applies to routing).

The context reaches a handler only as an argument of `SecuredHandler.handle`. The interface lives in the security
module, not in the contract, because the contract must not know security exists.

The module's only compile dependency is `barezen-web`. `barezen-core` is not on its classpath — a security decision
that needed a lifecycle or a configuration handle would be reaching outside itself — and the adapter is test-scope,
used only by the test that stands a server up to prove the decorator survives one.

The middleware's collaborators are injected at construction (`Authentication`, `Authorization`) and held in final
fields, which is ADR-0016's plugin rule applied here: no component looks itself up.

## Consequences

- Positive: the whole surface is one decorator function and one optional interface; nothing in web, http-jdk or core
  changed for it, and the framework's frozen-contract tests stayed green untouched.
- Positive: `instanceof` dispatch on the delegate is the same mechanism ADR-0020 §3 used to keep a match out of a
  handler: capability is discovered from the concrete object, never requested by name.
- Accepted cost: a handler must implement a second interface to see the identity, where a ThreadLocal could be
  reached from anywhere. That is the point; §Option C is what this cost buys off.
- Accepted cost: `wrap` asks the policy one permission question per request — the route's `Permission.ANY` by
  default, or the named permission the four-argument overload is given. Fine-grained combinations of permissions are
  the application's `Authorization`'s job — the middleware asks once, the policy decides. A framework-level
  route→permission table is not built here (Non-goals).

## Security implications

The decorator order is fixed at construction — authentication, then authorization, then the delegate — so a route
cannot be wired to skip one of them without explicitly not wrapping it, and that is visible in the route table the
same way an unauthenticated route is visible in Spring's configuration. A forgotten wrap is the same operator
mistake in every framework; the mitigation is that the table, not a scanner or an annotation, is the only place a
route is declared.

## Testing

- 99 tests in the module, including the five-state matrix (allowed, anonymous, failure, denied, broken) with
  handler-invocation counted at zero for every refusal.
- `SecurityHttpEndToEndTest` runs the decorator on a real JDK adapter over one keep-alive connection and asserts
  ada → anonymous(401) → grace → anonymous(401) → ada: identity never crosses a request.
- ArchUnit (7 rules): no `ThreadLocal`/`InheritableThreadLocal`, no static mutable field, no `System.getProperty` /
  `getenv`, the session domain free of `com.barezen.web`/`java.net`/server packages, no logging facade in main
  sources, no JDBC, no adapter package. Every rule was proven to bite by a deliberately violating class (watched
  fail, deleted).
- Mutations SM1–SM6 of this phase's Stage H probe this record's decision points: the authenticated gate (SM1), the
  401 status (SM2), the allow test (SM3), the 500-on-broken-component (SM4), the SecuredHandler branch (SM5), and the
  scheme/value split (SM6); all killed, all restored byte-identical.
## Non-goals

- A `SecurityFilterChain`, an ordered filter registry, annotation-driven security, method security, CSRF, OAuth2/OIDC
  client support, and a CORS policy component. Each is a later phase or never.
- Changing `Handler.handle`'s signature or adding anything to `Request` or `Response`.

## Reopening trigger

If a second adapter (2.x-3.x) finds the decorator does not survive its threading model, or if a consumer needs the
context at a layer the decorator cannot reach (a router-level decision made from the identity), the argument-based
delivery reopens as an ADR that must again refuse the ThreadLocal for the reason in §Option C.

## Related Decisions

ADR-0018 (the frozen contract this record decorates rather than widens), ADR-0020 (the two-argument `handle` this
record routes around with an optional interface), ADR-0016 (constructor injection, no lookup), ADR-0021 (the adapter
boundary), ADR-0034 (the three-state result this decorator consumes), ADR-0037 (the 401/403 split this decorator
implements).
