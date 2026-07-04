package com.barezen.testkit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Executable form of ADR-0044's placement: TestKit is a leaf the applications' tests reach for, and nothing in the
 * shipped framework reaches back.
 *
 * <p>{@code barezen-testkit} sits beside {@code barezen-plugins} at the top of the reactor, and the two top modules
 * must not know each other. A framework module that depended on testkit would drag a test tool into the production
 * artifact, so the isolation rule below is the phase's central claim made structural — it is checked by adding a
 * class under another module's {@code src/main} that names a testkit type, watching the rule fail, and deleting it.
 * The other rules are the same fences the security and plugin modules already hold (no thread-local, no environment
 * reads, no static mutable state) plus the two that only a tool built on the whole stack can need: it may not name a
 * plugin, and it may not name a JDBC driver.
 *
 * <p>Every rule is analyzed over the shipped main sources; test classes are excluded, because testkit's own tests use
 * testkit on purpose and examples (Phase 2.16) depend on it as a consumer would — neither is production drift.
 */
@AnalyzeClasses(
    packages = "com.barezen",
    importOptions = ImportOption.DoNotIncludeTests.class)
class BarezenTestKitArchitectureTest {

  private static final String TESTKIT = "com.barezen.testkit..";
  private static final String PLUGINS = "com.barezen.plugins..";

  /**
   * nothing in the framework depends on testkit.
   *
   * <p>The isolation ADR-0044 states: testkit is for a consumer's tests, so a production module that reached for it
   * would make the shipped artifact depend on a test tool — the build would still pass, which is exactly why the rule
   * has to exist. Only classes outside the testkit namespace are constrained; testkit may of course name itself.
   */
  @ArchTest
  static final ArchRule nothing_in_the_framework_depends_on_testkit = noClasses()
      .that().resideOutsideOfPackage(TESTKIT)
      .should().dependOnClassesThat().resideInAPackage(TESTKIT)
      .as("testkit is a consumer-facing test tool; a framework module naming it drags a test dependency into the"
          + " shipped artifact (ADR-0044)")
      .allowEmptyShould(true);

  /**
   * testkit never names a plugin.
   *
   * <p>TestKit and the plugins are both tops of the reactor and must not see each other sideways: a testkit type that
   * imported {@code com.barezen.plugins} would force every consumer of the testkit onto the plugin classpath, and the
   * "testing OpenAPI" the phase asks for is documentation, not a compiled edge. TestKit talks to the contracts
   * (core/web/security/data), never to a plugin.
   */
  @ArchTest
  static final ArchRule testkit_does_not_name_plugins = noClasses()
      .that().resideInAPackage(TESTKIT)
      .should().dependOnClassesThat().resideInAPackage(PLUGINS)
      .as("the testkit and the plugins are independent tops; a sideways edge would couple every testkit user to a"
          + " plugin classpath (ADR-0044)")
      .allowEmptyShould(true);

  /**
   * no driver is named in testkit's main sources — while the datasource port stays legal.
   *
   * <p>{@link TestDatabase} takes an application-supplied {@link javax.sql.DataSource}; that port interface is the
   * whole JDBC surface it needs, exactly as it is for {@code barezen-data}. What is forbidden is a driver class
   * ({@code org.h2}, PostgreSQL, MySQL, …): testkit must not decide or hard-wire the test's database, so it may not
   * name any implementation. H2 stays a test-scope dependency of whoever writes the test.
   */
  @ArchTest
  static final ArchRule no_driver_is_named_in_main_sources = noClasses()
      .that().resideInAPackage(TESTKIT)
      .should().dependOnClassesThat(new com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>(
          "are a JDBC driver implementation") {
        @Override
        public boolean test(com.tngtech.archunit.core.domain.JavaClass clazz) {
          String name = clazz.getName();
          return name.startsWith("org.h2.") || name.startsWith("org.postgresql.") || name.startsWith("com.mysql.")
              || name.startsWith("org.mariadb.") || name.startsWith("org.sqlite.") || name.startsWith("org.hsqldb.");
        }
      })
      .as("testkit accepts a DataSource the test builds; naming a driver would hard-wire the database for every user")
      .allowEmptyShould(true);

  /**
   * no thread-local.
   *
   * <p>Inherited from the security module's rule (ADR-0034). A test tool that parked identity on a thread would make
   * an isolation test report a false pass, because the "leak" it is meant to detect would be sitting in the tool.
   */
  @ArchTest
  static final ArchRule no_thread_local = noClasses()
      .that().resideInAPackage(TESTKIT)
      .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ThreadLocal")
      .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.InheritableThreadLocal")
      .as("identity travels as an argument, never on a thread (same rule as the security module, ADR-0034)")
      .allowEmptyShould(true);

  /**
   * no configuration back-channels.
   *
   * <p>A testkit knob read from a system property or the environment would be a knob a test author cannot see and a
   * reviewer cannot audit; every testkit parameter is a method argument. (Inherited from ADR-0016.)
   */
  @ArchTest
  static final ArchRule no_system_property_or_environment_reads = noClasses()
      .that().resideInAPackage(TESTKIT)
      .should().callMethod(System.class, "getProperty", String.class)
      .orShould().callMethod(System.class, "getenv", String.class)
      .orShould().callMethod(System.class, "getenv")
      .as("testkit parameters are method arguments; nothing is configured through the environment");

  /**
   * no static mutable state in testkit.
   *
   * <p>The "fixture state shared" and "security context leaked" mutations start here: a {@code static} non-final field
   * in a fixture would be state two tests share without either asking to. Constants — the random-port sentinel, a
   * timeout — stay legal; mutable counters live in an instance, which is why the counting fixtures are per-instance.
   */
  @ArchTest
  static final ArchRule no_static_mutable_state = noFields()
      .that().areDeclaredInClassesThat().resideInAPackage(TESTKIT)
      .and().areNotFinal()
      .should().beStatic()
      .as("a static mutable field is state every test shares, which is the leak this module is supposed to detect")
      .allowEmptyShould(true);
}
