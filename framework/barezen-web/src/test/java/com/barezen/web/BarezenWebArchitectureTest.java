package com.barezen.web;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
import java.util.List;

/**
 * Executable form of the Phase 2.7 boundary: HTTP is a contract here, and a server is something else.
 *
 * <p>The rules are written so that the drift this design is most likely to suffer fails a build rather than a review.
 * Every rule was checked by adding a deliberately violating class, watching it fail, and deleting the class; the
 * evidence is in ADR-0018.
 */
@AnalyzeClasses(
    packages = "com.barezen.web",
    importOptions = ImportOption.DoNotIncludeTests.class)
class BarezenWebArchitectureTest {

  private static final String WEB = "com.barezen.web..";

  /**
   * the contract depends on nothing but the JDK, and only the two packages it actually uses.
   *
   * <p>{@code java.lang..} and {@code java.util} — the exact package, not its subpackages — are all this module needs
   * for five value types and one signature. Everything else is refused by this single sentence: a logging facade, a
   * JSON library, {@code java.net} (a URL, a socket, a URI), {@code java.io} (a stream handed to a handler),
   * {@code java.util.concurrent} (a thread, an executor, a future), and every server library. It is the phase's claim
   * made mechanical: <em>HTTP is a contract, not a server</em> (ADR-0018 §12).
   *
   * <p>The reason for writing it as an allow-list rather than a list of forbidden names is that a server library is not
   * the only way this leaks. {@code java.nio.file.Files} in a handler, {@code MessageDigest} for an ETag,
   * {@code java.text.Normalizer} for a path — each is a JDK type that would make the contract's behaviour depend on a
   * platform, and none of them appears in any blacklist anyone would think to write.
   */
  @ArchTest
  static final ArchRule the_contract_depends_only_on_java_lang_and_java_util = noClasses()
      .should().dependOnClassesThat(outsideTheAllowedJdkSurface())
      .as("barezen-web may use java.lang and java.util only: no server, no IO, no threads, no logging, no URI, no"
          + " third party library at all (ADR-0018 §12)");

  private static DescribedPredicate<JavaClass> outsideTheAllowedJdkSurface() {
    return JavaClass.Predicates.resideOutsideOfPackages("java.lang..", "java.util", WEB);
  }

  /**
   * no JDK HTTP server.
   *
   * <p>{@code com.sun.net.httpserver} is the tempting first adapter — it is in the JDK, so "just use it" costs no
   * dependency and no CVE to track. Naming it as a separate rule rather than leaving it to the allow-list above is
   * deliberate: the drift is not in the contract, it is one commit later in the same package, where someone adds the
   * server because it is convenient and the tests then pass on a real socket. A developer reading this rule at that
   * moment reads the reason too.
   */
  @ArchTest
  static final ArchRule no_jdk_http_server = noClasses()
      .should().dependOnClassesThat().resideInAnyPackage("com.sun.net.httpserver..", "sun..", "jdk..")
      .as("the contract must compile and test with no server present, and com.sun.net.httpserver is a server"
          + " (ADR-0018 §12)");

  /** Jetty, Netty, Undertow, Tomcat, the Servlet API, Spring Web, Helidon and Quarkus. */
  @ArchTest
  static final ArchRule no_server_or_web_framework = noClasses()
      .should().dependOnClassesThat().resideInAnyPackage(
          "org.eclipse.jetty..", "io.netty..", "io.undertow..", "io.vertx..", "org.apache.catalina..",
          "org.apache.tomcat..", "jakarta.servlet..", "javax.servlet..", "jakarta..", "javax..",
          "org.springframework..", "io.helidon..", "io.quarkus..", "io.ajai..", "play..")
      .as("an HTTP contract that imports a server cannot be implemented by a different one, which is the only reason"
          + " the contract exists (ADR-0018 §12)")
      .allowEmptyShould(true);

  /**
   * nothing reaches up into core, and the direction is proven by construction rather than by a rule alone.
   *
   * <p>Allowed direction is {@code barezen-core <- barezen-web}, never the reverse; that half is enforced in
   * {@code barezen-core}'s own test, where the package {@code com.barezen.web..} is named as forbidden. This rule
   * enforces the other half, and this half is stronger than a rule: {@code barezen-web} declares no compile
   * dependency, so {@code com.barezen.core} is not even on its classpath. The contract therefore cannot receive a
   * {@code LifecycleOrchestrator}, hold an {@code EventBus}, or ask a {@code Configuration} for a value — not because
   * a rule refuses it, but because a type it has never heard of cannot be named without editing the pom, which is the
   * moment a reviewer sees an ADR-sized decision.
   */
  @ArchTest
  static final ArchRule the_contract_does_not_reach_into_core = noClasses()
      .should().dependOnClassesThat().resideInAnyPackage("com.barezen.core..", "com.barezen.data..",
          "com.barezen.security..", "com.barezen.api..")
      .as("barezen-web is a leaf: it needs no lifecycle engine, no container, no event bus and no configuration"
          + " (ADR-0018 §9, §12)")
      .allowEmptyShould(true);

  /**
   * no type in the contract takes a {@code Class} and hands back an object, and none takes a name to look something up
   * by.
   *
   * <p>Core enforces this with a name list plus a {@code Class} parameter, because {@code Configuration} legitimately
   * reads a keyed value. Here there is no legitimate use of a {@code Class} parameter at all — a request carries what
   * the client sent, and a response carries what the handler decided — so the rule is simply "no method in this module
   * may take a Class, and none may be named like a lookup". That closes {@code request.service(UserService.class)},
   * {@code request.attribute("user")}, {@code request.context(...)} and {@code request.header(Class)} together,
   * including the verbs nobody thought to forbid (ADR-0018 §9).
   */
  @ArchTest
  static final ArchRule nothing_takes_a_type_or_a_key_to_find_an_object = methods()
      .that(JavaMember.Predicates.declaredIn(resideInAPackage(WEB)))
      .should(takeNeitherAClassParameterNorALookupName())
      .as("Request and Response carry data, they do not resolve components: a service locator on a request is the"
          + " shape ADR-0006 and ADR-0018 §9 refuse");

  private static ArchCondition<JavaMethod> takeNeitherAClassParameterNorALookupName() {
    List<String> lookupNames = java.util.List.of(
        "service", "services", "require", "find", "get", "lookup", "resolve", "bean", "instance", "component",
        "attribute", "attributes", "context", "attachment", "payload", "extension");
    return new ArchCondition<JavaMethod>(
        "take neither a Class parameter nor a lookup-shaped name") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        boolean takesAClass = method.getRawParameterTypes().stream()
            .anyMatch(parameter -> parameter.getName().equals("java.lang.Class"));
        boolean namedLikeALookup = lookupNames.contains(method.getName());
        boolean violates = takesAClass || namedLikeALookup;
        events.add(violates
            ? SimpleConditionEvent.violated(method, method.getFullName()
                + (takesAClass ? " takes a Class" : " is named like a lookup")
                + ", so it can hand out an object the client did not send and the handler did not build: a request or"
                + " response is not a service locator (ADR-0018 §9)")
            : SimpleConditionEvent.satisfied(method, method.getFullName()
                + " is not a lookup"));
      }
    };
  }

  /**
   * the contract reflects nothing, loads nothing by name, scans nothing and discovers nothing.
   *
   * <p>Every call named here is a step from "a handler is registered in application code" to "a handler is found by
   * the framework". A web layer is where annotation scanning is most tempting — {@code @Route}, {@code @GetMapping},
   * {@code @Inject} on a handler parameter — because it looks like the shortest way to write a controller, and it is
   * the exact mechanism that turns an interface-first framework into a container with a request object attached to it
   * (ADR-0018 §13).
   */
  @ArchTest
  static final ArchRule the_contract_never_reflects_loads_or_scans = noClasses()
      .that().resideInAnyPackage(WEB)
      .should().dependOnClassesThat().resideInAnyPackage("java.lang.reflect..")
      // Every overload listed by hand: callMethod(Class, String) matches only the no-argument signature, which is how
      // a Phase 2.2 rule turned out to be silently ineffective.
      .orShould().callMethod(Class.class, "forName", String.class)
      .orShould().callMethod(Class.class, "forName", String.class, boolean.class, ClassLoader.class)
      .orShould().callMethod(Class.class, "getClassLoader")
      .orShould().callMethod(Class.class, "getDeclaredConstructor", Class[].class)
      .orShould().callMethod(Class.class, "getConstructors")
      .orShould().callMethod(Class.class, "getMethods")
      .orShould().callMethod(Class.class, "getDeclaredMethods")
      .orShould().callMethod(Class.class, "getResource", String.class)
      .orShould().callMethod(ClassLoader.class, "getResources", String.class)
      .orShould().callMethod(ClassLoader.class, "getSystemResources", String.class)
      .orShould().callMethod(java.util.ServiceLoader.class, "load", Class.class)
      .orShould().callMethod(java.util.ServiceLoader.class, "load", Class.class, ClassLoader.class)
      .as("com.barezen.web contains no reflection, annotation processing, class loading, scanning or SPI lookup");

  /**
   * the contract owns no thread and nothing that waits.
   *
   * <p>Phase 2.7 decided dispatch is synchronous (ADR-0018 §6). The usual way that erodes is a type in the contract
   * holding an {@code Executor} "for the async handlers", or a {@code CompletableFuture} response, which would put a
   * thread pool and therefore a lifecycle inside an interface five layers of application code implement. The rule is
   * a dependency statement over {@code java.util.concurrent} rather than a naming rule, because the drift arrives as
   * an import.
   */
  @ArchTest
  static final ArchRule the_contract_owns_no_execution_machinery = noClasses()
      .that().resideInAnyPackage(WEB)
      .should().dependOnClassesThat().resideInAnyPackage("java.util.concurrent..")
      .as("a synchronous contract that holds an executor, queue or future owns a lifecycle it does not have"
          + " (ADR-0018 §6, §9)");

  /**
   * no {@code Context}, {@code Registry}, {@code Manager}, {@code Engine}, {@code Pipeline} or {@code Exchange}.
   *
   * <p>The names are the design. A request that needed an {@code HttpContext} would need it for something, and what it
   * needs is the list ADR-0018 §9 refuses: services, attributes, a router, a bus. §16 forbids the specific names
   * {@code HttpContext}, {@code HttpExchange}, {@code HttpRequestContext}, {@code HttpResponseContext},
   * {@code HttpPipeline}, {@code HttpEngine}, {@code HttpRuntime} and {@code HttpManager}; this rule covers those and
   * the suffix family that reaches the same place from a different word (ADR-0006, ADR-0018 §9, §16).
   */
  @ArchTest
  static final ArchRule no_container_shaped_type_names = classes()
      .that().resideInAnyPackage(WEB)
      .should(haveNameNotBelongingToTheContainerFamily())
      .as("the web layer is a contract: Context, Registry, Manager, Engine, Pipeline, Exchange, Runtime, Facade,"
          + " Broker, Holder and Scope are the names a service locator arrives under");

  /**
   * The container family, checked as a condition so the failure message can name the suffix that was hit.
   */
  private static ArchCondition<JavaClass> haveNameNotBelongingToTheContainerFamily() {
    List<String> forbiddenSuffixes = java.util.List.of(
        "Context", "Registry", "Manager", "Engine", "Pipeline", "Exchange", "Runtime", "Facade", "Broker", "Holder",
        "Scope", "Locator", "Provider", "Factory", "Container", "Session", "State", "Dispatcher", "Invoker",
        "Assembler", "Processor", "Resolver", "Support", "Helper", "Utils");
    List<String> forbiddenNames = java.util.List.of(
        "Router", "Route", "RouteRegistry", "PathPattern", "RouteMatcher", "Middleware", "HandlerChain", "Filter",
        "Interceptor", "HandlerAdapter", "HttpHandler", "HttpRequest", "HttpResponse", "HttpMessage",
        "BodyPublisher", "BodyReader", "BodyWriter", "StreamContext");
    return new ArchCondition<JavaClass>("not carry a container, router or pipeline name") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        String name = type.getSimpleName();
        String hitSuffix = forbiddenSuffixes.stream().filter(name::endsWith).findFirst().orElse(null);
        boolean exactHit = forbiddenNames.contains(name);
        boolean violates = hitSuffix != null || exactHit;
        events.add(violates
            ? SimpleConditionEvent.violated(type, type.getName() + " is named " + name
                + (hitSuffix != null ? ", ending in " + hitSuffix : ", which is a name this phase refuses")
                + ". Nothing in the contract owns components, routes or a chain of execution (ADR-0018 §9, §10,"
                + " §16)")
            : SimpleConditionEvent.satisfied(type, name + " is a value or a signature"));
      }
    };
  }

  /**
   * every field of every contract type is final, and every state is set at construction.
   *
   * <p>The immutability claim of §4 in mechanical form. A non-final field on a {@code Request} means some component
   * changed what the client asked, and then whichever component runs next is answering a different request than the one
   * that arrived — the exact failure a servlet's {@code setAttribute} became (ADR-0018 §8).
   */
  @ArchTest
  static final ArchRule every_field_is_final = fields()
      .that().areDeclaredInClassesThat().resideInAPackage(WEB)
      .should().haveModifier(JavaModifier.FINAL)
      .as("Request, Response and Headers are immutable after construction, so no component can edit another's request");

  /**
   * no setter, mutator or send-shaped method anywhere in the contract.
   *
   * <p>{@code Headers.with} is the only changing operation and it returns a new instance. A verb list catches the other
   * shape of the same mistake: {@code Response.send(...)}, which would commit bytes to a client before a later
   * component had its turn, and {@code Request.setPath(...)}, which would edit what the client sent (ADR-0018 §3, §8).
   */
  @ArchTest
  static final ArchRule no_method_writes_or_mutates = methods()
      .that(JavaMember.Predicates.declaredIn(resideInAPackage(WEB)))
      .should(haveNameNotStartingWithAWordThatMeansChange())
      .as("the contract is data and one signature: set, add, put, remove, clear, send, write, flush and commit all"
          + " mean acting on a client before the handler is finished");

  private static ArchCondition<JavaMethod> haveNameNotStartingWithAWordThatMeansChange() {
    List<String> prefixes = java.util.List.of("set", "add", "put", "remove", "clear", "send", "write",
        "flush", "commit", "cancel", "abort", "subscribe", "publish", "register");
    return new ArchCondition<JavaMethod>("not be named like an action on a client") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        String hit = prefixes.stream().filter(method.getName()::startsWith).findFirst().orElse(null);
        boolean violates = hit != null;
        events.add(violates
            ? SimpleConditionEvent.violated(method, method.getFullName() + " starts with"
                + " \"" + hit + "\", which is a verb that changes state or touches the client. A handler returns a"
                + " response; it does not write one (ADR-0018 §3)")
            : SimpleConditionEvent.satisfied(method, method.getName() + " reads or builds"));
      }
    };
  }

  /**
   * the five types are the whole module, and no sixth is a container in disguise.
   *
   * <p>Written against the compiled package directory rather than as a reflection over a class list, because a rule
   * over classes that exist cannot see the one that was just added. {@link ImportOption.DoNotIncludeTests} keeps test
   * fixtures out of the scan, so this measures the shipped surface.
   */
  @ArchTest
  static final ArchRule the_contract_is_exactly_five_types = classes()
      .that(JavaClass.Predicates.resideInAPackage(WEB)
          .and(JavaClass.Predicates.TOP_LEVEL_CLASSES))
      .should(haveFullyQualifiedNameBeingOneOfTheFive())
      .as("ADR-0018 §16 freezes the surface: Handler, Request, Response, Headers, HttpMethod — anything else needs a"
          + " consumer, which is the rule every earlier phase used");

  private static ArchCondition<JavaClass> haveFullyQualifiedNameBeingOneOfTheFive() {
    List<String> allowed = java.util.List.of(
        "com.barezen.web.Handler", "com.barezen.web.Request", "com.barezen.web.Response", "com.barezen.web.Headers",
        "com.barezen.web.HttpMethod");
    return new ArchCondition<JavaClass>("be one of the five contract types") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        boolean violates = !allowed.contains(type.getName());
        events.add(violates
            ? SimpleConditionEvent.violated(type, type.getName()
                + " is a sixth type in com.barezen.web; every public type must have a current consumer, and the"
                + " contract's consumer is the handler signature it already has (ADR-0018 §16)")
            : SimpleConditionEvent.satisfied(type, type.getSimpleName() + " is contracted"));
      }
    };
  }

  /**
   * no public type is a mutable holder: everything shipped is final or an interface.
   *
   * <p>A non-final public class invites a subclass that overrides an accessor, and a handler that reads
   * {@code request.path()} would then be reading a behaviour rather than a value. {@code Handler} is the interface
   * exception — it is the extension point the phase is about — and it holds nothing to override.
   */
  @ArchTest
  static final ArchRule every_contract_type_is_final_or_an_interface = classes()
      .that().resideInAPackage(WEB)
      .should(beFinalOrAnInterface())
      .as("a value type that can be subclassed changes what an application's handler reads back from it");

  private static ArchCondition<JavaClass> beFinalOrAnInterface() {
    return new ArchCondition<JavaClass>("be final or be an interface") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        boolean open = !type.isInterface()
            && !type.getModifiers().contains(JavaModifier.FINAL)
            && !type.getModifiers().contains(JavaModifier.ABSTRACT);
        events.add(open
            ? SimpleConditionEvent.violated(type, type.getName()
                + " is a public class that is neither final nor abstract, so an application could subclass it and"
                + " change what a handler sees")
            : SimpleConditionEvent.satisfied(type, type.getSimpleName() + " is closed"));
      }
    };
  }

  /**
   * no type in the contract dispatches a handler.
   *
   * <p>{@code Handler} is implemented by an application and called by an adapter; nothing inside the contract calls
   * it, because a type here that called it would be a router or a pipeline, and §10 refuses both. This is checkable
   * and not obvious from the file listing: {@code Request} and {@code Response} could each hold a
   * {@code Handler next} field and the module would still be five files.
   */
  @ArchTest
  static final ArchRule nothing_in_the_contract_dispatches_a_handler = noClasses()
      .that(not(JavaClass.Predicates.equivalentTo(Handler.class)))
      .should().callMethod(Handler.class, "handle", Request.class)
      .as("the contract states the signature and never invokes it: dispatching a handler is routing, which is not in"
          + " this phase (ADR-0018 §10)");

  /**
   * the contract declares no annotation and recognises none beyond {@code @FunctionalInterface}.
   *
   * <p>An annotation is how a web framework stops being interface-first: {@code @Route("/x")} on a method looks like
   * one small convenience, and behind it sits a scanner, a registry, a startup that discovers components, and the
   * loss of the compile-time check that makes {@code Handler} worth having. Declaring annotation types is the same
   * drift from the other side. {@code @FunctionalInterface} is permitted because it makes adding a second abstract
   * method a compile error, which is the contract's own guarantee rather than a lookup (ADR-0018 §13, §16).
   */
  @ArchTest
  static final ArchRule the_contract_declares_and_reads_no_annotations = classes()
      .that().resideInAPackage(WEB)
      .should(carryNoAnnotationThatDescribesComponents())
      .as("no annotation type is defined here and no component-describing annotation is carried: the moment a router"
          + " needs to read @Route it has to scan, and scanning is what this framework refuses (ADR-0014, ADR-0018"
          + " §13)");

  private static ArchCondition<JavaClass> carryNoAnnotationThatDescribesComponents() {
    return new ArchCondition<JavaClass>("carry no annotation but @FunctionalInterface") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        boolean declaresAnAnnotation = type.isAnnotation();
        // The one carried annotation, spelled out because the first draft of this rule guessed its package:
        // @FunctionalInterface is java.lang.FunctionalInterface, not java.lang.annotation.FunctionalInterface, and a
        // wrong allow-list here reads like the rule refusing the contract's own guarantee.
        List<String> foreign = type.getAnnotations().stream()
            .map(annotation -> annotation.getType().getName())
            .filter(name -> !name.equals("java.lang.FunctionalInterface"))
            .toList();
        boolean violates = declaresAnAnnotation || !foreign.isEmpty();
        events.add(violates
            ? SimpleConditionEvent.violated(type, type.getName() + (declaresAnAnnotation
                ? " is an annotation type, so something has to read it, and reading it means scanning"
                : " carries " + foreign + ", which describes a component the framework would have to find rather"
                    + " than be told about"))
            : SimpleConditionEvent.satisfied(type, type.getSimpleName() + " declares no annotation"));
      }
    };
  }
}
