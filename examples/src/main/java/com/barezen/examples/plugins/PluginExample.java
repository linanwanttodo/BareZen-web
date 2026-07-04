package com.barezen.examples.plugins;

import com.barezen.core.kernel.LifecycleOrchestrator;
import com.barezen.core.plugin.Plugin;
import com.barezen.core.plugin.PluginRegistration;
import com.barezen.data.Database;
import com.barezen.plugins.auth.AuthPlugin;
import com.barezen.plugins.openapi.OpenApiPlugin;
import com.barezen.plugins.user.UserPlugin;
import com.barezen.plugins.user.UserRepository;
import com.barezen.security.AuthenticationResult;
import com.barezen.security.Credential;
import com.barezen.security.PasswordHasher;
import com.barezen.security.Principal;
import com.barezen.security.SessionStore;

import java.time.Duration;
import java.util.List;

import javax.sql.DataSource;

/**
 * Plugins: the auth, user and OpenAPI plugins assembled into one lifecycle, ordered by their declared dependencies.
 *
 * <p>A plugin here is a {@link Plugin} — a lifecycle participant that can name itself. The three shipped plugins
 * cooperate through plain constructors, not a plugin context: a {@link UserRepository} sits on the {@link Database},
 * the {@link UserPlugin} wraps it and is both an authorization source and a password verifier, and the
 * {@link AuthPlugin} takes that verifier plus a {@link SessionStore} to issue and check bearer sessions. Each
 * declares its dependencies on its descriptor, and {@link PluginRegistration#validate} refuses cycles and duplicates
 * and returns the list ordered so every dependency is initialized before the plugin that needs it — that order is the
 * {@link LifecycleOrchestrator}'s startup order.
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

  /** The three plugins plus the repository behind them, wired to one data source. */
  record Wiring(UserRepository users, AuthPlugin auth, List<Plugin> plugins) {
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

  /** Build the plugins and their collaborators. The list is intentionally unordered; {@link #start} orders it. */
  static Wiring wiring(DataSource dataSource) {
    Database database = new Database(dataSource);
    UserRepository users = new UserRepository(database, hashing());
    UserPlugin user = new UserPlugin(users);
    SessionStore sessions = SessionStore.standard();
    AuthPlugin auth = new AuthPlugin(sessions, user, "Bearer", Duration.ofMinutes(30));
    OpenApiPlugin openapi = new OpenApiPlugin();
    openapi.add(com.barezen.web.HttpMethod.POST, "/sessions", "Log in");
    return new Wiring(users, auth, List.of(auth, user, openapi));
  }

  /** Order the plugins by declared dependency and start them on a fresh orchestrator. */
  static LifecycleOrchestrator start(Wiring wiring) {
    LifecycleOrchestrator.Builder builder = LifecycleOrchestrator.builder();
    for (Plugin plugin : PluginRegistration.validate(wiring.plugins())) {
      builder.register(plugin.descriptor().id(), plugin);
    }
    LifecycleOrchestrator app = builder.build();
    app.start();
    return app;
  }

  public static void main(String[] args) {
    Wiring wiring = wiring(dataSource());
    LifecycleOrchestrator app = start(wiring);
    try {
      System.out.println("startup order -> " + app.participantIds());

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
