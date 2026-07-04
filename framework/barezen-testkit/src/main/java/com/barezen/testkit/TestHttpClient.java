package com.barezen.testkit;

import com.barezen.web.HttpMethod;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A thin HTTP client for talking to a {@link TestServer} from a test, built on {@link HttpClient} and nothing else.
 *
 * <p>This client exists so an integration test reads as intent — {@code server.client().get("/health")} — not as socket
 * plumbing. It adds no framing and changes none: it resolves a path against the server's base URI, sends exactly the
 * method and headers and body the test asked for, and returns the raw {@link TestResponse}. Every verb the framework
 * routes (GET, HEAD, OPTIONS, POST, PUT, DELETE, PATCH) has a direct method, and anything else is reachable through
 * {@link #request(HttpMethod, String)}.
 *
 * <p>It is deliberately not a full HTTP client. There is no redirect-following policy to configure, no cookie jar, no
 * async futures — a test that needs those is testing the JDK, not BareZen. {@code followRedirects} is set to
 * {@code NEVER} on clients created by {@link TestServer} so a {@code 3xx} arrives as itself and the test can assert on
 * the status the handler actually returned, which is the behaviour worth pinning.
 */
public final class TestHttpClient {

  private final HttpClient client;
  private final String baseUri;

  TestHttpClient(HttpClient client, String baseUri) {
    this.client = client;
    this.baseUri = baseUri;
  }

  /** @return an absolute URI for a path against this client's base */
  private URI uri(String path) {
    Objects.requireNonNull(path, "the path to request; there is no request without one");
    if (path.startsWith("http://") || path.startsWith("https://")) {
      return URI.create(path);
    }
    return URI.create(baseUri + (path.startsWith("/") ? path : "/" + path));
  }

  /** @param path the request path @return the response to {@code GET path} */
  public TestResponse get(String path) {
    return request(HttpMethod.GET, path).send();
  }

  /** @param path the request path @return the response to {@code HEAD path} */
  public TestResponse head(String path) {
    return request(HttpMethod.HEAD, path).send();
  }

  /** @param path the request path @return the response to {@code OPTIONS path} */
  public TestResponse options(String path) {
    return request(HttpMethod.OPTIONS, path).send();
  }

  /** @param path the request path @return the response to {@code DELETE path} */
  public TestResponse delete(String path) {
    return request(HttpMethod.DELETE, path).send();
  }

  /** @param path the request path @param body the request body @return the response to {@code POST path} */
  public TestResponse post(String path, String body) {
    return request(HttpMethod.POST, path).body(body).send();
  }

  /** @param path the request path @param body the request body @return the response to {@code PUT path} */
  public TestResponse put(String path, String body) {
    return request(HttpMethod.PUT, path).body(body).send();
  }

  /** @param path the request path @param body the request body @return the response to {@code PATCH path} */
  public TestResponse patch(String path, String body) {
    return request(HttpMethod.PATCH, path).body(body).send();
  }

  /**
   * Start building a request for any method, including one without a shortcut above.
   *
   * @param method the HTTP method
   * @param path the request path
   * @return a builder; call {@link Request#header}, {@link Request#body}, then {@link Request#send()}
   */
  public Request request(HttpMethod method, String path) {
    return new Request(method, uri(path));
  }

  /**
   * One request being assembled: a method, a resolved URI, optional headers, an optional body.
   */
  public final class Request {

    private final HttpMethod method;
    private final URI uri;
    private final List<String[]> headers = new ArrayList<>();
    private String body;

    private Request(HttpMethod method, URI uri) {
      this.method = method;
      this.uri = uri;
    }

    /**
     * Add a request header. The header name is sent with the casing given, since a test may be asserting on exactly
     * what the client put on the wire.
     *
     * @param name the header name
     * @param value the header value
     * @return this builder
     */
    public Request header(String name, String value) {
      headers.add(new String[] {name, value});
      return this;
    }

    /**
     * Set the request body as UTF-8 text.
     *
     * @param body the body
     * @return this builder
     */
    public Request body(String body) {
      this.body = body;
      return this;
    }

    /**
     * Send this request and read the whole response.
     *
     * @return the response
     * @throws TestRequestFailedException if the request could not be completed — a connection refused, a timeout, or an
     *     interruption. A test failure should say which, not fail with a raw checked exception the caller must catch
     */
    public TestResponse send() {
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(10))
          .method(method.text(), requestBody());
      for (String[] header : headers) {
        builder.header(header[0], header[1]);
      }
      try {
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new TestResponse(response.statusCode(), response.headers().map(), response.body());
      } catch (IOException failure) {
        throw new TestRequestFailedException(
            method.text() + " " + uri + " could not be completed: " + failure, failure);
      } catch (InterruptedException interruption) {
        Thread.currentThread().interrupt();
        throw new TestRequestFailedException(method.text() + " " + uri + " was interrupted", interruption);
      }
    }

    private HttpRequest.BodyPublisher requestBody() {
      if (body == null) {
        return HttpRequest.BodyPublishers.noBody();
      }
      return HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8));
    }
  }
}
