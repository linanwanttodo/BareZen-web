package com.barezen.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Stage 2.12-F and G, against a real database: every state the directive names — fresh, existing, already
 * applied, duplicate, failed, partial-pending — plus the queryable history that makes the rest of it auditable.
 *
 * <p>The failed-migration tests here are also a measurement: they assert that H2 commits DDL implicitly, the
 * same behaviour MySQL and MariaDB have, and that the framework's guarantee therefore lives in the history
 * table — a version is recorded if and only if its apply completed — not in the schema. ADR-0040 records that
 * boundary; a test that asserted schema rollback would be asserting behaviour this build measured false.
 */
@DisplayName("migrations")
class MigratorTest {

  private static final Instant FIXED = Instant.parse("2026-10-03T09:00:00Z");

  /** One migration, declared as data so a test reads as the change list it stands for. */
  private static Migration migration(long version, String description, String... statements) {
    return new Migration() {
      @Override
      public long version() {
        return version;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public void apply(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
          for (String sql : statements) {
            statement.execute(sql);
          }
        }
      }
    };
  }

  /** A migration that also appends its version to {@code order}, to prove the run order is the version order. */
  private static Migration tracked(long version, StringBuilder order, String... statements) {
    Migration inner = migration(version, "v" + version, statements);
    return new Migration() {
      @Override
      public long version() {
        return inner.version();
      }

      @Override
      public String description() {
        return inner.description();
      }

      @Override
      public void apply(Connection connection) throws SQLException {
        order.append(version);
        inner.apply(connection);
      }
    };
  }

  @Nested
  @DisplayName("fresh database")
  class Fresh {

    @Test
    @DisplayName("an empty database receives every migration, in version order, and returns them all")
    void everyMigrationRunsInOrder() {
      DataSource database = H2.fresh();
      Database subject = new Database(database);
      Migrator migrator = Migrator.of(subject, Clock.fixed(FIXED, ZoneOffset.UTC));

      List<AppliedMigration> applied = migrator.migrate(List.of(
          migration(2, "items", "CREATE TABLE item (id INT PRIMARY KEY)"),
          migration(1, "widget", "CREATE TABLE widget (id INT PRIMARY KEY)")));

      assertThat(applied).extracting(AppliedMigration::version).containsExactly(1L, 2L);
      assertThat(tableExists(database, "widget")).isTrue();
      assertThat(tableExists(database, "item")).isTrue();
    }

    @Test
    @DisplayName("apply order follows versions even when the given list order does not")
    void listOrderIsIgnoredAndVersionOrderRuns() {
      DataSource database = H2.fresh();
      Migrator migrator = Migrator.of(new Database(database));
      StringBuilder order = new StringBuilder();

      List<Migration> shuffled = List.of(
          tracked(3, order, "CREATE TABLE t3 (id INT)"),
          tracked(1, order, "CREATE TABLE t1 (id INT)"),
          tracked(2, order, "CREATE TABLE t2 (id INT)"));

      List<AppliedMigration> applied = migrator.migrate(shuffled);

      assertThat(applied).extracting(AppliedMigration::version).containsExactly(1L, 2L, 3L);
      assertThat(order.toString()).as("the DDL actually ran in version order, not list order").isEqualTo("123");
      assertThat(historyVersions(migrator)).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("a migration list given out of order still applies every migration once")
    void everyMigrationRunsExactlyOncePerVersion() {
      DataSource database = H2.fresh();
      Migrator migrator = Migrator.of(new Database(database));

      AtomicInteger runs = new AtomicInteger();
      Migration counted = new Migration() {
        @Override
        public long version() {
          return 1;
        }

        @Override
        public String description() {
          return "counted";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
          runs.incrementAndGet();
        }
      };

      migrator.migrate(List.of(counted));
      migrator.migrate(List.of(counted));

      assertThat(runs.get()).as("migrate at every startup is legal: the second run skips the recorded version")
          .isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("existing database")
  class Existing {

    @Test
    @DisplayName("an already applied version is skipped, and the run returns only what it applied")
    void alreadyAppliedVersionsAreSkipped() {
      DataSource database = H2.fresh();
      Database subject = new Database(database);
      Migrator migrator = Migrator.of(subject);

      List<AppliedMigration> firstRun = migrator.migrate(List.of(migration(1, "one")));
      List<AppliedMigration> secondRun = migrator.migrate(
          List.of(migration(1, "one"), migration(2, "two")));

      assertThat(firstRun).hasSize(1);
      assertThat(secondRun).as("the run's return is its own effect, not the database's whole state")
          .extracting(AppliedMigration::version).containsExactly(2L);
    }

    @Test
    @DisplayName("a current database is a legal no-op that returns nothing")
    void runningWithNothingPendingIsEmpty() {
      DataSource database = H2.fresh();
      Migrator migrator = Migrator.of(new Database(database));
      migrator.migrate(List.of(migration(1, "one"), migration(2, "two")));

      assertThat(migrator.migrate(List.of(migration(1, "one"), migration(2, "two"))))
          .as("startup against an up-to-date database must succeed, not warn")
          .isEmpty();
      assertThat(historyVersions(migrator)).containsExactly(1L, 2L);
    }
  }

  @Nested
  @DisplayName("duplicate version")
  class Duplicate {

    @Test
    @DisplayName("two migrations claiming one version are refused before the database is touched")
    void duplicateVersionsFailTheWholeRunBeforeAnythingRuns() {
      DataSource database = H2.fresh();
      Migrator migrator = Migrator.of(new Database(database));

      assertThatThrownBy(() -> migrator.migrate(List.of(
          migration(1, "first claim"), migration(1, "second claim"))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("version 1")
          .hasMessageContaining("twice");

      assertThat(tableExists(database, Migrator.HISTORY_TABLE))
          .as("a refused list must leave no half-made history table")
          .isFalse();
    }
  }

  @Nested
  @DisplayName("failed migration")
  class Failure {

    @Test
    @DisplayName("a failed migration records no version, stops the run, and its DDL trace is the product's answer")
    void failureRecordsNothingAndRunsNothingAfterIt() {
      DataSource database = H2.fresh();
      AtomicInteger secondRun = new AtomicInteger();
      Migration fails = new Migration() {
        @Override
        public long version() {
          return 1;
        }

        @Override
        public String description() {
          return "creates then fails";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
          try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orphan (id INT)");
          }
          throw new SQLException("the migration decided halfway through");
        }
      };
      Migration later = new Migration() {
        @Override
        public long version() {
          return 2;
        }

        @Override
        public String description() {
          return "must not run";
        }

        @Override
        public void apply(Connection connection) {
          secondRun.incrementAndGet();
        }
      };
      Migrator migrator = Migrator.of(new Database(database));

      assertThatThrownBy(() -> migrator.migrate(List.of(fails, later)))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("migration 1")
          .hasMessageContaining("rollback was attempted");

      // The framework's guarantee, measured on H2 rather than assumed: the version is NOT recorded and the run
      // stopped, so the next startup re-runs it. The orphan table itself remains — this probe measures H2's
      // implicit DDL commit (JDBC default in its compatibility modes, like MySQL's), which is exactly the
      // exception ADR-0040 states instead of papering over: history is the authority, the schema is the
      // product's, and recovery after a failure is re-run-and-repair.
      assertThat(migrator.history()).as("no history row for a version that never completed").isEmpty();
      assertThat(secondRun.get()).as("a run that failed stopped there").isZero();
      assertThat(tableExists(database, "orphan")).as("measured, not assumed: H2 kept the rolled-back CREATE").isTrue();
    }

    @Test
    @DisplayName("a failed DDL-only migration recovers on re-run once its object is written defensively")
    void aFailedVersionIsRunnableAgainAndADefensiveApplyCompletes() {
      DataSource database = H2.fresh();
      Migrator migrator = Migrator.of(new Database(database));
      AtomicInteger attempts = new AtomicInteger();
      // The recovery shape ADR-0040 prescribes: an unrecorded version re-runs at the next startup, so a
      // migration whose first attempt left a DDL trace behind writes that step idempotently
      // (IF NOT EXISTS) and completes on the retry. A migration that blindly re-CREATEs would fail forever,
      // and this test would be the demonstration — it is the contract's sharp edge, documented here because
      // the framework cannot smooth it on every product.
      Migration flaky = new Migration() {
        @Override
        public long version() {
          return 1;
        }

        @Override
        public String description() {
          return "succeeds on a retry";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
          // The first attempt leaves its DDL trace behind (measured above), then fails; the retry completes
          // because its CREATE is written IF NOT EXISTS. This is the recovery shape, demonstrated honestly.
          try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS item (id INT PRIMARY KEY)");
          }
          if (attempts.incrementAndGet() == 1) {
            throw new SQLException("first attempt failed after writing its own DDL");
          }
        }
      };

      assertThatThrownBy(() -> migrator.migrate(List.of(flaky))).isInstanceOf(DataAccessException.class);
      assertThat(tableExists(database, "item"))
          .as("the first attempt's trace survived its own failure, exactly as the probe above measured")
          .isTrue();
      List<AppliedMigration> retry = migrator.migrate(List.of(flaky));

      assertThat(retry).extracting(AppliedMigration::version).containsExactly(1L);
      assertThat(tableExists(database, "item")).isTrue();
    }

    @Test
    @DisplayName("a rollback failure after a failed migration commit is suppressed and the version stays unrecorded")
    void commitAndRollbackFailuresAreBothReported() {
      RecordingDataSource recorded = H2.recording();
      Migrator migrator = Migrator.of(new Database(recorded));
      recorded.failNextCommit();
      recorded.failNextRollback();

      assertThatThrownBy(() -> migrator.migrate(List.of(
          migration(1, "create item", "CREATE TABLE item (id INT PRIMARY KEY)"))))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("migration 1")
          .hasMessageContaining("rollback was attempted")
          .hasMessageNotContaining("was rolled back")
          .satisfies(failure -> assertThat(failure.getSuppressed())
              .hasSize(1)
              .allMatch(suppressed -> suppressed instanceof SQLException
                  && suppressed.getMessage().contains("rollback")));

      assertThat(migrator.history()).isEmpty();
      assertThat(recorded.leaks()).isEmpty();
    }

    @Test
    @DisplayName("a lost commit response is resolved from recorded history on the next run")
    void aFailedCommitResponseDoesNotGuessWhetherTheVersionLanded() {
      RecordingDataSource recorded = H2.recording();
      Migrator migrator = Migrator.of(new Database(recorded));
      Migration migration = migration(1, "create item", "CREATE TABLE item (id INT PRIMARY KEY)");
      recorded.failAfterNextCommit();

      assertThatThrownBy(() -> migrator.migrate(List.of(migration)))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("rollback was attempted")
          .hasCauseInstanceOf(SQLException.class);

      assertThat(historyVersions(migrator)).containsExactly(1L);
      assertThat(migrator.migrate(List.of(migration))).isEmpty();
      assertThat(recorded.leaks()).isEmpty();
    }

    @Test
    @DisplayName("a runtime failure thrown by a migration also aborts the run")
    void uncheckedMigrationFailuresAlsoRollBackAndStopTheRun() {
      RecordingDataSource recorded = H2.recording();
      Migration throwsRuntime = new Migration() {
        @Override
        public long version() {
          return 1;
        }

        @Override
        public String description() {
          return "throws unchecked";
        }

        @Override
        public void apply(Connection connection) {
          throw new IllegalArgumentException("a migration bug is a migration bug");
        }
      };
      Migrator migrator = Migrator.of(new Database(recorded));
      recorded.failNextRollback();

      assertThatThrownBy(() -> migrator.migrate(List.of(throwsRuntime)))
          .isInstanceOf(IllegalArgumentException.class)
          .as("an application-style failure stays itself; the data layer does not translate what it did not cause")
          .hasMessage("a migration bug is a migration bug")
          .satisfies(failure -> assertThat(failure.getSuppressed())
              .hasSize(1)
              .allMatch(suppressed -> suppressed instanceof SQLException
                  && suppressed.getMessage().contains("rollback")));

      assertThat(migrator.history()).isEmpty();
      assertThat(recorded.leaks()).isEmpty();
    }
  }

  @Nested
  @DisplayName("out-of-order pending version")
  class OutOfOrder {

    @Test
    @DisplayName("a version below the applied maximum is refused before anything runs, naming both versions")
    void aLowerPendingVersionIsRefused() {
      DataSource database = H2.fresh();
      Migrator migrator = Migrator.of(new Database(database));
      migrator.migrate(List.of(migration(2, "two first"), migration(10, "ten")));

      assertThatThrownBy(() -> migrator.migrate(List.of(migration(5, "the forgotten one"))))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("migration 5")
          .hasMessageContaining("10");

      assertThat(tableExists(database, "item")).isFalse();
      assertThat(historyVersions(migrator)).as("the refusal ran nothing, so the history did not move")
          .containsExactly(2L, 10L);
    }

    @Test
    @DisplayName("a version above the applied maximum runs normally — the check targets inversions, not gaps")
    void aHigherPendingVersionRunsAfterAGap() {
      DataSource database = H2.fresh();
      Migrator migrator = Migrator.of(new Database(database));
      migrator.migrate(List.of(migration(1, "first", "CREATE TABLE a (id INT)")));

      List<AppliedMigration> applied = migrator.migrate(
          List.of(migration(4, "fourth, after a gap", "CREATE TABLE b (id INT)")));

      assertThat(applied).extracting(AppliedMigration::version).containsExactly(4L);
      assertThat(historyVersions(migrator)).containsExactly(1L, 4L);
    }

    @Test
    @DisplayName("a re-run of the full list after a migration is added keeps the already-applied lower versions")
    void anUpToDateListIsNotAnInversionJustBecauseItsFirstVersionIsLow() {
      DataSource database = H2.fresh();
      Migrator migrator = Migrator.of(new Database(database));
      migrator.migrate(List.of(migration(1, "one"), migration(2, "two")));

      // The design-bug probe: checking pending against ALL given versions would read version 1 as an inversion
      // here and refuse a list that has nothing to run. The refusal is about what would run.
      assertThat(migrator.migrate(List.of(migration(1, "one"), migration(2, "two"), migration(3, "three"))))
          .extracting(AppliedMigration::version).containsExactly(3L);
    }
  }

  @Nested
  @DisplayName("validation before any database contact")
  class Validation {

    @Test
    @DisplayName("a non-positive version is refused before the connection opens")
    void nonPositiveVersionFailsBeforeTheDatabase() {
      DataSource database = H2.fresh();
      Migrator migrator = Migrator.of(new Database(database));

      assertThatThrownBy(() -> migrator.migrate(List.of(migration(0, "zero"))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("positive");
      assertThat(tableExists(database, Migrator.HISTORY_TABLE))
          .as("a refused list touched nothing, history table included")
          .isFalse();
    }

    @Test
    @DisplayName("a blank description is refused, because a history row must say what it was")
    void blankDescriptionFails() {
      DataSource database = H2.fresh();
      Migrator migrator = Migrator.of(new Database(database));

      assertThatThrownBy(() -> migrator.migrate(List.of(migration(1, "  "))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("blank description");
    }

    @Test
    @DisplayName("a null list is refused, and a null element inside a list is refused")
    void nullListAndNullElementFail() {
      Migrator migrator = Migrator.of(new Database(H2.fresh()));

      assertThatThrownBy(() -> migrator.migrate(null)).isInstanceOf(NullPointerException.class);
      // Arrays.asList, not List.of: the latter would throw before the framework ever saw the list, and the
      // probe is meant to test validateAndSort's own check, not the JDK's.
      assertThatThrownBy(() -> migrator.migrate(
          java.util.Arrays.asList(migration(1, "ok"), null)))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("history, queryable")
  class History {

    @Test
    @DisplayName("history reads version, description and the injected clock's instant")
    void historyRowsCarryTheFactsADuplicateCouldNot() {
      DataSource database = H2.fresh();
      Database subject = new Database(database);
      Migrator migrator = Migrator.of(subject, Clock.fixed(FIXED, ZoneOffset.UTC));

      migrator.migrate(List.of(migration(7, "adds widget", "CREATE TABLE widget (id INT PRIMARY KEY)")));

      List<AppliedMigration> history = migrator.history();
      assertThat(history).singleElement().satisfies(row -> {
        assertThat(row.version()).isEqualTo(7L);
        assertThat(row.description()).isEqualTo("adds widget");
        assertThat(row.appliedAt()).isEqualTo(FIXED);
      });

      // The rows are database state, not migrator memory: a fresh migrator over the same source reads them.
      assertThat(Migrator.of(subject, Clock.offset(Clock.systemUTC(), Duration.ofHours(1))).history())
          .containsExactlyElementsOf(history);
    }

    @Test
    @DisplayName("an unmigrated database reads as an empty history, not as a failure")
    void historyOnAFreshDatabaseIsEmpty() {
      Migrator migrator = Migrator.of(new Database(H2.fresh()));

      assertThat(migrator.history()).isEmpty();
    }

    @Test
    @DisplayName("history survives a second Migrator and a second connection — it lives in the database")
    void historyIsQueryableAcrossConnections() {
      DataSource database = H2.fresh();
      Migrator first = Migrator.of(new Database(database));
      first.migrate(List.of(migration(1, "one", "CREATE TABLE a (id INT)")));

      Migrator reopened = Migrator.of(new Database(H2.named(urlName(database))));
      assertThat(reopened.history()).extracting(AppliedMigration::description).containsExactly("one");
    }
  }

  @Nested
  @DisplayName("construction and clock defaulting")
  class Construction {

    @Test
    @DisplayName("a null database or clock is refused by both factories")
    void factoriesRefuseNullCollaborators() {
      assertThatThrownBy(() -> Migrator.of(null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> Migrator.of(new Database(H2.fresh()), null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the one-argument factory uses the real clock, so a run stamps now, not a fixed instant")
    void theDefaultClockStampsThePresent() {
      Migrator migrator = Migrator.of(new Database(H2.fresh()));
      Instant before = Instant.now();

      List<AppliedMigration> applied = migrator.migrate(List.of(migration(1, "one")));

      assertThat(applied.get(0).appliedAt()).isAfterOrEqualTo(before.minusSeconds(1));
    }
  }

  @Nested
  @DisplayName("the applied list is the run's effect")
  class RunEffect {

    @Test
    @DisplayName("a partially run migration list leaves the database exactly at the last committed version")
    void aRunThatFailedHalfwayRecordsOnlyWhatCommitted() {
      DataSource database = H2.fresh();
      Migrator migrator = Migrator.of(new Database(database));

      Migration boom = new Migration() {
        @Override
        public long version() {
          return 2;
        }

        @Override
        public String description() {
          return "fails";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
          try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE should_not_exist (id INT)");
          }
          throw new SQLException("stop here");
        }
      };

      assertThatThrownBy(() -> migrator.migrate(List.of(migration(1, "fine", "CREATE TABLE fine (id INT)"), boom)))
          .isInstanceOf(DataAccessException.class);

      assertThat(tableExists(database, "fine")).as("version 1 committed before the failure stayed").isTrue();
      // The measured behaviour, asserted as-is (the Failure probe above states why): the rolled-back version 2
      // left its DDL trace on H2, while its history row did not exist — the version list is what the framework
      // guarantees, and the run stopped at the failure.
      assertThat(tableExists(database, "should_not_exist")).as("the DDL trace survives; the version does not")
          .isTrue();
      assertThat(historyVersions(migrator)).containsExactly(1L);
    }
  }

  private static List<Long> historyVersions(Migrator migrator) {
    return migrator.history().stream().map(AppliedMigration::version).toList();
  }

  private static boolean tableExists(DataSource dataSource, String table) {
    try (Connection connection = dataSource.getConnection();
        java.sql.ResultSet found = connection.getMetaData()
            .getTables(connection.getCatalog(), null, null, null)) {
      while (found.next()) {
        if (table.equalsIgnoreCase(found.getString("table_name"))) {
          return true;
        }
      }
      return false;
    } catch (SQLException e) {
      throw new AssertionError("fixture could not list tables", e);
    }
  }

  /** Extracts the {@code mem:<name>} part of a fixture URL, so a reopened source reaches the same database. */
  private static String urlName(DataSource dataSource) {
    if (dataSource instanceof org.h2.jdbcx.JdbcDataSource h2) {
      String url = h2.getURL();
      return url.substring("jdbc:h2:mem:".length(), url.indexOf(';'));
    }
    throw new IllegalArgumentException("fixture source must be an H2 source: " + dataSource);
  }
}
