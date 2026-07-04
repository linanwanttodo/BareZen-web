package com.barezen.examples.plugins;

import com.barezen.core.kernel.LifecycleOrchestrator;
import com.barezen.security.AuthenticationResult;
import com.barezen.security.Credential;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the plugin example's composition: {@code UserPlugin.initialize()} creates the tables the login depends on,
 * {@code PluginRegistration.validate} orders the plugins by their declared dependencies, and a bad password issues
 * no session.
 */
final class PluginExampleTest {

  @Test
  void loginAfterStartSucceedsAndBadPasswordIssuesNoSession() {
    PluginExample.Wiring wiring = PluginExample.wiring(PluginExample.dataSource());
    LifecycleOrchestrator app = PluginExample.start(wiring);
    try {
      // Startup order follows declared dependencies: user before auth.
      assertThat(app.participantIds()).containsExactly("user", "openapi", "auth");

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
}
