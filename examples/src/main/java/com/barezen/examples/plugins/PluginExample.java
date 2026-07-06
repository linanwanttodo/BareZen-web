package com.barezen.examples.plugins;

import com.barezen.core.kernel.LifecycleOrchestrator;
import com.barezen.core.plugin.Plugin;
import com.barezen.core.plugin.PluginDiscovery;
import com.barezen.core.plugin.PluginRegistration;
import com.barezen.data.Database;
import com.barezen.plugins.auth.AuthPlugin;
import com.barezen.plugins.openapi.OpenApiPlugin;
import com.barezen.plugins.routes.RouteContributions;
import com.barezen.plugins.user.UserPlugin;
import com.barezen.plugins.user.UserRepository;
import com.barezen.security.AuthenticationResult;
import com.barezen.security.Credential;
import com.barezen.security.PasswordHasher;
import com.barezen.security.Principal;
import com.barezen.security.SessionStore;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.barezen.web.Router;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * Plugins assembled three ways at once: the ones this application builds by hand, the one that announced itself on the
 * classpath, and the routes the contributors among them hand over.
 *
 * <p>A plugin here is a {@link Plugin} — a lifecycle participant that can name itself. The three shipped plugins
 * cooperate through plain constructors, not a plugin context: a {@link UserRepository} sits on the {@link Database},
 * the {@link UserPlugin} wraps it and is both an authorization source and a password verifier, and the
 * {@link AuthPlugin} takes that verifier plus a {@link SessionStore} to issue and check bearer sessions. None of them can
 * be discovered: each is constructed with collaborators, and a {@link java.util.ServiceLoader} provider is a no-argument
 * constructor, which is the limit ADR-0048 states rather than dissolves.
 *
 * <p>{@link ReportsPlugin} is the one that needs nothing, so it is announced in
 * {@code META-INF/services/com.barezen.core.plugin.Plugin} and arrives from {@link PluginDiscovery#discover()}. It also
 * owns an endpoint: it implements {@code RouteContributor} rather than receiving a {@link Router.Builder} in a
 * constructor (ADR-0047), and {@link #router} asks the contributors among the assembled list for their routes. The table
 * is built and frozen before anything starts, so the routes an application serves are decided by assembly and not by
 * which plugin ran first.
 *
 * <p>{@link PluginRegistration#validate} is where the two sources meet: it takes the hand-built and the discovered set
 * as one list, honours declared dependencies across that boundary, and refuses a duplicate id whether the claim arrives
 * from a jar or from {@code new} (ADR-0041, ADR-0048).
 *
 * <p>{@code UserPlugin.initialize()} creates the user tables, so the login must happen after {@code app.start()}, not
 * before. The OpenAPI plugin only collects operation metadata; it owns no HTTP and attaches no routes, which is why
 * its document is assembled in the openapi example rather than served from here.
 *
 * <p>Like the data example, this names its own in-memory database — an application choosing its database is exactly
 * the choice a framework module is forbidden to make (ADR-0038), and a unique name per run keeps it repeatable.
 */
public final class PluginExample {

  private PluginExample() {
  }

  /** The plugins this application constructs itself, plus the repository and the auth handle the demo needs. */
  record Wiring(UserRepository users, AuthPlugin auth, List<Plugin> built) {
  }

  public static DataSource dataSource() {
    org.h2.jdbcx.JdbcDataSource source = new org.h2.jdbcx.JdbcDataSource();
    source.setURL("jdbc:h2:mem:barezen_plugins_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    return source;
  }

  /**
   * The one adapter the plugin composition requires: {@link PasswordHasher} is a final class in the security module,
   * and {@link UserRepository.PasswordHashing} is a plugin-owned interface, so a consumer wires the two with a
   * four-line delegate. Java has no structural typing, and the framework keeps both types in their own module
   * deliberately — the composition is the application's, which is exactly what an example should show.
   */
  static UserRepository.PasswordHashing hashing() {
    PasswordHasher hasher = PasswordHasher.standard();
    return new UserRepository.PasswordHashing() {
      @Override
      public String hash(String password) {
        return hasher.hash(password);
      }

      @Override
      public boolean verify(String password, String encodedHash) {
        return hasher.verify(password, encodedHash);
      }
    };
  }

  /** Build the plugins that need collaborators. The list is intentionally unordered; {@link #assemble} orders it. */
  static Wiring wiring(DataSource dataSource) {
    Database database = new Database(dataSource);
    UserRepository users = new UserRepository(database, hashing());
    UserPlugin user = new UserPlugin(users);
    SessionStore sessions = SessionStore.standard();
    AuthPlugin auth = new AuthPlugin(sessions, user, "Bearer", Duration.ofMinutes(30));
    OpenApiPlugin openapi = new OpenApiPlugin();
    openapi.add(HttpMethod.POST, "/sessions", "Log in");
    return new Wiring(users, auth, List.of(auth, user, openapi));
  }

  /**
   * The whole plugin set: what the application built plus what the classpath announced, in dependency order.
   *
   * <p>One {@link PluginDiscovery#discover()} call, and its result goes into the same validated list the orchestrator
   * is later registered from — so the instance whose routes were contributed is the instance that gets lifecycle
   * phases. Discovery returns fresh instances every call (it stores nothing), and an application that called it twice
   * would run two plugins under one id and be refused by the validator rather than silently doubled.
   *
   * @param wiring the application's own plugins
   * @param announced the plugins found on the classpath, normally {@code PluginDiscovery.discover()}
   * @return every plugin, ordered so each one follows the ones it declares
   */
  static List<Plugin> assemble(Wiring wiring, List<Plugin> announced) {
    List<Plugin> merged = new ArrayList<>(wiring.built());
    merged.addAll(announced);
    return PluginRegistration.validate(merged);
  }

  /**
   * The route table: the application's own route plus whatever the contributors among these plugins declare.
   *
   * <p>{@link RouteContributions#of} is the only step that asks whether a plugin contributes routes, and it sits in the
   * plugin layer because that is the only layer allowed to name both {@link Plugin} and a web type (ADR-0047). Building
   * the table here, before {@link #start}, is what makes the routes decided by assembly rather than by a lifecycle.
   */
  static Router router(List<Plugin> plugins) {
    return Router.builder()
        .route(HttpMethod.GET, "/health", (request, ignored) ->
            new Response(200, Headers.of("Content-Type", "text/plain"), "ok".getBytes()))
        .contributing(RouteContributions.of(plugins))
        .build();
  }

  /** Register the assembled plugins under their descriptor ids and start them on a fresh orchestrator. */
  static LifecycleOrchestrator start(List<Plugin> plugins) {
    LifecycleOrchestrator.Builder builder = LifecycleOrchestrator.builder();
    for (Plugin plugin : plugins) {
      builder.register(plugin.descriptor().id(), plugin);
    }
    LifecycleOrchestrator app = builder.build();
    app.start();
    return app;
  }

  private static List<String> idsOf(List<Plugin> plugins) {
    return plugins.stream().map(plugin -> plugin.descriptor().id()).toList();
  }

  public static void main(String[] args) {
    Wiring wiring = wiring(dataSource());
    List<Plugin> announced = PluginDiscovery.discover();
    List<Plugin> plugins = assemble(wiring, announced);
    Router routes = router(plugins);
    LifecycleOrchestrator app = start(plugins);
    try {
      System.out.println("announced on the classpath -> " + idsOf(announced));
      System.out.println("assembled order -> " + idsOf(plugins));
      System.out.println("startup order -> " + app.participantIds());
      System.out.println("route table -> " + routes);

      // The contributed route is answerable before anything was started, and it answers with its own handler.
      routes.match(new Request(HttpMethod.GET, "/reports/status", null, Headers.empty(), null))
          .orElseThrow(() -> new IllegalStateException("the reports plugin's route is missing from the table"));
      System.out.println("GET /reports/status -> answered by the discovered plugin's handler");

      // The user table exists only after start(), because that is when UserPlugin.initialize() runs.
      wiring.users().create("ada", "hunter2");
      wiring.users().grant("ada", "report:read");

      String token = wiring.auth().issueSession("ada", "hunter2")
          .map(session -> session.token().value())
          .orElseThrow(() -> new IllegalStateException("login should have issued a session"));

      AuthenticationResult result = wiring.auth().authenticate(new Credential("Bearer", token));
      System.out.println("login then authenticate -> " + (result.isAuthenticated()
          ? "as " + result.maybePrincipal().map(Principal::name).orElse("?")
          : "rejected"));

      String stale = wiring.auth().issueSession("ada", "wrong-password")
          .map(session -> session.token().value())
          .orElse("<no session for a bad password>");
      System.out.println("bad password -> " + stale);

      System.out.println("ada permissions -> " + wiring.users().permissionsOf("ada"));
    } finally {
      app.stop();
    }
  }
}
