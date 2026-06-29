package com.barezen.core.plugin;

import com.barezen.core.kernel.LifecycleParticipant;

/**
 * A module an application adds to itself: it carries a stable identity, may declare what it needs from other
 * plugins, and takes part in the application's startup and shutdown.
 *
 * <p>One sentence and it is the whole design: <em>a plugin is a participant, not a container</em> (ADR-0016). This
 * interface therefore adds exactly one thing to {@link LifecycleParticipant} - {@link #descriptor()} - and owns no
 * lifecycle vocabulary of its own. There is no {@code install()}, no {@code Plugin.start()} and no adapter from one
 * to the other, because a second set of phase names would mean two engines disagreeing about order, which is what
 * ADR-0003 exists to prevent. A plugin's phases are {@code initialize}, {@code start}, {@code stop} and
 * {@code abort}, and the only thing that runs them is {@code LifecycleOrchestrator}.
 *
 * <p>Dependencies arrive through the constructor, never through a context handed to a lifecycle callback:
 *
 * <pre>
 *   Plugin auth = new AuthPlugin(router, credentialStore, passwordHasher, tokenIssuer);
 * </pre>
 *
 * <p>and not {@code context.require(Router.class)}. The distinction is not stylistic. A collaborator passed to a
 * lifecycle method is a lookup surface that grows one convenience accessor at a time until it is an
 * {@code ApplicationContext}, which ADR-0006 refuses and three ArchUnit rules enforce: nothing in
 * {@code com.barezen.core.plugin} may depend on {@code di}, {@code config}, {@code concurrent} or
 * {@code LifecycleOrchestrator}, and nothing in the rest of core may depend on this package at all.
 *
 * <p>Owning and releasing follows Phase 2.4's rule unchanged: the application's assembly code creates the plugin, so
 * the application owns it, and the orchestrator only drives it. {@code stop()} is a plugin's release hook; there is
 * no {@code uninstall()} to disagree with it.
 *
 * <p>A plugin that contributes behaviour to the framework - routes to a router, handlers to an event bus - does so
 * through an extension point that the owning component declares. No extension point exists in core yet, so there is
 * no contribution API here either, and no {@code PluginContext}: a context with nothing to contribute to is a bag
 * with a name on it, and a bag with a name on it is how {@code ApplicationContext} starts (ADR-0016).
 */
public interface Plugin extends LifecycleParticipant {

  /**
   * The plugin's identity, which is also the id its dependencies are declared against.
   *
   * <p>The orchestrator never calls this: a plugin is driven as the participant it is, and the descriptor exists for
   * the application's assembly code, which registers the plugin under {@code descriptor().id()} rather than under a
   * literal that could disagree with it.
   *
   * @return this plugin's descriptor, never {@code null}
   */
  PluginDescriptor descriptor();
}
