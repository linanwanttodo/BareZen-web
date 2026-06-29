package com.barezen.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What {@link Router#match(Request)} found: the handler registered for this request, and the path parameters the client
 * filled in.
 *
 * <p>Two things and no more, because two things is what routing actually produces. For a route with no parameters the
 * first is the whole answer, which is why a bare {@code Handler} was this type's candidate; the moment
 * {@code /users/{id}} exists the match has also decided that {@code 42} is {@code id}, and a caller cannot recover that
 * from either the handler or the request, because it never sees the pattern. A third member — the matched pattern, a
 * route name, the handler's class — would be metadata with no consumer yet, so it is absent (ADR-0019 §11, §19).
 *
 * <p>{@link #parameters()} are raw and undecoded, exactly as they arrived on the wire, for the reason Phase 2.7 gave for
 * {@link Request#path()}: {@code /users/a%2Fb} matched by {@code /users/{id}} captured one segment holding an encoded
 * slash, and decoding it here would turn a one-segment path into a two-segment value behind the application's back.
 * Whether {@code id} is an integer, a slug or a percent-encoded identifier is the handler's decision, and the handler is
 * the only component that can make it (ADR-0019 §5, §7).
 *
 * <p>Read the map rather than asking for one name: there is no {@code parameter("id")} accessor, because with the map in
 * hand a handler sees what the route actually captured instead of trusting a name it typed from memory, and a typo that
 * returns {@code null} is a worse trade than a typo that does not compile — which is also why the names a pattern
 * declared are the only keys present (ADR-0019 §7).
 *
 * <p>Immutable, and a record for that reason rather than for convenience: the map is copied on the way in and frozen, so
 * a handler cannot add a parameter that whatever runs next reads, and the two components are the whole state, so
 * {@code equals} compares an answer instead of a reference. A body-less value with no arrays is exactly where a record is
 * the honest choice, and where the {@code [B@…} problem of {@link Request} does not arise (ADR-0019 §11).
 *
 * @param handler the registered handler to call, never null. Calling it is not this type's business: the component that
 *     matches is the one that has to write bytes to a client, and it decides what to do with a handler it did not build
 * @param parameters the captured path parameters, keyed by the names the pattern declared, in pattern order, unmodifiable.
 *     Empty for a route with no parameters — an empty map and "no such concept" are the same answer here, unlike
 *     {@link Request#query()}, where absent and empty are different requests
 */
public record RouteMatch(Handler handler, Map<String, String> parameters) {

  /**
   * Validates and copies, because a public record constructor stays public and a match is read by whoever runs after the
   * router.
   *
   * <p>{@link Router} is the only type that builds one today; a factory method instead would add a member with nothing to
   * check that the arguments were already checked.
   *
   * @throws NullPointerException if {@code handler} or {@code parameters} is null
   */
  public RouteMatch {
    Objects.requireNonNull(handler,
        "the matched handler; a match with nothing to call is a routing failure dressed up as a result");
    Objects.requireNonNull(parameters,
        "the matched parameters; a route without parameters still has a map of them, and an empty one is the answer"
            + " — null here would make every handler test before reading one");
    // Copied, then frozen. LinkedHashMap because the order worth keeping is the order the pattern declared.
    parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
  }

  /**
   * The parameter names this match captured, or {@code no parameters}.
   *
   * <p>Never a parameter value, never the handler. A {@code RouteMatch} is what a log line about routing ends in, and the
   * two rules {@link Request#toString()} and {@link Headers#toString()} already follow apply: what the client sent stays
   * out, and an object the application handed to {@link Router#route} is a live collaborator rather than a value. The
   * handler's class name is absent for the same reason — a lambda's reads {@code $$Lambda$147/0x…}, which names nothing,
   * and printing it would put a runtime type query inside a contract that has none (ADR-0019 §21).
   *
   * @return a summary such as {@code RouteMatch[1 parameter: id]}
   */
  @Override
  public String toString() {
    int count = parameters.size();
    return "RouteMatch[" + count + " parameter" + (count == 1 ? "" : "s")
        + (count == 0 ? "" : ": " + String.join(", ", parameters.keySet())) + "]";
  }
}
