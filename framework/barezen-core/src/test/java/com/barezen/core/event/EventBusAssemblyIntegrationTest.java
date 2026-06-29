package com.barezen.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.core.concurrent.ThreadPolicy;
import com.barezen.core.di.ServiceAssembly;
import com.barezen.core.kernel.ApplicationState;
import com.barezen.core.kernel.LifecycleOrchestrator;
import com.barezen.core.kernel.LifecycleParticipant;
import com.barezen.core.kernel.StartupException;
import com.barezen.core.plugin.Plugin;
import com.barezen.core.plugin.PluginDescriptor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The composition Phase 2.6 exists to prove: a real capability added to core without a context.
 *
 * <p>Everything below is assembled the way an application would assemble it — the bus created by the assembly, handed
 * to a plugin and to a service through constructors, the plugin registered with the orchestrator by descriptor id, and
 * the whole thing started and stopped by one owner each. The events are the horizontal wire between two components
 * that never name each other. If this needed a {@code PluginContext} to work, ADR-0016's central claim was wrong,
 * which is why the test reaches every layer at once instead of testing the bus alone (ADR-0017).
 */
class EventBusAssemblyIntegrationTest {

  private record UserCreated(String userId) {}

  private record UserDeleted(String userId) {}

  private final List<String> trace = new ArrayList<>();

  /**
   * A plugin that needs the bus: it subscribes while initializing and cancels while stopping, and its only
   * collaborator is the bus itself. No context, no lookup, no registry — one constructor parameter, which is the
   * design Phase 2.5 claimed would hold (ADR-0016).
   */
  private final class AuthPlugin implements Plugin {

    private final EventBus bus;
    private final List<Subscription> subscriptions = new ArrayList<>();

    AuthPlugin(EventBus bus) {
      this.bus = bus;
      trace.add("create auth");
    }

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor("auth", List.of());
    }

    @Override
    public void initialize() {
      subscriptions.add(bus.subscribe(UserCreated.class, event -> trace.add("auth:welcome:" + event.userId())));
      subscriptions.add(bus.subscribe(UserDeleted.class, event -> trace.add("auth:revoke:" + event.userId())));
      trace.add("auth.initialize");
    }

    @Override
    public void start() {
      trace.add("auth.start");
    }

    @Override
    public void stop() {
      subscriptions.forEach(Subscription::cancel);
      trace.add("auth.stop");
    }
  }

  /** A second plugin on the same bus, subscribed to the same type, that knows nothing about the first. */
  private final class AuditPlugin implements Plugin {

    private final EventBus bus;
    private Subscription subscription;

    AuditPlugin(EventBus bus) {
      this.bus = bus;
      trace.add("create audit");
    }

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor("audit", List.of("auth"));
    }

    @Override
    public void initialize() {
      subscription = bus.subscribe(UserCreated.class, event -> trace.add("audit:record:" + event.userId()));
    }

    @Override
    public void stop() {
      subscription.cancel();
    }
  }

  /** A plain service, not a participant: it publishes and holds nothing else. */
  private final class UserService {

    private final EventBus bus;

    UserService(EventBus bus) {
      this.bus = bus;
      trace.add("create userservice");
    }

    void create(String userId) {
      trace.add("userservice.create");
      bus.publish(new UserCreated(userId));
    }

    void remove(String userId) {
      bus.publish(new UserDeleted(userId));
    }
  }

  /** A participant whose job at {@code start()} is to publish, so the event flow runs inside the lifecycle. */
  private final class Greeter implements LifecycleParticipant {

    private final EventBus bus;

    Greeter(EventBus bus) {
      this.bus = bus;
    }

    @Override
    public void start() {
      trace.add("greeter.start");
      bus.publish(new UserCreated("bootstrap"));
    }
  }

  private static LifecycleOrchestrator.Builder orchestrator() {
    return LifecycleOrchestrator.builder()
        .threadPolicy(ThreadPolicy.PLATFORM)
        .runtimeCapabilities(new com.barezen.core.runtime.RuntimeCapabilities(17, false, "test"))
        .registerShutdownHook(false);
  }

  /** The assembly the way an application writes it: one bus, two components that receive it, one orchestrator. */
  private ServiceAssembly assembly(EventBus bus, AuthPlugin auth, UserService userService) {
    return ServiceAssembly.builder()
        .instance(EventBus.class, bus)
        .instance(AuthPlugin.class, auth)
        .instance(UserService.class, userService)
        .build();
  }

  @Nested
  @DisplayName("the composition the phase claims")
  class Composition {

    @Test
    @DisplayName("a plugin receives the bus through its constructor and a service publishes onto it")
    void aPluginSubscribesAndAServicePublishes() {
      EventBus bus = EventBus.create();
      AuthPlugin auth = new AuthPlugin(bus);
      UserService users = new UserService(bus);
      ServiceAssembly services = assembly(bus, auth, users);
      trace.clear();

      LifecycleOrchestrator app = orchestrator().register(auth.descriptor().id(), auth).build();
      app.start();
      services.require(UserService.class).create("123");

      // The bus the plugin was handed and the bus the service holds are the same object: one capability, shared by
      // construction, discovered by nobody.
      assertThat(trace).containsExactly("auth.initialize", "auth.start", "userservice.create", "auth:welcome:123");
      assertThat(services.require(EventBus.class)).isSameAs(bus);

      app.stop();
      services.close();
      assertThat(app.state()).isEqualTo(ApplicationState.STOPPED);
    }

    @Test
    @DisplayName("two plugins on one bus are both invoked, in registration order, without naming each other")
    void twoPluginsShareOneBus() {
      EventBus bus = EventBus.create();
      AuthPlugin auth = new AuthPlugin(bus);
      AuditPlugin audit = new AuditPlugin(bus);
      ServiceAssembly services = ServiceAssembly.builder()
          .instance(EventBus.class, bus)
          .instance(AuthPlugin.class, auth)
          .instance(AuditPlugin.class, audit)
          .build();
      trace.clear();

      // The application read audit's descriptor, which declares auth, and registered them in that order. That is
      // the whole of dependency handling in this phase: metadata in, registration order out (ADR-0016).
      LifecycleOrchestrator app = orchestrator()
          .register(auth.descriptor().id(), auth)
          .register(audit.descriptor().id(), audit)
          .build();
      app.start();
      bus.publish(new UserCreated("123"));

      assertThat(trace).containsSubsequence("auth:welcome:123", "audit:record:123");

      app.stop();
      services.close();
      trace.clear();
      bus.publish(new UserCreated("late"));

      assertThat(trace).as("both plugins cancelled their own subscriptions on stop").isEmpty();
    }

    @Test
    @DisplayName("the event flow runs inside the lifecycle phases it is claimed to run in")
    void subscribeInInitializePublishInStartUnsubscribeInStop() {
      EventBus bus = EventBus.create();
      AuthPlugin auth = new AuthPlugin(bus);
      Greeter greeter = new Greeter(bus);

      LifecycleOrchestrator app = orchestrator()
          .register(auth.descriptor().id(), auth)
          .register("greeter", greeter)
          .build();

      app.start();

      // auth.initialize subscribed before anything could publish, and greeter's publish at start reached it. The
      // bus never appears in this sequence as a phase: it was constructed by the application and used by both.
      assertThat(trace).containsExactly(
          "create auth", "auth.initialize", "auth.start", "greeter.start", "auth:welcome:bootstrap");

      app.stop();
      assertThat(app.state()).isEqualTo(ApplicationState.STOPPED);
    }

    @Test
    @DisplayName("a stopped plugin no longer receives events while a running one still does")
    void onePluginStoppingDoesNotDisturbTheOther() {
      EventBus bus = EventBus.create();
      AuthPlugin auth = new AuthPlugin(bus);
      AuditPlugin audit = new AuditPlugin(bus);
      auth.initialize();
      audit.initialize();
      trace.clear();

      bus.publish(new UserCreated("1"));
      assertThat(trace).containsExactly("auth:welcome:1", "audit:record:1");

      auth.stop();
      trace.clear();
      bus.publish(new UserCreated("2"));

      // The bus did not notice that a plugin stopped and unsubscribed it; the plugin asked for its own
      // subscriptions to end, which is the only direction that keeps EventBus free of the plugin concept (ADR-0017).
      assertThat(trace).containsExactly("audit:record:2");
      audit.stop();
    }

    @Test
    @DisplayName("an assembled bus is the application's object, not one a plugin went and got")
    void theBusComesFromTheAssembly() {
      EventBus bus = EventBus.create();
      AuthPlugin auth = new AuthPlugin(bus);
      ServiceAssembly services = assembly(bus, auth, new UserService(bus));
      auth.initialize();
      trace.clear();

      services.close();
      bus.publish(new UserCreated("after close"));

      // Everything here was registered as an instance, so closing the assembly released nothing: the bus outlives it
      // because its owner is the application, and the plugin's subscription outlives it because the plugin owns that
      // (§16, ADR-0015).
      assertThat(services.require(EventBus.class)).isSameAs(bus);
      assertThat(trace).containsExactly("auth:welcome:after close");

      trace.clear();
      auth.stop();
      assertThat(trace).containsExactly("auth.stop");

      trace.clear();
      bus.publish(new UserCreated("after stop"));
      assertThat(trace).isEmpty();
    }
  }

  @Nested
  @DisplayName("the bus stays out of the lifecycle")
  class LifecycleBoundary {

    @Test
    @DisplayName("EventBus is not a participant and its implementation is not closeable")
    void theBusHasNoLifecycleVocabulary() {
      // A bus that were a participant would put a capability in the application's shutdown order and give a second
      // owner to subscriptions; a closeable one would be a resource the assembler releases while handlers still run.
      assertThat(LifecycleParticipant.class.isAssignableFrom(EventBus.class)).isFalse();
      assertThat(LifecycleParticipant.class.isAssignableFrom(Subscription.class)).isFalse();
      assertThat(AutoCloseable.class.isAssignableFrom(EventBus.class)).isFalse();

      EventBus created = EventBus.create();
      assertThat(created).isNotInstanceOf(LifecycleParticipant.class);
      assertThat(created).isNotInstanceOf(AutoCloseable.class);
      assertThat(Arrays.stream(created.getClass().getMethods())
          .map(java.lang.reflect.Method::getName)
          .filter(name -> List.of("close", "start", "stop", "initialize", "abort").contains(name)))
          .isEmpty();
    }

    @Test
    @DisplayName("the orchestrator can drive a plugin without knowing what a bus is")
    void theLifecycleEngineStaysIndifferentToEvents() {
      EventBus bus = EventBus.create();
      AuthPlugin auth = new AuthPlugin(bus);

      LifecycleOrchestrator app = orchestrator().register(auth.descriptor().id(), auth).build();
      app.start();

      // The engine called initialize and start on an object whose only collaborator is a bus; it never touched the
      // bus, which is what the kernel-to-event edge would have meant (ADR-0016, ADR-0017).
      assertThat(trace).containsExactly("create auth", "auth.initialize", "auth.start");

      app.stop();

      // Rollback reached the plugin's stop, which cancelled its own subscriptions: the engine never had to know
      // that a bus was involved (ADR-0016).
      assertThat(trace).endsWith("auth.stop");
    }

    @Test
    @DisplayName("a handler failure during a lifecycle publish is a startup failure, owned by the engine")
    void aHandlerThatFailsAtStartRollsBackThroughTheOrchestrator() {
      EventBus bus = EventBus.create();
      bus.subscribe(UserCreated.class, event -> {
        throw new IllegalStateException("handler refused the bootstrap event");
      });
      AuthPlugin auth = new AuthPlugin(bus);
      Greeter greeter = new Greeter(bus);

      LifecycleOrchestrator app = orchestrator()
          .register(auth.descriptor().id(), auth)
          .register("greeter", greeter)
          .build();

      // The handler's own exception is the cause of the StartupException the engine reports, which is ADR-0004's
      // rule: the original failure wins and travels unchanged. The bus added nothing to that path.
      assertThatThrownBy(app::start)
          .isInstanceOf(StartupException.class)
          .hasCauseInstanceOf(IllegalStateException.class)
          .extracting(failure -> ((StartupException) failure).participantId())
          .isEqualTo("greeter");

      assertThat(app.state()).isEqualTo(ApplicationState.FAILED);
      assertThat(trace).contains("auth.initialize", "auth.stop");
    }
  }

  @Nested
  @DisplayName("what the bus does not know")
  class Ignorance {

    @Test
    @DisplayName("the bus keeps no record of who subscribed")
    void theBusHasNoSubscriberIdentity() {
      EventBus bus = EventBus.create();
      AuthPlugin auth = new AuthPlugin(bus);
      auth.initialize();
      trace.clear();

      // No subscribers() view, no per-plugin bucket, nothing that could answer "which plugin holds this handler".
      // That question belongs to the application, and answering it here would be the first member of a context.
      // Synthetic filtering is load-bearing under the coverage agent, which injects $jacocoInit into this interface.
      assertThat(Arrays.stream(EventBus.class.getDeclaredMethods())
          .filter(method -> !method.isSynthetic())
          .map(java.lang.reflect.Method::getName))
          .containsExactlyInAnyOrder("subscribe", "publish", "create");

      bus.publish(new UserCreated("1"));
      assertThat(trace).containsExactly("auth:welcome:1");
      auth.stop();
    }

    @Test
    @DisplayName("publishing an event nobody handles is not an error")
    void aPublisherNeverNeedsItsAudience() {
      EventBus bus = EventBus.create();
      UserService users = new UserService(bus);
      trace.clear();

      users.remove("123");

      // The service ran; no handler existed for UserDeleted and the bus reported that as nothing at all.
      assertThat(trace).isEmpty();
    }
  }
}
