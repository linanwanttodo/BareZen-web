package com.barezen.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The handler signature: one request in, one response out, exceptions unhandled — and the composition that signature
 * makes possible without a middleware type.
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
    void declaresNoCheckedException() {
      assertThat(Handler.class.getDeclaredMethods()[0].getExceptionTypes()).isEmpty();
      // A checked exception would be caught or declared by every wrapper, and a wrapper has nothing better to do with
      // one than pass it on (ADR-0018 §8).
    }

    @Test
    void isUsableAsALambda() {
      Handler handler = request -> text("hi " + request.path());

      assertThat(handler.handle(get("/bob")).status()).isEqualTo(200);
    }

    @Test
    void isAnnotatedFunctionalInterface() {
      // Written as an annotation check rather than an "is it a lambda" inference, because the annotation is what makes
      // adding a second method a compile error rather than a breaking release.
      assertThat(Handler.class.isAnnotationPresent(FunctionalInterface.class)).isTrue();
    }
  }

  @Nested
  @DisplayName("request in, response out")
  class Dispatch {

    @Test
    void answersFromTheRequestItWasGiven() {
      Handler handler = request -> new Response(
          200,
          Headers.of("Content-Type", "text/plain"),
          request.headers().first("X-Name").orElse("anonymous").getBytes(StandardCharsets.UTF_8));

      Request named = new Request(
          HttpMethod.GET, "/greet", null, Headers.of("X-Name", "zen"), null);

      assertThat(new String(handler.handle(named).body(), StandardCharsets.UTF_8)).isEqualTo("zen");
      assertThat(new String(handler.handle(get("/greet")).body(), StandardCharsets.UTF_8)).isEqualTo("anonymous");
    }

    @Test
    void readsTheRequestThroughTheContractOnly() {
      // A handler that can answer needs nothing beyond method, path, query, headers and body — which is the evidence
      // that the type carries no server reference.
      Handler handler = request -> {
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

      assertThat(new String(handler.handle(request).body(), StandardCharsets.UTF_8))
          .isEqualTo("DELETE /orders/7?force=true 1 fields 4 bytes");
    }

    @Test
    void answersWithoutTouchingAnyMutableState() {
      Handler handler = request -> new Response(
          request.path().equals("/found") ? 200 : 404, Headers.empty(), null);

      assertThat(handler.handle(get("/found")).status()).isEqualTo(200);
      assertThat(handler.handle(get("/gone")).status()).isEqualTo(404);
      // 404 is a response, not an exception: the contract has no error type, so a handler chooses the status.
    }
  }

  @Nested
  @DisplayName("exception semantics: propagate unchanged, catch nothing")
  class Failures {

    @Test
    void letsAHandlerFailureEscapeUntouched() {
      IllegalStateException failure = new IllegalStateException("the database is down");
      Handler handler = request -> {
        throw failure;
      };

      assertThatThrownBy(() -> handler.handle(get("/x"))).isSameAs(failure);
      // The same identity rule ADR-0017 applies to publish: whatever the handler threw is what the caller gets, with
      // no wrapper inventing a message about it.
    }

    @Test
    void treatsAnErrorTheSameWay() {
      Handler handler = request -> {
        throw new StackOverflowError();
      };

      assertThatThrownBy(() -> handler.handle(get("/x"))).isInstanceOf(StackOverflowError.class);
      // Nothing is caught anywhere, so there is no path where an Error is converted into a 500 inside the contract.
      // An adapter that catches to write a response is a separate component making that choice on purpose.
    }

    @Test
    void leavesNoHalfAnswerBehind() {
      List<String> written = new CopyOnWriteArrayList<>();
      Handler handler = request -> {
        written.add("did some work");
        throw new IllegalStateException("failed afterwards");
      };

      assertThatThrownBy(() -> handler.handle(get("/x"))).isInstanceOf(IllegalStateException.class);
      // A handler produced nothing: no status, no headers. The Response is the only channel, so anything not returned
      // was not sent — which is the property an adapter relies on when it decides what to do with the failure.
      assertThat(written).containsExactly("did some work");
    }

    @Test
    void letsAHandlerTranslateItsOwnFailure() {
      Handler handler = request -> {
        try {
          throw new java.io.IOException("disk full");
        } catch (java.io.IOException expected) {
          return new Response(500, Headers.empty(), null);
        }
      };

      assertThatCode(() -> handler.handle(get("/x"))).doesNotThrowAnyException();
      assertThat(handler.handle(get("/x")).status()).isEqualTo(500);
      // Both choices are legal and neither is a contract feature: handling errors is a component, not a type here.
    }
  }

  @Nested
  @DisplayName("composition without a middleware type")
  class Composition {

    /** A component that decorates a handler: what a later middleware phase would be, built from two types. */
    private Handler timing(Handler inner) {
      return request -> {
        Response response = inner.handle(request);
        return new Response(
            response.status(), response.headers().with("X-Handler", "wrapped"), response.body());
      };
    }

    @Test
    void awrapperCanReadTheRequestAndChangeTheResponse() {
      Handler inner = request -> text("body of " + request.path());

      Response wrapped = timing(inner).handle(get("/orders"));

      assertThat(wrapped.status()).isEqualTo(200);
      assertThat(wrapped.headers().first("X-Handler")).contains("wrapped");
      assertThat(wrapped.headers().first("Content-Type")).contains("text/plain");
      assertThat(new String(wrapped.body(), StandardCharsets.UTF_8)).isEqualTo("body of /orders");
    }

    @Test
    void awrapperCanShortCircuitWithoutCallingInner() {
      List<String> reached = new CopyOnWriteArrayList<>();
      Handler inner = request -> {
        reached.add(request.path());
        return text("served");
      };
      Handler guard = request -> request.headers().first("Authorization").isPresent()
          ? inner.handle(request)
          : new Response(401, Headers.empty(), null);

      Request anonymous = new Request(HttpMethod.GET, "/admin", null, Headers.empty(), null);
      Request authorized = new Request(
          HttpMethod.GET, "/admin", null, Headers.of("Authorization", "Bearer t"), null);

      assertThat(guard.handle(anonymous).status()).isEqualTo(401);
      assertThat(guard.handle(authorized).status()).isEqualTo(200);
      assertThat(reached).containsExactly("/admin");
      // Short-circuiting is a return, not a framework feature: this is the §11 question "can it short-circuit?"
      // answered by composition, which is why no Middleware type is needed to prove it.
    }

    @Test
    void passDownTheSameRequestThatArrived() {
      List<Request> seenByInner = new CopyOnWriteArrayList<>();
      Handler inner = request -> {
        seenByInner.add(request);
        return text(request.headers().first("X-Trace").orElse("absent"));
      };
      Handler wrapper = request -> {
        Response response = inner.handle(request);
        return new Response(response.status(), response.headers(), response.body());
      };

      Request arrived = new Request(HttpMethod.GET, "/x", null, Headers.of("X-Trace", "abc123"), null);
      wrapper.handle(arrived);

      // Identity, not just equality: what the inner handler was handed is the object the adapter built. There is
      // nothing in Request to change, so a wrapper cannot adjust what the next component sees — which is the ordering
      // question ADR-0018 §11 refuses to answer, because here it cannot be asked.
      assertThat(seenByInner).singleElement().isSameAs(arrived);
      assertThat(new String(inner.handle(arrived).body(), StandardCharsets.UTF_8)).isEqualTo("abc123");
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
      public Response handle(Request request) {
        return text(department + " reports for " + request.path());
      }
    }

    @Test
    void answersWithAnInjectedCollaboratorAndNeverAsksTheRequest() {
      Handler handler = new ReportHandler("finance");

      assertThat(new String(handler.handle(get("/q3")).body(), StandardCharsets.UTF_8))
          .isEqualTo("finance reports for /q3");
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
      assertThat(handler.handle(get("/a")).status()).isEqualTo(handler.handle(get("/b")).status());
    }
  }
}
