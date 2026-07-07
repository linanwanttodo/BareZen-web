package com.barezen.console;

import com.barezen.content.StaticContent;
import com.barezen.core.kernel.LifecycleOrchestrator;
import com.barezen.core.plugin.Plugin;
import com.barezen.core.plugin.PluginRegistration;
import com.barezen.data.Database;
import com.barezen.http.jdk.JdkHttpServerAdapter;
import com.barezen.plugins.auth.AuthPlugin;
import com.barezen.plugins.openapi.ApiDocument;
import com.barezen.plugins.openapi.ApiResponse;
import com.barezen.plugins.openapi.Operation;
import com.barezen.plugins.openapi.Parameter;
import com.barezen.plugins.openapi.OperationMetadata;
import com.barezen.plugins.openapi.Schema;
import com.barezen.plugins.openapi.Security;
import com.barezen.plugins.routes.RouteContributions;
import com.barezen.plugins.user.UserPlugin;
import com.barezen.plugins.user.UserRepository;
import com.barezen.security.Authorization;
import com.barezen.security.SecurityMiddleware;
import com.barezen.security.PasswordHasher;
import com.barezen.security.SessionStore;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Handler;
import com.barezen.web.Response;
import com.barezen.web.Router;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * The console application: a sign-in form, a session, and a table of reports whose routes arrived from a plugin.
 *
 * <p>This module exists to be the consumer ADR-0016 asked for before this project would talk about a frontend — a real
 * application with named rules, rather than a documentation folder dressed as one (ADR-0054 retracted that claim). What
 * it uses is only what a project outside this repository could use: the contract's {@link Router}, the JDK adapter,
 * {@link StaticContent} for its own page, the security decorator, {@code barezen-data} over an application-supplied
 * {@link DataSource}, and the shipped {@link UserPlugin}/{@link AuthPlugin} plus a {@link ReportsPlugin} that
 * contributes its routes through ADR-0047's extension point. Nothing in the framework knows this module exists, and it
 * is compiled against public API only, which a different Maven module makes a compile fact rather than a review finding
 * (ADR-0045's leaf rule, applied again).
 *
 * <p><b>Assemble, then start, then serve</b> — the order the lifecycle's two-phase startup means (ADR-0003): the route
 * table is frozen before any plugin's {@code initialize()} has run, so a route can never point at a schema that does
 * not exist yet, and a contributor cannot open a socket or start a thread because there is no phase for it to do that
 * in. Seed rows arrive after {@link #start}, because the tables arrive with it.
 *
 * <p><b>Run it</b> with {@code java -cp <classes> com.barezen.console.Console [port]}. The port is an argument, not a
 * system property or an environment variable (ADR-0013), and {@code 0} asks the operating system.
 */
public final class Console {

  private static final byte[] HEALTHY = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);

  /**
   * The console's pages: the address a reader types, and the file behind it.
   *
   * <p>Seven files, seven addresses, none of them ending in {@code .html} — the application chooses its own URLs, which
   * is the one thing a framework-served site may do that a GitHub-Pages folder cannot (ADR-0054 vs ADR-0057).
   */
  private static final List<String[]> PAGES = List.of(
      new String[] {"/", "index.html"},
      new String[] {"/reports", "reports.html"},
      new String[] {"/session", "session.html"},
      new String[] {"/events", "events.html"},
      new String[] {"/people", "people.html"},
      new String[] {"/answers", "answers.html"},
      new String[] {"/about", "about.html"});

  /** The one script and the one stylesheet, addressed the way the pages address them. */
  private static final List<String[]> ASSETS = List.of(
      new String[] {"/app.js", "app.js"},
      new String[] {"/assets/app.css", "assets/app.css"});

  private Console() {
  }

  /**
   * What the application assembled: the ports the demo routes need, the plugin it owns, the audit trail its events
   * land in, and the clock the session-detail handler reads.
   *
   * <p>The clock is a collaborator rather than a call to {@code Instant.now()} inside a handler, so a test can pin an
   * expiry to an instant it chose — the same reason {@code StaticContent} takes no clock at all (ADR-0051).
   */
  record Assembly(UserRepository users, SessionStore sessions, AuthPlugin auth, ReportsPlugin reports, Audit audit,
                  Authorization authorization, Clock clock, List<Plugin> built) {
  }

  /**
   * An in-memory database, named per run so two consoles on one machine cannot read each other's rows.
   *
   * <p>The application chooses the driver; {@code barezen-data} never does (ADR-0038). This is the same line the data
   * and plugin examples write, and it is here to be read as a consumer's decision rather than a framework default.
   */
  public static DataSource dataSource() {
    org.h2.jdbcx.JdbcDataSource source = new org.h2.jdbcx.JdbcDataSource();
    source.setURL("jdbc:h2:mem:barezen_console_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    return source;
  }

  /** The composition seam the plugins require: {@code PasswordHasher} is final, so an adapter delegates to it. */
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

  /**
   * Build the plugins that need collaborators, in whatever order is convenient.
   *
   * <p>One {@link SessionStore} is shared on purpose: the auth plugin issues into it and the sign-out handler
   * invalidates from it, so the token the page receives is the token the store can end.
   */
  static Assembly wiring(DataSource dataSource) {
    Database database = new Database(dataSource);
    UserRepository users = new UserRepository(database, hashing());
    UserPlugin user = new UserPlugin(users);
    Audit audit = new Audit(Clock.systemUTC());
    // One source of truth for authorisation: the shipped UserPlugin answers Authorization by reading the grants table
    // through UserRepository, so the console never keeps a second map that can drift from what the page displays. A
    // static table here was tried first and disagreed with the UI about what cy was allowed to do — which is the whole
    // argument for not duplicating a decision (ADR-0042's direction, applied inside one application).
    SessionStore sessions = SessionStore.standard();
    AuthPlugin auth = new AuthPlugin(sessions, user, "Bearer", Sessions.LIFETIME);
    ReportsPlugin reports = new ReportsPlugin(database, auth, user, audit);
    return new Assembly(users, sessions, auth, reports, audit, user, Clock.systemUTC(),
        List.of(auth, user, reports));
  }

  /** Every plugin in dependency order: what the application built, plus whatever the classpath announced. */
  static List<Plugin> assemble(Assembly wiring, List<Plugin> announced) {
    List<Plugin> merged = new ArrayList<>(wiring.built());
    merged.addAll(announced);
    return PluginRegistration.validate(merged);
  }

  /**
   * The route table: the application's own routes, plus the reports plugin's, plus one handler per static file.
   *
   * <p>The page is served by the capability ADR-0051 built and ADR-0054 moved to {@code examples/} — and it is served
   * here for both {@code GET} and {@code HEAD}, because a router matches the method the client sent and a page that
   * answers 405 to a HEAD probe is a page a crawler and a cache both read as absent (ADR-0028).
   *
   * @param wiring what the application assembled
   * @param plugins the ordered plugin list whose contributors own {@code /api/reports}
   * @param web the directory holding {@code index.html}, {@code app.js} and {@code assets/app.css}
   */
  static Router router(Assembly wiring, List<Plugin> plugins, Path web) throws IOException {
    Router.Builder builder = Router.builder()
        .route(HttpMethod.GET, "/health", (request, parameters) ->
            new Response(200, Headers.of("Content-Type", "application/json"), HEALTHY))
        .route(HttpMethod.POST, "/api/sessions", Sessions.signIn(wiring.auth(), wiring.audit()))
        .route(HttpMethod.DELETE, "/api/sessions",
            Sessions.signOut(wiring.auth(), wiring.sessions(), wiring.audit()))
        .route(HttpMethod.GET, "/api/session", Sessions.whoAmI(wiring.auth(), wiring.users()))
        .route(HttpMethod.GET, "/api/session/detail",
            Sessions.detail(wiring.auth(), wiring.sessions(), wiring.clock()))
        .route(HttpMethod.POST, "/api/session/rotate",
            Sessions.rotate(wiring.auth(), wiring.sessions(), wiring.audit()));

    // Reading the trail is as privileged as reading another account's grants: it names other people's actions, so it
    // carries user:read rather than being open to whoever is signed in.
    builder.route(HttpMethod.GET, "/api/events", SecurityMiddleware.wrap(wiring.auth(), wiring.authorization(),
        People.USER_READ, (request, parameters) -> new Response(200,
            Headers.of("Content-Type", "application/json").with("Cache-Control", "no-store"),
            wiring.audit().toJson().getBytes(StandardCharsets.UTF_8))));
    People.contribute(builder, wiring.auth(), wiring.authorization(), wiring.users(), wiring.audit());

    for (String[] page : concat(PAGES, ASSETS)) {
      Path file = web.resolve(page[1]);
      if (!Files.isRegularFile(file)) {
        throw new IllegalArgumentException("the console page " + page[1] + " is not in " + web
            + "; this application serves its own frontend, so a missing file is a broken build, not a blank screen");
      }
      // Both verbs, because a router matches the method the client sent and a page that 405s at a HEAD probe reads as
      // absent to a crawler and a cache alike (ADR-0028); both wrapped, because every one of these documents may hold
      // a signed-in reader.
      Handler content = SecurityHeaders.of(StaticContent.file(file));
      builder.route(HttpMethod.GET, page[0], content);
      builder.route(HttpMethod.HEAD, page[0], content);
    }

    return builder
        .route(HttpMethod.GET, "/api/document", document().handler())
        .contributing(RouteContributions.of(plugins))
        .build();
  }

  /**
   * The document the console publishes — and only the endpoints that answer with JSON.
   *
   * <p>{@link ApiResponse} describes a JSON body or no body, so the HTML page and its script are not operations in this
   * document; that is ADR-0043's model limit, and the console meets it the way the documentation site used to: by
   * leaving them out rather than claiming a response carries no bytes when a browser expects a page.
   */
  public static ApiDocument document() {
    Schema token = Schema.object(List.of(
        new Schema.Property("token", Schema.string(), true),
        new Schema.Property("user", Schema.string(), true)));
    Schema identity = Schema.object(List.of(
        new Schema.Property("user", Schema.string(), true),
        new Schema.Property("permissions", Schema.string(), true)));
    Schema report = Schema.object(List.of(
        new Schema.Property("id", Schema.integer(), true),
        new Schema.Property("title", Schema.string(), true),
        new Schema.Property("body", Schema.string(), true)));
    Schema status = Schema.object(List.of(new Schema.Property("status", Schema.string(), true)));

    List<Operation> operations = List.of(
        Operation.builder(new OperationMetadata(HttpMethod.GET, "/health", "Liveness of this application",
                List.of("console")))
            .response(ApiResponse.json("200", "the status object", status))
            .build(),
        Operation.builder(new OperationMetadata(HttpMethod.POST, "/api/sessions", "Exchange credentials for a session",
                List.of("console")))
            .response(ApiResponse.json("200", "a bearer token", token))
            // One answer for a wrong password and an unknown user, so the document cannot promise more than the
            // handler will give (ADR-0037's enumeration rule).
            .response(ApiResponse.json("401", "credentials rejected", status))
            .build(),
        Operation.builder(new OperationMetadata(HttpMethod.DELETE, "/api/sessions", "End the caller's session",
                List.of("console")))
            .response(ApiResponse.empty("204", "the session is gone"))
            .response(ApiResponse.empty("401", "no session was presented"))
            .build(),
        Operation.builder(new OperationMetadata(HttpMethod.GET, "/api/session", "Who the bearer token identifies",
                List.of("console")))
            .response(ApiResponse.json("200", "the caller and its grants", identity))
            .response(ApiResponse.empty("401", "no session was presented"))
            .build(),
        Operation.builder(new OperationMetadata(HttpMethod.GET, "/api/reports", "The reports this application keeps",
                List.of("reports")))
            .parameters(List.of(Parameter.query("q", Schema.string(), false),
                Parameter.query("limit", Schema.integer(), false),
                Parameter.query("offset", Schema.integer(), false)))
            .response(ApiResponse.json("200", "every report row", report))
            .response(ApiResponse.empty("403", "the caller may not read reports"))
            .build(),
        Operation.builder(new OperationMetadata(HttpMethod.POST, "/api/reports", "Add a report",
                List.of("reports")))
            .response(ApiResponse.json("201", "created", status))
            .response(ApiResponse.empty("405", "the path exists for other methods"))
            .build(),
        Operation.builder(new OperationMetadata(HttpMethod.GET, "/api/reports/{id}", "One report by id",
                List.of("reports")))
            .parameter(Parameter.path("id", Schema.integer()))
            .response(ApiResponse.json("200", "the row", report))
            .response(ApiResponse.empty("404", "no row with that id"))
            .build(),
        Operation.builder(new OperationMetadata(HttpMethod.DELETE, "/api/reports/{id}", "Remove one report",
                List.of("reports")))
            .parameter(Parameter.path("id", Schema.integer()))
            .response(ApiResponse.empty("204", "the row is gone"))
            .response(ApiResponse.empty("404", "no row with that id"))
            .build(),
        Operation.builder(new OperationMetadata(HttpMethod.GET, "/api/session/detail", "When this session ends",
                List.of("console")))
            .response(ApiResponse.json("200", "the expiry the store holds", identity))
            .build(),
        Operation.builder(new OperationMetadata(HttpMethod.POST, "/api/session/rotate",
                "Trade this token for a new one", List.of("console")))
            .response(ApiResponse.json("200", "the new token and the superseded one", token))
            .build(),
        Operation.builder(new OperationMetadata(HttpMethod.GET, "/api/users/{name}/permissions",
                "The grants another account holds", List.of("console")))
            .parameter(Parameter.path("name", Schema.string()))
            .response(ApiResponse.json("200", "the named account and its grants", identity))
            .response(ApiResponse.empty("403", "the caller does not carry user:read"))
            .build(),
        Operation.builder(new OperationMetadata(HttpMethod.GET, "/api/events", "The audit trail",
                List.of("console")))
            .response(ApiResponse.json("200", "the recorded events", status))
            .response(ApiResponse.empty("403", "the caller does not carry user:read"))
            .build());

    // GET /api/reports grows query parameters, declared rather than discovered: the document is written from what the
    // handler reads, which is the only reason a reader can trust it to match (ADR-0042).

    return ApiDocument.builder()
        .info("BareZen console", "1.0")
        .server("http://127.0.0.1:8080")
        .schema("status", status)
        // The document names the scheme the page actually uses, so a client reading it knows how to authenticate.
        .securityScheme(Security.Scheme.bearer("bearer"))
        .operations(operations)
        .build();
  }

  /** Register every assembled plugin under its descriptor id and run the two startup phases. */
  static LifecycleOrchestrator start(List<Plugin> plugins) {
    LifecycleOrchestrator.Builder builder = LifecycleOrchestrator.builder();
    for (Plugin plugin : plugins) {
      builder.register(plugin.descriptor().id(), plugin);
    }
    LifecycleOrchestrator app = builder.build();
    app.start();
    return app;
  }

  /**
   * The first user and the first two rows, after the schema exists.
   *
   * <p>{@code ada/hunter2} and {@code bob/hunter2} are demo credentials written where a demo can be run by reading one line of source. A real
   * deployment seeds its own accounts; what this proves is the path through {@link UserRepository} (which hashes) and
   * {@link AuthPlugin} (which issues), not a policy for production passwords.
   */
  static void seed(Assembly wiring) {
    wiring.users().create("ada", "hunter2");
    wiring.users().grant("ada", "report:read");
    wiring.users().grant("ada", "report:write");
    // A second account with no grants, so 403 is a thing the page can show rather than a claim in prose (ADR-0037)…
    wiring.users().create("bob", "hunter2");
    // …and a third that can ask about other people, which is what makes /api/users/{name} and /api/events answerable
    // at all rather than permanently refused.
    // Three accounts, three different answers, all of them living in the grants table rather than in a decorator's
    // private map: ada reads and writes but may not ask about others, bob may do nothing, cy reads reports and may
    // ask — and People's own permissions come back empty until something grants them.
    wiring.users().create("cy", "hunter2");
    wiring.users().grant("cy", "report:read");
    wiring.users().grant("cy", "user:read");
    wiring.reports().seed("First report", "Contributed by ReportsPlugin.contributeRoutes at assembly time.");
    wiring.reports().seed("Second report", "Listed over GET /api/reports, guarded by report:read.");
  }

  /** Where the page files are, resolved from this class's own location rather than a working directory. */
  public static Path webRoot() {
    URI location;
    try {
      location = Console.class.getProtectionDomain().getCodeSource().getLocation().toURI();
    } catch (URISyntaxException | RuntimeException unreadable) {
      throw new IllegalStateException("cannot locate the classes directory this console was loaded from: "
          + unreadable.getMessage(), unreadable);
    }
    Path web = Path.of(location).resolve("web");
    if (!Files.isDirectory(web)) {
      throw new IllegalStateException("no web directory at " + web + ". The console is run from the reactor's classes"
          + " directory, where Maven has copied src/main/resources/web; serving from inside a jar would need"
          + " classpath resources, which ADR-0051 defers by name");
    }
    return web;
  }

  /**
   * Starts the console and stays up until the process is interrupted.
   *
   * @param args optionally one argument, the port to bind; anything else is refused rather than ignored
   * @throws Exception if the port is taken or the page files cannot be read
   */
  public static void main(String[] args) throws Exception {
    int port = args.length == 0 ? 8080 : parsePort(args[0]);
    Assembly wiring = wiring(dataSource());
    List<Plugin> plugins = assemble(wiring, List.of());
    Router routes = router(wiring, plugins, webRoot());
    LifecycleOrchestrator app = start(plugins);
    seed(wiring);

    JdkHttpServerAdapter server = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", port), routes);
    // The hook is for ctrl-c, the finally is for anything that goes wrong on the way up; stop() is idempotent on both
    // halves, so running the pair twice says nothing false (ADR-0012).
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      server.stop();
      app.stop();
    }, "barezen-console-shutdown"));
    server.start();
    System.out.println("console on http://127.0.0.1:" + server.port().getAsInt() + "/  (sign in as ada/hunter2)");
    System.out.println("routes -> " + routes);
    System.out.println("document -> /api/document, " + document().toJson().length() + " bytes declared");
    try {
      Thread.currentThread().join();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      server.stop();
      app.stop();
    }
  }

  /** The page list and the asset list in one, so one loop registers both. */
  private static List<String[]> concat(List<String[]> pages, List<String[]> assets) {
    List<String[]> all = new ArrayList<>(pages.size() + assets.size());
    all.addAll(pages);
    all.addAll(assets);
    return List.copyOf(all);
  }

  private static int parsePort(String argument) {
    int port;
    try {
      port = Integer.parseInt(argument);
    } catch (NumberFormatException notANumber) {
      throw new IllegalArgumentException("the port must be a number; got \"" + argument + "\". It is an argument"
          + " rather than a system property because hidden configuration is what this framework refuses elsewhere"
          + " (ADR-0013)", notANumber);
    }
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("the port must be 0-65535; got " + port + ". 0 asks the operating system for"
          + " a free one");
    }
    return port;
  }
}
