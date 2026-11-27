package com.barezen.content;

import com.barezen.web.Handler;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One disk file or classpath resource, served as one route: bytes, a type, an {@code ETag}, a conditional GET and one
 * byte range on GET.
 *
 * <p><b>One handler per file, and why there is no directory route.</b> {@link com.barezen.Router} answers
 * «method plus raw path, to a handler» and its patterns are a literal segment or exactly {@code {name}} capturing one
 * non-empty segment (ADR-0019 §5, §19) — there is no wildcard, no prefix match and no multi-segment capture, by name.
 * A static file server usually wants {@code /assets/*}, and the honest options were to widen the router or to enumerate
 * the files. This module enumerates: an application that knows its own asset list registers one route per asset, in two
 * lines of its own code, and the router stays the one-question component ADR-0019 decided it is. A deployment with a
 * dynamic asset set is the trigger for the other decision, and it is named in ADR-0051 rather than pre-empted here.
 *
 * <p><b>The {@code ETag} is derived from the bytes, not from the clock.</b> A hash of the content changes exactly when
 * the representation does, which makes it correct across a restart, across machines, and across a filesystem that
 * reports no mtime at all. {@code Last-Modified} is deliberately absent: it would bring a clock into the answer, and a
 * second validator ({@code If-Modified-Since}) whose rules are a coarse-grained overlap with the one field this already
 * answers correctly. ADR-0011's discipline about time being injectable applies here by the same logic — a component that
 * reads {@code Instant.now()} has made its own output untestable.
 *
 * <p><b>The whole file is in memory, which is ADR-0030's decision, not an oversight here.</b> {@link Response} carries a
 * {@code byte[]}, so a served file is read fully before it is answered. That is the right shape for a stylesheet, a
 * favicon and a documentation page, and the wrong shape for a video. The reopening trigger is the one ADR-0030 already
 * names — a consumer with a bounded size and an owner for a post-return writer — and this module does not invent a
 * streaming response to dodge it, because a streaming body would break ADR-0029's framing matrix and the HEAD length
 * rule that depends on it.
 *
 * <p><b>What a missing file answers.</b> A plain 404 whose body is the words {@code Not Found}, naming no path and no
 * reason — the same rule the adapters follow for their own 404 (ADR-0023 §2), applied to a handler. An application that
 * registered a route to a file that has since disappeared gets a truthful answer, not a stack trace, and not a
 * directory listing that leaks the deployment's layout.
 *
 * <p>Instances are immutable and stateless: the path and the stated type are fixed at construction, and everything else
 * is read per request. The handler answers whatever route points at it, so register it on {@code GET} and, if the
 * representation's length should be knowable to a client that asks with {@code HEAD}, on {@code HEAD} too — the
 * adapter, not this class, decides what goes on the wire for a HEAD request (ADR-0028).
 */
public final class StaticContent {

  /** The answer this module gives for a file it cannot read, identical in shape to the adapter's own 404. */
  private static final Response NOT_FOUND = new Response(404,
      Headers.of("Content-Type", "text/plain; charset=utf-8"), "Not Found".getBytes(StandardCharsets.UTF_8));

  private StaticContent() {}

  /**
   * A handler for one file, typed by its extension.
   *
   * @param file the regular file to serve, never null and never a directory
   * @return a handler that reads the file per request, so an edited asset is served fresh without a restart
   * @throws IllegalArgumentException if the path is not an existing regular file — a route to nothing is a misassembly
   *     the application can fix at once rather than a 404 a client has to discover
   */
  public static Handler file(Path file) {
    return file(file, contentTypeOf(file.getFileName().toString()));
  }

  /**
   * A handler for one file with the type stated rather than guessed.
   *
   * <p>The second form exists because an extension is a convention, not a fact: a file served as
   * {@code application/manifest+json} or {@code text/html} from a name ending {@code .json} is the application's call,
   * and this module should not overrule it with a map.
   *
   * @param file the regular file to serve, never null and never a directory
   * @param contentType the value of the {@code Content-Type} field, never null or blank
   * @return a handler that reads the file per request
   * @throws IllegalArgumentException if the path is not an existing regular file, or the type is blank
   */
  public static Handler file(Path file, String contentType) {
    Objects.requireNonNull(file, "the file to serve; a handler with nothing behind it answers 404 forever");
    Objects.requireNonNull(contentType, "the content type; a byte stream with no type is a browser guessing");
    if (contentType.isBlank()) {
      throw new IllegalArgumentException("the content type must not be blank; got \"" + contentType + "\" for " + file
          + ". An empty type is not \"unknown\", it is a field line with nothing in it");
    }
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("no regular file at " + file + "; StaticContent serves one existing file"
          + " per route. A directory would be a listing, and a path that does not exist yet is a 404 the client"
          + " should not have to discover");
    }
    return new FileHandler(file.toAbsolutePath().normalize(), contentType);
  }

  /** A handler for one classpath resource, typed by its name. */
  public static Handler resource(ClassLoader loader, String name) {
    return resource(loader, name, contentTypeOf(name));
  }

  /** A handler for one classpath resource with an explicitly stated content type. */
  public static Handler resource(ClassLoader loader, String name, String contentType) {
    Objects.requireNonNull(loader, "the class loader; without one there is no classpath to read");
    Objects.requireNonNull(name, "the classpath resource name; without one there is no resource to serve");
    Objects.requireNonNull(contentType, "the content type; a byte stream with no type makes the browser guess");
    checkResourceName(name);
    if (contentType.isBlank()) {
      throw new IllegalArgumentException("the content type must not be blank");
    }
    return new ResourceHandler(readResource(loader, name), contentType);
  }

  private static void checkResourceName(String name) {
    if (name.isBlank() || name.startsWith("/") || name.contains("\\")) {
      throw new IllegalArgumentException("a classpath resource name must be relative and use '/' separators");
    }
    for (String segment : name.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException("a classpath resource name must not contain empty, '.' or '..' segments");
      }
    }
  }

  private static byte[] readResource(ClassLoader loader, String name) {
    try (InputStream input = loader.getResourceAsStream(name)) {
      if (input == null) {
        throw new IllegalArgumentException("no classpath resource named " + name);
      }
      return input.readAllBytes();
    } catch (IOException unreadable) {
      throw new IllegalStateException("classpath resource " + name + " could not be read", unreadable);
    }
  }

  /**
   * The type an extension implies, or {@code application/octet-stream} when it implies nothing.
   *
   * <p>A fixed table rather than {@code Files.probeContentType}, because that consults the host platform and two
   * machines would answer the same file with different types — a deployment detail leaking into a wire format. Unknown
   * extensions get the binary type rather than a guess at text, which is the refusal a browser can act on.
   *
   * @param name the file name; only its extension is read, case-insensitively
   * @return the field value, never null
   */
  public static String contentTypeOf(String name) {
    Objects.requireNonNull(name, "the file name to type; with no name there is no extension to read");
    int dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1) {
      return "application/octet-stream";
    }
    String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
    switch (extension) {
      case "html":
      case "htm":
        return "text/html; charset=utf-8";
      case "css":
        return "text/css; charset=utf-8";
      case "js":
      case "mjs":
        return "text/javascript; charset=utf-8";
      case "json":
      case "map":
        return "application/json";
      case "txt":
      case "md":
        return "text/plain; charset=utf-8";
      case "xml":
        return "application/xml";
      case "svg":
        return "image/svg+xml";
      case "png":
        return "image/png";
      case "jpg":
      case "jpeg":
        return "image/jpeg";
      case "gif":
        return "image/gif";
      case "webp":
        return "image/webp";
      case "ico":
        return "image/x-icon";
      case "woff":
        return "font/woff";
      case "woff2":
        return "font/woff2";
      case "wasm":
        return "application/wasm";
      case "pdf":
        return "application/pdf";
      default:
        return "application/octet-stream";
    }
  }

  /**
   * The one handler: read the bytes, derive the tag, then answer a conditional request or one byte range.
   *
   * <p>Package-private because a handler that reaches the disk is not something an application should be subclassing;
   * the configuration it might want (a type, a cache policy) is either an argument here or a handler of its own.
   */
  private static final class FileHandler implements Handler {

    private final Path file;
    private final String contentType;

    FileHandler(Path file, String contentType) {
      this.file = file;
      this.contentType = contentType;
    }

    @Override
    public Response handle(Request request, java.util.Map<String, String> parameters) {
      byte[] content;
      try {
        content = Files.readAllBytes(file);
      } catch (IOException unreadable) {
        // The file went away, or the process lost the right to read it. The client's answer is a 404 either way: a 500
        // would blame the server for a request that names nothing it can serve, and the path belongs in the log the
        // application owns, not on the socket (ADR-0023 §2).
        return NOT_FOUND;
      }
      return respond(content, contentType, request);
    }
  }

  private static final class ResourceHandler implements Handler {

    private final byte[] content;
    private final String contentType;

    ResourceHandler(byte[] content, String contentType) {
      this.content = content;
      this.contentType = contentType;
    }

    @Override
    public Response handle(Request request, java.util.Map<String, String> parameters) {
      return respond(content, contentType, request);
    }
  }

  private static Response respond(byte[] content, String contentType, Request request) {
    String etag = etagOf(content);
    if (matches(request.headers().values("If-None-Match"), etag)) {
      // No body, and no length stated: ADR-0029 D1 keeps a 304 free of Content-Length, and Response refuses a body
      // for the status anyway. The ETag travels because a validator has to survive the answer it validated.
      return new Response(304, Headers.of("ETag", etag), null);
    }
    Headers representation = Headers.of("Content-Type", contentType)
        .with("ETag", etag)
        .with("Accept-Ranges", "bytes");
    List<String> ranges = request.headers().values("Range");
    List<String> ifRanges = request.headers().values("If-Range");
    if (request.method() != HttpMethod.GET || ranges.size() > 1 || ifRanges.size() > 1
        || (ifRanges.size() == 1 && !ifRanges.get(0).equals(etag))) {
      return new Response(200, representation, content);
    }
    ByteRange range = parseRange(ranges.isEmpty() ? null : ranges.get(0), content.length);
    if (range == null) {
      return new Response(200, representation, content);
    }
    if (range.first() < 0) {
      return new Response(416, representation.with("Content-Range", "bytes */" + content.length), new byte[0]);
    }
    Headers rangeHeaders = representation.with("Content-Range",
        "bytes " + range.first() + "-" + range.last() + "/" + content.length);
    return new Response(206, rangeHeaders,
        Arrays.copyOfRange(content, (int) range.first(), (int) range.last() + 1));
  }

  private static final ByteRange UNSATISFIABLE = new ByteRange(-1, -1);

  private record ByteRange(long first, long last) {}

  private static ByteRange parseRange(String value, int size) {
    if (value == null || value.indexOf(',') >= 0) {
      return null;
    }
    int equals = value.indexOf('=');
    if (equals < 0 || !value.substring(0, equals).trim().equalsIgnoreCase("bytes")) {
      return null;
    }
    String specification = value.substring(equals + 1).trim();
    int dash = specification.indexOf('-');
    if (dash < 0 || specification.indexOf('-', dash + 1) >= 0) {
      return null;
    }
    String firstText = specification.substring(0, dash).trim();
    String lastText = specification.substring(dash + 1).trim();
    if (firstText.isEmpty()) {
      long suffix = position(lastText);
      if (suffix < 0) {
        return null;
      }
      if (size == 0 || suffix == 0) {
        return UNSATISFIABLE;
      }
      return new ByteRange(size - Math.min(suffix, size), size - 1);
    }
    long first = position(firstText);
    if (first < 0) {
      return null;
    }
    if (lastText.isEmpty()) {
      if (first >= size) {
        return UNSATISFIABLE;
      }
      return new ByteRange(first, size - 1L);
    }
    long last = position(lastText);
    if (last < 0 || last < first) {
      return null;
    }
    if (first >= size) {
      return UNSATISFIABLE;
    }
    return new ByteRange(first, Math.min(last, size - 1L));
  }

  private static long position(String text) {
    if (text.isEmpty()) {
      return -1;
    }
    for (int index = 0; index < text.length(); index++) {
      if (text.charAt(index) < '0' || text.charAt(index) > '9') {
        return -1;
      }
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException overflow) {
      return -1;
    }
  }

  /**
   * A strong entity tag over the bytes: the first eight bytes of the content's SHA-256, in quotes.
   *
   * <p>Truncated because a tag is compared, not read, and 16 hex characters is 2^64 values — collision-free for the set
   * of files one deployment serves, and short enough that a test can name it. SHA-256 is in the JDK, so this costs no
   * dependency (ADR-0001).
   */
  private static String etagOf(byte[] content) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException absent) {
      // SHA-256 is in every JDK this project supports; reaching here is an environment failure, not a client's.
      throw new IllegalStateException("this JVM has no SHA-256, which every JDK 17 and later provides", absent);
    }
    byte[] hash = digest.digest(content);
    StringBuilder tag = new StringBuilder(18).append('"');
    for (int index = 0; index < 8; index++) {
      tag.append(String.format("%02x", hash[index]));
    }
    return tag.append('"').toString();
  }

  /**
   * Whether a client's {@code If-None-Match} covers this tag.
   *
   * <p>Handles the three shapes the field takes (RFC 9110 §8.8.3): the wildcard, a comma-separated list, and a weak tag
   * on either side — a weak comparison is what a conditional GET needs, since the representation is identical even if
   * the validator was recorded weakly. Each candidate must be a syntactically valid entity tag; an unquoted or
   * unterminated value is ignored rather than allowed to manufacture a 304.
   */
  private static boolean matches(List<String> validators, String etag) {
    for (String validator : validators) {
      if (validator.isBlank()) {
        continue;
      }
      for (String candidate : candidates(validator)) {
        String trimmed = candidate.trim();
        if (trimmed.equals("*") || etag.equals(opaqueTag(trimmed))) {
          return true;
        }
      }
    }
    return false;
  }

  private static List<String> candidates(String validators) {
    List<String> candidates = new ArrayList<>();
    boolean quoted = false;
    int start = 0;
    for (int index = 0; index < validators.length(); index++) {
      char character = validators.charAt(index);
      if (character == '"') {
        quoted = !quoted;
      } else if (character == ',' && !quoted) {
        candidates.add(validators.substring(start, index));
        start = index + 1;
      }
    }
    candidates.add(validators.substring(start));
    return candidates;
  }

  private static String opaqueTag(String value) {
    int start = value.startsWith("W/") ? 2 : 0;
    if (value.length() - start < 2 || value.charAt(start) != '"'
        || value.charAt(value.length() - 1) != '"') {
      return null;
    }
    for (int index = start + 1; index < value.length() - 1; index++) {
      char character = value.charAt(index);
      if (character == '"' || character <= 0x20 || character == 0x7f) {
        return null;
      }
    }
    return value.substring(start);
  }
}
