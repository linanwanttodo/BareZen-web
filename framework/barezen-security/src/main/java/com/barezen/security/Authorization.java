package com.barezen.security;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What an authorization decision needs: the identity, the permissions it holds, and the answer.
 *
 * <p>An {@code Authorization} is not a role resolver, not a group membership service and not a policy engine. It is
 * the component that takes an authenticated identity and a required permission and answers allow or deny. The answer
 * is always one of two states, because HTTP has two answers: {@code 403} for deny and pass-through for allow. There
 * is no third state, because an authorization that cannot decide is a bug, not a policy.
 *
 * <p>The default is deny: an identity that has not been explicitly granted a permission does not have it. This is
 * the secure default, because a missing permission check that allows is a security hole that cannot be found by
 * testing — it looks like everything works, until someone discovers they can do something they should not.
 */
@FunctionalInterface
public interface Authorization {

  /**
   * Decide whether the given identity is allowed the given permission.
   *
   * @param principal the authenticated identity, never null; anonymous requests are denied by the middleware before
   *     this method is called, so an authorization never sees an anonymous request
   * @param required the permission the request requires, never null
   * @return the decision, never null
   * @throws RuntimeException if the implementation itself failed — a database unreachable, a policy service that
   *     threw. The middleware that calls this method catches the exception and answers {@code 500}, because a
   *     broken authorization component is a server problem, not a client one
   */
  Decision authorize(Principal principal, Permission required);

  /**
   * An authorization that allows everything: every authenticated identity has every permission.
   *
   * <p>Useful for tests and for applications that do not use authorization. The default is deliberately not this,
   * because an authorization that allows everything is not a security layer — it is a decoration.
   *
   * @return an authorization that always allows
   */
  static Authorization allowAll() {
    return (principal, required) -> Decision.ALLOW;
  }

  /**
   * An authorization that denies everything: no authenticated identity has any permission.
   *
   * <p>Useful as the default when no authorization is configured, so that a missing security setup is not silently
   * permissive — it is explicitly denied, and the application must explicitly allow.
   *
   * @return an authorization that always denies
   */
  static Authorization denyAll() {
    return (principal, required) -> Decision.DENY;
  }

  /**
   * An authorization backed by a fixed table of which permissions each principal holds.
   *
   * @param grants the grants, keyed by principal, never null; a principal absent from the table, or present without
   *     the required permission, is denied. The table is copied, so later changes to the given map do not change the
   *     authorization's answer
   * @return an authorization that allows exactly the permissions the table grants
   */
  static Authorization of(Map<Principal, Set<Permission>> grants) {
    Objects.requireNonNull(grants, "the grants table; without one there is nothing to authorize");
    Map<Principal, Set<Permission>> table = Map.copyOf(grants);
    return (principal, required) -> {
      Set<Permission> held = table.get(principal);
      return held != null && held.contains(required) ? Decision.ALLOW : Decision.DENY;
    };
  }

  /**
   * The decision an authorization makes: allow or deny.
   */
  enum Decision {
    ALLOW,
    DENY
  }
}
