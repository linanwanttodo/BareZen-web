package com.barezen.http.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What an application can reach from this module, counted.
 *
 * <p>The phase's public-API discipline in one number: one class, one declared public method, two constants. Anything
 * larger needs a consumer today, which is the standard every module in this build holds itself to (ADR-0018 §16,
 * ADR-0049 §Decision 6). The bridge is package-private on purpose for the same reason {@code JdkExchangeBridge} is: a
 * second public way to build a {@code Request} or write a {@code Response} is a way around the contract's own
 * validation (ADR-0022 §9).
 */
class BarezenServletSurfaceTest {

  @Nested
  @DisplayName("BarezenServlet")
  class Servlet {

    @Test
    void oneDeclaredPublicMethodAndTwoConstants() {
      assertThat(Arrays.stream(BarezenServlet.class.getDeclaredMethods())
          .filter(method -> Modifier.isPublic(method.getModifiers()))
          .map(Method::getName)
          .distinct())
          .containsExactly("init");

      assertThat(Arrays.stream(BarezenServlet.class.getDeclaredFields())
          .filter(field -> Modifier.isPublic(field.getModifiers()))
          .map(Field::getName)
          .distinct())
          .containsExactlyInAnyOrder("ROUTER_ATTRIBUTE", "MAX_REQUEST_BODY_BYTES_PARAM");
    }

    @Test
    void bothConstantsAreFinalStringsApplicationsCanName() throws Exception {
      for (String name : new String[] {"ROUTER_ATTRIBUTE", "MAX_REQUEST_BODY_BYTES_PARAM"}) {
        Field field = BarezenServlet.class.getField(name);
        assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        assertThat(field.getType()).isEqualTo(String.class);
      }
      assertThat(BarezenServlet.ROUTER_ATTRIBUTE).isEqualTo("com.barezen.http.servlet.router");
    }

    @Test
    void theDispatcherStaysProtectedBecauseTheContainerCallsIt() throws Exception {
      Method service = BarezenServlet.class.getDeclaredMethod("service",
          jakarta.servlet.http.HttpServletRequest.class, jakarta.servlet.http.HttpServletResponse.class);

      // Protected, as HttpServlet declares it: the container invokes it, and an application that wants different
      // behaviour overrides it. Public would be a second way to run a request outside the container's filter chain.
      assertThat(Modifier.isProtected(service.getModifiers())).isTrue();
      assertThat(Modifier.isPublic(service.getModifiers())).isFalse();
    }

    @Test
    void theServletExtendsNothingElseTheContainerKnows() {
      assertThat(BarezenServlet.class.getSuperclass().getName())
          .isEqualTo("jakarta.servlet.http.HttpServlet");
      assertThat(BarezenServlet.class.getInterfaces()).isEmpty();
    }

    @Test
    void bothConstructionPathsArePublic() throws Exception {
      assertThat(BarezenServlet.class.getConstructor().getModifiers()).isEqualTo(Modifier.PUBLIC);
      assertThat(BarezenServlet.class.getConstructors()).hasSize(2);
    }
  }

  @Nested
  @DisplayName("the bridge")
  class Bridge {

    @Test
    void theTranslationIsNotAnApi() {
      assertThat(Modifier.isPublic(ServletBridge.class.getModifiers())).isFalse();
      assertThat(ServletBridge.class.getDeclaredConstructors()).hasSize(1);
      assertThat(Modifier.isPrivate(ServletBridge.class.getDeclaredConstructors()[0].getModifiers())).isTrue();
    }

    @Test
    void theBodyLimitDefaultIsTheJdkAdaptersOwnBound() {
      // One mebibyte in both servers, so choosing a deployment model does not change how much content a client may
      // send (ADR-0031). Stated as a number here because a constant copied in two modules drifts silently.
      assertThat(ServletBridge.DEFAULT_MAX_REQUEST_BODY_BYTES).isEqualTo(1024L * 1024L);
    }
  }
}
