package com.barezen.testkit;

import com.barezen.security.Authentication;
import com.barezen.security.Principal;
import com.barezen.web.Handler;
import com.barezen.web.Headers;
import com.barezen.web.Request;
import com.barezen.web.Response;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ready-made handlers and authentications for a test to wire into a router, so the interesting part of an integration
 * test is the assertion and not the fixture.
 *
 * <p>Every fixture here is built only from the public API of the modules it touches. None of them reach into framework
 * internals, and none of them change them — a fixture that could alter production behaviour would make the test
 * worthless, because it would no longer be testing the thing that ships.
 *
 * <p>The counting handler is the one a security or middleware test needs most: the question "was the delegate reached?"
 * is the question behind every 401 and 403, and {@link CountingHandler#count()} answers it directly. The counters are
 * per-instance, so a test that wants to know how often a route ran holds its own {@code CountingHandler}; two tests
 * never share one unless they deliberately pass the same instance around.
 */
public final class TestFixtures {

  private TestFixtures() {}

  /**
   * A handler that answers {@code 200} with a fixed text body, encoded as UTF-8.
   *
   * @param body the text to answer with
   * @return a handler that always answers {@code 200} with that body and {@code text/plain}
   */
  public static Handler textHandler(String body) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    return (request, parameters) -> new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8"), bytes);
  }

  /**
   * A handler that answers a fixed status with no body.
   *
   * @param status the status to answer with
   * @return a handler that always answers that status
   */
  public static Handler statusHandler(int status) {
    return (request, parameters) -> new Response(status, Headers.empty(), new byte[0]);
  }

  /**
   * A handler that echoes the method and path it received.
   *
   * <p>Useful for asserting routing reached a specific pattern with specific parameters, without writing a bespoke
   * handler per test.
   *
   * @return a handler answering {@code 200} with {@code "METHOD /path"}
   */
  public static Handler echoHandler() {
    return (request, parameters) -> new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8"),
        (request.method().text() + " " + request.path()).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * A handler that answers JSON, as a convenience for a test asserting on a JSON body.
   *
   * @param json the JSON text to answer with
   * @return a handler answering {@code 200} with {@code application/json}
   */
  public static Handler jsonHandler(String json) {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    return (request, parameters) -> new Response(200, Headers.of("Content-Type", "application/json"), bytes);
  }

  /**
   * A handler that wraps a delegate and records how many times it ran.
   *
   * <p>The count is the assertion a middleware test needs: a refused request must not reach the delegate, so the
   * delegate's count staying at zero is the proof. The counter is atomic because the adapter may invoke a handler
   * concurrently, and a lost update would make the test flaky in exactly the situation it is checking.
   */
  public static final class CountingHandler implements Handler {

    private final Handler delegate;
    private final AtomicInteger count = new AtomicInteger();

    CountingHandler(Handler delegate) {
      this.delegate = delegate;
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters) {
      count.incrementAndGet();
      return delegate.handle(request, parameters);
    }

    /** @return the number of times this handler has been invoked */
    public int count() {
      return count.get();
    }

    /** Reset the count to zero, for a test that reuses one handler across several requests. */
    public void reset() {
      count.set(0);
    }
  }

  /**
   * A handler that counts invocations while always answering {@code 200} with a fixed body.
   *
   * @param body the text to answer with
   * @return a counting handler
   */
  public static CountingHandler countingHandler(String body) {
    return new CountingHandler(textHandler(body));
  }

  /**
   * A handler that records the principal the security middleware handed it, for a test that must prove which identity
   * reached the delegate.
   *
   * <p>Like every secured handler in this framework it implements both {@link Handler} and
   * {@link com.barezen.security.SecuredHandler}: the middleware detects the secured half and calls it with the request's
   * {@link com.barezen.security.SecurityContext}, so the principal it records is exactly the one the middleware
   * established for that request. It is the object a leak would corrupt, which makes it the object to ask whether
   * anything leaked — {@link #seen()} reports the most recent identity, and {@link #seenNames()} the whole sequence, so
   * "request A was alice, request B was anonymous, and they did not blend" is a question with a direct answer.
   *
   * <p>The plain {@link Handler#handle} half is a tripwire: reaching it means the middleware ran this handler without a
   * context, which is a middleware bug the test wants to fail on loudly, not silently tolerate.
   */
  public static final class PrincipalCapturingHandler
      implements Handler, com.barezen.security.SecuredHandler {

    private final AtomicInteger count = new AtomicInteger();
    private volatile Optional<Principal> lastSeen = Optional.empty();
    private final List<String> seenNames = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override
    public Response handle(Request request, java.util.Map<String, String> parameters) {
      throw new AssertionError("a SecuredHandler reached through SecurityMiddleware must be invoked with a "
          + "SecurityContext, not through the plain Handler half");
    }

    @Override
    public Response handle(
        Request request, java.util.Map<String, String> parameters, com.barezen.security.SecurityContext context) {
      count.incrementAndGet();
      Principal principal = context.principal().orElse(null);
      lastSeen = Optional.ofNullable(principal);
      seenNames.add(principal == null ? "anonymous" : principal.name());
      byte[] body = ("hello, " + (principal == null ? "anonymous" : principal.name()))
          .getBytes(StandardCharsets.UTF_8);
      return new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8"), body);
    }

    /** @return the principal the most recent invocation saw, empty when it saw none */
    public Optional<Principal> seen() {
      return lastSeen;
    }

    /** @return every principal name seen, in order, for a test asserting no cross-request leak */
    public List<String> seenNames() {
      return List.copyOf(seenNames);
    }

    /** @return the number of secured invocations */
    public int count() {
      return count.get();
    }
  }

  /**
   * A handler that records the identity the middleware gives it, answering {@code 200} with a greeting to that identity.
   *
   * @return a principal-capturing secured handler
   */
  public static PrincipalCapturingHandler principalCapturingHandler() {
    return new PrincipalCapturingHandler();
  }

  /**
   * An {@link Authentication} that accepts a fixed token and maps it to a principal, for tests of the security
   * middleware.
   *
   * @param token the bearer value to accept
   * @param principal the principal a matching credential produces
   * @return an authentication accepting that one token
   */
  public static Authentication authenticationFor(String token, Principal principal) {
    return Authentication.bearer("Bearer", token, principal);
  }

  /**
   * An {@link Authentication} that trusts nothing.
   *
   * @return an always-anonymous authentication
   */
  public static Authentication anonymousAuthentication() {
    return Authentication.anonymous();
  }
}
