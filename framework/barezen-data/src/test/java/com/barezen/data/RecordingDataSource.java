package com.barezen.data;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * A {@link DataSource} that counts what every connection, statement and result set it hands out is asked to do,
 * and can be told to make the next JDBC call of a named kind fail.
 *
 * <p>Why a proxy over a real {@code DataSource} instead of a hand-written fake: the behaviours under test —
 * commit, rollback, auto-commit restoration, close on every path — are JDBC <em>protocol</em> questions, and a
 * fake that only answers the calls its author remembered proves the framework agrees with its own assumptions,
 * not with JDBC. This wrapper delegates every call to a real driver (H2 in tests) and adds two things the
 * production API cannot give a test: counts, and injected failures at the exact JDBC seam.
 *
 * <p>The counts answer Stage 2.12-C as written: connection acquired, statement closed, result set closed,
 * connection returned/closed, exception cleanup. {@link #openConnections()}, {@link #openStatements()} and
 * {@link #openCursors()} name what is still <em>not</em> closed, so a leak is a non-empty list with the
 * offending SQL in it, not a missing assertion about a boolean someone hoped would flip.
 *
 * <p>Thread safe enough for the concurrency test: every recording list is synchronised, every counter atomic.
 */
final class RecordingDataSource implements DataSource {

  private final DataSource delegate;
  private final List<Tracked> connections = Collections.synchronizedList(new ArrayList<>());
  private final List<Tracked> statements = Collections.synchronizedList(new ArrayList<>());
  private final List<Tracked> cursors = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger commits = new AtomicInteger();
  private final AtomicInteger rollbacks = new AtomicInteger();
  private final AtomicInteger autoCommitChanges = new AtomicInteger();
  private final AtomicInteger updateExecutions = new AtomicInteger();

  /** Each named JDBC method in this set fails once, independently of the other armed methods. */
  private final Set<String> armedFailures = ConcurrentHashMap.newKeySet();

  RecordingDataSource(DataSource delegate) {
    this.delegate = delegate;
  }

  /** Makes exactly the next {@code Connection.commit} fail; every later call behaves. */
  void failNextCommit() {
    armedFailures.add("commit");
  }

  /** Makes exactly the next {@code Connection.rollback} fail. */
  void failNextRollback() {
    armedFailures.add("rollback");
  }

  /** Makes exactly the next {@code Connection.setAutoCommit} fail. */
  void failNextSetAutoCommit() {
    armedFailures.add("setAutoCommit");
  }

  /** Makes exactly the next {@code PreparedStatement.setObject} fail — the bind seam of a prepared statement. */
  void failNextBind() {
    armedFailures.add("setObject");
  }

  /** The decision-bearing JDBC calls made on connections, in order — "commit after rollback?" reads from here. */
  List<String> decisionSequence() {
    return new ArrayList<>(decisions);
  }

  private final List<String> decisions = Collections.synchronizedList(new ArrayList<>());

  int commits() {
    return commits.get();
  }

  int rollbacks() {
    return rollbacks.get();
  }

  int autoCommitChanges() {
    return autoCommitChanges.get();
  }

  int updateExecutions() {
    return updateExecutions.get();
  }

  /** Connections taken from this source and never closed. Empty is the only acceptable answer (Stage 2.12-C). */
  List<String> openConnections() {
    return stillOpen(connections);
  }

  List<String> openStatements() {
    return stillOpen(statements);
  }

  List<String> openCursors() {
    return stillOpen(cursors);
  }

  /** Connections taken and not yet closed <em>or</em> statements and cursors still open — one assertion for "nothing leaked". */
  List<String> leaks() {
    List<String> all = new ArrayList<>(openConnections());
    all.addAll(openStatements());
    all.addAll(openCursors());
    return all;
  }

  int connectionsAcquired() {
    return connections.size();
  }

  private static List<String> stillOpen(List<Tracked> tracked) {
    List<String> open = new ArrayList<>();
    synchronized (tracked) {
      for (Tracked item : tracked) {
        if (!item.closed) {
          open.add(item.label);
        }
      }
    }
    return open;
  }

  /** True once, for a matching armed seam; clears the arm so the same failure fires exactly one time. */
  private boolean consumeArmedFailure(String method) {
    return armedFailures.remove(method);
  }

  private static final class Tracked {
    final String label;
    volatile boolean closed;

    Tracked(String label) {
      this.label = label;
    }
  }

  private void markClosed(Tracked tracked) {
    tracked.closed = true;
  }

  @Override
  public Connection getConnection() throws SQLException {
    Connection real = delegate.getConnection();
    Tracked tracked = new Tracked("connection#" + (connections.size() + 1));
    connections.add(tracked);
    return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
        new ConnectionHandler(real, tracked));
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    throw new UnsupportedOperationException("the tests configure no credentials");
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    return delegate.unwrap(iface);
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return delegate.isWrapperFor(iface);
  }

  @Override
  public java.io.PrintWriter getLogWriter() throws SQLException {
    return delegate.getLogWriter();
  }

  @Override
  public void setLogWriter(java.io.PrintWriter out) throws SQLException {
    delegate.setLogWriter(out);
  }

  @Override
  public void setLoginTimeout(int seconds) throws SQLException {
    delegate.setLoginTimeout(seconds);
  }

  @Override
  public int getLoginTimeout() throws SQLException {
    return delegate.getLoginTimeout();
  }

  @Override
  public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException("no java.util.logging in tests either");
  }

  /** Intercepts the connection calls the data layer's lifecycle is made of; everything else delegates untouched. */
  private final class ConnectionHandler implements InvocationHandler {
    private final Connection real;
    private final Tracked tracked;

    ConnectionHandler(Connection real, Tracked tracked) {
      this.real = real;
      this.tracked = tracked;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      String name = method.getName();
      switch (name) {
        case "close":
          markClosed(tracked);
          return callReal(method, args);
        case "commit":
          commits.incrementAndGet();
          decisions.add("commit");
          return maybeFailing(method, args);
        case "rollback":
          rollbacks.incrementAndGet();
          decisions.add("rollback");
          return maybeFailing(method, args);
        case "setAutoCommit":
          autoCommitChanges.incrementAndGet();
          decisions.add("setAutoCommit:" + args[0]);
          return maybeFailing(method, args);
        case "prepareStatement":
          return trackStatement(real.prepareStatement((String) args[0]));
        case "createStatement":
          return trackPlainStatement(real.createStatement());
        case "unwrap":
          return real.unwrap((Class<?>) args[0]);
        case "isWrapperFor":
          return real.isWrapperFor((Class<?>) args[0]);
        default:
          return callReal(method, args);
      }
    }

    private Object callReal(Method method, Object[] args) throws Throwable {
      try {
        return method.invoke(real, args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }

    /** One armed failure, then the method behaves again — so a test that arms twice gets two failures. */
    private Object maybeFailing(Method method, Object[] args) throws Throwable {
      if (consumeArmedFailure(method.getName())) {
        throw new SQLException("injected failure at the JDBC seam: " + method.getName());
      }
      return callReal(method, args);
    }

    private PreparedStatement trackStatement(PreparedStatement realStatement) {
      Tracked statementTracked = new Tracked("statement#" + (statements.size() + 1));
      statements.add(statementTracked);
      return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
          new Class<?>[] {PreparedStatement.class}, new StatementHandler(realStatement, statementTracked));
    }

    private Statement trackPlainStatement(Statement realStatement) {
      Tracked statementTracked = new Tracked("statement#" + (statements.size() + 1));
      statements.add(statementTracked);
      return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Statement.class},
          new StatementHandler(realStatement, statementTracked));
    }
  }

  /** Counts statement closes, and wraps the result sets a query opens so their closes are visible too. */
  private final class StatementHandler implements InvocationHandler {
    private final Statement real;
    private final Tracked tracked;

    StatementHandler(Statement real, Tracked tracked) {
      this.real = real;
      this.tracked = tracked;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      String name = method.getName();
      switch (name) {
        case "close":
          tracked.closed = true;
          break;
        case "executeUpdate":
          updateExecutions.incrementAndGet();
          break;
        case "setObject":
          if (consumeArmedFailure(name)) {
            throw new SQLException("injected failure at the JDBC seam: " + name);
          }
          break;
        case "executeQuery":
          ResultSet realResultSet = (ResultSet) method.invoke(real, args);
          Tracked cursorTracked = new Tracked("cursor#" + (cursors.size() + 1));
          cursors.add(cursorTracked);
          return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
              (cursorProxy, cursorMethod, cursorArgs) -> {
                if ("close".equals(cursorMethod.getName())) {
                  cursorTracked.closed = true;
                }
                try {
                  return cursorMethod.invoke(realResultSet, cursorArgs);
                } catch (InvocationTargetException e) {
                  throw e.getCause();
                }
              });
        case "unwrap":
          return real.unwrap((Class<?>) args[0]);
        case "isWrapperFor":
          return real.isWrapperFor((Class<?>) args[0]);
        default:
          break;
      }
      try {
        return method.invoke(real, args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }
  }
}
