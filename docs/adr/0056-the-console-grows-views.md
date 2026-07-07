# ADR-0056: The Console Grows Views, and the Event Bus Gets a Consumer Without Getting an Extension Point

## Status

accepted
Date: 2026-10-07
Decides: that the console becomes a multi-view application rather than one long page; which endpoints it gains; that the
core event bus is consumed by an application component instead of being given a plugin extension point; and that an
application must not keep a second copy of a decision a shipped plugin already answers
Related: ADR-0055 (the leaf this extends), ADR-0017 (the bus, consumerless since 2.6), ADR-0047 (the extension point this
deliberately does not add a second of), ADR-0042 (the framework does not name plugins back, and its mirror: a plugin's
answer is not copied beside it), ADR-0036/0037 (rotation and the 401/403 line), ADR-0018 §4 (the query is the
application's to read), ADR-0013 (an injected clock, not a call to `Instant.now()`), ADR-0052 (no generator, no
templating — still true of these views)

## Context

Phase 4.1 produced one long page with a few buttons. The user asked for more interface and more function.

Two ways that request goes badly: add a client-side framework and a build step, which ADR-0052 and ADR-0055 both refuse;
or add endpoints the framework cannot honestly serve, which turns the demo into a mock-up. The path taken was to spend
the request on things the shipped surface already supports and to see what breaks.

What broke is the more useful half of this record, so it is written up rather than quietly patched.

## Options Considered

**A — a second extension point for the bus** (`EventContributor`, mirroring `RouteContributor`). Refused for now: the
trigger ADR-0047 names is "a component other than the route table with a plugin that needs to contribute to it", and
nothing here is a plugin contributing a subscriber — the consumer is this application, and it can hand its own
subscriber to a bus it constructed. Adding the seam now would be a framework capability proposed before its consumer
exists, which is the failure mode this project has refused since ADR-0008.

**B — server-rendered views** (the application builds HTML per request). Refused: ADR-0052's reasoning holds — it would
put a templating capability into the framework's orbit with one consumer, and the framework's response type is
`byte[]` with no streaming (ADR-0030), which is a poor fit for progressive rendering anyway.

**C — a client-side framework** (Vue or React with a build step). Refused: every answer from this server is one JSON body
per request; a component system over that is a solution to a problem the application does not have, plus a `node_modules`
in a repository whose third-party compile dependency is `slf4j-api`.

**D — chosen: hash-routed views in one hand-written page, more routes over the same contract, and the bus consumed as
plainly as a `DataSource` is.**

## Decision

1. **Seven views, switched by the fragment.** `#overview`, `#reports`, `#session`, `#events`, `#people`, `#answers`,
   `#about`; `show()` toggles `hidden` and sets `aria-current`, and the fragment means the back button and a shared link
   work with no router library and no history API wrapper. Views are sections, not pages, so the answer log — the whole
   point of the application — survives moving between them.
2. **Nineteen routes instead of thirteen.** The additions: `GET /api/reports/{id}`, `DELETE /api/reports/{id}`,
   `GET /api/session/detail`, `POST /api/session/rotate`, `GET /api/users/{name}/permissions`, `GET /api/events`, and
   `q`/`limit`/`offset` on the list. Each exercises a contract behaviour the page can show rather than describe: a 404
   that names no path, a 400 for a non-numeric id (only the application knows its ids are numbers, so only it can answer
   that one), a 204 with no body, a refusal by permission rather than by absence of identity.
3. **The query string is parsed by the application, and the two absences are allowed to differ.** `Request.query()` is
   `Optional<String>` of the raw text (ADR-0018 §4 refuses to decode a query into routing); `Query` turns it into
   parameters, collapses `?q=` and no-`q` into "no filter" *deliberately*, and refuses `?limit=abc` with 400 instead of
   defaulting to 20. A server that guesses what a malformed parameter meant has written an API nobody documented.
4. **`SessionStore.rotate` is shown rather than asserted.** `POST /api/session/rotate` returns the new token *and* the
   superseded value, and the page keeps a button that sends the old one on purpose. The reader then sees `401` for a
   credential the client still holds — rotation as a server-side fact (ADR-0036), not as a client that forgot.
5. **The expiry is stated by the server through an injected `Clock`.** `GET /api/session/detail` answers `expiresAt`,
   `secondsLeft` and `sessionsOpen`. The clock arrives in the constructor, so the test pins both sides to a fixed
   instant and asserts exactly `1800` seconds instead of a wall-clock range — the same reason `StaticContent` takes no
   clock at all (ADR-0051: no clock in the answer, no clock in the assertion).
6. **The event bus gets a consumer, and no new API.** `Audit` creates an `EventBus`, subscribes its own reader to each
   concrete event class, and publishes onto it from the handlers that own those events — including the plugin's, which
   publishes from inside a route it contributed itself. Two facts that this had to be written down for: the bus routes on
   the event's exact runtime type, so subscribing to an interface receives nothing; and a bounded trail (fifty entries,
   oldest dropped) is a decision the application makes, not something the framework offers.
7. **The audit trail cannot see refusals, and does not pretend to.** `SecurityMiddleware` answers 403 before any handler
   runs, so the application never learns a refusal happened and the trail holds no "denied" entries. A first draft of
   this phase had a `recordRefusal(...)` helper for exactly that — dead code that could never be called, and an event
   describing something nobody observed. It is removed, and the limit is printed on the page instead of being faked.
8. **One source of truth for authorisation, learned the hard way.** 4.2 first wired an `Authorization.of(Map…)` built
   beside the shipped `UserPlugin`, which itself implements `Authorization` by reading the grants table through
   `UserRepository`. The two disagreed about `cy`: the static map said `user:read`, the database said nothing, so the
   page displayed "no permissions" while the same account was successfully reading `/api/events`. The static table is
   gone — the plugin is the `Authorization` the routes wrap, and grants exist in one place: the table `UserRepository`
   writes. This is ADR-0042's direction inside one application: do not copy the answer a component already gives.
9. **Presentation order is decided once.** `/api/session` and `/api/users/{name}/permissions` returned the same account's
   grants in different orders — one in the repository's row order, one sorted — which a reader comparing the two panels
   would experience as the server contradicting itself. Both now go through `People.grantsOf`, sorted.
10. **Three accounts, each with a real difference.** `ada` reads and writes reports, `bob` holds nothing, `cy` reads
    reports and may ask about other people and the trail. The header offers one-click sign-in for each, because the
    distinction the framework makes (401 for no identity, 403 for an identity without the grant) is only visible if the
    same request can be made as three identities.

## What this proved, and what it did not

It proved that the second view, the fourth parameter style, the audit stream and the paging headers all arrived without a
framework change: every one of them is composition of what 2.x and 3.x shipped. That is the ADR-0016 condition applied a
second time — a consumer asking, and the framework staying the same size.

It did not exercise, and does not claim: cookie sessions (ADR-0055's refusal stands), cross-process sessions
(`SessionStore` is one JVM's memory), streaming responses or large-file downloads, server-rendered HTML, or the bus as a
plugin extension point — the seam option A names, which stays a trigger until a plugin needs to subscribe rather than an
application choosing to.

## Consequences

- The console's tests go from 12 to 22, all over a real socket except one (`theDetailStatesAnExpiryMeasuredByTheInjectedClock`
  calls the wrapped handler directly, because it is testing the clock, not the transport).
- The bus now has a consumer, so ADR-0017's "consumerless since 2.6" line is out of date; the record of that is this one,
  and the event-bus-as-second-extension-point question stays open with a sharper trigger: it fires when a *plugin* wants
  to subscribe, not when an application does.
- Two claims in ADR-0055's neighbourhood are corrected rather than reworded: the console's authorisation is the shipped
  plugin's, and the audit trail has a hole in it (decision 7) that no amount of application code can close without a
  framework hook.
- The page is one HTML file, one script and one stylesheet still. Growing the interface did not grow the build: there is
  no bundler, no transpiler and nothing in `app/` that Node or npm is needed for.

## Security implications

- Rotation and invalidation are server-side removals; the page keeps the superseded value only so the reader can prove it
  is dead, and it is never written to storage.
- The trail is gated by `user:read` rather than by "is signed in": it names other people's actions, and an authenticated
  caller is not automatically an authorised one — the same distinction the rest of the application exists to display.
- Every parameter that reaches SQL does so as a bound `?` value; the one place a number is used in the query text is
  `LIMIT`/`OFFSET`, which are parsed to `int` first and refused with 400 before that. No string from a request is ever
  concatenated into SQL.
- Response bodies are rendered with `textContent`; the only parse is `JSON.parse`. A report body is text the application
  cannot vouch for, so it is never markup.
- `GET /api/events` and the token answers carry `Cache-Control: no-store`: neither is a response an intermediary should
  keep a copy of.

## Testing

- `ConsoleTest` — 22 tests, adding `Queries` (filtered list states a total the page hides; empty and absent `q` are the
  same answer; `limit`/`offset` page without losing the count; a malformed limit is 400; one row reads, an absent id is
  the plain 404, a non-numeric id is 400; delete goes 204 then 404 then 403 for a reader without write) and `Lifecycle`
  (rotation makes the old token anonymous; the detail's expiry is measured by the injected clock, pinned to exactly 1800
  seconds; the trail is gated by `user:read` and contains the plugin's own event; another account's grants need
  `user:read` and read in the same order twice).
- In a browser at this environment's width: seven views switch, `overflow == false` at `clientWidth` 977, paging moved
  from `2,3` to `4,5` and back, ada's `/api/events` renders the 403 explanation, cy's renders 6 events, and cy asking
  about `ada` returns `report:read,report:write`. Pointer events still do not reach the panel (it is hidden here), so
  these flows were driven through DOM events against the real listeners; the click path remains unverified.
- Full reactor after this phase: see CHANGELOG for the measured counts.

## Non-goals

A `CredentialSource` seam, an `EventContributor` extension point, a client-side framework or build step, session
persistence, a search index, server-rendered HTML, and a trail that claims to record refusals.

## Reopening trigger

- **A plugin that needs to subscribe to events** reopens option A — `EventContributor` — which is ADR-0047's named
  "second extension point" condition arriving for real rather than being proposed.
- **An authorisation audit that has to exist** reopens the hook decision: the trail cannot see refusals today
  (decision 7), and closing that means giving the decorator a way to report, which is a framework change with its own
  record.
- **A page that must navigate rather than fetch** reopens ADR-0055's option A (cookies) unchanged.

## Related Decisions

ADR-0055 (the leaf, the bearer choice, the copied design system), ADR-0017 (the bus this consumes), ADR-0047 (the seam
this does not add), ADR-0042 (the direction this applies inward), ADR-0036/0037 (rotation and the two refusals),
ADR-0018 §4 (the query belongs to the application), ADR-0013 (configuration and time arrive at assembly).
