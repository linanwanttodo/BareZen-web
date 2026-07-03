# ADR-0039: A Transaction Is an Object the Caller Receives and Never Decides; commit and rollback Are Package-Private, and Nesting Opens a Second Independent Transaction

## Status

accepted — 2026-10-03

Related: ADR-0038 (the JDBC boundary this lives inside), ADR-0033 (a context arrives as an argument, never as ambient state — the same delivery decision, made again), ADR-0040 (the schema side, which reuses the ownership rule for its own connection).

## Context

`Database.transaction` is the only place this framework decides a unit of work's fate, and every persistence bug that
survives into production is a decision made in the wrong place: a repository that commits what a service started, a
"nested" call that quietly joins or quietly doesn't, a connection left auto-committing in a pool. The directive asked
three questions — where does the transaction boundary live, can a repository decide, what happens on nesting — and
the answers had to be mechanical, because "the service layer should own transactions" is a review rule, and review
rules lose to the first deadline.

The measurements that shaped this: H2 commits DDL implicitly — measured twice on this build, a `CREATE TABLE` inside a
rolled-back transaction survives on the same connection and on a new one (ADR-0040 records the numbers, the
contract rewrite they forced, and the rule that other products' DDL behaviour is asserted per product from
measurement, not assumed). A transaction whose rollback was asserted only through DDL would therefore prove nothing;
the isolation tests here use DML — the kind the same measurements show rolls back honestly — so what is claimed is
what was actually verified.

## Problem

1. Who owns a unit of work — the code that starts it or the code closest to the SQL?
2. How is "a repository must not commit" enforced without a convention that can be broken silently?
3. What exactly does a second `transaction(...)` call made inside a running one do?
4. Which failure paths must still release the connection, and how is "still" proven rather than promised?

## Options Considered

**A — ambient transactions (a thread-local current transaction; repositories participate implicitly). Rejected.**
This is Spring's `@Transactional` shape, and the max stops here twice over. It hides the unit of work from its own
callers — a method's transactional behaviour becomes invisible in its signature — and it makes the nesting question
the worst kind of question: the answer depends on a propagation attribute a reader cannot see at the call site. The
data module bans `ThreadLocal` by rule (ADR-0038's no-ambient list), so this option is not merely unfashionable here,
it is unbuildable.

**B — repositories own their transactions, services coordinate through a returned handle.** Rejected because it
inverts the ownership the phase asked about: the code that composes several repository calls into one unit of work is
the only code that knows where the unit begins and ends, and if repositories commit, that caller cannot make its
composition atomic at all. A `Transaction commit()` public API would put the decision one `git add` away from every
repository author.

**C — the callback owns the scope, the framework owns the decision, chosen.** `Database.transaction(callback)` opens
the connection, hands the callback a `Transaction` as a parameter, and commits when the callback returns normally.
The caller expresses the unit of work; only `Database` can decide its fate, because `commit`/`rollback` are
package-private on `Transaction`. A repository that receives the `Transaction` and tries to commit it gets a compile
error — the enforcement mechanism the Context asked for.

## Decision

**Ownership.** A service method owns a unit of work: one `database.transaction(...)` call, and the `Transaction`
parameter passed to every repository call inside it. `Transaction` has no static accessor, no registry, no ambient
lookup — the delivery decision of ADR-0033 applied to persistence, with the same reason: a value reachable by magic
is a value whose owner nobody can see.

**The decision table**, tested as a table (`TransactionTest.DecisionTable`, six tests):

| callback outcome | what runs | what the caller gets |
| --- | --- | --- |
| returns normally | `commit()` | the callback's result, durable |
| throws `SQLException` (raw JDBC) | `rollback()` | `DataAccessException` ("transaction failed and was rolled back", cause preserved) |
| throws a runtime exception or `Error` (application or repository failure) | `rollback()` | the failure itself, untranslated (ADR-0038's non-translation rule) |
| returns, commit fails | rollback-then-release | `DataAccessException` naming the commit failure |
| rollback itself fails | release anyway | original failure with the rollback failure `addSuppressed` |

In every row the connection is closed before `transaction` returns. The commit-failure row is sequenced by the
recorder, not asserted from intent: `decisionSequence()` proves the JDBC calls were
`[setAutoCommit(false), commit, rollback, setAutoCommit(true)]` — a failed commit rolls back before the connection is
returned to its pool, because a connection whose commit threw cannot be assumed to have committed nothing
(`commitFailureRollsBackAndReports`). The release path restores `setAutoCommit(true)` best-effort before handing the
connection back (`Transaction.release`), so a pooled connection is never returned dirty; if that restore fails it is
swallowed deliberately — replacing a decided commit or rollback outcome with a cleanup failure would hide it.

**Lifetime.** A `Transaction` is valid only inside the callback that received it; every method fails with
`DataAccessException("transaction already committed or rolled back")` once decided (`aTransactionCannotOutliveItsCallback`).
That is the observable half of ownership: a repository cannot hold a connection past its boundary because the object
holding it stops working at the boundary.

**Nesting — the explicit answer.** `Database.transaction` is the only way to obtain a transaction and it does not
notice whether one is already open: a second call, from the same thread or anywhere, opens a **second, independent
transaction on a second connection**. The two never see each other's uncommitted work (H2 measures this: an outer
insert is invisible to the inner transaction until commit), neither can decide the other's fate, and no savepoint
exists (`nestingOpensASecondIndependentTransaction` pins all three claims). Nothing here is `REQUIRED`-style implicit
joining — an application that wants its inner call to join the outer unit passes the same `Transaction` object, which
is how the design expresses composition: by the parameter, visibly. `Propagation` as a concept does not exist because
there is only one propagation rule, and it is written in the signature.

## Consequences

Positive:
- "repository bypass" is structurally impossible for the commit path: `commit`/`rollback` package-private means the
  only framework type that can decide is `Database`, and a reflection probe keeps the visibility itself under test
  (`theDecidersAreNotVisibleOutsideTheModule` resolves `query` publicly while `getMethod("commit")` and
  `getMethod("rollback")` throw `NoSuchMethodException`) — the probe is the regression guard against a later "make
  it public, someone needed it" commit.
- The decision table is total: every exception path has a named row, a JDBC call sequence, and a test; there is no
  "undefined when the callback throws X" cell left for an incident to discover.
- No ambient state means the transaction's lifetime is readable at the call site — the same argument ADR-0033 made
  for security contexts, now covering the resource with the worst leak history in the industry.

Negative, stated because they are real:
- Nesting is unsupported rather than resolved: a service that calls another service's `transaction(...)` gets two
  units of work, and if the outer fails, the inner's committed part stays committed. That is a real hazard the design
  answers by being loud (javadoc + pinned test) rather than by pretending; a future consumer with a genuine need
  reopens the question — see Reopening trigger.
- The caller must return through `transaction`, not keep the lambda open across threads — no `Transaction` may be
  stored and used later; the lifetime guard makes that fail fast rather than silently (`aTransactionCannotOutliveItsCallback`).
- `Error` rolls back and rethrows: a `StackOverflowError` inside a unit of work now costs one JDBC rollback before it
  kills the thread. Cheap, but the choice should be visible rather than discovered.

## Security implications

- A repository cannot escape its service's unit of work, so "this write is inside the permission check" stays true by
  construction, not by review — the transaction boundary and the security boundary compose by the same
  callback-parameter argument that ADR-0033 uses for requests.
- No ambient transaction means no cross-request bleed: a thread cannot inherit a previous request's open unit of
  work, which is the standard thread-pool data-leak story this design forecloses.

## Testing

`TransactionTest` (13): DecisionTable 6 (every row above, recorder-sequenced), Isolation 2 (uncommitted work private;
rolled-back DML leaves no trace), Ownership 4 (visibility reflection probe, dead-after-decision guard, one
connection per unit of work via the recorder, nesting independence), Concurrent 1 (eight threads on one `Database`,
eight independent units, no cross-talk). Legacy probes: M1 (delete rollback), M2 (delete commit), M3 (commit after
exception), M4 (no rollback on commit failure), M7 (drop the lifetime guard), M8 (never `setAutoCommit(false)`),
M14 (failure path leaks the connection), M15 (make commit public again) — all killed, tree restored byte-identically
(`/tmp/p212/mut.py`).

## Non-goals

- No propagation semantics, no savepoints, no distributed transactions, no XA. Each assumes an ambient model or a
  transaction manager; both are ADR-shaped different frameworks, not this one.
- No declarative transactions (annotations, proxies). A unit of work invisible in the signature fails the same
  review the Context describes.
- No isolation-level control. The driver and pool already decide this on the `DataSource`; a second knob that
  overrides a DBA's configuration is not this boundary's call to make.

## Reopening trigger

- A real application pattern where a nested unit of work must join the outer one (a checkout flow composing
  inventory and payment services) reopens the "pass the object explicitly" rule — the alternatives at that point are
  ambient state (rejected) and signature threading all the way down (verbose but visible); whichever loses the
  argument should be recorded, not assumed.
- A driver whose commit-failure behaviour differs from H2's measured re-rollback tolerance reopens row 4 of the
  decision table; the sequence assertions in the recorder tests are where that difference would surface first.

## Related Decisions

ADR-0038 draws the boundary whose one decision point this is, ADR-0033 supplies the argument-arrives-not-ambient
shape reused here, ADR-0040 applies the same ownership rule to the migrator's long-lived connection (the framework
owns it, the migration never decides it), and ADR-0024/ADR-0011 established that a resource's owner is stated and
closable — the executor-ownership rule this decision table continues with a connection instead of a thread pool.
