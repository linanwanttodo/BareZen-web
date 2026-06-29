# BareZen-web

A lightweight, modular, plugin-based general-purpose Java Web framework. Package: `com.barezen`.

Design principles: **Simple Core. Composable Everything. Plug and Play. Secure by Default. Built to Extend.**

## Current state

Phase 2.2 (Configuration) is implemented. Everything else is design only.

| Module | Phase | State |
| --- | --- | --- |
| `barezen-parent`, `barezen-bom` | 2.1 | build skeleton, enforcer rules, JDK baseline |
| `framework/barezen-core` | 2.1–2.2 | lifecycle engine, thread capability, configuration |
| `barezen-web`, `barezen-http-jdk` | 3 | not created |
| DI, Plugin Model, Discovery, Extension Points, EventBus | 2.3–2.7 | not created |
| Security, Data, OpenAPI, Frontend | 4+ | not created |

A module directory appears in the tree only when the phase that fills it in runs (ADR-0008).

## Requirements

- Build and run: **JDK 17 or later**. JDK 21 is the recommended development and production runtime.
- Maven **3.9+**.

JDK 17 is a compatibility baseline, not a development constraint. Virtual threads are a runtime capability
selected at startup, never a build variant, and there are no `barezen-jdk17`/`barezen-jdk21` modules
(ADR-0002). Building on JDK 21 produces bytecode that still runs on JDK 17.

## Build

```bash
mvn verify
./scripts/check-jdk-baseline.sh
```

`mvn verify` runs unit tests, ArchUnit architecture rules and an 85% line-coverage gate on `barezen-core`.
The gate runs on JDK 21 only: on JDK 17 the virtual thread provider is unreachable by construction, so that
half of the bundle is not coverable there without asserting dead code is dead (ADR-0002, ADR-0010). JDK 17 in
CI proves compatibility, JDK 21 proves compatibility and quality.
`.github/workflows/backend-ci.yml` runs the same build on both; green on both is the definition of compatible.

## Layout

```
pom.xml                     barezen-parent: owns maven.compiler.release=17, versions, enforcer
barezen-bom/                version contract consumed by applications
framework/barezen-core/     kernel: lifecycle, thread capability, runtime capability, configuration, errors
docs/adr/                   architecture decision records, 0001-0013
scripts/                    build guards
```

Dependency direction is fixed: `core` ← `framework` ← `plugins` ← `app`. Lower layers never import higher
ones, and ArchUnit enforces it inside core today and across modules as they are created.

## Phase 2.1 in one paragraph

`LifecycleOrchestrator` drives a fixed set of `LifecycleParticipant` components: all participants are
initialized before any is started (ADR-0003), a failed startup rolls itself back and reports the original
failure (ADR-0004), shutdown is best effort and returns a report instead of throwing (ADR-0005), and
`stop()`/`close()` are legal and idempotent from every state including `NEW`, which makes an application safe
to hold in try-with-resources (ADR-0012). `ThreadProvider` is an executor factory that owns what it creates —
not a thread pool registry — and resolves from a `ThreadPolicy` plus a three-field `RuntimeCapabilities`
snapshot, with the only Java 21 reference in the codebase confined to one `MethodHandle` probe (ADR-0010,
ADR-0011).

## Phase 2.2 in one paragraph

`Configuration` is an immutable snapshot assembled from ordered `ConfigurationSource`s and read through typed
`ConfigurationKey<T>`s, so a value's type is checked at compile time and a value that cannot be converted fails
with its key, its raw text and the source that supplied it rather than falling back to a default. Precedence is
positional and fixed — key default &lt; `application.properties` &lt; environment variables &lt; system
properties, with environment names mapped by the one rule `.` to `_` then uppercase — and only
`com.barezen.core.config` reads the process environment or the property table, which ArchUnit enforces. A group
of keys binds to a record by hand (`RuntimeConfig.from(configuration)`): no annotations, no scanning, no
reflection over components. Configuration resolves values and nothing else, so it is not a service locator, and
the one setting core has today follows a single chain,
`Configuration → RuntimeConfig → ThreadPolicy → ThreadProviders.create(...)` (ADR-0013).

```java
Configuration configuration = Configuration.standard(Path.of("config/application.properties"));
LifecycleOrchestrator app = LifecycleOrchestrator.builder()
    .configuration(configuration)          // reads barezen.runtime.threads
    .register("http", httpRuntime)
    .build();
```

`barezen.runtime.threads` accepts `auto` (default), `platform` or `virtual`. An unsupported explicit value fails
startup; `auto` is the only policy allowed to fall back.
