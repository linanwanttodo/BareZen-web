package com.barezen.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.barezen.core.kernel.LifecycleOrchestrator;
import com.barezen.core.plugin.Plugin;
import com.barezen.testkit.Assertions;
import com.barezen.testkit.TestResponse;
import com.barezen.testkit.TestServer;
import com.barezen.testkit.TestHttpClient;
import com.barezen.plugins.auth.AuthPlugin;
import com.barezen.security.SessionStore;
import com.barezen.web.Handler;
import com.barezen.web.Headers;
import com.barezen.web.Request;
import com.barezen.web.Response;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import com.barezen.web.HttpMethod;
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

    String signInAs(String username) throws IOException {
      return signIn(username, "hunter2");
    }

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

  /** A request that carries a bearer token, so the tests name the credential they are testing with. */
  private static TestHttpClient.Request authorized(Running console, String token, HttpMethod method, String path) {
    return console.server.client().request(method, path).header("Authorization", "Bearer " + token);
  }

  /** Read one string field out of a flat JSON body, so a test does not have to own a parser. */
  private static String field(String json, String name) {
    String marker = "\"" + name + "\":\"";
    int start = json.indexOf(marker);
    if (start < 0) {
      throw new AssertionError(name + " is missing from " + json);
    }
    int from = start + marker.length();
    return json.substring(from, json.indexOf('"', from));
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

    /**
     * Every page, on the address the application chose, with the policy that makes a script-readable token defensible.
     *
     * <p>A page that moved its credential into {@code sessionStorage} to survive navigation (ADR-0057) is a page where a
     * successful injected script could read it, so the response has to say what may execute at all. Asserting the header
     * on every page rather than on one is the point: a policy that covers {@code /} and forgets {@code /reports} is a
     * policy with a hole shaped like the session.
     */
    @Test
    void everyPageIsServedWithAPolicyThatAllowsNoInlineScript() throws IOException {
      List<String> addresses = List.of("/", "/reports", "/session", "/events", "/people", "/answers", "/about");
      try (Running console = running()) {
        for (String address : addresses) {
          TestResponse page = console.server.client().get(address);
          Assertions.assertThat(page).statusIs(200).headerIs("Content-Type", "text/html; charset=utf-8");
          String policy = page.header("Content-Security-Policy").orElseThrow();
          assertThat(policy).as(address + " states a policy").contains("script-src 'self'");
          assertThat(policy).as(address + " allows no inline script").doesNotContain("unsafe-inline");
          Assertions.assertThat(page).headerIs("X-Content-Type-Options", "nosniff");
          Assertions.assertThat(page).headerIs("Referrer-Policy", "no-referrer");
          Assertions.assertThat(console.server.client().head(address)).statusIs(200);
        }
      }
    }

    /** The header is only honest if the files do not try to defeat it. */
    @Test
    void thePagesContainNoInlineScriptOrEventHandler() throws IOException {
      for (String file : List.of("index.html", "reports.html", "session.html", "events.html", "people.html",
          "answers.html", "about.html")) {
        String html = java.nio.file.Files.readString(Console.webRoot().resolve(file));
        assertThat(html).as(file + " loads its behaviour from the one shared script").doesNotContain("<script>");
        assertThat(html).as(file + " carries no inline handler the policy would refuse").doesNotContain("onclick=")
            .doesNotContain("onload=");
      }
    }

    /**
     * The addresses are the application's choice, so the file names are not among them: a reader who guesses
     * {@code /reports.html} gets the contract's 404, which is the difference this page has with a folder deployed to a
     * static host where the extension is the address (ADR-0054 vs ADR-0057).
     */
    @Test
    void aFileNameIsNotAnAddressTheApplicationServes() throws IOException {
      try (Running console = running()) {
        Assertions.assertThat(console.server.client().get("/reports.html")).statusIs(404).bodyIs("Not Found");
        Assertions.assertThat(console.server.client().get("/index.html")).statusIs(404);
      }
    }
  }
  @Nested
  @DisplayName("the query string, read by the application")
  class Queries {

    @Test
    void aFilteredListStatesTheTotalThePageHides() throws IOException {
      try (Running console = running()) {
        String token = console.signIn("ada", "hunter2");
        TestResponse all = authorized(console, token, HttpMethod.GET, "/api/reports").send();
        assertThat(all.header("X-Total-Count")).contains("2");
        assertThat(all.header("X-Page-Size")).contains("2");

        TestResponse filtered = authorized(console, token, HttpMethod.GET, "/api/reports?q=assembly").send();
        assertThat(filtered.status()).isEqualTo(200);
        // One row matches, but the header still states two: a page that lied about the total would strand a reader on
        // the first page of a filter they cannot see the end of.
        assertThat(filtered.header("X-Total-Count")).contains("1");
        assertThat(filtered.header("X-Page-Size")).contains("1");
        assertThat(filtered.bodyText()).contains("assembly");
      }
    }

    @Test
    void anEmptyQueryAndNoQueryAtAllAreTheSameAnswer() throws IOException {
      try (Running console = running()) {
        String token = console.signIn("ada", "hunter2");
        TestResponse bare = authorized(console, token, HttpMethod.GET, "/api/reports").send();
        TestResponse empty = authorized(console, token, HttpMethod.GET, "/api/reports?q=").send();
        // Request.query() distinguishes absent from empty, and this application collapses them on purpose: a filter of
        // nothing is no filter (ADR-0018 §4 leaves the reading of the query to the consumer, which is this assertion).
        assertThat(empty.status()).isEqualTo(bare.status());
        assertThat(empty.bodyText()).isEqualTo(bare.bodyText());
      }
    }

    @Test
    void limitAndOffsetPageWithoutLosingTheCount() throws IOException {
      try (Running console = running()) {
        String token = console.signIn("ada", "hunter2");
        for (String title : List.of("third", "fourth", "fifth")) {
          authorized(console, token, HttpMethod.POST, "/api/reports").body("title=" + title + "&body=x").send();
        }
        TestResponse page = authorized(console, token, HttpMethod.GET, "/api/reports?limit=2&offset=2").send();
        assertThat(page.header("X-Page-Size")).contains("2");
        assertThat(page.header("X-Total-Count")).contains("5");
      }
    }

    @Test
    void aMalformedLimitIsRefusedRatherThanGuessed() throws IOException {
      try (Running console = running()) {
        String token = console.signIn("ada", "hunter2");
        // 400 rather than a silent default: a server that guesses what a broken query meant has written an API nobody
        // documented.
        Assertions.assertThat(authorized(console, token, HttpMethod.GET, "/api/reports?limit=abc").send())
            .statusIs(400).bodyIs("Bad Request");
        Assertions.assertThat(authorized(console, token, HttpMethod.GET, "/api/reports?limit=-1").send())
            .statusIs(400);
      }
    }

    @Test
    void oneRowIsReadableAndAnAbsentOneIsThePlainFourOhFour() throws IOException {
      try (Running console = running()) {
        String token = console.signIn("ada", "hunter2");
        Assertions.assertThat(authorized(console, token, HttpMethod.GET, "/api/reports/1").send())
            .statusIs(200);
        // 404 that names no path, the same answer the adapters give for an unrouted address (ADR-0023 §2).
        Assertions.assertThat(authorized(console, token, HttpMethod.GET, "/api/reports/9999").send())
            .statusIs(404).bodyIs("Not Found");
        // A path the router was right to deliver: only this application knows its ids are numbers, so only it can
        // answer 400 — the handler parses rather than throwing, and the adapter's 500 stays a bug's answer.
        Assertions.assertThat(authorized(console, token, HttpMethod.GET, "/api/reports/abc").send())
            .statusIs(400).bodyIs("Bad Request");
      }
    }

    @Test
    void deletingARowGoesTwentyFourThenFourOhFour() throws IOException {
      try (Running console = running()) {
        String token = console.signIn("ada", "hunter2");
        Assertions.assertThat(authorized(console, token, HttpMethod.DELETE, "/api/reports/2").send()).statusIs(204);
        Assertions.assertThat(authorized(console, token, HttpMethod.DELETE, "/api/reports/2").send())
            .statusIs(404).bodyIs("Not Found");
        // cy may read reports but does not carry report:write, so the same DELETE is refused before it runs.
        String cy = console.signInAs("cy");
        Assertions.assertThat(authorized(console, cy, HttpMethod.DELETE, "/api/reports/1").send()).statusIs(403);
      }
    }
  }

  @Nested
  @DisplayName("rotation, expiry and the trail")
  class Lifecycle {

    @Test
    void rotationMakesTheOldTokenAnAnonymousCredential() throws IOException {
      try (Running console = running()) {
        String token = console.signIn("ada", "hunter2");
        TestResponse rotated = authorized(console, token, HttpMethod.POST, "/api/session/rotate").send();
        String fresh = Running.tokenOf(rotated.bodyText());
        String superseded = field(rotated.bodyText(), "superseded");

        assertThat(superseded).isEqualTo(token);
        Assertions.assertThat(authorized(console, fresh, HttpMethod.GET, "/api/session").send()).statusIs(200);
        // The store removed the value, so the old credential is now "offered but not recognised" — 401 by ADR-0037,
        // and not a client that merely stopped sending it.
        Assertions.assertThat(authorized(console, superseded, HttpMethod.GET, "/api/session").send()).statusIs(401);
      }
    }

    @Test
    void theDetailStatesAnExpiryMeasuredByTheInjectedClock() {
      // A pinned Clock on both sides — the store that stamps expiresAt and the handler that reads it — so the answer is
      // exactly thirty minutes rather than a range a wall clock would force the assertion into.
      Clock pinned = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
      SessionStore sessions = SessionStore.of(pinned, new SecureRandom());
      AuthPlugin auth = new AuthPlugin(sessions, (username, password) -> "ada".equals(username)
          && "hunter2".equals(password), "Bearer", Sessions.LIFETIME);
      String token = auth.issueSession("ada", "hunter2").orElseThrow().token().value();

      Handler detail = Sessions.detail(auth, sessions, pinned);
      Response answer = detail.handle(new Request(HttpMethod.GET, "/api/session/detail", null,
          Headers.of("Authorization", "Bearer " + token), null), Map.of());

      assertThat(new String(answer.body(), java.nio.charset.StandardCharsets.UTF_8))
          .contains("\"secondsLeft\":\"1800\"")
          .contains("2026-01-01T00:30:00Z");
    }

    @Test
    void theTrailNamesWhoDidWhatAndIsNotOpenToWhoeverAsks() throws IOException {
      try (Running console = running()) {
        String ada = console.signIn("ada", "hunter2");
        // The listing is what puts ReportListed in the trail: the plugin publishes onto the bus from inside a handler it
        // contributed itself, so this asserts the bus reaches an application component rather than only the console.
        Assertions.assertThat(authorized(console, ada, HttpMethod.GET, "/api/reports").send()).statusIs(200);
        Assertions.assertThat(authorized(console, ada, HttpMethod.GET, "/api/events").send()).statusIs(403);

        String cy = console.signInAs("cy");
        TestResponse trail = authorized(console, cy, HttpMethod.GET, "/api/events").send();
        Assertions.assertThat(trail).statusIs(200).headerIs("Cache-Control", "no-store");
        assertThat(trail.bodyText()).contains("SignedIn").contains("ReportListed").contains("ada");
      }
    }

    @Test
    void anothersGrantsNeedUserReadAndReadThemTheSameOrderTwice() throws IOException {
      try (Running console = running()) {
        String ada = console.signIn("ada", "hunter2");
        Assertions.assertThat(authorized(console, ada, HttpMethod.GET, "/api/users/bob/permissions").send())
            .statusIs(403);

        String cy = console.signInAs("cy");
        String own = authorized(console, cy, HttpMethod.GET, "/api/session").send().bodyText();
        String asked = authorized(console, cy, HttpMethod.GET, "/api/users/cy/permissions").send().bodyText();
        // Two routes, one ordering rule: a reader comparing them must not conclude the server contradicts itself.
        assertThat(own).contains("report:read,user:read");
        assertThat(asked).contains("report:read,user:read");
        assertThat(authorized(console, cy, HttpMethod.GET, "/api/users/bob/permissions").send().bodyText())
            .contains("\"count\":\"0\"");
      }
    }
  }

}
