package com.barezen.data;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps one row of an open {@link ResultSet} to a value.
 *
 * <p>A functional interface with one method, deliberately shaped like a row and not like a collection: the
 * caller names the columns it reads and returns one object, and the data layer owns the cursor, the ordering and
 * the closing. A mapper that returned a list would have to decide when reading stops, which is the same mistake a
 * {@code Statement}-handing API would be (ADR-0038).
 *
 * <p>{@link SQLException} is declared because reading a column through JDBC throws it, and a mapper that could
 * not state a failed read would have to invent a sentinel for a missing value. Every framework path that invokes
 * a mapper is inside the one translation point, so the exception a mapper lets through arrives at the caller as
 * {@link DataAccessException} with the SQL that produced it (ADR-0038).
 *
 * <p>The result set is positioned on the current row when {@link #map} is called and must not be closed,
 * advanced or retained by the implementation: after the call the cursor belongs to the framework again, and the
 * row is gone as soon as the next one is read. A mapper that stores the result set to read later is reading a
 * closed cursor, and it will meet that as a {@link DataAccessException} in production rather than here.
 *
 * @param <T> the type one row becomes
 */
@FunctionalInterface
public interface RowMapper<T> {

  /** Reads the current row and returns the value it represents. */
  T map(ResultSet resultSet) throws SQLException;
}
