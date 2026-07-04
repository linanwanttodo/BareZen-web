# Your first integration test

A test is "integration" only when what crosses the socket is what the framework produced. `TestServer` starts the
shipped `JdkHttpServerAdapter` bound to `127.0.0.1:0` and hands you a `TestHttpClient` that speaks HTTP/1.1 against
it; every framing decision made in Phase 2.9 and 2.10 — HEAD without a body, 204 without content, a configured
request-body limit — is real here, not stubbed.

## A handler that reads a body

```java
import com.barezen.testkit.Assertions;
import com.barezen.testkit.TestServer;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.barezen.web.Router;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CreateUserTest {

  @Test
  void post_returns_the_created_resource_location() {
    Router router = Router.builder()
        .route(HttpMethod.POST, "/users", (Request request, Map<String, String> params) -> new Response(
            201,
            Headers.of("Location", "/users/42"),
            ("{\"created\":" + new String(request.body(), StandardCharsets.UTF_8) + "}")
                .getBytes(StandardCharsets.UTF_8)))
        .build();
    try (TestServer server = TestServer.start(router)) {
      var response = server.client().post("/users", "{\"name\":\"ada\"}");
      Assertions.assertThat(response)
          .statusIs(201)
          .headerIs("Location", "/users/42")
          .jsonStringIs("created.name", "ada");
    }
  }
}
```

Three things worth noticing. The handler is a plain `com.barezen.web.Handler`, the same shape a production route uses —
a fixture is never required. The client sends the body as UTF-8 bytes and does not add framing: the request-response
pair is what the JDK adapter negotiated. `jsonStringIs("created.name", "ada")` walks the JSON tree the server actually
returned; if the handler had echoed `"name"` at the top level instead of under `"created"`, that assertion fails with
both shapes quoted, not with a NullPointerException from an unfamiliar parser.

## The assertions TestKit ships

| entry point | shape | asserts |
| --- | --- | --- |
| `Assertions.assertThat(TestResponse)` | `.statusIs(int)`, `.headerIs(name, value)`, `.hasHeader(name)`, `.bodyIs(text)`, `.bodyContains(frag)`, `.jsonHas(path)`, `.jsonStringIs(path, value)`, `.jsonNumberIs(path, text)`, `.jsonBooleanIs(path, bool)`, `.jsonArraySizeIs(path, n)` | the wire answer a client would see |
| `Assertions.assertThat(int)` | `.isEqualTo(int)` | a handler's `count()`, so a refused request proving the delegate never ran is a one-line check |
| `Assertions.assertThat(Optional<Principal>)` | `.hasName(name)`, `.isAnonymous()` | the identity the middleware delivered, so "no leakage across requests" is a question the fixture can answer |

A failed assertion throws an `AssertionError` whose message names both sides. It is an error, not an exception: JUnit
reports it as a test failure, not as a broken fixture.

## Verbs, headers and a body on a request

`client()` has direct methods for `get`, `head`, `options`, `delete`, `post`, `put`, `patch`. For anything else, or
when headers matter, the shape is the same:

```java
server.client().request(HttpMethod.PUT, "/users/42")
    .header("If-Match", "\"v3\"")
    .body("{\"name\":\"grace\"}")
    .send();
```

`followRedirects` is `NEVER` on the client `TestServer` builds, so a `302` arrives as a `302`. That is not a limitation
but the point: an integration test asserts the status a handler returned, and a client that quietly followed a redirect
would let a broken Location header pass.

## What "test failure" is worth reading

A failed `.statusIs` message looks like `expected status 200 but got 404 (TestResponse[status=404, bytes=18, headers=0])`.
If the whole shape looks wrong — a `TestRequestFailedException` naming the URI, not an assertion — the server is not
reachable: the try block closed too early, the URI was constructed wrong, or the port was someone else's. The exception
message distinguishes the two, because the debug paths are different.
