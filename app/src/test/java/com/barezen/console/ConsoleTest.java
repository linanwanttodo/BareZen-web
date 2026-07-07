package com.barezen.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.barezen.core.kernel.LifecycleOrchestrator;
import com.barezen.core.plugin.Plugin;
import com.barezen.testkit.Assertions;
import com.barezen.testkit.TestResponse;
import com.barezen.testkit.TestServer;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Router;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The console checked the way an application is checked: over a real socket, on the shipped adapter, asserting the
 * answers the contract promises rather than the ones the page renders.
 *
 * <p>Every test starts its own in-memory database and its own assembled plugin set, because the point of the module is
 * that composition is ordinary code — {@code wiring}, {@code assemble}, {@code router}, {@code start}, {@code seed} —
 * and a test that reused one fixture across the class would be testing a warm application rather than the assembly.
 */
class ConsoleTest {

  /** One running console, closed with its orchestrator, so a test cannot leak a started plugin set into the next. */
  private record Running(TestServer server, Console.Assembly wiring, List<Plugin> plugins,
                         LifecycleOrchestrator application) implements AutoCloseable {

    String signIn(String username, String password) throws IOException {
      TestResponse answer = server.client().post("/api/sessions", "username=" + username + "&password=" + password);
      return answer.status() == 200 ? tokenOf(answer.bodyText()) : null;
    }

    private static String tokenOf(String body) {
      int start = body.indexOf("\"token\":\"");
      if (start < 0) {
        return null;
      }
      int from = start + "\"token\":\"".length();
      return body.substring(from, body.indexOf('"', from));
    }

    @Override
    public void close() {
      server.close();
      application.stop();
    }
  }

  private static Running running() throws IOException {
    Console.Assembly wiring = Console.wiring(Console.dataSource());
    List<Plugin> plugins = Console.assemble(wiring, List.of());
    Router routes = Console.router(wiring, plugins, Console.webRoot());
    LifecycleOrchestrator application = Console.start(plugins);
    Console.seed(wiring);
    return new Running(TestServer.start(routes), wiring, plugins, application);
  }

  @Nested
  @DisplayName("the session")
  class Session {

    @Test
    void credentialsExchangeForABearerTheSameStoreRecognises() throws IOException {
      try (Running console = running()) {
        String token = console.signIn("ada", "hunter2");
        assertThat(token).as("a correct password issues a token").isNotNull();
        Assertions.assertThat(console.server.client().request(HttpMethod.GET, "/api/session")
            .header("Authorization", "Bearer " + token).send()).statusIs(200)
            .jsonStringIs("user", "ada")
            .headerIs("Cache-Control", "no-store");
      }
    }

    @Test
    void aWrongPasswordAndAnUnknownUserAreTheSameAnswer() throws IOException {
      try (Running console = running()) {
        Assertions.assertThat(console.server.client().post("/api/sessions", "username=ada&password=wrong"))
            .statusIs(401);
        Assertions.assertThat(console.server.client().post("/api/sessions", "username= nobody&password=hunter2"))
            .statusIs(401);
        // The body says only that credentials were rejected: which half failed is a recon result (ADR-0037).
        Assertions.assertThat(console.server.client().post("/api/sessions", "username=ada&password=wrong"))
            .bodyIs("{\"error\":\"credentials rejected\"}");
      }
    }

    @Test
    void signOutEndsTheTokenRatherThanMerelyHidingIt() throws IOException {
      try (Running console = running()) {
        String token = console.signIn("ada", "hunter2");
        Assertions.assertThat(console.server.client().request(HttpMethod.DELETE, "/api/sessions")
            .header("Authorization", "Bearer " + token).send()).statusIs(204);
        // The store no longer knows the value, so the same credential is now an anonymous one (ADR-0036's point).
        Assertions.assertThat(console.server.client().request(HttpMethod.GET, "/api/session")
            .header("Authorization", "Bearer " + token).send()).statusIs(401);
        assertThat(console.wiring.sessions().size()).as("no session is left behind").isZero();
      }
    }
  }

  @Nested
  @DisplayName("what a route contributed by a plugin answers")
  class ContributedRoutes {

    @Test
    void theTableHoldsThePluginRouteBeforeAnyPhaseHasRun() throws IOException {
      Console.Assembly wiring = Console.wiring(Console.dataSource());
      List<Plugin> plugins = Console.assemble(wiring, List.of());
      Router routes = Console.router(wiring, plugins, Console.webRoot());
      // Contributed at assembly, not at start: this is the promise ADR-0047 makes and the reason a contributor may not
      // open a socket — there is no phase left in which to do it.
      assertThat(routes.match(new Request(HttpMethod.GET, "/api/reports", null,
          Headers.empty(), null))).isPresent();
      Console.start(plugins).stop();
    }

    @Test
    void aBearerWithoutThePermissionIsForbiddenWhileNoBearerIsUnauthorized() throws IOException {
      try (Running console = running()) {
        String reader = console.signIn("ada", "hunter2");
        Assertions.assertThat(console.server.client().get("/api/reports")).statusIs(401);
        // bob has a valid session and no grants, so 403 is an answer the demo can show rather than a claim in prose.
        String outsider = console.signIn("bob", "hunter2");
        Assertions.assertThat(console.server.client().request(HttpMethod.GET, "/api/reports")
            .header("Authorization", "Bearer " + outsider).send()).statusIs(403);
        TestResponse allowed = console.server.client().request(HttpMethod.GET, "/api/reports")
            .header("Authorization", "Bearer " + reader).send();
        Assertions.assertThat(allowed).statusIs(200);
        assertThat(allowed.header("X-Reader")).contains("ada");
      }
    }

    @Test
    void aWriteAddsARowThatTheNextReadShows() throws IOException {
      try (Running console = running()) {
        String token = console.signIn("ada", "hunter2");
        Assertions.assertThat(console.server.client().request(HttpMethod.POST, "/api/reports")
            .header("Authorization", "Bearer " + token)
            .body("title=third&body=written%20by%20the%20console").send()).statusIs(201);
        TestResponse rows = console.server.client().request(HttpMethod.GET, "/api/reports")
            .header("Authorization", "Bearer " + token).send();
        assertThat(rows.bodyText()).contains("third").contains("written by the console");
      }
    }

    @Test
    void aBodyOverTheAdaptersLimitNeverReachesThePlugin() throws IOException {
      try (Running console = running()) {
        String token = console.signIn("ada", "hunter2");
        // The 413 is the adapter's, above the same table ADR-0031 bounds at one mebibyte in both servers.
        Assertions.assertThat(console.server.client().request(HttpMethod.POST, "/api/reports")
            .header("Authorization", "Bearer " + token)
            .body("title=big&body=" + "x".repeat(1024 * 1024 + 64)).send()).statusIs(413);
        Assertions.assertThat(console.server.client().request(HttpMethod.GET, "/api/reports")
            .header("Authorization", "Bearer " + token).send()).statusIs(200);
      }
    }

    @Test
    void anUnsupportedMethodOnTheContributedPathSaysWhatItDoesAllow() throws IOException {
      try (Running console = running()) {
        Assertions.assertThat(console.server.client().request(HttpMethod.PUT, "/api/reports").send()).statusIs(405)
            .headerIs("Allow", "GET, POST");
      }
    }
  }

  @Nested
  @DisplayName("the page and the document")
  class Assets {

    @Test
    void thePageArrivesAsHtmlAndAReloadIsABodyFreeThreeOhFour() throws IOException {
      try (Running console = running()) {
        TestResponse page = console.server.client().get("/");
        Assertions.assertThat(page).statusIs(200).headerIs("Content-Type", "text/html; charset=utf-8");
        String tag = page.header("ETag").orElseThrow();
        TestResponse reload = console.server.client().request(HttpMethod.GET, "/")
            .header("If-None-Match", tag).send();
        Assertions.assertThat(reload).statusIs(304);
        assertThat(reload.bodyBytes()).isEmpty();
        assertThat(reload.header("Content-Length")).isEmpty();
      }
    }

    @Test
    void thePageAndItsStylesheetAnswerHeadWithALengthAndNoBytes() throws IOException {
      try (Running console = running()) {
        int length = console.server.client().get("/").bodyBytes().length;
        TestResponse head = console.server.client().head("/");
        Assertions.assertThat(head).statusIs(200);
        assertThat(head.header("Content-Length")).contains(Integer.toString(length));
        assertThat(head.bodyBytes()).isEmpty();
        Assertions.assertThat(console.server.client().head("/assets/app.css")).statusIs(200);
        // A GET-only page would answer 405 here (ADR-0028), which is why Console.router registers both verbs.
        Assertions.assertThat(console.server.client().head("/app.js")).statusIs(200);
      }
    }

    @Test
    void theDocumentDeclaresTheJsonEndpointsAndNotThePage() throws IOException {
      try (Running console = running()) {
        TestResponse answer = console.server.client().get("/api/document");
        Assertions.assertThat(answer).statusIs(200).jsonStringIs("openapi", "3.0.3");
        String json = answer.bodyText();
        assertThat(json).contains("/api/sessions").contains("/api/reports").contains("/health");
        // ADR-0043's model describes a JSON body or no body, so the HTML page stays out rather than being declared as
        // a response with no bytes where a browser receives a page.
        assertThat(json).doesNotContain("\"paths\":{\"/\":");
      }
    }

    @Test
    void anAddressTheApplicationNeverRegisteredIsTheAdaptersOwnFourOhFour() throws IOException {
      try (Running console = running()) {
        Assertions.assertThat(console.server.client().get("/nope")).statusIs(404).bodyIs("Not Found");
      }
    }
  }
}
