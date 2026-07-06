package com.barezen.plugins.routes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.core.kernel.LifecycleOrchestrator;
import com.barezen.core.plugin.Plugin;
import com.barezen.core.plugin.PluginDescriptor;
import com.barezen.core.plugin.PluginRegistration;
import com.barezen.plugins.auth.AuthPlugin;
import com.barezen.plugins.openapi.OpenApiPlugin;
import com.barezen.plugins.user.UserPlugin;
import com.barezen.web.Handler;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.barezen.web.RouteContributor;
import com.barezen.web.RouteMatch;
import com.barezen.web.Router;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The one loop that decides whether a component contributes routes, and the assembly it exists to spare an application.
 *
 * <p>ADR-0047's placement claim in test form: {@code RouteContributor} is a web type and {@code Plugin} is a core type,
 * neither may name the other, and this module is the only one allowed to hold both in one expression. What an application
 * gets is no {@code instanceof} and no cast — and what it does not get is a lookup: this returns the contributors it was
 * handed, stores nothing, and runs no lifecycle phase.
 */
class RouteContributionsTest {

  private static final Handler ROUTE = (request, ignored) -> new Response(200, Headers.empty(), "ok".getBytes());

  private static Request get(String path) {
    return new Request(HttpMethod.GET, path, null, Headers.empty(), null);
  }

  /** A plugin that contributes routes, holding no router, no context and no lookup (ADR-0047's shape). */
  private static class StatusPlugin implements Plugin, RouteContributor {

    private final List<String> phases = new ArrayList<>();
    private final String id;
    private final String path;
    private final List<String> dependencies;

    StatusPlugin(String id, List<String> dependencies) {
      this(id, "/" + id + "/status", dependencies);
    }

    StatusPlugin(String id, String path, List<String> dependencies) {
      this.id = id;
      this.path = path;
      this.dependencies = dependencies;
    }

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor(id, dependencies);
    }

    @Override
    public void contributeRoutes(Router.Builder routes) {
      routes.route(HttpMethod.GET, path, ROUTE);
    }

    @Override
    public void initialize() {
      phases.add("initialize");
    }

    @Override
    public void start() {
      phases.add("start");
    }
  }

  /** A plugin with a lifecycle and no routes, which is what all three shipped plugins are today. */
  private static final class PlainPlugin implements Plugin {

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor("plain", List.of());
    }
  }

  @Nested
  @DisplayName("the narrowing")
  class Narrowing {

    @Test
    void onlyContributorsComeBackAndTheirOrderIsTheCallers() {
      Plugin contributor = new StatusPlugin("a", List.of());
      Plugin plain = new PlainPlugin();
      Plugin other = new StatusPlugin("b", List.of());

      List<RouteContributor> contributors = RouteContributions.of(List.of(contributor, plain, other));

      assertThat(contributors).containsExactly((RouteContributor) contributor, (RouteContributor) other);
    }

    @Test
    void aPluginThatContributesNoRoutesIsSkippedRatherThanAnError() {
      assertThat(RouteContributions.of(List.of(new PlainPlugin()))).isEmpty();
    }

    @Test
    void anEmptyAssemblyContributesNothing() {
      assertThat(RouteContributions.of(List.of())).isEmpty();
    }

    @Test
    void theResultIsImmutableSoACallerCannotAddBehindIt() {
      List<RouteContributor> contributors = RouteContributions.of(List.of(new StatusPlugin("a", List.of())));

      assertThatThrownBy(() -> contributors.add(routes -> {
      })).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aNullPluginListIsRefusedRatherThanReadAsNoPlugins() {
      assertThatThrownBy(() -> RouteContributions.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aNullElementIsRefusedRatherThanDropped() {
      // Skipping a null would silently lose whatever that slot was meant to contribute — the failure mode an assembly
      // of plugins is most likely to produce and least likely to notice.
      assertThatThrownBy(() -> RouteContributions.of(Arrays.asList(new PlainPlugin(), null)))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("the assembly it replaces")
  class Assembly {

    @Test
    void routesAreContributedAtAssemblyTimeAndNoLifecyclePhaseHasRun() {
      StatusPlugin dependent = new StatusPlugin("reports", List.of("users"));
      StatusPlugin base = new StatusPlugin("users", List.of());
      List<Plugin> ordered = PluginRegistration.validate(List.of(dependent, base));

      Router routes = Router.builder()
          .route(HttpMethod.GET, "/health", ROUTE)
          .contributing(RouteContributions.of(ordered))
          .build();

      // The validator's dependency order is what the contributor list preserves (ADR-0041), and every contributed
      // route answers before initialize() has been called on anybody.
      assertThat(ordered).containsExactly(base, dependent);
      assertThat(routes.match(get("/users/status"))).get().extracting(RouteMatch::handler).isSameAs(ROUTE);
      assertThat(routes.match(get("/reports/status"))).isPresent();
      assertThat(routes.match(get("/health"))).isPresent();
      assertThat(base.phases).isEmpty();
      assertThat(dependent.phases).isEmpty();
    }

    @Test
    void theSamePluginsStartAfterTheTableIsBuiltAndCannotAddToIt() {
      StatusPlugin plugin = new StatusPlugin("users", List.of());
      Router routes = Router.builder().contributing(RouteContributions.of(List.of(plugin))).build();
      LifecycleOrchestrator app = LifecycleOrchestrator.builder()
          .register(plugin.descriptor().id(), plugin)
          .build();

      app.start();

      assertThat(plugin.phases).containsExactly("initialize", "start");
      assertThat(routes.match(get("/users/status"))).isPresent();
      app.stop();
      // A contribution is not a phase: stop() does not unregister anything, because the table an adapter holds is
      // frozen and immutable (ADR-0019 §13, ADR-0047).
      assertThat(plugin.phases).containsExactly("initialize", "start");
    }

    @Test
    void twoPluginsClaimingTheSameRouteFailTheAssemblyRatherThanRacing() {
      StatusPlugin first = new StatusPlugin("one", List.of());
      StatusPlugin collision = new StatusPlugin("two", "/one/status", List.of());

      assertThatThrownBy(() -> Router.builder()
          .contributing(RouteContributions.of(List.of(first, collision))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already registered");
    }

    @Test
    void theContributionOrderDoesNotDecideTheWinnerBecauseBothFail() {
      StatusPlugin first = new StatusPlugin("one", List.of());
      StatusPlugin collision = new StatusPlugin("two", "/one/status", List.of());

      assertThatThrownBy(() -> Router.builder()
          .contributing(RouteContributions.of(List.of(collision, first))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already registered");
    }
  }

  @Nested
  @DisplayName("the shipped plugins today")
  class ShippedPlugins {

    @Test
    void noneOfThePhase213PluginsIsARouteContributor() {
      // Honest current state, stated at the type level so no database is needed to prove a negative: AuthPlugin fills
      // security's Authentication port, UserPlugin fills Authorization, OpenApiPlugin collects declared metadata.
      // None owns endpoints, so the extension point's first real consumer is an application's own plugin, not a
      // shipped one (ADR-0047 records this rather than inventing a route-shipping plugin to look complete).
      assertThat(RouteContributor.class.isAssignableFrom(AuthPlugin.class)).isFalse();
      assertThat(RouteContributor.class.isAssignableFrom(UserPlugin.class)).isFalse();
      assertThat(RouteContributor.class.isAssignableFrom(OpenApiPlugin.class)).isFalse();
    }

    @Test
    void everyShippedPluginIsStillAPluginSoTheNarrowingIsWellTypedOnThem() {
      assertThat(Plugin.class.isAssignableFrom(AuthPlugin.class)).isTrue();
      assertThat(Plugin.class.isAssignableFrom(UserPlugin.class)).isTrue();
      assertThat(Plugin.class.isAssignableFrom(OpenApiPlugin.class)).isTrue();
    }
  }
}
