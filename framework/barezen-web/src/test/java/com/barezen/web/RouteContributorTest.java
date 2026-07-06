package com.barezen.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The first extension point: what a contributor may do to a route table, what it may not, and what the built table does
 * with what it was given.
 *
 * <p>Every observation is still a {@link Router#match(Request)} on the built table, because the {@code Route} type a
 * contributor would need to inspect its own registrations is the first name ADR-0019 §19 refuses. What these tests pin is
 * the seam ADR-0047 opens: a contribution is a {@code route} call made by somebody else, so every rule a hand-written
 * route carries — duplicate refusal, raw pattern, captured parameters, a table frozen at {@code build()} — lands on a
 * contributed route too, and nothing in this mechanism has ever heard the word {@code Plugin}.
 */
class RouteContributorTest {

  private static final Handler APP_ROUTE = (request, ignored) -> new Response(200, Headers.empty(), "app".getBytes());
  private static final Handler CONTRIBUTED =
      (request, ignored) -> new Response(200, Headers.empty(), "contributed".getBytes());

  private static Request request(HttpMethod method, String path) {
    return new Request(method, path, null, Headers.empty(), null);
  }

  @Nested
  @DisplayName("a contributed route is the registered route")
  class Contributions {

    @Test
    void aContributorRegistersRoutesTheBuiltTableAnswers() {
      RouteContributor contributor = routes -> routes.route(HttpMethod.GET, "/plugin/status", CONTRIBUTED);

      Router router = Router.builder().contributing(List.of(contributor)).build();

      assertThat(router.match(request(HttpMethod.GET, "/plugin/status")))
          .get()
          .extracting(RouteMatch::handler)
          .isSameAs(CONTRIBUTED);
    }

    @Test
    void applicationRoutesAndContributedRoutesLandInTheSameTable() {
      RouteContributor contributor = routes -> routes.route(HttpMethod.POST, "/plugin/echo", CONTRIBUTED);

      Router router = Router.builder()
          .route(HttpMethod.GET, "/health", APP_ROUTE)
          .contributing(List.of(contributor))
          .build();

      assertThat(router.match(request(HttpMethod.GET, "/health")))
          .get().extracting(RouteMatch::handler).isSameAs(APP_ROUTE);
      assertThat(router.match(request(HttpMethod.POST, "/plugin/echo")))
          .get().extracting(RouteMatch::handler).isSameAs(CONTRIBUTED);
      assertThat(router.match(request(HttpMethod.POST, "/health"))).isEmpty();
    }

    @Test
    void aContributedRouteCapturesItsParametersLikeAnyRoute() {
      Router router = Router.builder()
          .contributing(List.of(routes -> routes.route(HttpMethod.GET, "/plugin/items/{id}", CONTRIBUTED)))
          .build();

      Optional<RouteMatch> match = router.match(request(HttpMethod.GET, "/plugin/items/42"));

      assertThat(match).get().extracting(RouteMatch::parameters).isEqualTo(Map.of("id", "42"));
    }

    @Test
    void severalContributorsAreAppliedInIterationOrder() {
      List<String> order = new java.util.ArrayList<>();
      List<RouteContributor> contributors = List.of(
          routes -> {
            order.add("first");
            routes.route(HttpMethod.GET, "/one", CONTRIBUTED);
          },
          routes -> {
            order.add("second");
            routes.route(HttpMethod.GET, "/two", APP_ROUTE);
          });

      Router router = Router.builder().contributing(contributors).build();

      assertThat(order).containsExactly("first", "second");
      assertThat(router.methodsFor("/one")).containsExactly(HttpMethod.GET);
      assertThat(router.methodsFor("/two")).containsExactly(HttpMethod.GET);
    }

    @Test
    void contributingNothingIsNotAnError() {
      Router.Builder builder = Router.builder();

      assertThatCode(() -> builder.contributing(List.of()).route(HttpMethod.GET, "/x", APP_ROUTE).build())
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("a contribution buys no extra power")
  class Refusals {

    @Test
    void aContributedRouteCannotShadowAnApplicationRoute() {
      // The duplicate refusal is registration-time and order-blind: a plugin cannot win a route by running later, an
      // application cannot lose one silently either, and no precedence rule between the two kinds arrives for free
      // (ADR-0019 §9).
      RouteContributor colliding = routes -> routes.route(HttpMethod.GET, "/users/{id}", CONTRIBUTED);

      assertThatThrownBy(() -> Router.builder()
          .route(HttpMethod.GET, "/users/{name}", APP_ROUTE)
          .contributing(List.of(colliding)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already registered")
          .hasMessageContaining("/users/{name}");
    }

    @Test
    void twoContributorsCannotClaimTheSameRouteEither() {
      RouteContributor one = routes -> routes.route(HttpMethod.GET, "/shared", CONTRIBUTED);
      RouteContributor other = routes -> routes.route(HttpMethod.GET, "/shared", APP_ROUTE);

      assertThatThrownBy(() -> Router.builder().contributing(List.of(one, other)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already registered");
    }

    @Test
    void aContributorCannotRegisterARouteWithNoHandler() {
      RouteContributor incomplete = routes -> routes.route(HttpMethod.GET, "/plugin/half", null);

      assertThatThrownBy(() -> Router.builder().contributing(List.of(incomplete)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aNullContributorListIsRefusedNotTreatedAsEmpty() {
      // A null list would otherwise read as "nobody contributes", which is the quiet half of a missing plugin.
      assertThatThrownBy(() -> Router.builder().contributing(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aNullElementIsRefusedBecauseTheLoopWouldFailHalfwayThrough() {
      List<RouteContributor> withHole = Arrays.asList((RouteContributor) null);

      assertThatThrownBy(() -> Router.builder().contributing(withHole)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aContributionMadeAfterTheTableIsBuiltDoesNotReachThatTable() {
      Router.Builder builder = Router.builder();
      Router built = builder.build();
      RouteContributor tooLate = routes -> routes.route(HttpMethod.GET, "/late", CONTRIBUTED);

      builder.contributing(List.of(tooLate));

      // build() copies the registrations, so a frozen router cannot be edited through the builder it came from, even
      // though the builder itself stays usable (ADR-0019 §13).
      assertThat(built.match(request(HttpMethod.GET, "/late"))).isEmpty();
      assertThat(builder.build().match(request(HttpMethod.GET, "/late"))).isPresent();
    }

    @Test
    void aContributorThatRegistersNothingContributesNothing() {
      Router router = Router.builder()
          .route(HttpMethod.GET, "/health", APP_ROUTE)
          .contributing(List.of(routes -> {
          }))
          .build();

      // The table's own printing is the strongest statement available that an empty contribution added no
      // registration: a route is not a public type, so an entry a caller could see is exactly the bug this asserts
      // against (ADR-0019 §19).
      assertThat(router.toString()).isEqualTo("Router[GET /health]");
      assertThat(router.match(request(HttpMethod.GET, "/health"))).isPresent();
    }
  }

  @Nested
  @DisplayName("the extension point's own surface")
  class Surface {

    @Test
    void oneMethodTakingTheBuilderReturningNothing() {
      assertThat(Arrays.stream(RouteContributor.class.getDeclaredMethods())
          .map(Method::getName)
          .distinct())
          .containsExactly("contributeRoutes");
      Method contribute = RouteContributor.class.getDeclaredMethods()[0];
      assertThat(contribute.getParameterTypes()).containsExactly(Router.Builder.class);
      assertThat(contribute.getReturnType()).isEqualTo(void.class);
    }

    @Test
    void itIsAnInterfaceCarryingOnlyFunctionalInterface() {
      assertThat(RouteContributor.class.isInterface()).isTrue();
      assertThat(Arrays.stream(RouteContributor.class.getAnnotations()).map(Annotation::toString)
          .filter(carried -> !carried.startsWith("@" + FunctionalInterface.class.getName())))
          .isEmpty();
      assertThat(RouteContributor.class.isAnnotationPresent(FunctionalInterface.class)).isTrue();
    }

    @Test
    void aLambdaIsACompleteContributor() {
      RouteContributor lambda = routes -> routes.route(HttpMethod.GET, "/x", CONTRIBUTED);

      Router router = Router.builder().contributing(List.of(lambda)).build();

      assertThat(router.match(request(HttpMethod.GET, "/x"))).isPresent();
    }

    @Test
    void theContractStillNamesNoPluginType() {
      // ADR-0047's placement claim, checked at the only level a contract can check it: nothing in this package's
      // signatures reaches for a lifecycle concept, so a contributor is chosen by the caller rather than discovered.
      assertThat(Arrays.stream(RouteContributor.class.getDeclaredMethods())
          .flatMap(method -> Arrays.stream(method.getParameterTypes()))
          .map(Class::getName))
          .containsExactly("com.barezen.web.Router$Builder");
    }
  }
}
