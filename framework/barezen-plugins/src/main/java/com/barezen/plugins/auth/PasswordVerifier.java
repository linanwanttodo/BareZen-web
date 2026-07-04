package com.barezen.plugins.auth;

/**
 * The one thing the auth plugin needs from whoever stores passwords: "does this username match this password". It
 * is a port the auth plugin *consumes*, not one it owns — the point of naming it (ADR-0042) is that the auth plugin
 * never reaches into the user plugin's {@code Database}, it holds an interface the application wired in.
 *
 * <p>One direction, one method. The user plugin implements it (a stored-hash check); a test supplies a lambda.
 * This keeps the dependency edge {@code AuthPlugin → PasswordVerifier ← UserPlugin} pointing so that removing the
 * user plugin entirely still leaves the auth plugin compiling and testable — the SPI rule (Security ← SPI ←
 * plugin, never plugin → plugin internals) with the arrow drawn where a mutation probe can find it.
 */
@FunctionalInterface
public interface PasswordVerifier {

  /**
   * @return true only if a user named {@code username} exists and {@code password} hashes to its stored value;
   *     false for an unknown user and a wrong password alike, because telling those apart to a caller who might be
   *     an attacker is the enumeration leak ADR-0037's refusal design keeps closed.
   */
  boolean verify(String username, String password);
}
