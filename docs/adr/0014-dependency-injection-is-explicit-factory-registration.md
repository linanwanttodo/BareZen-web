# ADR-0014: dependency injection is explicit factory registration, not reflective bean discovery

Status: accepted
Date: 2026-09-29
Decides: how objects get their dependencies at startup, who owns releasing them, and what the container is forbidden to become
Related: ADR-0001, ADR-0003, ADR-0005, ADR-0006, ADR-0009, ADR-0011, ADR-0013

## Context

Phase 2.3 has to answer exactly one question: how the objects an application is made of get created at startup
with their dependencies passed in. Everything else in the phase — scope, lifecycle, release order, plugins —
follows from how that question is answered, so getting it wrong is not a local mistake.

The obvious answer is the one every Java framework has converged on: register a class, find a constructor by
reflection, work out what its parameters need, recurse. It removes boilerplate and it costs a great deal.

- Dependencies stop being visible. To read what `UserService` needs you run the container instead of reading
  `new UserService(repository)`.
- Failure moves out of the code. A missing or circular dependency becomes a container error with a container's
  vocabulary — beans, injection points, scopes, qualified names — that the application never wrote.
- The mechanism has to be fed. Reflective construction needs discovery, and discovery needs annotations or a scan,
  which is where classpath scanning, `@Component`, `@Configuration`, `@Bean`, profiles and auto-configuration come
  from. Each one answers a question the previous mechanism created.
- It takes over ownership. A container that constructs objects is asked to release them, then to start and stop
  them, and there are suddenly two lifecycle engines disagreeing about order.

BareZen's stated principles are *Explicit over Magic* and *Simple Core*, and ADR-0006 already refuses the service
locator for `PluginContext`. A reflective container is that locator with better documentation: `require(Type.class)`
in a factory body is the same call as `require(Type.class)` in a component method, differing only in where it is
legal to write it.

## Decision

**An application states how each object is built, and the container performs that statement.** The mechanism is
`ServiceAssembly`: registration names a type and a factory; the factory body writes the constructor call.

```java
ServiceAssembly assembly = ServiceAssembly.builder()
    .instance(DataSource.class, dataSource)                            // caller built it, caller owns it
    .factory(UserRepository.class, services ->
        new SqlUserRepository(services.require(DataSource.class)))
    .factory(UserService.class, services ->
        new UserService(services.require(UserRepository.class)))
    .build();
```

1. **Constructor injection only.** A dependency appears as a constructor argument in a factory body, where a
   reader sees it and the compiler checks it. There is no field injection, no setter injection, no `@Autowired`,
   and no way to build an object without stating what it needs.
2. **No scanning of any kind.** No classpath scan, no package scan, no annotation scan, no `ServiceLoader`
   discovery, no bean definitions. Nothing in `com.barezen.core.di` touches `java.lang.reflect`, `Class.forName`
   or `ClassLoader`, and an ArchUnit rule says so. The distinction with SPI is kept: `ServiceLoader` finds
   implementations of an extension point, which is a plugin question (Phase 2.4), not an injection question.
3. **Explicit registration, so a registration is a decision.** `bind(X).to(Impl.class)` would require the
   container to pick and invoke `Impl`'s constructor, which is rule 2 by another route. Writing
   `services -> new Impl(...)` keeps `new A(b)` in the application's own source, which is the conceptual model the
   phase is meant to automate and nothing else.
4. **Registration is the only place types are chosen.** Two registrations of one `Class` are refused at
   registration time. There is no `@Primary`, no qualifier, no name and no ordering, so "which implementation"
   has one answer: the one the assembly code named. Two implementations of one interface are two distinct
   registered types, and a dependent names which one it takes.
5. **Resolution is eager.** `build()` creates every registered object before returning, so a missing dependency,
   a cycle or a factory that returned `null` fails while the application is still being assembled — never at the
   first request. Afterwards the graph is a finished map nothing writes, which is what makes lookups thread-safe
   without a lock.
6. **Cycles fail at startup and name the path.** A dependency chain of `A -> B -> A` is reported as that chain.
   Without the guard the recursion ends in `StackOverflowError`, an `Error` that is not catchable usefully and
   never names the two types involved. The guard costs an "in progress" list, because the alternative — a
   topological sort exposed as API — is a dependency-graph package a caller could query.
7. **One scope, and it is application-wide.** There is no request, session, prototype, thread or transaction
   scope. A request-scoped object needs a current request to resolve against, which is a web concern (ADR-0007),
   and the standard fix for that — a scoped proxy — is method interception, which is AOP. The absence of scope is
   what keeps `Provider` at `Supplier`-level simplicity and prevents `FactoryBean`, `ObjectFactory`,
   `ObjectProvider` and `Lazy` from being needed at all.
8. **Ownership follows creation.** `factory(...)` means this assembly created the object, so `close()` releases it
   when it is `AutoCloseable`; `instance(...)` means the caller created it, so `close()` never touches it. Release
   runs in reverse creation order, and because a dependency is always created before the object that needs it,
   that is precisely "a dependent is released before what it depends on". Two owners releasing one object is how
   double release happens, which is why a `LifecycleParticipant` is registered as an instance: the orchestrator
   owns its `stop()`.
9. **The orchestrator stays the only lifecycle engine.** DI builds participants and hands them over; it has no
   `initialize`, `start`, `stop`, `abort`, `refresh` or state machine, and it does not implement
   `LifecycleParticipant`. The chain is `Configuration → application assembly → DI → application services →
   LifecycleOrchestrator`.
10. **DI reads no configuration.** A factory cannot call `System.getProperty`; objects arrive already built. The
    chain from ADR-0013 is unchanged — `Configuration → RuntimeConfig.from(...) → ThreadProviders.create(...) →
    constructor` — and DI sits at the receiving end of it.
11. **Nothing may hold the container.** No type outside `com.barezen.core.di` depends on it at all, so no core
    component receives a `Services` or a `ServiceAssembly`. `Services` is the view a factory is handed while the
    graph is being resolved, not a lookup service an object keeps: a component holding it could resolve anything,
    anywhere, which is the locator this phase exists to avoid. The view stops answering once `build()` returns, so
    the one loophole — a factory leaking its view into the object it built — fails with a message that says to take
    the dependency in the constructor instead. Wiring an assembled graph into the orchestrator goes through
    constructors, which is the only path left.
12. **Four public types, seven operations.** `ServiceAssembly` (`builder`, `require`, `find`, `close`), its nested
    `Builder` (`instance`, `factory`, `build`), `Services` (`require`, `find`) and `AssemblyException`, which adds
    no methods. `Services` is implemented by a package-private class that no caller can name. A test pins the
    declared surface of each type, because API growth in an assembler is how it turns into a bean factory.

`AssemblyException` is one type for the whole layer rather than duplicate/missing/cyclic/close subclasses: the fix
in every case is the application's own assembly code, and what identifies it — the type involved, the path that
reached it — is in the message, not in a class name. It extends `LifecycleException` for now, per the re-parenting
planned in ADR-0009.

## Consequences

- core gains no dependency (ADR-0001): the whole assembly layer is 213 lines of code across three files — 192 in
  `ServiceAssembly`, 10 in `Services`, 11 in `AssemblyException` — and 407 including Javadoc. Spring's `BeanFactory`
  hierarchy is not smaller by a degree, it is a different mechanism, and the difference is visible as a compile
  error the moment someone tries to add scanning.
- Applications write one factory line per object. That is the cost, paid deliberately: the alternative saves a line
  and loses the ability to answer "what does this depend on" by reading the file.
- Assembly is one file per application, and it grows. That is correct — the place where an application says what it
  is made of should be one readable file, not a scattering of annotations the framework collects.
- A `Configuration` snapshot cannot be reached from a factory by construction, so every value an object needs has
  to be decided and passed by whoever assembled it (ADR-0013).
- Plugins in Phase 2.4 cannot resolve their own dependencies out of a container, because ArchUnit forbids the
  dependency in core. A plugin's collaborators are built by the application or by plugin code that owns them, then
  registered.
- Requests needing per-request objects get them from Phase 3's `RequestContext` (ADR-0007), not from a scope
  invented here.
- Three rules were written before their package existed: no public method outside `di` may take a `Class` under a
  lookup-shaped name (which covers a future `PluginContext` by the same sentence as the current kernel), no type
  outside `di` may depend on `di`, and `di` must be a leaf. The first is a net over common verbs, not a proof: a
  mutation probe with `service(Class)` and `router(Class)` fired on the first and passed the second. The second is
  what closes that gap, since handing out an assembled object requires holding the graph. Each new rule was checked
  against a deliberately violating class before it was trusted, which is how the Phase 2.2 lesson — a rule that
  silently matched only the no-argument overload — was not repeated.
- Deferred, each with a stated trigger: `ServiceKey` and named bindings until a real second implementation of one
  interface exists in a running application; lazy creation until build cost is the complaint; provider factories
  until a factory needs arguments the assembly does not have.
