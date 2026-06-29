package com.barezen.http.jdk;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * A test fixture that reads what the adapter logged, so that the two halves of the error policy are both measured.
 * ADR-0023 §4 claims a handler exception goes to the log <em>and</em> not to the socket; the socket half is a response
 * body assertion, and this is the log half.
 *
 * <p>{@code ch.qos.logback} appears here and nowhere else in this module: {@code barezen-core} already carries logback
 * as a test dependency for the same reason, and the production code depends only on the SLF4J facade (ADR-0001). A test
 * without a binding cannot see a log line at all — SLF4J silently discards it — so asserting "the exception was recorded"
 * needs a recording appender, which needs a binding, which needs the implementation on the test classpath only.
 *
 * <p>Attached to one logger by name at one level, detached on {@link #close()}. Package private, test scope.
 */
final class CapturedLogs implements AutoCloseable {

  private final Logger logger;
  private final Level previousLevel;
  private final ListAppender<ILoggingEvent> appender;

  private CapturedLogs(Logger logger, Level previousLevel, ListAppender<ILoggingEvent> appender) {
    this.logger = logger;
    this.previousLevel = previousLevel;
    this.appender = appender;
  }

  /**
   * Start recording one logger.
   *
   * @param name the logger name the production code uses, for example {@code "com.barezen.http.jdk.JdkRequestDispatcher"}
   * @param level the level to record at; {@code DEBUG} catches the abandoned-response line, {@code ERROR} the handler
   *     failure alone
   * @return a recording handle, closeable so a test cannot leak an appender into the next one
   */
  static CapturedLogs record(String name, Level level) {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger logger = context.getLogger(name);
    Level previousLevel = logger.getLevel();
    logger.setLevel(level);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.setContext(context);
    appender.start();
    logger.addAppender(appender);
    return new CapturedLogs(logger, previousLevel, appender);
  }

  /** @return the events recorded so far, in the order they were logged */
  java.util.List<ILoggingEvent> events() {
    return appender.list;
  }

  /** @return the one recorded event, failing if there was not exactly one — most of these tests have one thing to log */
  ILoggingEvent onlyEvent() {
    if (appender.list.size() != 1) {
      throw new AssertionError("expected exactly one log event but found " + appender.list.size() + ": "
          + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }
    return appender.list.get(0);
  }

  @Override
  public void close() {
    logger.detachAppender(appender);
    appender.stop();
    logger.setLevel(previousLevel);
  }
}
