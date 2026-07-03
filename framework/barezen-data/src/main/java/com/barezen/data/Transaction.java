package com.barezen.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * One unit of work against the database, decided by the code that opened it — which is never this object's
 * caller.
 *
 * <p>A transaction here is an object, never ambient state. {@link Database#transaction} opens one and hands it
 * to a lambda as a parameter, and this type has no static accessor, no thread local and no registry a caller
 * could consult — the same delivery decision the security layer made for its context (ADR-0033) and for the same
 * reason: a value reachable by magic is a value whose owner nobody can see.
 *
 * <p>Ownership rule (ADR-0039): a service method owns a unit of work, expresses it as one
 * {@link Database#transaction} call, and passes this object to every repository call it makes. A repository
 * cannot commit what it never opened, and here "cannot" is visibility, not convention: {@code commit} and
 * {@code rollback} are package-private, so the only type in the framework able to decide the fate of a
 * transaction is {@link Database}, and it decides once, at the boundary of its own {@code transaction} call.
 * "不要让每个 repository 自己 commit" stops being a review rule and becomes a compile error.
 *
 * <p>Nesting is not supported, and the API records that explicitly rather than leaving it to behaviour
 * (ADR-0039): {@link Database#transaction} is the only way to obtain a transaction, so a second call made while
 * one is already running opens a second, independent transaction on a second connection. The two never see each
 * other's uncommitted work, neither can decide the other's fate, and no savepoint exists. That consequence is
 * pinned by a test rather than discovered at the first lock contention.
 *
 * <p>Lifetime: a {@code Transaction} is valid only inside the callback that received it. {@link Database}
 * decides its fate at that boundary, and every method here fails with {@link DataAccessException} afterwards —
 * which is what stops a repository from holding a connection past its boundary, the observable half of the same
 * ownership rule.
 */
public final class Transaction {

  private final Connection connection;
  private final Runnable releaser;
  private boolean decided;

  /**
   * Puts a fresh connection into transaction scope.
   *
   * <p>Package private on purpose: {@link Database} is the only type allowed to decide when a connection enters
   * transaction scope, which is what keeps "every connection is a transaction" from becoming an accident
   * somewhere outside the one open point.
   *
   * @param connection a connection whose auto-commit has not been changed yet; on failure it is closed here
   * @param releaser returns the connection to its owner, run exactly once by the first decision ({@code commit}
   *     or {@code rollback}) and never afterwards
   * @throws DataAccessException if auto-commit cannot be disabled, because handing a caller a connection that
   *     might still commit on its own is worse than handing it none
   */
  Transaction(Connection connection, Runnable releaser) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.releaser = Objects.requireNonNull(releaser, "releaser");
    try {
      connection.setAutoCommit(false);
    } catch (SQLException e) {
      closeQuietly(connection);
      throw new DataAccessException("could not open a transaction: auto-commit could not be disabled", e);
    }
  }

  /**
   * Inserts, updates or deletes within this transaction, returning the affected row count.
   *
   * <p>The statement and its result resources are closed here, on every path including failure, so a
   * repository that calls this cannot leak a {@link java.sql.Statement} (Stage 2.12-C is a lifecycle test,
   * not a promise). Parameters are bound by position with {@link PreparedStatement#setObject}; a {@code null}
   * parameter binds as SQL {@code NULL}.
   *
   * @throws DataAccessException if the statement fails, or after this transaction has been decided
   */
  public int executeUpdate(String sql, Object... parameters) {
    Connection live = liveConnection();
    try (PreparedStatement statement = JdbcCollect.prepared(live, sql, parameters)) {
      return statement.executeUpdate();
    } catch (SQLException e) {
      throw new DataAccessException("update failed inside a transaction: " + sql, e);
    }
  }

  /**
   * Runs a query within this transaction and maps every row, in result-set order.
   *
   * <p>The {@link ResultSet} is positioned on each row before the mapper runs and closed after the last row or
   * on any mapper failure, so a {@link RowMapper} holds neither a leak nor a cursor of its own to manage.
   * Reads inside a transaction see this transaction's own uncommitted writes — which is what a unit of work
   * means — and nothing another transaction has not committed.
   *
   * @throws DataAccessException if the query fails, a row cannot be mapped, or this transaction has been decided
   */
  public <T> List<T> query(String sql, RowMapper<T> mapper, Object... parameters) {
    Objects.requireNonNull(mapper, "mapper");
    Connection live = liveConnection();
    try (PreparedStatement statement = JdbcCollect.prepared(live, sql, parameters);
        ResultSet resultSet = statement.executeQuery()) {
      return JdbcCollect.rows(resultSet, mapper);
    } catch (SQLException e) {
      throw new DataAccessException("query failed inside a transaction: " + sql, e);
    }
  }

  /**
   * Makes every statement run through this transaction durable, in one JDBC commit.
   *
   * <p>Package private (ADR-0039): {@link Database} calls it once, when its callback returns normally. A
   * commit that fails rolls back first — a connection whose commit threw cannot be assumed to have committed
   * nothing — and releases the connection before {@link DataAccessException} reaches the caller.
   */
  void commit() {
    requireUndecided();
    decided = true;
    try {
      connection.commit();
    } catch (SQLException e) {
      rollbackQuietly();
      release();
      throw new DataAccessException("transaction commit failed; the unit of work was rolled back", e);
    }
    release();
  }

  /**
   * Undoes everything this transaction did and releases the connection.
   *
   * <p>Package private, run by {@link Database} when its callback throws, and by {@link #close()} when a
   * callback returns without reaching {@link #commit()}. A rollback that itself fails still releases the
   * connection — keeping a poisoned connection out of the pool would turn one failure into a leak — and the
   * caller receives the rollback failure translated.
   */
  void rollback() {
    requireUndecided();
    decided = true;
    try {
      connection.rollback();
    } catch (SQLException e) {
      release();
      throw new DataAccessException("transaction rollback failed; the connection was released anyway", e);
    }
    release();
  }

  /**
   * Rolls back unless this transaction was already decided.
   *
   * <p>What {@link Database#transaction} runs at its boundary. Decided means committed or rolled back, and a
   * decided transaction closes into nothing: the caller is already receiving the data layer's own exception,
   * not a second one from cleanup.
   */
  void close() {
    if (!decided) {
      rollback();
    }
  }

  /** True once {@link #commit()} or {@link #rollback()} has run; the state every public method refuses. */
  boolean isDecided() {
    return decided;
  }

  /** Guards every public entry point: the boundary of the callback that received this object is its lifetime. */
  private Connection liveConnection() {
    if (decided) {
      throw new DataAccessException("transaction already committed or rolled back");
    }
    return connection;
  }

  private void requireUndecided() {
    if (decided) {
      throw new DataAccessException("transaction already committed or rolled back");
    }
  }

  /** Re-enables auto-commit before handing the connection back, so a pooled connection is never returned dirty. */
  private void release() {
    try {
      connection.setAutoCommit(true);
    } catch (SQLException ignored) {
      // The transaction's fate is already decided; a connection a pool can mark dirty is the pool's problem,
      // and replacing a commit or rollback outcome with this failure would hide it.
    }
    releaser.run();
  }

  private void rollbackQuietly() {
    try {
      connection.rollback();
    } catch (SQLException ignored) {
      // A rollback cannot restore what the commit failed to persist; the commit failure is the exception the
      // caller receives, and this only bounds the damage.
    }
  }

  private static void closeQuietly(Connection connection) {
    try {
      connection.close();
    } catch (SQLException ignored) {
      // Construction already failed, and that exception is what the caller receives.
    }
  }
}
