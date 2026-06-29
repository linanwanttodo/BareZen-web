# ADR-0001: barezen-core's only third-party compile dependency is slf4j-api

Status: accepted
Date: 2026-09-29
Decides: dependency surface of `framework/barezen-core`
Enforced by: `BarezenCoreArchitectureTest.core_depends_only_on_jdk_and_slf4j`, Maven enforcer `bannedDependencies`

## Context

The kernel runs inside every other module, so each dependency it takes is inherited by everything
downstream and becomes part of the framework's attack and upgrade surface. Logging was the first real
choice: write a facade, adopt a logging implementation, or depend only on an API.

A self-written facade would reinvent a solved problem and would need adapters for Log4j2 and java.util.logging
to be useful. Depending on an implementation would force a logging backend onto every application embedding
BareZen, which is exactly how dependency conflicts start.

## Decision

`barezen-core` compiles against `org.slf4j:slf4j-api` and nothing else. The binding is chosen by the
application: `barezen-app` brings Logback in Phase 2.6, tests get `logback-classic` in test scope only.
SLF4J simple/jdk14 bindings are never declared by core.

## Consequences

- Every consumer of core gets one transitive compile dependency, whose version is managed in one place.
- Logging configuration, levels and appenders are an application concern, not a framework one; core cannot
  set defaults beyond the calls it makes.
- Core code must not print to `System.out`/`System.err` and must not catch-and-log at a level it later
  re-throws from; one log statement per failure path.
- Adding any other compile dependency to core requires a new ADR; the ArchUnit rule fails otherwise.
