# BareZen examples

Eight runnable programs in `examples/`, one public class per topic. Every example uses **only the public developer
API** — a fact enforced by the compiler, because package-private and private types in a framework module are
unreachable from a different Maven module. Every example proves itself twice: a `main()` that binds a real socket and
a TestKit-driven test the reactor runs. ADR-0045 is where the shape of this module and its two bounded decisions
(the driver, the composition adapter) are recorded.

## Build and test

Both are part of the reactor, so the ordinary build runs them:

```bash
# from the repository root, JDK 17 or JDK 21
mvn -o -B -ntp -pl examples -am test
```

That compiles every framework module the examples depend on and runs the 34 example tests — measured on the full
reactor: 29 behavioural across the eight topics, a per-module `ClassMajorContractTest` of 4, and
`ReadmeQuickStartTest`'s 1. They are also in the
full-reactor `mvn -o -B -ntp clean verify`, so no separate command is needed once the whole project is green.

## Run a `main()`

The examples deliberately keep `main()` outside the Maven lifecycle: an offline build cannot reach
`exec-maven-plugin`, and a run that only works when a network fetch succeeds is not reproducible. Instead a run is
one `java -cp` over the classes the reactor just built. After any `mvn ... package`, the classpath is the framework
modules' `target/classes` plus the two jars the examples need (SLF4J's API and, for the database and plugin examples,
H2):

```bash
M2=$HOME/.m2/repository
FRAMEWORK=framework
CP="examples/target/classes"
for m in core web http-jdk content security data plugins; do CP="$CP:$FRAMEWORK/barezen-$m/target/classes"; done
CP="$CP:$M2/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar:$M2/com/h2database/h2/2.2.224/h2-2.2.224.jar"

java -cp "$CP" com.barezen.examples.hello.HelloWorld
```

Each `main()` prints one line per observation and exits; the port is `127.0.0.1:0` (OS-assigned), never a fixed
endpoint. The recorded output of the eight runs:

| Example | Observable output |
| --- | --- |
| `hello.HelloWorld` | `GET /hello -> 200 hello, world` |
| `routing.RoutingExample` | path variable bound (`{id=42}`), `methods on /books -> [GET, POST]`, an unrouted path empty, `GET /books/7 -> book 7` |
| `requestresponse.RequestResponseExample` | echo round-trips the body, `describe` reports method/header/query, `DELETE /gone -> 204` |
| `security.SecurityExample` | password verify true/false, `/health` 200, `/report` 401 then 200 with the bearer, `/admin` 403 |
| `database.DatabaseExample` | one migration applied, two rows after a committed transaction, a rolled-back insert leaves the count at 2 |
| `plugins.PluginExample` | announced on the classpath `[reports]`, assembled order `[user, openapi, auth, reports]`, startup order `[user, openapi, auth, reports]`, the table `Router[GET /health, GET /reports/status]` with the second route contributed by the discovered plugin, a login authenticates as `ada`, a bad password issues no session |
| `openapi.OpenApiExample` | a 1201-byte document, served `200 application/json` at `/openapi.json` |
| `staticcontent.StaticFileExample` | disk files show `200`, `304` and `HEAD` framing; `GET /classpath.txt -> 200, body "hello from the example classpath"` proves a packaged resource through the public API; an unregistered path `-> 404, body "Not Found"` |

## What each example demonstrates

- **`HelloWorld`** — the three pieces every app is built from: a `Handler`, a `Router`, the JDK adapter. Nothing else
  is required to answer a request.
- **`RoutingExample`** — path variables arrive in the handler's `parameters` map, and the router's own table turns an
  unknown path into 404 and a known path with the wrong verb into 405 (the `methodsFor` question).
- **`RequestResponseExample`** — read a body, a header (`Optional`), a query (`Optional`) and write a `Response`;
  including a 204 the framework frames with no body and no length.
- **`SecurityExample`** — `SecurityMiddleware.wrap` as a route decorator, the three authentication/authorization
  outcomes (401 missing token, 403 no permission, 200 granted), and identity delivered to a `SecuredHandler` as a
  parameter, never a thread-local. `PasswordHasher` shows a real credential check.
- **`DatabaseExample`** — `Migrator` records a version if and only if its `apply` completed; `Database.transaction`
  decides commit/rollback so the callback cannot; `RowMapper` reads rows. The example names H2 because it is an
  application, not the framework (ADR-0038's rule holds where it was set).
- **`PluginExample`** — `PluginRegistration.validate` orders the plugins by their declared dependencies and the
  `LifecycleOrchestrator` starts them in that order; `UserPlugin.initialize()` creates the tables a login then needs.
  The `PasswordHashing` adapter is the composition seam the framework keeps on purpose.
  `ReportsPlugin` (its own public class, `examples/src/main/java/.../plugins/ReportsPlugin.java`) is both of this
  line's plugin features at once: it is ADR-0047's consumer — a plugin that owns an endpoint implements
  `RouteContributor`, `RouteContributions.of` picks the contributors out of the validated list, and the table is built
  and frozen before anything starts — and ADR-0048's, because it is announced in
  `examples/src/main/resources/META-INF/services/com.barezen.core.plugin.Plugin` and reaches the assembly through
  `PluginDiscovery.discover()` rather than a constructor call. `PluginExampleTest` serves that route over a real
  socket, asserts a `POST` to it is a 405, and asserts a second claim on the id `reports` aborts the assembly.
- **`OpenApiExample`** — `ApiDocument.build()` refuses a malformed document up front, and `document.handler()` serves
  the deterministic JSON from its own route on the application's router.
- **`StaticFileExample`** — `StaticContent.file()` as the handler it is: a directory listed at assembly, one route per
  file, an `ETag` computed from the bytes, and the three framing answers measured over a socket (200 with a length, 304
  with neither body nor length, HEAD stating a length it does not send). It also demonstrates the trap ADR-0054 records:
  `HEAD` is a route an application registers, not a `GET` the framework folds in, so a GET-only file answers 405.
  It also registers `StaticContent.resource(...)` for a resource packaged in the examples jar, so both supported
  content sources have a runnable public-API consumer. This program is `barezen-content`'s consumer in shipped code
  (ADR-0051, ADR-0054).

## Reading order

Start with `HelloWorld`, then `RoutingExample` and `RequestResponseExample` to see the web contract, then
`SecurityExample` for the first capability decorator, then `DatabaseExample`, then `PluginExample` for how the
capabilities compose under a lifecycle, and `OpenApiExample` last because it projects the plugin's metadata into a
served document. The TestKit guides in `docs/testkit/` cover how to test these same surfaces.
