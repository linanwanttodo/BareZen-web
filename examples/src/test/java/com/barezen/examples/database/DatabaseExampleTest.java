package com.barezen.examples.database;

import com.barezen.data.Database;
import com.barezen.data.Migrator;
import org.junit.jupiter.api.Test;

import java.util.List;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the data example's contract against a real driver: a migration creates its table, a committed
 * transaction persists, and a transaction that throws leaves nothing behind.
 */
final class DatabaseExampleTest {

  @Test
  void migrationThenTransactionThenQueryRoundTrip() {
    DataSource dataSource = DatabaseExample.dataSource();
    Database database = new Database(dataSource);

    var applied = Migrator.of(database).migrate(DatabaseExample.migrations());
    assertThat(applied).singleElement()
        .satisfies(migration -> assertThat(migration.description()).isEqualTo("create notes"));

    database.transaction(tx -> {
      tx.executeUpdate("INSERT INTO notes (id, body) VALUES (?, ?)", 1, "first");
      return null;
    });

    List<String> bodies = database.query("SELECT body FROM notes ORDER BY id", rs -> rs.getString("body"));
    assertThat(bodies).containsExactly("first");
  }

  @Test
  void aTransactionThatThrowsRollsBackEverything() {
    Database database = new Database(DatabaseExample.dataSource());
    Migrator.of(database).migrate(DatabaseExample.migrations());

    List<String> kept = database.transaction(tx -> {
      tx.executeUpdate("INSERT INTO notes (id, body) VALUES (?, ?)", 1, "kept");
      return tx.query("SELECT body FROM notes", rs -> rs.getString("body"));
    });
    assertThat(kept).containsExactly("kept");

    // Now a transaction that fails mid-way: the framework rolls it back, so the doomed row never appears.
    try {
      database.transaction(tx -> {
        tx.executeUpdate("INSERT INTO notes (id, body) VALUES (?, ?)", 99, "doomed");
        throw new IllegalStateException("business failure");
      });
    } catch (RuntimeException expected) {
      // The framework's decision, not ours: the callback threw, so the transaction rolled back.
    }

    List<Long> ids = database.query("SELECT id FROM notes ORDER BY id", rs -> rs.getLong(1));
    assertThat(ids).containsExactly(1L);
  }
}
