package com.barezen.console.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The emitted-bytecode half of the JDK 17 baseline (ADR-0032): every class file <em>this module</em> compiled has
 * class-file major version 61, the version JDK 17 reads.
 *
 * <p>{@code scripts/check-jdk-baseline.sh} asserts the <em>declared</em> release, and a declaration is not the
 * output: {@code -Dmaven.compiler.release=21} on the command line satisfies that script and emits major 65 anyway,
 * which is bytecode a JDK 17 deployment cannot load. This test asserts the bytes.
 *
 * <p>It is a test and not only a script because a test runs inside {@code mvn verify} — the command this project
 * treats as the definition of green — while a script is a job step someone can leave out. It is scoped to this
 * module, and one copy lives in every module that emits class files, because reactor ordering makes a wider in-build
 * scope impossible: this module's tests run before a later module has been compiled, so after {@code clean} there is
 * nothing of that module's to read. {@code scripts/check-class-major.sh} is the whole-reactor assertion, made after
 * the build wrote every module, and the third test below checks the two agree.
 */
@DisplayName("emitted class files are major 61 (ADR-0032)")
class ClassMajorContractTest {

  /** The major version {@code --release 17} must emit: JDK 17's class-file version. */
  private static final int EXPECTED_MAJOR = 61;

  /** The class-file magic, read before any major so a truncated or foreign file says so instead of misreporting. */
  private static final int MAGIC = 0xCAFEBABE;

  @Test
  @DisplayName("every class file this module compiled is major 61")
  void everyEmittedClassFileIsMajor61() throws IOException, URISyntaxException {
    List<String> offenders = new ArrayList<>();
    TreeSet<Integer> majors = new TreeSet<>();
    int checked = 0;
    for (Path directory : moduleOutputs()) {
      List<Path> files = classFilesUnder(directory);
      // A module with sources that emitted nothing means this test read the wrong tree, and a contract that can pass
      // on zero files is not a contract.
      assertThat(files).as(directory + " holds the class files this module compiled").isNotEmpty();
      for (Path classFile : files) {
        checked++;
        int[] header = readHeader(classFile);
        if (header[0] != MAGIC) {
          offenders.add(classFile + " is not a class file (magic " + Integer.toHexString(header[0]) + ")");
          continue;
        }
        majors.add(header[1]);
        if (header[1] != EXPECTED_MAJOR) {
          offenders.add(classFile + " is major " + header[1]);
        }
      }
    }

    // The three numbers the phase asks this automation to print, so a log states what was verified instead of only
    // that something passed.
    System.out.println("class-major: checked=" + checked + " unique-majors=" + majors
        + " expected=" + EXPECTED_MAJOR);

    assertThat(offenders)
        .as("class files the JDK 17 baseline does not allow; every module compiles with --release 17")
        .isEmpty();
    assertThat(majors).as("one major version across this module's outputs").containsExactly(EXPECTED_MAJOR);
  }

  @Test
  @DisplayName("the expected major is 61, the version JDK 17 reads")
  void expectedMajorIsTheJdkSeventeenVersion() {
    // A literal on the right, so editing EXPECTED_MAJOR to match a build that emitted something else fails here.
    // That edit is the phase's mutation M10, and this is the named place it shows up.
    assertThat(EXPECTED_MAJOR).as("--release 17 means class-file major 61").isEqualTo(61);
  }

  @Test
  @DisplayName("the shell guard exists, is executable, and pins the same expected major")
  void theWholeReactorGuardAgreesWithThisTest() throws IOException {
    // The build and the CI job assert one contract, so they must not drift into two different numbers.
    Path guard = repoRoot().resolve("scripts/check-class-major.sh");
    assertThat(Files.isExecutable(guard))
        .as("%s runs ./scripts/check-class-major.sh, so it must exist and be executable", guard)
        .isTrue();
    String script = Files.readString(guard);
    assertThat(script).contains("expected_major=" + EXPECTED_MAJOR);
    assertThat(script).contains("classes").contains("test-classes");
  }

  @Test
  @DisplayName("the reader reports a wrong major and a foreign file instead of skipping them")
  void theReaderDistinguishesAWrongMajorFromANonClassFile() throws IOException {
    // Without this, the check above could pass because the reader always answered 61. Two forged eight-byte headers,
    // read by the same method that reads the build output: one is a real JDK 21 emission's major, one is not a class
    // file at all.
    Path sixtyFive = Files.createTempFile("class-major-probe", ".class");
    Path foreign = Files.createTempFile("class-major-probe", ".class");
    try {
      Files.write(sixtyFive, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 65});
      Files.write(foreign, new byte[] {'P', 'K', 3, 4, 0, 0, 0, 65});
      assertThat(readHeader(sixtyFive)).as("major 65 is read, not skipped").containsExactly(MAGIC, 65);
      assertThat(readHeader(sixtyFive)[1]).isNotEqualTo(EXPECTED_MAJOR);
      assertThat(readHeader(foreign)[0]).as("a foreign header is not mistaken for a class file").isNotEqualTo(MAGIC);
    } finally {
      Files.deleteIfExists(sixtyFive);
      Files.deleteIfExists(foreign);
    }
  }

  /** The reactor root, found by walking up from this module's directory to the file that owns the baseline. */
  private static Path repoRoot() throws IOException {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null && !Files.isRegularFile(candidate.resolve("scripts/check-jdk-baseline.sh"))) {
      candidate = candidate.getParent();
    }
    if (candidate == null) {
      throw new IOException("no ancestor directory holds scripts/check-jdk-baseline.sh;"
          + " this test must run inside the BareZen-web reactor");
    }
    return candidate;
  }

  /**
   * This module's {@code target/classes} and {@code target/test-classes}, located through the running test's own
   * code source, so the assertion follows what surefire actually loaded rather than a path convention written down
   * beside it.
   */
  private static List<Path> moduleOutputs() throws IOException, URISyntaxException {
    Path testClasses = Path.of(ClassMajorContractTest.class.getProtectionDomain().getCodeSource()
        .getLocation().toURI());
    Path classes = testClasses.getParent().resolve("classes");
    for (Path output : List.of(classes, testClasses)) {
      if (!Files.isDirectory(output)) {
        throw new IOException(output + " does not exist; this test ran from outside a build output");
      }
    }
    return List.of(classes, testClasses);
  }

  private static List<Path> classFilesUnder(Path directory) throws IOException {
    try (Stream<Path> walk = Files.walk(directory)) {
      return walk.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".class"))
          .sorted()
          .toList();
    }
  }

  /** {@code [magic, major]} from a class file's first eight bytes, big-endian. */
  private static int[] readHeader(Path classFile) throws IOException {
    byte[] head = new byte[8];
    try (InputStream in = Files.newInputStream(classFile)) {
      int read = 0;
      while (read < head.length) {
        int n = in.read(head, read, head.length - read);
        if (n < 0) {
          throw new IOException(classFile + " ends after " + read + " bytes, before a class header");
        }
        read += n;
      }
    }
    int magic = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
    return new int[] {magic, ((head[6] & 0xFF) << 8) | (head[7] & 0xFF)};
  }
}
