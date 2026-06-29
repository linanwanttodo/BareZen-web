package com.barezen.core.plugin;

import java.util.List;
import java.util.Objects;

/**
 * A plugin's static description: what it is called and which other plugins it expects to be present.
 *
 * <p>Two fields, because those are the two with a use. {@code id} is the plugin's stable name - the key an
 * application registers it under, the string that appears in a startup failure, and the thing two plugins must not
 * both claim. {@code dependencies} is a statement of intent that an application reads when it decides registration
 * order, which ADR-0003 already makes the assembler's job rather than the orchestrator's.
 *
 * <p>What is deliberately absent (ADR-0016):
 *
 * <ul>
 *   <li><b>a display name.</b> Nothing renders it. An application that needs a longer label for a console can keep
 *       it in its own code, where a reader can see what consumes it;
 *   <li><b>a version.</b> A version is only worth carrying once something compares or requires one. There is no
 *       version constraint and no solver in this framework, so the jar and the build system already hold that fact
 *       better than an API field that nothing reads would;
 *   <li><b>optional dependencies, capabilities, provides, ordering weights.</b> Each of these is a resolver asking
 *       to be written. A declared dependency that nothing verifies is one thing; a metadata model that implies
 *       verification is another.
 * </ul>
 *
 * <p>This is a description, not a manifest format. It is read from code that a person wrote, which is the point:
 * nothing parses a file, scans a classpath or discovers a plugin (ADR-0016).
 *
 * @param id stable identity, non-blank, unique within one application
 * @param dependencies ids of the plugins this one expects to be registered, copied on construction and never
 *     modified; empty for a plugin that stands alone
 * @throws IllegalArgumentException if {@code id} is blank, if {@code dependencies} holds a blank id, or if it
 *     holds {@code id} itself, which is a statement no registration order can satisfy
 */
public record PluginDescriptor(String id, List<String> dependencies) {

  public PluginDescriptor {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("plugin id must not be blank; it is the name a startup failure is"
          + " reported against and the key an application registers the plugin under");
    }
    Objects.requireNonNull(dependencies, "dependencies must not be null; use List.of() for none");
    // Checked against the caller's list rather than the copy: List.copyOf rejects a null element with an
    // exception that names no plugin, and "which plugin declared the unusable dependency" is the whole message.
    for (String dependency : dependencies) {
      if (dependency == null || dependency.isBlank()) {
        throw new IllegalArgumentException("plugin '" + id + "' declares a dependency that is null or blank;"
            + " a dependency is an id, and an id that is missing is not an optional one");
      }
      if (dependency.equals(id)) {
        throw new IllegalArgumentException("plugin '" + id + "' declares itself as a dependency; no registration"
            + " order can put a plugin before itself");
      }
    }
    dependencies = List.copyOf(dependencies);
  }
}
