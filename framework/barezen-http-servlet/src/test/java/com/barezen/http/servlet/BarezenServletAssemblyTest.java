package com.barezen.http.servlet;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Response;
import com.barezen.web.Router;
import java.lang.reflect.Proxy;
import java.util.Map;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a servlet refuses at startup, before it can serve anything.
 *
 * <p>No container here: {@link ServletConfig} and {@link ServletContext} are interfaces, so the stub is a proxy over a
 * map rather than a hundred empty methods, and the thing under test is {@code init()} — the one moment this module
 * resolves configuration, which is where ADR-0013 says a bad value must fail loudly instead of falling back quietly.
 * The same paths run against a real container in {@link ServletDeploymentTest}.
 */
class BarezenServletAssemblyTest {

  private static final Router ROUTER = Router.builder()
      .route(HttpMethod.GET, "/health", (request, ignored) ->
          new Response(200, Headers.of("Content-Type", "text/plain"), "ok".getBytes()))
      .build();

  /**
   * A {@code ServletConfig} over two maps: init-parameters and the context attributes its servlet context exposes.
   *
   * <p>{@code getServletContext} is answered rather than left null because the declarative deployment path is the one
   * being tested, and a config without a context is a container that never starts a servlet — the case
   * {@link BarezenServlet#init} also refuses, and refuses below.
   */
  private static ServletConfig config(Map<String, String> parameters, Map<String, Object> attributes) {
    ServletContext context = (ServletContext) Proxy.newProxyInstance(
        BarezenServletAssemblyTest.class.getClassLoader(), new Class<?>[] {ServletContext.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getAttribute" -> attributes.get((String) args[0]);
          case "getMajorVersion", "getMinorVersion" -> 6;
          default -> null;
        });
    return (ServletConfig) Proxy.newProxyInstance(
        BarezenServletAssemblyTest.class.getClassLoader(), new Class<?>[] {ServletConfig.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getServletName" -> "barezen";
          case "getInitParameter" -> parameters.get((String) args[0]);
          case "getServletContext" -> context;
          default -> null;
        });
  }

  @Nested
  @DisplayName("the route table")
  class RouteTable {

    @Test
    void anInstanceBuiltWithARouterStartsWithoutAnAttribute() {
      BarezenServlet servlet = new BarezenServlet(ROUTER);

      assertThatCode(() -> servlet.init(config(Map.of(), Map.of()))).doesNotThrowAnyException();
    }

    @Test
    void aRouterConstructorRefusesNullRatherThanServingA404Forever() {
      assertThatThrownBy(() -> new BarezenServlet(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theContextAttributeIsTheDeclarativeWayIn() {
      BarezenServlet servlet = new BarezenServlet();

      assertThatCode(() -> servlet.init(config(Map.of(),
          Map.of(BarezenServlet.ROUTER_ATTRIBUTE, ROUTER)))).doesNotThrowAnyException();
    }

    @Test
    void aServletThatStartsWithNoRouteTableFailsTheDeploymentNamingBothRemedies() {
      BarezenServlet servlet = new BarezenServlet();

      // The alternative is a healthy-looking application that 404s everything it is asked about.
      assertThatThrownBy(() -> servlet.init(config(Map.of(), Map.of())))
          .isInstanceOf(ServletException.class)
          .hasMessageContaining(BarezenServlet.ROUTER_ATTRIBUTE)
          .hasMessageContaining("new BarezenServlet(router)");
    }

    @Test
    void anAttributeOfTheWrongTypeIsRefusedWithTheClassNameItFound() {
      BarezenServlet servlet = new BarezenServlet();

      assertThatThrownBy(() -> servlet.init(config(Map.of(),
          Map.of(BarezenServlet.ROUTER_ATTRIBUTE, "not a router"))))
          .isInstanceOf(ServletException.class)
          .hasMessageContaining("java.lang.String");
    }
  }

  @Nested
  @DisplayName("the request-body limit")
  class Limit {

    @Test
    void anAbsentParameterTakesTheSecureDefault() {
      BarezenServlet servlet = new BarezenServlet(ROUTER);

      // Unconfigured is not unbounded (ADR-0031), and the default is the JDK adapter's own bound, so a client cannot
      // send more by choosing a different server.
      assertThatCode(() -> servlet.init(config(Map.of(), Map.of()))).doesNotThrowAnyException();
    }

    @Test
    void aLimitThatIsNotANumberFailsTheDeployment() {
      BarezenServlet servlet = new BarezenServlet(ROUTER);

      assertThatThrownBy(() -> servlet.init(config(
          Map.of(BarezenServlet.MAX_REQUEST_BODY_BYTES_PARAM, "1MB"), Map.of())))
          .isInstanceOf(ServletException.class)
          .hasMessageContaining(BarezenServlet.MAX_REQUEST_BODY_BYTES_PARAM)
          .hasMessageContaining("1MB")
          .hasCauseInstanceOf(NumberFormatException.class);
    }

    @Test
    void aBlankLimitIsConfiguredButNotANumberAndFailsTheDeployment() {
      for (String value : new String[] {"", "   "}) {
        BarezenServlet servlet = new BarezenServlet(ROUTER);

        assertThatThrownBy(() -> servlet.init(config(
            Map.of(BarezenServlet.MAX_REQUEST_BODY_BYTES_PARAM, value), Map.of())))
            .isInstanceOf(ServletException.class)
            .hasMessageContaining(BarezenServlet.MAX_REQUEST_BODY_BYTES_PARAM)
            .hasMessageContaining("not a byte count")
            .hasCauseInstanceOf(NumberFormatException.class);
      }
    }

    @Test
    void aNegativeLimitIsAMisassemblyNotAPolicy() {
      BarezenServlet servlet = new BarezenServlet(ROUTER);

      assertThatThrownBy(() -> servlet.init(config(
          Map.of(BarezenServlet.MAX_REQUEST_BODY_BYTES_PARAM, "-1"), Map.of())))
          .isInstanceOf(ServletException.class)
          .hasMessageContaining("negative");
    }

    @Test
    void zeroIsAcceptedBecauseItRefusesEveryBody() {
      BarezenServlet servlet = new BarezenServlet(ROUTER);

      assertThatCode(() -> servlet.init(config(
          Map.of(BarezenServlet.MAX_REQUEST_BODY_BYTES_PARAM, "0"), Map.of()))).doesNotThrowAnyException();
    }

    @Test
    void whitespaceAroundAValueIsTheSameValue() {
      BarezenServlet servlet = new BarezenServlet(ROUTER);

      assertThatCode(() -> servlet.init(config(
          Map.of(BarezenServlet.MAX_REQUEST_BODY_BYTES_PARAM, " 4096 "), Map.of()))).doesNotThrowAnyException();
    }
  }
}
