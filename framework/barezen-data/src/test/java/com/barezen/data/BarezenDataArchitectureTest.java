package com.barezen.data;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Executable form of the Phase 2.12 boundary: the data layer is a JDBC edge, and edges do not reach back into
 * the layers they serve, do not choose their database product, and do not carry a transaction onto a thread.
 *
 * <p>Each rule was checked by adding a deliberately violating class, watching the build fail on it, and deleting
 * the class — the same evidence convention ADR-0018 and ADR-0033 record for the other modules' rules.
 */
@AnalyzeClasses(
    packages = "com.barezen.data",
    importOptions = ImportOption.DoNotIncludeTests.class)
class BarezenDataArchitectureTest {

  /**
   * the data layer knows nothing about HTTP, security, or the layers an application assembles above it.
   *
   * <p>The reverse dependency this phase could create in one import: {@code SecurityContext} read by a
   * repository to decide row ownership, or a {@code Request} threaded through a query to name a tenant. Both
   * would put the web contract's shape inside persistence code. {@code com.barezen.core..} is on the list as
   * the stronger statement ADR-0038 makes: the data layer does not even depend on core — its only collaborator
   * is the application-supplied {@code DataSource} — so configuration keys, lifecycle types and the DI layer
   * are all refused here too.
   */
  @ArchTest
  static final ArchRule the_data_layer_touches_no_other_framework_layer = noClasses()
      .should().dependOnClassesThat().resideInAnyPackage(
          "com.barezen.core..", "com.barezen.web..", "com.barezen.http..", "com.barezen.security..",
          "com.barezen.plugin..", "com.barezen.app..",
          "com.sun.net.httpserver..", "jakarta..", "javax.servlet..")
      .as("barezen-data must stay independent of every other framework layer: no HTTP concepts, no security"
          + " types, no lifecycle, no configuration (ADR-0038)");

  /**
   * no database product is named, and no connection is built by reaching for a driver.
   *
   * <p>The single most important rule of the module, and the one that makes "production code never depends on
   * the test database" structural rather than promised. A driver import is the framework choosing a vendor for
   * every application that imports it; a {@code DriverManager.getConnection("jdbc:...")} or a
   * {@code Class.forName("org.h2.Driver")} is the same choice arriving at runtime, which is worse — it works on
   * the test machine and fails on the deployment one. Every overload of the banned calls is spelled out
   * because {@code callMethod} matches only the signature named, the lesson ADR-0013 records for Phase 2.2.
   */
  @ArchTest
  static final ArchRule no_database_product_is_named = noClasses()
      .should().dependOnClassesThat().resideInAnyPackage(
          "org.h2..", "org.postgresql..", "com.mysql..", "org.mariadb..", "org.sqlite..",
          "org.apache.derby..", "com.zaxxer.hikari..")
      .orShould().callMethod(java.sql.DriverManager.class, "getConnection", String.class)
      .orShould().callMethod(java.sql.DriverManager.class, "getConnection", String.class, String.class,
          String.class)
      .orShould().callMethod(java.sql.DriverManager.class, "getConnection", String.class,
          java.util.Properties.class)
      .orShould().callMethod(Class.class, "forName", String.class)
      .orShould().callMethod(Class.class, "forName", String.class, boolean.class, ClassLoader.class)
      .as("barezen-data speaks javax.sql only: no driver class, no connection string, no reflective driver"
          + " loading (ADR-0038)");

  /**
   * the H2 driver is a test-scope fact of the build, not a production import.
   *
   * <p>Redundant with the vendor list above on today's tree and kept anyway: the named sentence is what a
   * reviewer reads to learn that H2 is the ADR-0038 §Testing exception and nothing more, and redundancy is what
   * keeps a boundary honest after the wider rule gains a legitimate exception of its own.
   */
  @ArchTest
  static final ArchRule the_test_database_stays_in_test_scope = noClasses()
      .should().dependOnClassesThat().resideInAnyPackage("org.h2..")
      .as("the H2 driver exists for the build's own tests (ADR-0038 §Testing); a production import of it means"
          + " the boundary lost its point");

  /**
   * no thread-local, and no execution machinery: there is no ambient transaction here.
   *
   * <p>A {@code ThreadLocal<Transaction>} is the shape ADR-0033 refused for security and ADR-0039 refuses for
   * data: a server thread answers many requests, and an ambient transaction is a commit that arrives in code
   * nobody reading the call site can see. An executor or future in this module is the other half of the same
   * drift — a framework layer running its own threads owns a lifecycle it has no claim on (ADR-0015), and a
   * data type that hands work to a pool breaks the one-connection-per-unit-of-work rule {@code TransactionTest}
   * pins from the database side.
   */
  @ArchTest
  static final ArchRule no_thread_local_and_no_execution_machinery = noClasses()
      .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ThreadLocal")
      .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.InheritableThreadLocal")
      .orShould().dependOnClassesThat(executionMachinery())
      .as("barezen-data owns no thread, no pool and no ambient scope: a transaction is an object passed to the"
          + " code that uses it (ADR-0039)");

  private static DescribedPredicate<JavaClass> executionMachinery() {
    DescribedPredicate<JavaClass> machinery = null;
    for (String fragment : java.util.List.of("Executor", "Thread", "Future", "Scheduler")) {
      DescribedPredicate<JavaClass> candidate = JavaClass.Predicates.simpleNameContaining(fragment);
      machinery = machinery == null ? candidate : machinery.or(candidate);
    }
    return JavaClass.Predicates.resideInAnyPackage("java.util.concurrent..").and(machinery);
  }

  /**
   * the data layer reads no configuration and no environment.
   *
   * <p>The application resolves its own {@code Configuration} in its assembly code and hands over the finished
   * {@code DataSource}. A framework layer reading a datasource URL behind its own signature is the
   * service-locator shape ADR-0006 and ADR-0013 refuse, and it is also how a test database reaches production:
   * a URL read from global state is a URL a test can set.
   */
  @ArchTest
  static final ArchRule the_data_layer_reads_no_global_state = noClasses()
      .should().callMethod(System.class, "getProperty", String.class)
      .orShould().callMethod(System.class, "getProperty", String.class, String.class)
      .orShould().callMethod(System.class, "getenv")
      .orShould().callMethod(System.class, "getenv", String.class)
      .as("connection facts arrive through the application's constructor call, never through the environment"
          + " (ADR-0013, ADR-0038)");

  /**
   * no logging facade in main sources.
   *
   * <p>The security module established the rule and its reason (ADR-0037 §Security implications), and for a
   * data layer the reason is sharper: a logger here is one debug statement away from writing row contents to a
   * log file, because SQL and its parameters <em>are</em> the data. Failures arrive as
   * {@link DataAccessException}; an uncloseable connection is the pool's own problem to report.
   */
  @ArchTest
  static final ArchRule main_sources_hold_no_logger = noClasses()
      .should().dependOnClassesThat().resideInAnyPackage("org.slf4j..", "java.util.logging..")
      .as("barezen-data main sources name no logging framework (ADR-0037)");

  /**
   * no static mutable state: every field in the module is either final or per-instance.
   *
   * <p>Same posture as the security module. A static cache of the migration history, or of a connection, would
   * be the framework pretending to know what the database knows — and the second lifecycle nobody drives.
   */
  @ArchTest
  static final ArchRule no_static_mutable_state = noFields()
      .that().areDeclaredInClassesThat().resideInAPackage("com.barezen.data..")
      .and().areNotFinal()
      .should().beStatic()
      .as("a static mutable field in a data module is a connection cache or a history cache wearing a different"
          + " name")
      .allowEmptyShould(true);
}
