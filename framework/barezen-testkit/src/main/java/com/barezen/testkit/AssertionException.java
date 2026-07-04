package com.barezen.testkit;

/**
 * Raised when a testkit assertion does not hold.
 *
 * <p>An {@code AssertionError}, not an {@code Exception}: a failed expectation is a test failure, and JUnit reports
 * {@code AssertionError} as one without treating it as an error or a broken fixture. It is package-private because a
 * test never catches it — the message is for whoever reads the failure, and the throwing is what matters.
 */
final class AssertionException extends AssertionError {

  private static final long serialVersionUID = 1L;

  AssertionException(String message) {
    super(message);
  }
}
