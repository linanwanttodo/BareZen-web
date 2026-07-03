package com.barezen.data;

import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/**
 * The in-memory H2 fixture every data test builds on, and the only place a test of this module names the driver.
 *
 * <p>One named database per fixture call, so no test can inherit another's schema or rows: {@code mem:<name>}
 * databases live in the JVM until it exits, which makes them independent units of work for a test that opens and
 * closes connections the way production code does, instead of a shared global schema tests have to co-ordinate
 * around. A unique name per database also keeps the concurrent-access test's writes from landing in a database a
 * different test happens to be reading.
 *
 * <p>H2 is test scope by ADR-0038 §Testing; nothing under {@code src/main} may name it, and the architecture test
 * of this module enforces exactly that separation.
 */
final class H2 {

  private static final AtomicInteger NAME = new AtomicInteger();

  private H2() {}

  /** A fresh, empty in-memory database, returned as the plain unpooled {@link DataSource} production code accepts. */
  static DataSource fresh() {
    return source("barezen_test_" + NAME.incrementAndGet());
  }

  /** A fresh database the same test can reopen by name after every connection is closed. */
  static DataSource named(String name) {
    return source(name);
  }

  private static DataSource source(String name) {
    JdbcDataSource dataSource = new JdbcDataSource();
    // DB_CLOSE_DELAY=-1 keeps the database alive with zero open connections until the JVM exits; without it the
    // first close would destroy the schema between a migration run and the test that reads its history back.
    dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    return dataSource;
  }

  /** A recording wrapper over a fresh database — the lifecycle probe used by the Stage 2.12-C tests. */
  static RecordingDataSource recording() {
    return new RecordingDataSource(fresh());
  }
}
