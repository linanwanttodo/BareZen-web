package com.barezen.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Stage 2.12-C, tested as the directive words it: connection acquired, statement closed, result set closed,
 * connection returned/closed, exception cleanup — and no connection leaked.
 *
 * <p>Every test here runs against a real H2 database through {@link RecordingDataSource}, because the behaviour
 * under test is what JDBC calls the framework <em>actually makes</em>, not what its source appears to make. A
 * hand-written fake would pass for a framework that never called close, as long as its author forgot to check;
 * the recorder fails when close is deleted from the production code (mutation probes M4, M5, M6).
 */
@DisplayName("connection lifecycle")
class DatabaseLifecycleTest {

  @Nested
  @DisplayName("query borrows a connection for one call and leaves nothing open")
  class QueryBorrowsAndReturns {

    @Test
    @DisplayName("a successful query closes statement, cursor and connection, in that order")
    void queryClosesEveryResourceItOpens() {
      RecordingDataSource recorded = H2.recording();
      seed(recorded, "CREATE TABLE item (id INT PRIMARY KEY, label VARCHAR(64))");
      seed(recorded, "INSERT INTO item VALUES (1, 'a'), (2, 'b')");
      Database database = new Database(recorded);

      assertThat(database.query("SELECT label FROM item ORDER BY id", rs -> rs.getString(1)))
          .containsExactly("a", "b");

      assertThat(recorded.openConnections()).as("connection returned/closed").isEmpty();
      assertThat(recorded.openStatements()).as("statement closed").isEmpty();
      assertThat(recorded.openCursors()).as("result set closed").isEmpty();
    }

    @Test
    @DisplayName("bad SQL still closes the connection before the translated failure reaches the caller")
    void queryFailureReleasesTheConnectionToo() {
      RecordingDataSource recorded = H2.recording();
      Database database = new Database(recorded);

      assertThatThrownBy(() -> database.query("SELECT * FROM no_such_table", rs -> rs.getString(1)))
          .isInstanceOf(DataAccessException.class)
          .hasCauseInstanceOf(SQLException.class);

      assertThat(recorded.leaks()).as("exception cleanup: the borrowed connection is not left open").isEmpty();
    }

    @Test
    @DisplayName("a mapper that fails through JDBC lands in the one translation point")
    void mapperFailuresAreTranslatedWithTheQueryContext() {
      RecordingDataSource recorded = H2.recording();
      seed(recorded, "CREATE TABLE item (id INT PRIMARY KEY)");
      seed(recorded, "INSERT INTO item VALUES (7)");
      Database database = new Database(recorded);

      assertThatThrownBy(() -> database.query("SELECT id FROM item", rs -> {
        throw new SQLException("the mapper read a column that is not there");
      }))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("SELECT id FROM item")
          .hasCauseInstanceOf(SQLException.class);

      assertThat(recorded.leaks()).isEmpty();
    }

    @Test
    @DisplayName("a bind failure closes the statement it already opened before anything runs on it")
    void bindFailureDoesNotLeaveAPreparedStatementOpen() {
      RecordingDataSource recorded = H2.recording();
      seed(recorded, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database database = new Database(recorded);
      recorded.failNextBind();

      assertThatThrownBy(() -> database.query("SELECT id FROM item WHERE id = ?", rs -> rs.getInt(1), 1))
          .isInstanceOf(DataAccessException.class);

      assertThat(recorded.openStatements())
          .as("JdbcCollect.prepared closes the statement it prepared when binding fails")
          .isEmpty();
      assertThat(recorded.openConnections()).isEmpty();
    }
  }

  @Nested
  @DisplayName("executeUpdate borrows a connection for one call")
  class UpdateBorrowsAndReturns {

    @Test
    @DisplayName("an update returns the affected row count and closes the connection")
    void updateClosesItsConnection() {
      RecordingDataSource recorded = H2.recording();
      seed(recorded, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database database = new Database(recorded);

      assertThat(database.executeUpdate("INSERT INTO item VALUES (1)")).isEqualTo(1);
      assertThat(recorded.leaks()).isEmpty();
      assertThat(recorded.updateExecutions()).as("one statement, executed once").isEqualTo(1);
    }

    @Test
    @DisplayName("a failing update is translated and its connection closed")
    void updateFailureClosesItsConnection() {
      RecordingDataSource recorded = H2.recording();
      seed(recorded, "CREATE TABLE item (id INT PRIMARY KEY)");
      seed(recorded, "INSERT INTO item VALUES (1)");
      Database database = new Database(recorded);

      assertThatThrownBy(() -> database.executeUpdate("INSERT INTO item VALUES (1)"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("INSERT INTO item VALUES (1)")
          .hasCauseInstanceOf(SQLException.class);

      assertThat(recorded.leaks()).isEmpty();
    }
  }

  @Nested
  @DisplayName("a transaction never leaves its connection open, on any outcome")
  class TransactionConnectionLifecycle {

    @Test
    @DisplayName("a committed transaction closes exactly one connection and commits exactly once")
    void committedTransactionClosesItsConnection() {
      RecordingDataSource recorded = H2.recording();
      seed(recorded, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database database = new Database(recorded);

      database.transaction(tx -> tx.executeUpdate("INSERT INTO item VALUES (1)"));

      assertThat(recorded.leaks()).isEmpty();
      assertThat(recorded.commits()).as("the boundary decides once").isEqualTo(1);
      assertThat(recorded.rollbacks()).isZero();
      // Two auto-commit calls per transaction: off at open, back on at release. A missing second call is the
      // dirty pooled connection probe (mutation M2 variant would show here too).
      assertThat(recorded.autoCommitChanges()).as("transaction scope entered and left").isEqualTo(2);
      assertThat(recorded.decisionSequence())
          .containsExactly("setAutoCommit:false", "commit", "setAutoCommit:true");
    }

    @Test
    @DisplayName("a callback that throws leaves the connection closed and the failure translated or not, by kind")
    void failingTransactionStillCloses() {
      RecordingDataSource recorded = H2.recording();
      seed(recorded, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database database = new Database(recorded);

      assertThatThrownBy(() -> database.transaction(tx -> {
        tx.executeUpdate("INSERT INTO item VALUES (1)");
        throw new IllegalStateException("the application's own failure");
      })).isInstanceOf(IllegalStateException.class);

      assertThat(recorded.leaks()).isEmpty();
      assertThat(recorded.commits()).isZero();
      assertThat(recorded.rollbacks()).as("a broken unit of work may not stay durable").isEqualTo(1);
    }

    @Test
    @DisplayName("a callback failing through raw JDBC is translated, and its rollback still ran")
    void rawJdbcFailureInCallbackIsTranslated() {
      RecordingDataSource recorded = H2.recording();
      seed(recorded, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database database = new Database(recorded);

      assertThatThrownBy(() -> database.transaction(tx -> {
        throw new SQLException("the callback used a Connection directly");
      }))
          .isInstanceOf(DataAccessException.class)
          .hasCauseInstanceOf(SQLException.class);

      assertThat(recorded.leaks()).isEmpty();
      assertThat(recorded.rollbacks()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("acquiring a connection can itself fail, and that failure is the data layer's")
  class AcquisitionFailure {

    @Test
    @DisplayName("a source that cannot produce a connection throws DataAccessException, not SQLException")
    void acquireFailureIsTranslated() {
      DataSource broken = new DataSource() {
        @Override
        public java.sql.Connection getConnection() throws SQLException {
          throw new SQLException("the pool is empty");
        }

        @Override
        public java.sql.Connection getConnection(String user, String password) throws SQLException {
          return getConnection();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
          throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
          return false;
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
          return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
          return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
          throw new UnsupportedOperationException();
        }
      };
      Database database = new Database(broken);

      assertThatThrownBy(() -> database.query("SELECT 1", rs -> rs.getInt(1)))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("could not acquire a connection")
          .hasCauseInstanceOf(SQLException.class);
      assertThatThrownBy(() -> database.executeUpdate("CREATE TABLE item (id INT)"))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(() -> database.transaction(tx -> tx.executeUpdate("INSERT INTO item VALUES (1)")))
          .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("a transaction whose connection cannot enter scope closes that connection before throwing")
    void autoCommitFailureDoesNotLeakTheConnection() {
      RecordingDataSource recorded = H2.recording();
      seed(recorded, "CREATE TABLE item (id INT PRIMARY KEY)");
      Database database = new Database(recorded);
      recorded.failNextSetAutoCommit();

      assertThatThrownBy(() -> database.transaction(tx -> tx.executeUpdate("INSERT INTO item VALUES (1)")))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("auto-commit could not be disabled");

      assertThat(recorded.leaks())
          .as("a connection that could not enter transaction scope is not handed to anybody and is not left open")
          .isEmpty();
      assertThat(recorded.commits()).isZero();
    }
  }

  /** DDL through a fresh connection, outside the object under test, so a seed failure never looks like the test. */
  private static void seed(DataSource dataSource, String sql) {
    try (java.sql.Connection connection = dataSource.getConnection();
        java.sql.Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new AssertionError("test fixture could not run: " + sql, e);
    }
  }
}
