package com.barezen.core.di;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.core.error.AssemblyException;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * An assembler tested against the ways an application can be put together wrongly: a dependency handed to the
 * wrong object, one type resolving to two instances, a missing dependency surfacing at the first request, a
 * cycle ending in {@code StackOverflowError}, an object released while something still uses it.
 *
 * <p>The fixtures are dull on purpose - interfaces, an implementation holding its dependency in a final field
 * set by the constructor, and a list of strings recording what happened in which order. That list is how every
 * ordering claim below is measured rather than asserted.
 */
class ServiceAssemblyTest {

  private final List<String> events = new ArrayList<>();

  private interface Store {}

  private final class InMemoryStore implements Store {
    InMemoryStore() {
      events.add("create store");
    }
  }

  private interface UseCase {
    Store store();
  }

  private final class CreateOrder implements UseCase {
    private final Store store;

    CreateOrder(Store store) {
      this.store = store;
      events.add("create use case");
    }

    @Override
    public Store store() {
      return store;
    }
  }

  private final class Endpoint {
    private final UseCase useCase;

    Endpoint(UseCase useCase) {
      this.useCase = useCase;
      events.add("create endpoint");
    }

    UseCase useCase() {
      return useCase;
    }
  }

  /** A dependency with something to release, so ownership and release order are observable. */
  private final class Resource implements AutoCloseable {
    private final String name;

    Resource(String name) {
      this.name = name;
      events.add("create " + name);
    }

    @Override
    public void close() {
      events.add("close " + name);
    }
  }

  /**
   * A three-link chain, each link closeable, each close reporting whether the link underneath it was still open.
   * Reverse creation order is only worth claiming if releasing the outer link first happens while the inner one
   * can still be inspected, so the fixtures measure that rather than trusting the name of the ordering rule.
   */
  private final class Pool implements AutoCloseable {
    private boolean open = true;

    Pool() {
      events.add("create pool");
    }

    @Override
    public void close() {
      open = false;
      events.add("close pool");
    }
  }

  private final class Session implements AutoCloseable {
    private final Pool pool;
    private boolean open = true;

    Session(Pool pool) {
      this.pool = pool;
      events.add("create session");
    }

    Pool pool() {
      return pool;
    }

    @Override
    public void close() {
      events.add("close session, pool still open=" + pool.open);
      open = false;
    }
  }

  private final class Statement implements AutoCloseable {
    private final Session session;

    Statement(Session session) {
      this.session = session;
      events.add("create statement");
    }

    @Override
    public void close() {
      events.add("close statement, session still open=" + session.open);
    }
  }

  private ServiceAssembly sample() {
    return ServiceAssembly.builder()
        .factory(Store.class, services -> new InMemoryStore())
        .factory(UseCase.class, services -> new CreateOrder(services.require(Store.class)))
        .factory(Endpoint.class, services -> new Endpoint(services.require(UseCase.class)))
        .build();
  }

  @Test
  @DisplayName("a constructor parameter receives the registered object")
  void injectsTheDependencyThroughTheConstructor() {
    ServiceAssembly assembly = sample();

    Endpoint endpoint = assembly.require(Endpoint.class);
    UseCase useCase = assembly.require(UseCase.class);

    assertThat(endpoint.useCase()).isSameAs(useCase);
    assertThat(useCase.store()).isSameAs(assembly.require(Store.class));
    assembly.close();
  }

  @Test
  @DisplayName("one type resolves to the same object every time")
  void everyLookupReturnsTheSameInstance() {
    ServiceAssembly assembly = sample();

    assertThat(assembly.require(Store.class)).isSameAs(assembly.require(Store.class));
    assertThat(assembly.require(UseCase.class)).isSameAs(assembly.require(UseCase.class));
    // A second entry point must not build a second copy either.
    assertThat(assembly.find(Endpoint.class)).containsSame(assembly.require(Endpoint.class));
    assembly.close();
  }

  @Test
  @DisplayName("a dependency is created before the object that needs it")
  void createsADependencyBeforeItsDependent() {
    ServiceAssembly assembly = sample();

    assertThat(events).containsExactly("create store", "create use case", "create endpoint");
    assembly.close();
  }

  @Test
  @DisplayName("registration order does not decide creation order")
  void registrationOrderIsNotADependencyStatement() {
    // Registered shallowest-first: what decides creation order is a factory asking, not where a line sits.
    ServiceAssembly assembly = ServiceAssembly.builder()
        .factory(Endpoint.class, services -> new Endpoint(services.require(UseCase.class)))
        .factory(UseCase.class, services -> new CreateOrder(services.require(Store.class)))
        .factory(Store.class, services -> new InMemoryStore())
        .build();

    assertThat(events).containsExactly("create store", "create use case", "create endpoint");
    assembly.close();
  }

  @Test
  void registeringSomethingDoesNotCreateIt() {
    ServiceAssembly.Builder builder = ServiceAssembly.builder()
        .factory(Store.class, services -> new InMemoryStore());

    assertThat(events).as("registration states intent; build() performs it").isEmpty();

    ServiceAssembly assembly = builder.build();
    assertThat(events).containsExactly("create store");

    assembly.require(Store.class);
    assertThat(events).containsExactly("create store");
    assembly.close();
  }

  @Test
  void anUnregisteredTypeFailsRatherThanYieldingNull() {
    ServiceAssembly assembly = sample();

    assertThat(assembly.find(String.class)).isEmpty();
    assertThatThrownBy(() -> assembly.require(String.class))
        .isInstanceOf(AssemblyException.class)
        .hasMessageContaining(String.class.getName())
        .hasMessageContaining("registered");
    assembly.close();
  }

  @Test
  @DisplayName("a factory that asks for something nobody registered fails while assembling")
  void aMissingDependencyFailsAtBuildNotAtTheFirstRequest() {
    // Where the failure lands is the point: an application that assembles cleanly and then throws on the
    // first request has moved a typo from startup into production traffic.
    assertThatThrownBy(() -> ServiceAssembly.builder()
        .factory(UseCase.class, services -> new CreateOrder(services.require(Store.class)))
        .build())
        .isInstanceOf(AssemblyException.class)
        .hasMessageContaining(Store.class.getName())
        .hasMessageContaining("asked for");
  }

  @Test
  @DisplayName("a second registration of one type is refused as ambiguous")
  void twoCandidatesForOneTypeAreRefusedAtRegistration() {
    // No @Primary, no qualifier, no name: there is nothing to choose with, so the ambiguity is refused where
    // it was created instead of being resolved by whichever registration happened to come last.
    ServiceAssembly.Builder builder = ServiceAssembly.builder()
        .factory(Store.class, services -> new InMemoryStore());

    assertThatThrownBy(() -> builder.factory(Store.class, services -> new InMemoryStore()))
        .isInstanceOf(AssemblyException.class)
        .hasMessageContaining(Store.class.getName())
        .hasMessageContaining("more than one");

    assertThatThrownBy(() -> builder.instance(Store.class, new InMemoryStore()))
        .isInstanceOf(AssemblyException.class)
        .hasMessageContaining("more than one");
  }

  @Test
  @DisplayName("two implementations of one interface coexist as two types")
  void theWayToHaveTwoImplementationsIsTwoRegistrationsOfDistinctTypes() {
    // What the ambiguity rule pushes an application towards, and the honest version of it: the assembly names
    // which implementation the dependent gets, in code a reader can see.
    Store primary = new InMemoryStore();
    Store replica = new InMemoryStore();

    ServiceAssembly assembly = ServiceAssembly.builder()
        .instance(Store.class, primary)
        .factory(String.class, services -> "chosen: "
            + (services.require(Store.class) == primary ? "primary" : "replica"))
        .build();

    assertThat(assembly.require(String.class)).isEqualTo("chosen: primary");
    assertThat(replica).as("the unchosen one was never registered and is nobody's to close").isNotNull();
    assembly.close();
  }

  @Test
  void anObjectTheCallerBuiltIsHandedOutUntouched() {
    Store handBuilt = new InMemoryStore();
    events.clear();

    ServiceAssembly assembly = ServiceAssembly.builder().instance(Store.class, handBuilt).build();

    assertThat(assembly.require(Store.class)).isSameAs(handBuilt);
    assertThat(events).as("the factory of an instance registration never runs").isEmpty();
    assembly.close();
  }

  @Test
  @DisplayName("a registration whose object is not its type is refused while building")
  void registeringAnObjectThatIsNotTheRegisteredTypeIsRefused() {
    // The compiler forbids instance(Store.class, "a string") and factory(Store.class, services -> "a string")
    // outright, which is why reaching the check needs the type argument cast away. What it proves is where the
    // check lives: build() validates both registration paths, so a lookup afterwards never has to.
    @SuppressWarnings({"unchecked", "rawtypes"})
    ServiceAssembly.Builder instancePath = ServiceAssembly.builder()
        .instance((Class) (Class<?>) Store.class, "not a store");

    assertThatThrownBy(instancePath::build)
        .isInstanceOf(AssemblyException.class)
        .hasMessageContaining(Store.class.getName())
        .hasMessageContaining("is not an instance of it");

    @SuppressWarnings({"unchecked", "rawtypes"})
    ServiceAssembly.Builder factoryPath = ServiceAssembly.builder()
        .factory((Class) (Class<?>) Store.class, services -> "not a store");

    assertThatThrownBy(factoryPath::build)
        .isInstanceOf(AssemblyException.class)
        .hasMessageContaining(Store.class.getName())
        .hasMessageContaining("is not an instance of it");
  }

  @Test
  @DisplayName("a factory returning null is an assembly mistake")
  void aNullResultFailsRatherThanBecomingANullDependency() {
    // A null in the graph would surface as a NullPointerException in whatever method first used it, far from
    // the line that registered it.
    assertThatThrownBy(() -> ServiceAssembly.builder().factory(Store.class, services -> null).build())
        .isInstanceOf(AssemblyException.class)
        .hasMessageContaining("null");
  }

  @Test
  @DisplayName("a factory that throws is not wrapped")
  void doesNotBuryTheApplicationsOwnFailure() {
    IllegalStateException original = new IllegalStateException("cannot open the file");

    assertThatThrownBy(() -> ServiceAssembly.builder().factory(Resource.class, services -> {
      throw original;
    }).build()).isSameAs(original);
  }

  @Nested
  class Cycles {

    /** Two objects that need each other to be constructed, which is the only way to be in a cycle. */
    private class Left {
      private final Right right;

      Left(Right right) {
        this.right = right;
      }
    }

    private class Right {
      private final Left left;

      Right(Left left) {
        this.left = left;
      }
    }

    @Test
    @DisplayName("a two-type cycle is reported as the path it went through")
    void reportsTheCyclePathInsteadOfOverflowingTheStack() {
      // Without the guard this recurses until StackOverflowError: an Error, not catchable usefully, and one
      // that never names the two types involved.
      assertThatThrownBy(() -> ServiceAssembly.builder()
          .factory(Left.class, services -> new Left(services.require(Right.class)))
          .factory(Right.class, services -> new Right(services.require(Left.class)))
          .build())
          .isInstanceOf(AssemblyException.class)
          .hasMessageContaining("dependency cycle")
          .hasMessageContaining(Left.class.getName() + " -> " + Right.class.getName() + " -> "
              + Left.class.getName());
    }

    @Test
    @DisplayName("an object that depends on itself is a cycle too")
    void aSelfReferenceIsDetected() {
      assertThatThrownBy(() -> ServiceAssembly.builder()
          .factory(Store.class, services -> services.require(Store.class))
          .build())
          .isInstanceOf(AssemblyException.class)
          .hasMessageContaining("dependency cycle")
          .hasMessageContaining(Store.class.getName() + " -> " + Store.class.getName());
    }

    @Test
    void aThreeTypeCycleNamesEveryStep() {
      // Endpoint -> UseCase -> Store -> Endpoint, the shape a real application reaches when a low-level object
      // asks back for the thing that calls it. Only the path matters: the cycle is caught on the third hop,
      // not after the recursion has run out of stack.
      assertThatThrownBy(() -> ServiceAssembly.builder()
          .factory(Endpoint.class, services -> new Endpoint(services.require(UseCase.class)))
          .factory(UseCase.class, services -> new CreateOrder(services.require(Store.class)))
          .factory(Store.class, services -> {
            services.require(Endpoint.class);
            return new InMemoryStore();
          })
          .build())
          .isInstanceOf(AssemblyException.class)
          .hasMessageContaining("dependency cycle")
          .hasMessageContaining(Endpoint.class.getName() + " -> " + UseCase.class.getName() + " -> "
              + Store.class.getName() + " -> " + Endpoint.class.getName());
    }
  }

  @Nested
  class OwnershipAndReleaseOrder {

    @Test
    @DisplayName("created objects are released newest first, so a dependent goes before its dependency")
    void releasesInReverseCreationOrder() {
      // Statement -> Session -> Pool. Registered deepest-last to show it is creation order that decides release
      // order, not the order the registrations were written in.
      ServiceAssembly assembly = ServiceAssembly.builder()
          .factory(Pool.class, services -> new Pool())
          .factory(Session.class, services -> new Session(services.require(Pool.class)))
          .factory(Statement.class, services -> new Statement(services.require(Session.class)))
          .build();
      events.clear();

      assembly.close();

      assertThat(events).containsExactly(
          "close statement, session still open=true",
          "close session, pool still open=true",
          "close pool");
      // The two "true" values are the claim being measured rather than named: had the pool gone first, the
      // session would have been closed against a released resource.
      Session session = assembly.require(Session.class);
      assertThat(session.pool()).as("the assembly holds on to what it built, it only stops using it").isNotNull();
    }

    @Test
    @DisplayName("an object the caller supplied is never closed by the assembly")
    void leavesCallerOwnedObjectsToTheirCaller() {
      Pool callersPool = new Pool();
      events.clear();
      ServiceAssembly assembly = ServiceAssembly.builder().instance(Pool.class, callersPool).build();

      assembly.close();

      assertThat(events).as("one owner per object, and it is not this one").isEmpty();
    }

    @Test
    @DisplayName("closing twice releases once")
    void closingIsIdempotent() {
      ServiceAssembly assembly = ServiceAssembly.builder().factory(Pool.class, services -> new Pool()).build();
      events.clear();

      assembly.close();
      assembly.close();

      assertThat(events).containsExactly("close pool");
    }

    @Test
    @DisplayName("one failing release does not skip the others")
    void everyOwnedInstanceIsAttemptedEvenWhenOneThrows() {
      AutoCloseable broken = () -> {
        throw new IllegalStateException("will not drain");
      };
      ServiceAssembly assembly = ServiceAssembly.builder()
          .factory(Resource.class, services -> new Resource("behind it"))
          // Created last, so released first: the failure lands before this one is reached, which is the case
          // where giving up at the first exception would trade a reported failure for a leaked resource.
          .factory(AutoCloseable.class, services -> broken)
          .build();
      events.clear();

      assertThatThrownBy(assembly::close)
          .isInstanceOf(AssemblyException.class)
          .hasMessageContaining("failed to close")
          .hasCauseInstanceOf(IllegalStateException.class);

      assertThat(events).containsExactly("close behind it");
    }

    @Test
    @DisplayName("every failure is reported, not just the one that happened first")
    void severalFailuresAreAllReported() {
      ServiceAssembly assembly = ServiceAssembly.builder()
          .factory(AutoCloseable.class, services -> (AutoCloseable) () -> {
            throw new IllegalStateException("first");
          })
          .factory(Closeable.class, services -> (Closeable) () -> {
            throw new IllegalStateException("second");
          })
          .build();

      Throwable[] reported;
      try {
        assembly.close();
        throw new AssertionError("expected the release to fail");
      } catch (AssemblyException failure) {
        reported = failure.getSuppressed();
        // Reverse order, so the failure of the newest object is the cause. Naming both is what makes a shutdown
        // failure diagnosable in one read instead of one leak at a time.
        assertThat(failure).hasMessageContaining("2 instance(s)").hasCauseInstanceOf(IllegalStateException.class);
      }
      assertThat(reported).as("one failure carried, the rest attached").hasSize(1);
    }

    @Test
    @DisplayName("non-closeable objects are simply left alone at release")
    void objectsWithNothingToReleaseAreNotAnError() {
      ServiceAssembly assembly = ServiceAssembly.builder()
          .factory(Store.class, services -> new InMemoryStore())
          .factory(UseCase.class, services -> new CreateOrder(services.require(Store.class)))
          .build();
      events.clear();

      assembly.close();

      assertThat(events).isEmpty();
    }
  }

  @Nested
  class BuilderHygiene {

    @Test
    @DisplayName("one builder builds one assembly")
    void buildingTwiceIsRefused() {
      // A second build() would run every factory again, hand out a second set of objects and leave the first
      // set with no reference and no way to release it.
      ServiceAssembly.Builder builder = ServiceAssembly.builder()
          .factory(Store.class, services -> new InMemoryStore());
      builder.build();

      assertThatThrownBy(builder::build)
          .isInstanceOf(AssemblyException.class)
          .hasMessageContaining("already");
    }

    @Test
    void nullsAreRefusedAtRegistration() {
      ServiceAssembly.Builder builder = ServiceAssembly.builder();

      assertThatThrownBy(() -> builder.factory(null, services -> new InMemoryStore()))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> builder.factory(Store.class, null))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> builder.instance(Store.class, null))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> builder.build().require(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a lookup after build never creates anything")
    void theGraphIsFrozenOnceBuilt() {
      // Unregistering the surprise is the point: after build() a lookup can only find what was declared, so
      // nothing can be assembled lazily behind the assembler's back.
      ServiceAssembly assembly = ServiceAssembly.builder()
          .factory(Store.class, services -> new InMemoryStore())
          .build();
      events.clear();

      assertThat(assembly.find(UseCase.class)).isEmpty();
      assertThat(events).isEmpty();
      assembly.close();
    }

    @Test
    @DisplayName("an empty assembly is legal")
    void anApplicationWithNoRegisteredTypesAssembles() {
      // Degenerate but reachable, and it should not throw: an application that only wires through the
      // orchestrator registers nothing here.
      ServiceAssembly assembly = ServiceAssembly.builder().build();

      assertThatThrownBy(() -> assembly.require(Store.class)).isInstanceOf(AssemblyException.class);
      assembly.close();
    }

    @Test
    @DisplayName("a Services view handed out by a factory stops answering")
    void aLeakedFactoryViewIsRefusedRatherThanServed() {
      // The shape this phase is most likely to be asked for later: "just let the component look up what it needs".
      // It works until the view refuses, so the refusal is the thing that has to be tested - a component that
      // resolves its own dependencies is a service locator, and the assembler must not be the place where one
      // happens to function (ADR-0006, ADR-0014).
      Services[] leaked = new Services[1];
      ServiceAssembly assembly = ServiceAssembly.builder()
          .factory(Store.class, services -> {
            leaked[0] = services;
            return new InMemoryStore();
          })
          .build();

      assertThatThrownBy(() -> leaked[0].require(Store.class))
          .isInstanceOf(AssemblyException.class)
          .hasMessageContaining("service locator")
          .hasMessageContaining("constructor");
      assertThatThrownBy(() -> leaked[0].find(Store.class)).isInstanceOf(AssemblyException.class);
      assembly.close();
    }
  }

  @Test
  @DisplayName("four public types and seven operations is the whole surface")
  void theContainerIsSmallOnPurpose() {
    // The phase brief's warning is about exactly this list. If it starts growing towards getBean-style surface,
    // the design has drifted and this test is where it shows up first.
    assertThat(java.util.Arrays.stream(ServiceAssembly.class.getDeclaredMethods())
        .filter(method -> !method.isSynthetic())
        .map(java.lang.reflect.Method::getName))
        .containsExactlyInAnyOrder("builder", "require", "find", "close");
    assertThat(java.util.Arrays.stream(ServiceAssembly.Builder.class.getDeclaredMethods())
        .filter(method -> !method.isSynthetic())
        .map(java.lang.reflect.Method::getName))
        .containsExactlyInAnyOrder("instance", "factory", "build", "register");
    assertThat(java.util.Arrays.stream(Services.class.getDeclaredMethods())
        .filter(method -> !method.isSynthetic())
        .map(java.lang.reflect.Method::getName))
        .containsExactlyInAnyOrder("require", "find");

    // builder, instance, factory, build, require, find, close. register is private, so it is not surface.
    long publicOperations = java.util.stream.Stream
        .of(ServiceAssembly.class, ServiceAssembly.Builder.class, Services.class)
        .flatMap(type -> java.util.Arrays.stream(type.getMethods()))
        .filter(method -> method.getDeclaringClass() != Object.class)
        .map(java.lang.reflect.Method::getName)
        .distinct()
        .count();
    assertThat(publicOperations).isEqualTo(7);

    // No second lookup shape, and no way to reach instances in bulk.
    assertThat(java.util.Arrays.stream(Services.class.getDeclaredMethods()))
        .noneMatch(method -> method.getName().equals("require")
            && method.getParameterCount() == 2);
  }
}
