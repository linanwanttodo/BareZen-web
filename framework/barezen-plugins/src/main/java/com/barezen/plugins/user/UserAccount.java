package com.barezen.plugins.user;

import java.util.Objects;

/**
 * One user's identity as the framework's security layer needs it: a name and the stored password hash behind that
 * name. The hash, never a password, is what crosses this boundary in either direction (ADR-0035).
 *
 * @param username the login name, which is also the {@code com.barezen.security.Principal}'s name
 * @param passwordHash the self-describing PBKDF2 encoding {@code PasswordHasher} produced, never a raw password
 */
public record UserAccount(String username, String passwordHash) {

  public UserAccount {
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(passwordHash, "passwordHash; an account with no stored hash cannot be verified");
    if (username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank; it is the identity a session is issued to");
    }
  }

  @Override
  public String toString() {
    // The username is identity, the hash is secret-shaped: neither the hash nor any password appears here
    // (ADR-0035's disclosure rule, applied to the persistence row as strictly as to the credential).
    return "UserAccount[" + username + "]";
  }
}
