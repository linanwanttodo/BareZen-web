package com.barezen.plugins.routes;

import com.barezen.core.plugin.Plugin;
import com.barezen.web.RouteContributor;
import com.barezen.web.Router;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The narrowing an application cannot write for itself: from a list of {@code Plugin}s to the {@code RouteContributor}s
 * among them (ADR-0047).
 *
 * <p><b>Why this lives here and nowhere else.</b> The two types it names sit on opposite sides of a fence — {@link
 * RouteContributor} belongs to a contract that ships no compile dependency at all (ADR-0018 §12), and {@link Plugin}
 * belongs to a core package that must stay a leaf nothing else in core depends on (ADR-0016, {@code
 * no_core_type_depends_on_the_plugin_spi}). Neither can name the other, and the framework must not name either pair
 * together or it has stopped being plugin-agnostic (ADR-0042's first rule). The plugin namespace is the only place that
 * is allowed to see both, so the one loop that decides «is this component a contributor» lives here, and an application
 * never writes an {@code instanceof} or a cast.
 *
 * <p><b>It is a pure function, like {@code PluginRegistration.validate} before it (ADR-0041).</b> No state, no
 * registry, no lookup, no phase: it reads the interfaces the arguments already implement and returns a list. It cannot
 * be asked «give me the routes plugin» — there is nothing stored to give — and it runs no lifecycle callback, so a
 * component whose routes depend on something not yet initialized is still that component's problem at its own
 * {@code initialize()} rather than a hidden ordering this function could impose.
 *
 * <p><b>Order is the caller's.</b> The returned list preserves the argument's iteration order, so an application that
 * wants dependencies before dependents passes the list {@code PluginRegistration.validate} returned (ADR-0041). Order
 * does not buy precedence: {@link Router.Builder} refuses a colliding route whichever side registered first (ADR-0019
 * §9).
 */
public final class RouteContributions {

  private RouteContributions() {}

  /**
   * Picks out the components that contribute routes.
   *
   * @param plugins the components in the order their contributions should be applied, never {@code null} and never
   *     containing {@code null}; a plugin that does not implement {@link RouteContributor} is skipped rather than being
   *     an error, because most plugins contribute no routes at all
   * @return the contributors among them, in the same order, immutable
   * @throws NullPointerException if {@code plugins} or any element of it is null
   */
  public static List<RouteContributor> of(Iterable<? extends Plugin> plugins) {
    Objects.requireNonNull(plugins,
        "the plugin list; null is not an empty plugin set, and a caller that meant to pass no plugins passes List.of()");
    List<RouteContributor> contributors = new ArrayList<>();
    for (Plugin plugin : plugins) {
      Objects.requireNonNull(plugin,
          "a null plugin in the list; skipping it would silently drop whatever it was meant to contribute, and"
              + " failing says which assembly is wrong");
      if (plugin instanceof RouteContributor contributor) {
        contributors.add(contributor);
      }
    }
    return List.copyOf(contributors);
  }
}
