package com.barezen.core.di;

import com.barezen.core.error.AssemblyException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Explicit, reflection-free assembly of an application's objects.
 *
 * <p>The model is one sentence: an application states what it has, and this works out the order and performs
 * {@code new A(b)} on its behalf.
 *
 * <pre>
 *   ServiceAssembly assembly = ServiceAssembly.builder()
 *       .instance(DataSource.class, dataSource)                  // caller built it, caller owns it
 *       .factory(UserRepository.class, services -&gt;
 *           new SqlUserRepository(services.require(DataSource.class)))
 *       .factory(UserService.class, services -&gt;
 *           new UserService(services.require(UserRepository.class)))
 *       .build();
 * </pre>
 *
 * <p>Three consequences are the reason the type exists. A dependency appears in the factory body, so "what does
 * UserService need" has one answer and it is the constructor. Nothing is discovered - no classpath scan, no
 * annotation, no constructor picked by reflection, no descriptor file - so {@link Builder#build()} creates
 * exactly the registered types and fails if one of them was never registered. Resolution is eager, so a missing
 * or circular dependency becomes an {@link AssemblyException} while the application is still being assembled
 * instead of a failure at the first request.
 *
 * <p>Scope is one and it is application-wide: each type has at most one instance per assembly, created during
 * {@code build()} and returned by every later lookup. There is no request, session, prototype, thread or
 * transaction scope (ADR-0014).
 *
 * <p>Ownership follows creation, which keeps the two release paths from overlapping:
 * <ul>
 *   <li>{@link Builder#factory} - this assembly created the instance, so {@link #close()} closes it when it is
 *       {@link AutoCloseable}, in reverse creation order, meaning a dependent is released before what it depends
 *       on;
 *   <li>{@link Builder#instance} - the caller created it, the caller releases it, {@link #close()} never touches
 *       it. A component that is also a {@code LifecycleParticipant} is registered this way, because the
 *       orchestrator drives its {@code stop()} and two owners releasing one object is how double release
 *       happens.
 * </ul>
 *
 * <p>Instances are reached by type, one per type, and a second registration of the same type is refused rather
 * than resolved by preference: there is no {@code @Primary}, no qualifier and no name, so an application that
 * wants two implementations of one interface registers two distinct types and picks between them in its own
 * assembly code (ADR-0014).
 *
 * <p>Thread safety: {@code build()} runs before the application has threads of its own. Afterwards lookups read
 * a finished map nothing writes, so they are safe from any thread. {@link #close()} is a shutdown operation and
 * is not meant to run concurrently with use.
 */
public final class ServiceAssembly implements Services, AutoCloseable {

  private final Map<Class<?>, Object> instances;
  private final List<Object> owned;

  private boolean closed;

  private ServiceAssembly(Map<Class<?>, Object> instances, List<Object> owned) {
    this.instances = Collections.unmodifiableMap(new LinkedHashMap<>(instances));
    this.owned = List.copyOf(owned);
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public <T> T require(Class<T> type) {
    return find(type).orElseThrow(() -> new AssemblyException(
        "nothing is registered as " + type.getName() + "; a type has to be registered to be resolved"));
  }

  @Override
  public <T> Optional<T> find(Class<T> type) {
    Objects.requireNonNull(type, "type must not be null");
    Object value = instances.get(type);
    if (value == null) {
      return Optional.empty();
    }
    // Nothing to validate here: build() already refused any instance that was not the type it was registered
    // under, so a lookup reads a map whose contents are known good.
    return Optional.of(type.cast(value));
  }

  /**
   * Releases the instances this assembly created, newest first, so an object is released before the objects that
   * depend on it. Instances registered already built stay with their caller and are not touched.
   *
   * <p>Best effort, in the shape the lifecycle engine uses for shutdown: every instance is attempted even if one
   * throws, and the failures are reported together once the rest have been tried.
   *
   * @throws AssemblyException if any instance failed to close; the first failure is the cause and the remainder
   *     are attached as suppressed
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    List<Exception> failures = releaseAll(owned);
    if (!failures.isEmpty()) {
      throw aggregate(failures);
    }
  }

  /**
   * Closes every {@link AutoCloseable} in {@code created}, newest first, and collects what went wrong instead of
   * throwing. Shared with the failed-build path in {@link Builder#build()} so "whoever created it releases it" has
   * exactly one implementation of the order and the best-effort rule.
   *
   * @return the failures, in the order they happened
   */
  private static List<Exception> releaseAll(List<Object> created) {
    List<Exception> failures = new ArrayList<>();
    for (int i = created.size() - 1; i >= 0; i--) {
      if (created.get(i) instanceof AutoCloseable closeable) {
        try {
          closeable.close();
        } catch (Exception e) {
          failures.add(e);
        }
      }
    }
    return failures;
  }

  /** One exception carrying the whole set: the first as the cause, the rest as suppressed. */
  private static AssemblyException aggregate(List<Exception> failures) {
    AssemblyException failure = new AssemblyException(
        failures.size() + " instance(s) created by this assembly failed to close", failures.get(0));
    for (int i = 1; i < failures.size(); i++) {
      failure.addSuppressed(failures.get(i));
    }
    return failure;
  }

  /**
   * Registers the objects an application is made of. Two methods, one per ownership rule, and nothing that
   * influences resolution: there is no order, priority, qualifier, profile or conditional, because those are the
   * features that let a container decide things its author did not (ADR-0014).
   *
   * <p>Registration order is not a dependency statement - it only decides what gets created first when nothing
   * asks. A dependency is stated where it belongs, in the factory that resolves it.
   */
  public static final class Builder {

    private record Binding(Class<?> type, Function<Services, ?> production, boolean assemblyOwnsIt) {}

    private final Map<Class<?>, Binding> bindings = new LinkedHashMap<>();

    private boolean built;

    private Builder() {}

    /**
     * Registers an object the caller already built. It is handed to dependents and never closed by this
     * assembly, because this assembly did not create it.
     *
     * @throws AssemblyException if {@code type} is already registered
     */
    public <T> Builder instance(Class<T> type, T object) {
      Objects.requireNonNull(type, "type must not be null");
      Objects.requireNonNull(object, "the object registered as " + type.getName() + " must not be null");
      // No type check here: build() validates what every registration produced, whichever of the two ways it
      // was made, so there is one place where "is this the object it claims to be" is decided.
      register(new Binding(type, ignored -> object, false));
      return this;
    }

    /**
     * Registers how to build an object. The factory receives a {@link Services} view, resolves what it needs,
     * and the object it returns becomes this assembly's to close.
     *
     * <p>A factory that needs nothing is {@code services -> new X()}. Writing the dependency down is the point of
     * registering a factory rather than a class: the alternative is choosing a constructor by reflection and
     * discovering what it wants, which is the mechanism that turns an assembler into a bean factory (ADR-0014).
     *
     * <p>The view is valid while this assembly is building and refuses afterwards, so it cannot be stored in the
     * object being built and used as a locator later. Pass finished objects in through a constructor.
     *
     * @throws AssemblyException if {@code type} is already registered
     */
    public <T> Builder factory(Class<T> type, Function<Services, ? extends T> production) {
      Objects.requireNonNull(type, "type must not be null");
      Objects.requireNonNull(production, "the factory registered for " + type.getName() + " must not be null");
      register(new Binding(type, production, true));
      return this;
    }

    /**
     * Creates every registered object and freezes the result.
     *
     * <p>Objects are created in registration order, and a dependency is created the moment something first asks
     * for it, so creation order is always dependencies before dependents - which is what lets {@link
     * ServiceAssembly#close()} release in reverse and be correct.
     *
     * <p>If a factory fails, the objects this builder had already created are closed before the failure is
     * rethrown, so an application that never started does not hold a half-built graph with no owner. The
     * factory's own exception is what propagates; a failure during that cleanup is attached as suppressed rather
     * than replacing it (ADR-0004). Without this, a factory that opens a file handle before throwing would leak it
     * and hand the caller nothing to close, because no {@link ServiceAssembly} was ever returned.
     *
     * <p>A factory that throws is otherwise not wrapped: the application's own failure is the interesting part, and
     * burying it under an assembly error loses the stack trace that names the cause.
     *
     * @throws AssemblyException for a duplicate registration during {@code build()}, a missing dependency, a
     *     cycle, or a factory that returned {@code null}
     */
    public ServiceAssembly build() {
      if (built) {
        throw new AssemblyException("this builder has already been asked to build once; a second call would run"
            + " the factories again and orphan the objects from the first, so start a new builder");
      }
      built = true;
      Resolution resolution = new Resolution(bindings);
      try {
        resolution.createEverything();
      } catch (RuntimeException | Error failure) {
        List<Exception> cleanupFailures = ServiceAssembly.releaseAll(resolution.owned());
        if (!cleanupFailures.isEmpty()) {
          AssemblyException cleanup = ServiceAssembly.aggregate(cleanupFailures);
          cleanup.addSuppressed(failure);
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
      return new ServiceAssembly(resolution.instances(), resolution.owned());
    }

    private void register(Binding binding) {
      if (bindings.containsKey(binding.type())) {
        throw new AssemblyException("more than one thing is registered as " + binding.type().getName()
            + "; two candidates for one type cannot be resolved without a preference rule, and this"
            + " framework has none, so the ambiguity is refused here rather than decided by order"
            + " of registration");
      }
      bindings.put(binding.type(), binding);
    }
  }

  /**
   * Creates the graph, once. A separate object rather than {@code Builder} methods, so a builder cannot resolve
   * a type after {@code build()} has returned, no lookup API is exposed on it, and the view handed to a factory
   * can be switched off when resolution ends.
   */
  private static final class Resolution implements Services {

    private final Map<Class<?>, Builder.Binding> bindings;
    private final Map<Class<?>, Object> instances = new LinkedHashMap<>();
    private final List<Object> owned = new ArrayList<>();

    /** Types being created right now, outermost first, so a cycle can be reported as a path. */
    private final List<Class<?>> inProgress = new ArrayList<>();

    /** Set once every registered object exists, which is when a leaked view starts being refused. */
    private boolean finished;

    Resolution(Map<Class<?>, Builder.Binding> bindings) {
      this.bindings = bindings;
    }

    void createEverything() {
      for (Builder.Binding binding : bindings.values()) {
        create(binding);
      }
      finished = true;
    }

    @Override
    public <T> T require(Class<T> type) {
      refuseLateLookup(type);
      // The requester is always known when this answers: a factory only runs while its own type is being created,
      // so the innermost type in progress is the one that asked.
      String asker = inProgress.get(inProgress.size() - 1).getName();
      return find(type).orElseThrow(() -> new AssemblyException("nothing is registered as " + type.getName()
          + ", which " + asker + " asked for; register it before building"));
    }

    @Override
    public <T> Optional<T> find(Class<T> type) {
      Objects.requireNonNull(type, "type must not be null");
      refuseLateLookup(type);
      if (!instances.containsKey(type)) {
        Builder.Binding binding = bindings.get(type);
        if (binding == null) {
          return Optional.empty();
        }
        create(binding);
      }
      return Optional.of(type.cast(instances.get(type)));
    }

    /**
     * Refuses a lookup made through a factory's view after {@code build()} returned.
     *
     * <p>Reachable only if a factory handed the view to the object it built and that object asked later. It is
     * refused rather than served: the view exists to state dependencies during assembly, and a component still
     * holding it afterwards has turned itself into a service locator - the pattern this layer exists to prevent
     * (ADR-0006, ADR-0014). Serving it would be the one place where that pattern works.
     */
    private void refuseLateLookup(Class<?> type) {
      if (finished) {
        throw new AssemblyException("a lookup for " + type.getName() + " was made through the view a factory was"
            + " given, after this assembly finished building; a component that still holds that view is a service"
            + " locator, so take the dependency in its constructor instead");
      }
    }

    Map<Class<?>, Object> instances() {
      return instances;
    }

    List<Object> owned() {
      return owned;
    }

    private void create(Builder.Binding binding) {
      if (instances.containsKey(binding.type())) {
        return;
      }
      if (inProgress.contains(binding.type())) {
        throw new AssemblyException(cyclePath(binding.type()));
      }
      inProgress.add(binding.type());
      try {
        Object instance = binding.production().apply(this);
        if (instance == null) {
          throw new AssemblyException("the factory for " + binding.type().getName()
              + " returned null; register nothing instead of a null object");
        }
        if (!binding.type().isInstance(instance)) {
          // The only way here is a registration that cast away its own type parameters, which the compiler
          // otherwise forbids. It is checked once, where both registration paths land, rather than twice.
          throw new AssemblyException("what was registered as " + binding.type().getName() + " produced a "
              + instance.getClass().getName() + ", which is not an instance of it");
        }
        instances.put(binding.type(), instance);
        if (binding.assemblyOwnsIt()) {
          owned.add(instance);
        }
      } finally {
        inProgress.remove(binding.type());
      }
    }

    private String cyclePath(Class<?> target) {
      StringBuilder path = new StringBuilder("dependency cycle: ");
      for (Class<?> step : inProgress) {
        path.append(step.getName()).append(" -> ");
      }
      path.append(target.getName());
      return path + "; an object cannot be built from something that is still waiting for it, so there"
          + " is no order in which these could be constructed";
    }
  }
}
