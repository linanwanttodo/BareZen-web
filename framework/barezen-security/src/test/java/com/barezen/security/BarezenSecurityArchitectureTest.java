package com.barezen.security;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Executable form of the Phase 2.11 boundary: security is a decision about a request, not a framework service.
 *
 * <p>Every rule here exists so that the drift this module is most likely to suffer fails a build rather than a
 * review. The rules were checked by adding a deliberately violating class, watching it fail, and deleting the class.
 */
@AnalyzeClasses(
    packages = "com.barezen.security",
    importOptions = ImportOption.DoNotIncludeTests.class)
class BarezenSecurityArchitectureTest {

  /**
   * no thread-local, ever.
   *
   * <p>The single most damaging shortcut available to this module: identity stored on a thread looks request-scoped
   * and is not, because a server thread answers many requests. The design's answer is {@link SecuredHandler}, which
   * delivers the context as an argument, and this rule exists so the answer cannot quietly regress into a
   * {@code ThreadLocal<SecurityContext>} the day a handler author wants one more convenience.
   */
  @ArchTest
  static final ArchRule no_thread_local = noClasses()
      .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ThreadLocal")
      .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.InheritableThreadLocal")
      .as("identity must travel as an argument, never on a thread: a thread answers many requests and would leak one"
          + " request's principal into the next")
      .allowEmptyShould(true);

  /**
   * no static mutable identity: no static non-final field anywhere in the module.
   *
   * <p>A {@code static Principal currentUser} is the same leak as a {@code ThreadLocal}, without even the excuse of
   * being scoped to a thread. Constants are fine — a permission name harms nobody — so the rule is written against
   * state, not against the {@code static} keyword.
   */
  @ArchTest
  static final ArchRule no_static_mutable_state = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields()
      .that().areDeclaredInClassesThat().resideInAPackage("com.barezen.security..")
      .and().areNotFinal()
      .should().beStatic()
      .as("a static mutable field in a security module is a global currentUser wearing a different name")
      .allowEmptyShould(true);

  /**
   * no configuration back-channels.
   *
   * <p>Security parameters — iteration counts, token lengths, durations — are constructor arguments, because a
   * security decision that can be changed by a system property or an environment variable is a security decision an
   * operator cannot see. Configuration enters through {@code com.barezen.core.config.Configuration} in the
   * application that wires the module, never by the module reaching for the environment itself.
   */
  @ArchTest
  static final ArchRule no_system_property_or_environment_reads = noClasses()
      .should().callMethod(System.class, "getProperty", String.class)
      .orShould().callMethod(System.class, "getenv", String.class)
      .orShould().callMethod(System.class, "getenv")
      .as("security parameters are constructor arguments; a property nobody reviews must not loosen a hash or a"
          + " token (same rule as ADR-0016)");

  /**
   * the token and session domain does not know HTTP exists.
   *
   * <p>{@link SessionToken} and {@link SessionStore} must be usable by anything that needs a revocable session — a
   * CLI, a message consumer, a test — so they cannot import {@code com.barezen.web}, {@code HttpExchange}, a servlet
   * type or a cookie. The transport binds a token to a header at the edge; the domain stays ignorant, which is what
   * keeps the revocation model testable without a socket.
   */
  @ArchTest
  static final ArchRule the_session_domain_is_transport_ignorant = noClasses()
      .that().haveSimpleName("SessionToken").or().haveSimpleName("SessionStore")
      .should().dependOnClassesThat().resideInAnyPackage(
          "com.barezen.web..", "com.sun.net.httpserver..", "jakarta.servlet..", "javax.servlet..", "java.net..")
      .as("a session is a revocable server-side fact, not an HTTP header: the domain must not know the transport");

  /**
   * no password or token logging framework in the main sources: nothing to leak through.
   *
   * <p>The module's types hide secrets in {@code toString} by construction, and the strongest guarantee that no code
   * path prints one anyway is that the module cannot name a logger at all. slf4j stays a test-scope tool.
   */
  @ArchTest
  static final ArchRule no_logging_facade_in_main_sources = noClasses()
      .should().dependOnClassesThat().resideInAnyPackage("org.slf4j..", "java.util.logging..")
      .as("a secret that is never passed to a logger is a secret that is never logged")
      .allowEmptyShould(true);

  /**
   * the security domain does not reach for a database.
   *
   * <p>JDBC arrives in Phase 2.12 as its own module; a credential store that needs one implements {@link
   * Authentication} in the application or a plugin and is handed the data access object at construction. The module
   * that owns the decision must not own the connection.
   */
  @ArchTest
  static final ArchRule no_jdbc = noClasses()
      .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..")
      .as("the security module decides; persistence belongs to the application that wires it (2.12)")
      .allowEmptyShould(true);

  /**
   * the module stays below the adapter: it may use the web contract, never the server.
   *
   * <p>{@code barezen-http-jdk} is the transport; security policy applied there would vanish the day a second
   * adapter exists, which is why the middleware is a {@code Handler} decorator in this module instead.
   */
  @ArchTest
  static final ArchRule no_adapter_dependency = noClasses()
      .should().dependOnClassesThat().resideInAnyPackage("com.barezen.http..", "com.sun.net.httpserver..")
      .as("security is middleware on the web contract; an adapter holds no security policy (ADR-0018 §12)")
      .allowEmptyShould(true);
}
