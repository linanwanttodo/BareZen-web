# ADR-0046: Release Metadata Only Records What Exists

## Status

accepted
Date: 2026-10-04
Decides: what release metadata BareZen-web's POMs, README and CHANGELOG may assert at the close of the 2.x line; how the release-path hardening scan is scoped so the examples' legitimate driver and endpoint choices never reach a shipped artifact
Related: ADR-0038 (H2's role in the framework vs an application), ADR-0044 (testkit isolation), ADR-0045 (examples are a leaf), ADR-0032 (class-major contract), ADR-0013 (no env reads in framework code)

## Context

The directive's final gate asks for release metadata — version, license, repository metadata, developers, SCM, README, CHANGELOG — under an explicit prohibition on fabricating a homepage, license, release URL, CI status, or download URL. Every one of those five would be trivial to invent; the framework's max ("如果一个设计开始越来越像 Spring:停下来重新设计") applies at the metadata boundary too. The honest question is not "what should the fields say", it is "which fields are true right now".

Measured against the repository as it stands at the close of Phase 2.16:

- **Version** is `0.1.0-SNAPSHOT` (root `pom.xml`). The suffix is the truth: no artifact has been published to any repository, so nothing could be a non-`-SNAPSHOT` release.
- **SCM**: `git remote -v` returns nothing. There is no configured origin. Any `<scm>` block naming a URL is a fiction.
- **License**: no `LICENSE` file exists in the tree; no `<licenses>` element appears in any POM; there is no copyright statement in any source file. Choosing Apache-2.0 or MIT would be an invention.
- **Developers / organization / issue tracker / distribution management**: none of these are configured anywhere in the build; each would be invented text.
- **CI status**: `.github/workflows/backend-ci.yml` runs `mvn verify` on JDK 17 and JDK 21 with the two guard scripts after it — a real configuration, but *not a badge and not a public URL to a passing run*, so any `![CI status]` image link in the README would be a claim the phase cannot substantiate. The workflow file exists; that is the whole truthful statement.
- **Download URL**: no artifact repository is configured (`<distributionManagement>` is absent), so any `https://repo…/barezen-core-0.1.0.jar` would be a dead link.

The framework's own baseline is a real, verifiable set of facts: name (`BareZen Parent`), description, JDK 17 minimum with JDK 21 recommended, `maven.compiler.release=17` enforced by the enforcer and re-asserted by `scripts/check-class-major.sh`, module list matching what's actually in the reactor, and the enforcer's banned dependencies (`org.springframework.boot:*:compile`, `org.springframework:spring-core:compile`, `org.apache.logging.log4j:log4j-core`, `commons-logging:commons-logging`) with Spring explicitly refused at the mechanical level. None of that needs a URL.

## Problem

Can the 2.x line close with an honest release-metadata surface — one where every field is either a fact the repository can currently back or is *deliberately absent*, with the absence recorded rather than papered over — without a fabricated homepage, license, release URL, CI badge or download URL sneaking in through the ordinary "let's make it look ready" instinct?

## Options Considered

- **A. Fill in the fields with reasonable defaults.** Apache-2.0 for the license, a placeholder GitHub URL for SCM, `dev@barezen.example` as a developer. Rejected: every one of them is a fabrication the directive specifically forbids.
- **B. Add only what already exists in the code and record the absences as a named debt.** Chosen: the version, name, description, module list, enforcer rules and JDK baseline are already there; the license, SCM, homepage, CI badge and download URL are not, and saying "not yet" is the smaller honest surface.
- **C. Choose a license now.** Rejected as a *release-metadata* question: picking Apache-2.0 is a legal choice, and no phase in 2.x made it, and no evidence in this repo (a source header, a `LICENSE` file, an existing contributor statement) supports one. This ADR records the choice as an open question with a named trigger (a real release cut), not as an unfinished checkbox.
- **D. Fabricate a CI badge pointing at `backend-ci.yml`'s checks endpoint.** Rejected: the workflow file exists but no public run of it has produced a badge URL that resolves here, and `git remote -v` is empty.

## Decision

1. **The POMs stay where they are.** No `<scm>`, `<licenses>`, `<developers>`, `<distributionManagement>`, `<issueManagement>` or `<ciManagement>` element is added to `pom.xml` or `barezen-bom/pom.xml` in Phase 2.16. The name, description, version and JDK baseline in the root POM are already truthful; nothing else can be filled in without inventing it.
2. **The `README.md` says what exists and what doesn't.** The current-state paragraph describes the modules and the phases without any external URL claim. The build-and-verify section names the two scripts (`check-jdk-baseline.sh`, `check-class-major.sh`) and the JDKs the reactor is exercised on, matching the local CI-equivalent — a real workflow file — without claiming a passing badge.
3. **A `CHANGELOG.md` records the actual commits, not a fabricated release cadence.** Under an `[Unreleased]` heading, the phase summaries are taken from the git history and the ADRs each phase produced. The document explicitly states that "no release artifact has been published, and no release date is claimed here" — the honest form Keep-a-Changelog permits when a project has no published versions.
4. **The release-path hardening scan is scoped by what is actually shipped.** Framework `src/main` sources have zero debug prints (`System.out/err.print*`), zero stack-trace leaks (`printStackTrace`), zero thread-locals, zero `TODO`/`FIXME`/`XXX` markers, and zero disabled-security shortcuts. Two reads are on the record rather than smoothed over, and both are the framework's chosen design:
   - `System.getProperty("java.vm.vendor", …)` in `RuntimeCapabilities` and `System.getProperty("java.version")` in `VirtualThreadProbe` are the *runtime capability probe* — they read the JVM's identity to answer "does this JDK support virtual threads", which is how the thread provider is selected without a build variant (ADR-0002's rule against `barezen-jdk17`/`barezen-jdk21` modules). The ArchUnit no-env/no-property rule is deliberately scoped to the capability modules (security, data, plugins, testkit) that must not read a knob a reviewer can't see; core's own runtime-probe reads are the mechanism, not a violation, and the core ArchUnit rule enforces them (ADR-0013).
   - `EnvironmentVariablesSource` and `SystemPropertiesSource` under `core/config` read env and system properties as their entire purpose — that is the seam through which configuration enters at all, and every framework type *downstream* of them must go through `Configuration` instead of reading a property directly (the ArchUnit rules in security/data/plugins/testkit enforce that).
   The scan's honest claim is therefore "no framework module outside core's declared probe/config sources reads a property or the environment", which is exactly what the ArchUnit rules assert, not a blanket zero.
5. **The `127.0.0.1` bind is the contract, not a dev endpoint.** The examples and `testkit.TestServer` bind `127.0.0.1` on port `0`, which the JDK adapter's own port-0 promise turns into an OS-assigned ephemeral port — the point is to never hardcode a fixed port, and the loopback address is the correct production shape for a test server or a demonstration (an internet-reachable bind would be the bug). `barezen-web.HttpServerAdapterProvider` and `JdkHttpServerAdapterProvider` mention `127.0.0.1` in javadoc only, as an example of the host form a caller may pass. The examples module is not in the BOM and is not shipped as a framework artifact, so nothing in the release path hardcodes a bind address.
6. **No hardcoded absolute paths in framework main.** A search for `/Users/`, `/home/<user>/`, `C:\` and similar returns no matches; the framework reads no path from a literal.
7. **The dependency audit is scoped to what the reactor can measure offline.** `mvn -o` runs the enforcer's `requireUpperBoundDeps`, `dependencyConvergence`, `bannedDependencies` (Spring, log4j-core, commons-logging) and `requireJavaVersion[17,)` + `requireMavenVersion[3.9,)` on every build; a real dependency vulnerability scan (`dependency-check` with an NVD mirror) is *not* run and is recorded as such — the offline sandbox cannot fetch the NVD data and fabricating a "0 vulnerabilities" claim would be exactly the fiction the directive forbids. The audit says what the reactor actually asserted.
8. **Deferred items carry a named reopening trigger, not a "later" placeholder.** The module table in `README.md` classifies "Discovery, Extension Points" as DEFERRED (3.x, trigger: a first real component that owns an extension point, per ADR-0016) and "Frontend" as NON-GOAL (a frontend binding needs a real consumer with named rules). Both classifications are §20 debt-taxonomy categories, not TODOs.

## Consequences

The POM set at the close of 2.x is small but honest. A user who wants to run `mvn verify` on a JDK 17 or JDK 21 host and consume `barezen-core`/`barezen-web`/`barezen-http-jdk`/`barezen-security`/`barezen-data`/`barezen-plugins`/`barezen-testkit`/`barezen-examples` from the local reactor gets exactly what the framework says it is. A user who expects to find BareZen on Maven Central with a license, a homepage, a documented maintainer, a download URL and a passing CI badge finds none of those facts, and that is truthful: this is a `0.1.0-SNAPSHOT` project with no published artifact and no remote.

The cost of the honest path is that the POM does not look "release-ready". The gain is that anyone reading it learns the actual state of the project rather than an aspirational one, and the next phase — whichever phase makes the license and remote decisions — will do so with a real trigger and a real record, not with a placeholder someone left behind.

## Security implications

No secret is embedded in any POM or metadata file. The version is a plain `0.1.0-SNAPSHOT`. The enforcer's banned dependencies are still in force — Spring, log4j-core and commons-logging cannot arrive transitively and the reactor build would fail rather than ship them. The examples module carries `127.0.0.1` binds and one H2 driver reference because it is an application, and the leaf shape (nothing in the framework depends on it, not in the BOM, no release artifact) means no shipped jar inherits those choices.

## Testing

Two Maven-level tests cover what the metadata actually asserts. `barezen-bom/pom.xml` and the root `pom.xml` are read by every reactor build; the enforcer's `requireUpperBoundDeps` and `dependencyConvergence` fire before any test runs. The dual-JDK `mvn -o -B -ntp clean verify` (JDK 17: `BUILD SUCCESS`, 1063 tests, `checked=544 unique-majors=[61]`, `check-jdk-baseline rc=0`; JDK 21: `BUILD SUCCESS`, 1063 tests, `checked=543 unique-majors=[61]`, all 7 coverage gates met) is the reproducibility and completeness evidence. The `scripts/check-class-major.sh` sweep covers 148 main-class files in the framework modules and the examples module, all major 61; two independent JDK 17 `clean verify` builds produced byte-identical SHA-256 digests across all 148 files. No new gate asserts the *absence* of fabricated metadata fields (that would need a `dependency:analyze` or `enforcer:ban` for the tags themselves, a decision ADR-0046 §Decision 1 records rather than implements).

## Non-goals

Choosing a license, opening a remote, publishing an artifact, minting a CI badge, adding `<developers>`, `<distributionManagement>`, `<issueManagement>` or `<ciManagement>`, declaring an "official" release version, or wiring a real CVE feed into the reactor build. Each of those is a decision with a real trigger the phase does not have.

## Reopening trigger

Any real release cut: choosing a license, publishing an artifact, opening a remote repository, configuring `<distributionManagement>` for a Maven Central or GitHub Packages deploy, or adding a real CI badge whose URL resolves. Any consumer asking for a project-level metadata statement (a documentation site, a support contact, a security-contact address) is a real trigger to add those POM fields with real content. The framework's "no dependency audit" claim reverses with a real consumer who can supply a NVD mirror.

## Related Decisions

ADR-0045 (the examples leaf's `127.0.0.1:0` bind and named driver are scoped off the release path by the leaf's shape), ADR-0044 (testkit isolation is the framework's own no-reach-in rule, mirrored in this record's release-path scan), ADR-0038 (H2's role in the framework vs an application), ADR-0032 (the class-major contract the reproducibility evidence asserts), ADR-0013 (no env or system-property reads inside framework code).
