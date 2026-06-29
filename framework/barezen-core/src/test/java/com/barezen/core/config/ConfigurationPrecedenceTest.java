package com.barezen.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.core.error.ConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Precedence, proven as the framework's documented layering rather than as an arbitrary argument order.
 *
 * <p>The production ordering is defaults &lt; file &lt; environment &lt; system properties. Tests here build
 * the same four layers out of injectable sources, because a JVM's real environment and real properties are not
 * something a test may own: {@link Configuration#standard} is exercised against the true global state in
 * {@link StandardConfigurationTest}, which only ever reads keys this build controls.
 */
class ConfigurationPrecedenceTest {

  private static final ConfigurationKey<String> KEY = ConfigurationKey.of("barezen.sample.threads", String.class);

  private static Map<String, String> only(String value) {
    Map<String, String> map = new LinkedHashMap<>();
    map.put(KEY.name(), value);
    return map;
  }

  /** The environment name {@link #KEY} maps to, spelled as an operator would have to spell it. */
  private static Map<String, String> envOnly(String value) {
    return Map.of(EnvironmentVariablesSource.toVariableName(KEY.name()), value);
  }

  /** The four standard layers, with the given value in each of the supplied ones. */
  private static Configuration layered(String fileValue, String envValue, String systemValue) {
    return Configuration.of(
        PropertiesConfigurationSource.of("defaults", only("default")),
        PropertiesConfigurationSource.of("application.properties", fileValue == null ? Map.of() : only(fileValue)),
        EnvironmentVariablesSource.of(envValue == null ? Map.of() : envOnly(envValue)),
        SystemPropertiesSource.of(systemValue == null ? Map.of() : only(systemValue)));
  }

  @Test
  void eachLayerOverridesTheOneBelowIt() {
    assertThat(layered(null, null, null).get(KEY)).contains("default");
    assertThat(layered("file", null, null).get(KEY)).contains("file");
    assertThat(layered("file", "env", null).get(KEY)).contains("env");
    assertThat(layered("file", "env", "sys").get(KEY)).contains("sys");
    assertThat(layered(null, "env", null).get(KEY)).contains("env");
    assertThat(layered(null, null, "sys").get(KEY)).contains("sys");
  }

  @Test
  @DisplayName("the worked example from the specification resolves to the highest layer present")
  void fileSaysPlatformEnvSaysVirtualSystemSaysPlatform() {
    Configuration configuration = layered("platform", "virtual", "platform");

    assertThat(configuration.get(KEY)).contains("platform");
  }

  @Test
  @DisplayName("an override replaces a value instead of merging with it")
  void aHigherLayerShadowsRatherThanCombines() {
    // The env layer has the key; the file layer has a different key that must still be readable.
    Configuration configuration = Configuration.of(
        PropertiesConfigurationSource.of("file", Map.of("a.one", "from-file", "a.two", "only-in-file")),
        EnvironmentVariablesSource.of(Map.of("A_ONE", "from-env")));

    assertThat(configuration.get(ConfigurationKey.of("a.one", String.class))).contains("from-env");
    assertThat(configuration.get(ConfigurationKey.of("a.two", String.class))).contains("only-in-file");
  }

  @Test
  @DisplayName("a key's own default sits below every source")
  void defaultsNeverWinOverAConfiguredValue() {
    ConfigurationKey<String> withDefault =
        ConfigurationKey.of("barezen.sample.threads", String.class, "key-default");
    Configuration configuration = Configuration.of(
        PropertiesConfigurationSource.of("application.properties", only("from-file")));

    assertThat(configuration.get(withDefault)).contains("from-file");
  }

  @Test
  void anEmptyLayerListIsRejected() {
    assertThatThrownBy(() -> Configuration.of())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one source");
  }

  @Test
  void aNullLayerIsRejected() {
    assertThatThrownBy(() -> Configuration.of(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Nested
  class EnvironmentMapping {

    @Test
    void dotsBecomeUnderscoresAndNameBecomesUpperCase() {
      assertThat(EnvironmentVariablesSource.toVariableName("barezen.runtime.threads"))
          .isEqualTo("BAREZEN_RUNTIME_THREADS");
      assertThat(EnvironmentVariablesSource.toVariableName("barezen.http.max-body-size"))
          .isEqualTo("BAREZEN_HTTP_MAX-BODY-SIZE");
    }

    @Test
    void theMappedVariableIsWhatTheSourceReads() {
      Configuration configuration = Configuration.of(EnvironmentVariablesSource.of(
          Map.of("BAREZEN_RUNTIME_THREADS", "virtual")));

      assertThat(configuration.get(ConfigurationKey.of("barezen.runtime.threads", String.class)))
          .contains("virtual");
    }

    @Test
    @DisplayName("the literal dotted name is not looked for in the environment")
    void dottedNameIsNotMatchedDirectly() {
      Configuration configuration = Configuration.of(EnvironmentVariablesSource.of(
          Map.of("barezen.runtime.threads", "platform")));

      assertThat(configuration.get(ConfigurationKey.of("barezen.runtime.threads", String.class))).isEmpty();
    }

    @Test
    @DisplayName("an environment value is converted like any other, so a typo still fails")
    void badEnvironmentValueFails() {
      Configuration configuration = Configuration.of(EnvironmentVariablesSource.of(
          Map.of("BAREZEN_SAMPLE_THREADS", "  ")));

      // Blank after trimming is not a legal value for an Integer-typed key; it is not read as absent.
      assertThatThrownBy(() -> configuration.get(ConfigurationKey.of("barezen.sample.threads", Integer.class)))
          .isInstanceOf(ConfigurationException.class);
    }
  }

  @Nested
  class PropertiesFile {

    @Test
    void readsARealFileFromDisk(@TempDir Path directory) throws IOException {
      Path file = directory.resolve("application.properties");
      // Whitespace around the separator and the trailing-backslash continuation are java.util.Properties
      // syntax, not ours; the assertions state what Properties actually produced for this content.
      Files.writeString(file, """
          # a comment
          barezen.runtime.threads=platform
          barezen.http.port = 8080
          continuation=a\\
          b
          """);

      Configuration configuration = Configuration.of(PropertiesConfigurationSource.fromFile(file));

      assertThat(configuration.get(ConfigurationKey.of("barezen.runtime.threads", String.class)))
          .contains("platform");
      assertThat(configuration.get(ConfigurationKey.of("barezen.http.port", Integer.class))).contains(8080);
      assertThat(configuration.get(ConfigurationKey.of("continuation", String.class))).contains("ab");
    }

    @Test
    @DisplayName("a missing file is not an error, because defaults plus environment is a valid deployment")
    void missingFileContributesNothing(@TempDir Path directory) {
      Configuration configuration = Configuration.of(
          PropertiesConfigurationSource.fromFile(directory.resolve("absent.properties")),
          EnvironmentVariablesSource.of(Map.of("BAREZEN_RUNTIME_THREADS", "virtual")));

      assertThat(configuration.get(ConfigurationKey.of("barezen.runtime.threads", String.class)))
          .contains("virtual");
    }

    @Test
    void aFileThatCannotBeReadFailsInsteadOfBeingIgnored(@TempDir Path directory) throws IOException {
      Path directoryAsFile = directory.resolve("application.properties");
      Files.createDirectory(directoryAsFile);

      assertThatThrownBy(() -> PropertiesConfigurationSource.fromFile(directoryAsFile))
          .isInstanceOf(ConfigurationException.class)
          .hasMessageContaining("could not be read");
    }

    @Test
    @DisplayName("the file's path names it in a failure message, so an operator knows which layer to fix")
    void sourceNameIsTheFilePath(@TempDir Path directory) throws IOException {
      Path file = directory.resolve("application.properties");
      Files.writeString(file, "app.port=http");

      assertThatThrownBy(() -> Configuration.of(PropertiesConfigurationSource.fromFile(file))
          .get(ConfigurationKey.of("app.port", Integer.class)))
          .isInstanceOf(ConfigurationException.class)
          .hasMessageContaining(file.toString());
    }

    @Test
    void nonAsciiValuesSurviveAsWritten(@TempDir Path directory) throws IOException {
      Path file = directory.resolve("application.properties");
      Files.writeString(file, "app.greeting=こんにちは\n", java.nio.charset.StandardCharsets.UTF_8);

      // Properties.load(InputStream) would read ISO-8859-1 and produce mojibake; this source reads UTF-8.
      assertThat(Configuration.of(PropertiesConfigurationSource.fromFile(file))
          .get(ConfigurationKey.of("app.greeting", String.class)))
          .contains("こんにちは");
    }

    @Test
    void theMapPassedInIsCopiedSoTheSnapshotStaysFixed() {
      Map<String, String> mutable = new LinkedHashMap<>();
      mutable.put("app.name", "orders");
      Configuration configuration = Configuration.inMemory(mutable);

      mutable.put("app.name", "changed");

      assertThat(configuration.get(ConfigurationKey.of("app.name", String.class))).contains("orders");
    }
  }

  @Nested
  class SystemProperties {

    @Test
    void onlyStringKeyedPropertiesAreVisible() {
      Map<String, String> snapshot = new LinkedHashMap<>();
      snapshot.put(KEY.name(), "platform");

      Configuration configuration = Configuration.of(SystemPropertiesSource.of(snapshot));

      assertThat(configuration.get(KEY)).contains("platform");
    }

    @Test
    @DisplayName("a snapshot does not grow entries the layer did not have")
    void theSnapshotIsFixedAtConstruction() {
      Map<String, String> backing = new LinkedHashMap<>();
      Configuration configuration = Configuration.of(SystemPropertiesSource.of(backing));

      // Proves a source that reads global state hands out a copy rather than a live view; the equivalent for
      // the real System.getProperties() table is covered in StandardConfigurationTest.
      backing.put("late.key", "appeared after the snapshot");

      assertThat(configuration.get(ConfigurationKey.of("late.key", String.class))).isEmpty();
    }
  }
}
