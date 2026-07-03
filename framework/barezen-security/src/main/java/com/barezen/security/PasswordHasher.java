package com.barezen.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * A password hasher that uses PBKDF2 with HMAC-SHA-256, because a password is not a document.
 *
 * <p>SHA-256 alone is the wrong answer for a password: it is fast, which is exactly what a password hash must not be.
 * PBKDF2 is the standard answer for making a password hash slow, and it is available in every JDK without a
 * dependency. The iteration count is deliberately high — high enough that a brute-force attack on a stolen database
 * is impractical, low enough that a legitimate login is not a denial of service. The count is in the encoded form,
 * so it can be changed without invalidating old hashes.
 *
 * <p>The encoded format is {@code algorithm$iterations$salt$derivedKey}, all Base64, so the hash is
 * self-describing and can be verified without knowing how it was produced. A malformed hash is rejected as a
 * verification failure, not as an exception — a hash that cannot be parsed is not a password, and a wrong password
 * is not a server error.
 *
 * <p>The salt is 16 bytes from {@link SecureRandom}, which is enough to make two identical passwords produce two
 * different hashes. The derived key length is 32 bytes, which is the output size of HMAC-SHA-256 and therefore the
 * natural block size for this construction.
 *
 * <p>The raw password is never stored, never logged and never returned from {@link #hash}. The {@code toString} of
 * this class prints only the algorithm name, because a hash that appears in a log line is a leak that cannot be
 * recalled.
 */
public final class PasswordHasher {

  /** The algorithm name used in the encoded format. */
  static final String ALGORITHM = "PBKDF2WithHmacSHA256";

  /** Default iteration count: 600,000, which is OWASP's 2023 recommendation for PBKDF2-HMAC-SHA256. */
  static final int DEFAULT_ITERATIONS = 600_000;

  /** Salt length in bytes: 16 bytes = 128 bits, enough to make rainbow tables impractical. */
  static final int SALT_LENGTH = 16;

  /** Derived key length in bytes: 32 bytes = 256 bits, the HMAC-SHA-256 output size. */
  static final int KEY_LENGTH = 32;

  private final int iterations;
  private final SecureRandom random;

  private PasswordHasher(int iterations, SecureRandom random) {
    this.iterations = iterations;
    this.random = random;
  }

  /**
   * A hasher with the default iteration count and a fresh secure random source.
   *
   * @return the default hasher
   */
  public static PasswordHasher standard() {
    return new PasswordHasher(DEFAULT_ITERATIONS, new SecureRandom());
  }

  /**
   * A hasher with a specific iteration count and random source, for tests that need determinism or speed.
   *
   * @param iterations the iteration count, must be positive
   * @param random the random source, never null
   * @return a hasher with the given parameters
   */
  public static PasswordHasher of(int iterations, SecureRandom random) {
    if (iterations <= 0) {
      throw new IllegalArgumentException(
          "iterations must be positive; a zero or negative count makes the hash deterministic and fast");
    }
    Objects.requireNonNull(random, "the random source; a password hasher with no randomness is a digest");
    return new PasswordHasher(iterations, random);
  }

  /**
   * Hash a password.
   *
   * @param password the password to hash, never null; empty passwords are hashed like any other — a policy that
   *     refuses them belongs to the caller, not to the hasher
   * @return the encoded hash, never null; the raw password is not retained, returned or logged
   */
  public String hash(String password) {
    Objects.requireNonNull(password, "the password to hash; null is not a password, it is a missing input");
    byte[] salt = new byte[SALT_LENGTH];
    random.nextBytes(salt);
    byte[] derived = derive(password, salt, iterations);
    return ALGORITHM + "$" + iterations + "$"
        + Base64.getEncoder().encodeToString(salt) + "$"
        + Base64.getEncoder().encodeToString(derived);
  }

  /**
   * Verify a password against an encoded hash.
   *
   * @param password the password to verify, never null
   * @param encodedHash the encoded hash to verify against, never null
   * @return true if the password matches the hash, false if it does not or if the hash is malformed, has an
   *     unsupported algorithm, corrupted parameters, corrupted salt or corrupted derived key
   */
  public boolean verify(String password, String encodedHash) {
    Objects.requireNonNull(password, "the password to verify; null is not a password, it is a missing input");
    Objects.requireNonNull(encodedHash, "the hash to verify against; null is not a hash, it is a missing input");
    String[] parts = encodedHash.split("\\$", -1);
    if (parts.length != 4) {
      return false;
    }
    if (!ALGORITHM.equals(parts[0])) {
      return false;
    }
    int iterations;
    try {
      iterations = Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      return false;
    }
    byte[] salt;
    byte[] expected;
    try {
      salt = Base64.getDecoder().decode(parts[2]);
      expected = Base64.getDecoder().decode(parts[3]);
    } catch (IllegalArgumentException e) {
      return false;
    }
    if (salt.length != SALT_LENGTH || expected.length != KEY_LENGTH) {
      return false;
    }
    byte[] actual = derive(password, salt, iterations);
    return MessageDigest.isEqual(actual, expected);
  }

  private static byte[] derive(String password, byte[] salt, int iterations) {
    try {
      javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance(ALGORITHM);
      javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
          password.toCharArray(), salt, iterations, KEY_LENGTH * 8);
      return factory.generateSecret(spec).getEncoded();
    } catch (NoSuchAlgorithmException | java.security.spec.InvalidKeySpecException e) {
      throw new IllegalStateException("PBKDF2WithHmacSHA256 is required by every JDK; this JVM cannot hash passwords", e);
    }
  }

  @Override
  public String toString() {
    return "PasswordHasher[" + ALGORITHM + ", iterations=" + iterations + "]";
  }
}
