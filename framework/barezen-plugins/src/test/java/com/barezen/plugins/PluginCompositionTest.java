package com.barezen.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.core.kernel.ApplicationState;
import com.barezen.core.kernel.LifecycleOrchestrator;
import com.barezen.core.kernel.StartupException;
import com.barezen.core.plugin.Plugin;
import com.barezen.core.plugin.PluginDescriptor;
import com.barezen.core.plugin.PluginRegistration;
import com.barezen.data.Database;
import com.barezen.plugins.auth.AuthPlugin;
import com.barezen.plugins.openapi.OpenApiPlugin;
import com.barezen.plugins.user.UserPlugin;
import com.barezen.plugins.user.UserRepository;
import com.barezen.security.AuthenticationResult;
import com.barezen.security.Authorization;
import com.barezen.security.Credential;
import com.barezen.security.Permission;
import com.barezen.security.Principal;
import com.barezen.security.SessionStore;
import com.barezen.web.HttpMethod;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Stage 2.13-F: the three plugins assembled the way an application would assemble them — validated by
 * {@link PluginRegistration}, ordered, registered into the {@link LifecycleOrchestrator} under their descriptor
 * ids, and started. What is proven here is the phase's isolation claims, not the plugins' own behaviour (that is
 * each plugin's unit test): registration cannot be bypassed, a plugin's state cannot be reached through another
 * plugin, the stop phase really releases, and one plugin's failure leaves nothing half-started.
 */
class PluginCompositionTest {

  private static final UserRepository.PasswordHashing FAKE_HASHING = new UserRepository.PasswordHashing() {
    @Override
    public String hash(String password) {
      return "fake$" + password;
    }

    @Override
    public boolean verify(String password, String encodedHash) {
      return hash(password).equals(encodedHash);
    }
  };

  private static SessionStore store() {
    return SessionStore.of(Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC), new SecureRandom());
  }

  /** The assembly an application writes: construct, validate, register under descriptor ids, start. */
  private static LifecycleOrchestrator orchestratorOver(List<Plugin> plugins) {
    LifecycleOrchestrator.Builder builder = LifecycleOrchestrator.builder().registerShutdownHook(false);
    for (Plugin plugin : PluginRegistration.validate(plugins)) {
      builder.register(plugin.descriptor().id(), plugin);
    }
    return builder.build();
  }

  @Nested
  @DisplayName("registration is the gate")
  class Registration {

    @Test
    @DisplayName("the declared auth→user edge orders the plugins for the orchestrator, caller order or not")
    void dependencyOrderDrivesRegistration() {
      Database database = new Database(TestDatabase.fresh());
      UserRepository users = new UserRepository(database, FAKE_HASHING);
      UserPlugin user = new UserPlugin(users);
      AuthPlugin auth = new AuthPlugin(store(), user, "Bearer", Duration.ofMinutes(30));

      // Assembled in the wrong order on purpose; validate must still place the dependency first.
      List<Plugin> ordered = PluginRegistration.validate(List.of(auth, user));

      assertThat(ordered).containsExactly(user, auth);
      assertThat(ordered.stream().map(plugin -> plugin.descriptor().id()).toList())
          .isEqualTo(List.of("user", "auth"));
    }

    @Test
    @DisplayName("auth without a user plugin in the set is refused before anything is registered")
    void missingDependencyRefused() {
      AuthPlugin orphan = new AuthPlugin(store(), (u, p) -> false, "Bearer", Duration.ofMinutes(30));

      assertThatThrownBy(() -> PluginRegistration.validate(List.of(orphan)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("user");
    }

    @Test
    @DisplayName("the orchestrator refuses a duplicate id too, so a validated set cannot be double-registered")
    void duplicateIdCannotReachTheOrchestrator() {
      Database database = new Database(TestDatabase.fresh());
      UserPlugin first = new UserPlugin(new UserRepository(database, FAKE_HASHING));
      UserPlugin second = new UserPlugin(new UserRepository(database, FAKE_HASHING));

      assertThatThrownBy(() -> PluginRegistration.validate(List.of(first, second)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("both claim the id 'user'");
      // And if someone skipped the verifier, the orchestrator's own register() still refuses the id.
      LifecycleOrchestrator.Builder builder = LifecycleOrchestrator.builder().registerShutdownHook(false);
      builder.register("user", first);
      assertThatThrownBy(() -> builder.register("user", second))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already registered");
    }

    @Test
    @DisplayName("a dependency cycle between plugins is refused by validate, never resolved by luck of ordering")
    void cycleRefused() {
      Plugin a = namedCycleMember("a", "b");
      Plugin b = namedCycleMember("b", "a");

      assertThatThrownBy(() -> PluginRegistration.validate(List.of(a, b)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cycle");
    }

    private Plugin namedCycleMember(String id, String dependsOn) {
      PluginDescriptor descriptor = new PluginDescriptor(id, List.of(dependsOn));
      return new Plugin() {
        @Override
        public PluginDescriptor descriptor() {
          return descriptor;
        }
      };
    }
  }

  @Nested
  @DisplayName("the lifecycle runs plugins and releases them")
  class Lifecycle {

    @Test
    @DisplayName("start runs initialize then start in registration order, stop reverses it, and no phase is skipped")
    void everyPluginDrivenThroughEveryPhase() {
      Recorder first = new Recorder("first");
      Recorder second = new Recorder("second");
      LifecycleOrchestrator app = LifecycleOrchestrator.builder()
          .registerShutdownHook(false)
          .register("first", first)
          .register("second", second)
          .build();

      app.start();
      assertThat(app.state()).isEqualTo(ApplicationState.RUNNING);
      app.stop();

      assertThat(first.phases).containsExactly("initialize", "start", "stop");
      assertThat(second.phases).containsExactly("initialize", "start", "stop");
      assertThat(app.state()).isEqualTo(ApplicationState.STOPPED);
    }

    @Test
    @DisplayName("the real plugins run through the orchestrator: user's initialize creates its schema before use")
    void realPluginsBootAndShutDown() {
      Database database = new Database(TestDatabase.fresh());
      UserRepository users = new UserRepository(database, FAKE_HASHING);
      UserPlugin user = new UserPlugin(users);
      AuthPlugin auth = new AuthPlugin(store(), user, "Bearer", Duration.ofMinutes(30));
      OpenApiPlugin openapi = new OpenApiPlugin();
      openapi.add(HttpMethod.POST, "/sessions", "Log in");

      LifecycleOrchestrator app = orchestratorOver(List.of(openapi, auth, user));
      app.start();
      try {
        // The user schema exists because the orchestrator ran UserPlugin.initialize — a row can be created.
        users.create("ada", "hunter2");
        users.grant("ada", "user:read");

        String token = auth.issueSession("ada", "hunter2").orElseThrow().token().value();
        AuthenticationResult result = auth.authenticate(new Credential("Bearer", token));
        assertThat(result.maybePrincipal()).contains(new Principal("ada"));
        assertThat(user.authorize(new Principal("ada"), Permission.of("user:read")))
            .isEqualTo(Authorization.Decision.ALLOW);
      } finally {
        app.stop();
      }
      assertThat(app.state()).isEqualTo(ApplicationState.STOPPED);
    }

    @Test
    @DisplayName("a plugin that fails to initialize leaves nothing started, and the failure names the plugin")
    void failedStartupRollsBackAndIsNotSwallowed() {
      Recorder before = new Recorder("before");
      Recorder after = new Recorder("after");
      Plugin broken = new Plugin() {
        @Override
        public PluginDescriptor descriptor() {
          return new PluginDescriptor("broken", List.of());
        }

        @Override
        public void initialize() {
          throw new IllegalStateException("no schema today");
        }
      };

      LifecycleOrchestrator app = LifecycleOrchestrator.builder()
          .registerShutdownHook(false)
          .register("before", before)
          .register("broken", broken)
          .register("after", after)
          .build();

      assertThatThrownBy(app::start)
          .isInstanceOf(StartupException.class)
          .hasMessageContaining("broken")
          .hasMessageContaining("no schema today");
      assertThat(app.state()).isEqualTo(ApplicationState.FAILED);
      assertThat(before.phases)
          .as("initialize ran, then the failure rolled it back; start never ran because init failed")
          .containsExactly("initialize", "stop");
      assertThat(after.phases).as("a plugin after the failure was never touched").isEmpty();
      // stop() after a failed start is legal, idempotent and never throws; nothing is stopped twice.
      assertThat(app.stop().failures()).isEmpty();
      assertThat(before.phases).containsExactly("initialize", "stop");
    }
  }

  @Nested
  @DisplayName("plugin state is not reachable through a plugin")
  class Isolation {

    @Test
    @DisplayName("the auth plugin holds the user plugin only as a PasswordVerifier, and cannot be made to mutate it")
    void authReachesUserThroughThePortOnly() {
      Database database = new Database(TestDatabase.fresh());
      UserRepository users = new UserRepository(database, FAKE_HASHING);
      UserPlugin user = new UserPlugin(users);
      user.initialize();
      users.create("ada", "hunter2");
      AuthPlugin auth = new AuthPlugin(store(), user, "Bearer", Duration.ofMinutes(30));

      // The only surface auth has of user is verify(String, String): no repository, no Database, no setters.
      List<String> fieldTypes = java.util.Arrays.stream(AuthPlugin.class.getDeclaredFields())
          .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
          .map(field -> field.getType().getSimpleName())
          .toList();
      assertThat(fieldTypes).containsExactlyInAnyOrder("SessionStore", "PasswordVerifier", "String", "Duration");

      assertThat(auth.issueSession("ada", "hunter2")).isPresent();
      assertThat(auth.issueSession("ada", "guessed")).isEmpty();
      // The failed login through the port touched no user state: the row and its hash are exactly as created.
      assertThat(users.verifyPassword("ada", "hunter2")).isTrue();
    }

    @Test
    @DisplayName("a caller that took openapi's snapshot cannot mutate what the plugin holds")
    void snapshotCannotWriteBack() {
      OpenApiPlugin openapi = new OpenApiPlugin();
      openapi.add(HttpMethod.GET, "/users", "List users");
      List<com.barezen.plugins.openapi.OperationMetadata> snapshot = openapi.operations();

      // "Another plugin" style caller: try to inject and to clear through the snapshot. Both miss.
      assertThatThrownBy(() -> snapshot.add(new com.barezen.plugins.openapi.OperationMetadata(
          HttpMethod.DELETE, "/users", "Purged", List.of())))
          .isInstanceOf(UnsupportedOperationException.class);
      assertThat(snapshot).hasSize(1);
      assertThat(openapi.size()).as("the plugin's state is untouched by its snapshot").isEqualTo(1);
      assertThat(openapi.operations()).singleElement()
          .satisfies(op -> assertThat(op.summary()).isEqualTo("List users"));
    }

    @Test
    @DisplayName("the lifecycle hands a plugin no context object, so there is no registry to reach sideways")
    void thereIsNothingToReach() {
      // ADR-0016's claim, checked mechanically rather than trusted: every LifecycleParticipant phase method is
      // parameterless. A plugin cannot receive a context, and therefore cannot look up — or mutate — a neighbour
      // through one; the only way one plugin touches another is a constructor-supplied port, as above.
      for (java.lang.reflect.Method method : com.barezen.core.kernel.LifecycleParticipant.class.getDeclaredMethods()) {
        if (method.isSynthetic() || method.isBridge()) {
          continue; // a coverage agent's probe method is not part of the declared API
        }
        assertThat(method.getParameterCount())
            .as("phase method %s must take no context object", method.getName())
            .isZero();
      }
    }
  }

  /** Records the phase methods the orchestrator calls, in order — the stop-skipped and exception probes. */
  private static final class Recorder implements Plugin {
    private final PluginDescriptor descriptor;
    private final List<String> phases = new ArrayList<>();

    Recorder(String id) {
      this.descriptor = new PluginDescriptor(id, List.of());
    }

    @Override
    public PluginDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public void initialize() {
      phases.add("initialize");
    }

    @Override
    public void start() {
      phases.add("start");
    }

    @Override
    public void stop() {
      phases.add("stop");
    }
  }
}
