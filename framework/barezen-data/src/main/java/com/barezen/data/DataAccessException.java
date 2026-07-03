package com.barezen.data;

/**
 * The one exception every data-layer failure arrives as.
 *
 * <p>A {@link java.sql.SQLException} is checked, names a driver-specific error code and appears at every JDBC
 * call, so without a single translation point the alternatives are a {@code throws SQLException} on every
 * repository method — which pushes driver taxonomy into services that can do nothing with it — or catching and
 * dropping, which loses the failure. Both are rejected (ADR-0038): the data layer translates instead.
 *
 * <p>Unchecked for the reason recorded in ADR-0009: the code that notices a persistence failure is rarely the
 * code that can repair one, and an HTTP request handler has the same two real answers — retry the operation or
 * fail the request — whether the compiler was told or not.
 *
 * <p>Deliberately a direct {@link RuntimeException} rather than a subclass of anything in
 * {@code com.barezen.core.error}: those types describe assembly, lifecycle and configuration failures, which are
 * fixed by editing application code, while a persistence failure is usually fixed by the database or the query.
 * A shared parent would let one catch block claim both are the same problem. Phase 2.8's {@code BarezenException}
 * taxonomy is the place where such a shared parent could be decided; see ADR-0009.
 *
 * <p>The {@link java.sql.SQLException} is always preserved as the cause — SQL state and driver codes are the
 * operator's diagnostic detail — but the message here names the operation and the SQL, because
 * {@code getMessage()} on a JDBC exception often repeats the SQL without saying what the framework was doing
 * when it failed.
 */
public class DataAccessException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public DataAccessException(String message) {
    super(message);
  }

  public DataAccessException(String message, Throwable cause) {
    super(message, cause);
  }
}
