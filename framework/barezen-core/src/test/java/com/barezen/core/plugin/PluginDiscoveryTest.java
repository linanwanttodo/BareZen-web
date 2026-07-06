package com.barezen.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What discovery finds, what it refuses to decide, and what it does not keep.
 *
 * <p>ADR-0048's claims in executable form. The two fixtures are announced by
 * {@code src/test/resources/META-INF/services/com.barezen.core.plugin.Plugin}, so the primary tests run against a real
 * provider configuration rather than a mock of one — a mocked {@code ServiceLoader} would prove the code calls an API and
 * not that a plugin jar actually arrives.
 */
class PluginDiscoveryTest {

  private static URLClassLoader loaderOver(Path directory) throws IOException {
    return new URLClassLoader("barezen-discovery-test",
        new URL[] {directory.toUri().toURL()},
        PluginDiscovery.class.getClassLoader());
  }

  private static Plugin pluginNamed(String id) {
    return () -> new PluginDescriptor(id, List.of());
  }

  private static List<String> idsOf(List<? extends Plugin> plugins) {
    return plugins.stream().map(plugin -> plugin.descriptor().id()).toList();
  }

  @Nested
  @DisplayName("the search")
  class Search {

    @Test
    void announcedProvidersComeBackInProviderConfigurationOrder() {
      assertThat(idsOf(PluginDiscovery.discover())).containsExactly("echo", "status");
    }

    @Test
    void aProviderNeedsAPublicNoArgumentConstructorWhichIsWhyItNeedsNoCollaborator() throws Exception {
      // The limit ADR-0048 states rather than hides: ServiceLoader instantiates through the no-arg constructor, so a
      // plugin that needs a DataSource or a SessionStore cannot be a provider and stays the application's to build.
      assertThat(DiscoveredEchoPlugin.class.getDeclaredConstructors()).hasSize(1);
      assertThat(DiscoveredEchoPlugin.class.getDeclaredConstructors()[0].getParameterCount()).isZero();
      assertThat(DiscoveredStatusPlugin.class.getDeclaredConstructors()[0].getParameterCount()).isZero();
    }

    @Test
    void theDiscoveredGraphOrdersLikeAnyOther() {
      // The fixture status declares a dependency on echo, so ADR-0041's validator has a real edge to honour.
      assertThat(idsOf(PluginRegistration.validate(PluginDiscovery.discover()))).containsExactly("echo", "status");
    }

    @Test
    void aDiscoveredPluginIsTheInterfaceTheApplicationAlreadyHolds() {
      assertThat(PluginDiscovery.discover()).allSatisfy(plugin -> {
        assertThat(plugin).isInstanceOf(Plugin.class);
        assertThat(plugin.getClass().getClassLoader())
            .isSameAs(PluginDiscovery.class.getClassLoader());
      });
    }
  }

  @Nested
  @DisplayName("discovery decides nothing and keeps nothing")
  class Limits {

    @Test
    void everyCallReadsTheClasspathAgainSoNothingIsStored() {
      List<Plugin> first = PluginDiscovery.discover();
      List<Plugin> second = PluginDiscovery.discover();

      // Two calls, two sets of instances. A cached set would be the registry ADR-0006 and ADR-0016 §9 refuse, and it
      // would also let somebody ask «give me the auth plugin», which this type cannot answer.
      assertThat(idsOf(first)).isEqualTo(idsOf(second));
      assertThat(first.get(0)).isNotSameAs(second.get(0));
    }

    @Test
    void discoveryRunsNoLifecyclePhaseOnWhatItFound() {
      DiscoveredEchoPlugin plugin = (DiscoveredEchoPlugin) PluginDiscovery.discover().get(0);

      // initialize() and start() belong to the orchestrator (ADR-0003); arriving from a jar is not a phase.
      assertThat(plugin.phases()).isEmpty();
    }

    @Test
    void theReturnedListIsImmutable() {
      List<Plugin> discovered = PluginDiscovery.discover();

      assertThatThrownBy(() -> discovered.add(pluginNamed("late")))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aJarAndANewMayNotBothClaimOneId() {
      List<Plugin> merged = new ArrayList<>(PluginDiscovery.discover());
      merged.add(pluginNamed("echo"));

      assertThatThrownBy(() -> PluginRegistration.validate(merged))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("echo");
    }

    @Test
    void aNullLoaderIsRefusedRatherThanSilentlyReplacedByTheContextLoader() {
      assertThatThrownBy(() -> PluginDiscovery.discover(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("a caller-supplied loader")
  class Loaders {

    @Test
    void aLoaderThatNamesNoProvidersIsAnEmptyResultAndNotAnError() throws IOException {
      // Parent is the platform loader, so this class loader cannot see the test classpath's services file at all.
      try (URLClassLoader isolated = new URLClassLoader("barezen-empty-test", new URL[0],
          ClassLoader.getPlatformClassLoader())) {

        assertThat(PluginDiscovery.discover(isolated)).isEmpty();
      }
    }

    @Test
    void aBrokenProviderFailsTheCallRatherThanBeingSkipped(@TempDir Path directory) throws IOException {
      Path services = directory.resolve("META-INF/services");
      Files.createDirectories(services);
      Files.writeString(services.resolve(Plugin.class.getName()), "com.barezen.AbsentProvider\n");

      // Booting an application that quietly lacks an integration is the failure nobody can debug (ADR-0004 keeps the
      // original cause, and here the JDK's own message names the provider).
      try (URLClassLoader loader = loaderOver(directory)) {
        assertThatThrownBy(() -> PluginDiscovery.discover(loader))
            .isInstanceOf(ServiceConfigurationError.class)
            .hasMessageContaining("com.barezen.AbsentProvider");
      }
    }
  }
}
