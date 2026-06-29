# ADR-0002: JDK 17 is the compile baseline; newer JDK features are a runtime capability, not a JDK module

Status: accepted
Date: 2026-09-29
Decides: how the framework uses APIs newer than its baseline
Related: ADR-0010, ADR-0011

## Context

Virtual threads are the framework's first feature that needs Java 21 while the project targets Java 17 as a
compatibility baseline. The common shortcut is to build a `barezen-jdk21` module, or to bump
`maven.compiler.release` because the current development machine runs JDK 21. Both make the build host decide
the architecture, and both split the codebase along a JDK version line that then has to be maintained.

## Decision

1. `barezen-parent` owns `<maven.compiler.release>17</maven.compiler.release>`. Child modules must not
   override it, and no module may set a higher release without an ADR.
2. There are no `barezen-jdk17`, `barezen-jdk21` or `barezen-jdk25` modules, and no multi-release JAR.
   Animal Sniffer is not used: `--release 17` already checks language level, API symbols against `ct.sym` and
   bytecode target in one place.
3. Java 21 behaviour is exposed as a **runtime capability**: `ThreadProvider` has a platform and a virtual
   implementation selected at runtime. Where a class must reference a symbol absent from JDK 17, it uses a
   `MethodHandle` lookup and nothing else (see ADR-0010).
4. JDK 21 is the recommended development and production runtime. JDK 17 remains fully supported: the virtual
   thread provider simply is not selectable there. JDK 25 is a future target verified by CI, not a design
   input.

## Consequences

- One artifact per module runs on every supported JDK; there is no variant selection at build or install time.
- Compiling against `Thread.ofVirtual()` directly is impossible, so the capability seam is a small amount of
  deliberate indirection. That indirection is confined to one class and checked by an ArchUnit rule, which is
  cheaper than maintaining JDK-specific source trees.
- The cost is paid at every JDK upgrade review: the baseline can only move by changing one parent property
  and deleting probe code, which is the intended direction of travel.
- CI must run the same build on JDK 17 and JDK 21; a feature that only works on one of them is a bug.
