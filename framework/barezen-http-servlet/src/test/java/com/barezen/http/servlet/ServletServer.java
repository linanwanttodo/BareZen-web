package com.barezen.http.servlet;

import com.barezen.web.Router;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletContextEvent;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;

/**
 * An embedded Tomcat on an OS-assigned port, for tests that assert what a real container does rather than what this
 * module hopes it does.
 *
 * <p>Tomcat appears once in the build, at test scope, and it is the container rather than a mock of one: ADR-0022 settled
 * this project's discipline by reading the JDK server's bytes off a socket instead of trusting its documentation, and the
 * same discipline applies to a second server. Every framing claim in {@link ServletBridge}'s javadoc — that the container
 * suppresses a HEAD entity itself, that it derives the length from the bytes it was handed, that a lowercase {@code head}
 * token is an ordinary method as far as the parser is concerned — was measured through this class before it was written
 * down, and the measurements are the tests.
 *
 * <p>{@link #servlet} registers an <em>instance</em>, which is the programmatic embedding path; {@link #deployed}
 * registers the no-argument constructor with a {@link ServletContextListener} that sets
 * {@link BarezenServlet#ROUTER_ATTRIBUTE} first, which is the declarative path a {@code web.xml} or {@code @WebServlet}
 * takes. Both run, because the phase's claim is that an existing container can serve a route table.
 *
 * <p>Test scope and package private. The one static field below is a fixture handoff, not a pattern: production classes
 * in this module hold no state a request can reach, and {@code BarezenHttpServletArchitectureTest} checks that with a
 * rule that excludes exactly this directory.
 */
final class ServletServer implements AutoCloseable {

  private final Tomcat tomcat;
  private final int port;

  private ServletServer(Tomcat tomcat, int port) {
    this.tomcat = tomcat;
    this.port = port;
  }

  /** @return the port the connector bound — 0 is what was asked for, and this is the number that came back */
  int port() {
    return port;
  }

  /** The router handed to the servlet instance, the way an embedding application does it. */
  static ServletServer servlet(Router router) throws Exception {
    return start(router, true, Map.of());
  }

  /** The same instance path, with the container's init-parameters set on the wrapper. */
  static ServletServer servlet(Router router, Map<String, String> initParameters) throws Exception {
    return start(router, true, initParameters);
  }

  /** The container instantiates the servlet; the router arrives from a context attribute set at startup. */
  static ServletServer deployed(Router router) throws Exception {
    return start(router, false, Map.of());
  }

  /**
   * The same declarative deployment, mounted under a context path — what a {@code .war} at {@code /app} looks like to
   * the container, and the only way to test that a route is matched on the application's path rather than on the URI
   * the client typed.
   */
  static ServletServer deployedUnderContext(String contextPath, Router router) throws Exception {
    return startAt(contextPath, router, false);
  }

  /**
   * The declarative path with nothing published into the context: the deployment an application makes when it names the
   * servlet class and forgets to set the attribute.
   *
   * @return a server that started, which is the finding — this is only a failure at the point the servlet initialises
   */
  static ServletServer deployedWithoutARouter() throws Exception {
    return start(null, false, Map.of());
  }

  private static ServletServer start(Router router, boolean instantiate, Map<String, String> initParameters)
      throws Exception {
    return startAt("", router, instantiate, initParameters);
  }

  private static ServletServer startAt(String contextPath, Router router, boolean instantiate) throws Exception {
    return startAt(contextPath, router, instantiate, Map.of());
  }

  private static ServletServer startAt(String contextPath, Router router, boolean instantiate,
      Map<String, String> initParameters) throws Exception {
    File base = Files.createTempDirectory("barezen-servlet-test").toFile();
    Tomcat tomcat = new Tomcat();
    tomcat.setBaseDir(base.getAbsolutePath());
    tomcat.setPort(0);
    // A refused body's surplus is swallowed rather than left on the connection, so the 413 tests see the answer rather
    // than a container-side reset. The JDK adapter has its own drain rule (ADR-0031 §Decision 5); here it is configured.
    tomcat.getConnector().setProperty("maxSwallowSize", "-1");
    Context context = tomcat.addContext(contextPath, base.getAbsolutePath());
    if (context instanceof org.apache.catalina.core.StandardContext standard) {
      // Without this the container logs a failed load-on-startup servlet and starts anyway, so a deployment that never
      // published its router comes up healthy and answers nothing — the exact shape the servlet's own refusal exists
      // to prevent. ADR-0049 records that turning the refusal into a failed start is a container setting, not a
      // framework default, because the framework is not the one deciding whether a context is acceptable.
      standard.setFailCtxIfServletStartFails(true);
    }
    Wrapper wrapper = instantiate
        ? Tomcat.addServlet(context, "barezen", new BarezenServlet(router))
        : Tomcat.addServlet(context, "barezen", new BarezenServlet());
    if (!instantiate) {
      context.addApplicationListener(PublishRouter.class.getName());
      PublishRouter.publish(router);
    }
    initParameters.forEach(wrapper::addInitParameter);
    // Load-on-startup is what makes a misassembled deployment fail when it is started rather than when a client first
    // asks about it, and the phase's claim is the former: an application that forgot its route table does not boot.
    wrapper.setLoadOnStartup(1);
    context.addServletMappingDecoded("/", "barezen");
    tomcat.start();
    return new ServletServer(tomcat, tomcat.getConnector().getLocalPort());
  }

  @Override
  public void close() throws Exception {
    tomcat.stop();
    tomcat.destroy();
  }

  /**
   * The listener half of the declarative path: publishes the router into the {@link jakarta.servlet.ServletContext}
   * during {@code contextInitialized}, which runs before the servlet is initialised — the same order an application's
   * own startup listener relies on.
   */
  public static final class PublishRouter implements ServletContextListener {

    private static volatile Router pending;

    static void publish(Router router) {
      pending = router;
    }

    @Override
    public void contextInitialized(ServletContextEvent event) {
      Router router = pending;
      pending = null;
      event.getServletContext().setAttribute(BarezenServlet.ROUTER_ATTRIBUTE, router);
    }
  }
}
