# Quick start

BareZen's TestKit is one Maven artifact and three ideas: a real HTTP server on a random loopback port, a client that
speaks plain HTTP to it, and fixtures that let a test see what the framework actually did — status, headers, body, JSON,
invocation count, identity.

Add the artifact to a project's test scope:

```xml
<dependency>
  <groupId>com.barezen</groupId>
  <artifactId>barezen-testkit</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

Nothing inside BareZen's own reactor depends on TestKit; a rule in `BarezenTestKitArchitectureTest` says so, and the
shipped framework therefore never carries a test tool as a runtime dependency (ADR-0044). Add H2 (or any JDBC driver) to
your own test scope if the code under test needs a database; TestKit does not decide for you.

## The shortest useful shape

```java
import com.barezen.testkit.Assertions;
import com.barezen.testkit.TestFixtures;
import com.barezen.testkit.TestServer;
import com.barezen.web.HttpMethod;
import com.barezen.web.Router;
import org.junit.jupiter.api.Test;

class HealthTest {

  @Test
  void health_answers_200() {
    Router router = Router.builder()
        .route(HttpMethod.GET, "/health", TestFixtures.textHandler("ok"))
        .build();
    try (TestServer server = TestServer.start(router)) {
      Assertions.assertThat(server.client().get("/health")).statusIs(200).bodyIs("ok");
    }
  }
}
```

Every integration test in this guide follows the same three lines: build the router, start a server over it, assert on
the response. `TestServer.start` binds the real `JdkHttpServerAdapter` to `127.0.0.1:0` so the OS hands back a free port,
and two tests running in parallel never collide (ADR-0044). `close()` — automatic at the end of the try block — stops
the adapter exactly once and releases both the port and the threads the adapter owned.

## What to read next

- [first-integration-test.md](first-integration-test.md) walks the same test with a real handler and a request body, and
  shows the assertions that catch the mistakes a passing test can hide.
- [testing-security.md](testing-security.md) puts `SecurityMiddleware` on the same server, with a token fixture and a
  principal-capturing handler that proves no identity blended across requests.
- [testing-database.md](testing-database.md) hands TestKit the `DataSource` your test already owns and shows the reset
  boundary that keeps one test's rows out of the next.
- [testing-openapi.md](testing-openapi.md) explains the honest scope of that name in this phase: what a document looks
  like over the wire is a plugin and generator concern (ADR-0043), and this document is about asserting on the bytes.
