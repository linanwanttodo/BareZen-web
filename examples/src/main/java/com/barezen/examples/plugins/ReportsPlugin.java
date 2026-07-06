package com.barezen.examples.plugins;

import com.barezen.core.plugin.Plugin;
import com.barezen.core.plugin.PluginDescriptor;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Response;
import com.barezen.web.RouteContributor;
import com.barezen.web.Router;

import java.util.List;

/**
 * A plugin that owns one endpoint and announces itself, so an application can pick it up from the classpath instead of
 * naming its constructor.
 *
 * <p>Two interfaces, one class: {@link Plugin} gives it a name and a lifecycle (ADR-0016) and {@link RouteContributor}
 * gives it routes to hand over (ADR-0047). It is public with a public no-argument constructor because
 * {@link java.util.ServiceLoader} instantiates through exactly that, and that is the limit worth reading: a plugin that
 * needed a {@code DataSource} could not be discovered, and the application would build it by hand — which is the
 * arrangement ADR-0016 §1 chose for every plugin, discovered ones being the subset that needs nothing.
 *
 * <p>It declares a dependency on {@code "user"}: not because this route reads a table, but because a plugin that ships
 * an endpoint about users is the kind that will, and declaring the edge costs one list literal. {@code
 * PluginRegistration.validate} honours it in the merged set (ADR-0041) and refuses a second plugin claiming the id
 * {@code reports}, whether the claim arrives from {@code META-INF/services} or from {@code new} (ADR-0048).
 */
public class ReportsPlugin implements Plugin, RouteContributor {

  /** Required by {@link java.util.ServiceLoader}; there is nothing to inject into a plugin that needs no collaborator. */
  public ReportsPlugin() {
  }

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor("reports", List.of("user"));
  }

  @Override
  public void contributeRoutes(Router.Builder routes) {
    routes.route(HttpMethod.GET, "/reports/status", (request, ignored) ->
        new Response(200, Headers.of("Content-Type", "text/plain"), "reports ready".getBytes()));
  }
}
