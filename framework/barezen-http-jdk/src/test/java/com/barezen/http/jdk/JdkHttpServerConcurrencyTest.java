package com.barezen.http.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Response;
import com.barezen.web.Router;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Concurrency through a real server: 16 threads, 100 requests each, over three routes, and a per-request answer that
 * could only be right if nothing leaked (ADR-0021 §1, ADR-0019 §13).
 *
 * <p>This is not a router test. The router's thread safety is already proven in {@code barezen-web} with a direct
 * {@code match()} call; what only a socket test can show is that the adapter's own state — the exchange, the thread
 * provider's executor, the bridge's byte buffers, the dispatcher's method-local variables — does not carry a value from
 * one request to another. A shared mutable field somewhere in that chain is invisible to a single-threaded test and
 * produces a wrong answer here.
 *
 * <p>Each request asks for {@code /users/<n>} where {@code n} is the thread's own index modulo 3, and the handler echoes
 * the captured parameter plus a per-request correlation value derived from it. A leak of either the parameter map or the
 * response body shows up as a mismatched pair.
 */
class JdkHttpServerConcurrencyTest {

  private static final int THREADS = 16;
  private static final int REQUESTS_PER_THREAD = 100;

  @Test
  @DisplayName("1600 concurrent requests each get their own parameter, body and header back")
  void nothingLeavesOneRequestIntoAnother() throws Exception {
    Router router = Router.builder()
        // The handler reads its parameters and its own header value only, and writes both into the body. Nothing is
        // stored on any shared object, which is the property under test rather than an assumption of it.
        .route(HttpMethod.GET, "/users/{id}", (request, parameters) -> {
          String id = parameters.get("id");
          String marker = request.headers().first("X-Marker").orElse("<none>");
          String body = id + "|" + marker;
          return new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8")
              .with("X-Echo-Id", id), body.getBytes(StandardCharsets.UTF_8));
        })
        .route(HttpMethod.POST, "/echo/{id}", (request, parameters) -> {
          String body = parameters.get("id") + "|" + new String(request.body(), StandardCharsets.UTF_8);
          return new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8"),
              body.getBytes(StandardCharsets.UTF_8));
        })
        .build();

    List<String> failures = new ArrayList<>();
    try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router)) {
      adapter.start();
      int port = adapter.port().orElseThrow();

      ExecutorService clients = Executors.newFixedThreadPool(THREADS);
      List<Future<Integer> > futures = new ArrayList<>();
      for (int thread = 0; thread < THREADS; thread++) {
        final int index = thread;
        futures.add(clients.submit(() -> {
          int correct = 0;
          for (int request = 0; request < REQUESTS_PER_THREAD; request++) {
            String id = String.valueOf(index % 3 + 1);
            String marker = "t" + index + "r" + request;
            try {
              RawHttp.Response answer = RawHttp.request(port, "GET", "/users/" + id,
                  List.of("X-Marker: " + marker), null);
              if (answer.status() == 200 && answer.body().equals(id + "|" + marker)
                  && id.equals(answer.firstOf("X-Echo-Id"))) {
                correct++;
              } else {
                record(failures, "GET /users/" + id + " -> " + answer.status() + " [" + answer.body() + "]");
              }

              RawHttp.Response echoed = RawHttp.request(port, "POST", "/echo/" + id,
                  List.of(), marker + marker + marker + marker);
              if (echoed.status() == 200 && echoed.body().equals(id + "|" + marker + marker + marker + marker)) {
                correct++;
              } else {
                record(failures, "POST /echo/" + id + " -> " + echoed.status() + " [" + echoed.body() + "]");
              }
            } catch (IOException arrived) {
              record(failures, "socket failure for " + marker + ": " + arrived);
            }
          }
          return correct;
        }));
      }

      int answered = 0;
      for (Future<Integer> future : futures) {
        answered += future.get(120, TimeUnit.SECONDS);
      }
      clients.shutdown();
      assertThat(clients.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

      assertThat(failures).isEmpty();
      assertThat(answered).isEqualTo(THREADS * REQUESTS_PER_THREAD * 2);
      assertThat(adapter.isRunning()).isTrue();
    }
  }

  @Test
  @DisplayName("a server under concurrent load still answers a 404 and a 500 per request")
  void statusCodesStayAttachedToTheirOwnRequests() throws Exception {
    Router router = Router.builder()
        .route(HttpMethod.GET, "/users/{id}", (request, parameters) -> {
          if (parameters.get("id").equals("boom")) {
            throw new IllegalStateException("only this request");
          }
          return new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8"),
              parameters.get("id").getBytes(StandardCharsets.UTF_8));
        })
        .build();

    try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router)) {
      adapter.start();
      int port = adapter.port().orElseThrow();

      ExecutorService clients = Executors.newFixedThreadPool(THREADS);
      List<Future<Integer>> futures = new ArrayList<>();
      for (int thread = 0; thread < THREADS; thread++) {
        final int index = thread;
        futures.add(clients.submit(() -> {
          int matched = 0;
          for (int request = 0; request < REQUESTS_PER_THREAD / 4; request++) {
            try {
              // Same route, three different outcomes: a parameter, a 500, a 404. If a status or a body were shared
              // across in-flight exchanges, one of these three reads would be wrong.
              String id = String.valueOf(index % 3 + 1);
              if (RawHttp.request(port, "GET", "/users/" + id, List.of(), null).body().equals(id)) {
                matched++;
              }
              if (RawHttp.request(port, "GET", "/users/boom", List.of(), null).status() == 500) {
                matched++;
              }
              if (RawHttp.request(port, "GET", "/missing/" + id, List.of(), null).status() == 404) {
                matched++;
              }
            } catch (IOException arrived) {
              return matched;
            }
          }
          return matched;
        }));
      }

      int matched = 0;
      for (Future<Integer> future : futures) {
        matched += future.get(120, TimeUnit.SECONDS);
      }
      clients.shutdown();
      assertThat(clients.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

      assertThat(matched).isEqualTo(THREADS * (REQUESTS_PER_THREAD / 4) * 3);
    }
  }

  /**
   * The response shapes this phase changed, interleaved across threads: 8 threads, 50 rounds each, on one server.
   *
   * <p>The shapes are the ones ADR-0027 gives each a different wire footprint: a {@code 200} with bytes, a {@code 200}
   * with none, a {@code 204}, a {@code 304}, a {@code HEAD} that states a length without bytes, a {@code 405} with
   * {@code Allow}, a {@code 404}, and an {@code OPTIONS *} that this server answers before a handler exists. Each shape
   * is asserted by its own field lines and body, which is what makes the test a pollution check rather than a load test:
   * a framing decision taken on one exchange and remembered by the next would show up as a length on the 204, bytes on
   * the HEAD, or an {@code Allow} that arrived on a 404.
   *
   * <p>The counters close the phase's §19 requirements. No resource handler may run for the asterisk — that is the
   * pollution rule, tested under concurrency rather than one request at a time — and the GET and HEAD handlers must each
   * be called exactly as often as their own requests, because "HEAD and GET share mutable response state" is only
   * refutable by counting both of them separately.
   */
  @Test
  @DisplayName("8 threads x 50 rounds of every response shape, and no shape leaks into another")
  void mixedShapesUnderConcurrencyStayOnTheirOwnRequests() throws Exception {
    int threads = 8;
    int rounds = 50;
    java.util.concurrent.atomic.AtomicInteger parametrizedCalls = new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger getCalls = new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger headCalls = new java.util.concurrent.atomic.AtomicInteger();
    byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
    Router router = Router.builder()
        .route(HttpMethod.GET, "/users/{id}", (request, parameters) -> {
          parametrizedCalls.incrementAndGet();
          String id = parameters.get("id");
          if (id.equals("gone")) {
            return new Response(204, Headers.empty(), null);
          }
          if (id.equals("stale")) {
            return new Response(304, Headers.of("ETag", "\"7\""), null);
          }
          if (id.equals("nothing")) {
            return new Response(200, Headers.of("Content-Type", "text/plain"), new byte[0]);
          }
          return new Response(200, Headers.of("Content-Type", "text/plain"),
              (id + "|" + request.headers().first("X-Marker").orElse("<none>")).getBytes(StandardCharsets.UTF_8));
        })
        .route(HttpMethod.GET, "/doc", (request, parameters) -> {
          getCalls.incrementAndGet();
          return new Response(200, Headers.of("Content-Type", "text/plain"), body);
        })
        .route(HttpMethod.HEAD, "/doc", (request, parameters) -> {
          headCalls.incrementAndGet();
          return new Response(200, Headers.of("Content-Type", "text/plain"), body);
        })
        .route(HttpMethod.POST, "/doc", (request, parameters) -> {
          throw new AssertionError("the 405 for /doc must never reach a handler");
        })
        .build();

    List<String> failures = new ArrayList<>();
    try (JdkHttpServerAdapter adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", 0), router)) {
      adapter.start();
      int port = adapter.port().orElseThrow();

      ExecutorService clients = Executors.newFixedThreadPool(threads);
      List<Future<Integer>> futures = new ArrayList<>();
      for (int thread = 0; thread < threads; thread++) {
        final int index = thread;
        futures.add(clients.submit(() -> {
          int correct = 0;
          for (int round = 0; round < rounds; round++) {
            String marker = "t" + index + "r" + round;
            try {
              // 1. a body, its own parameter, its own marker
              RawHttp.Response named = RawHttp.request(port, "GET", "/users/42", List.of("X-Marker: " + marker), null);
              if (named.status() == 200 && named.bodyUtf8().equals("42|" + marker)
                  && named.valuesOf("Content-Length").equals(List.of(Integer.toString(marker.length() + 3)))
                  && named.valuesOf("Allow").isEmpty()) {
                correct++;
              } else {
                record(failures, "named: " + named.status() + " [" + named.body() + "] " + named.fieldLines());
              }

              // 2. a 200 with no bytes: a zero length and no transfer-encoding
              RawHttp.Response empty = RawHttp.request(port, "GET", "/users/nothing", List.of(), null);
              if (empty.status() == 200 && empty.body().isEmpty()
                  && empty.valuesOf("Content-Length").equals(List.of("0"))
                  && empty.valuesOf("Transfer-Encoding").isEmpty()) {
                correct++;
              } else {
                record(failures, "empty: " + empty.status() + " " + empty.fieldLines());
              }

              // 3. a 204: no length at all, which is the shape a neighbouring 200's length must not leak into
              RawHttp.Response none = RawHttp.request(port, "GET", "/users/gone", List.of(), null);
              if (none.status() == 204 && none.body().isEmpty() && none.valuesOf("Content-Length").isEmpty()) {
                correct++;
              } else {
                record(failures, "204: " + none.status() + " " + none.fieldLines());
              }

              // 4. a 304: no length, no body, and the ETag the handler set on this request only
              RawHttp.Response stale = RawHttp.request(port, "GET", "/users/stale", List.of(), null);
              if (stale.status() == 304 && stale.body().isEmpty() && stale.valuesOf("Content-Length").isEmpty()
                  && stale.valuesOf("ETag").equals(List.of("\"7\""))) {
                correct++;
              } else {
                record(failures, "304: " + stale.status() + " " + stale.fieldLines());
              }

              // 5. HEAD of the same resource: five bytes stated, zero bytes sent
              RawHttp.Response head = RawHttp.request(port, "HEAD", "/doc", List.of(), null);
              if (head.status() == 200 && head.body().isEmpty()
                  && head.countOf("Content-Length") == 1
                  && head.valuesOf("Content-Length").equals(List.of("5"))) {
                correct++;
              } else {
                record(failures, "head: " + head.status() + " [" + head.body() + "] " + head.fieldLines());
              }

              // 6. GET of it: the same single length, plus the bytes
              RawHttp.Response get = RawHttp.request(port, "GET", "/doc", List.of(), null);
              if (get.status() == 200 && get.body().equals("hello")
                  && get.countOf("Content-Length") == 1 && get.valuesOf("Content-Length").equals(List.of("5"))) {
                correct++;
              } else {
                record(failures, "get: " + get.status() + " [" + get.body() + "] " + get.fieldLines());
              }

              // 7. 405 with Allow, and 404 without it — the two route-table answers must not trade fields
              RawHttp.Response notAllowed = RawHttp.request(port, "DELETE", "/doc", List.of(), null);
              if (notAllowed.status() == 405 && notAllowed.valuesOf("Allow").equals(List.of("GET, HEAD, POST"))) {
                correct++;
              } else {
                record(failures, "405: " + notAllowed.status() + " " + notAllowed.fieldLines());
              }
              RawHttp.Response missing = RawHttp.request(port, "GET", "/nowhere" + index, List.of(), null);
              if (missing.status() == 404 && missing.body().equals("Not Found")
                  && missing.valuesOf("Allow").isEmpty()) {
                correct++;
              } else {
                record(failures, "404: " + missing.status() + " [" + missing.body() + "]");
              }

              // 8. the asterisk, interleaved: answered by the server, and not by any of the handlers above
              RawHttp.Response star = RawHttp.request(port, "OPTIONS", "*", List.of(), null);
              if (star.status() == 404 && star.body().contains("No context found for request")) {
                correct++;
              } else {
                record(failures, "star: " + star.status() + " [" + star.body() + "]");
              }
            } catch (IOException arrived) {
              record(failures, "socket failure for " + marker + ": " + arrived);
              return correct;
            }
          }
          return correct;
        }));
      }

      int answered = 0;
      for (Future<Integer> future : futures) {
        answered += future.get(120, TimeUnit.SECONDS);
      }
      clients.shutdown();
      assertThat(clients.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

      assertThat(failures).isEmpty();
      assertThat(answered).isEqualTo(threads * rounds * 9);
      // Four GETs per round on this route, and none of them the asterisk: the handler count is the
      // evidence that OPTIONS * polluted nothing, and that a 204 or a 304 did not swallow or duplicate a call.
      assertThat(parametrizedCalls).hasValue(threads * rounds * 4);
      assertThat(getCalls).hasValue(threads * rounds);
      assertThat(headCalls).hasValue(threads * rounds);
      assertThat(adapter.isRunning()).isTrue();
    }
  }

  private static synchronized void record(List<String> failures, String description) {
    if (failures.size() < 20) {
      // Capped, because a leak that fails 1600 assertions buries the first one.
      failures.add(description);
    }
  }
}
