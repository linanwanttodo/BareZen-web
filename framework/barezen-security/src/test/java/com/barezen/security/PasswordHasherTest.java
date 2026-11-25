package com.barezen.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PasswordHasher")
class PasswordHasherTest {

  /** A fast hasher for tests: 1,000 iterations instead of 600,000, same construction. */
  private static final PasswordHasher HASHER = PasswordHasher.of(1_000, new SecureRandom(new byte[32]));

  @Nested
  @DisplayName("hash")
  class Hash {

    @Test
    @DisplayName("produces a self-describing encoded form: algorithm, iterations, salt, derived key")
    void selfDescribingFormat() {
      String encoded = HASHER.hash("correct horse battery staple");
      String[] parts = encoded.split("\\$", -1);
      assertThat(parts).hasSize(4);
      assertThat(parts[0]).isEqualTo("PBKDF2WithHmacSHA256");
      assertThat(parts[1]).isEqualTo("1000");
      assertThat(Base64.getDecoder().decode(parts[2])).hasSize(16);
      assertThat(Base64.getDecoder().decode(parts[3])).hasSize(32);
    }

    @Test
    @DisplayName("two hashes of the same password differ: the salt makes each one unique")
    void saltedUniqueness() {
      assertThat(HASHER.hash("same password")).isNotEqualTo(HASHER.hash("same password"));
    }

    @Test
    @DisplayName("refuses a null password: null is a missing input, not a password")
    void refusesNull() {
      assertThatNullPointerException().isThrownBy(() -> HASHER.hash(null));
    }
  }

  @Nested
  @DisplayName("verify")
  class Verify {

    @Test
    @DisplayName("accepts the password that produced the hash")
    void acceptsTheRightPassword() {
      String encoded = HASHER.hash("correct horse battery staple");
      assertThat(HASHER.verify("correct horse battery staple", encoded)).isTrue();
    }

    @Test
    @DisplayName("rejects a wrong password")
    void rejectsAWrongPassword() {
      String encoded = HASHER.hash("correct horse battery staple");
      assertThat(HASHER.verify("Tr0ub4dor&3", encoded)).isFalse();
    }

    @Test
    @DisplayName("rejects a malformed hash as a failed verification, not an exception")
    void rejectsMalformed() {
      assertThat(HASHER.verify("pw", "not-a-hash")).isFalse();
      assertThat(HASHER.verify("pw", "PBKDF2WithHmacSHA256$1000$onlythree")).isFalse();
      assertThat(HASHER.verify("pw", "PBKDF2WithHmacSHA256$1000$too$many$parts$here")).isFalse();
    }

    @Test
    @DisplayName("rejects an unknown algorithm")
    void rejectsUnknownAlgorithm() {
      String encoded = HASHER.hash("pw");
      String swapped = encoded.replace("PBKDF2WithHmacSHA256", "MD5");
      assertThat(HASHER.verify("pw", swapped)).isFalse();
    }

    @Test
    @DisplayName("rejects corrupted parameters: non-numeric iterations")
    void rejectsCorruptedIterations() {
      String encoded = HASHER.hash("pw");
      String corrupted = encoded.replace("$1000$", "$not-a-number$");
      assertThat(HASHER.verify("pw", corrupted)).isFalse();
    }

    @Test
    @DisplayName("rejects a non-positive iteration count without invoking PBKDF2")
    void rejectsNonPositiveIterations() {
      String encoded = HASHER.hash("pw");
      assertThat(HASHER.verify("pw", encoded.replace("$1000$", "$0$"))).isFalse();
      assertThat(HASHER.verify("pw", encoded.replace("$1000$", "$-1$"))).isFalse();
    }

    @Test
    @DisplayName("rejects corrupted salt or derived key: undecodable or wrong-length Base64")
    void rejectsCorruptedMaterial() {
      String encoded = HASHER.hash("pw");
      String[] parts = encoded.split("\\$", -1);
      String badSalt = parts[0] + "$" + parts[1] + "$!!!not-base64!!!$" + parts[3];
      assertThat(HASHER.verify("pw", badSalt)).isFalse();
      String shortKey = parts[0] + "$" + parts[1] + "$" + parts[2] + "$" + Base64.getEncoder().encodeToString(new byte[8]);
      assertThat(HASHER.verify("pw", shortKey)).isFalse();
    }

    @Test
    @DisplayName("verifies across hashers with different iteration counts: the count is in the hash")
    void verifiesAcrossIterationCounts() {
      PasswordHasher slow = PasswordHasher.of(5_000, new SecureRandom(new byte[32]));
      String encoded = slow.hash("pw");
      assertThat(HASHER.verify("pw", encoded)).isTrue();
    }

    @Test
    @DisplayName("refuses null inputs")
    void refusesNull() {
      assertThatNullPointerException().isThrownBy(() -> HASHER.verify(null, "x"));
      assertThatNullPointerException().isThrownBy(() -> HASHER.verify("x", null));
    }
  }

  @Nested
  @DisplayName("construction and disclosure")
  class Construction {

    @Test
    @DisplayName("refuses a non-positive iteration count: a fast hash is not a password hash")
    void refusesWeakIterations() {
      assertThatIllegalArgumentException().isThrownBy(() -> PasswordHasher.of(0, new SecureRandom()));
      assertThatIllegalArgumentException().isThrownBy(() -> PasswordHasher.of(-1, new SecureRandom()));
    }

    @Test
    @DisplayName("refuses a null random source: a hasher with no randomness is a digest")
    void refusesNullRandom() {
      assertThatNullPointerException().isThrownBy(() -> PasswordHasher.of(1_000, null));
    }

    @Test
    @DisplayName("standard() uses the OWASP-recommended 600,000 iterations")
    void standardDefaults() {
      assertThat(PasswordHasher.standard().toString()).contains("600000");
    }

    @Test
    @DisplayName("toString names the algorithm and count, never a password or a hash")
    void toStringDisclosesNothingSecret() {
      String printed = HASHER.toString();
      assertThat(printed).contains("PBKDF2WithHmacSHA256").contains("1000");
    }
  }
}
