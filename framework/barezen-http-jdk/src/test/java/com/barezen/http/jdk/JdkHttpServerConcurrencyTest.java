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

  private static synchronized void record(List<String> failures, String description) {
    if (failures.size() < 20) {
      // Capped, because a leak that fails 1600 assertions buries the first one.
      failures.add(description);
    }
  }
}
