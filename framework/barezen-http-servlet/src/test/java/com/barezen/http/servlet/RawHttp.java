package com.barezen.http.servlet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A client that speaks HTTP/1.1 on a socket, for tests that have to see what actually left the container.
 *
 * <p>The same fixture {@code barezen-http-jdk} uses, and for the same reasons stated in ADR-0022 §9:
 * {@code java.net.http.HttpClient} folds repeated response fields, normalizes field-name case, refuses some method
 * tokens, and will not send the request lines this module needs to send on purpose. A claim about a {@code head} token,
 * two {@code Set-Cookie} lines, or the number after {@code Content-Length} is a claim about bytes, so it is tested
 * against bytes.
 *
 * <p>Duplicated rather than shared because a test fixture in one module's {@code src/test} is not an artifact another
 * module can depend on without a test-jar, and shipping a test-jar of socket helpers would put test code on a consumer's
 * classpath — the boundary ADR-0044 exists to keep.
 */
final class RawHttp {

  private static final int TIMEOUT_MILLIS = 5_000;

  private RawHttp() {}

  /** The status line, the field lines in arrival order, and the body — nothing interpreted. */
  record Response(int status, String statusLine, List<String> fieldLines, String body) {

    String bodyUtf8() {
      return new String(body.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    List<String> valuesOf(String name) {
      String prefix = name.toLowerCase(Locale.ROOT) + ":";
      List<String> values = new ArrayList<>();
      for (String line : fieldLines) {
        if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
          values.add(line.substring(line.indexOf(':') + 1).trim());
        }
      }
      return List.copyOf(values);
    }

    String firstOf(String name) {
      List<String> values = valuesOf(name);
      return values.isEmpty() ? null : values.get(0);
    }

    int countOf(String name) {
      return valuesOf(name).size();
    }

    /** @return every field line that mentions the text, which is how one assertion can look for a leak anywhere */
    List<String> linesContaining(String text) {
      return fieldLines.stream().filter(line -> line.contains(text)).toList();
    }

    boolean hasHeader(String name) {
      return countOf(name) > 0;
    }
  }

  /**
   * Send one well-formed request and read the whole answer.
   *
   * @param port the port the container bound
   * @param method the request method token, sent verbatim
   * @param target the request target, sent verbatim: no encoding, no normalization, no {@code URI} involved
   * @param extraHeaders field lines to add after {@code Host}, each as {@code "Name: value"}, or empty
   * @param body the request body, or null for none; a non-null body is sent with an exact {@code Content-Length}
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
    try (Socket socket = connected(port)) {
      OutputStream out = socket.getOutputStream();
      out.write(message.toString().getBytes(StandardCharsets.US_ASCII));
      out.write(payload);
      out.flush();
      return read(socket);
    }
  }

  /** Send an exact byte sequence, which is how a chunked body and an odd request line are tested. */
  static Response requestRaw(int port, String message) throws IOException {
    try (Socket socket = connected(port)) {
      socket.getOutputStream().write(message.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      return read(socket);
    }
  }

  /** Send a request whose body stops early, so the container meets an end of stream inside the declared body. */
  static Response requestWithTruncatedBody(int port, String method, String target, int declaredLength,
      int actualLength) throws IOException {
    String headers = method + " " + target + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n"
        + "Content-Length: " + declaredLength + "\r\n\r\n";
    byte[] payload = new byte[actualLength];
    Arrays.fill(payload, (byte) 'x');
    try (Socket socket = connected(port)) {
      OutputStream out = socket.getOutputStream();
      out.write(headers.getBytes(StandardCharsets.US_ASCII));
      out.write(payload);
      out.flush();
      socket.shutdownOutput();
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

  private static Response parse(String message) {
    int endOfHeaders = message.indexOf("\r\n\r\n");
    String head = endOfHeaders < 0 ? message : message.substring(0, endOfHeaders);
    String body = endOfHeaders < 0 ? "" : message.substring(endOfHeaders + 4);
    String[] lines = head.split("\r\n");
    if (lines.length == 0 || (!lines[0].startsWith("HTTP/1.1 ") && !lines[0].startsWith("HTTP/1.0 "))) {
      return new Response(0, "", List.of(), body);
    }
    int status = Integer.parseInt(lines[0].substring(lines[0].indexOf(' ') + 1).split(" ")[0]);
    return new Response(status, lines[0], List.of(lines).subList(1, lines.length), body);
  }
}
