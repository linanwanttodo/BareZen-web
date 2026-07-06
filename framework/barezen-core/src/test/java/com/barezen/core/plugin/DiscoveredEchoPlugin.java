package com.barezen.core.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * A self-sufficient plugin announced through {@code META-INF/services}, used as a discovery fixture.
 *
 * <p>It has a public no-argument constructor because {@link java.util.ServiceLoader} requires one, and it takes no
 * collaborator for the same reason made visible: a discovered plugin is one that needs nothing from the application
 * beyond its own class (ADR-0016 §7, amended by ADR-0048). Its id deliberately collides with a plugin the tests also
 * construct by hand, so «a jar and a {@code new} may not both claim one id» is an executed assertion rather than a
 * paragraph.
 */
public class DiscoveredEchoPlugin implements Plugin {

  private final List<String> phases = new ArrayList<>();

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor("echo", List.of());
  }

  @Override
  public void initialize() {
    phases.add("initialize");
  }

  @Override
  public void start() {
    phases.add("start");
  }

  /** The phases <em>this instance</em> was driven through — empty unless somebody called {@code LifecycleOrchestrator}. */
  public List<String> phases() {
    return List.copyOf(phases);
  }
}
