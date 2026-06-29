package com.barezen.http.jdk;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.barezen.web.HttpServerAdapter;
import com.barezen.web.HttpServerAdapterProvider;
import com.barezen.web.Router;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
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
import java.util.List;

/**
 * The boundary this phase is actually claiming: an HTTP server is an adapter, and an adapter is a leaf.
 *
 * <p>These are the rules the phase's §19 asks for, and they are written against <em>packages</em> rather than against
 * this module's class names. Rule 1 is the difference most clearly: it forbids anything in {@code com.barezen} outside
 * {@code com.barezen.http..} from naming {@code com.sun.net.httpserver}, so a second JDK adapter in a new package fails
 * the rule and a Jetty adapter in a new package passes it without an edit here. A rule of the form "only
 * {@code JdkHttpServerAdapter} may import that package" would have to be edited every time an adapter is added, which is
 * how a boundary turns into a list.
 *
 * <p>Analyzed over the whole {@code com.barezen} namespace, which this module's test classpath is the only place in the
 * reactor holding all three modules at once: {@code barezen-web} declares no dependency on core, and {@code barezen-core}
 * knows nothing of either. That is why the cross-module rules live here rather than in the module whose name appears in
 * them — it is the one test class that can see both sides of each edge (ADR-0021 §3).
 *
 * <p>Test classes are excluded with {@link ImportOption.DoNotIncludeTests}, as in the other two suites. The fixtures use
 * {@code java.net} sockets, reflection and a logback appender on purpose, and these rules measure the shipped artifact —
 * which is also why a violation in a main source file would fail compilation before it failed a rule here.
 *
 * <p>Two rules mention {@code $jacocoInit}. JaCoCo's agent injects that method into every instrumented class and
 * reflection and ArchUnit both see it, so a rule over a member's <em>name</em> or signature has to skip it; that is an
 * artifact of measuring a coverage-instrumented class file, not of the code.
 */
@AnalyzeClasses(
    packages = "com.barezen",
    importOptions = ImportOption.DoNotIncludeTests.class)
class BarezenHttpJdkArchitectureTest {

  private static final String HTTP = "com.barezen.http..";
  private static final String WEB = "com.barezen.web..";
  private static final String CORE = "com.barezen.core..";
  private static final String JDK_SERVER = "com.sun.net.httpserver..";
  private static final String JDK_SERVER_PREFIX = "com.sun.net.httpserver";

  /** Rule 1, and the phase's title claim: no module outside an HTTP adapter may name the JDK's HTTP server. */
  @ArchTest
  static final ArchRule only_an_http_adapter_module_may_name_the_jdk_http_server = noClasses()
      .that().resideOutsideOfPackages(HTTP)
      .should().dependOnClassesThat().resideInAnyPackage(JDK_SERVER, "sun.net.httpserver..")
      .as("nothing outside an HTTP adapter module mentions com.sun.net.httpserver, barezen-web included: a contract"
          + " that names a server cannot be implemented by a different one (ADR-0018 §12, ADR-0021 §3)")
      .allowEmptyShould(true);

  /**
   * The same edge seen from the inside, so that the allowed package is named rather than inferred from a negation.
   *
   * <p>A condition rather than a {@code that()} clause, because ArchUnit's {@code ClassesThat} offers no "depends on"
   * selector — and reading it from the dependency side is the honest form anyway: the rule asks each class what it
   * reaches for and then asks where the class lives.
   */
  @ArchTest
  static final ArchRule the_jdk_server_is_reached_only_from_inside_the_adapter = classes()
      .should(onlyReachTheJdkServerFromInsideAnAdapter())
      .as("com.barezen.http.. is the package that touches com.sun.net.httpserver; an adapter added elsewhere is a new"
          + " adapter module, not an exception to this rule (ADR-0021 §3)");

  private static ArchCondition<JavaClass> onlyReachTheJdkServerFromInsideAnAdapter() {
    return new ArchCondition<JavaClass>("reach com.sun.net.httpserver only from inside an HTTP adapter package") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        boolean mentionsTheServer = type.getDirectDependenciesFromSelf().stream()
            .anyMatch(dependency -> dependency.getTargetClass().getName().startsWith(JDK_SERVER_PREFIX));
        if (!mentionsTheServer) {
          return;
        }
        boolean insideAnAdapter = type.getPackageName().startsWith("com.barezen.http");
        events.add(insideAnAdapter
            ? SimpleConditionEvent.satisfied(type, type.getSimpleName() + " names the JDK server inside the adapter")
            : SimpleConditionEvent.violated(type, type.getFullName() + " reaches com.sun.net.httpserver from outside"
                + " com.barezen.http.. — a contract, a plugin or a persistence module that names a server cannot be"
                + " served by a different one (ADR-0018 §12, ADR-0021 §3)"));
      }
    };
  }

  /**
   * Rules 5 and 6: the reserved packages never point at an adapter.
   *
   * <p>{@code com.barezen.security..} and {@code com.barezen.data..} do not exist yet, and both halves of this rule are
   * written anyway — a rule over classes that exist cannot see the one that was just added, and the failure this guards
   * against arrives months later as a "temporary" import inside a persistence module.
   */
  @ArchTest
  static final ArchRule no_other_barezen_module_may_depend_on_an_http_adapter = noClasses()
      .that().resideOutsideOfPackages(HTTP)
      .should().dependOnClassesThat().resideInAnyPackage(HTTP, "com.barezen.security..", "com.barezen.data..",
          "com.barezen.api..")
      .as("an adapter is a leaf: core, web and every future module stay independent of it, so deleting"
          + " barezen-http-jdk from a build removes a server and changes no contract (ADR-0021 §3)")
      .allowEmptyShould(true);

  /** Rule 2 and rule 3 together: the direction is core ← web ← adapter, never the reverse. */
  @ArchTest
  static final ArchRule the_dependency_direction_is_core_then_web_then_adapter = noClasses()
      .that().resideInAPackage(CORE)
      .should().dependOnClassesThat().resideInAnyPackage(WEB, HTTP)
      .as("barezen-core depends on neither the HTTP contract nor an adapter, so the contract stays implementable by a"
          + " server that has never heard of core (ADR-0018 §12)")
      .allowEmptyShould(true);

  /**
   * Rule 4 from the contract's own side, because the edge is worth two rules.
   *
   * <p>The compile classpath already forbids this — {@code barezen-web} declares no dependency on this module, so
   * {@code com.barezen.http} cannot be named there without editing a pom — and ADR-0018 §12 treats that as the stronger
   * enforcement. This rule is the reviewable statement of what the pom implies.
   */
  @ArchTest
  static final ArchRule the_contract_does_not_know_about_the_adapter = noClasses()
      .that().resideInAPackage(WEB)
      .should().dependOnClassesThat().resideInAnyPackage(HTTP, JDK_SERVER)
      .as("the contract names no server, no server package and no server implementation — proved by construction as"
          + " well as by this rule (ADR-0018 §12)")
      .allowEmptyShould(true);

  /** Rule 7: the plugin SPI stays out of the server business. */
  @ArchTest
  static final ArchRule plugins_neither_name_a_server_nor_become_one = noClasses()
      .that().resideInAnyPackage("com.barezen.core.plugin..", "com.barezen.plugin..")
      .should().dependOnClassesThat().resideInAnyPackage(JDK_SERVER, HTTP)
      .as("a Plugin registers routes through the contract and never reaches for a server, so a plugin works unchanged"
          + " under any adapter (ADR-0020 §7, ADR-0021 §3)")
      .allowEmptyShould(true);

  /**
   * Rule 8, from this side: an adapter implementation is not a lifecycle participant.
   *
   * <p>The contract side of the same decision — {@code HttpServerAdapter} extends nothing but {@code AutoCloseable} —
   * cannot be expressed in {@code BarezenWebArchitectureTest}, because a web rule would have to name a core type that
   * is not on the web module's classpath. It is asserted here, over both modules at once, and again as a reflection
   * assertion in the lifecycle test.
   */
  @ArchTest
  static final ArchRule an_adapter_is_not_a_lifecycle_participant = classes()
      .that().areAssignableTo(HttpServerAdapter.class)
      .should(notBeAssignableToLifecycleParticipant())
      .as("the orchestrator owns the application's phases and an adapter owns a port; joining them by inheritance"
          + " would make a server restart on the orchestrator's schedule rather than the assembly's (ADR-0024 §2)")
      .allowEmptyShould(true);

  private static final String LIFECYCLE_PARTICIPANT = "com.barezen.core.kernel.LifecycleParticipant";

  private static ArchCondition<JavaClass> notBeAssignableToLifecycleParticipant() {
    return new ArchCondition<JavaClass>("not be assignable to " + LIFECYCLE_PARTICIPANT) {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        boolean participates = type.isAssignableTo(JavaClass.Predicates.assignableTo(LIFECYCLE_PARTICIPANT));
        events.add(participates
            ? SimpleConditionEvent.violated(type, type.getFullName() + " implements LifecycleParticipant, so starting"
                + " the application would start a server on the orchestrator's schedule rather than the assembly's"
                + " (ADR-0024 §2, §8.8)")
            : SimpleConditionEvent.satisfied(type, type.getSimpleName() + " owns no lifecycle phase"));
      }
    };
  }

  /** Rule 8's other half: nothing in an adapter implements the plugin SPI either. */
  @ArchTest
  static final ArchRule an_adapter_is_not_a_plugin = noClasses()
      .that().resideInAPackage(HTTP)
      .should().implement("com.barezen.core.plugin.Plugin")
      .as("an adapter is created by the application's assembly with an address and a route table, not discovered and"
          + " started by a plugin manager (ADR-0025 §4)")
      .allowEmptyShould(true);

  /**
   * Rule 9: a provider creates; it does not locate.
   *
   * <p>Scoped to this module's provider implementation rather than to every method the contract declares, because the
   * web suite already refuses a third member on {@code HttpServerAdapterProvider} itself. What web cannot see is the
   * implementation reaching for a container, which is what this rule is for.
   */
  @ArchTest
  static final ArchRule a_provider_never_looks_anything_up = noClasses()
      .that().resideInAPackage(HTTP)
      .should().dependOnClassesThat().haveSimpleNameEndingWith("Locator")
      .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Registry")
      .orShould().dependOnClassesThat().haveFullyQualifiedName("com.barezen.core.di.ServiceAssembly")
      .orShould().dependOnClassesThat().haveFullyQualifiedName("com.barezen.core.di.Services")
      .orShould().dependOnClassesThat().haveFullyQualifiedName("com.barezen.core.kernel.LifecycleOrchestrator")
      .as("an adapter provider offers an id and a creation and reaches for nothing else; a container, a registry or"
          + " the orchestrator inside a provider is the ServiceLocator this framework refuses to build (ADR-0025 §4)"
          + " — and §8.8's forbidden direction for the orchestrator too")
      .allowEmptyShould(true);

  /** Rule 9's positive form: the implementation's own declared methods are exactly the two the contract allows. */
  @ArchTest
  static final ArchRule the_provider_implementation_declares_nothing_else = methods()
      .that(JavaMember.Predicates.declaredIn(JavaClass.Predicates.equivalentTo(JdkHttpServerAdapterProvider.class)))
      .should(beIdCreateToStringOrNothing())
      .as("JdkHttpServerAdapterProvider declares id(), create(...) and toString(); a fourth member is a lookup or a"
          + " stored server waiting to be discovered twice (ADR-0025 §4, §6)");

  private static ArchCondition<JavaMethod> beIdCreateToStringOrNothing() {
    List<String> allowed = List.of("id", "create", "toString");
    return new ArchCondition<JavaMethod>("be id(), create(...) or toString()") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        boolean instrumented = method.getName().equals("$jacocoInit");
        boolean violates = !instrumented && !allowed.contains(method.getName());
        events.add(violates
            ? SimpleConditionEvent.violated(method, method.getFullName() + " is a member beyond the two the provider"
                + " contract has. A provider that can be asked for an object it was never handed is a service locator"
                + " wearing a factory's name (ADR-0025 §4)")
            : SimpleConditionEvent.satisfied(method, method.getName() + " is a provider operation or the summary"));
      }
    };
  }

  /**
   * Rule 10: a provider holds nothing.
   *
   * <p>A field of type {@link HttpServerAdapter} would make a provider the place a running server lives — a registry
   * reached by discovery rather than by construction. A {@code static final} constant of type {@code String} is allowed,
   * because {@code ID} is a string rather than a server.
   */
  @ArchTest
  static final ArchRule no_provider_holds_a_server = noFieldsOfTypeAServerOrARouteTable()
      .as("a provider is stateless: create() returns a fresh adapter and keeps no reference to it, so two callers get"
          + " two servers and neither can reach the other's (ADR-0025 §8)");

  private static ArchRule noFieldsOfTypeAServerOrARouteTable() {
    return fields()
        .that().areDeclaredInClassesThat().areAssignableTo(HttpServerAdapterProvider.class)
        .should(notHoldARuntime())
        .as("no field of a provider holds a server, a provider, a route table or a JDK HttpServer")
        .allowEmptyShould(true);
  }

  private static ArchCondition<JavaField> notHoldARuntime() {
    List<String> runtimeTypes = List.of(HttpServerAdapter.class.getName(),
        HttpServerAdapterProvider.class.getName(), Router.class.getName(),
        "com.sun.net.httpserver.HttpServer");
    return new ArchCondition<JavaField>("not hold a server, a provider or a route table") {
      @Override
      public void check(JavaField field, ConditionEvents events) {
        String type = field.getRawType().getName();
        boolean violates = runtimeTypes.contains(type);
        events.add(violates
            ? SimpleConditionEvent.violated(field, field.getFullName() + " holds a " + type + ". A type that stores the"
                + " runtime it describes is a singleton holder, and a singleton holder is how a test ends up with a"
                + " server it never asked for (ADR-0025 §8)")
            : SimpleConditionEvent.satisfied(field, field.getName() + " holds no runtime"));
      }
    };
  }

  /**
   * Nothing in this module is reachable as a global.
   *
   * <p>The rule above bounds the mutable <em>instance</em> state; this one refuses mutable <em>static</em> state, which
   * is the half that a lifecycle lock cannot protect. A non-final static here would be a value one request could hand to
   * the next without either asking, and a static holding a server or a route table would be a registry reached by
   * discovery rather than by construction (ADR-0025 §8, ADR-0021 §1).
   */
  @ArchTest
  static final ArchRule nothing_in_this_module_is_a_mutable_global = fields()
      .that().areDeclaredInClassesThat().resideInAPackage(HTTP)
      .and().areStatic()
      .should().haveModifier(JavaModifier.FINAL)
      .as("every static field in an HTTP adapter module is a constant: a mutable static is global state reached without"
          + " being asked for, which is the ServiceLocator-shaped failure ADR-0025 §8 refuses (ADR-0021 §1)")
      .allowEmptyShould(true);

  /** The mutable state of this module, bounded rather than banned. */
  @ArchTest
  static final ArchRule the_mutable_state_is_the_lifecycle_and_nothing_else = fields()
      .that().areDeclaredInClassesThat().resideInAPackage(HTTP)
      .and().areNotFinal()
      .should(beLifecycleFieldsOfTheAdapter())
      .as("the only non-final fields in this module are JdkHttpServerAdapter's state and server, both private and both"
          + " written only under the lifecycle lock; a non-final field on the dispatcher or the bridge would be a"
          + " request's value surviving into the next request (ADR-0024 §1, ADR-0021 §1)")
      .allowEmptyShould(true);

  private static ArchCondition<JavaField> beLifecycleFieldsOfTheAdapter() {
    List<String> lifecycle = List.of("state", "server");
    return new ArchCondition<JavaField>("be the adapter's own guarded lifecycle fields") {
      @Override
      public void check(JavaField field, ConditionEvents events) {
        boolean guarded = field.getOwner().getName().equals(JdkHttpServerAdapter.class.getName())
            && field.getModifiers().contains(JavaModifier.PRIVATE)
            && lifecycle.contains(field.getName());
        events.add(guarded
            ? SimpleConditionEvent.satisfied(field, field.getName() + " is guarded by the lifecycle lock")
            : SimpleConditionEvent.violated(field, field.getFullName() + " is a non-final field outside the adapter's"
                + " guarded lifecycle. Nothing on the request path may be writable after start()"));
      }
    };
  }

  /**
   * This adapter's own dependency surface, as an allow-list rather than a blacklist.
   *
   * <p>Four JDK packages, the contract, core's thread providers, the SLF4J facade and the JDK server. Everything else is
   * refused by one sentence, including every server library, every JSON library and every framework with a name in it.
   * An allow-list is the right shape for the reason ADR-0018 §12 gives: a Netty import and a {@code MessageDigest} are
   * both leaks, and only one of them is on a blacklist anyone would think to write.
   */
  @ArchTest
  static final ArchRule the_adapter_depends_only_on_the_contract_core_slf4j_and_the_jdk = noClasses()
      .that().resideInAPackage(HTTP)
      .should().dependOnClassesThat(outsideTheAllowedAdapterBase())
      .as("barezen-http-jdk may use java.lang, java.io, java.net, java.util, java.nio.charset, java.time,"
          + " com.barezen.web, com.barezen.core, its own package, org.slf4j and com.sun.net.httpserver — nothing else,"
          + " and specifically no server library (ADR-0021 §2)");

  private static DescribedPredicate<JavaClass> outsideTheAllowedAdapterBase() {
    return resideOutsideOfPackages("java.lang..", "java.io..", "java.net..", "java.util..", "java.nio.charset..",
        "java.time..", "com.barezen.web..", "com.barezen.core..", "com.barezen.http..", "org.slf4j..", JDK_SERVER);
  }

  /**
   * The frameworks this adapter must stay out of, named rather than left to the allow-list above.
   *
   * <p>Redundant with the previous rule and written anyway: a reviewer asking "could this module quietly acquire Spring
   * or Netty?" should find the answer in one sentence rather than by working out what an allow-list excludes.
   */
  @ArchTest
  static final ArchRule no_server_framework_or_container_appears_here = noClasses()
      .should().dependOnClassesThat().resideInAnyPackage(
          "org.springframework..", "io.netty..", "org.eclipse.jetty..", "io.undertow..", "io.vertx..",
          "jakarta.servlet..", "javax.servlet..", "com.fasterxml..", "org.apache..", "org.hibernate..",
          "org.junit..", "org.assertj..", "com.tngtech..", "ch.qos..")
      .as("a second adapter replaces this module; a dependency on another server, a container or a test library inside"
          + " a shipped class is the beginning of a framework rather than an adapter (ADR-0018 §12, ADR-0021 §2)")
      .allowEmptyShould(true);

  /**
   * The adapter borrows threads rather than starting them.
   *
   * <p>Every executor this server runs on comes from a {@code ThreadProvider} — created here when the adapter owns one,
   * borrowed otherwise. {@code Executors}, {@code ThreadPoolExecutor}, {@code ForkJoinPool} and a bare
   * {@code java.lang.Thread} are each a thread that {@code stop()} would not know about, which is the exact failure the
   * ownership rule in ADR-0024 §4 exists to prevent. That is why it is a rule rather than a paragraph.
   */
  @ArchTest
  static final ArchRule the_adapter_creates_no_thread_of_its_own = noClasses()
      .that().resideInAPackage(HTTP)
      .should().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.Executors")
      .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.ThreadPoolExecutor")
      .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.ForkJoinPool")
      .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.ScheduledThreadPoolExecutor")
      .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Thread")
      .as("requests run on an executor obtained from a ThreadProvider, because that is the only way the ownership rule"
          + " in ADR-0024 §4 can be honoured by stop()")
      .allowEmptyShould(true);

  /** No reflection, no scanning, no dynamic loading, no per-request holder: the adapter is given what it serves. */
  @ArchTest
  static final ArchRule the_adapter_discovers_nothing_it_was_not_given = noClasses()
      .that().resideInAPackage(HTTP)
      .should().dependOnClassesThat().resideInAnyPackage("java.lang.reflect..", "java.lang.invoke..",
          "java.net.URLClassLoader..", "java.util.concurrent.atomic..")
      .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.ServiceLoader")
      .as("an adapter takes an address and a Router and nothing else; reflection, classpath scanning, its own"
          + " discovery, a dynamic classloader and a request-scoped mutable holder all belong above this boundary"
          + " (ADR-0021 §4, §6, ADR-0018 §9)")
      .allowEmptyShould(true);

  /**
   * {@code com.sun.net.httpserver} types never reach a public signature.
   *
   * <p>The bridge and the dispatcher are package private, and this rule is what keeps the JDK inside them: an exchange
   * or a server appearing in a public member would put the JDK back where an application can read it, which is the thing
   * the adapter was written to hide. A dependency rule would not catch this — the adapter legitimately depends on the
   * package — so the condition reads signatures rather than imports.
   */
  @ArchTest
  static final ArchRule the_jdk_types_never_appear_in_a_public_signature = classes()
      .that().resideInAPackage(HTTP).and().arePublic()
      .should(notMentionTheJdkServerInTheirSurface())
      .as("the two public types of this module speak Request, Response, Router, InetSocketAddress and ThreadProvider;"
          + " an HttpExchange or an HttpServer in a public signature makes an application that reads it unable to switch"
          + " adapters, which is the only reason the contract exists (ADR-0021 §4)");

  private static ArchCondition<JavaClass> notMentionTheJdkServerInTheirSurface() {
    return new ArchCondition<JavaClass>("expose no com.sun.net.httpserver type") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        type.getMethods().forEach(method -> {
          if (method.getName().equals("$jacocoInit")) {
            return;
          }
          List<String> jdkTypes = new java.util.ArrayList<>();
          if (method.getRawReturnType().getName().startsWith(JDK_SERVER_PREFIX)) {
            jdkTypes.add(method.getRawReturnType().getName());
          }
          method.getRawParameterTypes().stream()
              .filter(parameter -> parameter.getName().startsWith(JDK_SERVER_PREFIX))
              .forEach(parameter -> jdkTypes.add(parameter.getName()));
          boolean violates = !jdkTypes.isEmpty();
          events.add(violates
              ? SimpleConditionEvent.violated(method, method.getFullName() + " exposes " + jdkTypes
                  + " in its public signature")
              : SimpleConditionEvent.satisfied(method, method.getName() + " names no server type"));
        });
      }
    };
  }

  /**
   * The public surface of this module, checked against the §25 list.
   *
   * <p>A name rule, deliberately: unlike the package rules above, this one polishes an artifact's published API rather
   * than a boundary between modules, so "these two types and no others" is the correct form and a third public type is
   * an ADR-sized decision.
   */
  @ArchTest
  static final ArchRule the_module_exposes_exactly_two_public_types = classes()
      .that().resideInAPackage(HTTP).and().arePublic()
      .should().haveSimpleName("JdkHttpServerAdapter")
      .orShould().haveSimpleName("JdkHttpServerAdapterProvider")
      .as("barezen-http-jdk publishes JdkHttpServerAdapter and JdkHttpServerAdapterProvider; the bridge, the"
          + " dispatcher and the adapter's state stay inside the package (ADR-0021 §4, §6, §25)");

  /** The one context registration, and the reason the JDK's filter chain is not this contract's dispatch mechanism. */
  @ArchTest
  static final ArchRule no_filter_chain_is_built_here = noClasses()
      .that().resideInAPackage(HTTP)
      .should().dependOnClassesThat().haveSimpleName("Filter")
      .as("the adapter registers one context and no filter, because a com.sun Filter is a second dispatch mechanism the"
          + " contract has no type for, and a middleware phase would have to delete it rather than reuse it"
          + " (ADR-0021 §6)")
      .allowEmptyShould(true);

  @ArchTest
  static final ArchRule no_middleware_shaped_types_appear_anywhere_yet = classes()
      .that().resideInAPackage("com.barezen..")
      .should(notBeNamedLikeAMiddlewareType())
      .as("Filter, Interceptor, Middleware, HttpContext, RequestContext and HandlerChain are not this phase's"
          + " vocabulary in any module, the adapter included (ADR-0018 §10, §11)")
      .allowEmptyShould(true);

  private static ArchCondition<JavaClass> notBeNamedLikeAMiddlewareType() {
    List<String> forbidden = List.of("Filter", "Interceptor", "Middleware", "HttpContext", "RequestContext",
        "HandlerChain");
    return new ArchCondition<JavaClass>("not be named like a middleware type") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        if (!type.isTopLevelClass()) {
          return;
        }
        String matched = forbidden.stream()
            .filter(name -> type.getSimpleName().endsWith(name))
            .findFirst()
            .orElse(null);
        events.add(matched == null
            ? SimpleConditionEvent.satisfied(type, type.getSimpleName() + " is not middleware vocabulary")
            : SimpleConditionEvent.violated(type, type.getFullName() + " is named like a " + matched + ". A chain"
                + " around the handler is a second dispatch mechanism the contract has no type for (ADR-0018 §10)"));
      }
    };
  }

  /**
   * The two internal types of this module are not extension points.
   *
   * <p>Package private and final together: a package-private but open class would still be subclassable by the next file
   * added to this package, which is how a dispatcher becomes an override point for an error format. An application that
   * wants different error text writes a {@code Handler}; an application that wants a different server writes an
   * adapter (ADR-0022 §9, ADR-0023 §8).
   */
  @ArchTest
  static final ArchRule the_bridge_and_the_dispatcher_are_not_extension_points = classes()
      .that().resideInAPackage(HTTP).and().areNotPublic()
      .should().haveModifier(JavaModifier.FINAL)
      .as("JdkExchangeBridge and JdkRequestDispatcher are package-private and final")
      .allowEmptyShould(true);

  /**
   * The lifecycle vocabulary this adapter refuses to add, in one sentence rather than six.
   *
   * <p>{@code restart()}, {@code shutdown()}, {@code shutdownNow()}, {@code awaitTermination()}, {@code startAsync()}
   * and a {@code server()} accessor are the members that would turn a start/stop/isRunning/port contract into a
   * thread-pool facade. §5.1 names them and no dependency rule would notice one being added, so the names are refused
   * explicitly. {@code close()} is absent because it arrives from {@code AutoCloseable} through the contract and is
   * declared nowhere in this package.
   */
  @ArchTest
  static final ArchRule no_method_offers_a_second_lifecycle_shape = methods()
      .that().areDeclaredInClassesThat().resideInAPackage(HTTP)
      .should(notBeRestartOrPoolVocabulary())
      .as("an adapter is started, stopped and asked; a restart, a pool shutdown, a wait or a handle to the server"
          + " behind the adapter is a second lifecycle owned from the wrong place (ADR-0024 §5, §6, ADR-0021 §4)")
      .allowEmptyShould(true);

  private static ArchCondition<JavaMethod> notBeRestartOrPoolVocabulary() {
    List<String> forbidden = List.of("restart", "shutdown", "shutdownNow", "awaitTermination", "startAsync",
        "stopAsync", "server", "destroy", "close", "getServer", "httpServer");
    return new ArchCondition<JavaMethod>("not be named like a restart, a pool shutdown or a server accessor") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        boolean violates = forbidden.contains(method.getName());
        events.add(violates
            ? SimpleConditionEvent.violated(method, method.getFullName() + " is refused lifecycle vocabulary. One"
                + " instance is one server lifecycle; a caller that wants another one builds another adapter"
                + " (ADR-0024 §5)")
            : SimpleConditionEvent.satisfied(method, method.getName() + " is not a lifecycle synonym"));
      }
    };
  }

  /**
   * Nothing in this module reads or writes an environment variable or a system property.
   *
   * <p>The adapter has two constants — a backlog and a stop grace — and §8.4's argument for them is that they are not
   * configurable yet. A {@code System.getProperty} here would be a configuration system smuggled in through a
   * constant's declaration site, which is exactly the drift the phase's maxim warns about.
   */
  @ArchTest
  static final ArchRule the_adapter_reads_no_environment_and_no_properties = noClasses()
      .that().resideInAPackage(HTTP)
      .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.System")
      .as("an adapter is configured by the arguments it is constructed with; System.getenv and System.getProperty here"
          + " would be a configuration framework arriving as a side effect (ADR-0021 §4, ADR-0003)")
      .allowEmptyShould(true);
}
