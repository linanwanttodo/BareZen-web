package com.barezen.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a route table matches, what it refuses at registration, and what it cannot be changed into afterwards.
 *
 * <p>Everything here goes through {@link Router#match(Request)} and {@link Router#methodsFor(String)} rather than
 * through the shape of a route, because a route is not a public type: the only way to observe a table is to ask it about
 * a request, which is what an adapter does. If a route ever has to be inspected directly, that is the moment a
 * {@code Route} type would need a consumer — see ADR-0019 §19.
 */
class RouterTest {

  /** One named handler per role, so a match is checked by identity rather than by behaviour. */
  private static final Handler LIST_USERS = (request, ignored) -> new Response(200, Headers.empty(), null);
  private static final Handler CREATE_USER = (request, ignored) -> new Response(201, Headers.empty(), null);
  private static final Handler READ_USER = (request, ignored) -> new Response(200, Headers.empty(), null);
  private static final Handler NEW_USER_FORM = (request, ignored) -> new Response(200, Headers.empty(), null);
  private static final Handler TRAILING_SLASH_HANDLER = (request, ignored) -> new Response(200, Headers.empty(), null);

  private static Request request(HttpMethod method, String path) {
    return new Request(method, path, null, Headers.empty(), null);
  }

  private static Optional<RouteMatch> match(Router router, HttpMethod method, String path) {
    return router.match(request(method, path));
  }

  /** The MVP shape the phase asked for: a collection, its creation, and one item by id. */
  private static Router usersRouter() {
    return Router.builder()
        .route(HttpMethod.GET, "/users", LIST_USERS)
        .route(HttpMethod.POST, "/users", CREATE_USER)
        .route(HttpMethod.GET, "/users/{id}", READ_USER)
        .build();
  }

  @Nested
  @DisplayName("the surface")
  class Surface {

    @Test
    void arouterIsBuiltAndThenOnlyAsked() {
      assertThat(Arrays.stream(Router.class.getDeclaredMethods())
          .filter(method -> Modifier.isPublic(method.getModifiers()))
          .map(Method::getName)
          .distinct())
          .containsExactlyInAnyOrder("builder", "match", "methodsFor", "toString");
    }

    @Test
    void abuilderHasOneRegistrationMethodOneContributionMethodAndOneBuildMethod() {
      // Not get/post/put/delete: the same nine statements in nine spellings, naming the HTTP methods twice
      // (ADR-0019 §12). `contributing` is that registration run over a list somebody else wrote, not a third way to
      // register (ADR-0047).
      assertThat(Arrays.stream(Router.Builder.class.getDeclaredMethods())
          .filter(method -> Modifier.isPublic(method.getModifiers()))
          .map(Method::getName)
          .distinct())
          .containsExactlyInAnyOrder("route", "contributing", "build");
    }

    @Test
    void theOnlyWayToObtainARouterIsABuilder() {
      assertThat(Router.class.getDeclaredConstructors()).hasSize(1);
      assertThat(Modifier.isPrivate(Router.class.getDeclaredConstructors()[0].getModifiers())).isTrue();
      assertThat(Modifier.isPrivate(Router.Builder.class.getDeclaredConstructors()[0].getModifiers())).isTrue();
    }

    @Test
    void thereIsNoPublicRouteType() {
      // Router$Builder is the register phase; a public Route would be a noun no caller needs (ADR-0019 §19). The
      // private Registration record is declared too, which is the point: a route is data the table holds, not a type.
      assertThat(Arrays.stream(Router.class.getDeclaredClasses())
          .filter(declared -> Modifier.isPublic(declared.getModifiers())).map(Class::getName))
          .containsExactly(Router.Builder.class.getName());
    }

    @Test
    void registrationsChain() {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/a", LIST_USERS)
          .route(HttpMethod.GET, "/b", LIST_USERS)
          .build();

      assertThat(match(router, HttpMethod.GET, "/a")).isPresent();
      assertThat(match(router, HttpMethod.GET, "/b")).isPresent();
    }

    @Test
    void anEmptyRouterAnswersNothing() {
      Router empty = Router.builder().build();

      assertThat(match(empty, HttpMethod.GET, "/users")).isEmpty();
      assertThat(empty.methodsFor("/users")).isEmpty();
      assertThat(empty).hasToString("Router[]");
    }

    @Test
    void amatchHandsBackAHandlerTheCallerCanCall() {
      // The adapter's two lines, written here so the hand-off is proven from this side too: matching hands back a
      // handler and never calls it (ADR-0019 §1).
      RouteMatch found = match(usersRouter(), HttpMethod.GET, "/users").orElseThrow();

      assertThat(found.handler().handle(request(HttpMethod.GET, "/users"), found.parameters()).status())
          .isEqualTo(200);
    }

    @Test
    void theRouterNeverCallsTheHandlerItRegistered() {
      Handler failing = (request, ignored) -> {
        throw new IllegalStateException("a router must not call a handler");
      };
      Router router = Router.builder().route(HttpMethod.GET, "/users", failing).build();

      assertThatCode(() -> match(router, HttpMethod.GET, "/users")).doesNotThrowAnyException();
      assertThatCode(() -> router.methodsFor("/users")).doesNotThrowAnyException();
      assertThatCode(router::toString).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("static routes")
  class StaticRoutes {

    @Test
    void aStaticPathMatchesItself() {
      assertThat(match(usersRouter(), HttpMethod.GET, "/users"))
          .get()
          .extracting(RouteMatch::handler)
          .isSameAs(LIST_USERS);
    }

    @Test
    void aDeeperStaticPathMatchesItselfAndNotItsNeighbours() {
      Router router = Router.builder().route(HttpMethod.GET, "/reports/monthly", LIST_USERS).build();

      assertThat(match(router, HttpMethod.GET, "/reports/monthly")).isPresent();
      assertThat(match(router, HttpMethod.GET, "/reports")).isEmpty();
      assertThat(match(router, HttpMethod.GET, "/reports/monthly/extra")).isEmpty();
      assertThat(match(router, HttpMethod.GET, "/reports/weekly")).isEmpty();
    }

    @Test
    void aLiteralSegmentIsCaseSensitive() {
      Router router = Router.builder().route(HttpMethod.GET, "/Users", LIST_USERS).build();

      // A path is case-sensitive in HTTP, so /users and /Users are different resources rather than two spellings of
      // one (ADR-0019 §4).
      assertThat(match(router, HttpMethod.GET, "/users")).isEmpty();
      assertThat(match(router, HttpMethod.GET, "/Users")).isPresent();
    }

    @Test
    void theRootPathIsARouteLikeAnyOther() {
      Router router = Router.builder().route(HttpMethod.GET, "/", LIST_USERS).build();

      assertThat(match(router, HttpMethod.GET, "/")).isPresent();
      assertThat(match(router, HttpMethod.GET, "/x")).isEmpty();
    }

    @Test
    void aRequestForTheAsteriskTargetMatchesNoRoute() {
      Router router = usersRouter();
      Request optionsStar = new Request(HttpMethod.OPTIONS, "*", null, Headers.empty(), null);

      // OPTIONS * asks about the server rather than a resource, and no pattern names it. Empty is honest; what to
      // answer is the adapter's decision (ADR-0019 §10).
      assertThat(router.match(optionsStar)).isEmpty();
      assertThat(router.methodsFor("*")).isEmpty();
    }
  }

  @Nested
  @DisplayName("method separation")
  class MethodSeparation {

    @Test
    void theSamePathUnderTwoMethodsIsTwoRoutes() {
      // Route identity is the pair, so a collection resource can be read and written by two handlers that never see
      // each other (ADR-0019 §3).
      Router router = usersRouter();

      assertThat(match(router, HttpMethod.GET, "/users")).get()
          .extracting(RouteMatch::handler).isSameAs(LIST_USERS);
      assertThat(match(router, HttpMethod.POST, "/users")).get()
          .extracting(RouteMatch::handler).isSameAs(CREATE_USER);
    }

    @Test
    void aMethodWithNoRouteAtThatPathMatchesNothing() {
      assertThat(match(usersRouter(), HttpMethod.DELETE, "/users")).isEmpty();
    }

    @Test
    void aStandardMethodParsedInAnyCaseIsTheSameRouteKey() {
      // HttpMethod.of("get") IS HttpMethod.GET, so routing keys on the method value rather than on the text a client
      // sent — and the duplicate rule fires on the two spellings too (ADR-0018 §4, ADR-0019 §3).
      assertThatThrownBy(() -> Router.builder()
          .route(HttpMethod.GET, "/users", LIST_USERS)
          .route(HttpMethod.of("get"), "/users", CREATE_USER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already registered");

      assertThat(match(usersRouter(), HttpMethod.of("GeT"), "/users")).isPresent();
    }

    @Test
    void twoNonStandardSpellingsAreTwoMethodsAndTwoRoutes() {
      Router router = Router.builder()
          .route(HttpMethod.of("PURGE"), "/cache", LIST_USERS)
          .route(HttpMethod.of("purge"), "/cache", CREATE_USER)
          .build();

      // A method name is case-sensitive, which Phase 2.7 decided and the router inherits rather than relitigating.
      assertThat(match(router, HttpMethod.of("PURGE"), "/cache")).get()
          .extracting(RouteMatch::handler).isSameAs(LIST_USERS);
      assertThat(match(router, HttpMethod.of("purge"), "/cache")).get()
          .extracting(RouteMatch::handler).isSameAs(CREATE_USER);
    }

    @Test
    void everyMethodValueRoutesTheSameWayAsAnyOther() {
      Router router = Router.builder()
          .route(HttpMethod.PUT, "/users/{id}", READ_USER)
          .route(HttpMethod.PATCH, "/users/{id}", CREATE_USER)
          .route(HttpMethod.DELETE, "/users/{id}", LIST_USERS)
          .build();

      assertThat(match(router, HttpMethod.PUT, "/users/7")).get().extracting(RouteMatch::handler).isSameAs(READ_USER);
      assertThat(match(router, HttpMethod.PATCH, "/users/7")).get().extracting(RouteMatch::handler).isSameAs(CREATE_USER);
      assertThat(match(router, HttpMethod.DELETE, "/users/7")).get().extracting(RouteMatch::handler).isSameAs(LIST_USERS);
      assertThat(router.methodsFor("/users/7"))
          .containsExactly(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
    }
  }

  @Nested
  @DisplayName("duplicate routes")
  class Duplicates {

    @Test
    void theSameRouteTwiceFails() {
      assertThatThrownBy(() -> Router.builder()
          .route(HttpMethod.GET, "/users", LIST_USERS)
          .route(HttpMethod.GET, "/users", CREATE_USER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already registered")
          .hasMessageContaining("GET /users");
    }

    @Test
    void theSameShapeWithDifferentParameterNamesFails() {
      // /users/{id} and /users/{userId} both answer GET /users/42, so a rename is a collision rather than a second
      // route (ADR-0019 §9).
      assertThatThrownBy(() -> Router.builder()
          .route(HttpMethod.GET, "/users/{id}", LIST_USERS)
          .route(HttpMethod.GET, "/users/{userId}", CREATE_USER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("shape users/{}");
    }

    @Test
    void twoParametersWithDifferentNamesAtTheSamePositionsCollide() {
      assertThatThrownBy(() -> Router.builder()
          .route(HttpMethod.GET, "/{a}/{b}", READ_USER)
          .route(HttpMethod.GET, "/{c}/{d}", NEW_USER_FORM))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aduplicateIsRefusedRatherThanDecidedByOrder() {
      Router.Builder builder = Router.builder().route(HttpMethod.GET, "/users", LIST_USERS);

      // The two behaviours a framework reaches for here are replacing the first handler and letting the last one win.
      // Both make an application's routes depend on which plugin registered first (ADR-0019 §9).
      assertThatThrownBy(() -> builder.route(HttpMethod.GET, "/users", CREATE_USER))
          .isInstanceOf(IllegalArgumentException.class);

      // The refused registration left the first route in place and the builder usable.
      assertThat(builder.build().match(request(HttpMethod.GET, "/users"))).get()
          .extracting(RouteMatch::handler).isSameAs(LIST_USERS);
    }

    @Test
    void aDuplicateUnderAnotherMethodIsNotADuplicate() {
      assertThatCode(() -> Router.builder()
          .route(HttpMethod.GET, "/users", LIST_USERS)
          .route(HttpMethod.POST, "/users", CREATE_USER)
          .route(HttpMethod.DELETE, "/users", READ_USER)
          .build()).doesNotThrowAnyException();
    }

    @Test
    void twoIndependentTablesMayOverlap() {
      // Only one table has to answer for its own ambiguity. Nothing here merges routers, because merging is where a
      // route registry with precedence rules arrives (ADR-0019 §19).
      assertThatCode(() -> {
        Router.builder().route(HttpMethod.GET, "/users", LIST_USERS).build();
        Router.builder().route(HttpMethod.GET, "/users", CREATE_USER).build();
      }).doesNotThrowAnyException();
    }

    @Test
    void aParameterRouteAndAStaticRouteAreNotDuplicates() {
      assertThatCode(() -> Router.builder()
          .route(HttpMethod.GET, "/users/{id}", READ_USER)
          .route(HttpMethod.GET, "/users/new", NEW_USER_FORM)
          .route(HttpMethod.GET, "/users", LIST_USERS)
          .build()).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("no route: 404 versus 405")
  class NoRoute {

    @Test
    void anUnknownPathMatchesNothingAndAnswersNoMethods() {
      Router router = usersRouter();

      assertThat(match(router, HttpMethod.GET, "/unknown")).isEmpty();
      assertThat(router.methodsFor("/unknown")).isEmpty();
    }

    @Test
    void aMethodMismatchMatchesNothingButNamesTheMethodsThatWouldHaveAnswered() {
      // The pair of answers IS the 404/405 decision, handed to the adapter as data rather than made here as a
      // response (ADR-0019 §10).
      Router router = usersRouter();

      assertThat(match(router, HttpMethod.DELETE, "/users")).isEmpty();
      assertThat(router.methodsFor("/users")).containsExactly(HttpMethod.GET, HttpMethod.POST);
    }

    @Test
    void aMismatchOnAParameterPathNamesTheMethodsThatCoverIt() {
      assertThat(usersRouter().methodsFor("/users/42")).containsExactly(HttpMethod.GET);
    }

    @Test
    void theDistinctionHoldsForEveryParameterSpelling() {
      Router router = usersRouter();

      assertThat(router.methodsFor("/users/a%2Fb")).containsExactly(HttpMethod.GET);
      assertThat(router.methodsFor("/users/")).isEmpty();
    }

    @Test
    void theMethodSetIsInRegistrationOrderAndCannotBeChanged() {
      Router router = Router.builder()
          .route(HttpMethod.DELETE, "/users", READ_USER)
          .route(HttpMethod.GET, "/users", LIST_USERS)
          .route(HttpMethod.POST, "/users", CREATE_USER)
          .build();

      Set<HttpMethod> methods = router.methodsFor("/users");
      assertThat(methods).containsExactly(HttpMethod.DELETE, HttpMethod.GET, HttpMethod.POST);
      // An adapter reports this answer; it does not edit it. A mutable set here would be a mutable table next to it.
      assertThatThrownBy(() -> methods.add(HttpMethod.PUT)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void askingAboutAPathNeverProducesAResponse() {
      Router router = usersRouter();

      // No status code, no error body, no Content-Type: nothing here reaches for Response's constructor, which is
      // what the architecture rule the_router_never_answers_a_request_itself enforces (ADR-0019 §10).
      assertThat(match(router, HttpMethod.GET, "/unknown")).isEmpty();
      assertThat(router.methodsFor("/users")).isNotEmpty();
      assertThat(router.toString()).doesNotContain("404").doesNotContain("405");
    }

    @Test
    void abarePathMustLookLikeARequestPath() {
      Router router = usersRouter();

      // methodsFor takes a String rather than a Request, so it applies the rule Request.path() already applied
      // instead of silently matching nothing (ADR-0019 §10).
      assertThatThrownBy(() -> router.methodsFor("users"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must start with \"/\"");
      assertThatThrownBy(() -> router.methodsFor(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("path to ask about");
    }
  }

  @Nested
  @DisplayName("path parameters")
  class Parameters {

    @Test
    void aParameterMatchesOneSegmentAndIsCaptured() {
      assertThat(match(usersRouter(), HttpMethod.GET, "/users/123"))
          .get()
          .extracting(RouteMatch::handler, RouteMatch::parameters)
          .containsExactly(READ_USER, Map.of("id", "123"));
    }

    @Test
    void twoParametersAreCapturedAtTheirOwnPositions() {
      // The bug this test found while it was being written: zipping names against path indices captured "a" as x.
      Router router = Router.builder().route(HttpMethod.GET, "/a/{x}/b/{y}", READ_USER).build();

      assertThat(match(router, HttpMethod.GET, "/a/1/b/2"))
          .get()
          .extracting(RouteMatch::parameters)
          .isEqualTo(Map.of("x", "1", "y", "2"));
    }

    @Test
    void parametersAreInPatternOrderRatherThanAlphabeticalOrder() {
      Router router = Router.builder().route(HttpMethod.GET, "/{zoo}/{alpha}", READ_USER).build();

      assertThat(match(router, HttpMethod.GET, "/a/b"))
          .get()
          .extracting(found -> List.copyOf(found.parameters().keySet()))
          .isEqualTo(List.of("zoo", "alpha"));
    }

    @Test
    void aStaticRouteCapturesAnEmptyMap() {
      assertThat(match(usersRouter(), HttpMethod.GET, "/users")).get()
          .extracting(RouteMatch::parameters).isEqualTo(Map.of());
    }

    @Test
    void aParameterDoesNotMatchAnEmptySegment() {
      Router router = usersRouter();

      // /users// is a malformed path rather than a user whose id is empty: matching it would hand the handler an
      // identifier it cannot validate (ADR-0019 §6).
      assertThat(match(router, HttpMethod.GET, "/users/")).isEmpty();
      assertThat(router.methodsFor("/users/")).isEmpty();
    }

    @Test
    void aParameterDoesNotSpanTwoSegments() {
      assertThat(match(usersRouter(), HttpMethod.GET, "/users/1/2")).isEmpty();
    }

    @Test
    void theParameterMapCannotBeChanged() {
      Map<String, String> parameters = match(usersRouter(), HttpMethod.GET, "/users/123").orElseThrow().parameters();

      assertThatThrownBy(() -> parameters.put("id", "other")).isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(() -> parameters.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void onlyTheNamesThePatternDeclaredArePresent() {
      RouteMatch found = match(usersRouter(), HttpMethod.GET, "/users/123").orElseThrow();

      // A handler that asks for a name the route never declared gets a missing key rather than an invented value.
      assertThat(found.parameters()).containsOnlyKeys("id").doesNotContainKey("userId");
    }
  }

  @Nested
  @DisplayName("matching priority")
  class Priority {

    @Test
    void aStaticRouteBeatsAParameterRoute() {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/users/{id}", READ_USER)
          .route(HttpMethod.GET, "/users/new", NEW_USER_FORM)
          .build();

      // Registered parameter-first on purpose: the winner is a property of the shapes, not of the order they arrived
      // in (ADR-0019 §8).
      assertThat(match(router, HttpMethod.GET, "/users/new")).get()
          .extracting(RouteMatch::handler).isSameAs(NEW_USER_FORM);
      assertThat(match(router, HttpMethod.GET, "/users/7")).get()
          .extracting(RouteMatch::handler).isSameAs(READ_USER);
    }

    @Test
    void registrationOrderDoesNotChangeTheWinner() {
      Router parameterFirst = Router.builder()
          .route(HttpMethod.GET, "/a/{x}/b", READ_USER)
          .route(HttpMethod.GET, "/a/static/b", NEW_USER_FORM)
          .build();
      Router literalFirst = Router.builder()
          .route(HttpMethod.GET, "/a/static/b", NEW_USER_FORM)
          .route(HttpMethod.GET, "/a/{x}/b", READ_USER)
          .build();

      for (Router router : List.of(parameterFirst, literalFirst)) {
        assertThat(match(router, HttpMethod.GET, "/a/static/b")).get()
            .extracting(RouteMatch::handler).isSameAs(NEW_USER_FORM);
        assertThat(match(router, HttpMethod.GET, "/a/other/b")).get()
            .extracting(RouteMatch::handler).isSameAs(READ_USER);
      }
    }

    @Test
    void theFirstPositionWhereTheShapesDifferDecides() {
      // /{a}/fixed/{b} and /fixed/{c}/fixed differ at position 0, where the second is literal, so it wins whatever
      // the rest of the pattern looks like.
      Router router = Router.builder()
          .route(HttpMethod.GET, "/{a}/fixed/{b}", READ_USER)
          .route(HttpMethod.GET, "/fixed/{c}/fixed", NEW_USER_FORM)
          .build();

      assertThat(match(router, HttpMethod.GET, "/fixed/fixed/fixed"))
          .get()
          .extracting(RouteMatch::handler, RouteMatch::parameters)
          .containsExactly(NEW_USER_FORM, Map.of("c", "fixed"));
    }

    @Test
    void theWinnerIsTheOnlyRouteComparedForThatPath() {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/{x}", READ_USER)
          .route(HttpMethod.GET, "/users", LIST_USERS)
          .route(HttpMethod.GET, "/admin", NEW_USER_FORM)
          .build();

      assertThat(match(router, HttpMethod.GET, "/users")).get().extracting(RouteMatch::handler).isSameAs(LIST_USERS);
      assertThat(match(router, HttpMethod.GET, "/admin")).get().extracting(RouteMatch::handler).isSameAs(NEW_USER_FORM);
      assertThat(match(router, HttpMethod.GET, "/anything")).get()
          .extracting(RouteMatch::handler, RouteMatch::parameters)
          .containsExactly(READ_USER, Map.of("x", "anything"));
    }

    @Test
    void thePriorityRuleNeverHasToBreakATie() {
      // A tie means the same shape under the same method, and that was refused at registration — so there is no
      // undocumented "first one wins" path left in the code (ADR-0019 §8, §9).
      assertThatThrownBy(() -> Router.builder()
          .route(HttpMethod.GET, "/{a}", READ_USER)
          .route(HttpMethod.GET, "/{b}", NEW_USER_FORM))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("encoded paths")
  class Encoding {

    @Test
    void anEncodedSlashStaysOneSegmentAndStaysEncoded() {
      Router router = usersRouter();

      // The phase's central test. /users/a%2Fb is one segment holding an encoded slash, so it matches /users/{id} and
      // captures the raw text — never "a/b", which would be a two-segment resource reached by the framework decoding
      // what the client sent (ADR-0018 §2, ADR-0019 §4, §5).
      Optional<RouteMatch> found = match(router, HttpMethod.GET, "/users/a%2Fb");

      assertThat(found).get().extracting(RouteMatch::parameters).isEqualTo(Map.of("id", "a%2Fb"));
      assertThat(found.orElseThrow().parameters().get("id")).doesNotContain("/");
    }

    @Test
    void anEncodedDotSegmentIsNotNormalisedAway() {
      Router router = Router.builder().route(HttpMethod.GET, "/a/{x}/b", READ_USER).build();

      // Nothing here calls URI.normalize(): /a/%2e%2e/b keeps three segments and captures the encoded text, so a
      // traversal attempt reaches the handler in the form the application can reason about (ADR-0019 §5).
      assertThat(match(router, HttpMethod.GET, "/a/%2e%2e/b"))
          .get()
          .extracting(RouteMatch::parameters)
          .isEqualTo(Map.of("x", "%2e%2e"));
    }

    @Test
    void anEncodedAndADecodedPathAreDifferentPaths() {
      Router router = Router.builder().route(HttpMethod.GET, "/a/b", LIST_USERS).build();

      assertThat(match(router, HttpMethod.GET, "/a%2Fb")).isEmpty();
      assertThat(match(router, HttpMethod.GET, "/a/b")).isPresent();
    }

    @Test
    void aLiteralPatternIsComparedAgainstRawText() {
      Router router = Router.builder().route(HttpMethod.GET, "/a/../b", LIST_USERS).build();

      // A pattern spelled with dots matches dots as sent, and does not pick up the encoded form of the same bytes.
      assertThat(match(router, HttpMethod.GET, "/a/../b")).isPresent();
      assertThat(match(router, HttpMethod.GET, "/a/%2e%2e/b")).isEmpty();
    }

    @Test
    void lowercaseEncodingIsPreservedRatherThanUpperCased() {
      assertThat(match(usersRouter(), HttpMethod.GET, "/users/a%2fb"))
          .get()
          .extracting(RouteMatch::parameters)
          .isEqualTo(Map.of("id", "a%2fb"));
    }

    @Test
    void everyOtherEncodedCharacterIsCapturedUnchanged() {
      Router router = usersRouter();

      for (String segment : List.of("a%20b", "%C3%A9t%C3%A9", "50%25", "%2F%2F", "x%252Fy")) {
        assertThat(match(router, HttpMethod.GET, "/users/" + segment)).as(segment).isPresent();
        assertThat(match(router, HttpMethod.GET, "/users/" + segment).orElseThrow().parameters().get("id"))
            .as("captured %s", segment)
            .isEqualTo(segment);
      }
    }

    @Test
    void aPlusInAPathIsNotASpace() {
      assertThat(match(usersRouter(), HttpMethod.GET, "/users/a+b"))
          .get()
          .extracting(RouteMatch::parameters)
          .isEqualTo(Map.of("id", "a+b"));
    }
  }

  @Nested
  @DisplayName("trailing slash")
  class TrailingSlash {

    @Test
    void aTrailingSlashIsASecondRouteNobodyGetsForFree() {
      Router router = Router.builder().route(HttpMethod.GET, "/users", LIST_USERS).build();

      // Exact semantics: no implicit match and no implicit redirect (ADR-0019 §6).
      assertThat(match(router, HttpMethod.GET, "/users/")).isEmpty();
      assertThat(router.methodsFor("/users/")).isEmpty();
    }

    @Test
    void aTrailingSlashRouteIsRegistrableOnItsOwn() {
      Router router = Router.builder().route(HttpMethod.GET, "/users/", TRAILING_SLASH_HANDLER).build();

      assertThat(match(router, HttpMethod.GET, "/users/")).get()
          .extracting(RouteMatch::handler).isSameAs(TRAILING_SLASH_HANDLER);
      assertThat(match(router, HttpMethod.GET, "/users")).isEmpty();
    }

    @Test
    void theTwoSpellingsAreDifferentShapesSoBothAreAllowed() {
      assertThatCode(() -> Router.builder()
          .route(HttpMethod.GET, "/users", LIST_USERS)
          .route(HttpMethod.GET, "/users/", TRAILING_SLASH_HANDLER)
          .build()).doesNotThrowAnyException();
    }

    @Test
    void anEmbeddedEmptySegmentIsRegisteredAndMatchedExactly() {
      Router router = Router.builder().route(HttpMethod.GET, "/a//b", LIST_USERS).build();

      assertThat(match(router, HttpMethod.GET, "/a//b")).isPresent();
      assertThat(match(router, HttpMethod.GET, "/a/b")).isEmpty();
    }

    @Test
    void theRootAndATrailingSlashPatternAreDistinctRoutes() {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/", LIST_USERS)
          .route(HttpMethod.GET, "//", TRAILING_SLASH_HANDLER)
          .build();

      assertThat(match(router, HttpMethod.GET, "/")).get().extracting(RouteMatch::handler).isSameAs(LIST_USERS);
      assertThat(match(router, HttpMethod.GET, "//")).get()
          .extracting(RouteMatch::handler).isSameAs(TRAILING_SLASH_HANDLER);
    }
  }

  @Nested
  @DisplayName("invalid patterns")
  class RefusedPatterns {

    @Test
    void aPatternMustStartWithASlash() {
      assertThatThrownBy(() -> Router.builder().route(HttpMethod.GET, "users", LIST_USERS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must start with \"/\"");
    }

    @Test
    void anAsteriskIsARequestTargetNotAPattern() {
      assertThatThrownBy(() -> Router.builder().route(HttpMethod.OPTIONS, "*", LIST_USERS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not \"*\"");
    }

    @Test
    void aPatternMayNotCarryAQuery() {
      assertThatThrownBy(() -> Router.builder().route(HttpMethod.GET, "/users?page=2", LIST_USERS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not contain \"?\"");
    }

    @Test
    void aPatternCannotContainRawWhitespaceOrControls() {
      List<String> accepted = new ArrayList<>();
      for (String pattern : List.of("/users/a b", "/users/a\tb", "/users/a\r\nb", "/users/a\u007fb")) {
        try {
          Router.builder().route(HttpMethod.GET, pattern, LIST_USERS);
          accepted.add(pattern);
        } catch (IllegalArgumentException failure) {
          assertThat(failure).hasMessageContaining("control character or space")
              .satisfies(error -> assertThat(error.getMessage()).doesNotContain("\r").doesNotContain("\n"));
        }
      }
      assertThat(accepted).as("unreachable or unsafe route patterns accepted by the builder").isEmpty();
    }

    @Test
    void percentEncodedWhitespaceRemainsAValidLiteralPattern() {
      Router router = Router.builder().route(HttpMethod.GET, "/users/a%20b", LIST_USERS).build();

      assertThat(match(router, HttpMethod.GET, "/users/a%20b")).isPresent();
      assertThat(router.methodsFor("/users/a%20b")).containsExactly(HttpMethod.GET);
    }

    @Test
    void bracesMustFillAWholeSegment() {
      // "{}" is three characters short of that: a two-character pair of braces is a literal segment holding braces,
      // not a parameter with an empty name, so it is refused before it could ever capture anything.
      for (String pattern : List.of("/users/prefix{id}", "/users/{id}suffix", "/users/{", "/users/}", "/users/{}")) {
        assertThatThrownBy(() -> Router.builder().route(HttpMethod.GET, pattern, LIST_USERS))
            .as("refuse %s", pattern)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not a whole parameter");
      }
    }

    @Test
    void aParameterNameMustBeAnIdentifier() {
      for (String segment : List.of("{2fa}", "{user-name}", "{id!}", "{2fa+x}", "{{id}}")) {
        assertThatThrownBy(() -> Router.builder().route(HttpMethod.GET, "/users/" + segment, LIST_USERS))
            .as("refuse %s", segment)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not a parameter name");
      }
    }

    @Test
    void oneParameterNameCannotBeUsedTwiceInOnePattern() {
      assertThatThrownBy(() -> Router.builder().route(HttpMethod.GET, "/{a}/{a}", LIST_USERS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("twice");
    }

    @Test
    void everyNullArgumentIsRefusedNamingWhatWasRefused() {
      Router.Builder builder = Router.builder();

      assertThatThrownBy(() -> builder.route(null, "/users", LIST_USERS))
          .isInstanceOf(NullPointerException.class).hasMessageContaining("route method");
      assertThatThrownBy(() -> builder.route(HttpMethod.GET, null, LIST_USERS))
          .isInstanceOf(NullPointerException.class).hasMessageContaining("route pattern");
      assertThatThrownBy(() -> builder.route(HttpMethod.GET, "/users", null))
          .isInstanceOf(NullPointerException.class).hasMessageContaining("route handler");
      assertThatThrownBy(() -> builder.build().match(null))
          .isInstanceOf(NullPointerException.class).hasMessageContaining("request to route");
    }

    @Test
    void anInvalidPatternLeavesTheTableUntouched() {
      Router.Builder builder = Router.builder().route(HttpMethod.GET, "/users", LIST_USERS);

      assertThatThrownBy(() -> builder.route(HttpMethod.GET, "/users/{1bad}", CREATE_USER))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(builder.build()).hasToString("Router[GET /users]");
    }

    @Test
    void anEmptyParameterSegmentNameIsRefusedBeforeItCouldCaptureAnything() {
      // A two-character "{}" is not a parameter at all, so it is a literal segment that happens to hold braces.
      assertThatThrownBy(() -> Router.builder().route(HttpMethod.GET, "/users/{}", LIST_USERS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not a whole parameter");
    }
  }

  @Nested
  @DisplayName("the table cannot change")
  class Immutability {

    @Test
    void aBuiltRouterDoesNotSeeRoutesRegisteredAfterwards() {
      Router.Builder builder = Router.builder().route(HttpMethod.GET, "/users", LIST_USERS);
      Router router = builder.build();

      // The freeze is in the table rather than in a flag: build() copies, so a later registration reaches only the
      // next router (ADR-0019 §13).
      builder.route(HttpMethod.POST, "/users", CREATE_USER);

      assertThat(match(router, HttpMethod.POST, "/users")).isEmpty();
      assertThat(router).hasToString("Router[GET /users]");
      assertThat(match(builder.build(), HttpMethod.POST, "/users")).isPresent();
    }

    @Test
    void everyFieldOfARouterAndItsBuilderIsFinal() {
      // The Phase 2.7 rule every_field_is_final still holds with the router in the package; asserted here too,
      // because a non-final table would make every test in this class meaningless.
      for (Class<?> type : List.of(Router.class, Router.Builder.class)) {
        assertThat(Arrays.stream(type.getDeclaredFields())
            .filter(field -> !field.isSynthetic())
            .filter(field -> !Modifier.isStatic(field.getModifiers())))
            .as("fields of %s", type.getSimpleName())
            .isNotEmpty()
            .allMatch(field -> Modifier.isFinal(field.getModifiers()));
      }
    }

    @Test
    void noRouterMethodHandsOutTheTable() {
      // match hands back a handler and a frozen map; methodsFor a frozen set. The registrations themselves are a
      // private record, so there is no collection of routes to edit (ADR-0019 §13, §19).
      assertThat(Arrays.stream(Router.class.getDeclaredMethods())
          .filter(method -> Modifier.isPublic(method.getModifiers()))
          .map(Method::getReturnType))
          .containsOnly(Optional.class, Set.class, Router.Builder.class, String.class);
    }

    @Test
    void theSameRouterAnswersTheSameQuestionTheSameWayEveryTime() {
      Router router = usersRouter();

      for (int attempt = 0; attempt < 50; attempt++) {
        assertThat(match(router, HttpMethod.GET, "/users/42")).get().extracting(RouteMatch::handler).isSameAs(READ_USER);
        assertThat(router.methodsFor("/users")).containsExactly(HttpMethod.GET, HttpMethod.POST);
        assertThat(router).hasToString("Router[GET /users, POST /users, GET /users/{id}]");
      }
    }

    @Test
    void twoMatchesForTheSameRequestAreEqualButIndependent() {
      Router router = usersRouter();

      RouteMatch first = match(router, HttpMethod.GET, "/users/42").orElseThrow();
      RouteMatch second = match(router, HttpMethod.GET, "/users/42").orElseThrow();

      assertThat(second).isEqualTo(first).hasSameHashCodeAs(first);
      assertThat(second).isNotSameAs(first);
    }
  }

  @Nested
  @DisplayName("concurrent matching")
  class Concurrency {

    @Test
    void manyThreadsMatchTheSameTableAndGetTheSameAnswers() throws Exception {
      Router router = usersRouter();
      int threads = 8;
      int repeats = 500;
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      List<Object> results = new CopyOnWriteArrayList<>();
      try {
        List<Callable<Void>> work = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
          work.add(() -> {
            for (int repeat = 0; repeat < repeats; repeat++) {
              results.add(match(router, HttpMethod.GET, "/users/42").map(RouteMatch::handler));
              results.add(match(router, HttpMethod.GET, "/users/42").map(RouteMatch::parameters));
              results.add(match(router, HttpMethod.POST, "/users").map(RouteMatch::handler));
              results.add(match(router, HttpMethod.GET, "/users/new"));
              results.add(match(router, HttpMethod.GET, "/unknown"));
              results.add(router.methodsFor("/users"));
            }
            return null;
          });
        }
        for (Future<Void> done : pool.invokeAll(work)) {
          done.get(60, TimeUnit.SECONDS);
        }
      } finally {
        pool.shutdownNow();
      }

      assertThat(results).hasSize(threads * repeats * 6);
      // Every answer is the one a single-threaded match gives on this table: the parameter route for /users/42 under
      // both projections, the creation route for POST /users, the parameter route for /users/new (no static route
      // covers it here), nothing for /unknown, and the two methods /users has. Nothing is missing, duplicated, or
      // half-read, because there is nothing to race: the table was copied before any thread saw it (ADR-0019 §13).
      assertThat(results).containsOnly(
          Optional.of(READ_USER),
          Optional.of(Map.of("id", "42")),
          Optional.of(CREATE_USER),
          Optional.of(new RouteMatch(READ_USER, Map.of("id", "new"))),
          Optional.empty(),
          Set.of(HttpMethod.GET, HttpMethod.POST));
    }

    @Test
    void aRouterBuiltFromTheSameRegistrationsAgreesWithTheFirstEverywhere() {
      // Two tables built from the same list answer identically, which is what makes "the routes this application has"
      // a question with one answer (ADR-0019 §13).
      Router first = usersRouter();
      Router second = usersRouter();

      for (String path : List.of("/users", "/users/7", "/users/new", "/users/", "/unknown", "/a/b")) {
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.DELETE)) {
          assertThat(match(second, method, path).map(RouteMatch::handler))
              .as("%s %s", method.text(), path)
              .isEqualTo(match(first, method, path).map(RouteMatch::handler));
        }
        assertThat(second.methodsFor(path)).isEqualTo(first.methodsFor(path));
      }
    }

    @Test
    void matchingIsTheSameQuestionFromEveryThreadOnTheParameterRoute() throws Exception {
      Router router = usersRouter();
      ExecutorService pool = Executors.newFixedThreadPool(6);
      try {
        List<Callable<String>> work = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
          work.add(() -> router.match(request(HttpMethod.GET, "/users/42"))
              .map(found -> found.handler() == READ_USER ? "read:" + found.parameters().get("id") : "other")
              .orElse("none"));
        }
        List<String> answers = new ArrayList<>();
        for (Future<String> done : pool.invokeAll(work)) {
          answers.add(done.get(60, TimeUnit.SECONDS));
        }

        assertThat(answers).containsOnly("read:42");
      } finally {
        pool.shutdownNow();
      }
    }
  }

  @Nested
  @DisplayName("parameter delivery: router to handler")
  class Delivery {

    @Test
    void theHandlerReceivesWhatTheRouterCapturedWithoutParsingThePath() {
      // The handler is written against the parameter name only: no substring, no lastIndexOf, no segment count. Under
      // the previous contract this test could not be written, because the only way to get an id was to slice
      // request.path() again (ADR-0020 §1).
      List<Map<String, String>> seenByHandler = new ArrayList<>();
      Handler read = (request, parameters) -> {
        seenByHandler.add(parameters);
        return new Response(200, Headers.empty(),
            parameters.get("id").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      };
      Router router = Router.builder().route(HttpMethod.GET, "/users/{id}", read).build();

      Request request = request(HttpMethod.GET, "/users/42");
      RouteMatch found = router.match(request).orElseThrow();

      assertThat(new String(found.handler().handle(request, found.parameters()).body(),
          java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("42");
      // Identity across the hand-off: the map the handler read is the very map the match produced, so nothing between
      // routing and dispatching rebuilt or replaced what routing decided.
      assertThat(seenByHandler).singleElement().isSameAs(found.parameters());
    }

    @Test
    void twoParametersArriveByNameAndInPatternOrder() {
      List<String> observed = new ArrayList<>();
      Handler both = (request, parameters) -> {
        observed.addAll(parameters.keySet());
        return new Response(200, Headers.empty(), null);
      };
      Router router = Router.builder()
          .route(HttpMethod.GET, "/users/{userId}/posts/{postId}", both)
          .build();

      Request request = request(HttpMethod.GET, "/users/7/posts/3");
      RouteMatch found = router.match(request).orElseThrow();
      found.handler().handle(request, found.parameters());

      assertThat(observed).containsExactly("userId", "postId");
      assertThat(found.parameters()).containsExactly(
          Map.entry("userId", "7"), Map.entry("postId", "3"));
      // The names came from the pattern, which the handler cannot see: re-parsing the path would have produced the two
      // values and no way to know which was which. This is the case where the router's answer is not reconstructible
      // from the request at all.
    }

    @Test
    void anEncodedParameterReachesTheHandlerStillEncodedAndStillOneValue() {
      Handler read = (request, parameters) -> new Response(
          200, Headers.empty(), parameters.get("id").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      Router router = Router.builder().route(HttpMethod.GET, "/users/{id}", read).build();

      Request request = request(HttpMethod.GET, "/users/a%2Fb");
      RouteMatch found = router.match(request).orElseThrow();

      assertThat(new String(found.handler().handle(request, found.parameters()).body(),
          java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("a%2Fb");
      assertThat(found.parameters().get("id")).doesNotContain("/");
      // Phase 2.8's encoding semantics survive the widened signature unchanged: the map holds raw text because the
      // table captured raw text (ADR-0019 §5).
    }

    @Test
    void aStaticRouteDeliversAnEmptyMapSoTheHandlerNeedsNoSpecialCase() {
      List<Integer> sizes = new ArrayList<>();
      Handler records = (request, parameters) -> {
        sizes.add(parameters.size());
        return new Response(200, Headers.empty(), null);
      };
      Router router = Router.builder().route(HttpMethod.GET, "/healthz", records).build();

      Request request = request(HttpMethod.GET, "/healthz");
      RouteMatch found = router.match(request).orElseThrow();
      found.handler().handle(request, found.parameters());

      assertThat(sizes).containsExactly(0);
    }

    @Test
    void aStaticRouteBeatsTheParameterRouteAndDeliversNoParameters() {
      // §11's static-route check, on the delivery side: /users/me answers from the static route with an empty map, so
      // the parameter route's name is not merely absent — the map itself says nothing was captured.
      List<Map<String, String>> seen = new ArrayList<>();
      Handler form = (request, parameters) -> {
        seen.add(parameters);
        return new Response(200, Headers.empty(), null);
      };
      Router router = Router.builder()
          .route(HttpMethod.GET, "/users/{id}", READ_USER)
          .route(HttpMethod.GET, "/users/me", form)
          .build();

      Request request = request(HttpMethod.GET, "/users/me");
      RouteMatch found = router.match(request).orElseThrow();
      found.handler().handle(request, found.parameters());

      assertThat(found.handler()).isSameAs(form);
      assertThat(seen).containsExactly(Map.of());
    }

    @Test
    void aHandlerThatEditsItsMapCannotReachTheTableOrTheNextRequest() {
      Router router = usersRouter();
      Request first = request(HttpMethod.GET, "/users/42");
      RouteMatch found = router.match(first).orElseThrow();

      Handler attempts = (request, parameters) -> {
        try {
          parameters.put("id", "1");
          return new Response(500, Headers.empty(), null);
        } catch (UnsupportedOperationException refused) {
          return new Response(200, Headers.empty(), null);
        }
      };

      assertThat(attempts.handle(first, found.parameters()).status()).isEqualTo(200);

      // The refused write was against the map this match produced; the table's answer for the same route is untouched,
      // so the next request still sees the value its own client sent (ADR-0020 §7).
      assertThat(router.match(request(HttpMethod.GET, "/users/42")).orElseThrow().parameters())
          .containsExactly(Map.entry("id", "42"));
      assertThat(router.match(request(HttpMethod.GET, "/users/7")).orElseThrow().parameters())
          .containsExactly(Map.entry("id", "7"));
    }

    @Test
    void concurrentRequestsOnOneRouteNeverSeeEachOthersParameters() throws Exception {
      List<String> observed = new CopyOnWriteArrayList<>();
      Handler echo = (request, parameters) -> {
        observed.add(parameters.get("id"));
        return new Response(200, Headers.empty(), null);
      };
      Router router = Router.builder().route(HttpMethod.GET, "/users/{id}", echo).build();

      ExecutorService pool = Executors.newFixedThreadPool(9);
      try {
        List<Callable<Void>> work = new ArrayList<>();
        for (int round = 0; round < 200; round++) {
          for (String id : List.of("1", "2", "3")) {
            work.add(() -> {
              Request request = request(HttpMethod.GET, "/users/" + id);
              RouteMatch found = router.match(request).orElseThrow();
              found.handler().handle(request, found.parameters());
              return null;
            });
          }
        }
        for (Future<Void> done : pool.invokeAll(work)) {
          done.get(60, TimeUnit.SECONDS);
        }
      } finally {
        pool.shutdownNow();
      }

      // 600 dispatches and every handler saw the id its own request carried: no null, no value from another thread.
      // The map is built per match rather than stored on the router or the request, which is what makes a crossed
      // parameter structurally impossible rather than merely unlikely (ADR-0020 §8).
      assertThat(observed).hasSize(600);
      assertThat(java.util.Collections.frequency(observed, "1")).isEqualTo(200);
      assertThat(java.util.Collections.frequency(observed, "2")).isEqualTo(200);
      assertThat(java.util.Collections.frequency(observed, "3")).isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("printing")
  class Printing {

    @Test
    void aSummaryNamesEveryRouteInRegistrationOrder() {
      assertThat(usersRouter()).hasToString("Router[GET /users, POST /users, GET /users/{id}]");
    }

    @Test
    void aSummaryNeverNamesAHandler() {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/users",
              (request, ignored) -> new Response(200, Headers.of("Set-Cookie", "secret"), null))
          .build();

      String summary = router.toString();

      assertThat(summary).isEqualTo("Router[GET /users]");
      assertThat(summary).doesNotContain("secret").doesNotContain("Set-Cookie").doesNotContain("com.barezen");
    }

    @Test
    void aSummaryNamesThePatternRatherThanTheShape() {
      Router router = Router.builder().route(HttpMethod.GET, "/users/{userId}", READ_USER).build();

      // The pattern is what the application wrote and what an operator needs; the shape is the internal key the
      // duplicate rule compares.
      assertThat(router).hasToString("Router[GET /users/{userId}]");
    }
  }
}
