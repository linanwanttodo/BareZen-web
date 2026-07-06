package com.barezen.http.servlet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The boundaries this integration is allowed to touch, made mechanical.
 *
 * <p>Analyzed over {@code com.barezen} so the rules that matter most — "does the rest of the framework name a
 * container?" — see both sides at once. Some of them are also enforced by javac, because this module's test classpath
 * is not on theirs: {@code barezen-web} declares no dependency at all and {@code barezen-core} declares none on a
 * server, so a framework class importing {@code jakarta.servlet} does not compile. The rules are written anyway, for
 * the same reason ADR-0018 wrote its own: a pom is edited by one line, and the reviewer should see the rule that line
 * broke rather than the build failure it causes three modules later.
 *
 * <p>Test classes are excluded: the fixtures here deliberately name Tomcat's own classes, which is the point of a
 * container-level test and not a dependency of the shipped artifact.
 */
@AnalyzeClasses(
    packages = "com.barezen",
    importOptions = ImportOption.DoNotIncludeTests.class)
class BarezenHttpServletArchitectureTest {

  private static final String SERVLET = "com.barezen.http.servlet..";

  /**
   * nothing outside this module names a servlet container.
   *
   * <p>The claim ADR-0018 §12 exists to keep: a contract that imports a server cannot be implemented by a different one.
   * Here it is the same claim pointed the other way — the container API stops at this package, so the router, the
   * security decorator, the data layer and the plugins never learn which server they run under, and an application can
   * choose between the JDK adapter and a Tomcat deployment by changing what it constructs rather than by rewriting
   * anything. Violated by {@code Router} taking a {@codeHttpServletRequest} parameter, which is the shape every
   * "just one convenience method" drift takes.
   */
  @ArchTest
  static final ArchRule no_other_module_names_the_servlet_api = noClasses()
      .that().resideOutsideOfPackage(SERVLET)
      .should().dependOnClassesThat().resideInAnyPackage("jakarta.servlet..", "javax.servlet..")
      .as("the container API is this integration's to know and nobody else's (ADR-0018 §12, ADR-0049)")
      .allowEmptyShould(true);

  /**
   * this integration knows the contract, the log facade and the container — and nothing else.
   *
   * <p>The allowed set is the module's three dependencies written as a rule. What it refuses is every neighbour: the
   * kernel (a servlet that grabs a {@code Configuration} or an orchestrator at request time), the security module (an
   * integration that authenticates before the router has been asked becomes a middleware chain nobody designed, ADR-0033),
   * the data layer, the plugins, and the other adapter. An application that wants security wraps its handlers; this
   * class is not where that decision arrives.
   */
  @ArchTest
  static final ArchRule this_integration_depends_on_the_contract_and_the_facade_only = noClasses()
      .that().resideInAPackage(SERVLET)
      .should().dependOnClassesThat().resideInAnyPackage(
          "com.barezen.core..", "com.barezen.security..", "com.barezen.data..", "com.barezen.plugins..",
          "com.barezen.testkit..", "com.barezen.api..")
      .as("the servlet integration sits on the web contract alone, exactly as the security module does (ADR-0033)")
      .allowEmptyShould(true);

  /**
   * the two servers never meet.
   *
   * <p>{@code com.sun.net.httpserver} belongs to {@code barezen-http-jdk} and to nothing else (ADR-0021). A servlet
   * integration that also started a JDK server would be one artifact deciding two deployment models, and the second
   * adapter's reason for existing is that a deployment picks one.
   */
  @ArchTest
  static final ArchRule this_integration_never_names_the_jdk_server = noClasses()
      .should().dependOnClassesThat().resideInAnyPackage("com.sun.net.httpserver..", "sun..")
      .as("one module per server: the servlet integration does not also run com.sun.net.httpserver (ADR-0021)")
      .allowEmptyShould(true);

  /**
   * no request-scoped state parked on a thread.
   *
   * <p>Same rule as security, data, plugins and testkit, and the same reason: a servlet container reuses threads across
   * requests, so an identity left on one is the next request's identity. Everything this module passes travels as an
   * argument of {@code handle} (ADR-0033, ADR-0034).
   */
  @ArchTest
  static final ArchRule no_thread_local = noClasses()
      .that().resideInAPackage(SERVLET)
      .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ThreadLocal")
      .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.InheritableThreadLocal")
      .as("state travels as an argument, never on a pooled container thread (ADR-0034)")
      .allowEmptyShould(true);

  /**
   * no hidden configuration.
   *
   * <p>The one knob this module has — the request-body limit — is a container init-parameter resolved once in
   * {@code init()}, which is visible in the deployment descriptor and settable by a test. A
   * {@code System.getProperty("barezen...")} here would be a knob no reviewer sees and no deployment owns (ADR-0013,
   * ADR-0031).
   */
  @ArchTest
  static final ArchRule no_system_property_or_environment_reads = noClasses()
      .that().resideInAPackage(SERVLET)
      .should().callMethod(System.class, "getProperty", String.class)
      .orShould().callMethod(System.class, "getenv", String.class)
      .orShould().callMethod(System.class, "getenv")
      .as("configuration arrives through the container's own mechanism, resolved at assembly time (ADR-0013)");

  /**
   * nothing mutable is shared.
   *
   * <p>One servlet instance serves every request concurrently, so a static non-final field is state every request can
   * change and no owner can see. The final constants — the attribute name, the parameter name, the method order, the
   * logger — are the legal form of a static (ADR-0042's same rule for plugins).
   */
  @ArchTest
  static final ArchRule no_static_mutable_state = noFields()
      .that().areDeclaredInClassesThat().resideInAPackage(SERVLET)
      .and().areNotFinal()
      .should().beStatic()
      .as("state lives in the servlet instance, where the container's own lifecycle owns it")
      .allowEmptyShould(true);

  /**
   * the servlet implements nothing the framework offers.
   *
   * <p>It is not a {@code LifecycleParticipant} (the container starts and stops it; a second lifecycle would be ADR-0003's
   * two-engine disagreement in one class), not an {@code HttpServerAdapter} (ADR-0024's contract — one instance, one port,
   * {@code port()}, no restart — cannot be honestly answered by something a container owns), and not
   * {@code AutoCloseable} "for symmetry". The way any of those erodes is an {@code implements} clause, so that is what
   * is forbidden.
   */
  @ArchTest
  static final ArchRule the_servlet_is_neither_a_participant_nor_an_adapter = noClasses()
      .that().resideInAPackage(SERVLET)
      .should().beAssignableTo("com.barezen.core.kernel.LifecycleParticipant")
      .orShould().beAssignableTo("com.barezen.web.HttpServerAdapter")
      .orShould().beAssignableTo("com.barezen.web.Handler")
      .orShould().beAssignableTo("com.barezen.web.RouteContributor")
      .as("a servlet is driven by its container and routes through the contract; it is not a framework component"
          + " (ADR-0024, ADR-0049)")
      .allowEmptyShould(true);

  /**
   * the shipped surface is one class.
   *
   * <p>{@link BarezenServlet} plus two package-private bridge classes. A public {@code ServletRequestBuilder}, a
   * {@code BarezenFilter}, a {@code RouterServletContextListener} or a {@code ServletAdapters} factory would each be a
   * second way to do what one constructor and one attribute already do, and every public type in this build needs a
   * consumer today (ADR-0018 §16's rule, applied outside the contract).
   */
  @ArchTest
  static final ArchRule the_only_public_type_here_is_the_servlet = noClasses()
      .that().resideInAPackage(SERVLET)
      .and().areTopLevelClasses()
      .and().doNotHaveFullyQualifiedName(BarezenServlet.class.getName())
      .should().bePublic()
      .as("the integration's public surface is BarezenServlet alone (ADR-0049)")
      .allowEmptyShould(true);
}
