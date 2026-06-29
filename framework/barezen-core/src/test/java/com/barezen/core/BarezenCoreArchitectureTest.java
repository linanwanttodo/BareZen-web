package com.barezen.core;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.barezen.core.kernel.LifecycleParticipant;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.Architectures;

/**
 * Executable form of the Phase 2 architecture rules.
 *
 * <p>These are the constraints that turn the design claims into something verifiable: core stays free of any
 * third-party library beyond slf4j, stays free of HTTP and persistence concepts, confines reaching for a newer
 * JDK API to a single package, keeps reading configuration inputs inside the config package, keeps the assembly
 * layer from becoming a service locator, and keeps package dependencies one directional.
 */
@AnalyzeClasses(
    packages = "com.barezen.core",
    importOptions = ImportOption.DoNotIncludeTests.class)
class BarezenCoreArchitectureTest {

  private static final String KERNEL = "com.barezen.core.kernel..";
  private static final String CONCURRENT = "com.barezen.core.concurrent..";
  private static final String RUNTIME = "com.barezen.core.runtime..";
  private static final String ERROR = "com.barezen.core.error..";
  private static final String CONFIG = "com.barezen.core.config..";
  private static final String DI = "com.barezen.core.di..";
  private static final String PLUGIN = "com.barezen.core.plugin..";
  private static final String EVENT = "com.barezen.core.event..";

  /**
   * Method names that mean "ask me for an object by type". Kept as data, with the reasoning, so the list is
   * reviewable rather than buried in a predicate.
   */
  private static final java.util.List<String> LOOKUP_NAMES =
      java.util.List.of("get", "require", "find", "service", "lookup", "resolve", "bean", "instance");

  /**
   * core depends on nothing but the JDK and the SLF4J API.
   *
   * <p>Red here means either a third party library entered the kernel, or core grew a concept that
   * belongs in a module of its own.
   */
  @ArchTest
  static final ArchRule core_depends_only_on_jdk_and_slf4j = noClasses()
      .should().dependOnClassesThat(dependencyOutsideAllowedBase())
      .as("barezen-core must not acquire any third party dependency beyond slf4j-api");

  private static DescribedPredicate<JavaClass> dependencyOutsideAllowedBase() {
    return resideOutsideOfPackages("java..", "javax..", "com.barezen.core..", "org.slf4j..");
  }

  /**
   * the kernel knows nothing about HTTP, SQL, security or plugins.
   *
   * <p>Several of these packages do not exist yet. The rule is written now so that introducing
   * {@code barezen-web} cannot quietly create a back dependency from core into a layer above it.
   */
  @ArchTest
  static final ArchRule core_has_no_web_or_persistence_concepts = noClasses()
      .should().dependOnClassesThat().resideInAnyPackage(
          "com.barezen.api..", "com.barezen.web..", "com.barezen.security..", "com.barezen.data..",
          "com.barezen.plugin..", "com.barezen.app..",
          "com.sun.net.httpserver..", "jakarta..", "java.sql..", "javax.sql..",
          "org.springframework..", "com.fasterxml..", "org.hibernate..",
          "org.junit..", "org.assertj..", "com.tngtech..")
      .as("core must stay free of HTTP, SQL, security, plugin, Spring and test framework concepts")
      .allowEmptyShould(true);

  /**
   * reaching for a Java API newer than the baseline is confined to the runtime probe.
   *
   * <p>The virtual thread {@code MethodHandle} is the single legitimate exception to compiling against
   * JDK 17. A second occurrence would mean the capability model needs an ADR rather than another probe.
   */
  @ArchTest
  static final ArchRule newer_jdk_probing_is_confined_to_runtime = noClasses()
      .that().resideOutsideOfPackages(RUNTIME)
      .should().dependOnClassesThat().resideInAnyPackage("java.lang.invoke..")
      .as("only com.barezen.core.runtime may use MethodHandle style runtime probing");

  /**
   * Java 21 APIs are never referenced directly, which would break the JDK 17 baseline.
   *
   * <p>ScopedValue is excluded from the MVP explicitly, so a reference to it is a scope violation and not
   * merely a compatibility problem.
   */
  @ArchTest
  static final ArchRule scoped_value_and_structured_concurrency_are_not_used = noClasses()
      .should().dependOnClassesThat().haveNameMatching(".*ScopedValue.*")
      .orShould().dependOnClassesThat().haveNameMatching(".*StructuredTaskScope.*")
      .as("ScopedValue and StructuredTaskScope are outside the JDK 17 baseline and outside the MVP")
      .allowEmptyShould(true);

  /** capability, configuration and orchestration-support packages never depend on the kernel. */
  @ArchTest
  static final ArchRule capabilities_do_not_depend_on_the_kernel = noClasses()
      .that().resideInAnyPackage(CONCURRENT, RUNTIME, CONFIG)
      .should().dependOnClassesThat().resideInAnyPackage(KERNEL)
      .as("concurrent, runtime and config must stay usable without the lifecycle engine");

  /**
   * only the config package may enumerate the process's configuration inputs.
   *
   * <p>The framework rule "core depends on abstractions, not on concrete sources" needs a mechanical guard,
   * because the tempting shortcut is a {@code System.getenv} call inside some component that just needs one
   * value. Each overload is listed separately: {@code callMethod(Class, String)} matches the no-argument
   * signature only, so a rule written for {@code getenv()} leaves {@code getenv("KEY")} unguarded, which is the
   * form a leak would actually take. {@code System.getProperty} with a specific JVM key stays legal in
   * {@code runtime}, which reports what the JVM is rather than what the operator configured.
   */
  @ArchTest
  static final ArchRule only_config_reads_environment_and_property_tables = noClasses()
      .that().resideOutsideOfPackages(CONFIG)
      .should().callMethod(System.class, "getenv")
      .orShould().callMethod(System.class, "getenv", String.class)
      .orShould().callMethod(System.class, "getProperties")
      .orShould().callMethod(System.class, "setProperties", java.util.Properties.class)
      .as("configuration sources live only in com.barezen.core.config (ADR-0013)");

  /**
   * nothing writes the property table, in the config package or anywhere else.
   *
   * <p>Configuration is an input. A component that called {@code System.setProperty} would be configuring
   * another component through global state, which is the coupling the snapshot exists to prevent (ADR-0013),
   * and it would make an immutable snapshot a lie. The only writers in the build are tests that arrange the
   * real system properties {@code Configuration.standard} reads.
   */
  @ArchTest
  static final ArchRule nobody_writes_the_property_table = noClasses()
      .should().callMethod(System.class, "setProperty", String.class, String.class)
      .orShould().callMethod(System.class, "clearProperty", String.class)
      .as("configuration is read, never written, so one component cannot configure another through global state");

  /** error and runtime are leaves of the internal dependency graph. */
  @ArchTest
  static final ArchRule error_and_runtime_packages_are_leaves = noClasses()
      .that().resideInAnyPackage(ERROR, RUNTIME)
      .should().dependOnClassesThat().resideInAnyPackage(KERNEL, CONCURRENT, CONFIG)
      .as("error and runtime must not reach back into kernel, concurrent or config");

  /**
   * the assembly layer depends on nothing but the error taxonomy.
   *
   * <p>DI is a leaf on purpose. It does not know about lifecycle phases, thread providers, configuration or
   * runtime capabilities, so it cannot start owning any of them. If it needed one of those, the correct fix is to
   * pass the already-built object in from the application's assembly code, not to add the dependency here.
   */
  @ArchTest
  static final ArchRule di_is_a_leaf_of_the_internal_graph = noClasses()
      .that().resideInAnyPackage(DI)
      .should().dependOnClassesThat().resideInAnyPackage(KERNEL, CONCURRENT, CONFIG, RUNTIME)
      .as("com.barezen.core.di may only use the JDK and com.barezen.core.error (ADR-0014)");

  /**
   * nothing in core is allowed to reach for an object out of the container.
   *
   * <p>This is the mechanical half of the service-locator rule, and it is written now because the leak it prevents
   * happens later. A {@code PluginContext}, an {@code HttpRequest} or a servlet-style accessor that takes a
   * {@code Services} or {@code ServiceAssembly} is the exact shape that turns an assembler into
   * {@code ApplicationContext}, and it is invisible to a reviewer until some component calls {@code require} on it.
   * Since no type outside this package may depend on {@code di} at all, that growth is refused before it exists:
   * wiring an assembled graph into the orchestrator goes through constructors, which is the only path left.
   */
  @ArchTest
  static final ArchRule no_core_type_depends_on_the_assembler = noClasses()
      .that().resideOutsideOfPackages(DI)
      .should().dependOnClassesThat().resideInAnyPackage(DI)
      .as("nothing outside com.barezen.core.di may hold Services or ServiceAssembly (ADR-0006, ADR-0014)");

  /**
   * no public method anywhere outside the assembler resolves an object by type.
   *
   * <p>The dependency rules above stop core from holding a container; this one stops core from <em>becoming</em>
   * one, which is how the same mistake arrives without any container in sight: a context or registry object that
   * grows {@code router(Class)}, {@code sql(Class)} or {@code service(Class)}. Name plus a {@code Class} parameter
   * is what identifies that shape. It is deliberately narrow - a lookup-shaped method taking a
   * {@code ConfigurationKey} or a {@code String} key, which {@code Configuration.get} and the configuration sources
   * legitimately do, is not a service locator - and it applies to the whole framework, so the future {@code
   * PluginContext} is covered by the same sentence as the current kernel.
   *
   * <p>What it cannot catch is a lookup with a name nobody thought to forbid. A mutation probe with both
   * {@code service(Class)} and {@code router(Class)} fired on the first and passed the second, so this rule is a
   * net over the common verbs, not a proof. The backstop is the dependency rule above: to hand out an assembled
   * object a method has to reach the graph somehow, and nothing outside {@code di} is allowed to hold it.
   */
  @ArchTest
  static final ArchRule nobody_exposes_a_type_lookup = methods()
      .that(JavaMember.Predicates.declaredIn(resideOutsideOfPackages(DI)))
      .should(notResolveAnObjectByType())
      .as("no public method outside com.barezen.core.di may take a Class and hand back the object it names");
  // Configuration.get(ConfigurationKey) and the sources' find(String) are keyed lookups of values, not object
  // resolution by type, so they stay legal: the condition only fires when a Class parameter is involved.

  /**
   * Written as a condition rather than a predicate because "is this a locator?" needs three facts about a method
   * at once - its name, its parameter types, and whether it is public surface - and the failure message has to say
   * which of them made it a locator.
   */
  private static ArchCondition<JavaMethod> notResolveAnObjectByType() {
    return new ArchCondition<JavaMethod>("not resolve an object by type") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        boolean namedLikeALookup = LOOKUP_NAMES.contains(method.getName());
        boolean takesAType = method.getRawParameterTypes().stream()
            .anyMatch(parameter -> parameter.getName().equals("java.lang.Class"));
        boolean publicSurface = method.getModifiers().contains(JavaModifier.PUBLIC);
        boolean violates = namedLikeALookup && takesAType && publicSurface;
        events.add(violates
            ? SimpleConditionEvent.violated(method, method.getFullName()
                + " is public and takes a Class, so it hands out the object that type names: that is the"
                + " service locator shape this framework forbids outside the assembler (ADR-0006, ADR-0014)")
            : SimpleConditionEvent.satisfied(method, method.getFullName() + " is not a type lookup"));
      }
    };
  }

  /**
   * the assembler never reads a single configuration value.
   *
   * <p>The enumeration rule above already stops this package from listing the environment or the property table,
   * and it deliberately leaves {@code System.getProperty("java.version")} legal in {@code runtime} because that is
   * the JVM reporting itself rather than an operator's setting. This rule closes the remaining gap for DI:
   * {@code System.getProperty("barezen.something")} inside a factory is the shortcut that would make the container
   * read configuration behind its own signature. The correct chain is {@code Configuration -> typed config ->
   * assembly -> constructor}, with the finished object handed in (ADR-0013, ADR-0014).
   */
  @ArchTest
  static final ArchRule di_never_reads_a_configuration_value = noClasses()
      .that().resideInAnyPackage(DI)
      .should().callMethod(System.class, "getProperty", String.class)
      .orShould().callMethod(System.class, "getProperty", String.class, String.class)
      .as("dependencies are handed to the assembler as objects, never looked up from the environment");

  /**
   * the assembler and the event bus do not scan for classes or reflect over them.
   *
   * <p>Registration is explicit in application code, so neither the container nor the bus has any reason to name a
   * package, load a class by name, pick a constructor or enumerate a service. Each of these calls is a step towards
   * discovering components instead of being told about them; the whole design is defined by not taking it. For the
   * event bus the temptation is specific and well known: a {@code ServiceLoader<EventHandler>} or a
   * {@code @Subscribe} annotation scan is how the mainstream event systems avoid declaring their handlers, and both
   * would need exactly the reflection this phase forbids (ADR-0014, ADR-0017).
   */
  @ArchTest
  static final ArchRule the_assembler_and_the_event_bus_do_not_scan_or_reflect = noClasses()
      .that().resideInAnyPackage(DI, EVENT)
      .should().dependOnClassesThat().resideInAnyPackage("java.lang.reflect..")
      // Every overload of each of these is listed by hand. callMethod(Class, String) matches only the
      // no-argument signature, so a rule written for one shape leaves the others unguarded, which is exactly how a
      // Phase 2.2 rule turned out to be silently ineffective.
      .orShould().callMethod(Class.class, "forName", String.class)
      .orShould().callMethod(Class.class, "forName", String.class, boolean.class, ClassLoader.class)
      .orShould().callMethod(Class.class, "getClassLoader")
      .orShould().callMethod(Class.class, "getDeclaredConstructor", Class[].class)
      .orShould().callMethod(Class.class, "getConstructors")
      .orShould().callMethod(ClassLoader.class, "getResources", String.class)
      .orShould().callMethod(ClassLoader.class, "getSystemResources", String.class)
      .orShould().callMethod(java.util.ServiceLoader.class, "load", Class.class)
      .orShould().callMethod(java.util.ServiceLoader.class, "load", Class.class, ClassLoader.class)
      .as("com.barezen.core.di and com.barezen.core.event contain no reflection, class loading, scanning or SPI"
          + " lookup");

  /**
   * the plugin SPI depends on one type in the lifecycle engine, and on nothing else in core.
   *
   * <p>{@code Plugin extends LifecycleParticipant} is the single allowed edge into {@code kernel}, and it is what
   * makes a plugin a participant rather than a second kind of component (ADR-0016). The rule is written at type level
   * rather than as "plugin may depend on kernel" because the thing being forbidden is on the same package path as the
   * thing being allowed: {@code LifecycleOrchestrator}. A plugin that could reach the orchestrator could register
   * itself, drive another component's phases, or hand itself a lifecycle engine of its own, and a package level rule
   * would let all three through. The layer rule covers the package shape; this one covers the type.
   */
  @ArchTest
  static final ArchRule the_plugin_spi_reaches_only_the_participant_interface = noClasses()
      .that().resideInAnyPackage(PLUGIN)
      .should().dependOnClassesThat(dependencyOnKernelOtherThanTheParticipantInterface())
      .as("com.barezen.core.plugin may use LifecycleParticipant and no other type in the kernel (ADR-0016)");

  private static DescribedPredicate<JavaClass> dependencyOnKernelOtherThanTheParticipantInterface() {
    return JavaClass.Predicates.resideInAnyPackage(KERNEL)
        .and(DescribedPredicate.not(JavaClass.Predicates.equivalentTo(LifecycleParticipant.class)));
  }

  /**
   * the plugin SPI knows nothing about the layers an application assembles.
   *
   * <p>Every type an application would pass to a plugin constructor lives below this package, so a dependency here
   * is always the plugin going to fetch it instead. The four packages are named rather than "everything except
   * kernel", because each forbids a distinct drift: {@code di} is {@code PluginContext.require(Class)} and a plugin
   * holding the container, {@code config} is a plugin reading its own settings at runtime, {@code concurrent} is a
   * plugin creating threads for itself, {@code runtime} is a plugin probing the JVM to decide its own behaviour
   * (ADR-0006, ADR-0013, ADR-0015, ADR-0016).
   */
  @ArchTest
  static final ArchRule plugins_do_not_reach_the_layers_the_application_assembles = noClasses()
      .that().resideInAnyPackage(PLUGIN)
      .should().dependOnClassesThat().resideInAnyPackage(DI, CONFIG, CONCURRENT, RUNTIME)
      .as("a plugin's dependencies come from its constructor, supplied by the application (ADR-0006, ADR-0016)");

  /**
   * nothing else in core depends on the plugin SPI.
   *
   * <p>The kernel drives participants and stays indifferent to what else a participant is. If {@code kernel} learned
   * about {@code Plugin} - a {@code PluginRegistry}, a {@code PluginManager}, an {@code instanceof} check that logs
   * plugin ids - the SPI would have become a concept the lifecycle engine carries, and the next plugin feature would
   * be requested as a kernel change instead of as an extension. The registration key stays a plain {@code String} for
   * the same reason: that is what lets the orchestrator be driven by a plugin without naming one (ADR-0016).
   */
  @ArchTest
  static final ArchRule no_core_type_depends_on_the_plugin_spi = noClasses()
      .that().resideOutsideOfPackages(PLUGIN)
      .should().dependOnClassesThat().resideInAnyPackage(PLUGIN)
      .as("com.barezen.core.plugin must stay a leaf; the lifecycle engine has no plugin concept (ADR-0016)");

  /**
   * the event bus depends on nothing in core at all.
   *
   * <p>This is the strongest form the phase's acceptance picture asks for, and it is stated as one rule rather than
   * four: {@code event} may not reach {@code di} ({@code eventBus.require(EventBus.class)} in reverse), may not reach
   * {@code config} (a bus reading its own dispatch settings), may not reach {@code concurrent} or {@code runtime} (an
   * executor for async handlers, a probe for a capability it does not need), and may not reach {@code kernel} — which
   * is the one worth naming, because the two ways a bus grows a lifecycle are a {@code close()} the orchestrator calls
   * and a subscription registry that notices when a participant stops. The bus is a capability with one dependency:
   * the objects it routes (ADR-0017).
   */
  @ArchTest
  static final ArchRule the_event_bus_depends_on_nothing_in_core = noClasses()
      .that().resideInAnyPackage(EVENT)
      .should().dependOnClassesThat().resideInAnyPackage(KERNEL, DI, CONFIG, CONCURRENT, RUNTIME, PLUGIN, ERROR)
      .as("com.barezen.core.event must stay a standalone capability: no lifecycle, no container, no configuration,"
          + " no threads, no plugin concept (ADR-0017)");

  /**
   * nothing else in core depends on the event bus.
   *
   * <p>The mirror of the rule above, and the one that keeps the phase's claim honest. The bus is used by plugins and
   * by application services, both of which receive it through a constructor; if {@code kernel} or {@code plugin}
   * imported it, then either the lifecycle engine had started caring about events or the plugin SPI had started
   * naming its collaborators, and both would be the context-shaped coupling ADR-0016 refuses. {@code Plugin -> Event}
   * is legal because it is an application's plugin reaching a capability; it simply never happens inside core, and no
   * future plugin design should make it necessary.
   */
  @ArchTest
  static final ArchRule no_core_type_depends_on_the_event_bus = noClasses()
      .that().resideOutsideOfPackages(EVENT)
      .should().dependOnClassesThat().resideInAnyPackage(EVENT)
      .as("com.barezen.core.event must stay a leaf; nothing in core routes events for anybody (ADR-0017)");

  /**
   * the event bus owns no execution machinery.
   *
   * <p>The one risk the dependency rules above cannot see, because it arrives as a JDK import: an
   * {@code ExecutorService} field, a {@code BlockingQueue} of pending events, a {@code ScheduledExecutorService} for
   * retry. Phase 2.6 decided dispatch is synchronous, and the usual way that decision erodes is a bus that keeps a
   * thread pool "for the async cases" while its signature still says {@code publish}. This rule is a dependency rule
   * over JDK types rather than a blacklist of BareZen method names: the concurrent *collections* the implementation
   * legitimately routes with ({@code ConcurrentHashMap}, {@code CopyOnWriteArrayList}) are not executors, threads,
   * queues, timers or schedulers, so they stay legal, and everything that could turn a publish into a submission
   * cannot (ADR-0017).
   */
  @ArchTest
  static final ArchRule the_event_bus_owns_no_execution_machinery = noClasses()
      .that().resideInAnyPackage(EVENT)
      .should().dependOnClassesThat(dependOnExecutionMachinery())
      .as("com.barezen.core.event must not hold an executor, thread, queue, timer or scheduler: dispatch is"
          + " synchronous, so a bus that owns a thread owns a lifecycle it does not have (ADR-0017)");

  private static DescribedPredicate<JavaClass> dependOnExecutionMachinery() {
    // Folded rather than a regex: JavaClass.Predicates has simpleNameContaining but not simpleNameMatching, and a
    // name list is reviewable in the way a pattern is not. Each fragment names a kind of machinery the synchronous
    // contract rules out - a pool to submit to, a queue to park events in, a future to hand back instead of a result.
    DescribedPredicate<JavaClass> machinery = null;
    for (String fragment : java.util.List.of("Executor", "Thread", "Queue", "Future", "Timer", "Scheduler",
        "Semaphore")) {
      DescribedPredicate<JavaClass> candidate = JavaClass.Predicates.simpleNameContaining(fragment);
      machinery = machinery == null ? candidate : machinery.or(candidate);
    }
    return JavaClass.Predicates.resideInAnyPackage("java.util.concurrent..").and(machinery);
  }

  /**
   * the lifecycle engine does not read configuration.
   *
   * <p>Phase 2.2 left a {@code Builder.configuration(Configuration)} on the orchestrator, which made {@code kernel}
   * depend on {@code config} and put a live configuration handle inside the lifecycle engine. ADR-0015 removed it:
   * an application resolves its snapshot in its own assembly code and hands over a {@code ThreadPolicy}, so the
   * engine receives a decision instead of a bag of keys it could go back to. The same rule stops it being added
   * back under a different name, and it is the {@code kernel} half of the boundary ADR-0013 describes; the
   * {@code di} half is {@link #di_is_a_leaf_of_the_internal_graph}.
   */
  @ArchTest
  static final ArchRule the_lifecycle_engine_does_not_read_configuration = noClasses()
      .that().resideInAnyPackage(KERNEL)
      .should().dependOnClassesThat().resideInAnyPackage(CONFIG)
      .as("com.barezen.core.kernel may not depend on com.barezen.core.config (ADR-0013, ADR-0015)");

  /**
   * the internal package graph is one directional and acyclic.
   *
   * <p>{@code Config} is reachable only from {@code Concurrent}, and {@code Concurrent} only from {@code Kernel}:
   * that is the assembly chain written as a layer rule, where each step hands a decided value to the step below it
   * rather than a reference it would have to interpret. {@code Runtime} stays reachable from both because it
   * reports what the JVM is instead of what the operator configured, and {@code Error} is the shared leaf.
   *
   * <p>{@code Kernel} is reachable from {@code Plugin} and from nowhere else, and that is the entire plugin edge:
   * {@code Plugin extends LifecycleParticipant}. A layer rule cannot say "one type from that package", so
   * {@link #the_plugin_spi_reaches_only_the_participant_interface} narrows what it permits to a single interface,
   * and {@code Di} stays sealed because a container that can reach lifecycle phases is a second lifecycle engine
   * (ADR-0014, ADR-0015, ADR-0016).
   *
   * <p>{@code Event} is sealed for the opposite reason: it is a capability, and a capability that can reach the
   * lifecycle engine or the assembler would be handed the application's ownership decisions. Nothing reaches it
   * either, so the two rules about it are a conjunction of "no outgoing edges" and "no incoming edges", which is why
   * the layer list below says {@code mayNotBeAccessedByAnyLayer} twice (ADR-0017).
   *
   * <p>There is no {@code App} layer. The application's assembly code lives in the application, outside core, so
   * a layer for it here would describe a package that does not exist (ADR-0015).
   */
  @ArchTest
  static final ArchRule core_is_free_of_cycles = Architectures.layeredArchitecture()
      .consideringOnlyDependenciesInAnyPackage("com.barezen.core..")
      .layer("Error").definedBy(ERROR)
      .layer("Runtime").definedBy(RUNTIME)
      .layer("Config").definedBy(CONFIG)
      .layer("Concurrent").definedBy(CONCURRENT)
      .layer("Di").definedBy(DI)
      .layer("Event").definedBy(EVENT)
      .layer("Kernel").definedBy(KERNEL)
      .layer("Plugin").definedBy(PLUGIN)
      .whereLayer("Kernel").mayOnlyBeAccessedByLayers("Plugin")
      .whereLayer("Di").mayNotBeAccessedByAnyLayer()
      .whereLayer("Event").mayNotBeAccessedByAnyLayer()
      .whereLayer("Plugin").mayNotBeAccessedByAnyLayer()
      .whereLayer("Concurrent").mayOnlyBeAccessedByLayers("Kernel")
      .whereLayer("Config").mayOnlyBeAccessedByLayers("Concurrent")
      .whereLayer("Runtime").mayOnlyBeAccessedByLayers("Kernel", "Concurrent")
      .whereLayer("Error").mayOnlyBeAccessedByLayers("Kernel", "Concurrent", "Config", "Di")
      .as("core packages must form a one directional graph with kernel, the assembler, the event bus and the plugin"
          + " SPI at the top");
}
