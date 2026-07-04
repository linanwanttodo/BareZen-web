package com.barezen.plugins;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Executable form of ADR-0042's direction: the plugin layer is the top of this reactor, and nothing below it is
 * allowed to know it exists.
 *
 * <p>Analyzed over the whole {@code com.barezen} namespace for the same reason {@code BarezenHttpJdkArchitectureTest}
 * is: this module's test classpath is one of the few places in the reactor that holds core, web, security, data and
 * plugins at once, and the first rule below is exactly the edge ("does {@code com.barezen.security} name a plugin?")
 * that needs both sides visible. Every rule was checked by adding a deliberately violating class under
 * {@code src/main}, watching it fail, and deleting the class.
 *
 * <p>Test classes are excluded: fixtures name plugin types and use a seeded socket-free clock on purpose; these rules
 * measure the shipped artifact.
 */
@AnalyzeClasses(
    packages = "com.barezen",
    importOptions = ImportOption.DoNotIncludeTests.class)
class BarezenPluginsArchitectureTest {

  private static final String PLUGINS = "com.barezen.plugins..";

  /**
   * the framework never reaches up.
   *
   * <p>The phase's core claim in one line: the arrow is {@code Security ← SPI ← User Plugin}, and never the reverse.
   * If any class outside the plugin namespace — core, web, the adapter, security, data — depends on a plugin, the
   * framework has become plugin-specific, a second application with different plugins cannot reuse it, and the
   * "ecosystem" was a fiction. This single rule subsumes "core Security is not changed for plugins": a change of
   * that shape would name something here.
   */
  @ArchTest
  static final ArchRule the_framework_does_not_know_plugins_exist = noClasses()
      .that().resideOutsideOfPackage(PLUGINS)
      .should().dependOnClassesThat().resideInAPackage(PLUGINS)
      .as("nothing below the plugin layer may name it: the plugins sit on the framework through SPI ports, and a"
          + " back-edge makes the framework plugin-specific (ADR-0042)")
      .allowEmptyShould(true);

  /**
   * concrete plugin types are private to their package.
   *
   * <p>Plugin A may talk to plugin B only through a port interface, never through B's implementation class — that is
   * "plugin A cannot mutate plugin B's state" made structural: you cannot reach a neighbour's mutable internals if
   * you cannot even name its type. The ports themselves ({@code PasswordVerifier}, and security's
   * {@code Authentication}/{@code Authorization}) are interfaces and stay reachable, which is the point of a port.
   */
  @ArchTest
  static final ArchRule plugins_do_not_name_each_others_concrete_types =
      com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
      .that().resideInAPackage(PLUGINS)
      .should(new ArchCondition<JavaClass>("stay out of a sibling plugin's implementation types") {
        @Override
        public void check(JavaClass pluginClass, ConditionEvents events) {
          for (Dependency dependency : pluginClass.getDirectDependenciesFromSelf()) {
            JavaClass target = dependency.getTargetClass();
            String targetPackage = target.getPackageName();
            boolean sibling = targetPackage.startsWith("com.barezen.plugins.")
                && !targetPackage.equals(pluginClass.getPackageName());
            if (sibling && target.getSimpleName().endsWith("Plugin") && !target.isInterface()) {
              events.add(SimpleConditionEvent.violated(dependency,
                  pluginClass.getFullName() + " names the sibling implementation " + target.getFullName()
                      + " (" + dependency.getDescription() + "); talk through its port instead (ADR-0042)"));
            }
          }
        }
      })
      .as("a plugin reaches a neighbour through a port interface, never by naming its concrete Plugin type");

  /**
   * no driver and no connection plumbing inside a plugin — with one named exception.
   *
   * <p>ADR-0038's driver-free rule continues up the stack: a plugin must not smuggle a JDBC URL, a driver class, a
   * {@code DataSource} or its own connection/statement handling past the data module's transaction and
   * error-translation decisions. The exception is measured, not guessed: {@code barezen-data}'s public read API is
   * {@code Database.query(sql, RowMapper, args)}, and {@link com.barezen.data.RowMapper} <em>hands the caller an
   * open {@code java.sql.ResultSet}</em> to read one row from — {@code ResultSet} is therefore part of the sanctioned
   * seam, and {@code UserRepository}'s row lambdas legitimately call {@code getString} on it. What stays forbidden is
   * everything a {@code RowMapper} cannot need: drivers, {@code javax.sql} sources, and {@code java.sql} types other
   * than the cursor the framework gave out. A rule that banned all of {@code java.sql} would either be loosened the
   * first time it bit honest code or would force a wrapper that hides the same call; naming the exception keeps the
   * fence exact.
   */
  @ArchTest
  static final ArchRule no_sql_or_driver_reaches_into_a_plugin = noClasses()
      .that().resideInAPackage(PLUGINS)
      .should().dependOnClassesThat(new com.tngtech.archunit.base.DescribedPredicate<JavaClass>(
          "are JDBC or a driver, other than the RowMapper cursor java.sql.ResultSet") {
        @Override
        public boolean test(JavaClass clazz) {
          if (clazz.getFullName().equals("java.sql.ResultSet")) {
            return false;
          }
          String name = clazz.getName();
          return name.startsWith("java.sql.") || name.startsWith("javax.sql.") || name.startsWith("org.h2.")
              || name.startsWith("org.postgresql.") || name.startsWith("com.mysql.") || name.startsWith("org.mariadb.")
              || name.startsWith("org.sqlite.") || name.startsWith("org.hsqldb.");
        }
      })
      .as("persistence goes through barezen-data's Database; the only JDBC a plugin may touch is the ResultSet a"
          + " RowMapper is handed (ADR-0038, ADR-0042)")
      .allowEmptyShould(true);

  /**
   * no thread-local.
   *
   * <p>The security module draws the same line (ADR-0034): a plugin that parks a principal or a session on a thread
   * leaks one request's identity into the next, and a plugin is exactly the layer where someone reaching for
   * {@code ThreadLocal} believes it is scoped per request.
   */
  @ArchTest
  static final ArchRule no_thread_local = noClasses()
      .that().resideInAPackage(PLUGINS)
      .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ThreadLocal")
      .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.InheritableThreadLocal")
      .as("identity travels as an argument, never on a thread (same rule as the security module, ADR-0034)")
      .allowEmptyShould(true);

  /**
   * no configuration back-channels.
   *
   * <p>A scheme name, a session lifetime, a table name — every plugin parameter is a constructor argument. A plugin
   * that reads {@code System.getProperty} or the environment creates a knob no reviewer sees and no test controls
   * (the phase's rule, inherited from ADR-0016).
   */
  @ArchTest
  static final ArchRule no_system_property_or_environment_reads = noClasses()
      .that().resideInAPackage(PLUGINS)
      .should().callMethod(System.class, "getProperty", String.class)
      .orShould().callMethod(System.class, "getenv", String.class)
      .orShould().callMethod(System.class, "getenv")
      .as("plugin parameters are constructor arguments; nothing is configured through the environment");

  /**
   * no static mutable state in the plugin namespace.
   *
   * <p>"plugin state leaked" in the phase's mutation list starts here: a {@code static} non-final field is state any
   * holder of any class in the package can mutate without going through a plugin at all. Final constants — the
   * descriptors, a logger name — remain legal.
   */
  @ArchTest
  static final ArchRule no_static_mutable_state = noFields()
      .that().areDeclaredInClassesThat().resideInAPackage(PLUGINS)
      .and().areNotFinal()
      .should().beStatic()
      .as("mutable plugin state lives in the plugin instance, where its owner and lifecycle are visible")
      .allowEmptyShould(true);

  /**
   * plugins sit below no adapter.
   *
   * <p>The OpenAPI plugin speaks the {@code com.barezen.web} contract ({@code HttpMethod}); naming a server
   * implementation would bind a documentation collector to one transport and break the ADR-0018 §12 layering the
   * security middleware already holds to.
   */
  @ArchTest
  static final ArchRule no_adapter_dependency = noClasses()
      .that().resideInAPackage(PLUGINS)
      .should().dependOnClassesThat().resideInAnyPackage("com.barezen.http..", "com.sun.net.httpserver..")
      .as("plugins use the web contract, never a server implementation (ADR-0018 §12)")
      .allowEmptyShould(true);
}
