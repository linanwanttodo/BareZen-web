package com.barezen.core.plugin;

import java.util.List;

/**
 * The second announced provider, so a discovery test can assert more than «one thing came back».
 *
 * <p>It declares a dependency on {@code "echo"} — the other fixture — so {@code PluginRegistration.validate} has a real
 * graph to order once the discovered set is merged with an application's own plugins (ADR-0041).
 */
public class DiscoveredStatusPlugin implements Plugin {

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor("status", List.of("echo"));
  }
}
