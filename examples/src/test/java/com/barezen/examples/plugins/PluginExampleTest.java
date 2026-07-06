package com.barezen.examples.plugins;

import com.barezen.core.kernel.LifecycleOrchestrator;
import com.barezen.security.AuthenticationResult;
import com.barezen.security.Credential;
import com.barezen.testkit.Assertions;
import com.barezen.testkit.TestHttpClient;
import com.barezen.testkit.TestServer;
import com.barezen.web.HttpMethod;
import com.barezen.web.Router;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the plugin example's composition: {@code UserPlugin.initialize()} creates the tables the login depends on,
 * {@code PluginRegistration.validate} orders the plugins by their declared dependencies, a bad password issues no
 * session, and the route the {@code ReportsPlugin} contributed answers over a real socket.
 */
final class PluginExampleTest {

  @Test
  void loginAfterStartSucceedsAndBadPasswordIssuesNoSession() {
    PluginExample.Wiring wiring = PluginExample.wiring(PluginExample.dataSource());
    LifecycleOrchestrator app = PluginExample.start(wiring);
    try {
      // Startup order follows declared dependencies: user first, and every plugin naming it later.
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
    LifecycleOrchestrator app = PluginExample.start(wiring);
    try {
      AuthenticationResult result = wiring.auth().authenticate(new Credential("Bearer", "not-a-real-token"));
      assertThat(result.isAuthenticated()).isFalse();
    } finally {
      app.stop();
    }
  }

  /**
   * ADR-0047's example-level proof: a plugin contributed a route, the table was built before anything started, and the
   * route answers on a real socket through the real JDK adapter.
   */
  @Test
  void aPluginContributesARouteTheServerServes() {
    PluginExample.Wiring wiring = PluginExample.wiring(PluginExample.dataSource());
    Router routes = PluginExample.router(wiring);

    assertThat(routes).hasToString("Router[GET /health, GET /reports/status]");

    try (TestServer server = TestServer.start(routes)) {
      TestHttpClient client = server.client();
      Assertions.assertThat(client.get("/reports/status")).statusIs(200).bodyIs("reports ready");
      Assertions.assertThat(client.get("/health")).statusIs(200).bodyIs("ok");
      Assertions.assertThat(client.request(HttpMethod.POST, "/reports/status").send()).statusIs(405);
    }
  }
}
