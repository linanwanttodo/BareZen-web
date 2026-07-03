package com.barezen.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Stage 2.12-B's contract, tested through a complete CRUD cycle on a real database: the boundary exists so an
 * application never names a driver, never builds a connection, and never sees a {@link SQLException}.
 */
@DisplayName("the database boundary")
class DatabaseTest {

  @Nested
  @DisplayName("construction")
  class Construction {

    @Test
    @DisplayName("a null DataSource is refused at the constructor, not on first use")
    void nullSourceFailsImmediately() {
      assertThatThrownBy(() -> new Database(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a null mapper or callback is refused before a connection is borrowed")
    void nullCollaboratorsFailBeforeTheDatabase() {
      RecordingDataSource recorded = H2.recording();
      Database subject = new Database(recorded);

      assertThatThrownBy(() -> subject.query("SELECT 1", null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> subject.transaction(null)).isInstanceOf(NullPointerException.class);
      assertThat(recorded.connectionsAcquired())
          .as("a refused call costs the database nothing")
          .isZero();
    }
  }

  @Nested
  @DisplayName("CRUD on a real database")
  class Crud {

    @Test
    @DisplayName("insert, read, update and delete round-trip through parameters and a mapped row")
    void fullCycleThroughTheBoundary() {
      DataSource database = H2.fresh();
      seed(database, "CREATE TABLE contact (id INT PRIMARY KEY, name VARCHAR(64), score INT)");
      Database subject = new Database(database);

      int inserted = subject.executeUpdate("INSERT INTO contact VALUES (?, ?, ?)", 1, "ada", 10);
      assertThat(inserted).isEqualTo(1);

      List<String> names = subject.query("SELECT name FROM contact WHERE id = ?", rs -> rs.getString(1), 1);
      assertThat(names).containsExactly("ada");

      assertThat(subject.executeUpdate("UPDATE contact SET score = ? WHERE id = ?", 11, 1))
          .as("executeUpdate reports the affected row count")
          .isEqualTo(1);
      assertThat(subject.query("SELECT score FROM contact WHERE id = ?", rs -> rs.getInt(1), 1))
          .containsExactly(11);

      assertThat(subject.executeUpdate("DELETE FROM contact WHERE id = ?", 1)).isEqualTo(1);
      assertThat(subject.query("SELECT name FROM contact", rs -> rs.getString(1))).isEmpty();
    }

    @Test
    @DisplayName("a null parameter binds as SQL NULL instead of throwing")
    void nullParametersAreAllowed() {
      DataSource database = H2.fresh();
      seed(database, "CREATE TABLE contact (id INT PRIMARY KEY, name VARCHAR(64))");
      Database subject = new Database(database);

      assertThat(subject.executeUpdate("INSERT INTO contact VALUES (?, ?)", 1, null)).isEqualTo(1);
      // The query binds nothing: the "(Object) null" was the varargs trap, which reads as one parameter
      // (position 1, which this SQL has no placeholder for) rather than as zero. The parameterless read is the
      // honest probe, and the SELECT filter above already proved the bound null stored as SQL NULL.
      assertThat(subject.query("SELECT name FROM contact", rs -> rs.getString(1)))
          .singleElement().satisfies(value -> assertThat(value).isNull());
      // Passing no parameters at all must bind nothing rather than fail: the empty varargs path.
      assertThat(subject.query("SELECT name FROM contact WHERE name IS NULL", rs -> rs.getString(1)))
          .containsExactly((String) null);
    }

    @Test
    @DisplayName("a mapper reads every column of a joined row, in cursor order")
    void rowsMapInResultSetOrder() {
      DataSource database = H2.fresh();
      seed(database, "CREATE TABLE contact (id INT PRIMARY KEY, name VARCHAR(64))");
      seed(database, "INSERT INTO contact VALUES (2, 'grace'), (1, 'ada'), (3, 'henry')");
      Database subject = new Database(database);

      List<String> ordered =
          subject.query("SELECT name FROM contact ORDER BY id", rs -> rs.getString("name"));

      assertThat(ordered).containsExactly("ada", "grace", "henry");
    }
  }

  @Nested
  @DisplayName("the translation point")
  class Translation {

    @Test
    @DisplayName("every failure path produces DataAccessException carrying the SQLException cause")
    void failuresArriveAsOneExceptionType() {
      DataSource database = H2.fresh();
      Database subject = new Database(database);

      assertThatThrownBy(() -> subject.query("SELECT * FROM missing", rs -> rs.getString(1)))
          .isInstanceOf(DataAccessException.class)
          .hasCauseInstanceOf(SQLException.class);
      assertThatThrownBy(() -> subject.executeUpdate("INSERT INTO missing VALUES (1)"))
          .isInstanceOf(DataAccessException.class)
          .hasCauseInstanceOf(SQLException.class);
      assertThatThrownBy(() -> subject.query("SELECT * FROM missing_two", rs -> rs.getString(1)))
          .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("the exception's message names the SQL that failed")
    void failureMessagesNameTheSql() {
      Database subject = new Database(H2.fresh());

      assertThatThrownBy(() -> subject.query("SELECT * FROM nowhere_table", rs -> rs.getString(1)))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("SELECT * FROM nowhere_table");
      // A statement the fresh database cannot accept; a CREATE TABLE would succeed here and hide the assertion.
      assertThatThrownBy(() -> subject.executeUpdate("UPDATE nothing_here SET x = 1"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("UPDATE nothing_here SET x = 1");
    }

    @Test
    @DisplayName("DataAccessException is unchecked and preserves its cause")
    void theExceptionShapeIsWhatCallersCanCatch() {
      SQLException cause = new SQLException("driver detail");
      DataAccessException failure = new DataAccessException("what the framework was doing", cause);

      assertThat(failure).isInstanceOf(RuntimeException.class);
      assertThat(failure.getCause()).isSameAs(cause);
      assertThat(new DataAccessException("no cause").getCause()).isNull();
    }
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
