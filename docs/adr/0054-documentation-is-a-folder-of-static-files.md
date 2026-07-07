# ADR-0054: Documentation Is a Folder of Static Files, Because the Host That Deploys It Runs None of Our Code

## Status

accepted
Date: 2026-10-07
Supersedes: ADR-0052's delivery decision (a `site/` Maven module served by the framework) and ADR-0053's decision 3 (the
`/zh` → `/zh/` redirect). Both records keep their other decisions: the pages stay hand-written with no generator
(ADR-0052), and a language stays part of the address (ADR-0053). Accepted records are not rewritten, so this one says
what changed and why.
Related: ADR-0051 (static content, whose named consumer this folder used to be), ADR-0045 (the examples leaf, which
becomes that consumer), ADR-0029 (the framing the socket tests stop exercising here and start exercising there),
ADR-0016 (the frontend boundary, which stays unmet), ADR-0013 (no hidden configuration), ADR-0008 (no module without a
phase to fill it)

## Context

The user's deployment target is GitHub Pages. Pages serves files from a branch and a folder; it runs no code of ours,
so a `site/` module whose entire job was to answer `GET` requests for its own resources is a server that cannot be used
where the documentation is going to live.

There was a second reason, stated plainly: the website and the frontend were conflated. `site/` is documentation. What
the project still does not have is a frontend — an application UI that consumes the framework — and ADR-0016's condition
for talking about one ("a real consumer with named rules") is still unmet, because a docs folder is not that consumer.

Removing the module is not free. ADR-0052 built the pages on `StaticContent`, and ADR-0051 named that site as the reason
the content module exists; deleting the server leaves the capability with no consumer in shipped code unless the consumer
moves somewhere. It also moves five socket-level assertions out of the build.

## Options Considered

**A — keep the module and add an export step** (a `main()` that copies the tree to a deployable folder). Refused: it is
a build step of the kind ADR-0052 refused, with the same failure mode — the exported folder can disagree with what the
server would have answered.

**B — keep the module as a local preview server and deploy the folder anyway** — refused: two ways to view one tree, and
they differ in the ways that matter to a reader (`/docs/plugins` works locally, 404s on Pages; `ETag` reloads work
locally, not there). A difference between the preview and the deployed artefact is the drift this project keeps refusing.

**C — chosen: the folder is the site. No module, no server, no build step; the host serves the files, and a script
checks them.**

## Decision

1. **`site/` is a directory of files, not a reactor module.** `Site.java`, `SiteTest.java` and `site/pom.xml` are gone
   and the reactor is eleven modules again. The tree flattened to `site/index.html`, `site/docs/*.html`, `site/zh/…`,
   `site/assets/site.css`, because a Pages folder root of `/site` should not put `web` in every published address.
2. **Every address is relative, and a page URL keeps its `.html`.** A project page lives under `/<repo>/site/`, where
   `href="/assets/site.css"` resolves above the site and 404s — the framework's server used to answer extensionless
   routes, so this is the one reader-visible cost: `/docs/plugins.html` instead of `/docs/plugins`. The clean-URL trick
   Pages offers (a `404.html` that rewrites with JavaScript) is refused for ADR-0052's reason: a page that needs JS to
   find its own stylesheet is a page a text browser and a test both read wrong. `.nojekyll` is present so the host does
   not process the files at all.
3. **The guarantees move to `scripts/check-site.sh`, and they are the same four:** every `href`/`src` resolves *on disk*
   (the check that a route table could not make), every page reachable from one of the two landing pages, every page has
   its counterpart in the other language with reciprocal `hreflang` links, and no absolute address anywhere. What is
   genuinely lost is listed under Consequences rather than glossed.
4. **`barezen-content` keeps a real consumer by moving it, not by losing it.** `examples/` gains
   `staticcontent/StaticFileExample` (the eighth program): it lists a directory at assembly, registers one handler per
   file for both `GET` and `HEAD`, and prints the answers a reader cares about. Measured on this build:
   `GET /notes.txt -> 200, 44 bytes, ETag "ec185bc08f4786af"`, `GET with the same tag -> 304, 0 bytes,
   Content-Length (absent)`, `HEAD /notes.txt -> 200, states Content-Length 44, received 0 bytes of body`,
   `GET outside the routes -> 404, body "Not Found"`. ADR-0051's named consumer is now this program, and the site no
   longer needs to be one.
5. **HEAD is registered, not inherited — a fact this phase discovered.** Writing the example against the real contract
   produced `405` for `HEAD /notes.txt` on a GET-only route: `Router` matches the method the client sent, and ADR-0028's
   HEAD behaviour is about what the *adapter* writes for a HEAD that has a handler, not about folding HEAD into GET. The
   old `Site.router` registered GET only, so its own pages would have refused a HEAD probe; nobody ever sent one, because
   nobody tested it. The example now registers both verbs and a test pins the 405 that the omission produces.

## Consequences

- **What readers lose**: conditional `GET` (no `ETag`, no 304 — the host's caching replaces it), `/health`, and a
  generated `/openapi.json` endpoint. The document still exists as a capability and is produced by `OpenApiExample`;
  what is gone is one particular way of publishing it.
- **What the build loses**: five socket-level assertions in `SiteTest` (`aPageArrivesAsHtmlWithADerivedTag`,
  `aReloadAfterNoEditIsA304WithNoBody`, `anEditedPageChangesTag`, `aChinesePageArrivesAsHtmlWithTheSameFraming`,
  `theLandingPageMissingItsTrailingSeparatorIsRedirectedToTheCanonicalOne`). Three of the four ADR-0029 framing
  statements they covered are now asserted over a real socket by `StaticFileExampleTest` (200 with a length, 304 with no
  body and no length, HEAD stating a length it does not send), so the contract claim is not orphaned — what has no
  socket test anymore is "a page that changed produces a new tag", which `StaticContentTest` covers at handler level.
- The reactor is eleven modules; the BOM is unchanged (`barezen-site` was never in it, by ADR-0052's rule).
- The documentation frontend question reopens: this folder is docs, and the project's UI — the thing ADR-0016 asked to
  be earned by a real consumer — is still unbuilt.

## Security implications

- Nothing executes: the deployed artefact is files, so the site has no input to validate, no path to traverse and no
  secret to hold. The old module had the same property by construction; this one has it by absence.
- `StaticFileExample` lists a directory the program chose and registers exactly those names; no client-supplied path
  reaches `StaticContent` (ADR-0051's point restated for its new consumer).

## Testing

- `scripts/check-site.sh` — checks 12 pages, exits non-zero with one `FAIL:` line per problem; run before a deploy and by
  CI alongside the two existing JDK guards. Measured here: exit 0, `checked 12 pages, 1 stylesheet(s)`.
- Deployability, checked the way the host does it rather than the way a route table does: the folder served under a
  subpath by a plain static server, all 12 pages fetched and **all 124 link targets answering 200**, `/site/zh/` resolving
  through the directory index, and `/site/docs/plugins` (no extension) returning 404 — the cost decision 2 names.
- `StaticFileExampleTest` — 5 tests over a real socket, including the GET-only-route 405 that decision 5 records.
- Full reactor after this phase: `mvn -o -B -ntp clean verify` — **BUILD SUCCESS in 10:44, 1209 tests, 0 failures,
  1 skipped, 9 coverage gates met**, `check-jdk-baseline.sh` and `check-class-major.sh` both exit 0.

## Non-goals

A bundler, a template engine, a base-tag or `<link>` injection step at deploy time, JavaScript URL rewriting via
`404.html`, extensionless URLs on Pages, a search index, and treating the docs folder as the project's frontend.

## Reopening trigger

- **The project's frontend** — a real application UI with named rules — reopens "a module that serves files", because it
  would have a reason the docs folder never had: endpoints to call.
- **A deployment that must have extensionless URLs** reopens the option B/C question, and the honest answer is a host
  that rewrites, not a script of ours.
- **Publishing the OpenAPI document from the site** reopens ADR-0043's model limit (it describes JSON bodies or no body,
  which is why these pages were never in it).

## Related Decisions

ADR-0052 (what this replaces, and the parts that survive), ADR-0053 (the address-carries-the-language decision, kept in
full), ADR-0051 (the capability this re-points at a consumer), ADR-0045 (the leaf that gains a program), ADR-0028/0029
(the HEAD and framing behaviour the new example measures).
