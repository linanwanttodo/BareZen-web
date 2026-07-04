package com.barezen.plugins.user;

import com.barezen.core.plugin.Plugin;
import com.barezen.core.plugin.PluginDescriptor;
import com.barezen.plugins.auth.PasswordVerifier;
import com.barezen.security.Authorization;
import com.barezen.security.Permission;
import com.barezen.security.Principal;
import java.util.Objects;

/**
 * The user plugin: identity, lookup and persistence, offered to the rest of the system through the one security
 * port a user naturally answers — {@link Authorization}.
 *
 * <p>This is Stage 2.13-D's direction made concrete. The plugin depends on {@code com.barezen.security} and
 * implements its port; the security module knows nothing that a user is a row, a name, or this class. The arrow is
 * {@code Security ← Authorization ← UserPlugin}, the same one-way seam ADR-0038 drew between the data module and
 * any driver, only here between a capability and the plugin that fills it. An {@code ArchUnit} rule in this module
 * fails if anything in the framework is ever wired to depend back on the plugin (ADR-0042).
 *
 * <p>It is a {@link Plugin}, which ADR-0016 makes precise: a participant with a {@link PluginDescriptor}, and no
 * more lifecycle than {@code LifecycleParticipant} offers. Its only phase is {@link #initialize()}, where it
 * creates its own schema — idempotent DDL, so a restart is a no-op (ADR-0040). It declares no plugin dependencies:
 * it stands on a {@code Database}-backed repository supplied by the application, and a plugin that could not be
 * constructed from what the caller already holds is a plugin asking for a container.
 *
 * <p>{@link #authorize} answers the permission question; it deliberately does <em>not</em> answer the identity
 * question, because turning a credential into a {@link Principal} is the auth plugin's job and this one receiving
 * an {@code Authentication} port would mean one plugin owning two security decisions the framework split on purpose
 * (ADR-0033). What {@link Permission#ANY} means — "any authenticated identity" — is the middleware's definition
 * (ADR-0037), and this plugin honours it without touching the database: a permission the caller already granted by
 * authenticating is not re-litigated with a query.
 */
public final class UserPlugin implements Plugin, Authorization, PasswordVerifier {

  private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor("user", java.util.List.of());

  private final UserRepository users;

  public UserPlugin(UserRepository users) {
    this.users = Objects.requireNonNull(users, "users");
  }

  @Override
  public PluginDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public void initialize() {
    // One unit of work for both CREATE TABLE statements: the plugin's schema is one decision (ADR-0039).
    users.database().transaction(tx -> {
      users.createSchema(tx);
      return null;
    });
  }

  @Override
  public Decision authorize(Principal principal, Permission required) {
    Objects.requireNonNull(principal, "principal");
    Objects.requireNonNull(required, "required");
    if (Permission.ANY.equals(required)) {
      return Decision.ALLOW;
    }
    return users.permissionsOf(principal.name()).contains(required.name()) ? Decision.ALLOW : Decision.DENY;
  }

  /**
   * The stored-hash half of the identity check the auth plugin needs, offered as {@link PasswordVerifier} rather
   * than by the auth plugin reading the user table itself (ADR-0042's direction): the auth plugin holds the port,
   * this plugin answers it, and neither reaches into the other's collaborators.
   */
  @Override
  public boolean verify(String username, String password) {
    return users.verifyPassword(username, password);
  }

  @Override
  public String toString() {
    return "UserPlugin[]";
  }
}
