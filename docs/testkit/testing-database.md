# Testing a database

TestKit does not own a database. It wraps one: the application's test code constructs a `javax.sql.DataSource` — an
in-memory H2 pool, a Docker container, a stub, whatever the test actually needs — and hands it to
`TestDatabase.of(dataSource, resetStatement)`. The fixture adds the only two things the framework's own `Database` does
not have: a per-test reset with a clear failure when the reset is a no-op, and a `close()` that runs the reset so
leaving a try block never leaves a row behind.

## The rule that keeps this honest

Nothing in TestKit's `src/main` may name a JDBC driver (ADR-0044). The `pom.xml` keeps H2 test-scope; the framework
never hard-wires a driver, because a database the framework picks for you is a database the framework cannot take back.
What is banned is `org.h2..` and the other driver packages in main sources; `javax.sql.DataSource` — the port — is
deliberately legal, because `TestDatabase.of(DataSource, String)` takes exactly one.

## The one test shape

```java
import com.barezen.data.Database;
import com.barezen.testkit.TestDatabase;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class UserRepositoryTest {

  private static DataSource freshDatabase() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:user_repo_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    ds.setUser("sa");
    return ds;
  }

  @Test
  void save_then_find_returns_the_row() {
    try (TestDatabase db = TestDatabase.of(freshDatabase(), "DROP ALL OBJECTS")) {
      Database database = db.database();
      database.executeUpdate("CREATE TABLE users (id INT, name VARCHAR(64))");
      database.executeUpdate("INSERT INTO users VALUES (1, 'ada')");
      var rows = database.query("SELECT name FROM users WHERE id = 1", rs -> rs.getString(1));
      assertThat(rows).containsExactly("ada");
    }
  }

  @Test
  void reset_leaves_no_rows_for_the_next_case() {
    DataSource shared = freshDatabase();
    try (TestDatabase db = TestDatabase.of(shared, "DELETE FROM users")) {
      db.database().executeUpdate("CREATE TABLE users (id INT, name VARCHAR(64))");
      db.database().executeUpdate("INSERT INTO users VALUES (1, 'ada')");
      db.reset();
      assertThat(db.database().query("SELECT name FROM users WHERE id = 1", rs -> rs.getString(1))).isEmpty();
    }
  }
}
```

`System.nanoTime()` in the URL is the standard shape for an isolated in-memory database — two tests running in
parallel get two databases, and `DB_CLOSE_DELAY=-1` keeps the database alive while the fixture holds the `DataSource`.
TestKit does not do this for you; that is your test's `DataSource` construction, and TestKit does not touch it.

## What the reset actually is

`TestDatabase.reset()` runs `database.executeUpdate(resetStatement)` — one SQL statement through the same `Database`
the test uses, on the same path a repository's own `INSERT` takes. Two options are visible in the sample above:

- `DROP ALL OBJECTS` — an H2 statement that drops the whole schema. A test that runs a fresh `CREATE TABLE` per case
  picks this: after the reset, even the tables are gone.
- `DELETE FROM users` (or similar) — a targeted statement. A test that shares a schema between cases and only needs
  rows emptied picks this.

A blank reset statement is refused at construction with a message that says why ("an empty reset leaves rows between
tests and turns an isolation bug into a flaky failure far from its cause"). The point of the refusal is that a test
fixture nobody *asked* to reset is exactly the shape that leaks state between tests; the compiler will not catch it,
so the constructor does.

## Where transactions belong

`TestDatabase` does not open a transaction, does not commit one, and does not roll one back. It wraps
`com.barezen.data.Database`, and `Database.transaction(callback)` already decides that (ADR-0039): the callback receives
a `Transaction` whose `commit`/`rollback` are package-private to the data module, so a repository cannot bypass them,
and the wrapper decides based on whether the callback returned or threw. What testkit adds is not the transaction but
the *state boundary around it* — reset after each case, so a transaction left half-committed in one test's cleanup does
not poison the next. A test that wants to check a transactional behaviour calls `db.database().transaction(...)`
directly, exactly as an application service would.

## The migration seam

A test that needs a schema built by `Migrator` runs the migrator before the fixture's reset statement matters: the
migration *is* the setup, and the reset is only the teardown. `Migrator.run(...)` (ADR-0040) is a plain public call;
nothing in TestKit wraps it. A consumer that wants a per-test schema builds it once in a `@BeforeAll` and resets rows
per `@BeforeEach` — that shape composes cleanly with the `TestDatabase` fixture.

## What a real application swaps in

An application that ships a JDBC pool (HikariCP, an in-JVM `DataSource`, a testcontainer) hands that same pool to
`TestDatabase.of` without any change to the test body. The reset statement is a per-schema decision, and the fixture is
on the *test*-side of that decision, not on the framework's — which is why the framework can honestly promise that a
row cannot cross a test without either the test writing it or the reset not running.
