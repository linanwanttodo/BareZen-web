# ADR-0055: A Console Application, Because a Frontend Is Earned by a Consumer and Not by a Documentation Folder

## Status

accepted
Date: 2026-10-07
Decides: that this project has an application leaf (`app/`) rather than only examples and docs; that its browser session
carries a bearer token in memory instead of a cookie; that its UI copies the documentation site's design system rather
than sharing it; what the console proves about the public surface, and the four things it deliberately does not prove
Related: ADR-0016 (the frontend condition this finally meets), ADR-0054 (which retracted the claim that the docs site met
it), ADR-0047/0048 (the extension point and discovery, used here by a non-example consumer), ADR-0033–0037 (the security
decorator this application consumes rather than reimplements), ADR-0038 (the driver is named by the consumer),
ADR-0030 (bodies are bytes — the reason the login form is form-encoded), ADR-0043 (the document model),
ADR-0045/0052 (the leaf rule, applied a third time), ADR-0051 (static files, now serving an application's own page)

## Context

ADR-0016 has said since 2.5 that a frontend binding needs "a real consumer with named rules". Phase 3.6 claimed that
condition was met by `site/`; phase 3.8 retracted it, because a folder of prose is not an application UI (ADR-0054). The
user then asked for the project's frontend and, for its look, said: copy the documentation page.

So the requirement is a consumer with named rules, in the shape a project would actually adopt: something a person signs
into, that shows data, that has endpoints a browser calls. And every part of it had to be reachable through the public
surface — the moment this module needs a framework change to exist, it stops being evidence and becomes a reason.

## Options Considered

**A — widen `SecurityMiddleware` with a pluggable credential source so the session can be a cookie.** Refused for now,
not on principle: its `credentialFrom` reads `Authorization` and is private by the design ADR-0033 recorded, and adding a
second source would be the framework growing a capability because one page wanted it. It would also bring CSRF into a
demo that today has no cookie to forge, and it is reversible — the seam is a small, honest ADR whenever a page needs
navigation rather than fetch. Named as a reopening trigger rather than hidden as a gap.

**B — a cookie-shaped token in `localStorage`, or the token in a `<script>`-readable place.** Refused: a bearer token
stored where any script on the origin can read it converts one XSS into session theft, and it buys only persistence
across reloads. The reload cost is shown in the page's own text instead of engineered away.

**C — a static page with no server: the "frontend" as mockups.** Refused: it proves nothing about the framework, which is
the entire purpose of the phase.

**D — chosen: a small application leaf that signs in over `POST /api/sessions`, keeps the token in memory, and reads its
data from a route a plugin contributed.**

## Decision

1. **`app/` is a reactor leaf, last in the build, depended on by nothing, absent from the BOM** — the rule ADR-0045 set
   for the examples and ADR-0052 for the site, applied a third time. Being a separate module is what makes "public API
   only" a compile fact rather than a review comment: the framework's package-private types are unreachable from here, so
   `Console` composes what a stranger could compose.
2. **It is assembled from the shipped plugins.** `UserPlugin` (authorization + password verification), `AuthPlugin`
   (issues and recognises bearer sessions) and a `ReportsPlugin` written *in the application* that implements
   `Plugin, RouteContributor` and contributes `/api/reports`. This is the first time ADR-0047's extension point is used
   by something that is not an example or a test: the application never writes that route, and the test asserts the route
   is in the frozen table before any lifecycle phase has run.
3. **The credential is `Authorization: Bearer <opaque token>`, held in one variable.** No cookie, no storage API, no
   ambient identity; `SecurityMiddleware` does its job unchanged, so 401 and 403 come from the framework rather than from
   application prose. The token response carries `Cache-Control: no-store` because it is a credential.
4. **The UI copies the documentation site's design system** — the same six tokens, the same band header, the same measure,
   table and notes treatment — and does **not** share the file. Two deployment shapes (a static folder and a served
   application) reading one stylesheet would make every site change an app change; the duplication is ~110 lines of CSS
   and it is stated here rather than left to be discovered. What the docs page had no reason to carry, the console adds:
   a form, buttons, and an answer log whose status column is the only coloured thing on the page.
5. **The page shows the raw answers.** Nine flows are wired to buttons — sign in, who am I, list, add, sign out, document,
   a no-grants account, an over-limit body — and each appends the status, the interesting headers and the body text as
   they arrived. This is the actual deliverable: a reader can watch 401 and 403 be different answers, see `Allow: GET,
   POST` on a 405, and see 413 arrive with `Content Too Large` from the adapter rather than from a handler.
6. **Requests are form-encoded, and JSON is written by the application.** The framework has no JSON parser (bodies are
   bytes, ADR-0030) and keeps its writer package-private (ADR-0043: "a public JSON writer is a second API the framework
   would then owe forever"), so `Json` here is ~60 lines of escaping and a form reader. Refusing to smuggle a JSON
   dependency in through a leaf is the point.
7. **The application names its own database.** `Console.dataSource()` constructs H2's `JdbcDataSource` at compile scope —
   the decision ADR-0038 forbids a framework module to make, made visibly by a consumer that needs the driver's class to
   exist. `barezen-data` still sees only `javax.sql`.
8. **Two seeded accounts, because one cannot show a refusal.** `ada/hunter2` holds `report:read` and `report:write`;
   `bob/hunter2` holds nothing, so 403 is an observable answer instead of a sentence in a doc. The seed creates no
   password plainly: rows go through `UserRepository`, which hashes with PBKDF2 (ADR-0035).
9. **The port is an argument** (`Console 8080`, refused if it is not a number or out of range), and the shutdown hook
   stops the server before the orchestrator, in the order ownership implies (ADR-0011, ADR-0024).

## What this proved, and what it did not

It proved, by being written at all: an application with a session, a plugin-contributed endpoint, a JSON document and its
own static page can be built against the public surface with no framework change — the strongest form of the ADR-0016
condition, because the consumer existed before any capability was discussed.

It did **not** exercise: cookie sessions (option A — no second credential source exists), the servlet adapter (this runs on
the JDK adapter only), streaming or large-response framing (ADR-0030), server-rendered HTML of any kind, or a session
store shared across processes (`SessionStore` is in-memory and one instance per application — a multi-node deployment
would need a different answer, which is the data layer's problem, not the framework's).

## Consequences

- The reactor is twelve modules again, with a different twelfth: `barezen-app` instead of the retired `barezen-site`.
  The BOM is unchanged — an application is never a BOM entry.
- The class-file-major contract test now exists in eleven module copies, because the convention is one copy per module
  that emits class files (ADR-0032: a module's own outputs are the only ones its test phase can see).
- `examples/` remains the documentation-facing runnable set; `app/` is the runnable *application*. The distinction is in
  what they prove: examples show one capability each, the console shows the capabilities working together.
- The frontend row in README moves from NON-GOAL to met, with this record as the rule it was met under.

## Security implications

- **No CSRF surface by construction**: the credential arrives only in a header a cross-site request cannot add, because
  the browser never sends `Authorization` on its own.
- The session token is never persisted by the page, never logged by the application, and is issued with `no-store`.
  `POST /api/sessions` answers the same way for an unknown user and a wrong password (ADR-0037).
- No user-supplied path reaches the filesystem: the page, script and stylesheet are three named routes registered at
  assembly (ADR-0051), not a directory listing.
- Every report value reaches the browser through `textContent`, never `innerHTML`, and the one parse is `JSON.parse` —
  application data the framework cannot vouch for stays data.
- `X-Reader` echoes the authenticated principal's own name back to its own caller; nothing else in the response names
  another user.

## Testing

16 new tests in the module, measured on this build:

- `ConsoleTest` — 12, all over a real socket through TestKit: a correct password issues a bearer the session route
  recognises; a wrong password and an unknown user are the same 401 with the same one-line body; sign-out invalidates the
  value so the *same* credential answers 401 afterwards and the store reports zero sessions; **a bearer without the
  permission is 403 while no bearer at all is 401** (the distinction the page exists to show); a contributed route is in
  the frozen table before any phase runs; a write adds a row the next read returns; a body over one mebibyte is refused
  413 before the plugin's handler; `PUT /api/reports` answers 405 with `Allow: GET, POST`; the page arrives as
  `text/html; charset=utf-8` with a tag, and a conditional reload is 304 with no body and no length; `HEAD` states the
  page's length and sends nothing, for page, script and stylesheet alike; the document declares the JSON endpoints and
  not the page; an unregistered address is the adapter's plain 404.
- `ClassMajorContractTest` — 4, this module's copy of the ADR-0032 contract.
- Observed in a browser rather than assumed: nine logged flows in one session, ending `DELETE → 204`, then
  `GET /api/session → 401`; the stylesheet applies at the width this environment offers and the page has no horizontal
  overflow (`scrollWidth == clientWidth == 962`). Pointer delivery does not work in this environment's browser panel, so
  the flows were driven through DOM events against the real listeners — the click path itself is unverified here.

## Non-goals

A cookie credential source, a template engine, a client-side framework or build step, a JSON dependency, session
persistence across reloads, real account provisioning, multi-node sessions, a servlet war, and publishing the console as
a site.

## Reopening trigger

- **A page that must navigate rather than fetch** (a link the browser follows, an embedded form post, a shared link that
  should still be signed in) reopens option A — a `CredentialSource` seam in `SecurityMiddleware`, with its own record,
  and CSRF protection as part of that same decision rather than a later patch.
- **Deployment into somebody else's container** reopens ADR-0049's servlet integration with a real application to test it
  against, which is the case the 3.3 phase could only test with an embedded Tomcat.
- **Packaging the console as a jar** reopens ADR-0051's classpath-resource deferral, since the page is read from the
  classes directory today.

## Related Decisions

ADR-0016 (the condition met here), ADR-0054 (the retraction this answers), ADR-0047 (the extension point, used for real),
ADR-0033/0036/0037 (identity as an argument, opaque sessions, the 401/403 line), ADR-0038 (who names a driver),
ADR-0030/0043 (bytes as bodies and the document's limits), ADR-0045/0052 (the leaf rule), ADR-0032 (the bytecode contract).
