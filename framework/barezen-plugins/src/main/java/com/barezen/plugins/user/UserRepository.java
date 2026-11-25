package com.barezen.plugins.user;

import com.barezen.data.Database;
import com.barezen.data.Transaction;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * User identity read from and written to the database, behind the driver-free boundary ADR-0038 drew. This is the
 * persistence integration the user plugin owns: lookup by name, create, grant lookup.
 *
 * <p>It is a plain collaborator, not a {@code Plugin}. The plugin (see {@link UserPlugin}) is the participant with a
 * lifecycle; this type is the thing the application hands its constructor, which is how ADR-0016 says a plugin's
 * dependencies arrive — the repository is reachable by the reader of this class's {@code new UserPlugin(users)},
 * not by anything inside the plugin reaching out for it.
 *
 * <p>Why the SQL lives here and nowhere closer to security: {@code com.barezen.security} has no idea a user is a
 * row, and must not (ADR-0042's direction). This class is where a {@code Principal}'s name becomes a
 * {@code SELECT} and back, and the one place that speaks {@link Database}/{@link Transaction} in the user plugin.
 * Every statement is parameterised through the data module's bind path (never string concatenation of a username),
 * so a login name that is also {@code ' OR 1=1 --} is a value bound to a placeholder, not a rewrite of the query.
 */
public final class UserRepository {

  private final Database database;
  private final PasswordHashing hashing;
  // Unknown accounts must still pay the real hash cost instead of revealing existence through a short-circuit.
  private final String unknownUserHash;

  /**
   * The hash/verify operation this repository needs, behind a port so no test has to run 600,000 PBKDF2 iterations
   * to place a user.
   *
   * <p>Not a security port — a seam for this module's own constructor, named here because its one job is to keep
   * the hashing decision (ADR-0035) on the security type that owns it while letting a test supply a cheap
   * implementation. {@code PasswordHasher} satisfies it as-is; {@code UserPlugin} passes the real one through.
   */
  public interface PasswordHashing {
    String hash(String password);

    boolean verify(String password, String encodedHash);
  }

  public UserRepository(Database database, PasswordHashing hashing) {
    this.database = Objects.requireNonNull(database, "database");
    this.hashing = Objects.requireNonNull(hashing, "hashing");
    this.unknownUserHash = hashing.hash("barezen-unknown-user");
  }

  /**
   * Stores a new user with the given password, hashed once on the way in.
   *
   * @throws IllegalArgumentException if the username is blank
   * @throws com.barezen.data.DataAccessException if the username already exists (the primary key is the guard) or
   *     the database refuses the write
   */
  public void create(String username, String password) {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    Objects.requireNonNull(password, "password");
    String hash = hashing.hash(password);
    database.executeUpdate("INSERT INTO barezen_user (username, password_hash) VALUES (?, ?)", username, hash);
  }

  /** Reads a stored account, or empty when no such user exists — an absent user is a fact, not a failure. */
  public Optional<UserAccount> find(String username) {
    if (username == null) {
      return Optional.empty();
    }
    List<UserAccount> rows = database.query(
        "SELECT username, password_hash FROM barezen_user WHERE username = ?",
        rs -> new UserAccount(rs.getString("username"), rs.getString("password_hash")),
        username);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** Verifies a password against the stored hash; false for an unknown user or a wrong password alike. */
  public boolean verifyPassword(String username, String password) {
    Optional<UserAccount> account = find(username);
    if (password == null) {
      return false;
    }
    String encodedHash = account.map(UserAccount::passwordHash).orElse(unknownUserHash);
    boolean matches = hashing.verify(password, encodedHash);
    return account.isPresent() && matches;
  }

  /** Grants a permission to a user; idempotent, because the primary key covers the pair. */
  public void grant(String username, String permission) {
    database.executeUpdate(
        "MERGE INTO barezen_user_permission (username, permission) KEY (username, permission) VALUES (?, ?)",
        username, permission);
  }

  /** The permissions a user holds, in a stable order; empty for an unknown user. */
  public Set<String> permissionsOf(String username) {
    if (username == null) {
      return Set.of();
    }
    List<String> names = database.query(
        "SELECT permission FROM barezen_user_permission WHERE username = ? ORDER BY permission",
        rs -> rs.getString("permission"), username);
    return Set.copyOf(names);
  }

  /**
   * The database this repository runs on, for the plugin's own one-time schema setup.
   *
   * <p>Package-private: exposing a {@link Database} to a caller outside this package would hand out the unit-of-work
   * factory the repository exists to wrap, which is the reach ADR-0039's ownership rule refuses. Only {@link
   * UserPlugin}, in this package, uses it, to run {@link #createSchema}.
   */
  Database database() {
    return database;
  }

  /**
   * The user plugin's schema: two tables, keyed by username, that the plugin creates on start (idempotent DDL per
   * ADR-0040's re-run contract) inside one unit of work.
   */
  void createSchema(Transaction transaction) {
    transaction.executeUpdate(
        "CREATE TABLE IF NOT EXISTS barezen_user (username VARCHAR(255) PRIMARY KEY, password_hash VARCHAR(512) NOT NULL)");
    transaction.executeUpdate(
        "CREATE TABLE IF NOT EXISTS barezen_user_permission (username VARCHAR(255) NOT NULL,"
            + " permission VARCHAR(255) NOT NULL, PRIMARY KEY (username, permission))");
  }

  @Override
  public String toString() {
    return "UserRepository[]"; // no connection, no database handle — nothing here is safe to print (ADR-0037's rule)
  }
}
