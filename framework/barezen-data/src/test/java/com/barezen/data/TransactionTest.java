package com.barezen.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Stage 2.12-D and E: begin, commit, rollback, the ownership rule, and the nesting answer — each tested against
 * a real H2 database, because "rolled back" is a fact about the data, not about a flag in an object.
 *
 * <p>Every assertion here is observable from outside the framework: a second connection queries the table and
 * either sees the row or does not. The recorder counts JDBC calls; these tests read databases.
 */
@DisplayName("transactions")
class TransactionTest {

  @Nested
  @DisplayName("the decision table")
  class DecisionTable {

    @Test
    @DisplayName("success commits: the write is visible to every later connection")
    void successCommits() {
      DataSource database = H2.fresh();
      seed(database, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database subject = new Database(database);

      Integer written = subject.transaction(tx -> tx.executeUpdate("INSERT INTO item VALUES (1)"));

      assertThat(written).isEqualTo(1);
      assertThat(count(subject, "item")).as("a committed write outlives its transaction").isEqualTo(1);
    }

    @Test
    @DisplayName("a checked failure rolls back: the callback's own SQLException undoes its earlier write")
    void checkedFailureRollsBack() {
      DataSource database = H2.fresh();
      seed(database, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database subject = new Database(database);

      assertThatThrownBy(() -> subject.transaction(tx -> {
        tx.executeUpdate("INSERT INTO item VALUES (1)");
        throw new SQLException("the unit of work failed through raw JDBC");
      })).isInstanceOf(DataAccessException.class);

      assertThat(count(subject, "item")).as("failure means nothing of it stays").isZero();
    }

    @Test
    @DisplayName("a runtime exception rolls back and arrives untranslated — it is the application's failure")
    void runtimeFailureRollsBackAndIsNotTranslated() {
      DataSource database = H2.fresh();
      seed(database, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database subject = new Database(database);

      assertThatThrownBy(() -> subject.transaction(tx -> {
        tx.executeUpdate("INSERT INTO item VALUES (1)");
        throw new IllegalStateException("the application's own decision to abort");
      })).isInstanceOf(IllegalStateException.class).hasMessage("the application's own decision to abort");

      assertThat(count(subject, "item")).isZero();
    }

    @Test
    @DisplayName("a DataAccessException from a repository call inside the callback rolls back too, and stays itself")
    void repositoryFailureRollsBackTheWholeUnit() {
      DataSource database = H2.fresh();
      seed(database, "CREATE TABLE item (id INT PRIMARY KEY)");
      seed(database, "INSERT INTO item VALUES (1)");
      Database subject = new Database(database);

      assertThatThrownBy(() -> subject.transaction(tx -> {
        tx.executeUpdate("INSERT INTO item VALUES (2)");
        tx.executeUpdate("INSERT INTO item VALUES (1)");
        return null;
      })).isInstanceOf(DataAccessException.class).hasMessageContaining("INSERT INTO item VALUES (1)");

      // Row 1 existed before the run; the question the test asks is row 2, which must not exist.
      assertThat(subject.query("SELECT id FROM item ORDER BY id", rs -> rs.getInt(1))).containsExactly(1);
    }

    @Test
    @DisplayName("a failing commit rolls the unit back first, and the caller reads the commit failure")
    void commitFailureRollsBackAndReports() {
      RecordingDataSource recorded = H2.recording();
      seed(recorded, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database subject = new Database(recorded);
      recorded.failNextCommit();

      assertThatThrownBy(() -> subject.transaction(tx -> {
        tx.executeUpdate("INSERT INTO item VALUES (1)");
        return null;
      })).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("transaction commit failed");

      List<String> sequence = recorded.decisionSequence();
      assertThat(sequence).as("the sequence a reviewer would expect at this boundary")
          .containsExactly("setAutoCommit:false", "commit", "rollback", "setAutoCommit:true");
      assertThat(recorded.leaks()).as("a failed commit still does not leak a connection").isEmpty();
    }

    @Test
    @DisplayName("a failing rollback still releases the connection — a poisoned connection must not be pooled open")
    void rollbackFailureStillReleases() {
      RecordingDataSource recorded = H2.recording();
      seed(recorded, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database subject = new Database(recorded);

      assertThatThrownBy(() -> subject.transaction(tx -> {
        recorded.failNextRollback();
        throw new IllegalStateException("fail while the boundary tries to roll back");
      })).isInstanceOf(IllegalStateException.class)
          .satisfies(failure -> assertThat(failure.getSuppressed())
              .as("the rollback failure travels attached, not on top of the application's failure")
              .anyMatch(suppressed -> suppressed instanceof DataAccessException));

      assertThat(recorded.leaks()).isEmpty();
    }
  }

  @Nested
  @DisplayName("visibility while uncommitted")
  class Isolation {

    @Test
    @DisplayName("an uncommitted insert is invisible to every other connection, and a committed one is not")
    void uncommittedWorkIsPrivateToItsTransaction() {
      DataSource database = H2.fresh();
      seed(database, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database subject = new Database(database);
      Database reader = new Database(database);

      String seenBeforeCommit = subject.transaction(tx -> {
        tx.executeUpdate("INSERT INTO item VALUES (1)");
        // Same-database reads through the transaction see their own uncommitted work...
        assertThat(tx.query("SELECT id FROM item", rs -> rs.getInt(1))).containsExactly(1);
        // ...and the rest of the world does not see it yet.
        return String.valueOf(count(reader, "item"));
      });

      assertThat(seenBeforeCommit).as("another connection while the unit of work is open").isEqualTo("0");
      assertThat(count(reader, "item")).as("the same connection after the commit").isEqualTo(1);
    }

    @Test
    @DisplayName("a rolled-back insert leaves no trace for any connection, ever")
    void rolledBackWorkLeavesNoTrace() {
      DataSource database = H2.fresh();
      seed(database, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database subject = new Database(database);

      assertThatThrownBy(() -> subject.transaction(tx -> {
        tx.executeUpdate("INSERT INTO item VALUES (1)");
        throw new IllegalStateException("abort after the write");
      })).isInstanceOf(IllegalStateException.class);

      assertThat(count(subject, "item")).isZero();
    }
  }

  @Nested
  @DisplayName("ownership: the boundary decides, not the callee")
  class Ownership {

    @Test
    @DisplayName("commit and rollback are not public: a repository cannot decide the fate of a transaction")
    void theDecidersAreNotVisibleOutsideTheModule() throws Exception {
      // The mechanical half of ADR-0039: if either method became public — or gained any public path to commit —
      // this reflection probe fails before any repository can exist that commits its own unit of work.
      assertThat(Transaction.class.getMethod("query", String.class, RowMapper.class, Object[].class))
          .isNotNull();
      assertThatThrownBy(() -> Transaction.class.getMethod("commit"))
          .as("no public commit: the API cannot express a repository that commits")
          .isInstanceOf(NoSuchMethodException.class);
      assertThatThrownBy(() -> Transaction.class.getMethod("rollback"))
          .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    @DisplayName("a stored transaction is dead after the callback returned — its connection left with it")
    void aTransactionCannotOutliveItsCallback() {
      DataSource database = H2.fresh();
      seed(database, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database subject = new Database(database);
      List<Transaction> kept = new java.util.ArrayList<>();

      subject.transaction(tx -> {
        kept.add(tx);
        return null;
      });
      Transaction survivor = kept.get(0);

      assertThatThrownBy(() -> survivor.executeUpdate("INSERT INTO item VALUES (1)"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("already committed or rolled back");
      assertThatThrownBy(() -> survivor.query("SELECT id FROM item", rs -> rs.getInt(1)))
          .isInstanceOf(DataAccessException.class);
      assertThat(count(subject, "item")).as("the late write did not happen").isZero();
    }

    @Test
    @DisplayName("one query path runs every repository call on the same connection the boundary opened")
    void aUnitOfWorkTouchesExactlyOneConnection() {
      RecordingDataSource recorded = H2.recording();
      seed(recorded, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database subject = new Database(recorded);
      int before = recorded.connectionsAcquired();

      subject.transaction(tx -> {
        tx.executeUpdate("INSERT INTO item VALUES (1)");
        tx.query("SELECT id FROM item", rs -> rs.getInt(1));
        tx.executeUpdate("INSERT INTO item VALUES (2)");
        return null;
      });

      assertThat(recorded.connectionsAcquired() - before)
          .as("three statements, one connection: a unit of work never re-borrows mid-flight")
          .isEqualTo(1);
    }

    @Test
    @DisplayName("a service's second transaction while one is running opens a second connection: documented, independent")
    void nestingOpensASecondIndependentTransaction() {
      DataSource database = H2.fresh();
      seed(database, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database subject = new Database(database);

      // The shape "nested transaction" would take, run as the ADR-0039 answer says it behaves: the inner call is
      // a peer, not a child. It cannot see the outer's uncommitted row, its commit is its own, and rolling the
      // outer back afterwards undoes the outer only.
      assertThatThrownBy(() -> subject.transaction(outer -> {
        outer.executeUpdate("INSERT INTO item VALUES (1)");
        Integer innerVisible = subject.transaction(inner ->
            inner.query("SELECT id FROM item ORDER BY id", rs -> rs.getInt(1)).size());
        assertThat(innerVisible).as("the inner transaction sees no uncommitted work of the outer one").isZero();
        outer.executeUpdate("INSERT INTO item VALUES (2)");
        throw new IllegalStateException("outer aborts");
      })).isInstanceOf(IllegalStateException.class);

      // The inner transaction committed its own empty unit (it read only), and the outer's two rows are gone:
      // nesting would have made the inner write part of the outer's fate, and this says it is not.
      assertThat(count(subject, "item")).isZero();
    }
  }

  @Nested
  @DisplayName("concurrent units of work")
  class Concurrent {

    @Test
    @DisplayName("eight threads running transactions against one Database leave exactly their eight rows")
    void transactionsOnOneDatabaseAreIndependentUnitsOfWork() throws Exception {
      DataSource database = H2.fresh();
      seed(database, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database subject = new Database(database);
      int workers = 8;
      CountDownLatch start = new CountDownLatch(1);
      var pool = Executors.newFixedThreadPool(workers);
      try {
        List<java.util.concurrent.Future<Void>> futures = new java.util.ArrayList<>();
        for (int id = 1; id <= workers; id++) {
          int row = id;
          futures.add(pool.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            subject.transaction(tx -> tx.executeUpdate("INSERT INTO item VALUES (" + row + ")"));
            return null;
          }));
        }
        // Release the workers only after every task is submitted: the latch makes the eight transactions run
        // together, which is the point of the test. invokeAll would await completion before countDown could run.
        start.countDown();
        for (var future : futures) {
          future.get(30, TimeUnit.SECONDS);
        }
      } finally {
        pool.shutdownNow();
      }

      assertThat(count(subject, "item")).as("every committed transaction stayed").isEqualTo(workers);
      assertThat(subject.query("SELECT id FROM item ORDER BY id", rs -> rs.getInt(1)))
          .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }
  }

  private static int count(Database database, String table) {
    return database.query("SELECT COUNT(*) FROM " + table, rs -> rs.getInt(1)).get(0);
  }

  private static void seed(DataSource dataSource, String sql) {
    try (java.sql.Connection connection = dataSource.getConnection();
        java.sql.Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new AssertionError("test fixture could not run: " + sql, e);
    }
  }
}
