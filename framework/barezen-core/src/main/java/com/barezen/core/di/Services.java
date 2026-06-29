package com.barezen.core.di;

import com.barezen.core.error.AssemblyException;
import java.util.Optional;

/**
 * The created objects of one application, as seen by the factories that build them.
 *
 * <p>Two methods, deliberately, and both take a type. This is the view a factory receives while the graph is being
 * resolved. It is not an application-wide lookup service: a business component that takes a {@code Services}
 * parameter can resolve whatever it likes, whenever it likes, which moves its dependencies out of the constructor
 * and into its body - the service locator this framework rejects (ADR-0006, ADR-0014). Dependencies belong in a
 * constructor. Only the assembly and the factories it registers are supposed to hold this type, and two ArchUnit
 * rules keep that from spreading: nothing outside {@code com.barezen.core.di} in core may depend on it at all, and
 * no public method outside that package may take a {@code Class} under a lookup-shaped name.
 *
 * <p>{@link ServiceAssembly#build()} resolves the whole graph before returning, and the view a factory receives
 * stops answering afterwards. A factory that hands its view to the object it built therefore finds that object
 * cannot use it, which is the point: the alternative is a component that resolves its own dependencies at runtime,
 * and once that works it spreads.
 */
public interface Services {

  /**
   * Returns the object bound to {@code type}.
   *
   * <p>{@code type} is an abstraction or a concrete class, matching how it was registered. A type nobody bound is
   * an assembly mistake, not a {@code null} the caller notices later: the failure names the missing type and,
   * inside a factory, the type that asked for it.
   *
   * <p>The two implementations of this interface differ in one respect. The view a factory receives creates a
   * not-yet-built dependency on the first request, which is how a dependency ends up existing before the object
   * that needs it; the view the finished assembly offers only ever reads what {@code build()} already made.
   *
   * @return the object bound to {@code type}, the same object for every call within one assembly
   * @throws AssemblyException if nothing is bound to {@code type}
   * @throws NullPointerException if {@code type} is {@code null}
   */
  <T> T require(Class<T> type);

  /**
   * @return the object bound to {@code type}, or empty when nothing is bound to it
   * @throws NullPointerException if {@code type} is {@code null}
   */
  <T> Optional<T> find(Class<T> type);
}
