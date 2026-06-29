package com.barezen.http.jdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A client that speaks HTTP/1.1 on a socket, for tests that have to see what actually left the server.
 *
 * <p>{@code java.net.http.HttpClient} was the obvious candidate and is the wrong tool for this phase's claims. It
 * folds repeated response fields into one entry, it normalizes field-name case, it refuses some method tokens, and it
 * will not send a request line this module needs to send on purpose. Every test below that asserts something about a
 * header's spelling, its order, or the existence of two {@code Set-Cookie} lines is asserting something about the wire,
 * so the wire is what this reads. The output is the status line, the field lines in arrival order and the body —
 * nothing interpreted.
 *
 * <p>One connection per request, closed by {@code Connection: close}: a keep-alive connection would make every test
 * responsible for framing its own read, and the adapter is not what those tests are about. Timeouts are set on connect
 * and on read so a hung server fails the suite with a message rather than hanging the build.
 *
 * <p>Test scope, and package private: this is a fixture, not an API.
 */
final class RawHttp {

  /** Milliseconds a connect or a read may take before the test is abandoned rather than left waiting. */
  private static final int TIMEOUT_MILLIS = 5_000;

  private RawHttp() {}

  /**
   * What came back: the status line, the field lines in the order they arrived, and the body.
   *
   * @param status the numeric status, or {@code 0} when the connection closed before a status line arrived — which is
   *     what a client that hung up during a handler observes, and a fact some tests assert
   * @param statusLine the first line verbatim, for a test that asserts on the reason phrase too
   * @param fieldLines the header fields verbatim, one entry per line, in arrival order, name case as sent
   * @param body the body bytes read one-to-one as ISO-8859-1 characters, so a byte never disappears; empty when there
   *     was none
   */
  record Response(int status, String statusLine, List<String> fieldLines, String body) {

    /**
     * The body as the bytes it carries, decoded as UTF-8.
     *
     * <p>{@link #body()} holds the wire bytes mapped one-to-one through ISO-8859-1 — which is why a header line can be
     * compared character for character without a charset guess. A test that asserts text rather than framing goes
     * through here instead, because a body of {@code text/plain; charset=utf-8} arrives as bytes and the mapping back is
     * what the reader would do.
     */
    String bodyUtf8() {
      return new String(body.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    /**
     * @return the values of one field, matched case-insensitively because the JDK canonicalizes names on the way out,
     *     one entry per line so that two {@code Set-Cookie}s give two values rather than one folded value
     */
    List<String> valuesOf(String name) {
      String prefix = name.toLowerCase(java.util.Locale.ROOT) + ":";
      List<String> values = new java.util.ArrayList<>();
      for (String line : fieldLines) {
        if (line.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
          // The value is taken from the original line, never from the lowercased comparison above: a test that asserts
          // a token stays "Bearer XYZ" would otherwise pass against text the fixture rewrote.
          values.add(line.substring(line.indexOf(':') + 1).trim());
        }
      }
      return List.copyOf(values);
    }

    /** @return the first value of one field, or null when the field was never sent */
    String firstOf(String name) {
      List<String> values = valuesOf(name);
      return values.isEmpty() ? null : values.get(0);
    }

    /** @return how many lines carried this field, which is how a duplicated header is counted without merging it */
    int countOf(String name) {
      return valuesOf(name).size();
    }
  }

  /**
   * Send one request and read the whole answer.
   *
   * @param port the port the adapter reported after starting
   * @param method the request method token, sent verbatim — including one no HTTP client would offer
   * @param target the request target, sent verbatim: no encoding, no normalization, no {@code URI} involved
   * @param extraHeaders field lines to add after {@code Host}, each as {@code "Name: value"}, or empty
   * @param body the request body, or null for none; a non-null body is sent with an exact {@code Content-Length}
   * @return the answer, never null
   * @throws IOException if the socket could not be opened or read
   */
  static Response request(int port, String method, String target, List<String> extraHeaders, String body)
      throws IOException {
    StringBuilder message = new StringBuilder(method).append(' ').append(target).append(" HTTP/1.1\r\n")
        .append("Host: 127.0.0.1:").append(port).append("\r\n")
        .append("Connection: close\r\n");
    for (String field : extraHeaders) {
      message.append(field).append("\r\n");
    }
    byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    if (body != null) {
      message.append("Content-Length: ").append(payload.length).append("\r\n");
    }
    message.append("\r\n");
    return exchange(port, message.toString().getBytes(StandardCharsets.US_ASCII), payload);
  }

  /**
   * Send a request whose body deliberately stops early, which is how a truncated request is tested.
   *
   * <p>The write side is shut down after the short body, so the server meets the end of the stream in the middle of the
   * declared body — the condition rather than a simulation of it. Without {@code shutdownOutput()} the server would
   * simply keep waiting for bytes that were never going to arrive, which is a different test (probed: the read then
   * times out instead of the body failing).
   */
  static Response requestWithTruncatedBody(int port, String method, String target, int declaredLength,
      int actualLength) throws IOException {
    String headers = method + " " + target + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n"
        + "Content-Length: " + declaredLength + "\r\n\r\n";
    byte[] payload = new byte[actualLength];
    java.util.Arrays.fill(payload, (byte) 'x');
    try (Socket socket = connected(port)) {
      OutputStream out = socket.getOutputStream();
      out.write(headers.getBytes(StandardCharsets.US_ASCII));
      out.write(payload);
      out.flush();
      socket.shutdownOutput();
      return read(socket);
    }
  }

  /**
   * Send a request, read the first bytes of the answer, then hang up mid-response.
   *
   * <p>{@code SO_LINGER} of zero turns the close into a TCP reset rather than an orderly FIN, which is what a client that
   * loses its network does, and what makes the server's next write fail. The bytes that did arrive are returned so the
   * test can show the response had started — a status line that reached the client is the definition of a committed
   * response (ADR-0023 §6).
   *
   * @param port the port the adapter reported
   * @param target the path to ask for, expected to answer with a body large enough to still be writing when the reset lands
   * @param bytesToRead how much of the answer to take before hanging up
   * @return the beginning of the response, as text
   */
  static String readThenReset(int port, String target, int bytesToRead) throws IOException {
    Socket socket = connected(port);
    try {
      String request = "POST " + target + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n"
          + "Content-Length: 0\r\n\r\n";
      OutputStream out = socket.getOutputStream();
      out.write(request.getBytes(StandardCharsets.US_ASCII));
      out.flush();
      byte[] head = new byte[bytesToRead];
      int read = socket.getInputStream().read(head);
      return new String(head, 0, Math.max(read, 0), StandardCharsets.ISO_8859_1);
    } finally {
      socket.setSoLinger(true, 0);
      socket.close();
    }
  }

  /**
   * Send an exact byte sequence as the request, which is how a malformed request line and a chunked body are tested.
   *
   * <p>{@link #request} builds a well-formed message; the tests that need an ill-formed one cannot be expressed through
   * it, and the point of those tests is that the JDK's parser — not this adapter — decides the answer (ADR-0023 §7).
   *
   * @param message the bytes to write, verbatim
   * @return the answer
   */
  static Response requestRaw(int port, String message) throws IOException {
    try (Socket socket = connected(port)) {
      socket.getOutputStream().write(message.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      return read(socket);
    }
  }

  private static Response exchange(int port, byte[] headers, byte[] body) throws IOException {
    try (Socket socket = connected(port)) {
      OutputStream out = socket.getOutputStream();
      out.write(headers);
      out.write(body);
      out.flush();
      return read(socket);
    }
  }

  private static Socket connected(int port) throws IOException {
    Socket socket = new Socket();
    socket.connect(new InetSocketAddress("127.0.0.1", port), TIMEOUT_MILLIS);
    socket.setSoTimeout(TIMEOUT_MILLIS);
    return socket;
  }

  private static Response read(Socket socket) throws IOException {
    ByteArrayOutputStream received = new ByteArrayOutputStream();
    InputStream in = socket.getInputStream();
    byte[] buffer = new byte[4096];
    int read;
    while ((read = in.read(buffer)) >= 0) {
      received.write(buffer, 0, read);
    }
    return parse(received.toString(StandardCharsets.ISO_8859_1));
  }

  /**
   * Split a message at the blank line.
   *
   * <p>The header block is read as ISO-8859-1 because that is the charset HTTP/1.1 field lines are defined in, and the
   * body is then decoded from the same bytes as UTF-8; a test that compares a body character for character is
   * comparing what the adapter wrote, not what a permissive parser made of it.
   */
  private static Response parse(String message) {
    int endOfHeaders = message.indexOf("\r\n\r\n");
    String head = endOfHeaders < 0 ? message : message.substring(0, endOfHeaders);
    String body = endOfHeaders < 0 ? "" : message.substring(endOfHeaders + 4);
    String[] lines = head.split("\r\n");
    if (lines.length == 0 || !lines[0].startsWith("HTTP/1.1 ") && !lines[0].startsWith("HTTP/1.0 ")) {
      return new Response(0, "", List.of(), body);
    }
    List<String> fields = List.of(lines).subList(1, lines.length);
    return new Response(Integer.parseInt(lines[0].substring("HTTP/1.1 ".length()).split(" ")[0]), lines[0], fields,
        body);
  }
}
