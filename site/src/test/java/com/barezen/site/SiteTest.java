package com.barezen.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.testkit.Assertions;
import com.barezen.testkit.TestServer;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.RouteMatch;
import com.barezen.web.Router;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The site checked the way an application is checked: every file reachable, every link followed, every answer's bytes
 * and framing asserted over a real socket.
 *
 * <p>There is no generator here, so there is no silent disagreement between a source and its output. What can drift is
 * a file added under {@code web/} that no route serves, an internal link that points at a page that is not there, and a
 * page translated into one language and not the other — all three are build failures below, which is the whole argument
 * for hand-writing the pages (ADR-0052, ADR-0053).
 */
class SiteTest {

  private static final Pattern LINK = Pattern.compile("href=\"(/[^\"]*)\"");

  private static Path web;
  private static Router routes;

  @BeforeAll
  static void buildTheTable() throws IOException {
    web = Site.webRoot();
    routes = Site.router(web);
  }

  /** The address a browser would use for a file under {@code web/}. */
  private static String urlFor(Path file) {
    String relative = relativized(file);
    if (relative.endsWith("index.html")) {
      return "/" + relative.substring(0, relative.length() - "index.html".length());
    }
    if (relative.endsWith(".html")) {
      return "/" + relative.substring(0, relative.length() - ".html".length());
    }
    // An asset keeps its name: the stylesheet is addressed by what it is called, not by a page-style clean URL.
    return "/" + relative;
  }

  private static String relativized(Path file) {
    return web.relativize(file).toString().replace('\\', '/');
  }

  /** Every {@code hreflang} target a page advertises for one language, in document order. */
  private static List<String> languageTargets(Path page, String language) throws IOException {
    Matcher matcher = Pattern.compile("hreflang=\"" + language + "\" href=\"([^\"]+)\"")
        .matcher(Files.readString(page, StandardCharsets.UTF_8));
    List<String> targets = new ArrayList<>();
    while (matcher.find()) {
      targets.add(matcher.group(1));
    }
    return targets;
  }

  private static List<Path> pages() throws IOException {
    try (Stream<Path> files = Files.walk(web)) {
      return files.filter(path -> path.toString().endsWith(".html")).sorted().toList();
    }
  }

  @Nested
  @DisplayName("coverage of the tree")
  class Coverage {

    @Test
    void everyFileUnderWebIsServedBySomeRoute() throws IOException {
      List<String> unreachable = new ArrayList<>();
      try (Stream<Path> files = Files.walk(web)) {
        files.filter(Files::isRegularFile).forEach(file -> {
          String url = urlFor(file);
          if (routes.match(new Request(HttpMethod.GET, url, null, Headers.empty(), null)).isEmpty()) {
            unreachable.add(web.relativize(file) + " -> " + url);
          }
        });
      }

      // A page in the tree with no route is invisible; a route with no page is caught at assembly by Site.router.
      assertThat(unreachable).isEmpty();
    }

    @Test
    void everyInternalLinkPointsAtARouteThatAnswers() throws IOException {
      List<String> dead = new ArrayList<>();
      try (Stream<Path> files = Files.walk(web)) {
        List<Path> pages = files.filter(path -> path.toString().endsWith(".html")).toList();
        for (Path page : pages) {
          String html = Files.readString(page, StandardCharsets.UTF_8);
          Matcher matcher = LINK.matcher(html);
          while (matcher.find()) {
            String target = matcher.group(1);
            boolean answered = routes.match(
                new Request(HttpMethod.GET, target, null, Headers.empty(), null)).isPresent();
            if (!answered) {
              dead.add(web.relativize(page) + " links to " + target);
            }
          }
        }
      }

      assertThat(dead).isEmpty();
    }

    @Test
    void bothLanguagesAreInTheTable() throws IOException {
      String table = Site.router(web).toString();
      assertThat(table).contains("/health", "/openapi.json", "/assets/site.css", "/docs/plugins",
          "/zh/docs/plugins", "/zh/");
      assertThat(Files.readString(web.resolve("index.html"), StandardCharsets.UTF_8)).contains("BareZen");
      assertThat(Files.readString(web.resolve("zh/index.html"), StandardCharsets.UTF_8)).contains("BareZen");
    }

    /**
     * The pairing check. A page in one language and not the other is the failure mode of a hand-maintained translation:
     * the switcher would send a reader to a 404, and nothing in the tree would say so. So every page must have its
     * counterpart, must link exactly that counterpart's address under {@code hreflang}, and must not advertise its own
     * language as an alternative to itself.
     */
    @Test
    void everyPageHasACounterpartAndBothLinkToEachOther() throws IOException {
      List<String> problems = new ArrayList<>();
      for (Path page : pages()) {
        String relative = relativized(page);
        boolean chinese = relative.startsWith("zh/");
        Path counterpart = chinese ? web.resolve(relative.substring("zh/".length()))
            : web.resolve("zh").resolve(relative);
        if (!Files.isRegularFile(counterpart)) {
          problems.add(relative + " has no counterpart at zh/" + relative);
          continue;
        }
        String other = chinese ? "en" : "zh";
        String expected = urlFor(counterpart);
        List<String> targets = languageTargets(page, other);
        if (targets.isEmpty()) {
          problems.add(relative + " links to no " + other + " page");
        } else if (!targets.stream().allMatch(expected::equals)) {
          problems.add(relative + " offers " + other + " at " + targets + ", expected only " + expected);
        }
        if (!languageTargets(page, chinese ? "zh" : "en").isEmpty()) {
          problems.add(relative + " offers its own language as an alternative");
        }
      }

      assertThat(problems).isEmpty();
    }
  }

  @Nested
  @DisplayName("what a reader gets")
  class Responses {

    @Test
    void aPageArrivesAsHtmlWithADerivedTag() {
      try (TestServer server = TestServer.start(routes)) {
        Assertions.assertThat(server.client().get("/")).statusIs(200);
        Assertions.assertThat(server.client().get("/docs/plugins")).statusIs(200).headerIs("Content-Type",
            "text/html; charset=utf-8");
      }
    }

    @Test
    void aReloadAfterNoEditIsA304WithNoBody() throws Exception {
      try (TestServer server = TestServer.start(routes)) {
        String url = server.baseUri() + "/docs/routing";
        HttpResponse<String> first = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        String etag = first.headers().firstValue("ETag").orElseThrow();
        HttpResponse<String> second = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).header("If-None-Match", etag).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("<h1>Routing &amp; HTTP</h1>");
        assertThat(second.statusCode()).isEqualTo(304);
        assertThat(second.body()).isEmpty();
        assertThat(second.headers().firstValue("Content-Length")).isEmpty();
      }
    }

    @Test
    void anEditedPageChangesTag() throws Exception {
      Path page = web.resolve("docs/reference.html");
      String original = Files.readString(page, StandardCharsets.UTF_8);
      try (TestServer server = TestServer.start(routes)) {
        String url = server.baseUri() + "/docs/reference";
        String before = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString()).headers().firstValue("ETag").orElseThrow();

        String edited = original.replace("</main>", "<p>note</p></main>");
        Files.writeString(page, edited, StandardCharsets.UTF_8);
        String after = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString()).headers().firstValue("ETag").orElseThrow();

        assertThat(after).isNotEqualTo(before);
      } finally {
        Files.writeString(page, original, StandardCharsets.UTF_8);
      }
    }

    @Test
    void aChinesePageArrivesAsHtmlWithTheSameFraming() {
      try (TestServer server = TestServer.start(routes)) {
        Assertions.assertThat(server.client().get("/zh/docs/plugins")).statusIs(200)
            .headerIs("Content-Type", "text/html; charset=utf-8");
        Assertions.assertThat(server.client().get("/zh/")).statusIs(200);
      }
    }

    /**
     * Every switcher links {@code /zh/}, and the segment-count rule makes {@code /zh} a different route rather than the
     * same page (ADR-0019 §3). One canonical address per page, so the missing separator is redirected to it instead of
     * the file being served twice.
     */
    @Test
    void theLandingPageMissingItsTrailingSeparatorIsRedirectedToTheCanonicalOne() {
      try (TestServer server = TestServer.start(routes)) {
        Assertions.assertThat(server.client().get("/zh")).statusIs(301).headerIs("Location", "/zh/");
      }
    }

    @Test
    void healthAndTheDocumentAnswerJson() {
      try (TestServer server = TestServer.start(routes)) {
        Assertions.assertThat(server.client().get("/health")).statusIs(200).jsonStringIs("status", "ok");
        Assertions.assertThat(server.client().get("/openapi.json")).statusIs(200);
      }
    }

    @Test
    void anUnknownAddressIsTheAdaptersOwnNotFound() {
      try (TestServer server = TestServer.start(routes)) {
        Assertions.assertThat(server.client().get("/no-such-page")).statusIs(404);
      }
    }

    @Test
    void aWrongVerbOnAPageIsRefusedWithWhatItDoesAllow() {
      try (TestServer server = TestServer.start(routes)) {
        Assertions.assertThat(server.client().request(HttpMethod.POST, "/docs/plugins").send()).statusIs(405)
            .headerIs("Allow", "GET");
      }
    }

    /**
     * The page's run recipe is documentation a reader pastes, and the first version of it did not work: with
     * {@code logback-classic} but not {@code logback-core} on the classpath, the adapter's own logger fails to bind and
     * {@code Site.main} dies with {@code NoClassDefFoundError: JoranException} before a port is bound. ADR-0052 recorded
     * that the recipe "actually starts a working server"; it did, but the one printed on the page was not the one that
     * was run. This asserts the printed list rather than re-running the paste, because the reactor cannot depend on the
     * machine's local repository layout.
     */
    @Test
    void theRunRecipeNamesEveryJarTheAdaptersLoggerNeeds() throws IOException {
      for (String page : List.of("docs/reference.html", "zh/docs/reference.html")) {
        String html = Files.readString(web.resolve(page), StandardCharsets.UTF_8);
        assertThat(html).contains("com.barezen.site.Site");
        assertThat(html).contains("slf4j-api-2.0.16.jar").contains("logback-classic-1.5.6.jar")
            .contains("logback-core-1.5.6.jar");
      }
    }
  }

  @Nested
  @DisplayName("the document, and what it leaves out")
  class Document {

    @Test
    void onlyTheJsonEndpointsAreDeclared() {
      Request target = new Request(HttpMethod.GET, "/openapi.json", null, Headers.empty(), null);
      com.barezen.web.Response answer = routes.match(target).map(RouteMatch::handler).orElseThrow()
          .handle(target, Map.of());

      String json = new String(answer.body(), StandardCharsets.UTF_8);
      // ADR-0043's model describes a JSON body or no body, so the HTML pages are left out rather than declared as
      // bodied responses: a document claiming no bytes where a browser receives a page is a claim about bytes that
      // never arrive.
      assertThat(json).contains("\"openapi\":\"3.0.3").contains("/health").contains("/openapi.json")
          .doesNotContain("/docs/plugins");
    }
  }

  @Nested
  @DisplayName("misassembly is refused at once")
  class Refusals {

    @Test
    void aMissingPageFailsTheAssemblyThatMapsIt() throws IOException {
      Path stripped = Files.createTempDirectory("barezen-site-stripped");
      // No pages at all: the mapped page is the first thing Site.router looks for.

      assertThatThrownBy(() -> Site.router(stripped))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("index.html");   // pages are checked in nav order, so the first route is the first name
    }

    @Test
    void theAssetDirectoryIsNotOptional() throws IOException {
      Path noAssets = Files.createTempDirectory("barezen-site-no-assets");

      // The stylesheet is referenced by every page, so a tree without it is a misassembly rather than a site with no
      // styling: the failure belongs at assembly, where the route table is built, not on a browser's blank page.
      assertThatThrownBy(() -> Site.assets(noAssets))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("assets");
    }

    @Test
    void aPortArgumentThatIsNotAPortIsRefusedRatherThanIgnored() {
      assertThatThrownBy(() -> Site.main(new String[] {"not-a-port"}))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("argument");
    }
  }
}
