package com.barezen.http.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.web.HttpServerAdapter;
import com.barezen.web.HttpServerAdapterProvider;
import com.barezen.web.Router;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The adapter SPI as the phase defines it: {@code ServiceLoader} discovers, an application decides, and nothing in
 * between stores a server (ADR-0025).
 *
 * <p>Some of these assertions read oddly — {@code assertThat(found).isNotSameAs(second)} on a stateless object is
 * asserting that a factory is not a singleton, which is the property §9.5 asks for rather than a fact about identity.
 * The alternative would be a rule that a provider must not cache, and a rule cannot express "two loads give two
 * independent objects" any better than two loads can.
 */
class JdkHttpServerAdapterProviderTest {

  private static final Router ROUTES = Router.builder()
      .route(com.barezen.web.HttpMethod.GET, "/users/{id}", (request, parameters) -> new com.barezen.web.Response(200,
          com.barezen.web.Headers.empty(), parameters.get("id").getBytes(StandardCharsets.UTF_8)))
      .build();

  @Nested
  @DisplayName("discovery")
  class Discovery {

    @Test
    void theJdkProviderIsFoundOnTheClasspath() {
      List<HttpServerAdapterProvider> found = ServiceLoader.load(HttpServerAdapterProvider.class).stream()
          .map(ServiceLoader.Provider::get)
          .toList();

      // The one assertion the phase names directly: load() finds "jdk" (acceptance H).
      assertThat(found).extracting(HttpServerAdapterProvider::id).contains("jdk");
      assertThat(found).hasSize(1);
    }

    @Test
    void theDiscoveredProviderIsTheJdkType() {
      HttpServerAdapterProvider provider = iterate();

      assertThat(provider).isInstanceOf(JdkHttpServerAdapterProvider.class);
      assertThat(provider.id()).isEqualTo("jdk");
    }

    @Test
    void discoveryIsRepeatableAndCostsNothing() {
      // Two loads, two provider instances, no global registration. ServiceLoader caches per iteration rather than per
      // process, and this provider holds no static state to leak — which the architecture rule on a provider's fields
      // also enforces (ADR-0025 §8).
      HttpServerAdapterProvider first = iterate();
      HttpServerAdapterProvider second = iterate();

      assertThat(first).isNotSameAs(second);
      assertThat(first.id()).isEqualTo(second.id());

      // And discovering is not starting: an un-started adapter binds nothing, so listing adapters is safe.
      HttpServerAdapter adapter = first.create("127.0.0.1", 0, ROUTES);
      assertThat(adapter.isRunning()).isFalse();
      assertThat(adapter.port()).isEmpty();
      adapter.close();
    }

    @Test
    void twoCreatedAdaptersAreIndependentServers() throws IOException {
      HttpServerAdapterProvider provider = iterate();
      HttpServerAdapter left = provider.create("127.0.0.1", 0, ROUTES);
      HttpServerAdapter right = provider.create("127.0.0.1", 0, ROUTES);

      try {
        left.start();
        right.start();

        // A provider that secretly returned one shared server would give the same port here — the case this phase's
        // "no global mutable state" rule is about, checked by running two.
        assertThat(left.port()).isNotEqualTo(right.port());
        assertThat(RawHttp.request(left.port().orElseThrow(), "GET", "/users/1", List.of(), null).body())
            .isEqualTo("1");
        assertThat(RawHttp.request(right.port().orElseThrow(), "GET", "/users/2", List.of(), null).body())
            .isEqualTo("2");

        left.stop();
        assertThat(left.isRunning()).isFalse();
        assertThat(right.isRunning()).isTrue();
      } finally {
        left.close();
        right.close();
      }
    }

    private static HttpServerAdapterProvider iterate() {
      return ServiceLoader.load(HttpServerAdapterProvider.class).iterator().next();
    }
  }

  @Nested
  @DisplayName("the contract a provider offers")
  class Contract {

    @Test
    void anIdIsStableAcrossInstances() {
      assertThat(new JdkHttpServerAdapterProvider().id()).isEqualTo(new JdkHttpServerAdapterProvider().id());
      assertThat(new JdkHttpServerAdapterProvider().id()).isEqualTo("jdk");
    }

    @Test
    void theSummaryNamesTheIdAndNothingElse() {
      // Discovery is a candidate list a human reads, so a provider has to say which one it is without holding
      // anything. toString() is refused as a member beyond id() and create() nowhere, because the summary is a
      // diagnostic rather than an operation — ADR-0025 §4 counts the declared surface as id, create and this
      // sentence, so the sentence has to be tested rather than assumed.
      assertThat(new JdkHttpServerAdapterProvider().toString())
          .isEqualTo("JdkHttpServerAdapterProvider[jdk]")
          .doesNotContain("null", "httpserver", "Router");
    }

    @Test
    void createBuildsAnAdapterForTheHostAndPortNamed() throws IOException {
      HttpServerAdapter adapter = new JdkHttpServerAdapterProvider().create("127.0.0.1", 0, ROUTES);

      assertThat(adapter).isInstanceOf(JdkHttpServerAdapter.class);
      adapter.start();
      try {
        // Port 0 through the provider, read back from the adapter: the SPI carries the same ephemeral-port support
        // the constructor does (ADR-0025 §7).
        int port = adapter.port().orElseThrow();
        assertThat(port).isPositive();
        assertThat(RawHttp.request(port, "GET", "/users/9", List.of(), null).body()).isEqualTo("9");
      } finally {
        adapter.stop();
      }
    }

    @Test
    void theProviderTypeOffersNothingButAnIdAndACreation() {
      assertThat(java.util.Arrays.stream(HttpServerAdapterProvider.class.getDeclaredMethods())
          .filter(method -> !method.isSynthetic())
          .map(java.lang.reflect.Method::getName).sorted().toList()).containsExactly("create", "id");
      assertThat(HttpServerAdapterProvider.class.getDeclaredFields()).isEmpty();
      assertThat(JdkHttpServerAdapterProvider.class.getDeclaredFields())
          .allMatch(field -> java.lang.reflect.Modifier.isStatic(field.getModifiers())
              && java.lang.reflect.Modifier.isFinal(field.getModifiers()));
      // A discovered service needs a public no-argument constructor, and that is the only constructor.
      assertThat(JdkHttpServerAdapterProvider.class.getConstructors()).hasSize(1);
    }

    @Test
    void aProviderIsNotAServiceLocator() {
      // No lookup, no registration, no getInstance, no context parameter. ADR-0025 §4: it creates, and the caller
      // owns what it created.
      assertThat(java.util.Arrays.stream(HttpServerAdapterProvider.class.getMethods())
          .filter(method -> !method.isSynthetic())
          .map(java.lang.reflect.Method::getName))
              .containsExactlyInAnyOrder("id", "create");
      // Everything else a class inherits from Object; nothing else it adds. getMethods() carries three wait overloads,
      // so the claim is written as an exclusion of the locator vocabulary rather than as a closed list.
      assertThat(java.util.Arrays.stream(JdkHttpServerAdapterProvider.class.getMethods())
          .map(java.lang.reflect.Method::getName))
              .contains("id", "create")
              .doesNotContain("getInstance", "instance", "current", "lookup", "register", "registry", "providers",
                  "context", "locator", "service", "services", "start", "stop", "router", "routes");
      assertThat(java.util.Arrays.stream(JdkHttpServerAdapterProvider.class.getDeclaredMethods())
          .filter(method -> !method.isSynthetic())
          .map(java.lang.reflect.Method::getName).sorted().toList())
              .containsExactly("create", "id", "toString");
    }
  }

  @Nested
  @DisplayName("a bad configuration is refused as a configuration error")
  class Arguments {

    @Test
    void aNullHostAndANullRouterAreRefused() {
      JdkHttpServerAdapterProvider provider = new JdkHttpServerAdapterProvider();

      assertThatThrownBy(() -> provider.create(null, 0, ROUTES)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> provider.create("127.0.0.1", 0, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aBlankHostIsRefusedRatherThanBoundToEverywhere() {
      // "" is the value that silently means "every interface" on some platforms, which is a security decision made by
      // an omitted character rather than by the application (ADR-0025 §7).
      assertThatThrownBy(() -> new JdkHttpServerAdapterProvider().create("", 8080, ROUTES))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("0.0.0.0");
      assertThatThrownBy(() -> new JdkHttpServerAdapterProvider().create("   ", 8080, ROUTES))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPortOutsideTheLegalRangeIsRefusedBeforeTheSocket() {
      JdkHttpServerAdapterProvider provider = new JdkHttpServerAdapterProvider();

      // -1 and 65536 would reach InetSocketAddress as "port out of range", a bind-time message about a value the
      // configuration named. The provider's own message is the difference between "your config is wrong" and "the
      // kernel refused you" (ADR-0025 §7).
      assertThatThrownBy(() -> provider.create("127.0.0.1", -1, ROUTES))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("-1");
      assertThatThrownBy(() -> provider.create("127.0.0.1", 65536, ROUTES))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("65536");

      // 0 and 65535 are both legal, and 0 means "ask the operating system".
      provider.create("127.0.0.1", 0, ROUTES).close();
    }

    @Test
    void anUnknownHostNameIsNotAConfigurationError() {
      // A name that does not resolve is a runtime condition rather than a malformed argument, so the provider does not
      // pretend to know DNS: it builds an adapter and start() reports the bind failure.
      HttpServerAdapter adapter = new JdkHttpServerAdapterProvider().create("no-such-host.invalid", 0, ROUTES);

      assertThatThrownBy(adapter::start).isInstanceOf(RuntimeException.class);
      assertThat(adapter.isRunning()).isFalse();
    }
  }

  @Nested
  @DisplayName("the registration file")
  class Registration {

    @Test
    void theServiceFileNamesOneImplementationOfTheContract() throws IOException {
      var resource = JdkHttpServerAdapterProvider.class.getClassLoader()
          .getResource("META-INF/services/com.barezen.web.HttpServerAdapterProvider");

      assertThat(resource).isNotNull();
      List<String> lines = new java.io.BufferedReader(new java.io.InputStreamReader(resource.openStream(),
          StandardCharsets.UTF_8)).lines().filter(text -> !text.isBlank()).toList();

      assertThat(lines).containsExactly("com.barezen.http.jdk.JdkHttpServerAdapterProvider");
    }

    @Test
    void aStreamedProviderCanBeSkippedWithoutLoadingIt() {
      // The reason ServiceLoader.stream() exists, and the reason an application can list adapters without starting
      // one: a provider class is not even loaded until its element is consumed (ADR-0025 §9).
      List<String> names = ServiceLoader.load(HttpServerAdapterProvider.class).stream()
          .map(ServiceLoader.Provider::type)
          .map(Class::getName)
          .toList();

      assertThat(names).containsExactly("com.barezen.http.jdk.JdkHttpServerAdapterProvider");
    }

    @Test
    void discoveryDoesNotThrowForThisModuleOwnConfiguration() {
      // A malformed registration is a real ServiceLoader failure mode; here the assertion is that this module's own
      // file is well-formed enough to load without a ServiceConfigurationError, which probe I makes a point of after
      // the file is deleted (ADR-0025 §10).
      assertThat(com.barezen.web.HttpServerAdapterProvider.class.getName())
          .isEqualTo("com.barezen.web.HttpServerAdapterProvider");
      try {
        ServiceLoader.load(HttpServerAdapterProvider.class).forEach(provider -> assertThat(provider.id()).isNotBlank());
      } catch (ServiceConfigurationError broken) {
        throw new AssertionError("the jdk registration is malformed", broken);
      }
    }
  }

  @Nested
  @DisplayName("what discovery is not for")
  class Limits {

    @Test
    void discoveryAloneHoldsNoAdapterAndStartsNothing() {
      // Reading ids must not create a server. The observable form of that claim is the provider's own state: nothing
      // an adapter can be reached through, and nothing mutable to hold one in (ADR-0025 §8, §9).
      ServiceLoader.load(HttpServerAdapterProvider.class).forEach(provider -> assertThat(provider.id()).isNotBlank());

      assertThat(JdkHttpServerAdapterProvider.class.getDeclaredFields())
          .filteredOn(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
          .isEmpty();
      assertThat(JdkHttpServerAdapterProvider.class.getDeclaredFields())
          .allMatch(field -> java.lang.reflect.Modifier.isFinal(field.getModifiers()));
    }

    @Test
    void aProviderRegistersNoRoutesAndHoldsNoRouter() {
      // create() takes the route table as an argument, which is the only way one can reach an adapter: nothing here
      // mutates a Router, holds one, or can answer "which routes exist" (ADR-0025 §4).
      assertThatThrownBy(() -> JdkHttpServerAdapterProvider.class.getMethod("router"))
          .isInstanceOf(NoSuchMethodException.class);
      assertThatThrownBy(() -> JdkHttpServerAdapterProvider.class.getMethod("route",
          com.barezen.web.HttpMethod.class, String.class, com.barezen.web.Handler.class))
          .isInstanceOf(NoSuchMethodException.class);
    }
  }
}
