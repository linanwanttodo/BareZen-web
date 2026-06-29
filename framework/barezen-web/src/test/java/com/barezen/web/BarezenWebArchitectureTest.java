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
import java.util.Map;

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
   *
   * <p><b>Amended by Phase 2.8.</b> ADR-0018 §16 put {@code Router} on this list, when routing was not in the phase.
   * ADR-0019 §20 takes that entry back: the name stays banned for every <em>other</em> type it could smuggle in —
   * {@code Route}, {@code RouteRegistry}, {@code PathPattern}, {@code RouteMatcher}, {@code Middleware},
   * {@code HandlerChain}, {@code Filter}, {@code Interceptor} all remain here — and {@code no_preemptive_routing_or_middleware_types}
   * below is the rule that keeps them banned while exactly one routing type, {@code Router}, is allowed to exist. The
   * alternative was renaming the type to dodge this list, which would have been this rule teaching the design.
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
    // "Router" is the one entry ADR-0019 removes from this list; every other routing-shaped name stays.
    List<String> forbiddenNames = java.util.List.of(
        "Route", "RouteRegistry", "PathPattern", "RouteMatcher", "Middleware", "HandlerChain", "Filter",
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
                + " §16; ADR-0019 §19, §20)")
            : SimpleConditionEvent.satisfied(type, name + " is a value, a signature or the route table"));
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
   * the shipped types are the whole module, and none of the extras is a container in disguise.
   *
   * <p>Written against the compiled package directory rather than as a reflection over a class list, because a rule
   * over classes that exist cannot see the one that was just added. {@link ImportOption.DoNotIncludeTests} keeps test
   * fixtures out of the scan, so this measures the shipped surface.
   *
   * <p><b>Amended by Phase 2.8.</b> ADR-0018 §16 froze five names; ADR-0019 §20 adds two — {@code Router} because the
   * phase's question («path + method → Handler») has no answer without a type that answers it, and {@code RouteMatch}
   * because a captured path parameter is something a caller cannot rebuild from a handler or a request. The rule is
   * kept rather than loosened: it is still an allow-list, so a sixth <em>and now eighth</em> type fails the build, and
   * the reason it was renamed rather than left saying "five" is that a rule whose name disagrees with its list is a
   * rule nobody trusts when it does fail.
   */
  @ArchTest
  static final ArchRule the_contract_is_exactly_the_seven_contracted_types = classes()
      .that(JavaClass.Predicates.resideInAPackage(WEB)
          .and(JavaClass.Predicates.TOP_LEVEL_CLASSES))
      .should(haveFullyQualifiedNameBeingOneOfTheSeven())
      .as("ADR-0018 §16 froze Handler, Request, Response, Headers, HttpMethod; ADR-0019 §20 added Router and RouteMatch"
          + " — anything else needs a consumer, which is the rule every earlier phase used");

  private static ArchCondition<JavaClass> haveFullyQualifiedNameBeingOneOfTheSeven() {
    List<String> allowed = java.util.List.of(
        "com.barezen.web.Handler", "com.barezen.web.Request", "com.barezen.web.Response", "com.barezen.web.Headers",
        "com.barezen.web.HttpMethod", "com.barezen.web.Router", "com.barezen.web.RouteMatch");
    return new ArchCondition<JavaClass>("be one of the seven contracted types") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        boolean violates = !allowed.contains(type.getName());
        events.add(violates
            ? SimpleConditionEvent.violated(type, type.getName()
                + " is not one of the contracted types in com.barezen.web; every public type must have a current"
                + " consumer, and the contract's consumer is the handler signature it already has (ADR-0018 §16,"
                + " ADR-0019 §20)")
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
   *
   * <p><b>Amended by Phase 2.8.1.</b> The target signature is now {@code handle(Request, Map)}. Writing the old
   * {@code (Request)} parameter list would have left this rule perfectly green and perfectly empty —
   * {@code callMethod} matches one exact signature rather than one name, so a rule that no longer names a real method
   * measures nothing. That is the same failure this file's reflection rule lists every {@code Class} overload to avoid,
   * and it is the reason {@code handler_receives_exactly_the_request_and_the_captured_parameters} below pins the
   * signature itself: a rule about a call is only as live as the signature it names (ADR-0018 §10, ADR-0020 §6).
   */
  @ArchTest
  static final ArchRule nothing_in_the_contract_dispatches_a_handler = noClasses()
      .that(not(JavaClass.Predicates.equivalentTo(Handler.class)))
      .should().callMethod(Handler.class, "handle", Request.class, Map.class)
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

  // --------------------------------------------------------------------------------------------------------------
  // Phase 2.8: the rules above are ADR-0018's, and adding a router to this package must not weaken one of them.
  // The five below are ADR-0019's, each written against the router rather than the contract, each checked by a
  // violation that no other rule in this file reports — the probes are listed in ADR-0019.
  // --------------------------------------------------------------------------------------------------------------

  // --------------------------------------------------------------------------------------------------------------
  // Phase 2.8.1: closing the gap ADR-0019 §7 recorded widened Handler.handle by one argument, and two of the rules
  // below exist because a probe proved the existing nineteen did not cover the new failure mode. See ADR-0020 §10.
  // --------------------------------------------------------------------------------------------------------------

  /**
   * the handler signature is the request plus the captured parameters, and nothing else.
   *
   * <p>The corrected contract is the thing most likely to drift again, because the pressure that produced it — "the
   * handler needs one more thing" — does not disappear when the first need is met. Pinning the parameter list turns that
   * pressure into a build failure: a third argument is a context arriving one argument at a time, and an argument of type
   * {@code RouteMatch} would hand a handler the handler it already is, which is the step from answering a request to
   * dispatching one (ADR-0018 §10, ADR-0020 §6).
   */
  @ArchTest
  static final ArchRule the_handler_signature_is_the_request_and_the_captured_parameters = methods()
      .that(JavaMember.Predicates.declaredIn(JavaClass.Predicates.equivalentTo(Handler.class)))
      .should(beHandleWithTheFrozenParameterList())
      .as("handle(Request, Map) is the whole extension surface: one more argument is a context, and a RouteMatch argument"
          + " would hand a handler its own identity (ADR-0020 §3, §6)");

  private static ArchCondition<JavaMethod> beHandleWithTheFrozenParameterList() {
    return new ArchCondition<JavaMethod>("be handle(Request, Map)") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        List<String> parameters = method.getRawParameterTypes().stream()
            .map(JavaClass::getName).sorted().toList();
        List<String> frozen = List.of("com.barezen.web.Request", "java.util.Map").stream().sorted().toList();
        boolean violates = !method.getName().equals("handle") || !parameters.equals(frozen);
        events.add(violates
            ? SimpleConditionEvent.violated(method, method.getFullName() + " takes " + parameters
                + ". The handler is handed what the client sent and what routing decided, in exactly two arguments;"
                    + " a third is a place to put anything, which is the shape ADR-0018 §9 refuses")
            : SimpleConditionEvent.satisfied(method, "handle(Request, Map) is the frozen signature"));
      }
    };
  }

  /**
   * {@code Request} carries what the client sent and nothing routing decided.
   *
   * <p>This rule exists because of a probe, not a worry. Handing the parameters to the handler as a request field —
   * {@code request.routeParameters()}, plus a {@code withRouteParameters} copy to get them in — was implemented in full
   * and passed all nineteen existing rules: the names are not lookup-shaped, {@code with} is the verb {@code Headers}
   * already uses, no field was non-final, nothing dispatched a handler and nothing reflected. The one rule that would
   * have caught it was not about names or mutability at all; it was the claim that a request is a record of the wire. So
   * the claim is now mechanical, as an allow-list over the whole type: a new accessor on {@code Request} has to be an
   * HTTP fact the client sent, and "the framework decided this value" fails it by construction.
   *
   * <p>Why an allow-list rather than a forbidden-name list. The rejected option's failure mode is not one bad name —
   * remove {@code routeParameters} and the next draft calls it {@code matchContext}, or hands back an attribute map that
   * fits none of the banned words. {@code attribute}, {@code service} and {@code context} are already refused by
   * {@code nothing_takes_a_type_or_a_key_to_find_an_object}; what was unprotected is a genuinely immutable, genuinely
   * well-named field carrying a framework-computed value, and only a closed set of legal accessors refuses that
   * (ADR-0018 §9, ADR-0020 §4).
   *
   * <p>A real addition to this list will be needed one day — {@code scheme()}, {@code authority()}, the target of a
   * {@code CONNECT} — and that is the correct cost: it is a change to what the contract says a request is, so it should
   * fail a build and be argued about, and it stays an HTTP fact rather than becoming a place to stash one.
   */
  @ArchTest
  static final ArchRule a_request_carries_only_what_the_client_sent = methods()
      .that(JavaMember.Predicates.declaredIn(JavaClass.Predicates.equivalentTo(Request.class)))
      .should(beAnHttpRequestFact())
      .as("method, path, query, headers, body and a summary: a Request that also carries what the router decided stops"
          + " being a record of the wire and becomes a place to put things (ADR-0018 §2, §9; ADR-0020 §4)");

  private static ArchCondition<JavaMethod> beAnHttpRequestFact() {
    List<String> allowed = List.of("method", "path", "query", "headers", "body", "toString");
    return new ArchCondition<JavaMethod>("be one of the request facts the contract defines") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        if (!method.getModifiers().contains(JavaModifier.PUBLIC)) {
          events.add(SimpleConditionEvent.satisfied(method, method.getName() + " is private"));
          return;
        }
        boolean constructor = method.getName().equals("<init>");
        boolean violates = !constructor && !allowed.contains(method.getName());
        events.add(violates
            ? SimpleConditionEvent.violated(method, method.getFullName() + " is a public accessor on Request named "
                + method.getName() + ", which is not one of " + allowed + ". Route parameters are not something the"
                    + " client sent, so they belong in the call, not in the record of the call — the handler receives"
                    + " them as an argument (ADR-0020 §4)")
            : SimpleConditionEvent.satisfied(method, method.getName() + " is a request fact"));
      }
    };
  }

  /**
   * The two types that route, named exactly.
   *
   * <p>{@code belongToAnyOf} would be the shorter call and is the wrong one: it includes member classes, so the rules
   * below would be measuring {@code Router.Registration} — a private record inside the table — rather than the surface
   * an application can call. {@code routerSurfaceTypes} adds the builder to the pair that has a public API to police.
   */
  private static DescribedPredicate<JavaClass> routerTypes() {
    return JavaClass.Predicates.equivalentTo(Router.class).or(JavaClass.Predicates.equivalentTo(RouteMatch.class));
  }

  private static DescribedPredicate<JavaClass> routeTableTypes() {
    return JavaClass.Predicates.equivalentTo(Router.class)
        .or(JavaClass.Predicates.equivalentTo(Router.Builder.class));
  }

  /**
   * the router answers "which handler" and never "what should the client be told".
   *
   * <p>The one boundary ADR-0019 §10 rests on. {@code 404} and {@code 405} are decisions the router informs by
   * returning an empty match and a method set, and an adapter makes by writing a status, headers and a body. If
   * {@code Router} could construct a {@code Response}, the next step is free: {@code return new Response(404, ...)}
   * instead of {@code return Optional.empty()}, and with it an error format, a content type and a status policy inside
   * the routing layer — the {@code GlobalExceptionHandler} that ADR-0018 §10 and this phase's §10 both defer. The rule
   * is a dependency statement rather than a naming one, because the drift is an import.
   */
  @ArchTest
  static final ArchRule the_router_never_answers_a_request_itself = noClasses()
      .that(routerTypes())
      .should().dependOnClassesThat().areAssignableTo(Response.class)
      .as("a router that builds a response stops being able to say \"I don't know, adapter\" — 404 and 405 are the"
          + " adapter's decisions, informed by an empty match and a method set (ADR-0019 §10)");

  /**
   * the public surface of the route table is the questions it answers, and nothing else.
   *
   * <p>Scoped to {@code Router} and {@code Router.Builder} rather than to every routing type, because
   * {@link RouteMatch}'s public surface is two accessors and it is {@code RouteMatchTest.thereIsNothingElseToAsk}
   * that holds it to exactly those — a rule here would have to allow {@code handler()} and {@code parameters()} by
   * name, and an allow-list that carries a record's generated accessors is a rule about naming rather than about
   * authority. What this rule polices is the type that owns the table.
   *
   * <p>An allow-list rather than a verb blacklist, which is the stronger statement and the one this phase needs: §13
   * promises a table that cannot change after {@code build()}, and the cheapest way to break that promise is not a
   * method called {@code clear()} — which ADR-0018's {@code no_method_writes_or_mutates} already refuses — but one
   * called {@code replace}, {@code reload} or {@code unregister}, none of which starts with any banned prefix. An
   * allow-list refuses the whole family, and refuses {@code size()} and {@code routes()} with it: methods that would
   * turn a table into something an application inspects, orders, or merges (ADR-0019 §13, §19).
   */
  @ArchTest
  static final ArchRule the_route_table_exposes_only_the_questions_it_answers = methods()
      .that(JavaMember.Predicates.declaredIn(routeTableTypes()))
      .should(beAPublicMethodOnTheAllowedList())
      .as("Router answers match and methodsFor; Router.Builder registers and builds. Anything else — unregister,"
          + " replace, reload, size, routes — is a route registry with precedence rules, which ADR-0019 §13 defers");

  private static ArchCondition<JavaMethod> beAPublicMethodOnTheAllowedList() {
    List<String> allowed = java.util.List.of("builder", "match", "methodsFor", "toString", "route", "build");
    return new ArchCondition<JavaMethod>("be one of the allowed router operations") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        if (!method.getModifiers().contains(JavaModifier.PUBLIC)) {
          // A private helper is not surface; the rules that matter for it are the dependency ones.
          events.add(SimpleConditionEvent.satisfied(method, method.getName() + " is private"));
          return;
        }
        boolean violates = !allowed.contains(method.getName());
        events.add(violates
            ? SimpleConditionEvent.violated(method, method.getFullName()
                + " is a public table operation outside the allowed set " + allowed + ". A router with a mutator is"
                + " a router a request thread can race, and a router with an inspection method is a route registry"
                + " (ADR-0019 §13, §19)")
            : SimpleConditionEvent.satisfied(method, method.getName() + " is an allowed router question"));
      }
    };
  }

  /**
   * the router implements nothing, so it is not a participant, a subscriber, or a handler.
   *
   * <p>§12 decided a router is not a {@code LifecycleParticipant}: it owns no thread, socket, executor or queue, so
   * there is nothing to start and nothing to stop, and a type frozen by the object that builds it has no phase to be in.
   * The way that erodes is an {@code implements} clause rather than a field — {@code implements LifecycleParticipant}
   * "so routes can be reloaded", or {@code AutoCloseable} "for symmetry". Core's rules already stop the first from ever
   * compiling here (test scope is not a compile classpath); this stops both inside the contract, where the JDK
   * interfaces are reachable (ADR-0019 §13, §14).
   */
  @ArchTest
  static final ArchRule the_router_implements_no_interface = classes()
      .that(routerTypes())
      .should(implementNothingAtAll())
      .as("implementing a lifecycle, closeable or subscriber interface would give a route table a phase to be in, and"
          + " a route table has no resource to start or stop (ADR-0019 §14)");

  private static ArchCondition<JavaClass> implementNothingAtAll() {
    return new ArchCondition<JavaClass>("implement no interface") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        List<String> implemented = type.getRawInterfaces().stream()
            .map(JavaClass::getName).sorted().toList();
        boolean violates = !implemented.isEmpty();
        events.add(violates
            ? SimpleConditionEvent.violated(type, type.getName() + " implements " + implemented
                + ". A router that is a participant, a closeable or a handler has acquired a phase or a resource,"
                + " which this phase refuses (ADR-0019 §14)")
            : SimpleConditionEvent.satisfied(type, type.getSimpleName() + " implements nothing"));
      }
    };
  }

  /**
   * routing reads the method and the path, and no other part of a request.
   *
   * <p>§4 and §20 as one mechanical statement. {@code Request.body()}, {@code Request.headers()} and
   * {@code Request.query()} are all reachable from a router without any new import, and each opens a door the phase
   * closed: headers is content negotiation and version routing, query is a match on a filter, body is a match that has
   * to read what the client sent before deciding who handles it — which is also a router that cannot route a request it
   * has not already buffered. Naming the three calls is enough because they are the whole surface a router could read
   * beyond the two it does (ADR-0019 §4).
   */
  @ArchTest
  static final ArchRule routing_decides_on_method_and_path_only = noClasses()
      .that(routerTypes())
      .should().callMethod(Request.class, "body")
      .orShould().callMethod(Request.class, "headers")
      // query() returns Optional<String>, whose signature mentions java.util — listed by hand for the same reason the
      // reflection rule above lists every Class overload: callMethod matches one signature, not one name.
      .orShould().callMethod(Request.class, "query")
      .as("a router that reads Accept is doing content negotiation, one that reads a version header is doing version"
          + " routing, and one that reads a body cannot answer until it has buffered it (ADR-0019 §4)");

  /**
   * no routing or middleware type exists before it has a consumer.
   *
   * <p>§20's forbidden-name list, kept as a rule rather than as a paragraph, because the names on it are exactly the
   * ones a later phase reaches for first: a {@code Route} to inspect, a {@code PathMatcher} to reuse, a
   * {@code RouteRegistry} to merge plugin tables, a {@code MiddlewareChain} to wrap a handler, a
   * {@code RouteParameterMap} to bind values. Checking member classes too is the part that matters — the honest place
   * for {@code Router$RouteNode} is inside {@code Router}, and the honest failure is the one that finds it there
   * (ADR-0019 §16, §19, and ADR-0018 §16 for the same rule about middleware).
   */
  @ArchTest
  static final ArchRule no_preemptive_routing_or_middleware_types = classes()
      .that().resideInAnyPackage(WEB)
      .should(haveNoNameThisPhaseRefusedInAdvance())
      .as("Router and RouteMatch are the routing types with consumers today: Route, RouteDefinition, RoutePattern,"
          + " PathPattern, PathMatcher, RouteRegistry, RouteTree, RouteNode, RouteParameter, RouteParameterMap,"
          + " Middleware, MiddlewareChain, Pipeline, Filter and Interceptor all need a test first (ADR-0019 §19)");

  private static ArchCondition<JavaClass> haveNoNameThisPhaseRefusedInAdvance() {
    List<String> refused = java.util.List.of(
        "Route", "RouteDefinition", "RoutePattern", "PathPattern", "PathMatcher", "RouteMatcher", "RouteRegistry",
        "RouteTree", "RouteNode", "RouteParameter", "RouteParameterMap", "RouteMatchResult", "RoutingContext",
        "Middleware", "MiddlewareChain", "Pipeline", "Filter", "Interceptor", "HandlerChain", "ParameterBinder",
        "ParameterConverter", "PathParameterResolver", "ParameterRegistry");
    return new ArchCondition<JavaClass>("not be a routing or middleware type nobody has asked for") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        String name = type.getSimpleName();
        boolean violates = refused.contains(name);
        events.add(violates
            ? SimpleConditionEvent.violated(type, type.getName() + " is named " + name
                + ", a type this phase refuses until a test shows Router and RouteMatch are not enough. Every one of"
                    + " these is a step towards the routing framework the maxim stops (ADR-0019 §4, §7, §16, §19)")
            : SimpleConditionEvent.satisfied(type, name + " has a consumer"));
      }
    };
  }
}
