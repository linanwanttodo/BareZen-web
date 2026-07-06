package com.barezen.http.servlet;

import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.barezen.web.RouteMatch;
import com.barezen.web.Router;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A BareZen route table running inside somebody else's server.
 *
 * <p>This is the integration that makes an existing deployment a BareZen deployment: one servlet mapped into a Tomcat,
 * Jetty or Wildfly context, serving the same {@link Router} an application would hand to
 * {@code com.barezen.http.jdk.JdkHttpServerAdapter}. It owns no port, no thread pool, no executor and no lifecycle —
 * the container owns all four, which is precisely why this class is <em>not</em> an
 * {@link com.barezen.web.HttpServerAdapter}. ADR-0024 decided an adapter is one instance that starts, stops, reports its
 * bound port and cannot restart; a servlet that was instantiated, initialised and destroyed by a container it does not
 * control cannot honestly answer {@code port()} or {@code isRunning()}, and pretending otherwise would widen the
 * contract with a lie rather than describe a second shape (ADR-0049).
 *
 * <p><b>Two ways to hand over the route table, because a container instantiates what it deploys.</b>
 *
 * <ul>
 *   <li>{@code new BarezenServlet(router)} for programmatic embedding — the caller builds the table and the container
 *       builds the servlet around the instance it was given;
 *   <li>the no-argument constructor plus a {@link ServletContext} attribute named {@link #ROUTER_ATTRIBUTE}, for the
 *       declarative case where {@code web.xml} or {@code @WebServlet} names a class and the container calls its empty
 *       constructor. The application's own {@code ServletContextListener} sets the attribute at startup, so the router
 *       still arrives from application code and the {@link Router.Builder} is long frozen before it does (ADR-0019 §13).
 * </ul>
 *
 * That attribute is not a service locator, and the difference is checkable: the container owns the bag, this class reads
 * exactly one key of exactly one type, once, at {@code init()} — there is no {@code getAttributeNames()} walk, no lookup
 * by request, no {@code require(Class)}, nothing stored that a later caller can ask for. ADR-0006 refuses a component
 * that goes shopping for collaborators during a request; a component that receives one at assembly time is ADR-0014's
 * explicit registration wearing the container's own clothes.
 *
 * <p><b>The six answers, and who decides each.</b> Identical to the JDK adapter's, deliberately — switching server must
 * not change what an application's client reads (ADR-0023): a handler's status written as returned; 404 when the path
 * matches nothing and no method lives there, with a body that names no path; 405 with {@code Allow} when the path lives
 * but the method does not; 500 when the handler threw, with the exception going to the log and not to the socket; 400
 * when the target or the body never became a {@link Request}; 413 when the content is over
 * {@link #MAX_REQUEST_BODY_BYTES_PARAM}. The container still owns what it always owned — the status line's reason phrase
 * and the framing fields — and {@link Response} refuses to let an application state a length, so the two never fight.
 *
 * <p><b>Configuration is resolved once, in {@code init()}.</b> The limit is read from the container's init-parameter and
 * stored as a {@code long}, never re-read per request, and a value that does not parse fails the deployment naming the
 * parameter and what was found — ADR-0013's rule, and ADR-0031's refusal of the reading that silently falls back so
 * "unconfigured" and "unbounded" cannot become the same word. An absent parameter is the 1 MiB default, the same bound
 * the JDK adapter enforces, so a client cannot send more content by choosing a different server.
 *
 * <p>One instance serves every request concurrently and holds nothing but the route table and one number, so there is
 * no per-request state to leak. The servlet specification publishes {@code init()} before the first {@code service()}
 * call, which is the happens-before that makes those two fields safe to read without a lock.
 *
 * <p>What this class does not do: it does not parse HTTP (the container refuses a malformed request line before a
 * request object exists); it does not answer {@code OPTIONS *} (there is no target here — the container resolved one
 * before routing); it does not log one line per request (access logging is the container's, and it is better at it); and
 * it does not catch {@link Error} — an {@code OutOfMemoryError} inside a handler is not a condition a 500 answers.
 */
public class BarezenServlet extends HttpServlet {

  private static final long serialVersionUID = 1L;

  private static final Logger log = LoggerFactory.getLogger(BarezenServlet.class);

  /**
   * The {@link jakarta.servlet.ServletContext} attribute an application puts its route table under when the container
   * instantiates this servlet declaratively. The value must be a {@link Router}.
   */
  public static final String ROUTER_ATTRIBUTE = "com.barezen.http.servlet.router";

  /**
   * The servlet init-parameter that sets the request-body limit in bytes. Absent means
   * {@link ServletBridge#DEFAULT_MAX_REQUEST_BODY_BYTES}; present and unparsable means the deployment fails.
   */
  public static final String MAX_REQUEST_BODY_BYTES_PARAM = "barezen.maxRequestBodyBytes";

  /**
   * The order {@code Allow} is written in, duplicated from {@code JdkRequestDispatcher} on purpose.
   *
   * <p>Both adapters need it and neither can own it: it is routing knowledge, so {@code Router} would have to expose it,
   * and {@code barezen-web} cannot host a helper type without amending its frozen contracted-type list (ADR-0018 §16,
   * ADR-0047). A third adapter makes this the second duplicate and the moment to decide where the order lives; two is
   * the point where copying is cheaper than inventing a home for it. ADR-0049 records the debt rather than hiding it.
   */
  private static final List<HttpMethod> ALLOW_ORDER = List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST,
      HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.CONNECT, HttpMethod.OPTIONS, HttpMethod.TRACE, HttpMethod.PATCH);

  /** The table this servlet routes with; set by the constructor, or by {@code init()} from the context attribute. */
  private Router router;

  /** The bound on one request body, resolved once in {@link #init(ServletConfig)}. */
  private long maxRequestBodyBytes = ServletBridge.DEFAULT_MAX_REQUEST_BODY_BYTES;

  /**
   * For declarative deployment, where the container instantiates the class named in {@code web.xml} or
   * {@code @WebServlet}. The route table arrives at {@code init()} from {@link #ROUTER_ATTRIBUTE}.
   */
  public BarezenServlet() {
  }

  /**
   * For programmatic embedding: the application builds the table and hands over the finished servlet instance.
   *
   * @param router the frozen route table to serve, never null
   */
  public BarezenServlet(Router router) {
    this.router = Objects.requireNonNull(router,
        "the route table to serve; a servlet with none could answer every request with a 404 and look healthy doing it");
  }

  /**
   * Resolve the route table and the body limit, once, before any request is served.
   *
   * @throws ServletException if no route table is available, or the limit parameter is present and not a number
   */
  @Override
  public void init(ServletConfig config) throws ServletException {
    super.init(config);
    if (router == null) {
      jakarta.servlet.ServletContext context = config.getServletContext();
      Object declared = context == null ? null : context.getAttribute(ROUTER_ATTRIBUTE);
      if (declared != null && !(declared instanceof Router)) {
        throw new ServletException("the ServletContext attribute \"" + ROUTER_ATTRIBUTE + "\" holds a "
            + declared.getClass().getName() + "; it must hold a com.barezen.web.Router");
      }
      router = (Router) declared;
    }
    if (router == null) {
      throw new ServletException("no route table for this servlet: construct it with new BarezenServlet(router), or"
          + " set the ServletContext attribute \"" + ROUTER_ATTRIBUTE + "\" to the Router before this servlet"
          + " initialises. A servlet that starts without one answers every request with a 404, which is a broken"
          + " deployment that looks like an empty application (ADR-0049).");
    }
    String limit = config.getInitParameter(MAX_REQUEST_BODY_BYTES_PARAM);
    if (limit != null && !limit.isBlank()) {
      long parsed;
      try {
        parsed = Long.parseLong(limit.trim());
      } catch (NumberFormatException notANumber) {
        throw new ServletException("\"" + MAX_REQUEST_BODY_BYTES_PARAM + "\" is \"" + limit + "\" in this servlet's"
            + " configuration, which is not a byte count; a limit that silently fell back to the default would leave"
            + " the deployment believing it was enforcing something it is not (ADR-0013, ADR-0031)", notANumber);
      }
      if (parsed < 0) {
        throw new ServletException("\"" + MAX_REQUEST_BODY_BYTES_PARAM + "\" is " + parsed + "; 0 is a policy — no"
            + " request body is accepted at all — and a negative number refuses every request including a bodiless"
            + " one, which is a misassembly rather than a limit (ADR-0031)");
      }
      maxRequestBodyBytes = parsed;
    }
  }

  /**
   * Answer one request: route it, call the handler, write the answer, and decide the status when there is no handler to
   * call.
   *
   * <p>Overrides the dispatcher entirely rather than implementing {@code doGet}/{@code doPost}, because {@code HttpServlet}'s
   * own {@code service} answers unlisted verbs with 405 <em>before</em> a router is asked, and a framework whose routing
   * answer depends on which verb was sent would be two routing tables disagreeing (ADR-0019 §10).
   */
  @Override
  protected void service(HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException {
    Request request;
    try {
      request = ServletBridge.toRequest(httpRequest, maxRequestBodyBytes);
    } catch (ServletBridge.Exceeded contentTooLarge) {
      // Logged at debug: a limit is a policy, and meeting it is the client's doing. No path is named.
      log.debug("refusing a request body over the limit: {}", contentTooLarge.getMessage());
      write(httpResponse, ServletBridge.contentTooLarge());
      return;
    } catch (IllegalArgumentException unusableTarget) {
      log.debug("refusing a request target the contract cannot name: {}", unusableTarget.getMessage());
      write(httpResponse, ServletBridge.badRequest());
      return;
    } catch (IOException bodyNeverArrived) {
      log.debug("the request body did not arrive: {}", bodyNeverArrived.getMessage());
      write(httpResponse, ServletBridge.badRequest());
      return;
    }
    route(httpRequest, httpResponse, request);
  }

  /**
   * Route, dispatch, write. Every status this produces is one of the six in the class documentation, and each is written
   * once: {@link #write} is the commitment point, so a failure at or after it is logged rather than answered twice
   * (ADR-0023 §6).
   */
  private void route(HttpServletRequest httpRequest, HttpServletResponse httpResponse, Request request)
      throws IOException {
    Optional<RouteMatch> found = router.match(request);
    if (found.isEmpty()) {
      Set<HttpMethod> allowed = router.methodsFor(request.path());
      write(httpResponse, allowed.isEmpty()
          ? ServletBridge.notFound()
          : ServletBridge.methodNotAllowed(allowValue(allowed)));
      return;
    }
    RouteMatch match = found.get();
    Response response;
    try {
      response = match.handler().handle(request, match.parameters());
    } catch (RuntimeException handlerFailure) {
      // Recorded with the throwable, because a stack trace is the part a developer needs and the part a socket must not
      // carry. The container's own error page is refused: it would print the exception the client has no business
      // reading, which is why the catch is here rather than left to a declared <error-page> (ADR-0023 §4).
      log.error("handler failed for {} {}", request.method().text(), request.path(), handlerFailure);
      response = ServletBridge.internalServerError();
    }
    write(httpResponse, response);
  }

  /**
   * Write, and stop trying once the response is committed.
   *
   * <p>{@link HttpServletRequest#getMethod()} is not needed to decide suppression: the container suppresses a HEAD body
   * itself and derives the length from these same bytes, so there is no adapter-side flag that could disagree with it
   * (ADR-0028's asymmetry was a JDK-server artefact, measured in ADR-0049's socket tests for this container too).
   */
  private void write(HttpServletResponse response, Response application) throws IOException {
    if (response.isCommitted()) {
      log.debug("the response was already committed before {} could be written", application.status());
      return;
    }
    try {
      ServletBridge.send(response, application);
    } catch (IOException responseAbandoned) {
      // A client that hung up mid-write. Nothing retries or answers again: a second status on a committed response is
      // refused by the container, and would replace the one failure worth reporting with a second nobody asked for.
      log.debug("the response did not reach the client: {}", responseAbandoned.getMessage());
    }
  }

  /**
   * {@code Allow} as one field value: the methods the route table named, in the declared order, comma-separated — the
   * same list and the same ordering rule the JDK adapter writes, because a client that switched servers between two
   * requests must not see the same 405 spelled differently.
   */
  private static String allowValue(Set<HttpMethod> allowed) {
    List<HttpMethod> ordered = new ArrayList<>(allowed.size());
    for (HttpMethod known : ALLOW_ORDER) {
      if (allowed.contains(known)) {
        ordered.add(known);
      }
    }
    allowed.stream()
        .filter(unlisted -> !ordered.contains(unlisted))
        .sorted((left, right) -> left.text().compareTo(right.text()))
        .forEach(ordered::add);
    StringBuilder value = new StringBuilder();
    for (HttpMethod method : ordered) {
      if (value.length() > 0) {
        value.append(", ");
      }
      value.append(method.text());
    }
    return value.toString();
  }
}
