package com.barezen.examples.plugins;

import com.barezen.core.kernel.LifecycleOrchestrator;
import com.barezen.core.plugin.Plugin;
import com.barezen.core.plugin.PluginDescriptor;
import com.barezen.core.plugin.PluginDiscovery;
import com.barezen.security.AuthenticationResult;
import com.barezen.security.Credential;
import com.barezen.testkit.Assertions;
import com.barezen.testkit.TestServer;
import com.barezen.web.HttpMethod;
import com.barezen.web.Router;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the plugin example's composition: the announced plugin arrives from the classpath, {@code
 * PluginRegistration.validate} orders the merged set by declared dependencies, {@code UserPlugin.initialize()} creates
 * the tables the login depends on, a bad password issues no session, and the route the discovered plugin contributed
 * answers over a real socket.
 */
final class PluginExampleTest {

  private static List<Plugin> assemble(PluginExample.Wiring wiring) {
    return PluginExample.assemble(wiring, PluginDiscovery.discover());
  }

  @Test
  void theAnnouncedPluginArrivesFromTheClasspath() {
    List<String> announced = PluginDiscovery.discover().stream()
        .map(plugin -> plugin.descriptor().id())
        .toList();

    // examples declares exactly one provider in META-INF/services/com.barezen.core.plugin.Plugin.
    assertThat(announced).containsExactly("reports");
  }

  @Test
  void loginAfterStartSucceedsAndBadPasswordIssuesNoSession() {
    PluginExample.Wiring wiring = PluginExample.wiring(PluginExample.dataSource());
    LifecycleOrchestrator app = PluginExample.start(assemble(wiring));
    try {
      // user first, because auth and reports both declare it; reports last, because nothing declares it.
      assertThat(app.participantIds()).containsExactly("user", "openapi", "auth", "reports");

      wiring.users().create("ada", "hunter2");
      wiring.users().grant("ada", "report:read");

      String token = wiring.auth().issueSession("ada", "hunter2")
          .map(session -> session.token().value())
          .orElseThrow(() -> new AssertionError("login should have issued a session"));
      AuthenticationResult result = wiring.auth().authenticate(new Credential("Bearer", token));
      assertThat(result.isAuthenticated()).isTrue();
      assertThat(result.maybePrincipal().orElseThrow().name()).isEqualTo("ada");

      assertThat(wiring.auth().issueSession("ada", "wrong-password")).isEmpty();
      assertThat(wiring.users().permissionsOf("ada")).containsExactly("report:read");
    } finally {
      app.stop();
    }
  }

  @Test
  void authenticateRejectsAnUnknownToken() {
    PluginExample.Wiring wiring = PluginExample.wiring(PluginExample.dataSource());
    LifecycleOrchestrator app = PluginExample.start(assemble(wiring));
    try {
      AuthenticationResult result = wiring.auth().authenticate(new Credential("Bearer", "not-a-real-token"));
      assertThat(result.isAuthenticated()).isFalse();
    } finally {
      app.stop();
    }
  }

  /**
   * ADR-0047 and ADR-0048 together, end to end: a plugin that announced itself contributed a route, the table was built
   * before anything started, and the route answers on a real socket through the real JDK adapter.
   */
  @Test
  void aDiscoveredPluginsRouteIsServedByTheServer() {
    PluginExample.Wiring wiring = PluginExample.wiring(PluginExample.dataSource());
    Router routes = PluginExample.router(assemble(wiring));

    assertThat(routes).hasToString("Router[GET /health, GET /reports/status]");

    try (TestServer server = TestServer.start(routes)) {
      Assertions.assertThat(server.client().get("/reports/status")).statusIs(200).bodyIs("reports ready");
      Assertions.assertThat(server.client().get("/health")).statusIs(200).bodyIs("ok");
      Assertions.assertThat(server.client().request(HttpMethod.POST, "/reports/status").send()).statusIs(405);
    }
  }

  @Test
  void aHandBuiltPluginAndAnAnnouncedOneMayNotClaimTheSameId() {
    PluginExample.Wiring wiring = PluginExample.wiring(PluginExample.dataSource());
    Plugin alsoClaimsReports = () -> new PluginDescriptor("reports", List.of("user"));

    // Discovery is not a fallback that silently doubles up: the merged set is validated, and a second claim on one id
    // aborts the assembly whichever route it came from (ADR-0048).
    assertThatThrownBy(() -> PluginExample.assemble(wiring, List.of(new ReportsPlugin(), alsoClaimsReports)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reports");
  }
}
