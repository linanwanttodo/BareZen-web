package com.barezen.plugins.openapi;

import com.barezen.core.plugin.Plugin;
import com.barezen.core.plugin.PluginDescriptor;
import com.barezen.web.HttpMethod;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The OpenAPI plugin: it *collects* operation metadata and answers "what does this application expose". It is a
 * {@link Plugin} (ADR-0016) with one phase — it registers into nothing, it is registered — and it deliberately
 * generates no document (Stage 2.13-E). The projection from these records to a specification is 2.14's decision,
 * made inside this same package's fence (ADR-0043 chose placement over a new module, because the framework may
 * not name these types); what lives here is the input that projection consumes, and nothing more.
 *
 * <p>How operations get in: an application calls {@link #add} while it assembles, passing the same
 * {@code HttpMethod} and pattern it passed to {@code Router.builder()}. The plugin does not read the router — the
 * web contract exposes no route enumeration, and adding one for documentation would widen a contract 2.10 held
 * closed (ADR-0019's "one question" rule). So the two lists, routes and operations, are kept aligned by the same
 * code that writes both, which is visible at a reader's one location rather than reconstructed from an object graph
 * a doc tool reached into. The one guard this class adds against them drifting is {@link #add}'s duplicate refusal:
 * one operation per {@code (method, path)}, because a spec that lists a path twice is a spec that lies about the API.
 *
 * <p>State isolation (Stage 2.13-F): the collection is a private, mutable-by-this-method-only map, and {@link
 * #operations()} returns a frozen copy, so a caller — another plugin, a test, a handler — cannot mutate what this
 * plugin holds. {@link #clear()} exists for the plugin's *own* lifecycle and a test's reset, never reached by an
 * outside holder of the snapshot.
 */
public final class OpenApiPlugin implements Plugin {

  private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor("openapi", List.of());

  // Keyed by "METHOD\u0000path"; a LinkedHashMap so the read-back order is deterministic for 2.14's diffing.
  private final Map<String, OperationMetadata> operations = new LinkedHashMap<>();

  @Override
  public PluginDescriptor descriptor() {
    return DESCRIPTOR;
  }

  /**
   * Records one operation.
   *
   * @throws IllegalArgumentException if an operation for the same {@code (method, path)} was already added — the
   *     duplicate is a spec lie caught at the point of writing, and it names both so the fixer sees the collision
   */
  public void add(HttpMethod method, String path, String summary, List<String> tags) {
    Objects.requireNonNull(method, "method");
    OperationMetadata operation = new OperationMetadata(method, path, summary, tags);
    OperationMetadata previous = operations.putIfAbsent(key(method, path), operation);
    if (previous != null) {
      throw new IllegalArgumentException("operation " + method.text() + " " + path + " was declared twice (as \""
          + previous.summary() + "\" and \"" + summary + "\"); one (method, path) is one operation");
    }
  }

  /** A convenient single-tag and no-tag form of {@link #add}. */
  public void add(HttpMethod method, String path, String summary) {
    add(method, path, summary, List.of());
  }

  /**
   * The collected operations, sorted by path then method — an order this class defines rather than delegating to
   * insertion, so the same set always projects to the same output no matter how a caller's assembly code happened
   * to enumerate it (2.14's determinism requirement, started here at the source).
   *
   * @return an unmodifiable snapshot; mutating the returned list cannot reach this plugin's state
   */
  public List<OperationMetadata> operations() {
    List<OperationMetadata> snapshot = new ArrayList<>(operations.values());
    snapshot.sort(Comparator.comparing(OperationMetadata::path)
        .thenComparing(operation -> operation.method().text()));
    return List.copyOf(snapshot);
  }

  /** The count of operations recorded so far; the size a caller can ask without taking the whole snapshot. */
  public int size() {
    return operations.size();
  }

  /** Drops every recorded operation. The plugin's own reset; not reachable through {@link #operations()}. */
  public void clear() {
    operations.clear();
  }

  private static String key(HttpMethod method, String path) {
    return method.text() + "\u0000" + path;
  }

  @Override
  public String toString() {
    return "OpenApiPlugin[" + operations.size() + " operations]";
  }
}
