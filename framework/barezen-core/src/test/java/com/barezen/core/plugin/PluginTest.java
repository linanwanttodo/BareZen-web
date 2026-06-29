package com.barezen.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.core.concurrent.ThreadPolicy;
import com.barezen.core.kernel.ApplicationState;
import com.barezen.core.kernel.LifecycleOrchestrator;
import com.barezen.core.kernel.LifecycleParticipant;
import com.barezen.core.kernel.StartupException;
import com.barezen.core.runtime.RuntimeCapabilities;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The plugin SPI, tested against the ways a plugin concept goes wrong.
 *
 * <p>Every framework with plugins eventually grows one of two things: a context object components can look things up
 * from, or a second lifecycle running alongside the real one. Both arrive as small, sensible additions - an
 * {@code install()} here, a {@code context()} parameter there - so what is checked here is the shape of the SPI, not
 * its behaviour. Behaviour during startup and shutdown belongs to
 * {@link LifecycleOrchestrator} and is covered in the {@code kernel} tests; what is new is the contract about what a
 * plugin may be given, and the guarantee that a plugin is driven through the one path every other component uses.
 */
class PluginTest {

  private final List<String> events = new ArrayList<>();

  /** A plugin that records every phase it is asked for. */
  private class RecordingPlugin implements Plugin {
    private final String id;
    private final List<String> dependencies;

    RecordingPlugin(String id) {
      this(id, List.of());
    }

    RecordingPlugin(String id, List<String> dependencies) {
      this.id = id;
      this.dependencies = dependencies;
    }

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor(id, dependencies);
    }

    @Override
    public void initialize() {
      events.add(id + ".initialize");
    }

    @Override
    public void start() {
      events.add(id + ".start");
    }

    @Override
    public void stop() {
      events.add(id + ".stop");
    }

    @Override
    public void abort() {
      events.add(id + ".abort");
    }
  }

  private static LifecycleOrchestrator.Builder orchestrator() {
    return LifecycleOrchestrator.builder()
        .threadPolicy(ThreadPolicy.PLATFORM)
        .runtimeCapabilities(new RuntimeCapabilities(17, false, "test"))
        .registerShutdownHook(false);
  }

  @Nested
  class Identity {

    @Test
    @DisplayName("the descriptor carries the id an application registers the plugin under")
    void theDescriptorIsThePluginsStableName() {
      Plugin auth = new RecordingPlugin("auth");

      assertThat(auth.descriptor().id()).isEqualTo("auth");
      assertThat(auth.descriptor().dependencies()).isEmpty();
    }

    @Test
    @DisplayName("an id is not optional")
    void aBlankIdIsRefused() {
      // A nameless plugin cannot be ordered against its dependencies, cannot be reported in a startup failure, and
      // cannot be registered under anything. The id is the minimum a plugin must be able to say about itself.
      assertThatThrownBy(() -> new PluginDescriptor(null, List.of()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be blank");
      assertThatThrownBy(() -> new PluginDescriptor("   ", List.of()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("a dependency is an id, not a null standing in for an optional one")
    void aMissingDependencyIdIsRefusedRatherThanTreatedAsOptional() {
      // This is the shape an "optional dependency" arrives as in practice: somebody writes null and calls it
      // flexibility. Refusing it is what keeps the absence of an optional-dependency engine (ADR-0016) from being
      // read as an invitation to smuggle one in as a null entry.
      assertThatThrownBy(() -> new PluginDescriptor("user", Arrays.asList("auth", null)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("null or blank");
      assertThatThrownBy(() -> new PluginDescriptor("user", Arrays.asList("auth", "  ")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("null or blank");

      assertThatThrownBy(() -> new PluginDescriptor("user", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("List.of()");
    }

    @Test
    @DisplayName("a plugin that declares itself a dependency is refused where it is stated")
    void aSelfDependencyIsRefused() {
      // No registration order satisfies this, and finding out later needs a graph walk. Two lines here refuse what
      // a resolver would otherwise have to detect.
      assertThatThrownBy(() -> new PluginDescriptor("auth", List.of("auth")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("before itself");
    }

    @Test
    @DisplayName("declared dependencies cannot change after the descriptor is handed over")
    void theDependencyListIsCopiedOnConstruction() {
      List<String> declared = new ArrayList<>();
      declared.add("auth");
      PluginDescriptor user = new PluginDescriptor("user", declared);

      declared.add("cache");

      // An application reads this list when it decides registration order. A descriptor is a statement made at
      // assembly time, not a live view that can disagree with the order the application already committed to.
      assertThat(user.dependencies()).containsExactly("auth");
      assertThatThrownBy(() -> user.dependencies().add("late"))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  class MinimalSurface {

    @Test
    @DisplayName("Plugin declares exactly one method and inherits every phase")
    void thePluginInterfaceAddsOnlyDescriptor() {
      // The API-clinic test, and the reason it is written as "declared methods are exactly this" rather than as a
      // list of forbidden names: install, uninstall, activate, deactivate and reload all arrive as obvious plugin
      // methods, and each one is a second lifecycle engine asking to be written (ADR-0003, ADR-0016).
      assertThat(Arrays.stream(Plugin.class.getDeclaredMethods())
          .filter(method -> !method.isSynthetic()))
          .extracting(java.lang.reflect.Method::getName)
          .containsExactly("descriptor");

      // The whole plugin vocabulary is one inherited interface: a plugin is a participant that can name itself.
      assertThat(Plugin.class.getInterfaces()).containsExactly(LifecycleParticipant.class);
    }

    @Test
    @DisplayName("no plugin phase is handed anything")
    void nothingIsPassedToAPluginAtAnyPhase() {
      // The strongest single guard against a context type: PluginContext does not fail to exist because somebody
      // forgot to forbid it by name, it cannot arrive as an argument to any phase without this failing. A
      // collaborator passed to a lifecycle method is a lookup surface with a launch date (ADR-0006).
      assertThat(Arrays.stream(Plugin.class.getMethods())
          .filter(method -> method.getDeclaringClass() != Object.class)
          .filter(method -> !method.isSynthetic())
          .flatMap(method -> Arrays.stream(method.getParameterTypes())))
          .isEmpty();
    }

    @Test
    @DisplayName("the descriptor speaks only in identity strings")
    void theDescriptorCarriesNoCapabilityHandle() {
      // Stated on the type rather than only in the ArchUnit rules, so a reader of this file sees what the boundary
      // is made of: two fields, both strings and lists of strings, nothing that reaches a container.
      assertThat(Arrays.stream(PluginDescriptor.class.getRecordComponents()))
          .extracting(java.lang.reflect.RecordComponent::getType)
          .containsExactly(String.class, List.class);

      assertThat(Arrays.stream(PluginDescriptor.class.getDeclaredMethods())
          .filter(method -> !method.isSynthetic())
          .map(java.lang.reflect.Method::getName)
          // equals, hashCode and toString come with every record and carry no surface of their own.
          .filter(name -> !List.of("equals", "hashCode", "toString").contains(name))
          .toList())
          .as("an accessor per field and nothing else - no service, no configuration, no lifecycle hook")
          .containsExactlyInAnyOrder("id", "dependencies");
    }
  }

  @Nested
  class OneLifecyclePath {

    @Test
    @DisplayName("an orchestrator drives a plugin through initialize, start and stop")
    void theOrchestratorRunsPluginsAsParticipants() {
      Plugin auth = new RecordingPlugin("auth");

      LifecycleOrchestrator app = orchestrator().register("auth", auth).build();
      assertThat(events).as("registration is not startup, and a plugin is no exception").isEmpty();

      app.start();
      assertThat(events).containsExactly("auth.initialize", "auth.start");
      assertThat(app.state()).isEqualTo(ApplicationState.RUNNING);

      assertThat(app.stop().clean()).isTrue();
      assertThat(events).containsExactly("auth.initialize", "auth.start", "auth.stop");
      assertThat(app.state()).isEqualTo(ApplicationState.STOPPED);
    }

    @Test
    @DisplayName("dependency order is registration order, decided by the application")
    void dependencyOrderIsRegistrationOrderAndNothingElse() {
      // The "user depends on auth" fact sits in the descriptor as data, and what acts on it is the order these two
      // lines were written in. There is no resolver in this phase; this test is the honest record of that, and of
      // whose job it is when one is wanted (ADR-0015, ADR-0016).
      Plugin auth = new RecordingPlugin("auth");
      Plugin user = new RecordingPlugin("user", List.of("auth"));

      LifecycleOrchestrator app = orchestrator()
          .register(auth.descriptor().id(), auth)
          .register(user.descriptor().id(), user)
          .build();

      app.start();

      // The two global phases hold across plugins exactly as they do for any other participant.
      assertThat(events).containsExactly(
          "auth.initialize", "user.initialize",
          "auth.start", "user.start");
      assertThat(app.participantIds()).containsExactly("auth", "user");

      app.stop();
      assertThat(events).endsWith("user.stop", "auth.stop");
    }

    @Test
    @DisplayName("a plugin whose initialize fails is aborted and the earlier plugin is rolled back")
    void startupRollbackReachesPluginsThroughTheOnePath() {
      Plugin healthy = new RecordingPlugin("healthy");
      Plugin broken = new RecordingPlugin("broken") {
        @Override
        public void initialize() {
          events.add("broken.initialize");
          throw new IllegalStateException("cannot connect");
        }
      };

      LifecycleOrchestrator app = orchestrator()
          .register("healthy", healthy)
          .register("broken", broken)
          .build();

      assertThatThrownBy(app::start).isInstanceOf(StartupException.class);

      // abort() for the participant that never finished initializing, stop() for the one that did: a plugin is
      // treated like every other component, which is only provable because there is one path to begin with
      // (ADR-0004).
      assertThat(events).containsExactly("healthy.initialize", "broken.initialize", "broken.abort", "healthy.stop");
      assertThat(app.state()).isEqualTo(ApplicationState.FAILED);
    }

    @Test
    @DisplayName("two plugins claiming one id cannot both be registered")
    void oneIdCannotBeClaimedTwice() {
      RecordingPlugin first = new RecordingPlugin("auth");
      RecordingPlugin alsoAuth = new RecordingPlugin("auth");

      LifecycleOrchestrator.Builder builder = orchestrator().register(first.descriptor().id(), first);

      // The descriptor id is the registration key, so a duplicate identity surfaces at the one place a set of
      // plugins exists. That is the whole of the enforcement this phase offers, and the reason there is no
      // PluginRegistry of its own to duplicate the check in (ADR-0016).
      assertThatThrownBy(() -> builder.register(alsoAuth.descriptor().id(), alsoAuth))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already registered");
    }
  }

  @Nested
  class Ownership {

    @Test
    @DisplayName("the application owns the plugin; the orchestrator only drives it")
    void aPluginIsNotReleasedByTheLifecycleEngine() {
      class CloseablePlugin extends RecordingPlugin implements AutoCloseable {
        CloseablePlugin() {
          super("audit");
        }

        @Override
        public void close() {
          events.add("audit.close");
        }
      }
      CloseablePlugin plugin = new CloseablePlugin();

      LifecycleOrchestrator app = orchestrator().register("audit", plugin).build();
      app.start();
      events.clear();

      app.stop();

      // stop() is a plugin's release hook, and close() runs only when the application calls it: whoever created the
      // plugin owns releasing it, which is Phase 2.4's rule applied to a plugin unchanged (ADR-0015).
      assertThat(events).containsExactly("audit.stop");

      plugin.close();
      assertThat(events).containsExactly("audit.stop", "audit.close");
    }

    @Test
    @DisplayName("a plugin that implements nothing but Plugin has no release obligation at all")
    void aPluginNeedsNoCloseableToBeWellBehaved() {
      Plugin plain = new RecordingPlugin("plain");

      LifecycleOrchestrator app = orchestrator().register("plain", plain).build();
      app.start();
      events.clear();

      assertThat(app.stop().clean()).as("stop() is the whole release protocol a plugin must honour").isTrue();
      assertThat(events).containsExactly("plain.stop");
    }
  }
}
