# Testing an OpenAPI document

Phase 2.15 asked for a "testing OpenAPI" doc. The honest scope is narrow, and it is worth stating why before the code:
TestKit may not name the plugin layer (ADR-0044), and the OpenAPI generator lives *inside* the plugins module (ADR-0043).
So TestKit ships no OpenAPI type and builds no document. What it does give you is the machinery to test a document your
application already assembles: a real server to serve it, and JSON assertions to check the bytes.

An `ApiDocument` is produced by the generator in `com.barezen.plugins.openapi`; that artifact is a *test-scope*
dependency of your test, exactly as it is a runtime dependency of your application. The document exposes a
`com.barezen.web.Handler` (`handler()`), so serving it is one route. Testing it is therefore the same shape as any other
integration test in this guide.

## Serve the document and assert on it

```java
import com.barezen.plugins.openapi.ApiDocument;   // the consumer's own test-scope plugins dependency
import com.barezen.testkit.Assertions;
import com.barezen.testkit.TestServer;
import com.barezen.web.HttpMethod;
import com.barezen.web.Router;
import org.junit.jupiter.api.Test;

class OpenApiServedTest {

  @Test
  void the_document_is_served_as_json_and_carries_the_declared_operations() {
    ApiDocument document = // ... assembled by the application from its routes, as ADR-0043 describes ...
        ApiDocument.builder()
            .info("BareZen", "1.0.0")
            .server("http://localhost:8080")
            .operation(/* an Operation built from the application's collected metadata */)
            .build();
    Router router = Router.builder()
        .route(HttpMethod.GET, "/openapi.json", document.handler())
        .build();
    try (TestServer server = TestServer.start(router)) {
      var response = server.client().get("/openapi.json");
      Assertions.assertThat(response)
          .statusIs(200)
          .headerIs("Content-Type", "application/json")
          .jsonStringIs("info.title", "BareZen")
          .jsonHas("paths");
    }
  }
}
```

The `ApiDocument.handler()` renders its JSON once, at assembly, and answers the same bytes to every request (ADR-0043).
An assertion over the served body is therefore an assertion over `document.toJson()` — you are checking the real wire
output, not a re-render.

## Asserting the parts that matter

A generated document is a claim about the API, so a test usually wants three things true at once:

- the document parses and the metadata is what the application declared — `jsonStringIs("info.title", ...)`,
  `jsonStringIs("info.version", ...)`, `jsonHas("openapi")`;
- the operations the application registered appear with the right shape — the OpenAPI path template is itself a key
  (`"paths./users/{id}"`), and a dotted path cannot reach a key containing a dot, so reach those with `bodyContains`
  or split the assertion where the real segments are;
- the security requirements are present, because a missing one is a client generator taught to call an endpoint
  unauthenticated — `jsonHas("components.securitySchemes.bearerAuth")`, `jsonArraySizeIs("paths./health.get.tags", 1)`.

```java
Assertions.assertThat(response).bodyContains("\"/users/{id}\"");
Assertions.assertThat(response).jsonArraySizeIs("tags", 2);
```

## Why the determinism check is a testkit test and not a testkit feature

The generator's byte-equivalence promise is made and proven in the plugins module (ADR-0043): two assemblies of the same
declarations render identical bytes. TestKit cannot hold that assertion for you without naming the plugin type it is
asserting on, which the isolation rule forbids. What TestKit *can* do is give a consumer the cheap form of the same
check over the wire — assemble twice, serve both, assert the two bodies are identical:

```java
String first  = server.client().get("/openapi.json").bodyText();
String second = serverAgnosticRebuild().client().get("/openapi.json").bodyText();
Assertions.assertThat(server.client().get("/openapi.json")).bodyIs(first);
```

That is an integration-level restatement of the determinism claim using only served bytes and `bodyIs`. The structural
proof — that nothing in the emitter depends on map iteration order — lives where the emitter lives, in the plugins
module's own tests, because that is the only place it can be made without crossing the fence.

## What belongs in the plugins module instead

Anything that needs to *build* a document from Java types, walk the OpenAPI grammar, or refuse an invalid schema
reference is generator work, and the generator is where those tests already are (`OpenApiDocumentTest`, `OpenApiModelTest`
— 2.14 shipped them inside `barezen-plugins`, ADR-0043). TestKit's role stops at the socket: serve it like any handler,
assert on it like any JSON body. If a document test keeps wanting more and more OpenAPI-specific helpers, the honest
reading is that the assertion belongs beside the generator, not in a test tool that is not allowed to name it.
