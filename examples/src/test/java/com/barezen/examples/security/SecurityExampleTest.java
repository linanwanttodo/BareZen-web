package com.barezen.examples.security;

import com.barezen.testkit.Assertions;
import com.barezen.testkit.TestServer;
import org.junit.jupiter.api.Test;

/** Proves the security gate answers public, unauthenticated, authorized and forbidden requests distinctly. */
final class SecurityExampleTest {

  @Test
  void publicRouteNeedsNoToken() {
    try (TestServer server = TestServer.start(SecurityExample.router())) {
      Assertions.assertThat(server.client().get("/health")).statusIs(200).bodyIs("ok");
    }
  }

  @Test
  void missingTokenIsUnauthorized() {
    try (TestServer server = TestServer.start(SecurityExample.router())) {
      Assertions.assertThat(server.client().get("/report")).statusIs(401);
    }
  }

  @Test
  void grantedPermissionReachesTheHandlerWithTheIdentity() {
    try (TestServer server = TestServer.start(SecurityExample.router())) {
      Assertions.assertThat(server.client().request(com.barezen.web.HttpMethod.GET, "/report")
              .header("Authorization", "Bearer " + SecurityExample.TOKEN).send())
          .statusIs(200)
          .bodyIs("report for ada");
    }
  }

  @Test
  void authenticatedButNotGrantedIsForbidden() {
    // The same valid token, a permission ada does not hold: the middleware refuses before the handler runs.
    try (TestServer server = TestServer.start(SecurityExample.router())) {
      Assertions.assertThat(server.client().request(com.barezen.web.HttpMethod.GET, "/admin")
              .header("Authorization", "Bearer " + SecurityExample.TOKEN).send())
          .statusIs(403);
    }
  }

  @Test
  void wrongTokenIsUnauthorized() {
    try (TestServer server = TestServer.start(SecurityExample.router())) {
      Assertions.assertThat(server.client().request(com.barezen.web.HttpMethod.GET, "/report")
              .header("Authorization", "Bearer not-the-token").send())
          .statusIs(401);
    }
  }
}
