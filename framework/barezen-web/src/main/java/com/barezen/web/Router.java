package com.barezen.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A route table: it answers which {@link Handler} a {@link Request} is for, and what the pattern captured from its path.
 *
 * <p>One responsibility, stated as {@code Request -> Handler} (ADR-0019 §1). A router answers "which handler is this
 * request for" and does not answer "what should the client be told when the answer is nobody". {@link #match(Request)}
 * returns a value rather than a {@link Response}, and it never calls a handler: the component that calls one is the
 * server adapter, which is also the component that has to write bytes to a client. That is why the rules about paths
 * belong here and the rules about errors belong there (ADR-0019 §1, §10).
 *
 * <p>Matching semantics, in the order they are decided:
 *
 * <ol>
 *   <li><b>raw segments.</b> The path is split on {@code /} and each segment is compared as the bytes arrived. Nothing is
 *       decoded and nothing is normalised — no {@code URI.normalize()} — because Phase 2.7 fixed the contract as a raw
 *       one. A router that decoded first would route {@code /a%2Fb} as the two-segment {@code /a/b}, reaching a different
 *       resource than the one the client named (ADR-0019 §4, §5);
 *   <li><b>exact segment count.</b> A pattern matches only a path with the same number of segments, so {@code /users} and
 *       {@code /users/} are two routes with different meanings and neither quietly serves the other (ADR-0019 §6);
 *   <li><b>a literal segment matches itself exactly</b>, case-sensitively, percent-encoding included (ADR-0019 §4);
 *   <li><b>a parameter segment {@code {name}} matches any one non-empty segment</b> and captures it undecoded
 *       (ADR-0019 §6, §7);
 *   <li><b>the most literal pattern wins.</b> Where {@code /users/new} and {@code /users/{id}} both match
 *       {@code /users/new}, the first position at which one is literal and the other is a parameter decides, in favour of
 *       the literal one. That is the whole priority rule: no order field, no weight, no annotation. It is a total order
 *       over shapes, and it can be stated at all because two routes with the same method and the same shape were already
 *       refused while building (ADR-0019 §8, §9).
 * </ol>
 *
 * <p>Instances are immutable, which is what makes concurrent matching trivial: the table is written by
 * {@link Builder#route} and copied once by {@link Builder#build()}, after which nothing in this type can be reached by
 * any caller. There is no {@code unregister}, no {@code replace} and no {@code reload}, so a request thread cannot race a
 * registration — the race does not exist to lose, rather than being managed (ADR-0019 §13).
 *
 * <p>A router owns no thread, socket, executor or queue and is deliberately <em>not</em> a {@code LifecycleParticipant}:
 * there is nothing to start and nothing to stop, and a type made immutable by the object that builds it has no phase to
 * be in (ADR-0019 §14). Nor is it a container. It holds {@code method + shape -> handler} and nothing else — no
 * configuration, no event bus, no assembler, no context, no way to look up an object by type or by name. Handlers are
 * built by the application and handed in; the router never builds one, and {@code router.resolve(...)} does not exist
 * (ADR-0019 §14).
 *
 * <p>Routing reads the method and the path, and nothing else. Headers, query and body are not routing data: a router that
 * read {@code Accept} would be doing content negotiation, and one that read a version header would be doing version
 * routing — each a design with rules of its own, none with a consumer here (ADR-0019 §4).
 */
public final class Router {

  /**
   * One registered route: its method, the pattern as written, the pattern split into raw segments, the parameter names in
   * the order they appear, and the handler to hand back.
   *
   * <p>Private, and named {@code Registration} rather than {@code Route}: a route is a thing a table holds and no caller
   * talks about, so a public {@code Route} would be a noun the API never needed — the first entry in the list §19 refuses
   * (ADR-0019 §19).
   *
   * <p>Lists rather than arrays throughout: an array's {@code toString()} is a hash code and its {@code equals()} is a
   * reference comparison, so a record holding a path in arrays would silently stop comparing. That is the same reason
   * {@link Request} is a class rather than a record (ADR-0018 §2, ADR-0019 §11).
   */
  private record Registration(HttpMethod method, String pattern, List<String> segments, List<String> parameterNames,
                              Handler handler) {

    /**
     * Whether a pattern segment answers an arrived one: a parameter takes any non-empty segment, a literal takes only
     * itself.
     *
     * <p>An empty segment fails a parameter, so {@code /users//} is not "a user whose id is the empty string". Matching it
     * would hand a handler an identifier it cannot validate, when the honest answer is that the path has no resource
     * there — a doubled slash is a malformed path, not a name (ADR-0019 §6).
     */
    boolean segmentMatches(int index, String arrived) {
      return isParameterAt(index) ? !arrived.isEmpty() : segments.get(index).equals(arrived);
    }

    boolean isParameterAt(int index) {
      return isParameter(segments.get(index));
    }

    /** Whether this route would answer a request whose path has exactly these segments. */
    boolean overlaps(List<String> path) {
      if (segments.size() != path.size()) {
        return false;
      }
      for (int index = 0; index < path.size(); index++) {
        if (!segmentMatches(index, path.get(index))) {
          return false;
        }
      }
      return true;
    }

    /**
     * The pattern with every parameter segment replaced by {@code {}}, which is what two routes collide on.
     *
     * <p>{@code /users/{id}} and {@code /users/{userId}} have the same shape and both answer {@code GET /users/42}, so
     * ambiguity is a question of shape rather than of spelling. Comparing patterns as text would let the collision arrive
     * through a rename, which is how two plugins come to own one resource without either noticing (ADR-0019 §9).
     */
    String shape() {
      StringBuilder shape = new StringBuilder();
      for (int index = 0; index < segments.size(); index++) {
        // A separator per index rather than one per non-empty segment: "/" is one empty segment and "//" is two, and
        // skipping an empty first segment gave both the shape "", so registering "//" after "/" was refused as a
        // duplicate even though their segment counts differ. The shape has to count segments the way overlaps() does.
        if (index > 0) {
          shape.append('/');
        }
        shape.append(isParameter(segments.get(index)) ? "{}" : segments.get(index));
      }
      return shape.toString();
    }

    /**
     * Whether this route answers {@code path} more precisely than {@code other}.
     *
     * <p>A literal segment pins the resource down more than a parameter does, so the first position where the two
     * disagree decides, in favour of whichever is literal. Read as a lexicographic comparison of shapes with
     * {@code literal < parameter} it is a total order, which is what makes the winner independent of registration order —
     * and two routes with equal shapes never reach the comparison, because {@link Builder#route} refused the second one
     * (ADR-0019 §8).
     */
    boolean isMoreSpecificThan(Registration other) {
      for (int index = 0; index < segments.size(); index++) {
        if (isParameterAt(index) != other.isParameterAt(index)) {
          return !isParameterAt(index);
        }
      }
      return false;
    }

    /** What the client filled into this route's parameters, keyed by the names the pattern declared, in that order. */
    Map<String, String> captured(List<String> path) {
      if (parameterNames.isEmpty()) {
        return Collections.emptyMap();
      }
      Map<String, String> values = new LinkedHashMap<>();
      int next = 0;
      // Walked over the segments rather than zipping names against path indices: /a/{x}/b/{y} declares its values at
      // positions 1 and 3, and the zip version captured "a" as x. The test twoParametersAreCapturedAtTheirOwnPositions
      // is the one that found it.
      for (int index = 0; index < segments.size(); index++) {
        if (isParameterAt(index)) {
          values.put(parameterNames.get(next++), path.get(index));
        }
      }
      return Collections.unmodifiableMap(values);
    }

    private static boolean isParameter(String segment) {
      return segment.length() > 2 && segment.charAt(0) == '{' && segment.charAt(segment.length() - 1) == '}';
    }
  }

  private final List<Registration> registrations;

  private Router(List<Registration> registrations) {
    this.registrations = registrations;
  }

  /**
   * Starts a route table.
   *
   * <p>The only way to obtain a {@code Router}. The builder exists because the phase needs an immutable table: that is
   * what makes concurrent matching safe without a lock, what makes "the routes this application has" a question with one
   * answer, and what stops a request thread from racing a route that arrives later (ADR-0019 §12, §13). Build, freeze,
   * hand out is the shape {@code ServiceAssembly} and {@code LifecycleOrchestrator} already use.
   *
   * @return an empty builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Which handler a request is for, and what its path parameters captured.
   *
   * @param request the request to route, never null. Its path is already validated by {@link Request}, which is why
   *     nothing here checks it again: the shape of a request path is the contract's job, and {@link #methodsFor(String)}
   *     takes a bare string and therefore does check
   * @return the match, or empty when no route answers this method <em>and</em> this path — which is not the same statement
   *     as "no route answers this path", because {@link #methodsFor(String)} answers that one. The two answers together
   *     are how an adapter tells {@code 404} from {@code 405} without this type owning an error format, an error page or
   *     a status code of its own (ADR-0019 §10)
   * @throws NullPointerException if {@code request} is null
   */
  public Optional<RouteMatch> match(Request request) {
    Objects.requireNonNull(request,
        "the request to route; with no request there is no method and no path, which is all a match needs");
    if (request.path().equals("*")) {
      // An OPTIONS * request asks about the server rather than a resource, and no pattern can name it. Empty is the
      // honest answer; what to reply instead is the adapter's decision, not a routing one. On the shipped adapter this
      // branch is a contract statement rather than a request path: the JDK refuses the target before a handler exists,
      // so no socket reaches it here — ADR-0026 §3, §4 records that, and a second adapter that does deliver the
      // asterisk gets this answer, which is the reason the branch stays.
      return Optional.empty();
    }
    List<String> path = segmentsOf(request.path());
    Registration best = null;
    for (Registration candidate : registrations) {
      if (candidate.method().equals(request.method()) && candidate.overlaps(path)
          && (best == null || candidate.isMoreSpecificThan(best))) {
        best = candidate;
      }
    }
    return best == null ? Optional.empty() : Optional.of(new RouteMatch(best.handler(), best.captured(path)));
  }

  /**
   * Which methods have a route that would answer a request for this path.
   *
   * <p>The second half of the {@code 404} / {@code 405} decision: with {@code GET /users} registered and
   * {@code POST /users} requested, {@link #match(Request)} is empty while this returns {@code [GET]}, so the adapter can
   * answer {@code 405} and name the allowed methods; a request for {@code /unknown} matches nothing and gives an empty
   * set, which is {@code 404}. The alternative was one richer result carrying both facts, which would have been a third
   * public type and a three-state outcome for a caller that is really asking two questions (ADR-0019 §10).
   *
   * @param path a raw request path, in the form {@link Request#path()} is in, validated here for the same reason
   * @return the methods with a matching route, in the order they were registered; empty when no route answers this path
   * @throws NullPointerException if {@code path} is null
   * @throws IllegalArgumentException if {@code path} does not begin with {@code /}, the rule {@link Request} applies to
   *     the field this argument is meant to come from
   */
  public Set<HttpMethod> methodsFor(String path) {
    Objects.requireNonNull(path,
        "the path to ask about; \"does anything live here\" is a question that needs a here");
    if (path.equals("*")) {
      return Set.of();
    }
    if (!path.startsWith("/")) {
      throw new IllegalArgumentException("the path must start with \"/\"; got \"" + path + "\". This is the argument a"
          + " caller takes from Request.path(), which enforces the same rule, so refusing it here keeps a hand-built"
          + " string from silently matching nothing (ADR-0018 §2)");
    }
    List<String> segments = segmentsOf(path);
    Set<HttpMethod> methods = new LinkedHashSet<>();
    for (Registration candidate : registrations) {
      if (candidate.overlaps(segments)) {
        methods.add(candidate.method());
      }
    }
    return Collections.unmodifiableSet(methods);
  }

  /**
   * The registered routes as {@code METHOD pattern}, in registration order — never a handler reference.
   *
   * <p>{@code Router[GET /users, POST /users, GET /users/{id}]}. A route table is exactly what an operator wants in a
   * startup log line, and a handler's {@code toString()} is whatever the application wrote: a class name, an identity
   * hash, or something that repeats request data. The redaction rule is the one {@link Request#toString()},
   * {@link Response#toString()} and {@link Headers#toString()} already follow (ADR-0019 §21).
   *
   * @return a summary naming every route and nothing else
   */
  @Override
  public String toString() {
    StringBuilder summary = new StringBuilder("Router[");
    for (Registration registration : registrations) {
      if (summary.length() > "Router[".length()) {
        summary.append(", ");
      }
      summary.append(registration.method().text()).append(' ').append(registration.pattern());
    }
    return summary.append(']').toString();
  }

  /**
   * Split a raw path or pattern into segments, keeping empty ones.
   *
   * <p>{@code "/users/"} gives {@code [users, ""]} and {@code "/users"} gives {@code [users]}. That is what makes a
   * trailing slash a real difference rather than a normalisation question: the two paths have different segment counts,
   * so they are different routes and no redirect is implied by the difference (ADR-0019 §6).
   */
  private static List<String> segmentsOf(String path) {
    return List.of(path.substring(1).split("/", -1));
  }

  /**
   * The route table under construction: explicit registration, then {@link #build()}.
   *
   * <p>{@code route} is the whole registration surface. {@code get(path, handler)} plus {@code post(...)} plus
   * {@code put(...)} would be nine ways to say the same thing, each with its own documentation to keep in agreement, and
   * it would name the HTTP methods twice — once as {@link HttpMethod} values and once as method names. Refusing a second
   * equivalent entry point is the same call {@code ServiceAssembly.Builder} made when it kept {@code instance} and
   * {@code factory} and added nothing else (ADR-0019 §12).
   *
   * <p>Like every other type in this package, a builder holds only final fields, and what freezes the router is
   * {@link #build()} copying the list rather than any flag recording that it was called. So a builder can be used again
   * after building and cannot reach a router that was already handed out: the immutability is in the table, not in a
   * rule about when it may be built (ADR-0019 §13, and ADR-0014's reason for refusing a second build, which is that a
   * factory would run twice — a router has no factories).
   */
  public static final class Builder {

    private final List<Registration> registrations = new ArrayList<>();

    private Builder() {}

    /**
     * Registers one handler for one method and one path pattern.
     *
     * @param method the request method this route answers. {@code GET /users} and {@code POST /users} are two routes and
     *     both may be registered: route identity is the pair, not the path (ADR-0019 §3)
     * @param pattern the raw path pattern, beginning with {@code /}. A literal segment is matched exactly as written, and
     *     a segment that is exactly {@code {name}} captures one non-empty raw segment. No wildcard, no regex, no optional
     *     segment, no repeated segment, no prefix match, no trailing-slash flexibility — each needs a semantic nobody has
     *     a consumer for, and each is refused by name by the architecture rule
     *     {@code no_preemptive_routing_or_middleware_types} (ADR-0019 §19)
     * @param handler what to hand back for a matching request. The router stores it and never invokes it
     * @return this builder, so an application can write one registration per line
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code pattern} is not a path, if a parameter name is not an identifier, if one
     *     name is used twice in one pattern, or if a route with the same method and the same shape is already registered.
     *     A duplicate is refused rather than resolved: replacing loses a handler silently, and "last one wins" would make
     *     an application depend on the order its plugins happened to run (ADR-0019 §9)
     */
    public Builder route(HttpMethod method, String pattern, Handler handler) {
      Objects.requireNonNull(method,
          "the route method; a route with no method is not a route, it is a guess about what the client meant");
      Objects.requireNonNull(handler,
          "the route handler for \"" + pattern + "\"; a route to nothing would match a request and have nothing to hand"
              + " back, which is a bug in the application rather than a condition a client can act on");
      Registration registration = parsed(method, pattern, handler);
      refuseAmbiguous(registration);
      registrations.add(registration);
      return this;
    }

    /**
     * Copies the table into an immutable router.
     *
     * <p>{@code List.copyOf} rather than an unmodifiable view: the builder's list keeps growing if the application keeps
     * using it, and a view would mean the router an adapter holds changes under it (ADR-0019 §13, and the defensive-copy
     * rule {@code Request} and {@code Headers} follow).
     *
     * @return a router with exactly these routes, in this order
     */
    public Router build() {
      return new Router(List.copyOf(registrations));
    }

    private void refuseAmbiguous(Registration registration) {
      for (Registration existing : registrations) {
        if (existing.method().equals(registration.method()) && existing.shape().equals(registration.shape())) {
          throw new IllegalArgumentException(registration.method().text() + " " + registration.pattern()
              + " is already registered as " + existing.pattern() + ": both have the shape " + existing.shape()
              + ", so both would answer the same request and the winner would be whichever was registered first."
              + " Replacing a route loses a handler silently, and deciding by order makes an application's"
              + " behaviour depend on which plugin happened to register first, which is not a question a router"
              + " should be answering at request time (ADR-0019 §9)");
        }
      }
    }
  }

  /**
   * Split and validate a pattern, refusing a bad one here rather than at match time.
   *
   * <p>Every check runs at registration because a malformed pattern is a bug in the application: the moment to tell a
   * developer is the line they wrote during assembly, not the first request that happens to reach it — the same argument
   * that makes {@code ServiceAssembly.build()} create everything eagerly instead of on a first lookup (ADR-0014,
   * ADR-0019 §7).
   */
  private static Registration parsed(HttpMethod method, String pattern, Handler handler) {
    Objects.requireNonNull(pattern,
        "the route pattern; a route with no pattern matches nothing, which is a route nobody can find");
    if (pattern.equals("*")) {
      throw new IllegalArgumentException("the route pattern must be a path, not \"*\"; an OPTIONS * request names the"
          + " server rather than a resource, so there is nothing here for a handler to be registered against");
    }
    if (!pattern.startsWith("/")) {
      throw new IllegalArgumentException("the route pattern must start with \"/\"; got \"" + pattern
          + "\". A pattern without a leading slash is relative, and what it is relative to is the adapter's business"
          + " — the same rule Request.path() applies to a request (ADR-0018 §2)");
    }
    if (pattern.indexOf('?') >= 0) {
      throw new IllegalArgumentException("the route pattern must not contain \"?\"; got \"" + pattern
          + "\". Routing is decided on the path, and a pattern carrying a query would match a resource and a filter as"
          + " though they were one thing (ADR-0019 §4)");
    }
    List<String> segments = segmentsOf(pattern);
    List<String> names = new ArrayList<>();
    for (String segment : segments) {
      if (Registration.isParameter(segment)) {
        names.add(checkedParameterName(segment, pattern, names));
      } else if (segment.indexOf('{') >= 0 || segment.indexOf('}') >= 0) {
        throw new IllegalArgumentException("the route pattern \"" + pattern + "\" has the segment \"" + segment
            + "\", in which braces are not a whole parameter. A parameter is a segment that is exactly \"{name}\":"
            + " allow a prefix or a suffix and matching has to say how much of the segment the parameter captures,"
            + " which is a template language rather than a route (ADR-0019 §7)");
      }
    }
    return new Registration(method, pattern, segments, List.copyOf(names), handler);
  }

  private static String checkedParameterName(String segment, String pattern, List<String> names) {
    String name = segment.substring(1, segment.length() - 1);
    if (!isIdentifier(name)) {
      throw new IllegalArgumentException("\"{" + name + "}\" in the route pattern \"" + pattern
          + "\" is not a parameter name. A name is a letter followed by letters, digits or underscores, because it"
          + " becomes a key an application reads: \"{}\", \"{2fa}\" and \"{user-name}\" would each have to be quoted"
          + " before use, which is a sign the name is not the application's to choose (ADR-0019 §7)");
    }
    if (names.contains(name)) {
      throw new IllegalArgumentException("the route pattern \"" + pattern + "\" names the parameter \"" + name
          + "\" twice. Path parameters come back as one map keyed by name, so reusing a name would either lose the"
          + " first value or need a list under a key that says nothing about holding one (ADR-0019 §7)");
    }
    return name;
  }

  private static boolean isIdentifier(String name) {
    if (name.isEmpty() || !Character.isLetter(name.charAt(0))) {
      return false;
    }
    for (int index = 1; index < name.length(); index++) {
      char character = name.charAt(index);
      if (!Character.isLetterOrDigit(character) && character != '_') {
        return false;
      }
    }
    return true;
  }
}
