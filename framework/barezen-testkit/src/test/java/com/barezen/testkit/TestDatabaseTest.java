package com.barezen.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit and integration tests for {@link TestDatabase} against an in-memory H2 datasource the test itself builds.
 *
 * <p>The whole point of the fixture is that it takes an application-owned datasource and never decides the database,
 * so these tests hand it a real one and assert the two things it promises: it wraps a working {@link
 * com.barezen.data.Database}, and {@link TestDatabase#reset()} genuinely empties it. H2 is a test-scope dependency here
 * and nowhere under {@code src/main}, which the module's architecture test enforces.
 */
@DisplayName("TestDatabase")
class TestDatabaseTest {

  private static final AtomicInteger NAME = new AtomicInteger();

  /** A fresh, isolated in-memory database, one per call so no test inherits another's rows. */
  private static DataSource fresh() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:barezen_testkit_" + NAME.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    return dataSource;
  }

  @Nested
  @DisplayName("construction")
  class Construction {

    @Test
    @DisplayName("a null datasource is refused with a reason")
    void nullDataSource() {
      assertThatThrownBy(() -> TestDatabase.of(null, "DROP ALL OBJECTS"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a null reset statement is refused")
    void nullReset() {
      assertThatThrownBy(() -> TestDatabase.of(fresh(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a blank reset statement is refused — an empty reset is a silent isolation bug")
    void blankReset() {
      assertThatThrownBy(() -> TestDatabase.of(fresh(), "   "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("a valid construction exposes the wrapped database")
    void wraps() {
      try (TestDatabase db = TestDatabase.of(fresh(), "DROP ALL OBJECTS")) {
        assertThat(db.database()).isNotNull();
      }
    }
  }

  @Nested
  @DisplayName("reset actually empties the schema")
  class Reset {

    @Test
    @DisplayName("rows written before reset() are gone after it")
    void emptiesRows() {
      try (TestDatabase db = TestDatabase.of(fresh(), "DROP ALL OBJECTS")) {
        db.database().executeUpdate("CREATE TABLE t (id INT)");
        db.database().executeUpdate("INSERT INTO t VALUES (1), (2)");
        assertThat(db.database().query("SELECT id FROM t", rs -> rs.getInt(1))).hasSize(2);
        db.reset();
        // DROP ALL OBJECTS removed the table, so a query for it now fails — the isolation the fixture promises.
        assertThatThrownBy(() -> db.database().query("SELECT id FROM t", rs -> rs.getInt(1)))
            .isInstanceOf(com.barezen.data.DataAccessException.class);
      }
    }

    @Test
    @DisplayName("a targeted reset statement empties a table while leaving the schema")
    void targetedReset() {
      try (TestDatabase db = TestDatabase.of(fresh(), "DELETE FROM t")) {
        db.database().executeUpdate("CREATE TABLE t (id INT)");
        db.database().executeUpdate("INSERT INTO t VALUES (1), (2), (3)");
        db.reset();
        List<Integer> remaining = db.database().query("SELECT id FROM t", rs -> rs.getInt(1));
        assertThat(remaining).isEmpty();
      }
    }

    @Test
    @DisplayName("close() resets, so leaving a try block leaves the schema empty")
    void closeResets() {
      DataSource dataSource = fresh();
      try (TestDatabase db = TestDatabase.of(dataSource, "DROP ALL OBJECTS")) {
        db.database().executeUpdate("CREATE TABLE t (id INT)");
        db.database().executeUpdate("INSERT INTO t VALUES (1)");
      }
      // Re-open the same database through a fresh fixture; the table should be gone because close() dropped it.
      try (TestDatabase reopened = TestDatabase.of(dataSource, "DROP ALL OBJECTS")) {
        assertThatThrownBy(() -> reopened.database().query("SELECT id FROM t", rs -> rs.getInt(1)))
            .isInstanceOf(com.barezen.data.DataAccessException.class);
      }
    }
  }
}
