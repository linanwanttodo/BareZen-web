package com.barezen.examples.hello;

import com.barezen.testkit.Assertions;
import com.barezen.testkit.TestServer;
import org.junit.jupiter.api.Test;

/** Proves the hello-world example's router answers a real HTTP request with the greeting it documents. */
final class HelloWorldTest {

  @Test
  void helloRouteAnswersWithTheGreeting() {
    try (TestServer server = TestServer.start(HelloWorld.router())) {
      Assertions.assertThat(server.client().get("/hello"))
          .statusIs(200)
          .bodyIs("hello, world")
          .headerIs("Content-Type", "text/plain; charset=utf-8");
    }
  }

  @Test
  void unknownPathIsNotFound() {
    try (TestServer server = TestServer.start(HelloWorld.router())) {
      Assertions.assertThat(server.client().get("/missing")).statusIs(404);
    }
  }
}
