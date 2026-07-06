package com.barezen.core.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Finding the plugins that announced themselves, and nothing else.
 *
 * <p>ADR-0016 §7 refused discovery and said a phase that answers both the plugin model and discovery at once gets the
 * wrong answer to the first. The model is settled now (ADR-0016, ADR-0041, ADR-0042, ADR-0047), so this is that separate
 * record's phase, and it keeps §7's refusal intact: discovering is <em>not</em> deciding. What lives here is the search,
 * and every decision about the result stays where ADR-0016 left it — ordering in {@link PluginRegistration#validate},
 * construction of anything with a collaborator in the application, and lifecycle in the orchestrator.
 *
 * <p><b>Why the search sits in the SPI's own package.</b> ADR-0025 §5 kept {@code ServiceLoader} out of
 * {@code barezen-web} because a contract must not decide which server runs, and that reasoning holds for a rule scoped
 * to {@code core.di} and {@code core.event}: the container and the bus may not scan, enumerate a service or load a class
 * by name, because a container that discovers has stopped being told. A plugin is different in kind: it is the one
 * package in core whose entire subject is components arriving from elsewhere. Putting the search anywhere else — in
 * {@code barezen-plugins}, say — would force an application that wants a pluggable framework to depend on the shipped
 * plugins, which inverts the arrow ADR-0042 exists to keep.
 *
 * <p><b>What a discovered plugin must be.</b> A {@code ServiceLoader} provider is a public class with a public
 * no-argument constructor, so discovery reaches exactly the plugins that need nothing: {@code ReportsPlugin} owning a
 * route is a real one, {@code AuthPlugin} needing a {@code SessionStore} and a {@code PasswordVerifier} is not, and an
 * application constructs the latter by hand as ADR-0016 §1 always required. That is a stated limit rather than a
 * gap: the alternative — handing a discovered plugin a way to ask for its collaborators — is the service locator every
 * rule in this package refuses, and it is why the two sets are merged only at {@link PluginRegistration#validate},
 * which refuses a duplicate id whether it came from a jar or from {@code new}.
 *
 * <p><b>Nothing is stored.</b> Every call re-reads the provider configuration and returns a fresh immutable list of
 * fresh instances, so this cannot be asked «give me the auth plugin» and cannot hold an instance past the call that
 * created it (ADR-0006). It is a function from a classpath to a list, in the same sense {@link PluginRegistration} is a
 * function from a list to an ordered list.
 *
 * <p><b>A broken provider fails the call.</b> A {@link java.util.ServiceConfigurationError} — a class named in a
 * services file that is missing, not public, has no no-arg constructor, or throws in its initialiser — propagates with
 * the JDK's own message naming the provider. Skipping it and carrying on would boot an application that quietly lacks
 * an integration, which is the failure mode nobody can debug; ADR-0004's rule that the original cause survives applies to
 * assembly as much as to startup.
 *
 * <p>Order: providers arrive in the order the classpath's service listings declare them, and nothing more is promised.
 * An application that cares about the order its plugins start in asks {@link PluginRegistration#validate} for it, which
 * is the only ordering this design has (ADR-0003, ADR-0041).
 */
public final class PluginDiscovery {

  private PluginDiscovery() {}

  /**
   * Loads every {@link Plugin} the thread's context class loader can find.
   *
   * <p>Reads {@code META-INF/services/com.barezen.core.plugin.Plugin} entries; returns an empty list when no component
   * announces itself, which is the ordinary case for an application with no plugin jar on its classpath.
   *
   * @return the discovered plugins, in provider-configuration order, immutable, never {@code null} and never containing
   *     {@code null}
   * @throws java.util.ServiceConfigurationError if a listed provider cannot be loaded or instantiated — the message
   *     names the provider class and the JDK keeps its cause
   */
  public static List<Plugin> discover() {
    return collect(ServiceLoader.load(Plugin.class));
  }

  /**
   * Loads every {@link Plugin} one specific class loader can find.
   *
   * <p>The overload exists for the caller that hosts plugins where the thread's context loader cannot see them — a web
   * application's own loader, or a directory read into a {@link java.net.URLClassLoader} — which is the shape an
   * embedded deployment has and a plain {@code main()} does not. Same search, same rules, and {@code Plugin} still comes
   * from the loader that loaded this class, so a plugin found through a foreign loader is an instance of the interface
   * the caller already holds.
   *
   * @param loader the class loader to search, never {@code null}
   * @return the discovered plugins, in provider-configuration order, immutable, never {@code null}
   * @throws java.util.ServiceConfigurationError if a listed provider cannot be loaded or instantiated
   * @throws NullPointerException if {@code loader} is null — a silent fallback to the context loader would search
   *     somewhere the caller did not name
   */
  public static List<Plugin> discover(ClassLoader loader) {
    Objects.requireNonNull(loader,
        "the class loader to search; null would fall back to the context loader, which is the one loader this"
            + " overload exists to override");
    return collect(ServiceLoader.load(Plugin.class, loader));
  }

  /**
   * Drains a loader into an immutable list, instantiating every provider it names.
   *
   * <p>{@code ServiceLoader} re-reads its provider configuration on every {@code iterator()} call, so draining it once
   * here is what makes a call return a list rather than a live view of the classpath, and nothing about the loader is
   * kept afterwards — the no-registry promise in method form.
   */
  private static List<Plugin> collect(ServiceLoader<Plugin> providers) {
    List<Plugin> discovered = new ArrayList<>();
    for (Plugin plugin : providers) {
      discovered.add(plugin);
    }
    return List.copyOf(discovered);
  }
}
