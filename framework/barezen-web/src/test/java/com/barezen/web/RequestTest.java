package com.barezen.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The request contract: what the client sent, kept in the form it arrived, and changed by nobody afterwards.
 */
class RequestTest {

  private static Request get(String path) {
    return new Request(HttpMethod.GET, path, null, Headers.empty(), null);
  }

  @Nested
  @DisplayName("the five facts it carries")
  class Surface {

    @Test
    void carriesMethodPathQueryHeadersBody() {
      Request request = new Request(
          HttpMethod.POST,
          "/orders",
          "id=7",
          Headers.of("Content-Type", "application/json"),
          "{\"total\":3}".getBytes(StandardCharsets.UTF_8));

      assertThat(request.method()).isSameAs(HttpMethod.POST);
      assertThat(request.path()).isEqualTo("/orders");
      assertThat(request.query()).contains("id=7");
      assertThat(request.headers().first("content-type")).contains("application/json");
      assertThat(new String(request.body(), StandardCharsets.UTF_8)).isEqualTo("{\"total\":3}");
    }

    @Test
    void exposesExactlyTheFiveAccessorsAndNothingElse() {
      // The shape is the promise: no attribute bag, no context, no service accessor, no setter, no server handle
      // (ADR-0018 §9). The synthetic filter is load-bearing: under `mvn verify` JaCoCo injects a method into the
      // class, and an assertion about the public surface has to look at what was written, not at what was instrumented.
      assertThat(java.util.Arrays.stream(Request.class.getDeclaredMethods())
          .filter(method -> !method.isSynthetic())
          .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
          .map(java.lang.reflect.Method::getName)
          .distinct())
          .containsExactlyInAnyOrder("method", "path", "query", "headers", "body", "toString");
    }

    @Test
    void hasNoWayToChangeItself() {
      // Every declared method is an accessor or a parser; a setter would let one component rewrite the request another
      // one is answering.
      assertThat(java.util.Arrays.stream(Request.class.getDeclaredMethods())
          .filter(method -> !method.isSynthetic())
          .map(java.lang.reflect.Method::getName))
          .noneMatch(name -> name.startsWith("set") || name.equals("put") || name.equals("remove"));
      assertThat(Request.class.getDeclaredConstructors()).hasSize(1);
    }
  }

  @Nested
  @DisplayName("the path is what arrived, decoded by nobody")
  class Path {

    @Test
    void keepsPercentEncodingIntact() {
      assertThat(get("/a%2Fb").path()).isEqualTo("/a%2Fb");
      // java.net.URI.getPath() returns "/a/b" for this input, which would turn one segment containing a slash into
      // two segments — a route matched against a resource the client did not name.
      assertThat(java.net.URI.create("/a%2Fb").getPath()).isEqualTo("/a/b");
    }

    @Test
    void keepsADoubledDotEncodedSegmentIntact() {
      assertThat(get("/%2e%2e/etc/passwd").path()).isEqualTo("/%2e%2e/etc/passwd");
      // Decoding here would hand a handler "/../etc/passwd" and thereby decide, in the contract, what ".." means for
      // somebody's resource. It does not.
    }

    @Test
    void doesNotNormaliseTrailingSlashesOrDots() {
      assertThat(get("/a//b").path()).isEqualTo("/a//b");
      assertThat(get("/a/").path()).isEqualTo("/a/");
      assertThat(get("/a/./b").path()).isEqualTo("/a/./b");
    }

    @Test
    void acceptsTheAsteriskTargetOfAnOptionsStarRequest() {
      // OPTIONS * asks about the server itself; there is no resource, and refusing it would make the method unusable.
      Request request = new Request(HttpMethod.OPTIONS, "*", null, Headers.empty(), null);

      assertThat(request.path()).isEqualTo("*");
      assertThat(request.method()).isSameAs(HttpMethod.OPTIONS);
    }

    @Test
    void refusesAPathThatIsNotARequestTarget() {
      assertThatThrownBy(() -> get("user/1"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must start with \"/\"");
      assertThatThrownBy(() -> get(""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must start with \"/\"");
      assertThatThrownBy(() -> get("http://h/p"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must start with \"/\"");
      // An absolute-form target belongs to a proxy, and a relative path is resolved against a base the contract does
      // not have — so both would be an argument about routing, arriving inside a value object.
    }

    @Test
    void refusesANullPathNamingWhatWasMissing() {
      assertThatThrownBy(() -> get(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("request path");
    }
  }

  @Nested
  @DisplayName("absent query and empty query are different")
  class Query {

    @Test
    void reportsNoQueryMarkerAsAbsent() {
      assertThat(get("/search").query()).isEqualTo(Optional.empty());
    }

    @Test
    void reportsAnEmptyQueryAsPresentAndEmpty() {
      Request request = new Request(HttpMethod.GET, "/search", "", Headers.empty(), null);

      assertThat(request.query()).contains("");
      // A handler that cannot tell these apart cannot tell "no filter supplied" from "everything filtered out".
      assertThat(request.query()).isNotEqualTo(get("/search").query());
    }

    @Test
    void keepsTheQueryEncodedAndUnparsed() {
      Request request = new Request(HttpMethod.GET, "/s", "q=a%20b+&x=1&x=2", Headers.empty(), null);

      assertThat(request.query()).contains("q=a%20b+&x=1&x=2");
      // No param(name), no map: parsing this means choosing rules about '+' versus '%20', charset, repeated keys and
      // ';' separators, and every one of those choices would become a rule no application can decline.
    }
  }

  @Nested
  @DisplayName("the body is in memory and copied both ways")
  class Body {

    @Test
    void readsBackAsACopySoAHandlerCannotEditWhatTheAdapterBuilt() {
      byte[] sent = "secret".getBytes(StandardCharsets.UTF_8);
      Request request = new Request(HttpMethod.POST, "/login", null, Headers.empty(), sent);

      request.body()[0] = 'X';

      assertThat(new String(request.body(), StandardCharsets.UTF_8)).isEqualTo("secret");
    }

    @Test
    void doesNotAliasetheCallersBuffer() {
      byte[] buffer = "first".getBytes(StandardCharsets.UTF_8);
      Request request = new Request(HttpMethod.POST, "/x", null, Headers.empty(), buffer);

      // A server reuses its read buffer for the next request; an aliasing Request would change after the handler
      // already answered.
      java.util.Arrays.fill(buffer, (byte) 'z');

      assertThat(new String(request.body(), StandardCharsets.UTF_8)).isEqualTo("first");
    }

    @Test
    void treatsNullAndZeroLengthAsNoBody() {
      assertThat(get("/x").body()).isEmpty();
      assertThat(new Request(HttpMethod.GET, "/x", null, Headers.empty(), new byte[0]).body()).isEmpty();
      // Both are "nothing to read". On the wire they are Content-Length: 0, which an adapter writes; this contract has
      // no header-writing job, so the distinction would be a fact nobody here can act on.
    }

    @Test
    void keepsBinaryBytesUnchanged() {
      byte[] png = {-119, 80, 78, 71, 13, 10, 26, 7, 0, (byte) 255};
      Request request = new Request(HttpMethod.PUT, "/image", null, Headers.empty(), png);

      assertThat(request.body()).isEqualTo(png);
      // No charset is applied anywhere: bytes in, bytes out, which is what makes this type usable for a file upload
      // as well as a JSON document.
    }
  }

  @Nested
  @DisplayName("refused construction")
  class Refusals {

    @Test
    void refusesANullMethodOrHeadersAndNamesWhatWasMissing() {
      assertThatThrownBy(() -> new Request(null, "/x", null, Headers.empty(), null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("request method");
      assertThatThrownBy(() -> new Request(HttpMethod.GET, "/x", null, null, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("request headers");
    }
  }

  @Nested
  @DisplayName("toString carries no body and no header value")
  class Printing {

    @Test
    void printsMethodPathAndBodySizeOnly() {
      Request request = new Request(
          HttpMethod.POST,
          "/login",
          "user=alice",
          Headers.of("Authorization", "Bearer super-secret"),
          "password=hunter2".getBytes(StandardCharsets.UTF_8));

      String printed = request.toString();

      assertThat(printed).isEqualTo("Request[POST /login, 16 body bytes]");
      assertThat(printed).doesNotContain("hunter2").doesNotContain("super-secret").doesNotContain("user=alice");
    }

    @Test
    void printsASingularForOneByte() {
      assertThat(new Request(HttpMethod.PUT, "/f", null, Headers.empty(), new byte[1]).toString())
          .isEqualTo("Request[PUT /f, 1 body byte]");
    }
  }
}
