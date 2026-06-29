# ADR-0007: RequestContext belongs to barezen-web, not to core

Status: accepted (decided in Phase 2.1, implemented in Phase 3)
Date: 2026-09-29
Decides: where per-request state lives
Related: ADR-0006

## Context

`RequestContext` is the natural place for request-scoped data, and a lifecycle context is the natural place
people expect to find it. Putting it in core would mean the kernel knows what an HTTP request is, would let a
plugin reach request state from a background thread where none exists, and would make core's dependency graph
point at a layer above it.

## Decision

`RequestContext` lives in `barezen-web`, alongside `BzRequest`, `BzResponse`, `Router`, `Middleware`,
`HttpHandler` and `HttpServerAdapter`. Core keeps only transport-neutral propagation primitives, and even those
are deferred: `ContextPropagator`, `ContextSnapshot` and `ContextKey<T>` are not implemented in Phase 2.1
because nothing in the lifecycle engine consumes them yet.

`PluginContext` does not expose `requestContext()`. A plugin that needs request state receives it as a parameter
of the handler or middleware it registers.

## Consequences

- Core stays usable for a non-HTTP application, which is a stated goal of a general-purpose framework.
- Background work cannot accidentally read "the current request"; it has to be handed the values it needs, or
  capture them through a propagation primitive once one exists.
- The future `ContextKey<T>` in core must stay free of HTTP vocabulary. If it ever needs a request type, the
  type is wrong for core and belongs in `barezen-web`.
- Phase 2.1 creates no part of this boundary; the rule is recorded now so the lifecycle work does not grow a
  tempting convenience accessor later.
