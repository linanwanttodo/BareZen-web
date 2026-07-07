# Authoring a BareZen plugin

What a plugin is, what it may reach, and how it gets found. The decisions behind each rule are in ADR-0016 (the SPI),
ADR-0041 (registration), ADR-0042 (which direction dependencies may point), ADR-0047 (extension points), ADR-0048
(discovery) and ADR-0049 (running inside a container).

## 1. A plugin is a lifecycle participant that can name itself

```java
public interface Plugin extends LifecycleParticipant {
  PluginDescriptor descriptor();                 // record PluginDescriptor(String id, List<String> dependencies)
}
```

That is the whole SPI. `LifecycleParticipant` supplies four phases — `initialize()`, `start()`, `stop()`, `abort()` — all
with empty defaults, so a plugin implements the ones it needs. `PluginDescriptor` has an id and a dependency list and
nothing else: no display name (nothing renders it), no version (no constraint reads it yet), no optional dependencies
(`null` is refused as a dependency entry so an optional-dependency engine cannot arrive as a missing value).

```java
public final class MetricsPlugin implements Plugin {

  @Override public PluginDescriptor descriptor() {
    return new PluginDescriptor("metrics", List.of());     // id, and the plugins that must precede this one
  }

  @Override public void start() { /* open what you will release in stop() */ }

  @Override public void stop() { /* release it; the orchestrator calls stop() in reverse order and reports failures */ }
}
```

## 2. Contributing behaviour: implement the interface the owner declares

A plugin does not receive a context. Where a plugin has something to give the framework, the framework's owner of that
thing declares an interface and the plugin implements it beside `Plugin`. Today there is one extension point: routes.

```java
public class ReportsPlugin implements Plugin, RouteContributor {   // com.barezen.web.RouteContributor

  public ReportsPlugin() {}                                        // required to be discoverable, section 3

  @Override public PluginDescriptor descriptor() {
    return new PluginDescriptor("reports", List.of("user"));
  }

  @Override public void contributeRoutes(Router.Builder routes) {
    routes.route(HttpMethod.GET, "/reports/status", this::status);  // the same route() the application calls
  }
}
```

A contributed route is a registered route: `Router.Builder`'s duplicate refusal applies, so two plugins claiming one
method-and-shape abort the assembly rather than racing to see who wins (ADR-0019 §9), and no contributor gets precedence
by running later. Contributions are applied at assembly time, before `build()` and before any phase runs — a contributor
must therefore start no thread and open no socket, because it runs before anything owns stopping it.

**There is no `PluginContext`, no `contribute(ExtensionPoint, T)` registry and no `require(Class)`.** Those are the five
steps ADR-0016 lists as how a plugin API becomes `ApplicationContext`, and every rule in `com.barezen.core.plugin`
refuses them. If your plugin needs a collaborator, ask for it in the constructor (section 4).

## 3. Being found: two routes onto the classpath

A plugin is either constructed by the application or announced in a service file:

```
META-INF/services/com.barezen.core.plugin.Plugin
  -> one line: com.example.ReportsPlugin
```

`PluginDiscovery.discover()` (or `discover(ClassLoader)` when the container's loader is not the thread's) reads those
files and returns a fresh immutable list, keeping nothing and running no phase. A `ServiceLoader` provider is a public
class with a **public no-argument constructor**, which is the limit worth reading before you design against it:

| Your plugin needs | It can be discovered |
| --- | --- |
| nothing (a route over data it computes) | yes |
| a `DataSource`, a `SessionStore`, another plugin's port | no — the application constructs it |

That is not a gap to work around inside a plugin. The only mechanism that would let a discovered plugin fetch a
collaborator is a lookup, and ADR-0006 refuses it in three places. Constructing with a collaborator is the intended
path, and it keeps the reader of the call site able to see what the plugin needs.

`barezen-auth`'s three shipped plugins are all in the second row — `AuthPlugin`, `UserPlugin`, `OpenApiPlugin` take
collaborators and are therefore built by hand. `ReportsPlugin` in `examples/` is the discoverable one.

## 4. Dependencies, and where they are enforced

Declare them honestly and the application's call to `PluginRegistration.validate` will:

```java
List<Plugin> everything = new ArrayList<>(List.of(auth, user, openapi));   // built by hand
everything.addAll(PluginDiscovery.discover());                            // announced by jars
List<Plugin> ordered = PluginRegistration.validate(everything);           // refuses duplicate id, missing
                                                                          // dependency, cycle
Router routes = Router.builder()
    .route(HttpMethod.GET, "/health", health)
    .contributing(RouteContributions.of(ordered))                          // the narrowing, once
    .build();
LifecycleOrchestrator app = LifecycleOrchestrator.builder()
    .register("user", user).register("auth", auth).register("reports", reports)
    .build();
app.start();
```

- **Ordering**: every dependency precedes its dependent, in the caller's relative order between unrelated plugins
  (ADR-0041). Registration order is startup order, so the validator's output is what the orchestrator is fed.
- **Identity**: an id claimed twice — one copy from a jar, one from `new` — fails the call. There is no "the later one
  wins", because an application whose routes depend on classpath order is not debuggable.
- **Collaborators between plugins**: pass them through a port interface the plugin declares, as `AuthPlugin` takes the
  `PasswordVerifier` that `UserPlugin` happens to implement. Never by naming another plugin's concrete class:
  `plugins_do_not_name_each_others_concrete_types` makes that a build failure.

## 5. What a plugin may not do

| Forbidden | Enforced by |
| --- | --- |
| read configuration at request time | ADR-0013: values are resolved at assembly and arrive as constructor arguments |
| look anything up by type or name | `no_core_type_depends_on_the_plugin_spi`, `plugins_do_not_reach_the_layers_the_application_assembles`, ADR-0006 |
| park state on a thread | `no_thread_local` (identity is an argument of `SecuredHandler`, ADR-0033) |
| read `System.getProperty` / the environment | `no_system_property_or_environment_reads` |
| hold static mutable state | `no_static_mutable_state` |
| name a server implementation | `no_adapter_dependency` — a plugin speaks `com.barezen.web`, not `com.sun.net.httpserver` |
| reach a framework module that does not exist yet | `the_framework_does_not_know_plugins_exist` — nothing below the plugin layer may name yours |
| acquire a second lifecycle | `Plugin extends LifecycleParticipant` is the only phase mechanism (ADR-0003, ADR-0016 §2) |

## 6. Security: a plugin's routes are not protected because they came from a plugin

`SecurityMiddleware` decorates a `Handler` (ADR-0033). If your plugin contributes a bare handler, the endpoint is
public — exactly as if the application had registered it that way. To protect your own route, take the ports in your
constructor and decorate what you register:

```java
@Override public void contributeRoutes(Router.Builder routes) {
  routes.route(HttpMethod.GET, "/reports/status",
      SecurityMiddleware.wrap(authentication, authorization, Permission.of("report:read"), new StatusHandler()));
}
```

Nothing in the framework can attach policy to a contribution retroactively, and that is a decision rather than a
missing feature: a route's protection is part of what it is, so it is composed where the route is composed.

## 7. Testing a plugin

A plugin should be unit-testable with no database and no server, which is what the constructor-injection rule buys:
`AuthPlugin` is tested against a lambda `PasswordVerifier`. For the integration half use TestKit — `TestServer` binds the
real JDK adapter on an ephemeral port, `TestHttpClient` speaks the seven verbs, `Assertions` checks status, header, body,
route invocation count and delivered identity. See `docs/testkit/first-integration-test.md`. Three things worth pinning
for any plugin: the phase order it observed (`LifecycleOrchestrator.participantIds()` is registration order), that a
collaborator's failure surfaces as a startup failure rather than a swallowed log line, and that `stop()` releases what
`start()` opened — `PluginCompositionTest` is the worked pattern.

## 8. Packaging

A plain jar. Public class, public no-argument constructor, one line in
`META-INF/services/com.barezen.core.plugin.Plugin` **only if** the plugin needs no collaborator. Depend on
`barezen-web` for the contract and `barezen-core` for the SPI; take a server dependency in nothing you publish, so an
application can run your plugin under the JDK adapter, inside a servlet container (ADR-0049) or both. Do not add a
`barezen-plugin.yaml`: `PluginDescriptor` is a description written in code that runs, and a format with a parser would
be a second registration path (ADR-0016 §3, ADR-0048's option E).

## 9. What is not here yet

Named, with the record that defers it and its trigger: plugin **version constraints** (ADR-0016 §3 admits a version only
beside a requirement), **uninstall or reload** (no removal hook exists; a `Router` is frozen at `build()` by design),
**per-route security policy on a contribution** (section 6), a **second extension point** (the event bus is the named
candidate — ADR-0047's reopen list), and **injection of collaborators into discovered plugins** (section 3; the answer
must be an explicit factory, not a lookup).
