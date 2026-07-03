package com.barezen.data;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The two JDBC primitives every read in this module shares: position binding and result-set iteration.
 *
 * <p>Package private: {@link Database} and {@link Transaction} are the only entry points to the data layer, and
 * each of their read methods is the same three lines — prepare, execute, collect — so those lines exist once,
 * here. A {@code null} parameter binds as SQL {@code NULL} through {@link PreparedStatement#setObject(int,
 * Object)} with no type hint, which is what a portable framework's parameter binding should mean: the caller
 * declared a value and the driver declares how to store it.
 */
final class JdbcCollect {

  private JdbcCollect() {}

  /**
   * Prepares {@code sql} and binds its parameters by position.
   *
   * <p>If binding fails the statement is closed here before the exception escapes, so a caller inside a
   * try-with-resources header never holds a prepared statement it cannot reach.
   */
  static PreparedStatement prepared(java.sql.Connection connection, String sql, Object[] parameters)
      throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    try {
      bind(statement, parameters);
      return statement;
    } catch (SQLException | RuntimeException | Error e) {
      try {
        statement.close();
      } catch (SQLException ignored) {
        e.addSuppressed(ignored);
      }
      throw e;
    }
  }

  /** Binds parameters by 1-based JDBC position; a {@code null} parameter means SQL {@code NULL}. */
  static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
    if (parameters != null) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
    }
  }

  /**
   * Maps every row of an open result set, in cursor order, into a list.
   *
   * <p>The mapper runs on a positioned row and its failures are not caught here: a row that cannot be mapped is
   * a data or schema error, and the caller's try-with-resources closes the result set while the SQL-shaped
   * exception travels up to the one translation point.
   */
  static <T> List<T> rows(ResultSet resultSet, RowMapper<T> mapper) throws SQLException {
    List<T> collected = new ArrayList<>();
    while (resultSet.next()) {
      collected.add(mapper.map(resultSet));
    }
    return collected;
  }
}
