package com.barezen.core;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;

/**
 * Executable form of the Phase 2.1 architecture rules.
 *
 * <p>These are the constraints that turn the design claims into something verifiable: the kernel stays
 * free of HTTP and persistence concepts, reaching for a newer JDK API is confined to a single class, and
 * package dependencies stay one directional.
 */
@AnalyzeClasses(
    packages = "com.barezen.core",
    importOptions = ImportOption.DoNotIncludeTests.class)
class BarezenCoreArchitectureTest {

  private static final String KERNEL = "com.barezen.core.kernel..";
  private static final String CONCURRENT = "com.barezen.core.concurrent..";
  private static final String RUNTIME = "com.barezen.core.runtime..";
  private static final String ERROR = "com.barezen.core.error..";

  /**
   * Rule 1: core depends on nothing but the JDK and the SLF4J API.
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
   * Rule 2: the kernel knows nothing about HTTP, SQL, security or plugins.
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
   * Rule 3: reaching for a Java API newer than the baseline is confined to the runtime probe.
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
   * Rule 4: Java 21 APIs are never referenced directly, which would break the JDK 17 baseline.
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

  /** Rule 5: capability packages never depend on the orchestration package. */
  @ArchTest
  static final ArchRule capabilities_do_not_depend_on_the_kernel = noClasses()
      .that().resideInAnyPackage(CONCURRENT, RUNTIME)
      .should().dependOnClassesThat().resideInAnyPackage(KERNEL)
      .as("concurrent and runtime must stay usable without the lifecycle engine");

  /** Rule 6: error and runtime are leaves of the internal dependency graph. */
  @ArchTest
  static final ArchRule error_and_runtime_packages_are_leaves = noClasses()
      .that().resideInAnyPackage(ERROR, RUNTIME)
      .should().dependOnClassesThat().resideInAnyPackage(KERNEL, CONCURRENT)
      .as("error and runtime must not reach back into kernel or concurrent");

  /** Rule 7: the internal package graph is one directional and acyclic. */
  @ArchTest
  static final ArchRule core_is_free_of_cycles = Architectures.layeredArchitecture()
      .consideringOnlyDependenciesInAnyPackage("com.barezen.core..")
      .layer("Error").definedBy(ERROR)
      .layer("Runtime").definedBy(RUNTIME)
      .layer("Concurrent").definedBy(CONCURRENT)
      .layer("Kernel").definedBy(KERNEL)
      .whereLayer("Kernel").mayNotBeAccessedByAnyLayer()
      .whereLayer("Concurrent").mayOnlyBeAccessedByLayers("Kernel")
      .whereLayer("Runtime").mayOnlyBeAccessedByLayers("Kernel", "Concurrent")
      .whereLayer("Error").mayOnlyBeAccessedByLayers("Kernel", "Concurrent")
      .as("core packages must form a one directional graph with kernel at the top");
}
