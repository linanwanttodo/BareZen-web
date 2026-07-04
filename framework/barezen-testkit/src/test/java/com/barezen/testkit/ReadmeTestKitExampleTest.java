package com.barezen.testkit;

import com.barezen.security.Authorization;
import com.barezen.security.Principal;
import com.barezen.security.SecurityMiddleware;
import com.barezen.web.HttpMethod;
import com.barezen.web.Router;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executes the README's Phase 2.15 example verbatim, so the snippet a reader copies is proven against a real socket
 * rather than asserted in prose.
 *
 * <p>Every body below between the markers is copied character-for-character from README.md. If the example ever stops
 * compiling or stops passing, the documentation is wrong and this test says so — the same discipline
 * {@code ReadmeDocumentExampleTest} applies to the 2.14 document snippet (ADR-0043), applied here to the 2.15 testkit
 * snippet (ADR-0044).
 */
@DisplayName("README Phase 2.15 example, run as written")
class ReadmeTestKitExampleTest {

  @Test
  @DisplayName("the testkit walkthrough compiles and passes over a real server")
  void readmeSnippetRuns() {
    // ---- BEGIN README Phase 2.15 snippet ----
    Router router = Router.builder()
        .route(HttpMethod.GET, "/health", TestFixtures.textHandler("ok"))                 // a plain framework Handler
        .route(HttpMethod.GET, "/who",
            SecurityMiddleware.wrap(TestFixtures.authenticationFor("t0ken", new Principal("ada")),
                Authorization.allowAll(), TestFixtures.principalCapturingHandler()))
        .build();

    try (TestServer server = TestServer.start(router)) {                                   // the real JDK adapter, :0 port
      TestHttpClient client = server.client();                                             // java.net.http, NEVER-redirect
      Assertions.assertThat(client.get("/health")).statusIs(200).bodyIs("ok");
      Assertions.assertThat(client.request(HttpMethod.GET, "/who")
          .header("Authorization", "Bearer t0ken").send()).statusIs(200).bodyIs("hello, ada");
      Assertions.assertThat(client.get("/who")).statusIs(401);                             // anonymous never reached the delegate
    }                                                                                       // close() released the port here
    // ---- END README Phase 2.15 snippet ----
  }
}
