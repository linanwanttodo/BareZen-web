# Changelog

All notable changes to BareZen-web. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
headings, and the project tracks the phases it names rather than a semver release history — the current version is
`0.1.0-SNAPSHOT`, no release artifact has been published, and no release date is claimed here.

Dates are taken from `git log`, not from a plan.

Two claims inherited from the 2.x entries are not true of the environment that produced them and were corrected in
Phase 3.3 (ADR-0049): the 2.15/2.16 lines say tests were green "on JDK 17 and JDK 21", and no entry can show a CI run.
At that time JDK 17 was not installed and the repository had no remote, so `.github/workflows/backend-ci.yml` had never
executed. The repository now has an `origin`, and remote runs have been observed on 2026-11-27; a workflow file or a
push alone is still not evidence of a green run, so the historical entries below are not retroactively upgraded. The builds that produced the historical numbers below ran on Maven's JDK 24 with `maven.compiler.release=17`.
The bytecode claim survives because it is asserted inside the build (`ClassMajorContractTest`,
`scripts/check-class-major.sh`), not by an unobserved matrix.

## [Unreleased]

The whole 2.x line and the first phase of the 3.x line live under `0.1.0-SNAPSHOT` on `main`. Once a release is actually
cut, the phases below get grouped under its tag with the real release date; until then this section is the honest state.
Phase 3.1 is the first phase after the 2.x line closed, and it is the phase ADR-0016 §6 deferred the extension mechanism
to.

### Maintenance hardening (2026-11-27)

- HTTP methods, header names and authentication schemes are checked as RFC 9110 tokens; raw controls and separators
  are refused before they can become routing, framing or authentication input.
- `SessionStore.rotate` validates its duration before token lookup, so an unknown token cannot hide a null, zero or
  negative lifetime.
- Repeated `Authorization` fields are rejected with 401 before authentication. Choosing only the first field allowed
  an intermediary and the application to authenticate different identities.
- `If-None-Match` is evaluated only on GET and HEAD, as RFC 9110 §13.1.2 defines it. A matching validator sent on
  POST or another method now gets the ordinary representation instead of a 304.
- `Authentication.bearer` compares the credential value with `MessageDigest.isEqual` instead of `String.equals`, the
  constant-time primitive `PasswordHasher` already uses, so the time an API-token check takes no longer measures how
  many leading bytes the caller guessed.
- `If-None-Match` now requires valid quoted entity tags and splits candidate lists only outside quotes. Malformed
  validators can no longer manufacture a 304, while valid strong, weak, list and wildcard forms keep working.
- Static-content ETags retain the complete SHA-256 digest instead of a 64-bit prefix, removing the unsupported
  collision-free claim at the cost of one cache revalidation for existing clients.
- `TestServer.start(router, configuration)` passes an explicit configuration to the real adapter. The console's 413
  boundary test now uses a small drainable surplus instead of expecting a full-duplex client to read a response after
  the JDK's documented large-body reset.
- CI path filters now include `examples/**` and `app/**`, so changes in the two Java leaf modules run the build matrix.
- Tutorial checkpoints no longer claim a known JDK 24 method-token failure. Committed token validation answers 400 for
  control, separator and non-ASCII tokens; the full 216-test adapter module and the 201-test positive list are green on
  Java 24.0.2 (2026-11-27).

### Build tooling (2026-11-28)

- JaCoCo moves from 0.8.12 to 0.8.13, the first release that officially supports Java 24. The same TestKit database
  test that produced 36 `Unsupported class file major version 68` instrumentation errors on JDK 24 now produces none;
  the Maven plugin and runtime agent are both 0.8.13.
- ArchUnit moves from 1.3.0 to 1.4.0, the first release that supports Java 24 class files. The same core architecture
  test that produced 88 `Unsupported class file major version 68` import warnings on JDK 24 now produces none, with all
  21 architecture tests passing.

### Documentation integrity (2026-11-28)

- Tutorial source links are now checked by `scripts/check_source_anchors.py`: every Java anchor must resolve inside the
  repository, agree with its displayed line number, and land on a code line rather than a blank, comment or brace-only
  line. A method-name label must also name the method on that line. The checker has eleven filesystem-level unit tests.
- CI now includes `docs/**`, `tutorial-docs/**` and `README.md` in its path filters and runs the anchor tests and check
  before the JDK 17/21 Maven matrix, so documentation-only commits receive the same verification discipline.

### Phase 4.3 — Seven Pages, and the Credential Problem They Bring (2026-10-07)

- The console is now **seven files on seven clean addresses** — `/`, `/reports`, `/session`, `/events`, `/people`,
  `/answers`, `/about` — registered one-file-per-route by `Console.PAGES` for `GET` **and** `HEAD`, and 31 routes in
  total. `/reports.html` answers 404 on purpose: an application chooses its own URLs, which is the exact inverse of the
  static folder's constraint in ADR-0054 (ADR-0057).
- **Splitting pages destroys variables**, and ADR-0055's token lived in one. It moved to `sessionStorage` along with the
  answer log: per tab, gone when the tab closes, never attached to a request automatically — so still no CSRF surface,
  and the rotation demonstration still works. Rejected instead: re-login on every navigation, an iframe standing in as a
  shell (which is the one-document design wearing a hat), and a cookie credential source.
- **The cookie source is refused again, on the same ground, and named as the production answer.**
  `SecurityMiddleware.credentialFrom` reads `Authorization` and is private by design; the moment this console is
  deployed past `127.0.0.1`, an `HttpOnly` cookie plus a `CredentialSource` seam plus CSRF protection is the right
  decision, and it belongs in its own record rather than being smuggled in for a demo.
- **What makes the trade survivable is a response policy, not a promise.** `SecurityHeaders.of(...)` is a handler
  decorator in the leaf that adds `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self';
  img-src 'self'; connect-src 'self'; font-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'`,
  plus `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`, to **every** page and asset. The framework
  gained no hook for it — the third instance of ADR-0033's argument that an application states its policy by wrapping
  what it registers.
- **The header dictates the shape of the script**: one `app.js`, wiring the controls of whichever page it loaded on via
  `body[data-page]`, because an inline `<script>` per page is now unservable and seven copies of the fetch wrapper would
  be a lie about there being one policy.
- The answer log is capped at fifty entries with bodies truncated to 400 characters in storage, mirroring `Audit.LIMIT`
  server-side: a log that survives navigation is also a log that survives until the browser complains.
- **One band width, so one left edge.** The stylesheet had copied the docs site's two measures — a wide header band and a
  68ch reading column — and on a console those two are stacked neighbours: measured at 962px the account row's text
  started 70px left of the page title's, which reads as a page that cannot decide where it begins. Every band is now
  `52rem`, and the header, the account row, the state line, the title and the footer share one x.
- **The same finding, in the documentation site's stylesheet.** `ch` is the width of `0` in the element's *own* font, so a
  16px body column and a 14px footer column both written `68ch` measure 605px and 529px at the same viewport — centred,
  their left edges start 38px apart and the page's edge jags between them. `site/assets/site.css` now says `41rem` and
  `50rem` — still two measures, because a docs page holds one kind of block and its header can afford the width, but no
  longer two that drift with the font-size of whatever wraps them; measured after the change, `h1` and the footer's
  paragraph both start at x=177. The footer's gutter moved from the `footer` to its `.band` for the same reason: padding
  on both indents the text twice.
- **The state line states what the server said.** A static sentence in the page once read 还没有会话 above a header that
  said 已登录：bob, because the script only rewrote it when something was clicked. `checkSession()` now runs on every
  load: it asks `GET /api/session`, prints the answer, drops a token the server no longer honours (so a tab left open past
  an expiry says so instead of lying), and hands the page its own loader — visiting `/reports` signed-in shows the table
  rather than an empty placeholder waiting for a click.
- **Three things that made the pages look wrong without looking like bugs:** a placeholder row whose first cell was an
  empty `<td>`, so "还没有列表。" began under the 标题 column instead of at the table's edge (now one spanning cell, built
  by `emptyRow`); `（ADR-0047）` breaking across lines as `（ADR-` / `0047）`, because a hyphen is a legal break point in a
  line of Chinese (`.nb`, plus `nowrap` for code in prose); and a detail table whose field names were styled as column
  headers — uppercase, 12.5px, in a 56px column — which turned four facts into a 364px block, now 191px.
- **Form grids name their columns.** `auto-fit` at this measure chose two and dropped the submit button onto a row of its
  own 71px below the fields it belongs to; `.filters`, `.pair` and `.single` state the widths. The empty
  `<label>&nbsp;</label>` that used to sit above every button is gone — `align-items: end` already puts the button's
  bottom on the input's, and the spacer only existed to make mismatched label heights look intentional. Two labels
  wrapped and one didn't, which is what made the row read as crooked.
- **One filled button per panel, and links for navigation.** The pagination row had a filled 重新取本页 beside three
  outlined ones and the form's own submit, so nothing on the panel was primary; the overview's "去其余几页" was four
  buttons whose handlers assigned to `location.href`, which is a link with extra steps — now `<a class="button">`, and
  three click handlers deleted with it.
- Dead code left with the old shell: the side-tab CSS (`.shell`, `.tabs`, `.content`) is deleted rather than parked, and
  `Console` now builds its route table from two lists so a page without a route, or a route without a page, fails at
  assembly instead of as a blank window.
- `ConsoleTest` 22 → 25: every page served with a policy naming `script-src 'self'` and not containing `unsafe-inline`
  (asserted per page — a policy that covers `/` and forgets `/reports` is a hole shaped like the session), no inline
  script or handler attribute in the files, and `/reports.html` a deliberate 404.
- In a browser: sign in on `/`, land on `/reports` — the header still reads 已登录：ada, the list answers 200 with
  `X-Reader: ada`, and `/answers` still holds the login recorded in the previous document. After the layout pass, at a
  962px viewport: `.brand`, `.who`, `h1` and `.panel` all start at x=89, the filter's two inputs and its submit share a
  bottom edge at 452, `ADR-0047` measures 77x19 (one line), the nav is one line, and no page scrolls sideways —
  `scrollWidth` equals `clientWidth` on all seven. Pointer events are still unavailable in this environment, so the
  navigation was driven through the page.

### Phase 4.2 — The Console Grows Views, and the Event Bus Grows a Consumer (2026-10-07)

- `app/` went from one long page to **seven hash-routed views** (`#overview`, `#reports`, `#session`, `#events`,
  `#people`, `#answers`, `#about`) and from thirteen to **nineteen routes**, with no framework change and still no build
  step: one HTML file, one script, one stylesheet, no bundler, nothing that needs Node (ADR-0056).
- New endpoints, each one a contract behaviour the page can show rather than describe: `GET /api/reports/{id}` (200, or
  the plain 404 that names no path, or 400 for a non-numeric id — only the application knows its ids are numbers, so
  only it can refuse that), `DELETE /api/reports/{id}` (204 with no body, then 404, then 403 for a reader without
  `report:write`), `GET /api/session/detail`, `POST /api/session/rotate`, `GET /api/users/{name}/permissions` and
  `GET /api/events`.
- `GET /api/reports` gained `q`, `limit` and `offset`, parsed by the application because `Request.query()` hands over raw
  text and the router refuses to decode a query into routing (ADR-0018 §4). A malformed `limit` is a 400 rather than a
  silently defaulted 20; `X-Total-Count`, `X-Page-Size` and `X-Reader` are the server's own words about the page it sent.
  An empty `?q=` and no `q` at all are collapsed into "no filter" deliberately, and a test says so.
- **The expiry is stated by the server through an injected `Clock`.** `ConsoleTest`'s clock assertion pins both the store
  and the handler to one fixed instant and asserts exactly `1800` seconds, rather than asserting a range against a wall
  clock — the same reason `StaticContent` takes no clock at all (ADR-0051).
- **Rotation is shown instead of asserted.** `POST /api/session/rotate` returns the new token and names the superseded
  one, and the page keeps a button that sends the dead credential on purpose: it answers 401 because the store removed
  it, not because the client stopped sending it (ADR-0036).
- **The event bus has a consumer now** — ADR-0017's "consumerless since 2.6" is superseded — **and still no extension
  point.** `Audit` creates an `EventBus`, subscribes its own reader per concrete event class (the bus routes on the exact
  runtime type, so a subscription to an interface would receive nothing) and caps the trail at fifty. Option A of the
  record — an `EventContributor` — is refused because ADR-0047's trigger is a *plugin* needing to contribute, and this
  consumer is the application.
- **Two bugs the interface exposed, recorded rather than smoothed.** (i) `wiring()` kept an `Authorization.of(Map…)`
  beside the shipped `UserPlugin`, which already answers `Authorization` by reading the grants table — and they
  disagreed about `cy`: the panel said "no permissions" while the same token was successfully reading the audit stream.
  The duplicate table is deleted, the plugin is the authorization source, and ADR-0042's direction now applies inside one
  application as well as between modules. (ii) `/api/session` and `/api/users/{name}/permissions` returned one account's
  grants in different orders, which a reader compares as the server contradicting itself; the ordering lives in
  `People.grantsOf` now.
- **A limit that no application code can close:** the audit trail cannot record refusals, because `SecurityMiddleware`
  answers 403 before any handler runs, so the application never learns one happened. A `recordRefusal(...)` helper was
  written for it and deleted — it could not be called, and the event it published would have described something nobody
  observed. The page prints the hole instead, and the framework hook that would close it is a named reopening trigger.
- Three seeded accounts, one per answer: `ada` reads and writes reports, `bob` holds nothing (403 beside 401), `cy` reads
  reports plus `user:read`, which is what makes the account and audit routes answerable at all. The header signs in as
  any of them in one click.
- `ConsoleTest` 12 → 22 tests (new `Queries` and `Lifecycle` groups, all over a real socket except the clock test, which
  calls the wrapped handler directly to pin the clock). In a browser: views switch, paging moves `2,3` → `4,5` and back,
  ada's `/api/events` renders the 403 explanation and cy's renders the trail, no horizontal overflow at 977px. Pointer
  events still do not reach this environment's hidden panel, so the flows were driven through DOM events and the click
  path remains unverified.

### Phase 4.1 — The Console Application, and the Frontend Condition Finally Met (2026-10-07)

- `app/`: a small application that signs in, holds a session, and lists reports — the "real consumer with named rules"
  ADR-0016 has asked for since 2.5, and the first thing to satisfy it after ADR-0054 retracted the documentation site's
  claim to have. It was written **without changing the framework**, which is the finding worth more than the module
  (ADR-0055).
- The report endpoints are not written by the application: `ReportsPlugin` lives in `app/`, implements
  `Plugin, RouteContributor`, and contributes `/api/reports` at assembly. This is ADR-0047's extension point used by
  something that is neither an example nor a test for the first time, and a test asserts the contributed route is in the
  frozen table before any lifecycle phase runs.
- The credential is `Authorization: Bearer <opaque token>` kept in one JavaScript variable. No cookie, because
  `SecurityMiddleware` reads its credential from `Authorization` alone and widening that is a framework capability
  arriving for one page (recorded as option A with a named reopening trigger, together with the CSRF work it would
  entail); no `localStorage`, because a token stored there is readable by any script on the origin. The page states the
  consequence — reload signs you out — rather than hiding it.
- The UI copies the documentation site's design system rather than sharing its stylesheet: the same six tokens, band
  header, measure, table and notes treatment, with a form, buttons and an answer log added. A static folder and a served
  application are two deployment shapes; the ~110 duplicated CSS lines are named in the record.
- Bodies are form-encoded and the application writes its own JSON: the framework has no parser (ADR-0030) and keeps its
  writer package-private (ADR-0043), so a leaf that needed neither grew no dependency.
- The application names its own database — `Console.dataSource()` builds H2's `JdbcDataSource` at compile scope, which is
  ADR-0038's decision made by the consumer that has to make it. Two accounts are seeded, `ada` with grants and `bob`
  without, so 403 is an observable answer beside 401 rather than a sentence about it.
- `/api/document` is generated from declared `OperationMetadata` and omits the HTML page for the reason ADR-0052 recorded
  the first time: `ApiResponse` describes a JSON body or no body.
- The reactor is twelve modules again with a different twelfth (`barezen-app` for the retired `barezen-site`); the BOM is
  unchanged, because an application is never a BOM entry. `app/` carries its own copy of the class-file-major contract
  test, per ADR-0032's convention that each module asserts its own outputs.
- 16 new tests: `ConsoleTest`'s 12 over a real socket (bearer issues and is recognised; wrong password and unknown user
  are one 401 with one body; sign-out makes the *same* credential answer 401 and empties the store; **no bearer is 401
  while a bearer without grants is 403**; a contributed route is in the table before any phase; a write shows up in the
  next read; a body over 1 MiB is 413 before the plugin's handler; `PUT` is 405 with `Allow: GET, POST`; the page is
  `text/html; charset=utf-8` with a tag, a conditional reload is a body-free 304 with no length, and `HEAD` states the
  length without the bytes for page, script and stylesheet alike; the document declares the JSON endpoints and not the
  page; an unregistered address is the adapter's plain 404) plus this module's 4 class-major tests.
- Verified in a browser: nine flows driven against the real listeners in one session — `POST /api/sessions 200`,
  `GET /api/reports 200` with `X-Reader: ada`, bob's login then `GET /api/reports 403`, an oversized body `413 Content
  Too Large`, `/api/document 200` (2488 bytes, `openapi 3.0.3`), `DELETE /api/sessions 204`, then `401`. Stylesheet
  applied, no horizontal overflow at this environment's 962px width. Pointer delivery does not work in this environment's
  hidden browser panel, so the clicks themselves are unverified — the flows were driven through DOM events.

### Phase 3.8 — Documentation Becomes a Folder (2026-10-07)

- `site/` is no longer a Maven module. `Site.java`, `SiteTest.java` and `site/pom.xml` are deleted, `<module>site</module>`
  is gone from the parent, and the reactor is eleven modules again. The pages moved from `site/src/main/resources/web/` up
  to `site/`, so a static host's folder root does not put `web` in every published address (ADR-0054).
- The reason is a deployment target, not a preference: the documentation is going to GitHub Pages, which serves a branch
  and a folder and runs none of our code. A module whose job was to answer `GET` for its own files could not be deployed
  where the files are published, and keeping it as a *local preview* was refused too, because the preview had URLs the
  deployed site cannot have (`/docs/plugins`, a conditional reload) — a preview that disagrees with production is the drift
  this project refuses (options A and B recorded and refused by name).
- Every address in the twelve pages is now relative and keeps its `.html`. A project page lives under `/<repo>/site/`, where
  `href="/assets/site.css"` resolves above the site and 404s; the extensionless clean URL is gone, and the Pages trick that
  would restore it (a `404.html` that rewrites with JavaScript) is refused for ADR-0052's standing reason. `.nojekyll` keeps
  the host from processing the files.
- `SiteTest`'s four structural guarantees became `scripts/check-site.sh`: every `href`/`src` resolves **on disk**, every
  page is reachable from one of the two landing pages, every page has its counterpart in the other language with reciprocal
  `hreflang` links, and no absolute address exists. It reports one `FAIL:` line per problem and exits non-zero; the site now
  checks out at 12 pages.
- **What readers lose is named**: conditional `GET` (the host's caching replaces our `ETag`/304), `/health`, and a published
  `/openapi.json`. The OpenAPI capability is unaffected; only this particular way of publishing it is gone.
- `barezen-content` keeps a real consumer by moving it. `examples/` gains an eighth program,
  `staticcontent/StaticFileExample`, which lists a directory at assembly, registers one handler per file for **both** `GET`
  and `HEAD`, and prints what it measured: `GET /notes.txt -> 200, 44 bytes, ETag "ec185bc08f4786af"`, a reload with that
  tag `-> 304, 0 bytes, Content-Length (absent)`, `HEAD -> 200` stating `Content-Length 44` and receiving 0 bytes, and an
  unregistered path `-> 404, body "Not Found"`. `StaticFileExampleTest` asserts those over a real socket (5 tests), which is
  where three of ADR-0029's four framing statements now live.
- **A hidden contract fact surfaced while writing it**: `HEAD` is not a folded `GET`. `Router` matches the method the client
  sent (ADR-0028), so a file registered for `GET` only answers 405 to a `HEAD` — which the pages this module used to serve
  would have done as well, and nobody knew, because nothing sent one. A test pins the 405 now.
- The frontend claim is corrected rather than carried. ADR-0052 recorded ADR-0016's condition ("a real consumer with named
  rules") as met by `site/`; a documentation folder is not an application UI, so README's frontend row returns to NON-GOAL,
  and the project's actual frontend remains unbuilt. What 3.6 proved still stands: publishing these pages needed no
  templating and no markdown capability.
- Full reactor after the phase, measured on this build: `mvn -o -B -ntp clean verify` — **BUILD SUCCESS in 10:44,
  1209 tests, 0 failures, 1 skipped, 9 "All coverage checks have been met"**, with `check-jdk-baseline.sh`,
  `check-class-major.sh` and the new `check-site.sh` each exiting 0. Deployability was checked the way the host will do it:
  the folder served under a subpath by a plain static server, all 12 pages fetched and all **124 link targets answering
  200**, `/site/zh/` resolving through the directory index, and `/site/docs/plugins` (no extension) correctly 404ing — the
  cost ADR-0054 names rather than hides.

### Phase 3.7 — The Documentation Site in Two Languages (2026-10-07)

- The site is bilingual by address: English stays at `/` and `/docs/…`, Chinese arrives at `/zh/` and `/zh/docs/…`, one
  hand-written file per page in a parallel tree under `web/zh`. A reader's language is therefore visible, linkable and
  fixed at assembly rather than decided per request (ADR-0053).
- The switcher is two `hreflang` links per page — the nav anchor plus a `<link rel="alternate">` in the head — and nothing
  else. No cookie, no JavaScript (still ADR-0052's non-goal), no `Accept-Language` negotiation: an `ETag` derived from the
  bytes of a file (ADR-0029) cannot become a function of a request header without the address meaning two documents.
- `/zh` answers `301` with `Location: /zh/`, because the exact-segment-count rule makes the two different routes
  (ADR-0019 §3) and one page keeps one canonical address. The redirect target is a constant, so it is not an open
  redirect and no client bytes reach it.
- Code blocks, identifiers, HTTP tokens, file paths and ADR numbers are byte-identical between the trees. A translated
  `RouteContributor` is a search that returns nothing.
- **Nothing in the framework learned a locale.** No `Locale` type, no message-bundle mechanism, no i18n module — the
  translation lives entirely in the `site/` leaf, which is ADR-0052's rule ("no framework capability arrives *for* this
  site") applied a second time.
- `SiteTest` goes from 13 to 16 tests. The new pairing test walks every page, resolves its counterpart in the other
  direction, requires the `hreflang` targets to be exactly that counterpart's address, and refuses a page that
  advertises its own language as an alternative. `urlFor` now derives `/zh/` from `zh/index.html` by position, so the
  pre-existing tree-walk and dead-link tests cover both subtrees with no special-casing — and that refactor failed its own
  first run, when dropping the `.html` guard turned the stylesheet's address into `/assets/sit`.
- The cost is named as a cost: twelve files are maintained by hand, and no test proves a Chinese paragraph still says what
  the English one says. What bounds the drift is "both files in one commit", plus a third named reopening trigger — a
  language that cannot be maintained that way is when the generation question gets answered.
- **Two 3.6 claims corrected by running what 3.6 told a reader to run.** (i) The classpath recipe printed on
  `/docs/reference` names `logback-classic` but not `logback-core`, and `Site` died with
  `NoClassDefFoundError: …JoranException` before binding a port — the recipe that was *run* in 3.6 worked, the one that was
  *written down* did not. Both reference pages now name all three jars, and a test asserts it. (ii) The claim "no
  page-level horizontal overflow at 488px" held for the landing page and not for the doc pages: measured at that width,
  `document.scrollWidth` was 632 against a 473px client, with `/docs/plugins`' table 608px wide (a cell's longest
  unbreakable token sets a table's minimum width) and the header nav running to 513px — an overrun the new `中文` chip made
  worse. `th, td { overflow-wrap: anywhere }` plus a `max-width: 720px` nav-wrap rule bring all twelve pages to
  `scrollWidth == clientWidth == 473`.
- Verified in a browser, both directions: clicking `EN` on `/zh/docs/plugins` lands on `/docs/plugins` with `lang="en"`
  and the chip reversing to `中文`, with no JavaScript involved; Chinese glyphs measure full-width (192px for four at
  48px), so the CJK fallback resolves rather than rendering tofu. Screenshots remain unavailable in this environment, so
  every number above is a measured rect, not a look.

### Phase 3.6 — The Project's Own Website (2026-10-06)

- `site/`: the official site, served by the framework it documents — six pages and one stylesheet of hand-written HTML,
  one route per file through `StaticContent`, `/openapi.json` generated by the OpenAPI plugin, `/health` a plain handler,
  on the JDK adapter. A leaf module wired after the examples, depended on by nothing, absent from the BOM (ADR-0052).
- No generator, no template engine, no markdown pipeline and no build step. The drift a generator would hide is caught by
  two tests instead: every file under `web/` must have a route, and every internal `href="/…"` in every page must be
  answered by the router.
- Those tests earned their keep immediately: the first run failed on `/assets/assets/site.css`, because the asset route
  was built from the relativized path rather than the file name — every page would have loaded unstyled.
- The port is a command-line argument (`Site 8080`), refused if it is not a number or is out of range, because the rule
  the framework enforces on its own modules by ArchUnit should also bind the one module that could quietly break it
  (ADR-0013).
- Two findings recorded rather than worked around: the site is served from `target/classes` (a jar-packaged site needs
  ADR-0051's deferred classpath-resource serving), and `ApiResponse` describes a JSON body or no body, so the HTML pages
  are deliberately absent from `/openapi.json` rather than declared as bodiless 200s — asserted by a test so the day the
  model grows a media type, that test is where the change lands.
- 13 tests. Verified in a browser at the width this environment offers (488px): no page-level horizontal overflow, the
  stylesheet applied, tables and code blocks laid out, nav wrapping as designed; the desktop single-line header is
  measured rather than assumed (brand 71px + nav 418px inside a 672px band).
### Phase 3.5 — The Guides Phase 2.16 Owed (2026-10-06)

- README gains a **Guide** section covering the twelve topics Stage 2.16-C of the execution spec listed and phase 2.16
  never wrote: installation (BOM coordinates and which modules an application actually needs), quick start, routing,
  HTTP, configuration, security, database, plugins, OpenAPI, TestKit, examples and architecture. Each section cites the
  tests that run the shape it shows.
- Every snippet was checked against the real API rather than written from memory, and three were wrong on the first pass —
  `Response.text(...)` does not exist, `Migrator` is obtained with `Migrator.of`, and `ApiDocument.builder()` takes its
  `info(...)`/`schema(...)`/`operation(...)` calls rather than constructor arguments. The security snippet now shows the
  `Handler`-plus-`SecuredHandler` pair that `wrap`'s detection actually requires, because a bare three-argument lambda
  would not compile at a call site.
- `examples/.../contract/ReadmeQuickStartTest.java` executes the Quick start snippet verbatim against a real socket and
  asserts the 200 body, the captured path parameter, the 404's exact bytes, and that `stop()` leaves the adapter not
  running with no port held — the convention `ReadmeTestKitExampleTest` and `ReadmeDocumentExampleTest` already
  establish, extended to the guide.
- `docs/guide/authoring-a-plugin.md` is the piece that did not exist anywhere: what a plugin may reach, the two ways onto
  the classpath and the no-argument-constructor limit that decides between them, the forbidden-behaviour table naming the
  rule behind each row, why a plugin's routes are unprotected until the plugin wraps them, and what is deferred with its
  record.
- 1 new test (examples 28 -> 29).

### Phase 3.4 — HTTPS and Static Content (2026-10-06)

- TLS on the framework's own server, turned on by naming a keystore and never by a flag:
  `barezen.http.jdk.tls.keystorePath` with `...keystorePassword`, both with no default, resolved once at construction.
  A path without a password fails the construction naming both keys; a blank path reads as no path. `Tls` is
  package-private, so `JdkHttpServerAdapter`'s public surface is unchanged and no fourth constructor was added
  (ADR-0050).
- The password appears in no message this code can write and in no log line, asserted rather than reviewed; protocol,
  ciphers and mTLS are deliberately unconfigured, so no weaker default can arrive by accident. `isRunning()`, `port()`
  and `stop()` behave exactly as they do in plaintext (ADR-0024).
- `framework/barezen-content`: `StaticContent.file(path)` is one `Handler` per route. An `ETag` of eight bytes of the
  content's SHA-256 (no clock, no mtime, survives a restart), `If-None-Match` answered with a body-free 304 that carries
  the tag forward, weak validators and comma lists handled, a fixed extension table rather than the host's MIME database,
  unknown types falling to `application/octet-stream`, and a plain 404 naming no path when the file is gone (ADR-0051).
- One file per route, deliberately: the router refuses a wildcard by name (ADR-0019 §19) and a static fall-through would
  be a middleware chain (ADR-0033). The consequence is that no request ever supplies a path, so traversal is structurally
  absent rather than defended against.
- **ADR-0029 D1 re-measured and kept.** Its named trigger — a real conditional-GET consumer — arrived, and this is the
  first component that genuinely knows the stored representation's size. Measured over a socket: 200 states
  `Content-Length: 14`, HEAD states 14 and sends nothing, 304 states nothing. Stating it would need a channel from
  handler to adapter for a field no client here can act on, so the decision stands on new evidence rather than on the
  old absence of one.
- 12 TLS tests (keystore generated per run by the running JDK's `keytool`, nothing committed) + 22 content tests + 6
  new architecture rules; spikes fired 4 content rules (2/1/1/1) and `no_thread_local` (3); mutations T1–T4 and
  C1–C4, C6 killed with every file md5-restored, C5 recorded as not expressible rather than as passing.
- No existing rule, contract or public type needed amending; the content module's "depends on the contract only" rule is
  javac-enforced for its upper half, which is stated rather than counted as a passing guard.

### Phase 3.3 — A Second Server, as a Servlet (2026-10-06)

- `framework/barezen-http-servlet`: `BarezenServlet` routes the same `Router` inside an existing Tomcat, Jetty or Wildfly
  deployment — the first integration that makes "adopt BareZen" mean "add a jar" instead of "replace your server"
  (ADR-0049).
- It is deliberately **not** an `HttpServerAdapter`: ADR-0024's contract is one instance owning a bound port, and a servlet
  owns none. One public class, one declared public method (`init`), two constants; `ServletBridge` is package-private for
  ADR-0022 §9's reason.
- The route table arrives by constructor or by one `ServletContext` attribute read once in `init()` — the declarative
  deployment needs a no-arg constructor, and one key of one type read once is ADR-0014's explicit registration, not the
  lookup ADR-0006 refuses.
- Measured against a real container before asserted: Tomcat frames an unstated body as `Transfer-Encoding: chunked` where
  the JDK adapter emits `Content-Length`, so the bridge states the length and ADR-0029's single framing matrix now holds
  across two servers; 204/304 state nothing; HEAD's suppression and the lowercase-`head` asymmetry reproduce from a
  different cause; the routed path is raw `getRequestURI()` minus the context path.
- Same 1 MiB request-body limit as the JDK adapter (claim refused before the read, counted past the bound), resolved once
  from the container's init-parameter so this module needs no dependency on the kernel. A bad or negative value fails the
  deployment; `0` is a policy.
- Recorded rather than smoothed over: three refusals never reach the servlet (encoded slash, a body short of its declared
  length, `TRACE`) and arrive as Tomcat's own HTML — including an exception report — and `Content-Type` whitespace is
  re-written by the container. A miswired deployment comes down (its requests get the container's 404), but `start()`
  does not throw unless the container is configured to fail the context, so no fail-fast is claimed.
- 58 module tests (18 contract semantics over real sockets, 7 body-limit, 10 assembly with no container, 4 deployment
  including a `/app` context, 8 ArchUnit rules, 7 surface, 4 class-major). One spike class fired 6 of the 8 rules
  (violations 1/3/1/1/1/1); the two cross-module rules can only be enforced by javac here and say so. Mutations M1–M5 and
  M7 killed, M6 shown not to compile, every file md5-restored. Coverage gate met at 85%.
- Docs corrected against `mvn -version`: this build runs on Maven's JDK 24 with `release 17`, not on JDK 21 as ADR-0047 and
  ADR-0048 first said; JDK 17 is not installed here and there is no git remote, so the CI matrix has never executed. The
  bytecode claim rests on `ClassMajorContractTest` and `scripts/check-class-major.sh`, which run inside the build.

### Phase 3.2 — Plugin Discovery (2026-10-06)

- `com.barezen.core.plugin.PluginDiscovery`: `discover()` and `discover(ClassLoader)` read
  `META-INF/services/com.barezen.core.plugin.Plugin` and return a fresh immutable list in provider-configuration order.
  It keeps nothing, runs no lifecycle phase, and answers no lookup — the search is a function from a classpath to a list
  (ADR-0048).
- Supersedes ADR-0016 §7's refusal while keeping its concern as a build fact: `the_assembler_and_the_event_bus_do_not_scan_or_reflect`
  still forbids `ServiceLoader.load` in `core.di` and `core.event`, spike-proven again in this phase (1 violation, build
  failed). No architecture rule needed amending.
- A discoverable plugin is one a public no-argument constructor can build, so a plugin needing a `DataSource` stays the
  application's to construct. None of the three shipped plugins is discoverable; `examples/.../ReportsPlugin.java` is, and
  it is announced by a real services file so the tests run against a live `ServiceLoader` rather than a mock.
- Discovered and hand-built plugins meet only at `PluginRegistration.validate`, which honours declared dependencies across
  the boundary and refuses a duplicate id whichever side claimed it.
- A provider that cannot be loaded aborts the call with the JDK's `ServiceConfigurationError` naming it — booting without
  an integration nobody can see missing was refused as the undebuggable failure (ADR-0004's cause-preserving rule).
- 11 `PluginDiscoveryTest` + 2 new `PluginExampleTest` tests (core 264 → 275, examples 26 → 28); mutations M1–M4 (cache the
  list, skip a broken provider, ignore the caller's loader, drop the null check) all killed and every source md5-restored. M1
  leaked across tests, which is the registry failure mode the no-state rule exists to prevent.

### Phase 3.1 — The First Extension Point (2026-10-06)

- `com.barezen.web.RouteContributor` is the framework's first extension point and the tenth contracted type: one method
  taking the `Router.Builder` the application already holds, declared by the component that owns routes because
  `barezen-web` names no core type and could not receive a generic extension token (ADR-0047). ADR-0016 §6's forward
  sentence — the mechanism ships with the first extension point, in the phase that owns it — is discharged here.
- `Router.Builder.contributing(Iterable<? extends RouteContributor>)` runs the list in the caller's order and returns
  `this`; it is `route` over a list rather than a third way to register, so the route-table allow-list grew one name and
  the family it refuses (`size`, `routes`, `replace`, `reload`) is still absent. A contributed route colliding with any
  other is refused by ADR-0019 §9's existing rule whichever side registered first.
- `com.barezen.plugins.routes.RouteContributions.of` narrows `Plugin`s to contributors — the one `instanceof` in the
  mechanism, and the only namespace where `Plugin` and a web type may meet (ADR-0042 rule ①). A pure function on
  ADR-0041's model: no state, no registry, no lookup, no phase.
- `Plugin`, `PluginDescriptor`, `PluginRegistration` and `LifecycleOrchestrator` are unchanged, and there is no
  `PluginContext`, no token, no annotation and no route enumeration. The builder → plugins → `start()` → `build()` →
  adapter sequence ADR-0016 left to applications collapses to builder → contribute → build → start.
- `PluginExample` gained `ReportsPlugin`, a plugin that owns an endpoint, and `PluginExampleTest` serves its contributed
  route over a real socket through the real JDK adapter — the example-level proof, not a test-local fixture.
- 16 `RouteContributorTest` + 12 `RouteContributionsTest` (web 232 → 248, plugins 102 → 114); both amended allow-lists
  spike-proven to still bite (1 violation each, build failed); five mutations M1–M5 killed and every source restored
  md5-identical.
- Left open by name with their triggers in ADR-0047: plugin discovery (3.2), route enumeration (the OpenAPI double-write's
  real fix), a second extension point, and per-route security policy.

### Phase 2.16 — Examples / Release Hardening (2026-10-04)

- `examples/` as one leaf Maven module wired last in the reactor, containing seven runnable programs on the public
  developer API only: `HelloWorld`, `RoutingExample`, `RequestResponseExample`, `SecurityExample`, `DatabaseExample`,
  `PluginExample`, `OpenApiExample`.
- Each example splits a package-private `router()` / `wiring()` factory from a `public static main(String[])` that
  binds a real socket and self-verifies, plus a TestKit-driven test the reactor runs — 25 example tests, all green
  on JDK 17 and JDK 21.
- ADR-0045 records the shape of the leaf: "public API only" enforced by the compiler rather than by review; the
  example's H2 compile dependency (framework rule holds where it was set — ADR-0038 binds framework modules, an
  application names its own driver); the four-line `UserRepository.PasswordHashing` adapter as the composition seam
  the framework deliberately keeps in place.
- `docs/examples/README.md`: build-and-test invocation, the `java -cp` recipe for running each `main()`, the observed
  output of all seven, and a reading order.
- README current state and layout updated for the 2.x completion; the module table's two placeholder rows replaced
  with named debt classifications (DEFERRED with a reopen trigger, NON-GOAL).
- `examples/contract/ClassMajorContractTest` so the emitted-major contract holds inside `mvn verify` for every
  emitting module, not only in the after-the-fact shell guard.

### Phase 2.15 — TestKit / Developer Experience (2026-10-04)

- `framework/barezen-testkit` as the reactor's second top module: `TestServer` binds the real JDK adapter to
  `127.0.0.1:0`; `TestHttpClient` speaks the seven verbs over `java.net.http.HttpClient` with
  `followRedirects(NEVER)`; `TestFixtures` hands out the counting and principal-capturing handlers; `TestDatabase`
  wraps an application-supplied `DataSource` and owns only the reset; `Assertions` gives the response / count /
  identity checks.
- Six ArchUnit rules enforce the isolation: nothing in the framework depends on testkit (the isolation rule itself),
  testkit never names plugins, no driver is named in main sources (`javax.sql` allowed on purpose), no thread-local,
  no system-property or environment reads, no static mutable state.
- ADR-0044 records why a new module is legal here when 2.14's generator was forced to stay inside `barezen-plugins`
  — testkit's dependencies all point downward, so no sideways edge is drawn at the reactor top.
- `docs/testkit/`: quick start, first integration test, testing security, testing database, testing OpenAPI.
- 77 testkit tests, seven mutations (the directive's six plus a wrong-echo handler) all killed and restored
  byte-identical.

### Phase 2.14 — OpenAPI (2026-10-04)

- The OpenAPI generator lives *inside* `com.barezen.plugins.openapi`, not as a new module: a framework module naming
  plugin types is the exact edge ADR-0042 rule ① forbids, so the placement was measured against the fence, not
  preferred (`ADR-0043`).
- Schemas are declared data the application writes, not classes the framework reflects — cycle safety, stable
  property order, and "no scanning" are structural properties. OpenAPI 3.0.3 JSON only, no YAML (two determinism
  proofs for one model); `operationId` derived rather than declared, so a duplicate fails loudly instead of silently
  colliding.
- `ApiDocument.build()` refuses six malformed-document categories up front.
- Golden-bytes determinism: the same declared document renders the same bytes every time.
- Two generator bugs caught by the tests first (a `List.add` returning always-true where `Set.add` was meant; a
  `$ref` walk that stopped at nesting). 102 plugin-module tests, eight mutations M1–M8 killed and byte-restored.

### Phase 2.13 — Plugin Ecosystem (2026-10-04)

- `framework/barezen-plugins` on the SPI already in core: `AuthPlugin` fills security's existing `Authentication`
  port, `UserPlugin` fills `Authorization`, `OpenApiPlugin` collects declared `OperationMetadata` only, generating no
  document. The framework never names a plugin back — the direction is one way (`ADR-0042`).
- `PluginRegistration.validate` is a pure static verifier: refuses duplicate id, missing dependency, and cycle
  all-or-nothing, and returns the set ordered so every dependency precedes its dependent (`ADR-0041`).
- Seven ArchUnit rules hold the boundary; every rule spike-proven to bite (violation counts 2/2/1/2/1/1/1) before
  the spikes were deleted. Six mutations M1–M6 killed.

### Phase 2.12 — Data (2026-10-04)

- `framework/barezen-data`: `Database(DataSource)` with zero compile dependencies, a `Transaction` whose
  `commit` / `rollback` are package-private so a repository cannot decide, `DataAccessException` as the single
  translation point, and a `Migrator` with a history table the framework owns (`ADR-0038`).
- Transaction semantics measured, not asserted: a first-draft promise that a failed migration leaves no trace was
  falsified by a standalone probe — H2 commits DDL implicitly — and rewritten to one honest sentence about the table
  the framework owns (`ADR-0040`).
- Nesting gets an explicit answer: a second `transaction(...)` opens an independent transaction on a second
  connection, pinned by tests, not documented-and-hoped (`ADR-0039`).

### Phase 2.11 — Security (2026-10-04)

- `framework/barezen-security`: `SecurityMiddleware` is a `Handler` decorator, three authentication states
  (`Authenticated`, `Failure`, `Anonymous` — `ADR-0034`), opaque revocable sessions rather than JWTs (`ADR-0036`),
  PBKDF2-HMAC-SHA256 password hashing (`ADR-0035`), and the 401/403 boundary — identity is delivered to a
  `SecuredHandler` as a parameter, never a thread-local (`ADR-0033`).
- Attached to the web contract without widening it — `barezen-web`'s shipped jar still declares zero compile
  dependencies. 99 security tests (7 ArchUnit rules, 4 class-major, 6 socket-level integration), fourteen mutation
  probes SM1–SM14 covering the authenticated gate, the 401/403/500 mapping, context delivery, permission question,
  bearer comparison, expiry boundary, session retirement and rotation, iteration guard, salt randomness and
  credential disclosure.

### Phase 2.10 — HTTP semantics stabilization & contract hardening (2026-10-01)

- The HEAD framing boundary: folded method decides the handler, raw token decides the wire bytes (`ADR-0028`).
- One asserted framing matrix — 200/204/304/HEAD/404/405/500 × length × chunked, `Transfer-Encoding` never written,
  a duplicated framing header refused whether its values agree or not (`ADR-0029`).
- Response body stays `byte[]` — the streaming design was measured against three named reopening triggers rather
  than skipped (`ADR-0030`).
- The request-body limit and its 413 answer (`ADR-0031`).
- The class-major contract: `scripts/check-jdk-baseline.sh` guards the declared release and
  `scripts/check-class-major.sh` guards the emitted bytes, so `-Dmaven.compiler.release=21` cannot quietly produce
  major-65 bytecode that a JDK 17 deployment cannot load (`ADR-0032`).

### Phases 2.1 – 2.9.6

Earlier phases are recorded as individual ADRs in `docs/adr/` (0001–0027) and in the per-phase paragraphs of
`README.md`; a `0.1.0-SNAPSHOT` line has one rolling history, so this file summarises the recent phases by the
decisions they made rather than by a semver release cadence.
