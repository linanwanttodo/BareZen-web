package com.barezen.plugins.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.data.DataAccessException;
import com.barezen.data.Database;
import com.barezen.plugins.TestDatabase;
import com.barezen.security.Authorization;
import com.barezen.security.Permission;
import com.barezen.security.Principal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The user plugin and its repository, tested against a real H2 database (ADR-0038's test strategy, reused). The
 * hashing is a cheap fake here — {@code PasswordHasher}'s own behaviour is proven in its module (ADR-0035); what is
 * proven here is the plugin's decisions and its persistence wiring.
 */
class UserPluginTest {

  /** A fake that stores the password as its own "hash": verify is equality, so tests route through the port. */
  private static final UserRepository.PasswordHashing FAKE_HASHING = new UserRepository.PasswordHashing() {
    @Override
    public String hash(String password) {
      return "fake$" + password;
    }

    @Override
    public boolean verify(String password, String encodedHash) {
      return hash(password).equals(encodedHash);
    }
  };

  private Database database;
  private UserRepository users;
  private UserPlugin plugin;

  @BeforeEach
  void setUp() {
    database = new Database(TestDatabase.fresh());
    users = new UserRepository(database, FAKE_HASHING);
    plugin = new UserPlugin(users);
    plugin.initialize();
  }

  @Nested
  @DisplayName("the schema the plugin creates on initialize")
  class Schema {

    @Test
    @DisplayName("is empty at first and idempotent — initialize twice is not an error")
    void idempotentCreate() {
      assertThat(users.find("nobody")).isEmpty();
      plugin.initialize();
      plugin.initialize();
      users.create("ada", "hunter2");
      assertThat(users.find("ada")).isPresent();
    }
  }

  @Nested
  @DisplayName("lookup and verify")
  class Lookup {

    @Test
    @DisplayName("a created user is found, and its stored value is the fake hash never the raw password")
    void createThenFind() {
      users.create("ada", "hunter2");
      UserAccount account = users.find("ada").orElseThrow();
      assertThat(account.username()).isEqualTo("ada");
      assertThat(account.passwordHash()).isEqualTo("fake$hunter2").isNotEqualTo("hunter2");
    }

    @Test
    @DisplayName("verifyPassword is true for the right pair and false for wrong password or unknown user")
    void verifyPassword() {
      users.create("ada", "hunter2");
      assertThat(users.verifyPassword("ada", "hunter2")).isTrue();
      assertThat(users.verifyPassword("ada", "wrong")).isFalse();
      assertThat(users.verifyPassword("ghost", "hunter2")).isFalse();
      assertThat(users.verifyPassword(null, "x")).isFalse();
    }

    @Test
    @DisplayName("an unknown user still invokes password verification and is rejected")
    void unknownUserStillVerifiesPassword() {
      AtomicInteger verifications = new AtomicInteger();
      UserRepository.PasswordHashing counting = new UserRepository.PasswordHashing() {
        @Override
        public String hash(String password) {
          return "fake$hash";
        }

        @Override
        public boolean verify(String password, String encodedHash) {
          verifications.incrementAndGet();
          return "hunter2".equals(password);
        }
      };
      UserRepository countingUsers = new UserRepository(database, counting);
      countingUsers.create("ada", "hunter2");

      assertThat(countingUsers.verifyPassword("ada", "hunter2")).isTrue();
      assertThat(countingUsers.verifyPassword("ghost", "hunter2")).isFalse();
      assertThat(verifications).hasValue(2);
    }

    @Test
    @DisplayName("a duplicate username is refused at the database, not silently overwritten")
    void duplicateRefused() {
      users.create("ada", "one");
      assertThatThrownBy(() -> users.create("ada", "two")).isInstanceOf(DataAccessException.class);
      assertThat(users.verifyPassword("ada", "one")).as("the first row survived the refused second").isTrue();
    }

    @Test
    @DisplayName("a blank username is refused before any SQL")
    void blankUsernameRefused() {
      assertThatThrownBy(() -> users.create("  ", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a username that is SQL injection text is bound as a value, not run as a statement")
    void sqlInjectionIsAMereValue() {
      String tricky = "ada'); DROP TABLE barezen_user; --";
      users.create(tricky, "pw");
      assertThat(users.find(tricky).map(UserAccount::username)).contains(tricky);
      // The table still exists and still holds the row: nothing in the username was executed.
      assertThat(users.verifyPassword(tricky, "pw")).isTrue();
    }
  }

  @Nested
  @DisplayName("the Authorization port the plugin fills")
  class AuthorizationPort {

    @Test
    @DisplayName("ANY is always allowed, without a query")
    void anyIsAllowed() {
      assertThat(plugin.authorize(new Principal("ghost"), Permission.ANY)).isEqualTo(Authorization.Decision.ALLOW);
    }

    @Test
    @DisplayName("a granted permission allows and an ungranted one denies")
    void grantsDriveTheDecision() {
      users.create("ada", "pw");
      users.grant("ada", "user:read");
      assertThat(plugin.authorize(new Principal("ada"), Permission.of("user:read")))
          .isEqualTo(Authorization.Decision.ALLOW);
      assertThat(plugin.authorize(new Principal("ada"), Permission.of("user:write")))
          .isEqualTo(Authorization.Decision.DENY);
    }

    @Test
    @DisplayName("an unknown principal is denied any named permission")
    void unknownDenied() {
      assertThat(plugin.authorize(new Principal("nobody"), Permission.of("user:read")))
          .isEqualTo(Authorization.Decision.DENY);
    }

    @Test
    @DisplayName("grant is idempotent: the same permission twice does not error and does not duplicate")
    void grantIdempotent() {
      users.create("ada", "pw");
      users.grant("ada", "user:read");
      users.grant("ada", "user:read");
      assertThat(users.permissionsOf("ada")).containsExactly("user:read");
    }

    @Test
    @DisplayName("the plugin answers the PasswordVerifier port with the stored-hash check")
    void implementsTheVerifierPort() {
      users.create("ada", "hunter2");
      assertThat(plugin.verify("ada", "hunter2")).isTrue();
      assertThat(plugin.verify("ada", "nope")).isFalse();
      assertThat(plugin.verify("ghost", "hunter2")).isFalse();
    }

    @Test
    @DisplayName("authorize refuses a null argument before consulting anything")
    void nullArgumentsRefused() {
      assertThatThrownBy(() -> plugin.authorize(null, Permission.ANY))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> plugin.authorize(new Principal("ada"), null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("the plugin's identity")
  class Identity {

    @Test
    @DisplayName("is named 'user', declares no dependencies, and holds no printable secret")
    void descriptorAndToString() {
      assertThat(plugin.descriptor().id()).isEqualTo("user");
      assertThat(plugin.descriptor().dependencies()).isEqualTo(List.of());
      users.create("ada", "hunter2");
      assertThat(plugin.toString()).doesNotContain("hunter2").doesNotContain("fake$");
      assertThat(users.toString()).doesNotContain("hunter2");
    }
  }
}
