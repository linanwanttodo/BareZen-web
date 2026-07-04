package com.barezen.core.plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The check the plugin concept needs and the lifecycle engine must not do: that a set of plugins can be registered
 * at all, and in what order.
 *
 * <p>One static method, no instances, no state. {@link #validate(List)} reads what {@link PluginDescriptor}s
 * declare and either refuses the whole set or returns it ordered — it registers nothing, holds nothing, runs
 * nothing, and above all it does not start anything. Driving phases belongs to {@code LifecycleOrchestrator} and to
 * nothing else (ADR-0003, ADR-0016); this type exists because {@link PluginDescriptor#dependencies()} is a
 * declaration and ADR-0016's reasoning ("a declared dependency that nothing verifies is one thing") stops at
 * declaring. A framework that lets a plugin state a dependency its registration can silently violate has shipped a
 * broken promise; the smallest honest fix is a verifier with no powers. A registry that resolved instances,
 * exposed lookups or owned lifecycle would be the container this package's three rules refuse (ADR-0006), and
 * nothing about the guarantee below needs one.
 *
 * <p>The three refusals, each reported with the ids a fix needs (a self-dependency is refused earlier still, by
 * {@code PluginDescriptor}'s own constructor):
 * <ul>
 *   <li><b>duplicate id</b> — one id names one plugin, and two plugins claiming it cannot be registered, reported
 *       or rolled back apart. The orchestrator refuses a duplicate at {@code register(id, participant)}; that
 *       refusal happens after half an application has been assembled, and this check moves it to before any of it;
 *   <li><b>missing dependency</b> — a declared dependency no plugin in the set provides;
 *   <li><b>cycle</b> — a dependency chain that closes on itself; no registration order satisfies it, so
 *       {@code register()} could never be called successfully, and a partially applied registration order is the
 *       failure the row above exists to prevent.
 * </ul>
 *
 * <p>What a returned order means: every plugin appears after every plugin it (transitively) depends on, and
 * plugins with no declared relation between them keep the relative order the caller listed them in. That last
 * clause is deliberate and is ADR-0016 unchanged: ordering is the assembler's job, so the verifier proves the
 * caller's order satisfies the declarations rather than arbitrating one the declarations do not decide. Two
 * independent plugins are not an ambiguity to be resolved — a framework that demanded ordering between everything
 * would be answered with declared dependencies nobody means, which is a metadata lie the dependency checks exist
 * to catch.
 */
public final class PluginRegistration {

  private PluginRegistration() {}

  /**
   * Verifies a whole plugin set and returns a registration order its declarations permit.
   *
   * <p>All or nothing, deliberately: a partial validation would make the caller's "did anything fail?" question
   * depend on where in the list it looked, and the failure this guards against — half an application registered —
   * is the same one ADR-0004's startup rollback exists for. The reported ids name the plugins, because a fix is
   * always a change to one of them.
   *
   * @param plugins the whole set an application registers; the order is honoured between unrelated plugins
   * @return the same plugins, ordered so every dependency precedes its dependent
   * @throws IllegalArgumentException if the list is null or holds a null entry, or if any of the three refusals
   *     above applies
   */
  public static List<Plugin> validate(List<Plugin> plugins) {
    if (plugins == null) {
      throw new IllegalArgumentException("the plugin list must not be null; an application that registers no"
          + " plugin passes an empty list");
    }
    for (Plugin plugin : plugins) {
      if (plugin == null) {
        throw new IllegalArgumentException("the plugin list must not contain null; the refusal reports ids, and a"
          + " null entry has none");
      }
    }

    Map<String, Plugin> byId = index(plugins);

    List<String> order = new ArrayList<>(byId.size());
    Set<String> placed = new HashSet<>();
    // Repeated passes over the same declarations; a cycle is what makes a pass place nothing while plugins remain.
    while (placed.size() < byId.size()) {
      List<String> ready = new ArrayList<>();
      // Iterating in the caller's insertion order is what the "relative order is honoured" clause runs on.
      for (Map.Entry<String, Plugin> entry : byId.entrySet()) {
        if (!placed.contains(entry.getKey())
            && placedAll(entry.getValue().descriptor().dependencies(), placed)) {
          ready.add(entry.getKey());
        }
      }
      if (ready.isEmpty()) {
        // Names every unplaced plugin: a cycle involves all of them, and "which two?" has no honest answer
        // because the set as a whole is what no order satisfies.
        List<String> stuck = new ArrayList<>();
        for (String id : byId.keySet()) {
          if (!placed.contains(id)) {
            stuck.add(id);
          }
        }
        throw new IllegalArgumentException("no registration order satisfies the plugin set: these plugins"
            + " depend on each other in a cycle " + stuck + "; a plugin that needs a plugin that needs it back"
            + " describes two halves of one unit");
      }
      order.addAll(ready);
      placed.addAll(ready);
    }

    List<Plugin> ordered = new ArrayList<>(order.size());
    for (String id : order) {
      ordered.add(byId.get(id));
    }
    return List.copyOf(ordered);
  }

  /** Indexes by id (refusing duplicates), then verifies every declaration is provided (refusing absences). */
  private static Map<String, Plugin> index(List<Plugin> plugins) {
    Map<String, Plugin> byId = new LinkedHashMap<>();
    for (Plugin plugin : plugins) {
      PluginDescriptor descriptor = plugin.descriptor();
      if (descriptor == null) {
        throw new IllegalArgumentException("plugin implementation " + plugin.getClass().getName()
            + " returned a null descriptor; descriptor() is a plugin's identity, not an optional extra");
      }
      Plugin previous = byId.put(descriptor.id(), plugin);
      if (previous != null) {
        throw new IllegalArgumentException("plugins '" + previous.getClass().getName() + "' and '"
            + plugin.getClass().getName() + "' both claim the id '" + descriptor.id() + "'; one id names one"
            + " plugin — registration, startup failures and rollback are all reported against it");
      }
    }
    for (Plugin plugin : byId.values()) {
      for (String dependency : plugin.descriptor().dependencies()) {
        if (!byId.containsKey(dependency)) {
          throw new IllegalArgumentException("plugin '" + plugin.descriptor().id() + "' declares a dependency on"
              + " '" + dependency + "', which the set does not contain; a missing dependency is not an optional"
              + " one (a blank or self dependency is refused earlier, by PluginDescriptor)");
        }
      }
    }
    return byId;
  }

  private static boolean placedAll(List<String> dependencies, Set<String> placed) {
    for (String dependency : dependencies) {
      if (!placed.contains(dependency)) {
        return false;
      }
    }
    return true;
  }
}
