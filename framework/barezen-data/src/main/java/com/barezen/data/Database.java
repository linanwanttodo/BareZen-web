package com.barezen.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The entry point of the data layer: one {@link DataSource} in, transactions and queries out.
 *
 * <p>This class deliberately does not know how to create a connection. It takes a finished
 * {@link javax.sql.DataSource} and never names a driver, a URL, a user or a password — not because those are
 * hard to read from configuration, but because a framework that builds its own connections must know which
 * database product it is talking to, and that is the moment a data layer stops being portable. The application's
 * assembly code picks the driver (from its own configuration, through {@code Configuration}, per ADR-0013) and
 * hands the result over; {@code Database} is product-agnostic by construction, and it is the reason no production
 * code of this framework can depend on a test database (ADR-0038).
 *
 * <p>{@code DataSource} is also the seam every pooled implementation already exposes, which means connection
 * pooling is an application decision that needs no framework type: an unpooled
 * {@code org.h2.jdbcx.JdbcDataSource} and a pooled third-party source arrive through the same constructor, and
 * {@code Database} behaves identically because it never asks what kind it got.
 *
 * <p>Two access styles, and the difference between them is the whole transaction story (ADR-0039):
 * <ul>
 *   <li>{@link #query} and {@link #executeUpdate} run on a connection borrowed for the call, in the pool's
 *       auto-commit default, and are for statements that are complete units by themselves — a single read, a
 *       single row written regardless of anything else;
 *   <li>{@link #transaction} opens one connection, puts it in transaction scope, runs a
 *       {@link TransactionCallback} with the {@link Transaction} as its parameter, and commits when the callback
 *       returns normally. A unit of work that spans more than one statement uses this path and only this path,
 *       because a repository committing its own transaction (impossible here — {@code commit} is not public) or a
 *       service spreading one write across two auto-commit calls (visible here as two borrowed connections) are
 *       both shapes the API refuses to express.
 * </ul>
 *
 * <p>Connection lifecycle, stated as guarantees and tested as such (Stage 2.12-C): every connection this class
 * takes from the {@link DataSource} is closed before any public method returns, on every path — normal return,
 * translated failure, an application exception thrown from inside a transaction callback, or a cleanup failure of
 * its own. The same holds for {@link PreparedStatement} and {@link ResultSet}: they exist inside
 * try-with-resources at this level and are never handed to a caller.
 *
 * <p>Exception translation has exactly one home: every {@link SQLException} that leaves this module is rethrown
 * as {@link DataAccessException} with the SQL or the operation named, and the driver exception kept as the cause
 * (ADR-0038). A runtime exception thrown by a {@link TransactionCallback} is <em>not</em> translated — it is the
 * application's own failure type, and wrapping an {@code IllegalStateException} from business code in a
 * persistence exception would claim a database problem that never happened.
 *
 * <p>Thread safety: this class holds one field and opens connections per call, so two threads using the same
 * {@code Database} are two independent units of work; whether that is safe is the {@link DataSource}'s answer to
 * concurrency, and the contract of a pooled source already says yes.
 */
public final class Database {

  private final DataSource dataSource;

  /**
   * Takes the source every connection will come from.
   *
   * @param dataSource the application's source, pooled or not, created by its assembly code; {@code null}
   *     refused, because a {@code Database} without a source cannot answer a single method and a half-built
   *     object is worse than a failed constructor
   */
  public Database(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  /**
   * Runs one query on a connection borrowed for the call, mapping every row.
   *
   * <p>Auto-commit semantics of the underlying source apply: whatever the statement writes is already durable
   * when this returns, so a read is safe here and a multi-statement unit of work is not (ADR-0039).
   *
   * @throws DataAccessException if no connection can be opened or the query fails; the connection is closed
   *     before this throws either way
   */
  public <T> List<T> query(String sql, RowMapper<T> mapper, Object... parameters) {
    Objects.requireNonNull(mapper, "mapper");
    Connection connection = acquire();
    try (PreparedStatement statement = JdbcCollect.prepared(connection, sql, parameters);
        ResultSet resultSet = statement.executeQuery()) {
      return JdbcCollect.rows(resultSet, mapper);
    } catch (SQLException e) {
      throw new DataAccessException("query failed: " + sql, e);
    } finally {
      release(connection);
    }
  }

  /**
   * Runs one insert, update or delete on a connection borrowed for the call and returns the affected row count.
   *
   * <p>For a write that is a complete unit by itself. A write that must happen together with another write
   * belongs in {@link #transaction}: two calls of this method are two commits, and the second failing after the
   * first succeeding is the partial unit of work ADR-0039 exists to prevent.
   *
   * @throws DataAccessException if no connection can be opened or the statement fails
   */
  public int executeUpdate(String sql, Object... parameters) {
    Connection connection = acquire();
    try (PreparedStatement statement = JdbcCollect.prepared(connection, sql, parameters)) {
      return statement.executeUpdate();
    } catch (SQLException e) {
      throw new DataAccessException("update failed: " + sql, e);
    } finally {
      release(connection);
    }
  }

  /**
   * Runs one unit of work inside one transaction and commits it only if the unit of work completed.
   *
   * <p>The decision table, tested as a table (Stage 2.12-D): the callback returns normally → {@code commit()};
   * the callback throws anything, including its own {@link SQLException} from raw JDBC and any runtime exception
   * from application code → {@code rollback()} and the failure propagates, translated if it was the data
   * layer's; the callback returns but the commit fails → the transaction rolls back first and
   * {@link DataAccessException} reports the commit failure with the rollback failure attached if that failed too.
   * In every row of that table the connection is closed before {@code transaction} returns, and {@link
   * Transaction} is no longer usable afterwards.
   *
   * <p>What this method does <em>not</em> do is notice that a transaction is already open. A second call made
   * while one is running — from the same thread, a nested lambda, anywhere — opens a second transaction on a
   * second connection, and the two are independent (ADR-0039). That is the documented answer to nesting, not an
   * accident of implementation.
   *
   * @param work the unit of work, handed the transaction it runs on
   * @return what the callback produced, now durable
   * @throws DataAccessException if a connection cannot be opened, the callback fails through the database, or
   *     commit or rollback itself fails
   */
  public <T> T transaction(TransactionCallback<T> work) {
    Objects.requireNonNull(work, "work");
    Connection connection = acquire();
    // The constructor owns the connection from here: on its own failure it closes, and on success the releaser
    // below closes exactly once, at whichever decision ends the transaction.
    final Transaction transaction = new Transaction(connection, () -> release(connection));
    try {
      T result;
      try {
        result = work.doInTransaction(transaction);
      } catch (SQLException failure) {
        // Raw JDBC inside a callback: roll the unit of work back, then translate. The failure the caller gets
        // is the database's, and a rollback that also fails travels with it suppressed rather than replacing it.
        rollbackWithSuppressed(transaction, failure);
        throw new DataAccessException("transaction failed and was rolled back", failure);
      } catch (RuntimeException | Error failure) {
        // An application failure (including a DataAccessException a repository call produced): the unit of work
        // is broken and nothing of it may stay. The failure itself is rethrown untranslated — it names the
        // application's problem, not a persistence one (ADR-0038).
        rollbackWithSuppressed(transaction, failure);
        throw failure;
      }
      transaction.commit();
      return result;
    } finally {
      // The explicit never-left-open guarantee: a callback that returned and committed needs nothing here, and
      // anything that reached this point undecided rolls back.
      transaction.close();
    }
  }

  /**
   * Opens one connection for a sequence of statements that must behave as one unit outside transaction scope.
   *
   * <p>Package-private seam used by {@link Migrator}, which owns a long-lived connection across an ordered run
   * and closes it itself. Kept off the public API on purpose: handing out a raw {@link Connection} is the shape
   * that puts JDBC in every repository and makes auto-commit decisions invisible (ADR-0038), so the only type
   * allowed to hold one is inside this module's own boundary.
   *
   * @throws DataAccessException if the source cannot produce a connection
   */
  Connection openConnection() {
    return acquire();
  }

  private Connection acquire() {
    try {
      return dataSource.getConnection();
    } catch (SQLException e) {
      throw new DataAccessException("could not acquire a connection from the data source", e);
    }
  }

  private static void rollbackWithSuppressed(Transaction transaction, Throwable failure) {
    try {
      transaction.rollback();
    } catch (DataAccessException cleanup) {
      // The failure that ended the unit of work is the caller's exception; a rollback that also failed means
      // the connection is in an unknown state, and that belongs attached, not on top.
      failure.addSuppressed(cleanup);
    }
  }

  /**
   * Returns a connection to its source. A failure here cannot be reported to the caller without replacing the
   * outcome the caller is already receiving, and a source that cannot close its own connection has the leak; the
   * close is attempted on every path, and its failure cannot travel back into a decided result (ADR-0038).
   */
  private static void release(Connection connection) {
    try {
      connection.close();
    } catch (SQLException ignored) {
      // No logging facade exists in main sources (the same rule the security module enforces, ADR-0037's
      // dependency argument); an uncloseable connection is the pool's own failure to report.
    }
  }
}
