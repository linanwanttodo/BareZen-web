# ADR-0008: no aggregator POM without modules

Status: accepted
Date: 2026-09-29
Decides: `barezen-runtime` (deleted) and the shape of the module tree
Related: ADR-0002

## Context

The Phase 2 directory proposal listed a `barezen-runtime` aggregator. Once the HTTP runtime port moved into
`barezen-web` and the JDK implementation into `framework/barezen-http-jdk`, nothing was left for it to
aggregate: it would have been a `packaging = pom` module with an empty `<modules>` list, present only to match
a diagram.

## Decision

`barezen-runtime` is deleted. A directory becomes a Maven module only when it has something to build or
something to aggregate. The current tree is `barezen-parent`, `barezen-bom` and `framework/barezen-core`;
`framework/barezen-http-jdk`, `barezen-web` and the rest are created in the phase that fills them in.

## Consequences

- `mvn` output lists modules that produce artifacts, so a build failure points at real code rather than at a
  placeholder.
- The directory tree in the design documents is a forecast, not a build input. Modules appear when a phase
  needs them, which keeps Phase 2.x increments reviewable.
- Reintroducing an aggregator requires naming the modules it groups. If the answer is "none yet", the module
  should not exist.
