# ADR-0032: Class-Major Verification Is a Build Contract, Asserted by a Test and a Guard

## Status

accepted — 2026-10-01

Turns the phase's hand-confirmed bytecode fact (§Stage H: «279 class files, all major = 61») into an enforced one.
The reactor this record lands on emits 302 files, and every one of them is now checked by machinery. ADR-0002
declared the JDK 17 baseline and `scripts/check-jdk-baseline.sh` guards the *declaration*; this record guards the
*emission*, which is the half that can actually break a deployment.
Related: ADR-0002, ADR-0010, ADR-0031. Its mutation evidence continues in the phase's Stage I record.

## Context

Every module inherits `maven.compiler.release=17` from `barezen-parent`, and the declared-release script fails if a
module POM overrides it or the parent stops declaring 17. That is a statement about configuration. The property that
matters to a user is a statement about bytes: a class file whose major version is 61 loads on JDK 17, one whose major
version is 65 does not, and the second one can be produced by a build that satisfies the first script completely —
`mvn -Dmaven.compiler.release=21` is a single command line that does it, no POM edit required.

Phase 2.10 was asked to automate exactly this (§Stage H): fail the build when the major is not 61, prefer a JDK tool,
a Maven plugin or a small verification script over a large dependency, cover the whole reactor including
`target/test-classes`, prove `JDK 17 verify → PASS` and `JDK 21 verify → PASS` without faking either by forcing a JDK
or hardcoding a toolchain, do not misreport non-Java artifacts, and print the checked count, the unique majors and
the expected major.

## Problem

1. What is the difference between the declared release and the emitted major, and can the gap be measured? (§Investigation 1)
2. Which mechanism can enforce it where a wrong answer actually stops something — the build, the job, or a reviewer? (§Investigation 2–4)
3. What is in scope, and what must a scope that names future directories (`plugins/`, `barezen-app/`, `examples/`) do today? (§Investigation 5)
4. What does the automation print, and what makes its numbers trustworthy rather than assumed? (§Decision 4)
5. Does the phase's mutation list — M10, «the class-major expected value altered» — die here or somewhere else? (§Tests)

## Investigation

**1. The gap is real and it is one flag wide.** Both halves were measured on the same tree. The *declared* half:
`scripts/check-jdk-baseline.sh` passes with `-Dmaven.compiler.release=21` on the command line, because no POM
changed. The *emitted* half, on the build that flag produces:

| command | declared-release script | emitted majors |
| --- | --- | --- |
| `mvn -o clean compile test-compile` (JDK 21) | pass | `checked=302 unique-majors=[61] expected=61`, exit 0 |
| `mvn -o -Dmaven.compiler.release=21 clean compile test-compile` | **pass** | **`checked=302 unique-majors=[65] expected=61`, exit 1** |

The second row is the case the automation exists for. And the two directions of the contract are genuinely separate
claims: `javap -v` on the same product names the runtime that must read it (`major version: 61`), while
`java -version` names the runtime that produced it — a JDK 21 build emitting 61 is the proof ADR-0002 wanted, and it
is a fact about bytes, not about which JDK was on the PATH.

**2. Three candidate mechanisms, and what each one actually stops.**

| mechanism | where it fails | reactor-wide? | offline-safe? | new dependency? |
| --- | --- | --- | --- | --- |
| JUnit test reading its own module's outputs | `mvn verify`, every developer, every JDK | per module (see §3) | yes | no |
| shell guard in the CI job | the job | yes | yes | no |
| shell guard bound into the build by a plugin | `mvn verify` | yes | **no — measured** | yes |

The third row was tried and rejected on measurement, not taste: `maven-antrun-plugin:3.1.0` is in the local
repository but its own dependency is not, so invoking it offline ends in
`Cannot access central … org.apache.ant:ant:jar:1.10.12 has not been downloaded from it before`;
`exec-maven-plugin` and a Groovy compiler are absent from the repository entirely. A build step that needs a
download is a build that fails on an isolated machine, which is the opposite of what the phase asked for, and
pulling a plugin plus its runtime in to read eight bytes per file is the «巨大依赖» the directive forbids.

**3. A single in-build check cannot see the whole reactor, and the reason is reactor ordering.** Core's tests run
before `barezen-web` and `barezen-http-jdk` have been compiled, so a core test that walked the sibling directories
after `clean` would find nothing to read — it would pass by starving, which §Decision 3's floor assertion exists to
refuse. Hence the split this record lands on: the test asserts *its own module's* outputs (located through the
running test's own code source, so it follows whatever surefire actually loaded rather than a path convention
written down beside it), one copy per emitting module; the shell guard asserts the *whole tree* after the build has
written it. The two read the same bytes and must not drift into two different numbers, so a fourth test asserts the
guard exists, is executable and pins the same expected major.

**4. Byte reading has three traps, all hit and all fixed before the guard was trusted.**

- `find . -type d -name target -prune -o -name '*.class' -print` returns nothing: `-prune` on the `target` directory
  skips the descent into it, which is exactly where the class files are. Measured on this tree: `checked=0` — a
  guard that would have passed any build. The shipped sweep takes every `*.class` in the tree and classifies it, so
  an unexpected location is a failure rather than an invisibility.
- `case "$dir" in */target/classes | */target/test-classes)` never matches: the blanks around `|` make them part of
  the patterns. Every class file was then reported as out of scope (measured: 306 failures on a green build). The
  shipped version takes the first path element after `target/` and matches it without blanks.
- `od -tu2 -j6 -N2` reads a 2-byte word whose byte order is machine-dependent. The shipped reader takes two bytes
  and combines them by arithmetic (`hi * 256 + lo`), which is defined regardless of the machine running it. A
  `tr -s ' \n' ''` in the same helper is simply invalid usage (`tr: when not truncating set1, string2 must be
  non-empty`) and became `tr -d ' \t\n'`.

**5. §H.3's scope names directories that do not exist yet.** `plugins/`, `barezen-app/` and `examples/` are planned
(ADR-0008 puts a module directory in the tree only in the phase that fills it). A scope written as a list of today's
three modules would silently exclude the fourth; a scope written as *every* `target/classes` and
`target/test-classes` in the tree, with a per-directory floor for anything that has sources, includes them the first
time they compile. Measured on the current reactor: core 47 classes / 89 test-classes, web 12 / 75, http-jdk 7 / 72 —
and the two POM-only modules (`barezen-parent`, `barezen-bom`) are absent from the sweep because they emit nothing,
which is a fact about packaging rather than a gap in coverage. Non-Java build output (`.jar`, `jacoco.exec`,
`.xml`) is never read, and a `.class` file outside the two asserted outputs is a failure — probed by placing one
under `target/generated-classes/` (reported) and one inside a source tree (reported).

## Options Considered

**H.1's other two preferences — a JDK tool, a Maven plugin.** `javap` names a major only per file and needs a JDK
on the PATH, so it checks one build at a time and cannot run in the check-only role; a plugin binding needs a
download (§Investigation 2). Both are worse uses of the same eight bytes.
**A dedicated verification module.** Rejected: a module whose only job is to read its siblings' `target/` is a build
tool dressed as an artifact, it inherits the reactor-ordering problem of §Investigation 3 anyway, and ADR-0008 puts
module directories in the tree only with the phase that fills them.
**Coverage-tool proxy — fail when a class fails to load.** Rejected: a wrong major surfaces as `UnsupportedClassVersionError`
only if something actually loads the class, so the check would depend on test selection rather than on the build, and
a module with no test that loads that class would pass at major 65.
**Script only, no test.** Rejected against the directive's own wording «CI 中真正执行，而不是只提供脚本»: a job step
is a step someone can omit, and the build that produced the bad bytes stays green.
**Test only, no script.** Rejected: it leaves the whole-reactor assertion to a sum of module-local ones, and the
floor that catches "the guard looked at the wrong tree" needs to see every module at once.
**Enforce through the enforcer plugin's dependency rules.** Rejected: `maven-enforcer-plugin` rules reach plugin and
dependency coordinates, not file contents, and this contract is about file contents.

## Decision

1. **The emitted major is a build contract, with two enforcement surfaces.** `ClassMajorContractTest` asserts this
   module's `target/classes` and `target/test-classes` inside `mvn verify`, and one copy lives in each emitting
   module (`com.barezen.core.contract`, `com.barezen.web.contract`, `com.barezen.http.jdk.contract`) — the copies
   differ only in package, which §Investigation 3 makes unavoidable. `scripts/check-class-major.sh` asserts the whole
   tree and runs as a CI job step after `mvn -B -ntp clean verify`, on both JDKs.
2. **Any class file outside the two asserted outputs is a failure, not an omission.** The sweep takes every
   `*.class` in the tree. A file under another `target/` directory is a build output nobody asserted; a file in a
   source tree is committed bytecode. Both are reported by path and the guard exits 1.
3. **A checked count of zero is a failure.** The guard prints a per-module count and requires every module that has
   sources to have emitted into both of its outputs; the test asserts its own two directories are non-empty before it
   reads them. This is the rule that would have caught the `find -prune` bug in §Investigation 4 immediately.
4. **The output is the three numbers the phase asked for**, on one line, in both surfaces:
   `checked=<n> unique-majors=[<sorted distinct>] expected=61`, plus the per-module counts in the guard. The current
   reactor reads `checked=302 unique-majors=[61] expected=61` on JDK 17 and on JDK 21.
5. **Expected major 61 is a constant in each surface, and the two must agree.** A fourth test asserts the guard
   script exists, is executable, and carries `expected_major=61` — the same constant the test uses — so the build and
   the job cannot drift into checking different contracts.
6. **Nothing is forced to make the result pass.** No toolchain pinning, no JDK-specific profile, no `barezen-jdk17`
   or `barezen-jdk21` module (ADR-0002), no skipping the check on one JDK: the same assertion runs and passes under
   both compilers, which is the claim ADR-0002 made and this record makes checkable.

## Consequences

- `mvn -Dmaven.compiler.release=21 clean verify` now fails inside core's own test run, naming the offending files
  (`…/ExecutorSupport.class is major 65`), instead of producing a jar that cannot load on the baseline. Measured.
- The build's green means the bytecode matches the baseline, not that a configuration says it should. The declared
  half stays in place as well: the two scripts fail for different reasons, and both are steps in CI.
- A module added tomorrow is covered by the same rule that covers today's three, with no edit to either surface —
  the guard by its sweep, the test by the copy the module's own phase adds. The ADR that does not exist yet for that
  module has one more contract to inherit, stated here rather than discovered later.
- `ClassMajorContractTest` is the only test in the suite that reads the build output instead of the code under test,
  so it is deliberately in a package of its own (`..contract`) and its javadoc says what it reads and why. Its cost
  is four tests per module and no production type; its surface is `java.nio.file` and nothing else, so it adds no
  dependency and no public API.
- Doing this work surfaced a defect in the phase's own shipped feature: while checking what the limit's read path
  actually executes, `BodyLimit.readBounded`'s grow step turned out to be unreachable for bodies between 8 KiB and
  the limit, and the code it skipped was wrong — a legal upload of that size closed the connection with no HTTP
  answer. That is recorded and fixed in ADR-0031 §Investigation 7, whose §Tests records the two tests run red against the
  unfixed reader; the automation this record adds is what made the question get asked.

## Compatibility Impact

No public API, configuration key, dependency, or wire shape changes. The added surface is three test classes and one
shell script. `mvn verify` becomes stricter: a reactor that emits anything but major 61, or emits nothing into a
directory its sources should have filled, now fails. CI gains one step per JDK. Runtime behavior is untouched.

## Tests

`ClassMajorContractTest`, four per module, twelve in total:

- `everyEmittedClassFileIsMajor61` — the contract itself, with the floor assertion and the printed numbers.
- `expectedMajorIsTheJdkSeventeenVersion` — `assertThat(EXPECTED_MAJOR).isEqualTo(61)`, a literal on the right.
- `theWholeReactorGuardAgreesWithThisTest` — the script exists, is executable, pins the same major and the same two
  outputs.
- `theReaderDistinguishesAWrongMajorFromANonClassFile` — two forged eight-byte headers through the same reader, so
  the contract cannot pass because the reader always answered 61.

**M10, «class-major expected value altered», dies in all four ways.** `EXPECTED_MAJOR` was changed to 65 in core's
copy and `mvn verify` failed four tests, not one: the count check (`unique-majors=[61]` no longer contains the
expected 65), the constant check (65 ≠ 61), the guard-agreement check (the script still says 61) and the reader check
(major 65 is now "expected", so the forged file stops being a violation). The mutation was reverted and the copies
verified byte-identical afterwards, and the mutation itself stays recorded here rather than being
deleted to make the suite quiet.

**End-to-end, on the command that breaks it:** `mvn -o -Dmaven.compiler.release=21 -Dtest=ClassMajorContractTest
clean test` fails inside the core module with the offending class files named by path, and the guard run against the
same output reports `checked=302 unique-majors=[65] expected=61` and exits 1.

**Guard probes, each run against the real tree and each reverted:** wrong major in `target/classes`; wrong major in
`target/test-classes`; a class file under `target/generated-classes/`; a class file inside a source tree; a module's
`target/classes` emptied while its sources remain; and no class files at all (the guard refuses to run before a
build). Six shapes, six failures, and a clean tree that passes at `checked=302` on JDK 17 and JDK 21.

## Amendment (2026-11-28)

The coverage agent must understand the JDK that runs the tests, independently of the major 61 bytes the compiler emits.
JaCoCo 0.8.12 officially supports Java 22 and only experimentally supports Java 23; on the JDK 24 build host it reported
`Unsupported class file major version 68` while instrumenting JDK classes. The property in `barezen-parent` now pins
0.8.13, the first release that officially supports Java 24, so the Maven plugin, report goals and runtime agent stay on
one version. The focused command `mvn -B -ntp -pl framework/barezen-testkit -am test -Dtest=TestDatabaseTest
-Dsurefire.failIfNoSpecifiedTests=false` went from 36 such errors to zero with all seven tests passing. This changes
no emitted class major and no coverage threshold; it makes the existing build contract measurable on the documented
JDK 24 host. Source: [JaCoCo change history](https://www.jacoco.org/jacoco/trunk/doc/changes.html).

## Related Decisions

- **Completes ADR-0002.** That record made JDK 17 a compatibility baseline and refused JDK-specific modules; this one
  makes the baseline checkable at the only level that matters to a user — the bytes on disk.
- **Uses ADR-0010's shape and declines its scope.** `RuntimeCapabilities` probes the runtime through one confined
  class; this contract deliberately does not probe the runtime at all, because the question is what was emitted, not
  what is running. The two stay out of each other's way: the same assertion passes under both JDKs (§Decision 6).
- **Feeds the phase's Stage I regression.** M10 is one of the ten mutations that list names, and it is the only one
  whose target is a verification constant rather than HTTP behavior, so it is discharged here rather than in the
  adapter's suites; the other nine belong to the HTTP-semantics record this phase writes alongside them.
- **Corrects ADR-0031** rather than replacing it: the limit's decisions all stand; one line of its counting read was
  wrong in a way its own tests could not reach, and this record's work is how that became visible.
