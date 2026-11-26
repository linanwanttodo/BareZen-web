package com.barezen.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Brings a database to the schema the application's current migrations describe, and records what it did.
 *
 * <p>The model is one table and one rule. The table, {@code barezen_migration_history}, holds one row per
 * version the database accepted: its number, its description, and when it was recorded. The rule: {@link
 * #migrate} applies, in ascending version order, exactly the migrations whose version is not in that table, and
 * writes each version's row <em>inside the same transaction</em> as the change itself. A recorded version is
 * therefore a version whose apply ran to completion — the direction a startup can act on — and the paragraph
 * below this one states, from a measurement rather than from hope, what the framework cannot promise about a
 * version that failed halfway (ADR-0040).
 *
 * <p>The states and what each one does, as a table because the behaviours differ and every row is tested:
 * <ul>
 *   <li><b>fresh database</b> — no history table: it is created, every migration runs in version order, the run
 *       returns every version as newly applied;
 *   <li><b>existing database</b> — history present: only versions the table does not list run; the run returns
 *       exactly those, in ascending order;
 *   <li><b>already applied</b> — a version already in the table is skipped, and skipping is silent and cheap:
 *       {@code migrate} is safe to run at every startup, which is how it is meant to be used;
 *   <li><b>duplicate version in the given list</b> — refused before anything touches the database: two migrations
 *       claiming one version means the version no longer identifies a change, so the run throws
 *       {@link IllegalArgumentException} naming it;
 *   <li><b>failed migration</b> — rollback is attempted, no later migration runs, and a SQL failure reaches the
 *       caller as {@link DataAccessException} naming the version and description; a failed commit response has an
 *       uncertain outcome, so the next run reads <em>history</em> as the authority. The guarantee below states
 *       what that does and does not say about the schema;
 *   <li><b>out-of-order version</b> — a pending version lower than the highest already-applied one is refused
 *       before anything runs. It is the one state that cannot be answered safely: silently skipping it rewrites
 *       history, silently applying it means migration 7 ran after migration 12 against a schema that assumed 12
 *       happened second, so it fails loudly (ADR-0040).
 * </ul>
 *
 * <p>The guarantee the framework can make is about its own writes: it records a version only after apply, history
 * insertion and commit all return normally. A commit response can still fail after the database committed, so the
 * actual history table — not the exception alone — decides what runs next. Whether the <em>schema</em> is intact
 * after a failed migration is the product's answer, not ours — the failed-migration probe in {@code MigratorTest}
 * measures that H2, like MySQL and MariaDB, commits DDL implicitly, so a rolled-back {@code CREATE TABLE} can remain
 * visible even though its version was never recorded. That is why history, not schema, is authoritative: startup
 * skips recorded versions and re-runs unrecorded ones. ADR-0040 records re-run-and-repair rather than a promise of
 * per-dialect rollback this build cannot keep.
 *
 * <p>What a migration may do with the connection it is handed is stated in {@link Migration}; what this class
 * never does is expose the history table as anything other than the typed {@link AppliedMigration} list, so an
 * application cannot write a history row behind a migration's back.
 */
public final class Migrator {

  /** The one table this class owns; the name is part of the module's contract (ADR-0040). */
  static final String HISTORY_TABLE = "barezen_migration_history";

  private final Database database;
  private final Clock clock;

  private Migrator(Database database, Clock clock) {
    this.database = database;
    this.clock = clock;
  }

  /**
   * A migrator over {@code database}, stamping history rows with the system clock in UTC.
   *
   * <p>Same defaulting shape as the security module's session store: the clock is injectable because a test
   * asserting an {@link AppliedMigration#appliedAt()} has to control it, and a history table with an
   * un-queryable timestamp is a worse audit log than none.
   */
  public static Migrator of(Database database) {
    Objects.requireNonNull(database, "database");
    return new Migrator(database, Clock.systemUTC());
  }

  /** A migrator whose history timestamps come from {@code clock}. */
  public static Migrator of(Database database, Clock clock) {
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(clock, "clock");
    return new Migrator(database, clock);
  }

  /**
   * Applies every given migration the database has not yet accepted, and returns exactly what this run applied.
   *
   * <p>Order of events for each applied version, in one commit: {@link Migration#apply} runs, then the history
   * row is inserted. A normal return holds the list of versions this run recorded. A failure returns no list and
   * stops later versions; earlier committed versions remain. Because a driver can fail the commit response after
   * the database committed, an exception does not prove the failed version is absent — the next run reads history
   * as the authority and either skips a recorded version or re-runs an unrecorded one.
   *
   * @param migrations the application's full migration list, any order; each is validated before anything runs
   * @return the migrations this run applied and recorded, ascending by version — empty when the database was
   *     already current
   * @throws IllegalArgumentException for a non-positive version, a blank description, or a duplicate version in
   *     the list; these are code errors fixed by editing the list, not database states
   * @throws DataAccessException for an out-of-order pending version, or any failure against the database —
   *     including a migration's own {@link SQLException}, which arrives naming that migration's version
   */
  public List<AppliedMigration> migrate(List<Migration> migrations) {
    Objects.requireNonNull(migrations, "migrations");
    List<Migration> ordered = validateAndSort(migrations);
    Instant now = clock.instant();
    List<AppliedMigration> applied = new ArrayList<>();
    Connection connection = database.openConnection();
    try {
      createHistoryTable(connection);
      Set<Long> alreadyApplied = readAppliedVersions(connection);
      List<Migration> pending = new ArrayList<>();
      for (Migration migration : ordered) {
        if (!alreadyApplied.contains(migration.version())) {
          pending.add(migration);
        }
      }
      // The out-of-order refusal is a statement about what <em>would</em> run: a version already in the history
      // sitting below the applied maximum is recorded fact, not an inversion, so pending is filtered first and
      // only the smallest pending version is checked (ADR-0040).
      rejectOutOfOrder(alreadyApplied, pending);
      for (Migration migration : pending) {
        applyOne(connection, migration, now, applied);
      }
      return applied;
    } catch (SQLException e) {
      throw new DataAccessException("migration run failed before the first version was recorded", e);
    } finally {
      closeQuietly(connection);
    }
  }

  /**
   * The whole recorded history, ascending by version, read live from the database.
   *
   * <p>Legal on a database that has never been migrated — an absent history table reads as an empty history
   * rather than an error, because "what is applied" has exactly one honest answer there and a caller inspecting
   * a fresh database is asking a legitimate question (ADR-0040).
   *
   * @throws DataAccessException if a connection cannot be opened or the history cannot be read
   */
  public List<AppliedMigration> history() {
    Connection connection = database.openConnection();
    try {
      if (!historyTableExists(connection)) {
        return List.of();
      }
      List<AppliedMigration> rows = new ArrayList<>();
      try (Statement statement = connection.createStatement();
          ResultSet resultSet = statement.executeQuery(
              "SELECT version, description, applied_at FROM " + HISTORY_TABLE + " ORDER BY version")) {
        while (resultSet.next()) {
          rows.add(new AppliedMigration(resultSet.getLong("version"), resultSet.getString("description"),
              resultSet.getTimestamp("applied_at").toInstant()));
        }
      }
      return rows;
    } catch (SQLException e) {
      throw new DataAccessException("could not read the migration history", e);
    } finally {
      closeQuietly(connection);
    }
  }

  /**
   * Validates and orders the list before any database contact, so an invalid list costs nothing to discover.
   *
   * <p>Ascending sort is the framework's decision, not the caller's: migration code that depends on a list order
   * it has to guess is the bug the version numbers exist to remove.
   */
  private static List<Migration> validateAndSort(List<Migration> migrations) {
    Set<Long> seen = new HashSet<>();
    for (Migration migration : migrations) {
      Objects.requireNonNull(migration, "migration list must not contain null");
      long version = migration.version();
      if (version <= 0) {
        throw new IllegalArgumentException("migration version must be positive, got " + version
            + " (\"" + migration.description() + "\")");
      }
      String description = migration.description();
      if (description == null || description.isBlank()) {
        throw new IllegalArgumentException(
            "migration " + version + " has a blank description; a history row must say what it was");
      }
      if (!seen.add(version)) {
        throw new IllegalArgumentException("migration version " + version
            + " appears twice in the given list; a version identifies one change");
      }
    }
    List<Migration> ordered = new ArrayList<>(migrations);
    ordered.sort(java.util.Comparator.comparingLong(Migration::version));
    return ordered;
  }

  /**
   * Refuses a pending version below the highest applied one, before running anything (the out-of-order state).
   *
   * <p>Checked against the sorted pending list's first element only, because ascending order plus one check
   * covers every pending version: if the smallest pending version is above the applied maximum, so is all of them.
   */
  private static void rejectOutOfOrder(Set<Long> alreadyApplied, List<Migration> orderedPending) {
    if (alreadyApplied.isEmpty() || orderedPending.isEmpty()) {
      return;
    }
    long highestApplied = alreadyApplied.stream().mapToLong(Long::longValue).max().orElseThrow();
    long lowestPending = orderedPending.get(0).version();
    if (lowestPending <= highestApplied) {
      throw new DataAccessException("migration " + lowestPending + " was never applied, but version "
          + highestApplied + " already is: applying it now would run it out of order against a schema written"
          + " by later migrations. History says what ran; this run refuses to contradict it (ADR-0040)");
    }
  }

  /**
   * Runs one migration and its history row as one committed unit, on the shared connection.
   *
   * <p>Auto-commit is switched off for exactly this window and restored after the commit. Any failure stops later
   * versions. A SQL failure reports that rollback was attempted; an unchecked failure propagates unchanged. Both
   * keep a rollback failure as suppressed. A failed commit response has an uncertain outcome, so the next run
   * reads history rather than assuming "version N-1" — with the schema caveat the class javadoc states from
   * measurement.
   */
  private void applyOne(Connection connection, Migration migration, Instant now, List<AppliedMigration> sink)
      throws SQLException {
    connection.setAutoCommit(false);
    try {
      migration.apply(connection);
      try (PreparedStatement record = connection.prepareStatement(
          "INSERT INTO " + HISTORY_TABLE + " (version, description, applied_at) VALUES (?, ?, ?)")) {
        record.setLong(1, migration.version());
        record.setString(2, migration.description());
        record.setTimestamp(3, Timestamp.from(now));
        record.executeUpdate();
      }
      connection.commit();
    } catch (SQLException | RuntimeException | Error failure) {
      // No later migration runs. SQL failures are translated; unchecked failures stay themselves. In both paths a
      // rollback failure is suppressed on the first failure, and closeQuietly drops the connection afterwards.
      if (failure instanceof SQLException sql) {
        DataAccessException translated = new DataAccessException("migration " + migration.version() + " (\""
            + migration.description() + "\") failed; rollback was attempted; no later migration ran", sql);
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          translated.addSuppressed(rollbackFailure);
        }
        throw translated;
      }
      try {
        connection.rollback();
      } catch (SQLException rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
      }
      throw failure;
    }
    // Only a committed version restores auto-commit, and only then does the run continue to the next migration.
    restoreAutoCommitQuietly(connection);
    sink.add(new AppliedMigration(migration.version(), migration.description(), now));
  }

  /**
   * The history table, if the application's own migrations have not made it yet.
   *
   * <p>{@code CREATE TABLE IF NOT EXISTS} in the plainest SQL that works on the products ADR-0040 names: a
   * versioned {@code BIGINT} primary key — the primary key is the duplicate guard at the database level, the
   * in-list check is the earlier, kinder one — a description, and a {@code TIMESTAMP} applied at. A product
   * needing a different spelling is a dialect question this boundary answers once, and it is raised as an ADR,
   * not as a per-product branch in framework code.
   */
  private static void createHistoryTable(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE IF NOT EXISTS " + HISTORY_TABLE
          + " (version BIGINT PRIMARY KEY, description VARCHAR(512) NOT NULL, applied_at TIMESTAMP NOT NULL)");
    }
  }

  private static boolean historyTableExists(Connection connection) throws SQLException {
    // The pattern argument of getTables is a case-sensitive literal on every product we test against, and
    // unquoted identifiers fold to upper case on H2 and lower case on PostgreSQL — so the portable answer is to
    // list the tables and compare names without case. Naming the product instead would be the dialect branch
    // ADR-0040 refuses.
    try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, null, null)) {
      while (tables.next()) {
        if (HISTORY_TABLE.equalsIgnoreCase(tables.getString("table_name"))) {
          return true;
        }
      }
    }
    return false;
  }

  private static void restoreAutoCommitQuietly(Connection connection) {
    try {
      connection.setAutoCommit(true);
    } catch (SQLException ignored) {
      // The version is committed and the run's result is already known; a connection the source cannot reset
      // leaves with the rest of the run's connection, closed below by the same rule as everywhere in this module.
    }
  }

  private static Set<Long> readAppliedVersions(Connection connection) throws SQLException {
    Set<Long> versions = new HashSet<>();
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("SELECT version FROM " + HISTORY_TABLE)) {
      while (resultSet.next()) {
        versions.add(resultSet.getLong(1));
      }
    }
    return versions;
  }

  /** Same never-left-open rule as {@link Database}: the run's outcome is reported, a failed close cannot outrank it. */
  private static void closeQuietly(Connection connection) {
    try {
      connection.close();
    } catch (SQLException ignored) {
      // The source owns what an uncloseable connection costs; this run already produced its answer.
    }
  }
}
