package com.barezen.security;

import java.util.Objects;

/**
 * What an authenticated identity is allowed to do: a set of permissions.
 *
 * <p>A {@code Permission} is not a role, not a group and not a policy. It is the smallest unit of authorization: a
 * name that means "this identity may do this thing". A role is a collection of permissions, a group is a collection
 * of identities, and a policy is a rule that maps identities to permissions. This type owns the permission itself,
 * not the collection it belongs to.
 *
 * <p>Permissions are compared by name, case-sensitively, because a permission is a security decision, not a display
 * string. Two permissions with the same name are the same permission, which is why a {@code Set<Permission>} is the
 * natural collection: duplicates are impossible, and lookup is a name match.
 *
 * @param name the permission name, never null and never blank
 */
public record Permission(String name) {

  /**
   * The permission the middleware asks for when a route is wrapped without naming one.
   *
   * <p>A wrapped route with no declared permission means "any authenticated identity passes, and this is the gate
   * for the whole handler", so the question the policy is asked is "may this principal do anything here".
   * {@link Authorization#allowAll()} answers yes and {@link Authorization#denyAll()} answers no; a
   * {@link Authorization#of(java.util.Map)} table answers by whether the principal is listed for {@code ANY}. A
   * route that needs finer control names its own permission and uses {@link
   * SecurityMiddleware#wrap(Authentication, Authorization, Permission, Handler)}.
   */
  public static final Permission ANY = new Permission("barezen:any");

  /**
   * A permission with a name that is not blank.
   *
   * @throws NullPointerException if {@code name} is null
   * @throws IllegalArgumentException if {@code name} is blank — a blank permission name is indistinguishable from
   *     no permission, and no permission is represented by the absence of a permission, not by a permission with
   *     no name
   */
  public Permission {
    Objects.requireNonNull(name, "the permission name; without one the permission is not a permission");
    if (name.isBlank()) {
      throw new IllegalArgumentException(
          "a permission name must not be blank; no permission is represented by the absence of a permission");
    }
  }

  /**
   * A permission with the given name.
   *
   * @param name the permission name, never null and never blank
   * @return the permission
   */
  public static Permission of(String name) {
    return new Permission(name);
  }
}
