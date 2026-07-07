# ADR-0057: Seven Pages, and the Credential Problem That Arrives With Them

## Status

accepted
Date: 2026-10-07
Supersedes: ADR-0055's decision 3 (the bearer token lives in one JavaScript variable) and ADR-0056's decision 1 (seven
views inside one document). Both records keep everything else, including the refusal of a cookie credential source, which
this phase re-affirms under worse conditions rather than quietly reversing
Related: ADR-0055 (the leaf, the bearer choice, the copied design system), ADR-0056 (the views and the audit stream),
ADR-0054 (a folder where the host decides the URLs, against an application where it does not), ADR-0051 (one handler per
file), ADR-0028 (HEAD is registered, not folded in), ADR-0033 (policy is composed by wrapping, not attached later),
ADR-0036 (rotation is a server-side removal), ADR-0030 (no streaming, so one JSON body per request)

## Context

The user asked for the console to be split into pages by function instead of one long page with a tab list.

That request has one consequence that the request itself does not mention: separate pages are separate documents, and
loading a new document destroys every JavaScript variable the previous one held. ADR-0055 chose an in-memory bearer
token precisely because a script-readable store is where a token goes to be stolen. Keep the token there and signing in
again on every navigation, or move it somewhere that survives and re-examine the claim.

There was a third shape available and it is worth naming, because it is the one that looks easiest and is the worst:
keep one document and fake pages inside it, which is where ADR-0056 had already gone.

## Options Considered

**A — a cookie credential source, so the browser carries the session across documents automatically.** Again refused on
the same ground as ADR-0055: `SecurityMiddleware.credentialFrom` reads `Authorization` and is private by design, and
widening it is a framework capability arriving because one demo wants convenience. It is also the *correct* production
answer, which is why it stays a reopening trigger with a name rather than being dismissed — an `HttpOnly` cookie removes
exactly the risk this decision accepts.

**B — re-authenticate on each page.** Refused: a session that dies at every navigation is not a session, and the
rotation demonstration (ADR-0056 decision 4) depends on holding one credential across requests.

**C — a hidden iframe as a stable parent, with the pages inside it.** Refused: that is ADR-0056's single document with an
extra step, and the address bar would lie about where the reader is.

**D — chosen: seven files, and the token in `sessionStorage`, with a response policy that restricts what can execute.**
The storage choice is a real cost; the mitigation is taken seriously rather than assumed.

## Decision

1. **One file per page, and the addresses are the application's.** `/`, `/reports`, `/session`, `/events`, `/people`,
   `/answers`, `/about`, each registered for `GET` **and** `HEAD` against its own file through the one-handler-per-file
   rule (ADR-0051), and each one a *clean* address: `/reports.html` is a 404, on purpose. This is the exact inverse of
   ADR-0054's situation, and the contrast is the point — a folder on a static host must keep the extension because the
   host cannot rewrite, while an application chooses its URLs and says so in code. The router answers a HEAD probe
   because a page that refuses one reads as absent to a crawler and a cache (ADR-0028).
2. **The token moves to `sessionStorage`, and the answer log moves with it.** Tab-scoped, gone when the tab closes, never
   attached to a request automatically — so still no CSRF surface, and the rotation demo still works because the value is
   read explicitly and put in a header. What it is not: memory. A script that runs on this origin can read it, and no
   server-side typing prevents that. Saying so is decision 3.
3. **Every page and asset is served with a policy that admits no inline script and no foreign origin.**
   `SecurityHeaders.of(...)` is a handler decorator — the same composition pattern ADR-0033 uses for security — that adds
   `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; …; frame-ancestors 'none';
   base-uri 'none'`, plus `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`. That header is what makes
   decision 2 survivable rather than merely convenient: with no inline script, no `eval`, no third-party origin and one
   hand-written `app.js`, the number of ways an injected script can appear on these pages is the number of ways the
   application itself can be made to run one. It is not a proof of safety; it is the reason the risk is taken.
4. **Consequently there is exactly one script file, and it branches on the page.** `document.body.dataset.page` selects
   which controls get wired, because an inline `<script>` per page is now unshippable and a `<script src>` per page would
   mean seven files that each re-declare the same fetch wrapper and the same log. The wiring is a consequence of the
   header, and is written that way rather than worked around.
5. **The answer log is capped in storage at fifty entries and truncated to 400 characters per body.** A log that survives
   navigation is also a log that survives until the browser complains; the bound is the application's decision, mirroring
   `Audit.LIMIT` on the server side for the same reason.
6. **Thirty-one routes instead of nineteen.** The page and asset routes are the difference, and they are registered from
   two lists (`PAGES`, `ASSETS`) with one loop, so a page added without a route — or a route without a page — fails at
   assembly rather than as a blank window.
7. **The state line is written from the server's answer, on every load.** A page that prints 已登录 because a value is
   present in `sessionStorage` is asserting a fact it does not know: the server can have expired that token or rotated it
   away while the tab stayed open. So `checkSession()` asks `GET /api/session` on load, prints what comes back, drops a
   credential the server no longer honours, and then hands the page its own loader — visiting `/reports` signed-in shows
   the table instead of a placeholder waiting for a click. The cost is one request per document, which is the same cost
   every interaction already pays: the contract answers with a `byte[]` and refuses to stream (ADR-0030).
8. **The console's measure is its own, and this supersedes that clause of ADR-0055.** Decision 4 there said the console
   copies the documentation site's design system — the same six tokens, the same band header, the same measure. The tokens
   and the table-and-notes treatment are still that stylesheet's; the measure is not, and cannot be, because a docs page
   holds one kind of block while a console stacks an account row, a state line, a form and a table on one screen. Two
   widths there put the header's text 70px left of the page title's at a 962px viewport, which reads as a page that cannot
   decide where it begins. Every band is now one width (`52rem`), so every left edge on a page is one x. What ADR-0055
   actually decided — copy the design system rather than share the file, because a static folder and a served application
   are two deployment shapes — stands unchanged.

## What this proved, and what it did not

It proved that splitting an application into pages costs the framework nothing: seven files, one decorator, a router that
takes both verbs, and no new capability anywhere in `framework/`.

It did not make a script-readable token safe, and this record does not claim otherwise. The residual risk is: one
injected script on one of these origins can read a live credential for as long as the tab is open. The two things that
would close it are named above (a cookie credential source, which is the framework's decision to make, and ADR-0043's
documented refusal to add a JSON layer that could be coaxed into a different shape) and neither was taken here.

## Consequences

- `app/` has 7 HTML files, 1 script, 1 stylesheet, and `SecurityHeaders` as its only piece of response policy. Still no
  build step, still nothing that needs Node.
- The framework gained no hook this time either: the CSP header is a decorator in the leaf, which is the third instance
  (after ADR-0033's decorator and ADR-0047's extension point) of the argument that an application states its own policy by
  wrapping what it registers.
- A reader who bookmarks `/reports` and opens it in a *new tab* is signed out. That is the correct behaviour of a
  tab-scoped store and the page says so in the header strip rather than pretending otherwise.
- The multi-process limits do not change: `SessionStore` is one JVM's memory, and `sessionStorage` does not make it
  shared.

## Security implications

- **Accepted risk:** a session token is readable by same-origin script for the lifetime of the tab (decision 2), mitigated
  by a policy that admits no inline or foreign script (decision 3) and by the application's own pages containing neither,
  asserted by a test rather than believed.
- **Unchanged:** no cookie, therefore no cross-site request carries the credential, therefore no CSRF surface; the
  response that issues a token carries `Cache-Control: no-store`; refusals are the decorator's answers, and the audit
  trail still cannot see them (ADR-0056 decision 7).
- **New:** `frame-ancestors 'none'` (these pages must not be framed by anything, including each other), `base-uri 'none'`
  (no injected `<base>` to redirect relative URLs), `nosniff` (a text/html answer is the application's, not a misread
  upload), `Referrer-Policy: no-referrer` (the path is not for strangers, and a token never appears in one anyway).

## Testing

- `ConsoleTest` — 25 tests (three new): `everyPageIsServedWithAPolicyThatAllowsNoInlineScript` (all seven addresses, `GET`
  and `HEAD`, `text/html; charset=utf-8`, a policy naming `script-src 'self'` and *not* containing `unsafe-inline`,
  `nosniff` present — asserted per page, because a policy that covers `/` and forgets `/reports` is a policy with a hole
  shaped like the session); `thePagesContainNoInlineScriptOrEventHandler` (the files cannot defeat the header they are
  served with); `aFileNameIsNotAnAddressTheApplicationServes` (`/reports.html` and `/index.html` are 404 — the clean
  address is a decision, not an accident).
- In a browser: sign in on `/`, navigate to `/reports` — the header still reads `已登录：ada`, the list answers 200 with
  `X-Reader: ada`, and the answer log on `/answers` still holds the login from the previous document. No horizontal
  overflow at 962px. Pointer delivery is still unavailable in this environment, so the navigation was performed through the
  page rather than by a click.
- Measured after decision 8, at the same viewport: `.brand`, `.who`, `h1` and `.panel` all begin at x=89; the filter's two
  inputs and its submit button share a bottom edge at y=452; `ADR-0047` occupies one line (77x19) where it had broken into
  `（ADR-` / `0047）`; the session table is 191px tall where the uppercase field names had made it 364px; and
  `scrollWidth == clientWidth` on all seven pages.
- Full reactor after this phase: see CHANGELOG for the measured counts.

## Non-goals

A cookie credential source, `HttpOnly` sessions, CSP nonces or hashes (they need a build step to compute per response),
per-page script files, a client-side router, server-side rendering of these pages, and cross-tab session sharing.

## Reopening trigger

- **A deployment beyond `127.0.0.1`** reopens option A: the moment a real user on a real network holds this session, the
  answer is an `HttpOnly` cookie and a `CredentialSource` seam in `SecurityMiddleware`, with CSRF protection in the same
  decision — not another CSP header.
- **Any need for inline script** (a JSON blob bootstrapped into the page, a maps SDK) reopens the nonce/hash question, and
  with it the "no build step" rule this repository has kept since ADR-0001.
- **A second process behind the console** reopens `SessionStore`'s in-memory assumption, which `sessionStorage` cannot fix.

## Related Decisions

ADR-0055 (the token decision this replaces, and the refusal it re-affirms), ADR-0056 (the views this turns into files),
ADR-0054 (the other half of the URL question), ADR-0051 (one handler per file), ADR-0033 (policy by wrapping),
ADR-0001 (the dependency question a nonce pipeline would face).
