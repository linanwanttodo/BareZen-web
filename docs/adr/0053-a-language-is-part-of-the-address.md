# ADR-0053: A Language Is Part of the Address — Two Trees, Two `hreflang` Links, No Negotiation

## Status

accepted
Date: 2026-10-07
Decides: that the site serves documentation in Chinese as well as English, how a reader switches, what decides which
language a reader gets, and what the duplication costs
Related: ADR-0052 (the site this extends, and its no-client-side-JavaScript non-goal), ADR-0051 (static content, the
capability each page still uses), ADR-0019 §3 (exact segment count, which is why `/zh` is not `/zh/`), ADR-0029
(the ETag derived from bytes, which is what a `Vary` would have complicated), ADR-0001 (the dependency question, asked
again and answered the same way), ADR-0013 (nothing decided by a hidden input)

## Context

The site ADR-0052 shipped is English, and the person maintaining this framework works in Chinese. Documentation that
arrives in one language for a framework whose whole pitch is "put this into a project you already have" is a
compatibility claim with an exception in it.

The question is not whether to translate. It is what decides the language a reader receives, because every candidate
answer either moves that decision somewhere hidden or makes one address mean two documents — and the site's existing
guarantee is that what a reader sees is a file in the tree with a route pointing at it.

## Options Considered

**A — `Accept-Language` content negotiation on one URL.** Refused: one address would then return two different bodies,
which makes the `ETag` that `StaticContent` derives from bytes a function of a header rather than of the file
(ADR-0029), needs a `Vary: Accept-Language` the contract's header table can carry but nothing in the site reasons
about, and makes the page a reader shares with a colleague depend on the colleague's browser. It also means the build's
link test can no longer answer "does this page exist" without inventing a header to ask with.

**B — a cookie plus a redirect, or a JavaScript toggle.** Refused twice over: a cookie makes the language invisible in
the address, so it cannot be linked, cached or tested as a thing; and client-side JavaScript is a named non-goal of this
site (ADR-0052), accepted there because a page that needs JS to say which language it is in is a page a crawler, a
text browser and a test all read differently.

**C — one page with both languages interleaved.** Refused: it doubles the length of every page to serve the one language
any given reader wants, and it makes the "is this paragraph still true of the code" question ambiguous — an English
sentence and its translation can disagree, and interleaving hides which one a reader acted on.

**D — chosen: a parallel tree under `web/zh`, one file per English file, and two `hreflang` links per page.**

## Decision

1. **The address carries the language.** English stays at `/` and `/docs/…`; Chinese is at `/zh/` and `/zh/docs/…`. A
   reader's language is therefore visible, linkable, cacheable and fixed at assembly — `Site.ZH_PAGES` maps each
   address to one file, next to `PAGES`, in nav order for the same reason ADR-0052 gives for ordering the English table.
2. **The switcher is two links, not a widget.** Each page's nav ends with an anchor to its counterpart
   (`<a class="lang" hreflang="zh" href="/zh/docs/plugins">中文</a>` and the mirror `<a … hreflang="en" href="/docs/plugins">EN</a>`),
   plus the same pair as a `<link rel="alternate" hreflang="…">` in the head for a machine that reads the head rather
   than the nav. No JS, no cookie, no server-side guess. A button that "switches" by fetching and swapping text would
   make the site's own routing irrelevant, and this site exists to be a consumer that cannot be helped more than any
   other project could be (ADR-0052).
3. **`/zh` redirects to `/zh/`.** The segment-count rule makes the two different patterns rather than the same route
   (ADR-0019 §3), and one page must have one canonical address, so the address missing its separator answers
   `301` with `Location: /zh/` instead of serving the file twice. The `Location` is a constant; no client bytes reach
   it.
4. **Code does not translate.** Identifiers, class names, HTTP method and status tokens, ADR numbers, file paths and
   every code block are byte-identical between the two trees. A reader of the Chinese pages is expected to paste
   `RouteContributor`, `barezen-http-servlet` or `ADR-0047` into a search box or a terminal, and a translated token is a
   search that returns nothing. The Chinese prose uses the English word for a term where no Chinese equivalent is
   clearly better (`seam`, `provider`, `port`, `framing`) rather than inventing one per page.
5. **Nothing in the framework learns a locale.** No `Locale` type, no message-bundle mechanism, no i18n module: the
   translation lives in `site/`, a leaf. ADR-0052's rule — no framework capability arrives *for* this site — is the
   reason a bilingual site costs zero framework code, and it is worth restating because "add Chinese" is exactly the
   request that tempts one.

## Consequences

- Six pages become twelve files. Every future page is written twice, in the same commit, and the drift that creates is
  **prose drift, which no test catches**: the build proves a counterpart exists and that the two links point at each
  other's address, not that the Chinese paragraph still says what the English one says. That is a real, accepted cost;
  the alternatives (A–C) bought automatic alignment by giving up the address, the cache behaviour or the no-JS rule.
- `SiteTest`'s tree walk now covers both subtrees with no special-casing, because `urlFor` derives the address from the
  file's position (`zh/index.html` → `/zh/`) the same way it always derived `index.html` → `/`.
- The README and the guide remain English. The site is the translated surface; a second copy of every repository
  document is the same cost with no reader in mind.
- A third language is `web/<lang>/`, a third table next to `ZH_PAGES`, and a third `hreflang` value — the pairing test
  generalises because it walks the tree instead of enumerating two names. That is the shape to reopen this record on,
  since three hand-maintained trees is where the "single source, generated site" question (ADR-0052's reopening
  trigger) starts to have a real answer.

## Security implications

- No client input selects content: the language is in the path, the path is matched against a table written at
  assembly, and `StaticContent` serves one file chosen by the route (ADR-0051's traversal point unchanged).
- The redirect carries a constant target, so it is not an open redirect; a request to `/zh/../x` never reaches it,
  because routing compares raw segments.
- No secret, no cookie, no storage: a reader's language leaves no trace on the server.

## Testing

`SiteTest` — 17 tests. New in this record: `everyPageHasACounterpartAndBothLinkToEachOther` (walks every HTML file,
resolves its counterpart in the other direction, requires the `hreflang` targets to be exactly the counterpart's
address, and refuses a page that advertises its own language as an alternative); `bothLanguagesAreInTheTable`;
`aChinesePageArrivesAsHtmlWithTheSameFraming`; `theLandingPageMissingItsTrailingSeparatorIsRedirectedToTheCanonicalOne`;
`theRunRecipeNamesEveryJarTheAdaptersLoggerNeeds`. The pre-existing coverage tests were not weakened:
`everyFileUnderWebIsServedBySomeRoute` and `everyInternalLinkPointsAtARouteThatAnswers` now see twelve pages instead of
six because they enumerate the tree.

Two claims this phase had to correct rather than inherit, both found by running what the site tells a reader to run:

- **The printed run recipe was incomplete.** `Site` was started with the classpath exactly as `/docs/reference` prints it,
  and it died before binding a port: `NoClassDefFoundError: ch/qos/logback/core/joran/spi/JoranException`, because
  `logback-classic` needs `logback-core` and the recipe named only the first. In a Maven build the second arrives
  transitively, which is why the reactor never noticed; the recipe is a hand-written `java -cp` list, so it must name
  both — and logback is `test` scope in every module that declares it, so nothing of it is on the site's own runtime
  classpath. ADR-0052's Testing section says the recipe
  "actually starts a working server" — that was true of the classpath that was *run* in 3.6, not of the one that was
  *written down*. Both reference pages now carry `logback-core-1.5.6.jar`, and a test asserts the three artifact names
  appear on the page. The alternative — executing the recipe inside the build — was refused because it would make the
  reactor depend on this machine's local repository layout.
- **ADR-0052's browser claim did not generalise.** The 3.6 record asserts no page-level horizontal overflow at the 488px
  width this environment offers. Measured at that width (client 473px) on the English pages: `document.scrollWidth` 632,
  with the table on `/docs/plugins` 608px wide and the header's nav running to 513 — and the nav overrun was *made worse*
  by the `中文` chip this phase added, which is the reason it got measured at all. Two rules in `site.css` close it:
  `th, td { overflow-wrap: anywhere }` (a cell's longest unbreakable token, e.g.
  `plugins_do_not_reach_the_layers_the_application_assembles`, is what sets a table's minimum width; `anywhere`, unlike
  `break-word`, enters the min-content calculation) and a `max-width: 720px` block letting the nav wrap. Re-measured:
  every one of the twelve pages now has `scrollWidth == clientWidth == 473`, the table 425px, and the nav two or three
  lines at that width instead of a horizontal scroll. The twelve were measured in one pass by loading each in a
  same-origin iframe whose width was padded by the scrollbar's 15px, so each frame laid out at exactly the 473px the
  top-level page gets; a screenshot was not available, since this environment's browser surface reports
  `visibilityState=hidden`, which is why every number here is a measured rect.

The switch itself was driven in a browser rather than reasoned about: clicking `EN` on `/zh/docs/plugins` navigated to
`/docs/plugins` (`lang` becomes `en`, the `h1` becomes "Plugins", the chip becomes `中文 → /zh/docs/plugins`), and the
Chinese glyphs measure as full-width (4 characters at 48px = 192px), which is the difference between a CJK font resolving
and a tofu fallback. No JavaScript is involved in either direction, because the switch is an ordinary anchor.

## Non-goals

Machine translation, a translation memory, a content-negotiation filter, `Vary` handling, a locale-aware
`StaticContent`, per-language analytics, translating the repository's `docs/` markdown, and any framework type added
for the sake of a second language.

## Reopening trigger

- **A third language**, or any language whose pages cannot be maintained in the same commit as the English ones,
  reopens the generation question with a concrete cost attached instead of a hypothetical one.
- **A `Vary`-capable or classpath-serving `StaticContent`** (ADR-0051's deferrals) reopens option A — at which point
  negotiation becomes a decision that can be made correctly rather than approximately.
- **The prose drifting from the code** on either side reopens the "single source" question in ADR-0052.

## Related Decisions

ADR-0052 (the leaf, the no-JS rule, the no-capability-for-the-site rule), ADR-0051 (one handler per file),
ADR-0019 §3 (segment counting), ADR-0029 (a tag from bytes), ADR-0001 (one third-party compile dependency),
ADR-0013 (no hidden input decides an answer).
