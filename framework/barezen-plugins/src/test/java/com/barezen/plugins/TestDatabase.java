package com.barezen.plugins;

import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/**
 * The in-memory H2 fixture the user-plugin tests build on — the same shape {@code com.barezen.data}'s own test
 * fixture uses (ADR-0038 §Testing), copied rather than reached for because that class is package-private and
 * reaching into a sibling module's test scope is the import this framework's boundaries keep closed.
 *
 * <p>One named database per call so no test inherits another's rows, and H2 stays test-scope: nothing under
 * {@code src/main} may name it and the module's architecture test enforces that separation (ADR-0042).
 */
public final class TestDatabase {

  private static final AtomicInteger NAME = new AtomicInteger();

  private TestDatabase() {}

  /** A fresh, empty in-memory database, as the plain unpooled {@link DataSource} production code accepts. */
  public static DataSource fresh() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:barezen_plugin_test_" + NAME.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    return dataSource;
  }
}
