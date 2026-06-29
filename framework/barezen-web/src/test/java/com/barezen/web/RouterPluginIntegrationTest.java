package com.barezen.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.core.kernel.LifecycleOrchestrator;
import com.barezen.core.kernel.StartupException;
import com.barezen.core.plugin.Plugin;
import com.barezen.core.plugin.PluginDescriptor;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A {@link Plugin} that registers routes, driven by the real lifecycle engine (ADR-0019 §15).
 *
 * <p>Everything in this file is the one question the phase asks about integration: <em>can a plugin contribute a
 * route to a router without the router becoming a container and without a plugin being handed a context to look
 * things up in?</em> The answer is written as the smallest application that could possibly show it — an assembly that
 * builds a {@link Router.Builder}, constructs the plugin with it, drives {@code initialize()} through
 * {@link LifecycleOrchestrator}, freezes the table, and routes a request.
 *
 * <p>The dependency on {@code barezen-core} is test scope only, which is what lets this run here rather than in a
 * third module: the shipped {@code barezen-web.jar} still has no dependencies, and a main source file that imported
 * core would not compile at all. See the pom comment and ADR-0019 §15.
 */
class RouterPluginIntegrationTest {

  private static Request request(HttpMethod method, String path) {
    return new Request(method, path, null, Headers.empty(), null);
  }

  /** The store a plugin owns, so a routed request can be observed arriving somewhere other than the router. */
  private static final class UserStore {

    private final Map<String, String> users = new ConcurrentHashMap<>();
    private final AtomicInteger reads = new AtomicInteger();

    UserStore() {
      users.put("42", "{\"id\":\"42\"}");
    }

    Optional<String> find(String id) {
      reads.incrementAndGet();
      return Optional.ofNullable(users.get(id));
    }

    int reads() {
      return reads.get();
    }
  }

  /**
   * A plugin whose only web collaborator is the route table under construction.
   *
   * <p>Note what is absent: no {@code PluginContext}, no {@code require(Router.class)}, no configuration, no event
   * bus, no assembler. The builder arrives as a constructor argument because the application built it — the
   * {@code Plugin} javadoc's own example, {@code new AuthPlugin(router, ...)}, run one step further.
   */
  private static final class UserPlugin implements Plugin {

    private final Router.Builder routes;
    private final UserStore store;
    private final AtomicInteger initializations = new AtomicInteger();

    UserPlugin(Router.Builder routes, UserStore store) {
      this.routes = routes;
      this.store = store;
    }

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor("users", List.of());
    }

    @Override
    public void initialize() {
      initializations.incrementAndGet();
      // Registering here rather than in the constructor: a plugin is built during assembly, when its collaborators
      // are not necessarily ready, and initialize() is the phase whose documented purpose is "complete internal
      // wiring" — which is what binding routes to a table is (ADR-0003, ADR-0019 §15).
      routes.route(HttpMethod.GET, "/users/{id}", this::readUser);
      routes.route(HttpMethod.POST, "/users", request -> new Response(201, Headers.empty(), null));
    }

    /**
     * The handler for {@code GET /users/{id}}.
     *
     * <p>It reads the identifier off the path rather than being handed the {@link RouteMatch}: {@link Handler#handle(Request)}
     * takes a request and nothing else, Phase 2.7 froze that signature, and §1 of this phase forbids rewriting it for the
     * router's sake. The two ways out — a {@code RouteMatch}-taking handler, or parameters stashed on the request — are
     * both contract changes, and the second is the servlet {@code setAttribute} ADR-0018 §8 refuses. What makes the
     * workaround safe is that the router already proved this is a one-segment tail and captured it raw, so the handler is
     * re-deriving a value the table just computed rather than parsing a path nobody has checked (ADR-0019 §7, and the
     * design debt §25 asks to be named).
     */
    private Response readUser(Request request) {
      String path = request.path();
      String id = path.substring(path.lastIndexOf('/') + 1);

      return store.find(id)
          .map(json -> new Response(200, Headers.of("Content-Type", "application/json"),
              json.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          .orElseGet(() -> new Response(404, Headers.empty(), null));
    }

    @Override
    public void stop() {
      // Nothing to release: a plugin that registers routes owns no resource, and the router owns no lifecycle
      // (ADR-0019 §14). The empty default would have served; this is here to say so.
    }

    int initializations() {
      return initializations.get();
    }
  }

  @Nested
  @DisplayName("the assembly the phase promises")
  class Assembly {

    @Test
    void aPluginRegistersRoutesAndTheEngineDrivesIt() {
      Router.Builder routes = Router.builder();
      UserStore store = new UserStore();
      UserPlugin plugin = new UserPlugin(routes, store);

      Router router;
      try (LifecycleOrchestrator application = LifecycleOrchestrator.builder()
          // No shutdown hook: a test that installs one races the JVM on exit, and the assembly being proven here is
          // the participant set, not the hook.
          .registerShutdownHook(false)
          .register(plugin.descriptor().id(), plugin)
          .build()) {
        // Before startup the table has nothing to hand out: a plugin registers in initialize(), which the
        // orchestrator owns, so an application that routes too early is routing an incomplete application.
        assertThat(routes.build().match(request(HttpMethod.GET, "/users/42"))).isEmpty();

        application.start();
        router = routes.build();

        assertThat(plugin.initializations()).isEqualTo(1);
        RouteMatch found = router.match(request(HttpMethod.GET, "/users/42")).orElseThrow();
        Response answered = found.handler().handle(request(HttpMethod.GET, "/users/42"));

        assertThat(answered.status()).isEqualTo(200);
        assertThat(new String(answered.body(), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("{\"id\":\"42\"}");
        assertThat(found.parameters()).containsExactly(Map.entry("id", "42"));
        assertThat(store.reads()).isEqualTo(1);
      }

      // The frozen table outlives the orchestrator it was built next to, which is the point of routing being data:
      // nothing here is a resource to close.
      assertThat(router).hasToString("Router[GET /users/{id}, POST /users]");
    }

    @Test
    void twoPluginsShareOneTableWithoutMeetingEachOther() {
      Router.Builder routes = Router.builder();
      UserStore store = new UserStore();
      UserPlugin users = new UserPlugin(routes, store);
      HealthPlugin health = new HealthPlugin(routes);

      try (LifecycleOrchestrator application = LifecycleOrchestrator.builder()
          .registerShutdownHook(false)
          .register(users.descriptor().id(), users)
          .register(health.descriptor().id(), health)
          .build()) {
        application.start();

        Router router = routes.build();
        assertThat(router.match(request(HttpMethod.GET, "/healthz"))).get()
            .extracting(RouteMatch::handler).isSameAs(health.handler);
        assertThat(router.match(request(HttpMethod.GET, "/users/42"))).isPresent();
        // One table answering for both contributors, with each route's own method list: /users is the collection, so
        // only POST is registered there, while /users/42 is the item route the users plugin added.
        assertThat(router.methodsFor("/users")).containsExactly(HttpMethod.POST);
        assertThat(router.methodsFor("/users/42")).containsExactly(HttpMethod.GET);
        assertThat(router).hasToString("Router[GET /users/{id}, POST /users, GET /healthz]");
      }
    }

    @Test
    void aDuplicateRouteFromASecondPluginFailsAtStartupNotSilently() {
      Router.Builder routes = Router.builder().route(HttpMethod.GET, "/users", request -> null);
      ClashingPlugin clash = new ClashingPlugin(routes);

      try (LifecycleOrchestrator application = LifecycleOrchestrator.builder()
          .registerShutdownHook(false)
          .register(clash.descriptor().id(), clash)
          .build()) {
        // This is why the duplicate rule refuses rather than replaces: with two plugins in one application, "last one
        // wins" makes the route depend on which plugin the assembler happened to register second, and the failure
        // arrives as a wrong response in production instead of as a StartupException naming the plugin (ADR-0019 §9).
        assertThatThrownBy(application::start)
            .isInstanceOf(StartupException.class)
            .satisfies(thrown -> assertThat(((StartupException) thrown).participantId()).isEqualTo("clash"))
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasRootCauseMessage("GET /users is already registered as /users: both have the shape users, so both"
                + " would answer the same request and the winner would be whichever was registered first. Replacing a"
                + " route loses a handler silently, and deciding by order makes an application's behaviour depend on"
                + " which plugin happened to register first, which is not a question a router should be answering at"
                + " request time (ADR-0019 §9)");
      }
    }

    @Test
    void thePluginTakesOnlyWhatItRegistersInto() {
      // Constructor injection with a surface a reader can check: Router.Builder and the plugin's own store, no more.
      assertThat(UserPlugin.class.getDeclaredConstructors()).hasSize(1);
      assertThat(java.util.Arrays.stream(UserPlugin.class.getDeclaredConstructors()[0].getParameterTypes())
          .map(Class::getName))
          .containsExactly(Router.Builder.class.getName(), UserStore.class.getName());

      for (java.lang.reflect.Field field : UserPlugin.class.getDeclaredFields()) {
        if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
          assertThat(List.of("com.barezen.core.config.Configuration", "com.barezen.core.event.EventBus",
              "com.barezen.core.di.ServiceAssembly", "com.barezen.core.kernel.LifecycleOrchestrator"))
              .doesNotContain(field.getType().getName());
        }
      }
    }

    @Test
    void coreHandsOutNoContextForAPluginToLookThingsUpIn() {
      // Asserted rather than assumed, because ADR-0016 promises it and this is the test that would notice a
      // PluginContext arriving later: a context with a router in it is how a router becomes a service locator.
      assertThat(java.util.Arrays.stream(Plugin.class.getClasses()).map(Class::getName))
          .doesNotContain("com.barezen.core.plugin.PluginContext");
      assertThat(java.util.Arrays.stream(Plugin.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
          .containsExactly("descriptor");
    }

    @Test
    void theRouterIsNotAParticipantAndNobodyRegistersItAsOne() {
      assertThat(com.barezen.core.kernel.LifecycleParticipant.class.isAssignableFrom(Router.class)).isFalse();
      assertThat(Router.class.getInterfaces()).isEmpty();
    }
  }

  /** A second contributor, so "one table, several plugins" is shown rather than asserted about one plugin. */
  private static final class HealthPlugin implements Plugin {

    final Handler handler = request -> new Response(200, Headers.empty(), null);
    private final Router.Builder routes;

    HealthPlugin(Router.Builder routes) {
      this.routes = routes;
    }

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor("health", List.of());
    }

    @Override
    public void initialize() {
      routes.route(HttpMethod.GET, "/healthz", handler);
    }
  }

  /** A plugin that registers a route the application already has — the collision a review would otherwise miss. */
  private static final class ClashingPlugin implements Plugin {

    private final Router.Builder routes;

    ClashingPlugin(Router.Builder routes) {
      this.routes = routes;
    }

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor("clash", List.of());
    }

    @Override
    public void initialize() {
      routes.route(HttpMethod.GET, "/users", request -> new Response(200, Headers.empty(), null));
    }
  }
}
