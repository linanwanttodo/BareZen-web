# ADR-0052: The Website Is a Leaf of Hand-Written Files, Because a Consumer With No Build Step Is the Honest Test of a Framework

## Status

accepted
Date: 2026-10-06
Decides: whether this project has a frontend at all and in what form, how the site's pages become routes without a generator, what the site proves and what it deliberately does not, and the two limitations the exercise exposed
Related: ADR-0016 (the frontend NON-GOAL, which required "a real consumer with named rules"), ADR-0045 (the examples leaf, whose shape this copies), ADR-0051 (static content, the module this is the first consumer of), ADR-0043 (the OpenAPI model, whose limits this page found), ADR-0013 (the port is an argument, not a hidden knob), ADR-0008 (no module without a phase to fill it)

## Context

README said, since 2.5: *"Frontend | non-goal — BareZen is a server-side framework; a frontend binding needs a real
consumer with named rules."* That was right as a boundary and wrong as a trajectory: the framework now serves files,
generates an API document, and has two servers, and it had no non-trivial application of its own to check any of it
against. The examples are seven deliberate minima; nothing in the repository was a *product*.

The user asked for an official site for the project, with developer documentation as its substance. That is the named
consumer ADR-0016 asked for, so the question became what form it should take without teaching the framework anything
false about itself.

## Options Considered

**A — a static site generator (markdown to HTML, a template engine, a bundler).** Refused: it adds a build step and a
dependency to a project whose entire argument is that the third-party compile dependency is `slf4j-api` and nothing else
(ADR-0001), and it puts a layer between the source of a page and what a reader receives — the exact place where
"generated correctly once" turns into "still correct tomorrow".

**B — a server-rendered site with a template engine in the framework.** Refused: it would make templating a framework
capability that arrived without a consumer outside this one site, and the previous phase's own reasoning applies — if
the framework had a template story it would be ADR-0018 §12's rule about a contract that cannot name a server turned
inside out.

**C — reuse the docs markdown as the site's source, converted at request time.** Refused on the same ground as A, plus
one: the repository's documentation is written for a reader in a git host (ADR cross-references, test class names,
file paths), and a converter would show those to a browser user.

**D — chosen: hand-written HTML and one stylesheet, served by the framework, with tests instead of a generator.**

## Decision

1. **`site/` is a leaf module, last in the reactor, depended on by nothing, and absent from the BOM.** ADR-0045's rule
   for the examples applies unchanged: because the module is a separate Maven module, its dependency on package-private
   framework types is a compile error rather than a review finding, and the framework cannot acquire a dependency on its
   own website.
2. **Pages are files in `src/main/resources/web`; routes are declared in `Site.router(Path)`.** Six pages, one
   stylesheet, one JSON health handler, one generated `/openapi.json`. `StaticContent.file(...)` serves each with the
   ETag and conditional-GET behaviour it was written for in 3.4 — which is the reason that phase exists as a module
   rather than an adapter feature: the same pages serve under the JDK adapter (here) and under a container (nothing here
   stops it, ADR-0049).
3. **The tests replace the generator's guarantee.** `SiteTest` walks the tree and fails if a file under `web/` has no
   route, and it follows every internal `href="/…"` in every page and fails if the router does not answer it. A
   generated page can drift from its source silently; a page that nothing links to and a link that goes nowhere are both
   build failures here.
4. **The port is an argument.** `java -cp … com.barezen.site.Site [port]`, defaulting to 8080, refusing a non-number and
   an out-of-range value by name. No system property, no environment variable: the rule the framework enforces on its own
   modules by ArchUnit is applied to the one module that could have quietly broken it.
5. **The document describes what it can describe.** `ApiResponse` says "a JSON body of this schema" or "no body", so the
   HTML pages are left out of `/openapi.json` rather than declared as bodiless 200s. `Site.document()` says so in its
   own javadoc, and the omission is asserted by a test so the day the model grows a media type the test is where the
   change lands.

## What this proved, and what it did not

It proved the parts a unit test cannot: that `StaticContent` under a real router with a real adapter serves a browser
correctly (first load 200 with a length, reload 304 with no body and no length, edited file a new tag); that the
documented classpath recipe actually starts a working server; that an application can compose content, OpenAPI and the
contract without reaching around them.

It did **not** exercise the plugin line: the site uses no `Plugin`, contributes no routes and discovers nothing. It is a
consumer of the framework's *capabilities*, and ADR-0047/0048's plugin mechanism still has its only real consumers in
`examples/` (`ReportsPlugin`) and in the tests. Pretending otherwise in the record would be exactly the kind of claim this
audit exists to catch.

## Consequences

- The reactor has twelve modules and the build's public-API-only claim now covers two leaves.
- The site's five framework dependencies are `barezen-web`, `barezen-http-jdk`, `barezen-content`, `barezen-plugins`
  (for the document) and — at test scope — `barezen-testkit`. It takes no container, no JSON library and no template
  engine, because `ApiDocument`'s emitter and `StaticContent`'s type table are the framework's own.
- **Two limitations are recorded rather than fixed.** (i) The site is served from the classes directory, not from a jar:
  `StaticContent` needs a `Path`, and classpath-resource serving is a named deferral in ADR-0051. A packaged site would
  need that decision first, not an ad-hoc copy-to-temp-dir hack in the leaf. (ii) The pages restate the repository's
  guide prose, so a change to `docs/guide/authoring-a-plugin.md` does not automatically change
  `/docs/plugins`; the mitigation is that both are in one commit's diff, and the alternative was a converter that would
  have made the drift invisible rather than visible.
- `README`'s Frontend row moves from NON-GOAL to a named consumer, with the rule that produced the shape: no framework
  capability arrived *for* the site; the site consumed what 3.1–3.4 had already earned.

## Security implications

- No user input reaches a path: routes are written in `Site.router`, so traversal is absent by construction rather than
  filtered (ADR-0051's same point).
- No secret is in the module: no keystore, no password, and the health handler answers a constant.
- The shutdown hook stops the server; nothing else is released, because the site owns no thread pool and no
  connection — the adapter releases what it created (ADR-0024, ADR-0011).
- Cache behaviour is the framework's, not a hand-tuned one: `ETag` from content and no `Cache-Control`, which ADR-0051
  defers to a deployment that names a CDN.

## Testing

- `SiteTest` — 12 tests: coverage (every file routed, every internal link answered, the six pages and the stylesheet
  present), responses (200 with `text/html; charset=utf-8`, a reload that is a 304 with no body and no length, an edit
  that changes the tag, `/health` and `/openapi.json` as JSON, a 404 that is the adapter's own answer, a 405 with
  `Allow: GET`), the document's deliberate omission, and three misassemblies refused at assembly (missing page, missing
  asset directory, a port argument that is not a port).
- The site's page coverage is asserted structurally rather than by a count, because the number of pages is the thing
  most likely to change.
- JDK: measured on the build's own JDK 24 with `release 17`, consistent with ADR-0049's correction. JDK 17 and the CI
  matrix remain unexecuted here.

## Non-goals

A markdown-to-HTML pipeline, a template engine, client-side JavaScript, a search index, analytics, a favicon or any
binary asset, dark mode, a jar-packaged site, and any framework capability introduced to serve this site.

## Reopening trigger

- **A packaged site** opens ADR-0051's classpath-resource deferral; the trigger is a deployment that cannot ship a
  directory beside the classes.
- **A second consumer of the plugin mechanism** — the reason the site did not use it — opens when a feature team wants
  endpoints in a jar; that is 3.1/3.2's path and needs no change here.
- **Documentation prose in two places** opens the "single source, generated site" question again, and it should be
  answered with ADR-0001's dependency question asked out loud first.

## Related Decisions

ADR-0016 (the boundary this meets), ADR-0045 (the leaf this copies), ADR-0051 (the capability this consumes and the
wildcard refusal that shaped its routes), ADR-0043 (the model whose limit this page found), ADR-0047/0048 (what this
site does not exercise, stated plainly), ADR-0013 (the port as an argument).
