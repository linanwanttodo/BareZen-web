package com.barezen.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.core.kernel.LifecycleParticipant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The registration verifier, tested against the orders a plugin set can be registered in — and the four ways it
 * cannot be.
 *
 * <p>The distinction this suite keeps visible: nothing here starts a plugin. {@link PluginRegistration} refuses,
 * orders, and stops; the phase it must not take over is {@code LifecycleOrchestrator}'s, and one test asserts the
 * verifier never calls a lifecycle method at all, which is the mechanical half of "no second lifecycle".
 */
class PluginRegistrationTest {

  /** A plugin that records which lifecycle phases were called on it, if ever. */
  private static class Probe implements Plugin {
    private final PluginDescriptor descriptor;
    final List<String> called = new ArrayList<>();

    Probe(String id, String... dependencies) {
      this.descriptor = new PluginDescriptor(id, List.of(dependencies));
    }

    @Override
    public PluginDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public void initialize() {
      called.add("initialize");
    }

    @Override
    public void start() {
      called.add("start");
    }

    @Override
    public void stop() {
      called.add("stop");
    }
  }

  @Nested
  @DisplayName("a set that can be registered")
  class Registrable {

    @Test
    @DisplayName("an empty set validates to an empty order")
    void emptyIsValidEmpty() {
      assertThat(PluginRegistration.validate(List.of())).isEmpty();
    }

    @Test
    @DisplayName("a lone plugin keeps its place")
    void oneIsOne() {
      Probe solo = new Probe("solo");
      assertThat(PluginRegistration.validate(List.of(solo))).containsExactly(solo);
    }

    @Test
    @DisplayName("a dependent returns after the plugin it needs, whatever order the list arrived in")
    void dependencyPrecedesDependent() {
      Probe base = new Probe("base");
      Probe needs = new Probe("needs", "base");
      assertThat(PluginRegistration.validate(List.of(needs, base))).extracting(p -> p.descriptor().id())
          .containsExactly("base", "needs");
      assertThat(PluginRegistration.validate(List.of(base, needs))).extracting(p -> p.descriptor().id())
          .containsExactly("base", "needs");
    }

    @Test
    @DisplayName("a transitive chain holds through the middle of a list")
    void transitiveChainHolds() {
      Probe a = new Probe("a");
      Probe b = new Probe("b", "a");
      Probe c = new Probe("c", "b");
      assertThat(PluginRegistration.validate(List.of(c, b, a))).extracting(p -> p.descriptor().id())
          .containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("a diamond orders by dependency and keeps the caller's order between unrelated siblings")
    void diamondOrdersByConstraintNotByName() {
      Probe a = new Probe("a");
      Probe b = new Probe("b", "a");
      Probe c = new Probe("c", "a");
      Probe d = new Probe("d", "b", "c");
      // b and c are unrelated (each only needs a), so their relative order is the caller's, not alphabetical:
      // the list arrived [d, c, b, a], so c precedes b in the output. Forcing a,b,c,d here would be the
      // alphabetical arbitrating the contract disclaims — this asserts the actual guarantee.
      assertThat(PluginRegistration.validate(List.of(d, c, b, a))).extracting(p -> p.descriptor().id())
          .containsExactly("a", "c", "b", "d");
    }

    @Test
    @DisplayName("independent plugins keep the caller's relative order — ADR-0016's rule, verbatim")
    void callerOrderSurvivesBetweenUnrelatedPlugins() {
      Probe x = new Probe("x");
      Probe y = new Probe("y");
      assertThat(PluginRegistration.validate(List.of(x, y))).extracting(p -> p.descriptor().id())
          .containsExactly("x", "y");
      assertThat(PluginRegistration.validate(List.of(y, x))).extracting(p -> p.descriptor().id())
          .containsExactly("y", "x");
    }

    @Test
    @DisplayName("the returned list is unmodifiable")
    void returnedOrderIsDefensive() {
      List<Plugin> ordered = PluginRegistration.validate(List.of(new Probe("solo")));
      assertThatThrownBy(() -> ordered.add(new Probe("sneak"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("validating never calls initialize, start or stop")
    void theVerifierDoesNotRunAnything() {
      Probe base = new Probe("base");
      Probe needs = new Probe("needs", "base");
      PluginRegistration.validate(List.of(needs, base));
      assertThat(base.called).as("ordering is a static fact about declarations").isEmpty();
      assertThat(needs.called).isEmpty();
    }
  }

  @Nested
  @DisplayName("the refusals")
  class Refusals {

    @Test
    @DisplayName("two plugins claiming one id are refused, both class names in the message")
    void duplicateIdRefused() {
      Probe first = new Probe("shared");
      Probe impostor = new Probe("shared");
      assertThatThrownBy(() -> PluginRegistration.validate(List.of(first, impostor)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("shared")
          .hasMessageContaining(Probe.class.getName());
    }

    @Test
    @DisplayName("a dependency the set does not contain is refused and named")
    void missingDependencyRefused() {
      Probe needs = new Probe("needs", "absent");
      assertThatThrownBy(() -> PluginRegistration.validate(List.of(needs)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("needs")
          .hasMessageContaining("absent");
    }

    @Test
    @DisplayName("a cycle is refused, naming every plugin stuck in it")
    void cycleRefused() {
      Probe left = new Probe("left", "right");
      Probe right = new Probe("right", "left");
      assertThatThrownBy(() -> PluginRegistration.validate(List.of(left, right)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cycle")
          .hasMessageContaining("left")
          .hasMessageContaining("right");
    }

    @Test
    @DisplayName("a self-dependency is refused by the descriptor, before the verifier sees the list")
    void selfDependencyRefusedEarlier() {
      assertThatThrownBy(() -> new PluginDescriptor("self", List.of("self")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("itself");
    }
  }

  @Nested
  @DisplayName("input shape, because refusals report ids and an id is the point")
  class InputShape {

    @Test
    @DisplayName("a null list and a null element are refused before the first descriptor() call")
    void nullsRefused() {
      assertThatThrownBy(() -> PluginRegistration.validate(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("empty list");
      // Arrays.asList, not List.of: the JDK would throw before the framework saw anything.
      assertThatThrownBy(() -> PluginRegistration.validate(Arrays.asList(new Probe("ok"), null)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("null");
    }

    @Test
    @DisplayName("a plugin returning a null descriptor is refused, naming the implementation")
    void nullDescriptorRefused() {
      Probe broken = new Probe("broken") {
        @Override
        public PluginDescriptor descriptor() {
          return null;
        }
      };
      assertThatThrownBy(() -> PluginRegistration.validate(List.of(broken)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("null descriptor")
          // The anonymous subclass names itself in the message (its own runtime class), which is the point: a
          // plugin that returns a null descriptor is untraceable by id, so the implementation class is the only
          // handle a fixer has.
          .hasMessageContaining("$1");
    }
  }

  @Nested
  @DisplayName("the plugin package stays what ADR-0016 made it")
  class PackageDiscipline {

    @Test
    @DisplayName("the verifier is a function, not a component: no instances, no lifecycle vocabulary")
    void noSecondLifecycleSurface() throws Exception {
      // Public surface, asserted mechanically: every method static, the constructor private, and no lifecycle
      // names reachable anywhere on the type — the shape a "registry" would need to become a container.
      assertThat(java.lang.reflect.Modifier.isPrivate(PluginRegistration.class.getDeclaredConstructors()[0]
          .getModifiers())).isTrue();
      for (var method : PluginRegistration.class.getDeclaredMethods()) {
        assertThat(java.lang.reflect.Modifier.isStatic(method.getModifiers()))
            .as("%s is a static function", method.getName()).isTrue();
      }
      assertThat(PluginRegistration.class.getDeclaredFields()).extracting(java.lang.reflect.Field::getName)
          .as("no state a registry could leak from")
          .isEmpty();
      assertThat(LifecycleParticipant.class.isAssignableFrom(PluginRegistration.class))
          .as("not even a participant — nothing here has a phase")
          .isFalse();
    }
  }
}
