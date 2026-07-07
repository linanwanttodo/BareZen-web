package com.barezen.content;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The content module's boundaries: it sits on the contract, it owns nothing else, and it is not a server.
 *
 * <p>Analyzed over {@code com.barezen} with test classes excluded, so the rules see the shipped artifact. Several of
 * them are also enforced by javac — {@code barezen-core}, {@code barezen-security} and the adapters are not
 * dependencies of this module at all — and they are written anyway for ADR-0018's reason: a pom line is one edit, and
 * the reviewer should read the rule that edit broke.
 */
@AnalyzeClasses(
    packages = "com.barezen",
    importOptions = ImportOption.DoNotIncludeTests.class)
class BarezenContentArchitectureTest {

  private static final String CONTENT = "com.barezen.content..";

  /**
   * the contract, and nothing above it.
   *
   * <p>Static content is a {@code Handler}, so it needs the web module and no more: not the kernel (a file handler that
   * reads {@code Configuration} would be a component configuring itself after assembly, ADR-0013), not security (an
   * authorization decision inside a file reader is a middleware chain arriving from below, ADR-0033), not a server
   * (ADR-0018 §12), and not the plugins (ADR-0042's direction).
   */
  @ArchTest
  static final ArchRule this_module_depends_on_the_contract_only = noClasses()
      .that().resideInAPackage(CONTENT)
      .should().dependOnClassesThat().resideInAnyPackage(
          "com.barezen.core..", "com.barezen.security..", "com.barezen.data..", "com.barezen.plugins..",
          "com.barezen.testkit..", "com.barezen.http..", "com.barezen.api..")
      .as("static content is a Handler over the web contract; it takes no kernel, no security, no adapter (ADR-0018)")
      .allowEmptyShould(true);

  /**
   * no server, no IO stack of its own beyond the file it was given.
   *
   * <p>{@code java.nio.file} is allowed here and forbidden in {@code barezen-web} for exactly this reason: the contract
   * must not know where a file is, and a capability module that serves files is the place that does. What stays
   * forbidden is a socket, a server class, or a platform type probe — {@code Files.probeContentType} would make the
   * answer depend on the host (ADR-0051).
   */
  @ArchTest
  static final ArchRule this_module_names_no_server_and_no_platform_guessing = noClasses()
      .that().resideInAPackage(CONTENT)
      .should().dependOnClassesThat().resideInAnyPackage(
          "com.sun.net.httpserver..", "java.net..", "sun..", "jdk..", "javax.net..")
      .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.URLConnection")
      .as("a file handler that reaches for a socket or a host MIME database has left its remit (ADR-0018 §12, ADR-0051)")
      .allowEmptyShould(true);

  /** nothing shared and mutable. */
  @ArchTest
  static final ArchRule no_static_mutable_state = noFields()
      .that().areDeclaredInClassesThat().resideInAPackage(CONTENT)
      .and().areNotFinal()
      .should().beStatic()
      .as("every value this module holds is a final one; a mutable static would be state between requests (ADR-0042)")
      .allowEmptyShould(true);

  /** no hidden configuration. */
  @ArchTest
  static final ArchRule no_system_property_or_environment_reads = noClasses()
      .that().resideInAPackage(CONTENT)
      .should().callMethod(System.class, "getProperty", String.class)
      .orShould().callMethod(System.class, "getenv", String.class)
      .orShould().callMethod(System.class, "getenv")
      .as("what to serve is a constructor argument; nothing is configured through the environment (ADR-0013)");

  /** no thread-scoped state. */
  @ArchTest
  static final ArchRule no_thread_local = noClasses()
      .that().resideInAPackage(CONTENT)
      .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ThreadLocal")
      .as("a response is built from arguments and returned, never parked on a thread (ADR-0034)")
      .allowEmptyShould(true);

  /**
   * one public type, because a handler that reaches the disk is not something to subclass.
   *
   * <p>A {@code StaticContentBuilder}, a {@code DirectoryHandler} or a {@code CachePolicy} type would each be a way to
   * ask for a feature this module decides not to have until a consumer names it (ADR-0018 §16's standard, applied
   * outside the contract).
   */
  @ArchTest
  static final ArchRule the_only_public_type_here_is_static_content = noClasses()
      .that().resideInAPackage(CONTENT)
      .and().areTopLevelClasses()
      .and().doNotHaveFullyQualifiedName("com.barezen.content.StaticContent")
      .should().bePublic()
      .as("the public surface is StaticContent alone (ADR-0051)")
      .allowEmptyShould(true);
}
