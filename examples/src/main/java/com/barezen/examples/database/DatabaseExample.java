package com.barezen.examples.database;

import com.barezen.data.Database;
import com.barezen.data.Migration;
import com.barezen.data.Migrator;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

/**
 * Data: a migration, a transaction, and a query — against a database the application supplies.
 *
 * <p>{@code barezen-data} speaks {@code javax.sql} only. It never names a driver and never builds a connection from a
 * URL; the application hands it a finished {@link DataSource}. Here the data source is an in-memory H2 instance, and
 * that is the one thing this example hard-wires — exactly what an application does when it chooses its database, and
 * what a framework module is forbidden to do (ADR-0038). The rule holds where it matters: this leaf names the driver,
 * the framework never does.
 *
 * <p>Three operations show the module's contract. {@link Migrator} runs ordered migrations and records each in a
 * history table it owns, so a version is applied if and only if its {@code apply} completed. {@link Database} wraps
 * one connection in {@code transaction(...)} and decides commit or rollback for you: the callback returns, the work
 * commits; the callback throws, it rolls back — {@code commit}/{@code rollback} are not even callable from here.
 * {@link Database#query} maps rows through a {@code RowMapper} and returns a list.
 */
public final class DatabaseExample {

  private DatabaseExample() {
  }

  /** The application's own choice of database, made where an application makes it. */
  public static DataSource dataSource() {
    org.h2.jdbcx.JdbcDataSource source = new org.h2.jdbcx.JdbcDataSource();
    // A unique in-memory name per run so the example is repeatable from a guaranteed-clean slate: the migrator's
    // history table is the framework's authority (ADR-0040), and re-running against a stale history would skip the
    // migration this run expects to apply. DB_CLOSE_DELAY keeps it alive for the life of the JVM.
    source.setURL("jdbc:h2:mem:barezen_example_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    return source;
  }

  /** One migration that creates a table, using idempotent DDL because a retry re-runs an unrecorded version. */
  static List<Migration> migrations() {
    return List.of(new Migration() {
      @Override
      public long version() {
        return 1;
      }

      @Override
      public String description() {
        return "create notes";
      }

      @Override
      public void apply(Connection connection) throws SQLException {
        connection.createStatement().execute(
            "CREATE TABLE IF NOT EXISTS notes (id BIGINT PRIMARY KEY, body VARCHAR(255) NOT NULL)");
      }
    });
  }

  public static void main(String[] args) {
    Database database = new Database(dataSource());

    var applied = Migrator.of(database).migrate(migrations());
    System.out.println("migrated -> " + applied);

    database.transaction(tx -> {
      tx.executeUpdate("INSERT INTO notes (id, body) VALUES (?, ?)", 1, "first");
      tx.executeUpdate("INSERT INTO notes (id, body) VALUES (?, ?)", 2, "second");
      return null;
    });

    List<String> bodies = database.query("SELECT body FROM notes ORDER BY id", rs -> rs.getString("body"));
    System.out.println("notes    -> " + bodies);

    // A transaction that throws leaves nothing behind — the framework rolls it back before the exception propagates.
    try {
      database.transaction(tx -> {
        tx.executeUpdate("INSERT INTO notes (id, body) VALUES (?, ?)", 3, "doomed");
        throw new IllegalStateException("business failure mid-transaction");
      });
    } catch (RuntimeException expected) {
      long count = database.query("SELECT COUNT(*) FROM notes", rs -> rs.getLong(1)).get(0);
      System.out.println("after rollback, rows -> " + count + " (the doomed insert never landed)");
    }
  }
}
