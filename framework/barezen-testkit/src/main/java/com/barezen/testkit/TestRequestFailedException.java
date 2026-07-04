package com.barezen.testkit;

/**
 * Raised when a request made through {@link TestHttpClient} could not be completed at all — as opposed to completed
 * and answered with an error status, which is a normal {@link TestResponse}.
 *
 * <p>The distinction matters in a test. A {@code 500} says the server ran and said no; a connection refused says the
 * server was not reachable, which is almost always a broken test setup (a server that was closed too early, a wrong
 * port, a client pointed at nothing) and should read like one rather than like a stack trace from the HTTP client.
 *
 * <p>Package-private: a test does not catch this to recover, it lets the failure end the test with a clear message.
 */
final class TestRequestFailedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  TestRequestFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
