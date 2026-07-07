package com.barezen.console;

import com.barezen.plugins.user.UserRepository;
import com.barezen.security.Authentication;
import com.barezen.security.Authorization;
import com.barezen.security.Permission;
import com.barezen.security.Principal;
import com.barezen.security.SecuredHandler;
import com.barezen.security.SecurityContext;
import com.barezen.security.SecurityMiddleware;
import com.barezen.web.Handler;
import com.barezen.web.Headers;
import com.barezen.web.HttpMethod;
import com.barezen.web.Request;
import com.barezen.web.Response;
import com.barezen.web.Router;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Reading another account's grants — the one thing in this application that is privileged rather than personal.
 *
 * <p>Your own permissions already arrive at {@code GET /api/session}; asking about somebody else is a different kind of
 * question, so it is a different route with a different permission ({@code user:read}) and the framework's decorator
 * answers the refusal. That placement matters: the handler below never contains an "is this you?" comparison, because
 * the answer belongs to {@link SecurityMiddleware} and its grant table (ADR-0037's 401/403 split), not to a condition
 * written next to the SQL.
 *
 * <p>What a refusal costs is not visible here, and that is the honest limit: {@link SecurityMiddleware} answers 403
 * before any handler runs, so the application never learns that it happened and the audit trail records nothing about
 * it. A real authorisation audit needs a hook the framework does not have yet — inventing an event for it here would
 * be a fake record of a thing nobody observed.
 */
final class People {

  static final Permission USER_READ = Permission.of("user:read");

  private People() {
  }

  /** Register the account routes on the builder the application is assembling. */
  static void contribute(Router.Builder routes, Authentication authentication, Authorization authorization,
                         UserRepository users, Audit audit) {
    routes.route(HttpMethod.GET, "/api/users/{name}/permissions",
        SecurityMiddleware.wrap(authentication, authorization, USER_READ, new ReadPermissions(users, audit)));
  }

  /**
   * The grants one account holds, sorted.
   *
   * <p>Both routes that name a user's permissions call this — {@code /api/session} for the caller and this page's
   * {@code /api/users/{name}} for anybody else — because a list that arrives in the database's row order would show the
   * same account differently in two places, and a reader comparing the two would conclude the server contradicts
   * itself. The order is presentation, so it is decided once.
   */
  static List<String> grantsOf(UserRepository users, String name) {
    return new ArrayList<>(new TreeSet<>(users.permissionsOf(name)));
  }

  /** {@code GET /api/users/{name}/permissions} — the grants an account holds, as a sorted list. */
  private static final class ReadPermissions implements Handler, SecuredHandler {

    private final UserRepository users;
    private final Audit audit;

    ReadPermissions(UserRepository users, Audit audit) {
      this.users = users;
      this.audit = audit;
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters) {
      throw new AssertionError("registered under SecurityMiddleware, which delivers the secured half");
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters, SecurityContext context) {
      String asked = parameters.get("name");
      // Sorted rather than in the repository's order, because a page comparing two answers needs them to be
      // comparable; the database's row order is not a promise anyone made here.
      List<String> grants = grantsOf(users, asked);
      String caller = context.principal().map(Principal::name).orElse("anonymous");
      Map<String, String> fields = new LinkedHashMap<>();
      fields.put("user", asked);
      fields.put("askedBy", caller);
      fields.put("permissions", String.join(",", grants));
      fields.put("count", Integer.toString(grants.size()));
      return new Response(200, Headers.of("Content-Type", "application/json"),
          Json.object(fields).getBytes(StandardCharsets.UTF_8));
    }
  }
}
