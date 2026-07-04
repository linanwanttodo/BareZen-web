package com.barezen.plugins.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barezen.web.HttpMethod;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The OpenAPI plugin, tested for exactly what Stage 2.13-E allows it to do: hold declared metadata, refuse a
 * duplicate operation, and answer with a deterministic, isolated snapshot. It is asserted to generate nothing —
 * there is no document method to call, because a doc generator is 2.14's module, not this one's job (ADR-0042).
 */
class OpenApiPluginTest {

  @Nested
  @DisplayName("recording operations")
  class Record {

    @Test
    @DisplayName("an added operation reads back with the facts it was declared with")
    void addThenRead() {
      OpenApiPlugin plugin = new OpenApiPlugin();
      plugin.add(HttpMethod.GET, "/users/{id}", "Fetch one user", List.of("users"));

      assertThat(plugin.size()).isEqualTo(1);
      assertThat(plugin.operations()).singleElement()
          .isEqualTo(new OperationMetadata(HttpMethod.GET, "/users/{id}", "Fetch one user", List.of("users")));
    }

    @Test
    @DisplayName("the no-tag overload records an operation with an empty tag list")
    void noTagForm() {
      OpenApiPlugin plugin = new OpenApiPlugin();
      plugin.add(HttpMethod.DELETE, "/sessions", "Drop a session");

      assertThat(plugin.operations()).singleElement().satisfies(operation -> {
        assertThat(operation.tags()).isEmpty();
        assertThat(operation.summary()).isEqualTo("Drop a session");
      });
    }

    @Test
    @DisplayName("a second operation on the same (method, path) is refused and names both summaries")
    void duplicateRefused() {
      OpenApiPlugin plugin = new OpenApiPlugin();
      plugin.add(HttpMethod.POST, "/users", "Create a user");

      assertThatThrownBy(() -> plugin.add(HttpMethod.POST, "/users", "Register someone"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Create a user")
          .hasMessageContaining("Register someone")
          .hasMessageContaining("declared twice");
      assertThat(plugin.size()).as("the refused write left no trace").isEqualTo(1);
    }

    @Test
    @DisplayName("a different method on the same path is a different operation and is accepted")
    void samePathOtherMethodIsDistinct() {
      OpenApiPlugin plugin = new OpenApiPlugin();
      plugin.add(HttpMethod.GET, "/users", "List users");
      plugin.add(HttpMethod.POST, "/users", "Create a user");

      assertThat(plugin.size()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("declared facts are validated where written")
  class Validation {

    @Test
    @DisplayName("a null method, path, summary, or tags list is refused")
    void nullsRefused() {
      OpenApiPlugin plugin = new OpenApiPlugin();
      assertThatThrownBy(() -> plugin.add(null, "/a", "s"))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> plugin.add(HttpMethod.GET, null, "s"))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> plugin.add(HttpMethod.GET, "/a", null))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> plugin.add(HttpMethod.GET, "/a", "s", null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a path that is blank or does not start with '/' is refused")
    void badPathRefused() {
      OpenApiPlugin plugin = new OpenApiPlugin();
      assertThatThrownBy(() -> plugin.add(HttpMethod.GET, "users", "s"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("start with '/'");
      assertThatThrownBy(() -> plugin.add(HttpMethod.GET, "   ", "s"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a blank summary and a blank tag are refused")
    void blankTextRefused() {
      OpenApiPlugin plugin = new OpenApiPlugin();
      assertThatThrownBy(() -> plugin.add(HttpMethod.GET, "/a", "   "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("summary");
      assertThatThrownBy(() -> plugin.add(HttpMethod.GET, "/a", "s", List.of("ok", " ")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("tag");
    }
  }

  @Nested
  @DisplayName("the snapshot")
  class Snapshot {

    @Test
    @DisplayName("operations read back sorted by path then method, not insertion order")
    void orderIsDefinedNotAccidental() {
      OpenApiPlugin plugin = new OpenApiPlugin();
      plugin.add(HttpMethod.POST, "/zeta", "z");
      plugin.add(HttpMethod.GET, "/alpha", "a1");
      plugin.add(HttpMethod.DELETE, "/alpha", "a2");
      plugin.add(HttpMethod.PUT, "/mike", "m");

      assertThat(plugin.operations())
          .containsExactly(
              new OperationMetadata(HttpMethod.DELETE, "/alpha", "a2", List.of()),
              new OperationMetadata(HttpMethod.GET, "/alpha", "a1", List.of()),
              new OperationMetadata(HttpMethod.PUT, "/mike", "m", List.of()),
              new OperationMetadata(HttpMethod.POST, "/zeta", "z", List.of()));
    }

    @Test
    @DisplayName("mutating the returned list cannot reach the plugin's state")
    void snapshotIsIsolated() {
      OpenApiPlugin plugin = new OpenApiPlugin();
      plugin.add(HttpMethod.GET, "/a", "a");
      List<OperationMetadata> snapshot = plugin.operations();

      assertThatThrownBy(() -> snapshot.add(new OperationMetadata(HttpMethod.GET, "/b", "b", List.of())))
          .isInstanceOf(UnsupportedOperationException.class);
      assertThat(plugin.size()).as("the plugin still holds one operation").isEqualTo(1);
    }

    @Test
    @DisplayName("clear drops the plugin's own state but never touches a snapshot already handed out")
    void clearResetsOwnStateOnly() {
      OpenApiPlugin plugin = new OpenApiPlugin();
      plugin.add(HttpMethod.GET, "/a", "a");
      List<OperationMetadata> before = plugin.operations();

      plugin.clear();
      assertThat(plugin.size()).isZero();
      assertThat(before).as("the earlier snapshot is a frozen copy").hasSize(1);
    }
  }

  @Nested
  @DisplayName("identity")
  class Identity {

    @Test
    @DisplayName("the descriptor is 'openapi' with no dependencies, and toString leaks nothing")
    void descriptorAndToString() {
      OpenApiPlugin plugin = new OpenApiPlugin();
      assertThat(plugin.descriptor().id()).isEqualTo("openapi");
      assertThat(plugin.descriptor().dependencies()).isEmpty();

      plugin.add(HttpMethod.GET, "/users/{id}", "Fetch one user");
      assertThat(plugin.toString()).contains("1 operations");
      assertThat(plugin.toString()).doesNotContain("Fetch one user");
    }
  }
}
