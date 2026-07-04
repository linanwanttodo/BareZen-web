package com.barezen.examples.openapi;

import com.barezen.http.jdk.JdkHttpServerAdapter;
import com.barezen.plugins.openapi.ApiDocument;
import com.barezen.plugins.openapi.ApiResponse;
import com.barezen.plugins.openapi.Operation;
import com.barezen.plugins.openapi.Parameter;
import com.barezen.plugins.openapi.Schema;
import com.barezen.plugins.openapi.Security;
import com.barezen.plugins.openapi.RequestBody;
import com.barezen.web.HttpMethod;
import com.barezen.web.Router;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/**
 * OpenAPI: an API description assembled from an {@link ApiDocument} and served as JSON from its own route.
 *
 * <p>The document is a plain data structure with one promise — render it and you get deterministic bytes. Here the
 * application declares an endpoint, describes its path parameter, its request body, and its responses, then asks the
 * document for {@link ApiDocument#handler()}: a regular {@code com.barezen.web.Handler} that answers {@code
 * /openapi.json}. That is the whole integration — there is no separate server, no scanning, no annotation pass. The
 * OpenAPI plugin in the plugins example collects the operation metadata; this one turns declarations into the served
 * document, so the two halves of the shipped surface meet without either module naming the other.
 *
 * <p>{@code ApiDocument.build()} refuses malformed documents up front (a path parameter with no matching pattern
 * segment, a body on a 204, a duplicate operation), so the example's document is validated before it can be served —
 * the generator never ships a spec a valid client would reject.
 */
public final class OpenApiExample {

  private OpenApiExample() {
  }

  static ApiDocument document() {
    Schema user = Schema.object(List.of(
        new Schema.Property("id", Schema.integer(), true),
        new Schema.Property("name", Schema.string(), true)));

    Operation getUser = Operation.builder(new com.barezen.plugins.openapi.OperationMetadata(
            HttpMethod.GET, "/users/{id}", "Fetch one user", List.of("users")))
        .parameter(Parameter.path("id", Schema.integer()))
        .security(Security.Requirement.of("bearer", List.of()))
        .response(ApiResponse.json("200", "the user", Schema.ref("User")))
        .response(ApiResponse.empty("404", "no such user"))
        .build();

    Operation createUser = Operation.builder(new com.barezen.plugins.openapi.OperationMetadata(
            HttpMethod.POST, "/users", "Create a user", List.of("users")))
        .requestBody(RequestBody.json(user, true))
        .security(Security.Requirement.of("bearer", List.of()))
        .response(ApiResponse.json("201", "created", Schema.ref("User")))
        .build();

    return ApiDocument.builder()
        .info("BareZen Users API", "1.0")
        // A reserved example domain (RFC 6761): this is the OpenAPI "servers" field — documentation content a client
        // reads — not a bind address, so it is a real-looking public URL rather than a loopback the app never uses.
        .server("https://api.example.com")
        .schema("User", user)
        .securityScheme(Security.Scheme.bearer("bearer"))
        .operations(List.of(getUser, createUser))
        .build();
  }

  static Router router() {
    return Router.builder()
        .route(HttpMethod.GET, "/openapi.json", document().handler())
        .build();
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    System.out.println("document -> " + document().toJson().length() + " bytes of JSON");

    JdkHttpServerAdapter server = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router());
    server.start();
    try {
      int port = server.port().orElseThrow();
      HttpResponse<String> response = HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/openapi.json")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      System.out.println("GET /openapi.json -> " + response.statusCode() + " "
          + response.headers().firstValue("Content-Type").orElse("?") + " " + response.body().length() + " bytes");
    } finally {
      server.stop();
    }
  }
}
