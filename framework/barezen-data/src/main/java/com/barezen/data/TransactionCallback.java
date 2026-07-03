package com.barezen.data;

import java.sql.SQLException;

/**
 * A unit of work that runs inside one transaction and produces a value.
 *
 * <p>The parameter is not optional sugar, it is the design: {@link Database} opens a transaction and hands it
 * over as {@code tx}, so every repository call a unit of work makes is visibly made <em>with</em> a transaction
 * rather than with whatever the ambient state happens to hold (ADR-0033, ADR-0039). The callback cannot commit —
 * {@link Transaction}'s fate is decided only by {@link Database} — which is what makes a partial unit of work
 * impossible to hide behind a repository that committed early.
 *
 * <p>Declared {@code throws SQLException} because a unit of work reads and writes through JDBC-shaped calls
 * that can fail, and those failures arrive at the caller translated into {@link DataAccessException} with this
 * callback's SQL context (ADR-0038). The callback itself never catches an {@code SQLException} to hide it.
 *
 * <p>Returning {@code void} is legal — a lambda body that finishes with an update and a {@code return;} is a
 * {@code TransactionCallback<Void>} — but a unit of work that needs to report success should return something,
 * because a caller that ignores a completion value is also the caller that ignores a rolled-back write.
 *
 * @param <T> what the unit of work produces
 */
@FunctionalInterface
public interface TransactionCallback<T> {

  /**
   * Runs the unit of work on {@code transaction}.
   *
   * <p>When this method returns normally the transaction commits; when it throws — a
   * {@link DataAccessException} from a repository call, an {@link SQLException} from raw JDBC, or any runtime
   * exception — the transaction rolls back and the failure reaches the caller, translated where the data layer
   * produced it.
   */
  T doInTransaction(Transaction transaction) throws SQLException;
}
