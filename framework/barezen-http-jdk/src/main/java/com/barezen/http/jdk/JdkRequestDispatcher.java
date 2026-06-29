package com.barezen.http.jdk;

import com.barezen.web.Handler;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.barezen.web.RouteMatch;
import com.barezen.web.Router;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What happens to one request: route it, call the handler, write the answer, and decide the status when there is no
 * handler to call. This is the component ADR-0019 §10 pointed at — the one that turns a routing answer into HTTP.
 *
 * <p>Package private, and reached only from {@link JdkHttpServerAdapter#start()}. It is not an extension point: an
 * application that wants to change what a {@code 404} looks like wraps a {@link Handler}, not this class, and an
 * application that wants a different error policy is the reason a second adapter can exist (ADR-0023 §8).
 *
 * <p><b>The five answers, and who decides each:</b>
 *
 * <ul>
 *   <li><b>200..599 from a handler</b> — written as returned;
 *   <li><b>404</b> — {@link Router#match(Request)} is empty <em>and</em> {@link Router#methodsFor(String)} is empty:
 *       nothing lives at this path. The body is the words {@code Not Found} and names no path, because a 404 body that
 *       echoes the target teaches a probe which paths the server distinguishes (ADR-0023 §2);
 *   <li><b>405</b> — the match is empty but the methods are not: something lives here, and this method is not the way
 *       to ask for it. {@code Allow} lists them in the order declared below rather than in the order a hash table
 *       produced (ADR-0023 §3);
 *   <li><b>500</b> — the handler threw. The body is {@code Internal Server Error} and nothing else: no message, no
 *       exception class, no stack trace, no internal path, no request header. The exception goes to the log, where a
 *       developer reads it, and not to the socket, where a client would (ADR-0023 §4);
 *   <li><b>400</b> — the exchange reached this handler but no {@link Request} could be built from it: the target is one
 *       the contract refuses, or the body never arrived. Both are the client's failure, and answering either with
 *       {@code 500} would blame the server for a request it never parsed (ADR-0023 §5, §6).
 * </ul>
 *
 * <p><b>Commitment, which is why the catch blocks below are asymmetric.</b> Once a status line has been written the
 * response is committed and no other status can follow, so "before the first byte" and "after the first byte" are
 * treated as different failures rather than caught as one exception type. A failure while the response was being written
 * is logged and abandoned — the client hung up, or the socket broke; there is nobody left to tell. A blanket
 * {@code catch (Exception) → 500} would try to send a second status on a committed exchange, where the JDK answers
 * {@code IOException: headers already sent}, and the original failure would be lost behind a second exception nobody
 * asked for (ADR-0023 §6).
 *
 * <p><b>What this class does not do:</b> it does not parse HTTP — the JDK owns low-level parsing and refuses a malformed
 * request line, an illegal header and an unknown framing before an exchange is created (ADR-0023 §7); it does not answer
 * {@code OPTIONS *} as a server-wide capability list, which ADR-0023 §9 records as this phase's design debt and
 * {@link #route} explains; and it does not log one line per request, which is access logging and has no consumer yet.
 * Nor does it catch {@link Error}: an {@code OutOfMemoryError} inside a handler is not a condition a {@code 500} answers.
 *
 * <p>One instance serves every request concurrently and holds nothing but the route table, so there is no per-request
 * state to leak and no lock on the request path.
 */
final class JdkRequestDispatcher implements HttpHandler {

  private static final Logger log = LoggerFactory.getLogger(JdkRequestDispatcher.class);

  /**
   * The order {@code Allow} is written in, and the only stable method order this module defines.
   *
   * <p>{@code HttpMethod} has no public {@code values()}: its nine constants are the whole set and the array holding
   * them is private. So the phase's suggestion to order by {@code HttpMethod.values()} cannot be followed as written,
   * and widening a frozen contract type to allow it would be a change to what the contract exposes, made for one header.
   * This list is the order HTTP names the methods in, which is stable by construction rather than by registration, so
   * two identical 405s never differ. A method this framework does not name follows, sorted by text, so an application
   * that registered {@code PURGE} still sees it in {@code Allow} (ADR-0023 §3).
   */
  private static final List<HttpMethod> ALLOW_ORDER = List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST,
      HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.CONNECT, HttpMethod.OPTIONS, HttpMethod.TRACE, HttpMethod.PATCH);

  private final Router router;

  JdkRequestDispatcher(Router router) {
    this.router = Objects.requireNonNull(router, "the route table to dispatch from; a dispatcher with no routes has"
        + " nothing to report but a 404");
  }

  /**
   * Answer one exchange, and release it.
   *
   * <p>{@code exchange.close()} is in a {@code finally} on every path, including the failures: an exchange left open is
   * a connection the JDK keeps waiting on, and the phase's promise is that one request costs one exchange and no more.
   *
   * @param exchange the exchange the JDK created for this request, never null
   */
  @Override
  public void handle(HttpExchange exchange) {
    try {
      Request request = read(exchange);
      if (request == null) {
        return;
      }
      route(exchange, request);
    } finally {
      exchange.close();
    }
  }

  /**
   * The request, or {@code null} after its failure has been answered.
   *
   * <p>Two failures are possible and both are the client's, which is why neither becomes a 500. {@link Request}'s own
   * validation refuses a target that is not a path — reachable on a real socket as a bare {@code %2F}, which the JDK
   * hands over as a raw path with no leading slash, probed and recorded in ADR-0022 §2 — and a body that stops arriving
   * throws {@code IOException} out of the read. Both happen before anything was written, so a status is still available
   * to send.
   */
  private Request read(HttpExchange exchange) {
    try {
      return JdkExchangeBridge.toRequest(exchange);
    } catch (IllegalArgumentException unusableTarget) {
      log.debug("refusing a request target the contract cannot name: {}", unusableTarget.getMessage());
      respond(exchange, true, JdkExchangeBridge.badRequest());
      return null;
    } catch (IOException bodyNeverArrived) {
      log.debug("the request body did not arrive: {}", bodyNeverArrived.getMessage());
      respond(exchange, true, JdkExchangeBridge.badRequest());
      return null;
    }
  }

  /**
   * Route, dispatch, write.
   *
   * <p>Every status this method can produce is one of the five in the class documentation, and each is written exactly
   * once: {@link JdkExchangeBridge#send} is the commitment point, so a failure at or after it is logged rather than
   * answered.
   *
   * <p>An {@code OPTIONS *} request — the request line that names the server rather than a resource — reaches this
   * method with {@code path()} of {@code "*"}, which {@link Request} allows and {@link Router#match(Request)} answers
   * with an empty match, so it is answered {@code 404} here. That is the honest reading of a route table that has no
   * server-wide capability in it, and the alternative was inventing one for a request no client in this phase sends.
   * ADR-0023 §9 records it as design debt; the {@code JDK} server does not deliver {@code OPTIONS *} to a handler at
   * all, so no socket test can reach this branch.
   */
  private void route(HttpExchange exchange, Request request) {
    Optional<RouteMatch> found = router.match(request);
    if (found.isEmpty()) {
      // The second question is what separates "nothing here" from "not this way", and the route table already answers
      // both — which is why the adapter asks it twice rather than carrying a richer match type (ADR-0019 §10).
      Set<HttpMethod> allowed = router.methodsFor(request.path());
      respond(exchange, writesBody(request), allowed.isEmpty()
          ? JdkExchangeBridge.notFound()
          : JdkExchangeBridge.methodNotAllowed(allowValue(allowed)));
      return;
    }
    RouteMatch match = found.get();
    Response response;
    try {
      response = match.handler().handle(request, match.parameters());
    } catch (RuntimeException handlerFailure) {
      // Recorded with the throwable, because a stack trace is the part a developer needs and the part a socket must not
      // carry. The client gets the status text and nothing else (ADR-0023 §4).
      log.error("handler failed for {} {}", request.method().text(), request.path(), handlerFailure);
      response = JdkExchangeBridge.internalServerError();
    }
    respond(exchange, writesBody(request), response);
  }

  /**
   * {@code Allow} as one field line: the methods the route table named, in the declared order, comma-separated.
   *
   * <p>Ordered here and nowhere else. {@link Router#methodsFor(String)} returns registration order, which is a fact
   * about how an application was assembled rather than a fact about HTTP, and ADR-0019 keeps it that way: a later
   * adapter may sort differently without asking the router to change.
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

  /**
   * Whether a body goes on the wire for this request.
   *
   * <p>{@code HEAD} is the only method with an answer here: HTTP says the response carries the fields a GET would have
   * produced and no body, and {@link Response} deliberately has no {@code noBody()} to say so — the decision belongs to
   * the adapter, which is the component that holds the method (ADR-0018 §3, ADR-0022 §7).
   */
  private static boolean writesBody(Request request) {
    return !request.method().equals(HttpMethod.HEAD);
  }

  /**
   * Write, and stop trying once the response is committed.
   *
   * <p>The {@code IOException} caught here is a response-side failure — a client that hung up mid-write, a broken pipe.
   * Nothing retries or answers again, because a second status on a committed exchange is refused by the JDK
   * ({@code headers already sent}) and would replace the one failure worth reporting with a second one nobody asked for
   * (ADR-0023 §6). Logged at {@code debug} rather than {@code error}: a client going away is not a server fault, and an
   * abandoned request that is visible only in the log is the honest trace of what happened.
   */
  private void respond(HttpExchange exchange, boolean writeBody, Response response) {
    try {
      JdkExchangeBridge.send(exchange, writeBody, response);
    } catch (IOException responseAbandoned) {
      log.debug("the response did not reach the client: {}", responseAbandoned.getMessage());
    }
  }
}
