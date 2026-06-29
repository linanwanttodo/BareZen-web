package com.barezen.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.core.error.ConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Configuration#standard} against the process's real configuration inputs, which is the one part of the
 * layering a fabricated source list cannot prove.
 *
 * <p>These tests are the exception to the rule that a test may not touch global state, because what is under
 * test is precisely that production reads global state. Two limits follow: only keys this build creates are
 * read, and every {@code -D} property a test sets it removes again, so no other test sees a value this one
 * invented.
 *
 * <p>The environment layer is asserted against a variable the process always has rather than one a CI job
 * would have to export, which keeps the proof identical locally and in CI. A JVM cannot set its own
 * environment through the public API, so inventing a variable here is not an option; overriding behaviour for
 * invented variables is proven in {@link ConfigurationPrecedenceTest} instead.
 */
class StandardConfigurationTest {

  private static final String FILE_KEY = "barezen.it.from-file";
  private static final String SHADOWED_KEY = "barezen.it.shadowed";

  @AfterEach
  void removeSystemPropertiesThisTestSet() {
    System.clearProperty(FILE_KEY);
    System.clearProperty(SHADOWED_KEY);
  }

  private static Configuration standard(Path directory) {
    return Configuration.standard(directory.resolve("application.properties"));
  }

  @Test
  @DisplayName("a packaged file contributes values with no other layer present")
  void readFileThroughStandardWiring(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("application.properties"), FILE_KEY + "=from-file\n");

    assertThat(standard(directory).get(ConfigurationKey.of(FILE_KEY, String.class))).contains("from-file");
  }

  @Test
  @DisplayName("a -D flag overrides the file, which is the layering an operator relies on")
  void systemPropertyOverridesTheFile(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("application.properties"), SHADOWED_KEY + "=from-file\n");
    System.setProperty(SHADOWED_KEY, "from-command-line");

    assertThat(standard(directory).get(ConfigurationKey.of(SHADOWED_KEY, String.class)))
        .contains("from-command-line");
  }

  @Test
  @DisplayName("the file still wins for keys the command line does not mention")
  void layersShadowPerKeyRatherThanAsABlock(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("application.properties"),
        SHADOWED_KEY + "=from-file\n" + FILE_KEY + "=also-from-file\n");
    System.setProperty(SHADOWED_KEY, "from-command-line");

    Configuration configuration = standard(directory);
    assertThat(configuration.get(ConfigurationKey.of(SHADOWED_KEY, String.class)))
        .contains("from-command-line");
    assertThat(configuration.get(ConfigurationKey.of(FILE_KEY, String.class))).contains("also-from-file");
  }

  @Test
  @DisplayName("a snapshot taken before a property is set does not see it")
  void standardIsASnapshotNotALiveView(@TempDir Path directory) {
    Configuration configuration = standard(directory);

    System.setProperty(FILE_KEY, "set-after-the-snapshot");

    assertThat(configuration.get(ConfigurationKey.of(FILE_KEY, String.class))).isEmpty();
  }

  @Test
  @DisplayName("a missing file is not an error, the higher layers keep working")
  void absentFileIsNotAnError(@TempDir Path directory) {
    System.setProperty(FILE_KEY, "from-command-line");

    assertThat(standard(directory).get(ConfigurationKey.of(FILE_KEY, String.class)))
        .contains("from-command-line");
  }

  @Test
  @DisplayName("the environment layer of the standard wiring reads the real process environment")
  void environmentLayerIsLive(@TempDir Path directory) {
    // Deliberately not a barezen.* key: a test cannot create an environment variable for one, so this proves
    // the layer reads the live process environment by resolving a variable every build JVM has. That PATH has
    // no system property of the same name is what keeps the higher layer from shadowing it.
    String path = System.getenv("PATH");
    assertThat(path).as("a JVM running Maven always inherits a PATH").isNotBlank();

    assertThat(standard(directory).get(ConfigurationKey.of("path", String.class))).contains(path);
  }

  @Test
  @DisplayName("a bad value from a real file names the value, the key and the file")
  void aRealBadValueFailsWithTheFileName(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("application.properties"), FILE_KEY + "=not-a-number\n");
    Configuration configuration = standard(directory);

    assertThatThrownBy(() -> configuration.get(ConfigurationKey.of(FILE_KEY, Integer.class)))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("not-a-number")
        .hasMessageContaining(FILE_KEY)
        .hasMessageContaining(directory.resolve("application.properties").toString());
  }

  @Test
  @DisplayName("a key default applies through the standard wiring too")
  void keyDefaultsWorkThroughStandard(@TempDir Path directory) {
    ConfigurationKey<String> key = ConfigurationKey.of("barezen.it.absent.everywhere", String.class, "the-default");

    assertThat(standard(directory).get(key)).contains("the-default");
  }
}
