package com.barezen.http.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Response;
import com.barezen.web.Router;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Both ways a container gets a route table, run through a real container: an instance handed to the embedding
 * application, and the class the deployment names with the table arriving from a context attribute.
 *
 * <p>The second is the path {@code web.xml} and {@code @WebServlet} take, and it is the one that decides this module's
 * shape — a container instantiates what it deploys, so the no-argument constructor is not a convenience, it is the only
 * thing that works there. What the test below also establishes is when a miswired deployment is noticed: with
 * load-on-startup it fails while the context is starting, which is the behaviour
 * {@link BarezenServlet#init(jakarta.servlet.ServletConfig)}'s refusal exists to make possible.
 */
class ServletDeploymentTest {

  private static Router router() {
    return Router.builder()
        .route(HttpMethod.GET, "/doc", (request, ignored) ->
            new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8"),
                "hello".getBytes(StandardCharsets.UTF_8)))
        .build();
  }

  @Test
  void aContainerInstantiatedServletServesTheContextAttributesRouter() throws Exception {
    try (ServletServer server = ServletServer.deployed(router())) {
      RawHttp.Response response = RawHttp.request(server.port(), "GET", "/doc", List.of(), null);

      assertThat(response.status()).isEqualTo(200);
      assertThat(response.bodyUtf8()).isEqualTo("hello");
      assertThat(response.firstOf("Content-Length")).isEqualTo("5");
    }
  }

  /**
   * The {@code .war} case: the deployment owns the context path and the application owns the route, so a router that
   * registered {@code /doc} is reached at {@code /app/doc} and a router that registered {@code /app/doc} is not.
   */
  @Test
  void aRouteIsMatchedInsideTheContextNotOnTheUriTheClientTyped() throws Exception {
    try (ServletServer server = ServletServer.deployedUnderContext("/app", router())) {
      RawHttp.Response response = RawHttp.request(server.port(), "GET", "/app/doc", List.of(), null);

      assertThat(response.status()).isEqualTo(200);
      assertThat(response.bodyUtf8()).isEqualTo("hello");
    }
  }

  @Test
  void aRouterThatNamedTheContextPathItselfMatchesNothing() throws Exception {
    Router withTheContextBakedIn = Router.builder()
        .route(HttpMethod.GET, "/app/doc", (request, ignored) ->
            new Response(200, Headers.of("Content-Type", "text/plain"), "wrong".getBytes(StandardCharsets.UTF_8)))
        .build();

    try (ServletServer server = ServletServer.deployedUnderContext("/app", withTheContextBakedIn)) {
      RawHttp.Response response = RawHttp.request(server.port(), "GET", "/app/doc", List.of(), null);

      // The contract's own 404 body, not the container's page: the request reached the router, which was handed
      // "/doc". An application that wrote its routes against the deployment path is told so by the route table
      // rather than by a server that quietly matched it (ADR-0049).
      assertThat(response.status()).isEqualTo(404);
      assertThat(response.bodyUtf8()).isEqualTo("Not Found");
    }
  }

  /**
   * What a miswired deployment actually does, measured rather than hoped.
   *
   * <p>The servlet refuses to initialise (asserted in {@link BarezenServletAssemblyTest}), but whether that refusal
   * aborts the start is the container's decision, not the framework's: on Tomcat 10.1.34 with
   * {@code failCtxIfServletStartFails} the context comes down and {@code start()} still returns, and every request is
   * then answered by the container rather than by the application. That is the acceptable failure — the whole
   * deployment is visibly down — and the alternative it rules out is a router quietly serving nothing while the server
   * looks healthy. ADR-0049 records the measurement instead of claiming a fail-fast the framework cannot grant.
   */
  @Test
  void aDeploymentThatNeverPublishedItsRouterServesNothingOfTheRouteTable() throws Exception {
    try (ServletServer server = ServletServer.deployedWithoutARouter()) {
      RawHttp.Response response = RawHttp.request(server.port(), "GET", "/doc", List.of(), null);

      assertThat(response.status()).isEqualTo(404);
      // The container's page, not the contract's answer: the route table never got the request at all.
      assertThat(response.body()).contains("Apache Tomcat");
      assertThat(response.bodyUtf8()).isNotEqualTo("Not Found");
    }
  }
}
