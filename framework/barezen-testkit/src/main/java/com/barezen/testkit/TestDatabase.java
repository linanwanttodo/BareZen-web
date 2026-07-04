package com.barezen.testkit;

import com.barezen.data.Database;
import java.util.Objects;

/**
 * A {@link Database} paired with the schema a test needs, plus a {@link #reset()} that puts it back to empty between
 * tests.
 *
 * <p>This fixture does not own a database and does not create one. The application's test code constructs the
 * {@link javax.sql.DataSource} — an in-memory H2 pool in a test, whatever it likes — and hands it here; the testkit
 * only wraps the {@link Database} and the statements that reset it. That boundary is deliberate: a framework that
 * created its own datasource would either hard-code a driver (a dependency the framework does not have) or decide the
 * test's database for it. Owning the reset, not the connection, is the useful half.
 *
 * <p>H2 is a test-scope dependency of the consuming project, never of the framework. {@code TestDatabase} imports no
 * JDBC driver and names none; it works with any {@code DataSource} that speaks plain SQL.
 *
 * <pre>{@code
 * DataSource dataSource = new JdbcDataSource(); // the test's own H2 pool
 * try (TestDatabase db = TestDatabase.of(dataSource, "DROP ALL OBJECTS")) {
 *   db.database().executeUpdate("CREATE TABLE users (id INT, name VARCHAR(64))");
 *   // ... test against it ...
 *   db.reset();
 * }
 * }</pre>
 */
public final class TestDatabase implements AutoCloseable {

  private final Database database;
  private final String resetStatement;

  private TestDatabase(Database database, String resetStatement) {
    this.database = database;
    this.resetStatement = resetStatement;
  }

  /**
   * Wrap a {@link Database} over the application's datasource, with a statement that returns it to empty.
   *
   * @param dataSource the datasource the test built and owns; the framework borrows connections from it and returns
   *     them, exactly as {@link Database} does, and never closes the datasource itself
   * @param resetStatement a single SQL statement that empties the schema between tests, e.g. {@code "DROP ALL OBJECTS"}
   *     for H2; run by {@link #reset()}
   * @return a test database
   */
  public static TestDatabase of(javax.sql.DataSource dataSource, String resetStatement) {
    Objects.requireNonNull(dataSource, "the datasource the test owns; a test database over no datasource has nothing to"
        + " reset");
    Objects.requireNonNull(resetStatement, "the statement that empties the schema; reset() with no idea what to run"
        + " would be a silent no-op, which is the worst kind of test fixture");
    if (resetStatement.isBlank()) {
      throw new IllegalArgumentException("the reset statement must not be blank; an empty reset leaves rows between"
          + " tests and turns an isolation bug into a flaky failure far from its cause");
    }
    return new TestDatabase(new Database(dataSource), resetStatement);
  }

  /**
   * The wrapped {@link Database}, for the test to run queries and updates against.
   *
   * @return the database, never null
   */
  public Database database() {
    return database;
  }

  /**
   * Return the schema to empty by running the reset statement.
   *
   * <p>A test calls this between cases so one case's rows never become another's. If the reset fails, this throws — a
   * reset that swallows an error would let a test run against leftover state and report a false pass, which is the
   * isolation bug this whole fixture exists to prevent.
   */
  public void reset() {
    database.executeUpdate(resetStatement);
  }

  /**
   * Reset once and release this fixture's reference to the datasource.
   *
   * <p>The datasource itself is the test's to close, not this fixture's — it was handed in, so it is not closed here.
   * Closing resets, so a try-with-resources block leaves the schema empty for the next block even if the test forgot.
   */
  @Override
  public void close() {
    reset();
  }
}
