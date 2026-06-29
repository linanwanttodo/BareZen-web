package com.barezen.core.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import com.barezen.core.concurrent.ThreadPolicy;
import com.barezen.core.di.ServiceAssembly;
import com.barezen.core.di.Services;
import com.barezen.core.runtime.RuntimeCapabilities;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The seam the whole phase exists for: the assembler builds the objects, the lifecycle engine alone drives them.
 *
 * <p>Two failure modes live on either side of this seam, and each is how a framework ends up with two lifecycle
 * managers. If the assembler stopped participants, {@code stop()} would run in an order nobody decided and
 * rollback after a failed start would be incomplete. If participants were constructed by hand after registration,
 * the assembler would be decoration. So what is measured here is not that each part works -
 * {@link LifecycleOrchestratorTest} covers that - but that the handover has exactly one owner per responsibility.
 *
 * <p>The end state the phase claims is {@code Configuration -> Application Assembly -> DI -> application services
 * -> LifecycleOrchestrator}. Objects appear through constructors, then those same objects go through
 * {@code initialize()} and {@code start()} because of what they implement, never because the assembler was asked.
 */
class AssemblyToLifecycleTest {

  private final List<String> events = new ArrayList<>();

  /**
   * A participant holding a resource of its own. Deliberately not {@link AutoCloseable}: a participant created by
   * a factory must have exactly one release path, and the two owners are kept apart by {@link Pool} instead.
   */
  private final class Cache implements LifecycleParticipant {
    private boolean released;

    Cache() {
      events.add("create cache");
    }

    boolean released() {
      return released;
    }

    @Override
    public void initialize() {
      events.add("cache.initialize");
    }

    @Override
    public void start() {
      events.add("cache.start");
    }

    @Override
    public void stop() {
      events.add("cache.stop");
      released = true;
    }
  }

  /** A participant whose dependency is another participant, assembled rather than wired by hand. */
  private final class SearchIndex implements LifecycleParticipant {
    private final Cache cache;

    SearchIndex(Cache cache) {
      this.cache = cache;
      events.add("create index");
    }

    @Override
    public void initialize() {
      events.add("index.initialize");
      assertThat(cache).as("the dependency arrived through the constructor and is the assembled one").isNotNull();
    }

    @Override
    public void start() {
      events.add("index.start");
    }

    @Override
    public void stop() {
      events.add("index.stop");
    }
  }

  /** Something the assembler created and therefore releases, with no lifecycle role at all. */
  private final class Pool implements Closeable {
    Pool() {
      events.add("create pool");
    }

    @Override
    public void close() {
      events.add("pool.close");
    }
  }

  private static LifecycleOrchestrator.Builder orchestrator() {
    return LifecycleOrchestrator.builder()
        .threadPolicy(ThreadPolicy.PLATFORM)
        .runtimeCapabilities(new RuntimeCapabilities(17, false, "test"))
        .registerShutdownHook(false);
  }

  @Test
  @DisplayName("a participant built by the assembly goes through initialize, start and stop")
  void assembledParticipantsAreDrivenThroughEveryPhase() {
    ServiceAssembly assembly = ServiceAssembly.builder()
        .factory(Cache.class, services -> new Cache())
        .factory(SearchIndex.class, services -> new SearchIndex(services.require(Cache.class)))
        .build();

    // Assembling created objects and nothing more - building an object is not starting it.
    assertThat(events).containsExactly("create cache", "create index");
    events.clear();

    LifecycleOrchestrator app = orchestrator()
        .register("cache", assembly.require(Cache.class))
        .register("index", assembly.require(SearchIndex.class))
        .build();

    app.start();

    // The global two-phase rule holds across the seam: both initialize before either starts.
    assertThat(events).containsExactly(
        "cache.initialize", "index.initialize",
        "cache.start", "index.start");
    assertThat(app.state()).isEqualTo(ApplicationState.RUNNING);

    app.stop();

    // Reverse registration order for shutdown, which is the orchestrator's rule and not the assembler's.
    assertThat(events).endsWith("index.stop", "cache.stop");
    Cache cache = assembly.require(Cache.class);
    assertThat(cache.released()).isTrue();

    assembly.close();
    assertThat(events).as("the assembly has nothing left to release: neither participant is closeable")
        .containsExactly(
            "cache.initialize", "index.initialize",
            "cache.start", "index.start",
            "index.stop", "cache.stop");
  }

  @Test
  @DisplayName("the assembler owns no lifecycle vocabulary")
  void theAssemblerDoesNotReimplementLifecycle() {
    // The negative half of the integration claim. A container that grows start(), stop() or refresh() becomes a
    // second lifecycle engine, and two engines disagree about order. The declared surface is checked rather than
    // the intent, because the way this drifts is one extra convenience method at a time.
    List<String> names = Arrays.stream(ServiceAssembly.class.getDeclaredMethods())
        .filter(method -> !method.isSynthetic())
        .map(java.lang.reflect.Method::getName)
        .toList();

    assertThat(names).filteredOn(name -> List.of("initialize", "start", "stop", "abort", "run", "refresh",
        "getState", "lifecycle").contains(name)).isEmpty();

    // What remains is the release path, and it carries no phase: the type is closeable but is not a participant,
    // so it can neither be registered with an orchestrator nor report a state.
    assertThat(ServiceAssembly.class.getInterfaces()).containsExactly(Services.class, AutoCloseable.class);
    assertThat(LifecycleParticipant.class.isAssignableFrom(ServiceAssembly.class)).isFalse();
  }

  @Test
  @DisplayName("registering a participant as an instance leaves its release to the orchestrator")
  void theDocumentedOwnershipRulePreventsDoubleRelease() {
    Cache cache = new Cache();
    events.clear();

    ServiceAssembly assembly = ServiceAssembly.builder().instance(Cache.class, cache).build();
    assembly.close();

    assertThat(events).as("the assembly did not create this object, so it does not release it").isEmpty();
    assertThat(cache.released()).isFalse();

    LifecycleOrchestrator app = orchestrator().register("cache", cache).build();
    app.start();
    events.clear();

    app.stop();

    assertThat(events).containsExactly("cache.stop");
    assertThat(cache.released()).as("one owner, and it was the orchestrator").isTrue();
  }

  @Test
  @DisplayName("what the assembler creates is released by the assembler")
  void createdObjectsAreReleasedByTheirCreator() {
    ServiceAssembly assembly = ServiceAssembly.builder().factory(Pool.class, services -> new Pool()).build();
    events.clear();

    assembly.close();

    assertThat(events).containsExactly("pool.close");
  }
}
