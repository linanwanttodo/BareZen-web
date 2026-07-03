package com.barezen.data;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * One schema or data change, identified by a version number that never changes once applied.
 *
 * <p>A migration is the one place an application speaks to the database in the database's own language. That is
 * why {@link #apply} receives a raw {@link Connection} and not a framework wrapper: a migration needs DDL,
 * dialect-specific types, and occasionally a backfill that no abstraction of ours would honestly express. It does
 * not contradict the boundary ADR-0038 draws — a {@link Connection} is <em>handed to</em> the migration by
 * {@link Migrator}, exactly as a {@link Transaction} is handed to a callback by {@link Database}, so nothing ever
 * reaches for one, and the type stays inside the data module's own API. A repository that could name a
 * {@code Connection} is a repository that owns transactions, which ADR-0039 refuses.
 *
 * <p>Shape of a real migration, stated because the failure modes are specific: {@link #version()} is a positive
 * long assigned once and never reused or renumbered; {@link #description()} is a human label recorded in the
 * history table so an operator reading a database can tell what each version was; {@link #apply} runs the change.
 * A migration does not have to protect itself from the framework re-running what it already recorded — {@link
 * Migrator} guarantees a recorded version never runs again — but it does have to be re-runnable after its own
 * failure, because an unrecorded version runs again at the next startup and the product may still hold the
 * first attempt's trace (the contract below): write DDL steps idempotently, {@code IF NOT EXISTS} and its
 * equivalents, and a retried migration completes.
 *
 * <p>Implementations must not commit, roll back, or close the connection they are given: the {@link Migrator}
 * runs each migration in its own transaction and writes the history row inside it, which is what makes a
 * recorded version one whose {@link #apply} ran to completion. What the framework cannot promise is the mirror
 * on every product: a failed migration that already executed DDL may leave schema behind even though its
 * version was never recorded — the {@code MigratorTest} failed-migration probe measures exactly that on H2 —
 * so recovery after a failure is re-run-and-repair against the history, and ADR-0040 states that rule as the
 * contract instead of a rollback guarantee this build has measured to be false.
 */
public interface Migration {

  /**
   * The version this migration introduces: positive, unique across the set handed to one
   * {@link Migrator#migrate} run, and strictly greater than every version already applied — a version added later
   * than one that already ran is reported rather than skipped (ADR-0040).
   */
  long version();

  /**
   * A short human label, stored with the version in the history table. Blank is refused by the
   * {@link Migrator}, because a history row that says nothing is a row an operator has to go and read code for.
   */
  String description();

  /**
   * Applies the change on the connection {@link Migrator} opened and owns.
   *
   * <p>Throwing any exception, checked or not, means this migration's version is not recorded, no later
   * migration runs, and the failure reaches the caller of {@link Migrator#migrate}. It does <em>not</em> mean
   * no trace of the migration is visible in the schema: the history row is what the framework guarantees, and
   * the class contract above states why the schema is the product's answer.
   */
  void apply(Connection connection) throws SQLException;
}
