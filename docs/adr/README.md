# Architecture Decision Records

One decision per file, numbered, never edited after acceptance — a superseded decision gets a new record that
names it. Code references ADRs by number in Javadoc, so a reader of the implementation can reach the reasoning.

| # | Decision | Status |
| --- | --- | --- |
| [0001](0001-core-third-party-dependency-is-slf4j-api-only.md) | core's only third-party compile dependency is slf4j-api | accepted |
| [0002](0002-jdk-17-baseline-with-runtime-capability-instead-of-jdk-modules.md) | JDK 17 baseline; newer JDK features are a runtime capability, not a JDK module | accepted |
| [0003](0003-global-two-phase-startup.md) | initialize all participants, then start all participants | accepted |
| [0004](0004-startup-rollback-preserves-the-original-failure.md) | startup rolls back itself; the startup failure always wins | accepted |
| [0005](0005-shutdown-is-best-effort-and-reported.md) | shutdown is best effort and reported, not thrown | accepted |
| [0006](0006-plugin-context-is-not-a-service-locator.md) | PluginContext is not a service locator | accepted, prohibitions implemented in 2.4/2.5; proposed member list superseded by ADR-0016 |
| [0007](0007-request-context-belongs-to-barezen-web.md) | RequestContext belongs to barezen-web | accepted, implemented in 3 |
| [0008](0008-no-aggregator-pom-without-modules.md) | no aggregator POM without modules (`barezen-runtime` deleted) | accepted |
| [0009](0009-lifecycle-exceptions-are-runtime-exceptions-pending-the-2-8-taxonomy.md) | lifecycle exceptions are runtime exceptions now, re-parented in 2.8 | accepted |
| [0010](0010-runtime-capability-seam-scope.md) | RuntimeCapabilities is a 3-field snapshot, probe confined to one class | accepted |
| [0011](0011-thread-provider-is-a-factory-with-ownership.md) | ThreadProvider is a factory with ownership, not a registry | accepted |
| [0012](0012-new-to-stopped-is-legal-and-idempotent.md) | NEW → STOPPED is legal and idempotent | accepted |
| [0013](0013-configuration-is-resolved-at-assembly-time.md) | configuration is resolved once at assembly time and travels as typed values | accepted |
| [0014](0014-dependency-injection-is-explicit-factory-registration.md) | DI is explicit factory registration, not reflective bean discovery | accepted |
| [0015](0015-application-assembly-is-a-responsibility-not-a-component.md) | application assembly is the application's responsibility, not a framework component | accepted, supersedes ADR-0013 item 6 |
| [0016](0016-a-plugin-is-a-participant-not-a-container.md) | a plugin is a `LifecycleParticipant` that can name itself; no PluginContext, no registry, no discovery | accepted, refines ADR-0006 |
| [0017](0017-the-event-bus-is-a-capability-not-a-context.md) | the event bus is a capability: synchronous, exact type, no lifecycle, no context | accepted |
