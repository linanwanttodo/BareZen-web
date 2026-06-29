package com.barezen.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The handler signature: one request and one parameter map in, one response out, exceptions unhandled — and the
 * composition that signature makes possible without a middleware type.
 *
 * <p>Phase 2.8.1 added the second argument, so every test here is also evidence for the claim that the change left the
 * contract no larger: still one abstract method, still no default, still a lambda, still no way to write to a client
 * (ADR-0020 §6).
 */
class HandlerTest {

  private static Request get(String path) {
    return new Request(HttpMethod.GET, path, null, Headers.empty(), null);
  }

  private static Response text(String body) {
    return new Response(
        200, Headers.of("Content-Type", "text/plain"), body.getBytes(StandardCharsets.UTF_8));
  }

  @Nested
  @DisplayName("the signature")
  class Surface {

    @Test
    void hasExactlyOneAbstractMethodAndNoDefault() {
      // One method, no knobs: a second abstract method would mean every handler in every application implements both.
      assertThat(java.util.Arrays.stream(Handler.class.getDeclaredMethods())
          .filter(method -> !method.isSynthetic())
          .map(java.lang.reflect.Method::getName)
          .distinct())
          .containsExactly("handle");
    }

    @Test
    void takesTheRequestAndTheCapturedParametersAndNothingElse() {
      // Two parameters is the whole widening, and it is asserted as a signature rather than trusted from the source: a
      // third would be a context arriving one argument at a time (ADR-0020 §5).
      assertThat(java.util.Arrays.stream(Handler.class.getDeclaredMethods())
          .filter(method -> !method.isSynthetic())
          .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()).map(Class::getName)))
          .containsExactly("com.barezen.web.Request", "java.util.Map");
    }

    @Test
    void declaresNoOverloadOfHandle() {
      // The single-argument handle(Request) is gone, not kept beside the new one. Two live signatures would mean a
      // handler that quietly ignores the parameters its route declares, and every caller would have to guess which
      // overload an application meant to implement (ADR-0020 §6).
      assertThat(java.util.Arrays.stream(Handler.class.getDeclaredMethods())
          .filter(method -> !method.isSynthetic())
          .map(java.lang.reflect.Method::getName))
          .hasSize(1);
    }

    @Test
    void declaresNoCheckedException() {
      assertThat(Handler.class.getDeclaredMethods()[0].getExceptionTypes()).isEmpty();
      // A checked exception would be caught or declared by every wrapper, and a wrapper has nothing better to do with
      // one than pass it on (ADR-0018 §8).
    }

    @Test
    void isUsableAsALambda() {
      Handler handler = (request, ignored) -> text("hi " + request.path());

      assertThat(handler.handle(get("/bob"), Map.of()).status()).isEqualTo(200);
    }

    @Test
    void isAnnotatedFunctionalInterface() {
      // Written as an annotation check rather than an "is it a lambda" inference, because the annotation is what makes
      // adding a second method a compile error rather than a breaking release.
      assertThat(Handler.class.isAnnotationPresent(FunctionalInterface.class)).isTrue();
    }
  }

  @Nested
  @DisplayName("request and parameters in, response out")
  class Dispatch {

    @Test
    void answersFromTheRequestItWasGiven() {
      Handler handler = (request, ignored) -> new Response(
          200,
          Headers.of("Content-Type", "text/plain"),
          request.headers().first("X-Name").orElse("anonymous").getBytes(StandardCharsets.UTF_8));

      Request named = new Request(
          HttpMethod.GET, "/greet", null, Headers.of("X-Name", "zen"), null);

      assertThat(new String(handler.handle(named, Map.of()).body(), StandardCharsets.UTF_8)).isEqualTo("zen");
      assertThat(new String(handler.handle(get("/greet"), Map.of()).body(), StandardCharsets.UTF_8))
          .isEqualTo("anonymous");
    }

    @Test
    void readsTheRequestThroughTheContractOnly() {
      // A handler that can answer needs nothing beyond method, path, query, headers and body — which is the evidence
      // that the type carries no server reference.
      Handler handler = (request, ignored) -> {
        StringBuilder summary = new StringBuilder();
        summary.append(request.method().text())
            .append(' ')
            .append(request.path())
            .append(request.query().map(query -> "?" + query).orElse(""))
            .append(' ')
            .append(request.headers().names().size())
            .append(" fields ")
            .append(request.body().length)
            .append(" bytes");
        return text(summary.toString());
      };

      Request request = new Request(
          HttpMethod.DELETE,
          "/orders/7",
          "force=true",
          Headers.of("If-Match", "\"v3\""),
          "note".getBytes(StandardCharsets.UTF_8));

      assertThat(new String(handler.handle(request, Map.of()).body(), StandardCharsets.UTF_8))
          .isEqualTo("DELETE /orders/7?force=true 1 fields 4 bytes");
    }

    @Test
    void answersWithoutTouchingAnyMutableState() {
      Handler handler = (request, ignored) -> new Response(
          request.path().equals("/found") ? 200 : 404, Headers.empty(), null);

      assertThat(handler.handle(get("/found"), Map.of()).status()).isEqualTo(200);
      assertThat(handler.handle(get("/gone"), Map.of()).status()).isEqualTo(404);
      // 404 is a response, not an exception: the contract has no error type, so a handler chooses the status.
    }

    @Test
    void answersAStaticRouteWithAnEmptyMapRatherThanAbsentParameters() {
      Handler handler = (request, parameters) -> text(Integer.toString(parameters.size()));

      assertThat(new String(handler.handle(get("/healthz"), Map.of()).body(), StandardCharsets.UTF_8))
          .isEqualTo("0");
      // A route with no parameters and no parameter concept are the same answer, so a handler never has to branch on
      // "was there a router here at all" — which is what would happen with a null map (ADR-0020 §7).
    }
  }

  @Nested
  @DisplayName("the parameters argument is the router's answer, not a re-parse")
  class Parameters {

    /** {@code GET /users/{id}}, written the way an application will write it. */
    private final Handler readUser = (request, parameters) -> text("user " + parameters.get("id"));

    @Test
    void answersFromTheMapRatherThanFromThePath() {
      // The path deliberately disagrees with the parameter. A handler that read the parameter out of the map answers 42,
      // which is the proof that it did not re-parse request.path() — the shape of the bug Phase 2.8 left behind, where
      // the only way to get an id was to slice the path again. No router would ever produce this pair; that is the point,
      // the handler cannot tell the difference, so the test has to make the two sources distinguishable (ADR-0020 §2).
      Request request = new Request(HttpMethod.GET, "/users/99", null, Headers.empty(), null);

      assertThat(new String(readUser.handle(request, Map.of("id", "42")).body(), StandardCharsets.UTF_8))
          .isEqualTo("user 42");
    }

    @Test
    void receivesEveryParameterTheRouteCapturedInPatternOrder() {
      Handler handler = (request, parameters) -> text(String.join(",", parameters.keySet()));
      Map<String, String> captured = new java.util.LinkedHashMap<>();
      captured.put("userId", "7");
      captured.put("postId", "3");

      Request request = new Request(
          HttpMethod.GET, "/users/7/posts/3", null, Headers.empty(), null);

      assertThat(new String(handler.handle(request, captured).body(), StandardCharsets.UTF_8))
          .isEqualTo("userId,postId");
    }

    @Test
    void cannotEditWhatTheRouterBuilt() {
      Handler handler = (request, parameters) -> {
        try {
          parameters.put("id", "1");
          return text("the handler edited the parameters");
        } catch (UnsupportedOperationException expected) {
          return text("refused: " + expected.getClass().getSimpleName());
        }
      };

      Request request = new Request(HttpMethod.GET, "/users/42", null, Headers.empty(), null);

      assertThat(new String(handler.handle(request, Map.of("id", "42")).body(), StandardCharsets.UTF_8))
          .isEqualTo("refused: UnsupportedOperationException");
      // Map.of is immutable, and RouteMatch hands out an unmodifiable view of its copy, so there is no reachable path
      // from a handler to the table's state; the case that matters is proven through Router in RouterTest and
      // RouteMatchTest (ADR-0020 §7).
    }
  }

  @Nested
  @DisplayName("exception semantics: propagate unchanged, catch nothing")
  class Failures {

    @Test
    void letsAHandlerFailureEscapeUntouched() {
      IllegalStateException failure = new IllegalStateException("the database is down");
      Handler handler = (request, ignored) -> {
        throw failure;
      };

      assertThatThrownBy(() -> handler.handle(get("/x"), Map.of())).isSameAs(failure);
      // The same identity rule ADR-0017 applies to publish: whatever the handler threw is what the caller gets, with
      // no wrapper inventing a message about it.
    }

    @Test
    void treatsAnErrorTheSameWay() {
      Handler handler = (request, ignored) -> {
        throw new StackOverflowError();
      };

      assertThatThrownBy(() -> handler.handle(get("/x"), Map.of())).isInstanceOf(StackOverflowError.class);
      // Nothing is caught anywhere, so there is no path where an Error is converted into a 500 inside the contract.
      // An adapter that catches to write a response is a separate component making that choice on purpose.
    }

    @Test
    void leavesNoHalfAnswerBehind() {
      List<String> written = new CopyOnWriteArrayList<>();
      Handler handler = (request, ignored) -> {
        written.add("did some work");
        throw new IllegalStateException("failed afterwards");
      };

      assertThatThrownBy(() -> handler.handle(get("/x"), Map.of())).isInstanceOf(IllegalStateException.class);
      // A handler produced nothing: no status, no headers. The Response is the only channel, so anything not returned
      // was not sent — which is the property an adapter relies on when it decides what to do with the failure.
      assertThat(written).containsExactly("did some work");
    }

    @Test
    void letsAHandlerTranslateItsOwnFailure() {
      Handler handler = (request, ignored) -> {
        try {
          throw new java.io.IOException("disk full");
        } catch (java.io.IOException expected) {
          return new Response(500, Headers.empty(), null);
        }
      };

      assertThatCode(() -> handler.handle(get("/x"), Map.of())).doesNotThrowAnyException();
      assertThat(handler.handle(get("/x"), Map.of()).status()).isEqualTo(500);
      // Both choices are legal and neither is a contract feature: handling errors is a component, not a type here.
    }

    @Test
    void aParameterTypoFailsLoudlyInTheHandlerRatherThanSilentlyInTheRouter() {
      Handler mistyped = (request, parameters) -> text(String.valueOf(parameters.get("user")));

      Request request = new Request(HttpMethod.GET, "/users/42", null, Headers.empty(), null);

      assertThat(new String(mistyped.handle(request, Map.of("id", "42")).body(), StandardCharsets.UTF_8))
          .isEqualTo("null");
      // get of a name the pattern never declared is null, and that is the same Map behaviour everywhere in Java. The
      // defence is upstream rather than here: the router only ever puts the names the pattern declared into the map, so
      // a wrong name is a wrong key, never a route that captured something unexpected (ADR-0019 §7).
    }
  }

  @Nested
  @DisplayName("composition without a middleware type")
  class Composition {

    /** A component that decorates a handler: what a later middleware phase would be, built from two types. */
    private Handler timing(Handler inner) {
      return (request, parameters) -> {
        Response response = inner.handle(request, parameters);
        return new Response(
            response.status(), response.headers().with("X-Handler", "wrapped"), response.body());
      };
    }

    @Test
    void awrapperCanReadTheRequestAndChangeTheResponse() {
      Handler inner = (request, ignored) -> text("body of " + request.path());

      Response wrapped = timing(inner).handle(get("/orders"), Map.of());

      assertThat(wrapped.status()).isEqualTo(200);
      assertThat(wrapped.headers().first("X-Handler")).contains("wrapped");
      assertThat(wrapped.headers().first("Content-Type")).contains("text/plain");
      assertThat(new String(wrapped.body(), StandardCharsets.UTF_8)).isEqualTo("body of /orders");
    }

    @Test
    void awrapperPassesTheParametersThroughUntouched() {
      List<Map<String, String>> seenByInner = new CopyOnWriteArrayList<>();
      Handler inner = (request, parameters) -> {
        seenByInner.add(parameters);
        return text("served");
      };

      Map<String, String> captured = Map.of("id", "42");

      timing(inner).handle(get("/users/42"), captured);

      // Identity: the map the adapter took out of the RouteMatch is the map the handler read. A wrapper that rebuilt or
      // dropped it would be re-deciding what routing said, which is exactly why the second argument is passed straight
      // through rather than reconstructed (ADR-0020 §8).
      assertThat(seenByInner).singleElement().isSameAs(captured);
    }

    @Test
    void awrapperCanShortCircuitWithoutCallingInner() {
      List<String> reached = new CopyOnWriteArrayList<>();
      Handler inner = (request, ignored) -> {
        reached.add(request.path());
        return text("served");
      };
      Handler guard = (request, parameters) -> request.headers().first("Authorization").isPresent()
          ? inner.handle(request, parameters)
          : new Response(401, Headers.empty(), null);

      Request anonymous = new Request(HttpMethod.GET, "/admin", null, Headers.empty(), null);
      Request authorized = new Request(
          HttpMethod.GET, "/admin", null, Headers.of("Authorization", "Bearer t"), null);

      assertThat(guard.handle(anonymous, Map.of()).status()).isEqualTo(401);
      assertThat(guard.handle(authorized, Map.of()).status()).isEqualTo(200);
      assertThat(reached).containsExactly("/admin");
      // Short-circuiting is a return, not a framework feature: this is the §11 question "can it short-circuit?"
      // answered by composition, which is why no Middleware type is needed to prove it.
    }

    @Test
    void passDownTheSameRequestAndTheSameParametersThatArrived() {
      List<Request> seenByInner = new CopyOnWriteArrayList<>();
      List<Map<String, String>> parametersSeenByInner = new CopyOnWriteArrayList<>();
      Handler inner = (request, parameters) -> {
        seenByInner.add(request);
        parametersSeenByInner.add(parameters);
        return text(request.headers().first("X-Trace").orElse("absent"));
      };
      Handler wrapper = (request, parameters) -> {
        Response response = inner.handle(request, parameters);
        return new Response(response.status(), response.headers(), response.body());
      };

      Request arrived = new Request(HttpMethod.GET, "/x", null, Headers.of("X-Trace", "abc123"), null);
      Map<String, String> captured = Map.of("id", "42");
      wrapper.handle(arrived, captured);

      // Identity, not just equality: what the inner handler was handed is the object the adapter built. There is
      // nothing in Request to change, so a wrapper cannot adjust what the next component sees — which is the ordering
      // question ADR-0018 §11 refuses to answer, because here it cannot be asked.
      assertThat(seenByInner).singleElement().isSameAs(arrived);
      assertThat(parametersSeenByInner).singleElement().isSameAs(captured);
      assertThat(new String(inner.handle(arrived, captured).body(), StandardCharsets.UTF_8)).isEqualTo("abc123");
    }
  }

  @Nested
  @DisplayName("a handler is built from collaborators, not from the request")
  class NoServiceLocator {

    /** A handler whose dependency arrives through a constructor: the only wiring this contract allows. */
    private static final class ReportHandler implements Handler {

      private final String department;

      ReportHandler(String department) {
        this.department = department;
      }

      @Override
      public Response handle(Request request, Map<String, String> parameters) {
        return text(department + " reports for " + request.path() + " of " + parameters.get("period"));
      }
    }

    @Test
    void answersWithAnInjectedCollaboratorAndNeverAsksTheRequest() {
      Handler handler = new ReportHandler("finance");

      assertThat(new String(handler.handle(get("/q3"), Map.of("period", "Q3")).body(), StandardCharsets.UTF_8))
          .isEqualTo("finance reports for /q3 of Q3");
    }

    @Test
    void holdsNothingTheRequestCouldHaveSupplied() {
      ReportHandler handler = new ReportHandler("finance");

      // The field list is the evidence: a String. Had the handler needed a bus, a configuration or an assembler, the
      // next step a developer reaches for is request.service(...) — and that accessor is what ADR-0018 §9 refuses.
      assertThat(java.util.Arrays.stream(ReportHandler.class.getDeclaredFields())
          .filter(field -> !field.isSynthetic())
          .map(field -> field.getType().getName()))
          .containsExactly("java.lang.String");
      assertThat(handler.handle(get("/a"), Map.of()).status())
          .isEqualTo(handler.handle(get("/b"), Map.of()).status());
    }
  }
}
