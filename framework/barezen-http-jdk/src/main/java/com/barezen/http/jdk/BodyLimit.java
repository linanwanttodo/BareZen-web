package com.barezen.http.jdk;

import com.barezen.core.config.Configuration;
import com.barezen.core.config.ConfigurationKey;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * The request-body limit: one number, resolved once from a configuration snapshot, enforced once, in the bounded read
 * of {@link JdkExchangeBridge}.
 *
 * <p>The key lives here rather than in {@code com.barezen.web}, which keeps no compile dependency on core's
 * {@code Configuration} (ADR-0018 §12): an HTTP limit is HTTP, and the module that talks HTTP to a JDK server is the
 * module that owns it. The pattern is {@code RuntimeConfig.THREADS} — a {@code barezen.*} key declared beside the
 * component that consumes it and resolved through the existing configuration layer rather than a second one (ADR-0013,
 * ADR-0031 §Decision).
 *
 * <p>Package private on purpose, like the bridge and the dispatcher: {@link #from(Configuration)} is the seam the
 * adapter's constructor calls, and the resolved number then travels to the request path as an ordinary object
 * reference. Nothing on the public surface names this type (ADR-0031 §Compatibility).
 */
record BodyLimit(long bytes) {

  /**
   * {@code barezen.http.jdk.maxRequestBodyBytes} — the ceiling on the bytes one request body may carry, defaulting
   * to one mebibyte.
   *
   * <p>{@code Long} rather than {@code Integer} because the unit is bytes and a byte count that fits in an
   * {@code int} caps a deployment at 2 GiB by its type rather than by its policy. The value is the largest accepted
   * body: a body of exactly this size passes. Zero is legal and means "no request body at all"; a negative limit
   * would refuse every request including a bodiless one, which is a misassembly rather than a policy.
   */
  static final ConfigurationKey<Long> MAX_REQUEST_BODY_BYTES =
      ConfigurationKey.of("barezen.http.jdk.maxRequestBodyBytes", Long.class, 1024L * 1024L);

  /**
   * More content than the limit, claimed or arrived. Unchecked, like {@link Request}'s refusals: the client sent it
   * and nothing about reading it failed, so it is not an {@code IOException} — and because it is unchecked, the
   * request path's existing {@code catch (IOException)} cannot swallow it into the wrong status; the dispatcher
   * catches the refusal by name and answers 413 (ADR-0031 §Decision 3).
   */
  static final class Exceeded extends RuntimeException {

    private static final long serialVersionUID = 1L;

    Exceeded(long limit, String framing) {
      // No stack: the refusal happens inside a counting loop whose call chain is two frames long, and a limit
      // tripped by a client is not a server-side fault to diagnose from a trace.
      super("the request content exceeds the limit of " + limit + " bytes (" + framing + ")", null, false, false);
    }
  }

  BodyLimit {
    if (bytes < 0) {
      throw new IllegalArgumentException("the request-body limit must not be negative; got " + bytes
          + ". 0 is a policy — no request body is accepted at all — and a negative number accepts nothing,"
          + " including a bodiless request, which is a misassembly (ADR-0031 §Decision)");
    }
  }

  /**
   * @return the limit an adapter built without a configuration snapshot runs on — the same value
   *     {@code Configuration.get} falls back to for a key no source carries, stated once here so that
   *     "unconfigured" and "unbounded" cannot quietly become the same word (ADR-0031 §Decision 2)
   */
  static BodyLimit unconfigured() {
    return new BodyLimit(MAX_REQUEST_BODY_BYTES.defaultValue());
  }

  /**
   * Resolve the limit from a configuration snapshot.
   *
   * <p>A bad value fails here, at assembly, naming the key, the raw value and the source — {@code Configuration.get}'s
   * rule (ADR-0013): a component quietly running on a fallback looks healthy while doing the wrong thing, and a limit
   * that silently fell back to the default is exactly that. A snapshot that resolves nothing takes the key's own
   * default, so an unconfigured server runs on the secure value rather than on no limit at all (ADR-0031 §Decision).
   *
   * @param configuration the resolved snapshot, never null
   * @return the limit to enforce, never null
   * @throws com.barezen.core.error.ConfigurationException if a value is present but not a valid number
   */
  static BodyLimit from(Configuration configuration) {
    Objects.requireNonNull(configuration,
        "the configuration to read the body limit from; with none there is no value to resolve");
    // get() already answers a keyless snapshot with the key's default; orElseThrow states that rather than trusting it.
    return new BodyLimit(configuration.get(MAX_REQUEST_BODY_BYTES).orElseThrow(() -> new IllegalStateException(
        "configuration key '" + MAX_REQUEST_BODY_BYTES.name() + "' resolved to nothing, which cannot happen while it"
            + " declares a default")));
  }

  /**
   * The counting half of the read rule; {@link JdkExchangeBridge} adds the other half, refusing a declared
   * {@code Content-Length} above the limit before the counting read, because a claim is the client's own statement
   * about bytes it has not sent (ADR-0031 §Decision 4).
   *
   * <p>Refusal throws with the stream still open and the surplus unread, and that is the whole connection story: the
   * caller answers 413 and closes the exchange, and the JDK finishes an exchange whose body was not read to its end by
   * closing the connection — the leftover drain ({@code LeftOverInputStream.java:65-69}, bounded by
   * {@code sun.net.httpserver.drainAmount}, 64 KiB by default) makes forward progress only while bytes already sit in
   * the parser's buffer or arrive, and the dispatcher's own rule ({@code ServerImpl.java:455-462}) marks the
   * connection for closing at not-at-EOF. So the refused request's remaining bytes are never parsed as the next
   * request, and refusing costs the limit plus at most one drain of socket backlog — never the unbounded buffer
   * {@code readAllBytes()} was (ADR-0031 §Investigation).
   *
   * <p>The buffer grows into what it read and shrinks to what it keeps, so a 1 KiB body under a 1 MiB limit costs
   * 1 KiB: the bound is a read bound, not a pre-committed allocation. Every stream the JDK hands
   * {@code getRequestBody()} ({@code FixedLengthInputStream}, {@code ChunkedInputStream}) returns {@code -1} at its
   * declared end, throws on a connection that stopped short of it, or advances the count — a short read only ever
   * returns fewer bytes than the chunk or the remainder allows — so no branch of this loop spins.
   *
   * @param body the request-body stream
   * @param limit the largest accepted body in bytes
   * @return the body's bytes
   * @throws Exceeded if the bytes passed the limit
   * @throws IOException if the body stopped arriving — the client's failure, answered as a truncated request
   *     already is (ADR-0023 §6)
   */
  static byte[] readBounded(InputStream body, long limit) throws IOException {
    // `limit` is always a validated BodyLimit's own value, and the record's constructor already refused a negative
    // one, so capacity is never below 1, buf is never zero-length, and read() always asks the stream for something
    // (a limit of 0 reads exactly one byte to decide).
    // Read one past the limit: a read that lands exactly on `limit` cannot tell "body == limit" from "body == limit+1"
    // until it asks for more, and asking for more is what this capacity is for. The largest long has no successor, so
    // its probe capacity saturates instead of wrapping negative and silently returning an empty body.
    final long capacity = limit == Long.MAX_VALUE ? limit : limit + 1;
    byte[] buf = new byte[(int) Math.min(capacity, 8192)];
    int size = 0;
    while (size < capacity) {
      // Room in the buffer, not room to the bound: the JDK's own chunk and fixed-length streams clamp a read to the
      // current chunk or the declared remainder, but they hand the offset and length straight to a
      // BufferedInputStream, which bounds-checks them — asking for `buf.length` bytes at offset `size` writes past the
      // array and reaches the caller as an IndexOutOfBoundsException instead of as bytes (measured: a 20 000-byte
      // chunked body under the 1 MiB default closed the connection with no answer, 8 000 bytes answered normally, and
      // the boundary was exactly the first buffer's 8 192). `capacity - size` is still the outer bound; the
      // grow step below keeps `buf.length - size` at least 1, so this never asks for zero bytes.
      int room = (int) Math.min(capacity - size, buf.length - size);
      int read = body.read(buf, size, room);
      if (read < 0) {
        break;
      }
      size += read;
      if (size == buf.length && size < capacity) {
        // Arrays.copyOf rather than System.arraycopy: this module reads no java.lang.System at all, which is how
        // the ArchUnit rule "an adapter is configured by the arguments it is constructed with" stays exact —
        // the growth copies min(buf.length, grown) bytes, and grown never is below size, so the read prefix survives.
        buf = Arrays.copyOf(buf, (int) Math.min(capacity, Math.max(buf.length * 2L, size + 1L)));
      }
    }
    // size > limit implies size == capacity: the loop never passes capacity, and a read never returns more than `room`.
    if (size > limit) {
      throw new Exceeded(limit, "content past the bound of a body whose length was not declared");
    }
    return Arrays.copyOf(buf, size);
  }
}
