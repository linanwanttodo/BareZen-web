package com.barezen.web;

/**
 * How a component hands its routes to the table an application is building (ADR-0047).
 *
 * <p><b>What an extension point is in this framework.</b> ADR-0016 §6 refused a generic {@code ExtensionPoint} token and
 * an {@code ExtensionRegistry} in core, and said the mechanism ships <em>with the first extension point</em>, declared by
 * the component that owns it so that owner can state its own rules. This is that first one. Its shape is the direct
 * consequence of the boundary the same ADR group holds: {@code barezen-web} ships a jar with no compile dependency at
 * all, not even on {@code barezen-core} (ADR-0018 §12), so this package cannot name a core token type and core cannot
 * name a {@link Router}. An extension point here is therefore one interface, declared in the package that owns the thing
 * being contributed, and a contributor implements it. There is no lookup, no registration into anything the framework
 * holds, no scanning, and no method in this mechanism takes a {@code Class} and hands back an instance — which is the
 * one sentence ADR-0006 refuses a service locator with, and it stays refused.
 *
 * <p><b>Why a {@link Router.Builder} rather than a route list.</b> A contributor that received a {@code List} of route
 * declarations would have to invent that noun, and {@code Route} is the first name
 * {@code no_preemptive_routing_or_middleware_types} refuses (ADR-0019 §19): the table has no reason to expose what was
 * put into it. Handing over the builder hands over exactly the one operation the application itself has — {@link
 * Router.Builder#route} — so a contributor registers routes with no more power than the application's own assembly code
 * has, and every rule about what a route may be (a duplicate is refused, a pattern is raw, a handler is stored not
 * invoked) applies to contributed routes unchanged.
 *
 * <p><b>Who calls it, and when.</b> The application, at assembly time, before {@link Router.Builder#build()} — the
 * {@code barezen-plugins} layer ships {@code RouteContributions.apply} as the one loop over an ordered plugin list, so
 * no application writes an {@code instanceof} itself. This is the moment ADR-0016 §4 left unassigned: a plugin needed
 * the router as a constructor argument and the application had to sequence builder, plugins, {@code initialize()},
 * {@code build()} and the adapter. A contributor declares routes instead of receiving a builder, so the sequence
 * becomes build the table, ask the contributors, freeze it, and only then start anything.
 *
 * <p><b>Order and collision.</b> Contributions run in the order the caller iterates, which after {@code
 * PluginRegistration.validate} is an order in which every dependency precedes its dependent (ADR-0041). Two contributors
 * that want the same method and the same shape lose to {@link Router.Builder}'s refusal rather than to whichever ran
 * first: a route a plugin registered cannot be shadowed by a route a later plugin registered, and "last one wins" would
 * make an application's behaviour depend on its plugin order (ADR-0019 §9).
 *
 * <p><b>This is not a plugin.</b> {@code Plugin} lives in {@code com.barezen.core.plugin} and adds a lifecycle to a
 * component; this interface adds a capability to contribute, and this package cannot name the other. A route-registering
 * plugin implements both, in a module that can see both, which is the direction the dependency is allowed to point
 * (ADR-0042).
 */
@FunctionalInterface
public interface RouteContributor {

  /**
   * Registers this contributor's routes on the table under construction.
   *
   * <p>Called once per contributor, during assembly, before the table is built. It is not called on a request path, so
   * it may do real work — read a resource, wrap a handler — but it must not start a thread or open a socket, because it
   * runs before any lifecycle has begun and nothing will stop what it starts (ADR-0003, ADR-0019 §14).
   *
   * @param routes the route table the application is building, never {@code null}. It is the caller's builder, so routes
   *     registered here land in the same table the application's own routes land in, and the caller decides when it is
   *     finished with it
   */
  void contributeRoutes(Router.Builder routes);
}
