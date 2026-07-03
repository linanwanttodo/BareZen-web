package com.barezen.data;

import java.time.Instant;
import java.util.Objects;

/**
 * One migration the database has already accepted, as recorded in the history table.
 *
 * <p>{@link Migrator#migrate} returns the list of these produced by its run, and {@link Migrator#history}
 * returns the whole recorded past, which is what makes migration state answerable from the database itself
 * instead of from the application's current code (ADR-0040). A row written by an older version of this framework
 * reads back through the same record.
 *
 * @param version the version number the migration declared, unique in the history
 * @param description the human label the migration declared, recorded so an operator never has to read code to
 *     know what a version was
 * @param appliedAt when {@link Migrator} recorded it, from the clock the migrator was built with — a history
 *     timestamp says when the row was written, not when the code was compiled, and it is a queryable fact rather
 *     than a derived one
 */
public record AppliedMigration(long version, String description, Instant appliedAt) {

  public AppliedMigration {
    if (version <= 0) {
      throw new IllegalArgumentException("migration version must be positive, got " + version);
    }
    Objects.requireNonNull(description, "migration description must not be null");
    Objects.requireNonNull(appliedAt, "migration appliedAt must not be null");
  }
}
