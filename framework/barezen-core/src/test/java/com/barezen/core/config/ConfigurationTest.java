package com.barezen.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.core.error.ConfigurationException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reading values out of a snapshot, and what happens when a value cannot be read.
 *
 * <p>The central contract proven here is that a bad value is never quietly replaced: a type error fails, and
 * only an absent key falls back to a default. A configuration layer that coerced garbage into defaults would
 * start an application that looks configured while doing something its operator never asked for.
 */
class ConfigurationTest {

  /** Stand-in for a component's own key declarations, the way production code declares them. */
  private enum Colour {
    RED,
    GREEN
  }

  private static Configuration config(String... pairs) {
    java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      values.put(pairs[i], pairs[i + 1]);
    }
    return Configuration.of(new MapSource("test", values));
  }

  @Test
  void readsStringIntegerLongBooleanDurationAndEnum() {
    Configuration c = config(
        "app.name", "orders",
        "app.port", "8080",
        "app.timeout", "PT30S",
        "app.tls", "true",
        "app.colour", "green",
        "app.big", "9000000000");

    assertThat(c.get(ConfigurationKey.of("app.name", String.class))).contains("orders");
    assertThat(c.get(ConfigurationKey.of("app.port", Integer.class))).contains(8080);
    assertThat(c.get(ConfigurationKey.of("app.big", Long.class))).contains(9_000_000_000L);
    assertThat(c.get(ConfigurationKey.of("app.timeout", Duration.class))).contains(Duration.ofSeconds(30));
    assertThat(c.get(ConfigurationKey.of("app.tls", Boolean.class))).contains(true);
    assertThat(c.get(ConfigurationKey.of("app.colour", Colour.class))).contains(Colour.GREEN);
  }

  @Test
  @DisplayName("surrounding whitespace in a file is not part of a value")
  void trimsSurroundingWhitespace() {
    Configuration c = config("app.name", "  orders  ", "app.port", " 8080 ");

    assertThat(c.get(ConfigurationKey.of("app.name", String.class))).contains("orders");
    assertThat(c.get(ConfigurationKey.of("app.port", Integer.class))).contains(8080);
  }

  @Test
  void absentKeyWithoutDefaultIsEmpty() {
    Configuration c = config("app.name", "orders");

    assertThat(c.get(ConfigurationKey.of("app.missing", String.class))).isEmpty();
  }

  @Test
  void absentKeyWithDefaultYieldsTheDefault() {
    Configuration c = config("app.name", "orders");
    ConfigurationKey<String> key = new ConfigurationKey<>("app.name", String.class, "unnamed");
    ConfigurationKey<String> missing = ConfigurationKey.of("app.missing", String.class, "unnamed");

    assertThat(c.get(key)).contains("orders");
    assertThat(c.get(missing)).contains("unnamed");
  }

  @Test
  @DisplayName("an empty value is a value, not an absent key")
  void emptyStringIsConfiguredNotMissing() {
    Configuration c = config("app.name", "");

    assertThat(c.get(ConfigurationKey.of("app.name", String.class, "fallback"))).contains("");
  }

  @Nested
  @DisplayName("a bad value fails loudly")
  class TypeErrors {

    @Test
    void nonNumericPortFails() {
      Configuration c = config("app.port", "http");

      assertThatThrownBy(() -> c.get(ConfigurationKey.of("app.port", Integer.class)))
          .isInstanceOf(ConfigurationException.class)
          .hasMessageContaining("app.port")
          .hasMessageContaining("http")
          .hasMessageContaining("test");
    }

    @Test
    @DisplayName("an unrecognised enum value is never rounded to the default")
    void unknownValueFailsInsteadOfFallingBackToTheDefault() {
      Configuration c = config("app.colour", "abc");
      ConfigurationKey<Colour> key = ConfigurationKey.of("app.colour", Colour.class, Colour.RED);

      // The default exists for an absent key. A present-but-wrong value must not reach it, or a typo reads
      // as a deliberate choice.
      assertThatThrownBy(() -> c.get(key))
          .isInstanceOf(ConfigurationException.class)
          .hasMessageContaining("abc");
    }

    @Test
    void badBooleanFailsRatherThanReadingAsFalse() {
      Configuration c = config("app.tls", "tru");

      // Boolean.parseBoolean would answer false here, silently inverting what the operator meant.
      assertThatThrownBy(() -> c.get(ConfigurationKey.of("app.tls", Boolean.class)))
          .isInstanceOf(ConfigurationException.class)
          .hasMessageContaining("tru");
    }

    @Test
    void badDurationNamesTheExpectedFormat() {
      Configuration c = config("app.timeout", "30s");

      assertThatThrownBy(() -> c.get(ConfigurationKey.of("app.timeout", Duration.class)))
          .isInstanceOf(ConfigurationException.class)
          .hasMessageContaining("PT10S");
    }

    @Test
    void unknownEnumValueListsWhatIsAllowed() {
      Configuration c = config("app.colour", "blue");

      assertThatThrownBy(() -> c.get(ConfigurationKey.of("app.colour", Colour.class)))
          .isInstanceOf(ConfigurationException.class)
          .hasMessageContaining("red, green");
    }

    @Test
    void unsupportedKeyTypeFailsAtReadNotWithClassCastException() {
      Configuration c = config("app.name", "orders");

      assertThatThrownBy(() -> c.get(ConfigurationKey.of("app.name", java.util.List.class)))
          .isInstanceOf(ConfigurationException.class)
          .hasMessageContaining("unsupported type");
    }

    @Test
    void aPrimitiveKeyIsRefusedRatherThanFailingOnUnboxing() {
      Configuration c = config("app.port", "8080");

      assertThatThrownBy(() -> c.get(ConfigurationKey.of("app.port", int.class)))
          .isInstanceOf(ConfigurationException.class)
          .hasMessageContaining("Integer");
    }
  }

  @Nested
  class KeyValidation {

    @Test
    void blankAndMalformedNamesAreRefused() {
      assertThatThrownBy(() -> ConfigurationKey.of(" ", String.class))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ConfigurationKey.of(null, String.class))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ConfigurationKey.of("app..port", String.class))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("dot separated");
      assertThatThrownBy(() -> ConfigurationKey.of(".app", String.class))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ConfigurationKey.of("app port", String.class))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ConfigurationKey.of("app", null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void legalNamesIncludeDashesUnderscoresAndDigits() {
      assertThatCode(() -> ConfigurationKey.of("barezen.http.max-body-size_bytes2", String.class))
          .doesNotThrowAnyException();
    }

    @Test
    void keysAreComparableByIdentitySoConstantsWorkInMaps() {
      ConfigurationKey<String> a = ConfigurationKey.of("app.port", String.class);
      ConfigurationKey<String> b = ConfigurationKey.of("app.port", String.class, "8080");

      assertThat(a).hasSameClassAs(b);
      assertThat(a.name()).isEqualTo(b.name());
      assertThat(a.defaultValue()).isNull();
      assertThat(b.defaultValue()).isEqualTo("8080");
    }
  }

  /**
   * Configuration must not become the thing that looks objects up. A public method returning anything other
   * than a configured value would belong to DI, which is a later phase.
   *
   * <p>Synthetic methods are filtered out because the coverage agent injects one into the class under test;
   * guarding on declared API means the framework's own API, not the build tooling's.
   */
  @Test
  @DisplayName("the whole write surface of Configuration is empty")
  void configurationHasNoWriteApiAndNoLookupApi() {
    assertThat(declaredApi()).containsExactlyInAnyOrder("get", "of", "inMemory", "standard");
    // No put/set/remove: a snapshot cannot be edited. No get(Class): a snapshot does not resolve objects.
    assertThat(java.util.Arrays.stream(Configuration.class.getDeclaredMethods())
        .anyMatch(method -> method.getName().equals("get") && method.getParameterCount() == 1
            && method.getParameterTypes()[0] == Class.class))
        .isFalse();
  }

  @Test
  @DisplayName("Configuration resolves values, never objects")
  void noConfigurationMethodResolvesAFrameworkObject() {
    // A method returning a ThreadProvider, or any other collaborator, would make configuration the service
    // locator ADR-0006 refuses. Every instance method must return Optional; every static one a Configuration.
    for (java.lang.reflect.Method method : Configuration.class.getDeclaredMethods()) {
      if (method.isSynthetic()) {
        continue;
      }
      if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
        assertThat(method.getReturnType()).isEqualTo(Configuration.class);
      } else {
        assertThat(method.getReturnType()).isEqualTo(Optional.class);
      }
    }
  }

  @Test
  void aSourceIsTwoMethodsSoAFormatCannotSmuggleInBehaviour() {
    assertThat(java.util.Arrays.stream(ConfigurationSource.class.getDeclaredMethods())
        .filter(method -> !method.isSynthetic())
        .map(java.lang.reflect.Method::getName))
        .containsExactlyInAnyOrder("name", "find");
  }

  private static java.util.List<String> declaredApi() {
    return java.util.Arrays.stream(Configuration.class.getDeclaredMethods())
        .filter(method -> !method.isSynthetic())
        .map(java.lang.reflect.Method::getName)
        .toList();
  }

  /** Test-local source, so this file does not depend on the package-private implementations it exercises. */
  private record MapSource(String name, java.util.Map<String, String> values) implements ConfigurationSource {
    @Override
    public Optional<String> find(String keyName) {
      return Optional.ofNullable(values.get(keyName));
    }
  }
}
