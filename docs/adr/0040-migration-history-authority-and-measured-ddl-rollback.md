# ADR-0040: The Migration History Table Is the Framework's Authority, a Recorded Version Means Its apply Completed, and Recovery After a Failure Is Re-Run-and-Repair — Because This Build Measured That DDL Does Not Roll Back

## Status

accepted — 2026-10-03

Related: ADR-0038 (the boundary the migrator sits on), ADR-0039 (the ownership rule reused for the run's connection), ADR-0003/ADR-0004 (startup is ordered and its failure is preserved — a migration run is a startup step), ADR-0013 (the migration list arrives from assembly, never from framework-side discovery).

## Context

Every application that touches a database eventually asks the same question at startup: which changes have already
run? The naive answers all fail in specific, well-documented ways — run them all again and the second `CREATE TABLE`
throws; track "the last version" as a scalar and lose which one it was; trust the schema's shape instead of a record
and discover that a half-failed migration left a table whose version nobody recorded.

Phase 2.12's reconnaissance measured the ground before designing it, and the measurement changed the design. The
initial contract said "a migration and its history row are applied atomically — a failed migration leaves no trace."
Before that sentence could ship, two standalone programs (`/tmp/DdlTx.java`, `/tmp/DdlTx2.java`, both run against the
fetched H2 2.2.224 on JDK 17) falsified it:

- a `CREATE TABLE` executed inside a transaction that is then rolled back **still exists on the same connection**
  (printed `same-connection after rollback: true`);
- it **still exists in a new connection** opened afterwards (printed `new connection after rollback: true`);
- the control case proves the harness rather than the exception: DML in the same rolled-back transaction is gone
  (printed `control DML rows after rollback: 0`).

H2 commits DDL implicitly, as MySQL and MariaDB are documented to do. A migration model that promises rollback of
schema changes would promise something the framework cannot deliver on the very engine its own tests run on — the
exact "it worked against H2 in the tests" failure ADR-0038's Context names. What the framework *can* promise is about
its own table, and that is what this record chooses instead.

## Problem

1. What identifies a migration, and what makes that identity survive a redeploy?
2. What does "already applied" mean when the schema and the record can disagree?
3. Which states must a run handle without human intervention, and which must refuse loudly?
4. What does a failed migration leave behind, honestly — on the measured engine and on others?

## Options Considered

**A — adopt a migration tool (Flyway/Liquibase shapes, or a wrapper over one).** Rejected on the dependency gate
before any other argument: a new runtime dependency needs an ADR's worth of justification this one does not have, and
the value a wrapper would add over the model below is checksums and Java-migration parsing — features whose absence
is a deliberate non-goal here, not an oversight.

**B — keep the atomic promise and paper over the exception (per-dialect branches: `IF NOT EXISTS` rewriting,
auto-commit DDL guards, "skip DDL statements" heuristics).** Rejected because it hides the failure it cannot fix. A
framework that silently rewrites a migration's SQL to dodge an engine's behaviour has taken on the engine's dialect —
the PostgreSQL/H2 folding difference the history-table probe already shows (measured on H2: unquoted identifiers fold
to upper case; documented — not measured — on PostgreSQL: lower case) would become a per-product branch in framework
code. Branches like that are how "supports everything" turns into "tests everything."

**C — history-table authority with an honest failure story, chosen.** The framework records a version only after
`apply`, history insertion and commit all return normally. A failed commit response can still have committed, so the
recorded table — not the exception alone — is the authority. Everything else — whether the schema is intact after a
failure — is stated as the product's answer, with the contract rewritten around re-runnability rather than rollback,
which is the model ADR-0038 §Testing names re-run-and-repair.

## Decision

**One table, one rule.** `barezen_migration_history(version BIGINT PRIMARY KEY, description VARCHAR(512) NOT NULL,
applied_at TIMESTAMP NOT NULL)`, created with `CREATE TABLE IF NOT EXISTS` — the plainest SQL that works, and the
primary key is the duplicate guard at the database level where the in-list check is the earlier, kinder one. For each
pending version, in ascending order: `setAutoCommit(false)`, `migration.apply(connection)`, `INSERT` the history row,
`commit`. The row shares the transaction with the change, which is what makes the recorded direction the trustworthy
one; the unrecorded direction is not a rollback promise, and the contract says so.

**Identity is the version number, never the position in a list.** `migrate` sorts by version and ignores the order it
was given — migration code that has to guess a caller's list order is the bug version numbers exist to remove. A
version is assigned once and never reused, renumbered, or edited after it has run; that is a rule for applications,
stated in `Migration`'s contract rather than enforced here, because enforcing it would mean the checksum option B
declined.

**The six states, each tested** (`MigratorTest`, the table is the design's surface):

| state | behaviour |
| --- | --- |
| fresh database (no history table) | table created, every migration runs in version order, all returned as applied |
| existing database | only unrecorded versions run, returned ascending |
| already applied | skipped silently and cheaply — `migrate` is meant to run at every startup |
| duplicate version in the list | `IllegalArgumentException`, refused before any database contact |
| failed migration | rollback is attempted, no later version runs, `DataAccessException` names the version; history remains the authority (a failed commit response has an uncertain outcome), and the schema may not roll back, per the measurement above |
| out-of-order pending version | refused before anything runs: a pending version ≤ the highest applied one throws rather than silently skipping (rewrites history) or silently applying (runs 7 after 12 against a schema that assumed the opposite) |

**Two exception families, split by who fixes them.** `IllegalArgumentException` for a non-positive version, a blank
description, a duplicate in the list — code errors fixed by editing the list; `migrate` validates before it opens a
connection, and the refusal probe asserts the history table does not exist afterwards
(`nonPositiveVersionFailsBeforeTheDatabase`). `DataAccessException` for anything that is the database's: acquisition,
a migration's own `SQLException`, the out-of-order refusal, a failed history read.

**Order matters in the run, and one bug in that ordering was caught by a test.** The out-of-order check first reads
the recorded versions, filters the list to what is *pending*, and compares the smallest pending version against the
highest applied one. Checking the first element of the full sorted list instead — the first draft — would refuse every
up-to-date application whose lowest version is below its highest applied one, i.e. the normal case at every startup.
`anUpToDateListIsNotAnInversionJustBecauseItsFirstVersionIsLow` pins the corrected shape so the regression cannot
return.

**A migration cannot decide the run's connection.** `Migrator` holds the long-lived connection across the ordered run
through the same package-private seam `Database.transaction` uses, and `Migration`'s contract forbids committing,
rolling back, or closing what it was handed — ADR-0039's ownership rule applied to DDL, with `apply`'s raw
`Connection` as the deliberate exception ADR-0038 records (a migration speaks the database's own language; nothing
reaches for the connection, it arrives as a parameter).

**The measured exception, written into the contract.** Because the framework cannot promise schema rollback: a failed
version stays unrecorded and **re-runs at the next startup**, and the product may still hold the first attempt's
trace — the failed-migration probe measures exactly that on H2 (`orphan` survives the rollback, asserted as
*measured, not assumed*). The contract's answer is a rule for migration authors, stated in `Migration`'s javadoc and
demonstrated by the recovery test: write DDL steps idempotently (`IF NOT EXISTS` and its equivalents), and a retried
migration completes rather than colliding with its own leftovers. Recovery is re-run-and-repair against the history,
not a rollback guarantee — that sentence is the product of the two measurements in the Context, recorded rather than
smoothed over.

## Consequences

Positive:
- Startup is safe to repeat. `migrate` at every boot is the documented usage and the tests prove the cost of an
  up-to-date database is one read.
- The authority is a table an operator can read: `SELECT * FROM barezen_migration_history` answers "what ran"
  without the application's source. `history()` exposes the same facts typed, live, across connections.
- The failure story is one an operator can act on: history says the last good version, the next startup re-runs the
  failed one, and idempotent DDL is the documented way for that retry to be routine.
- No dialect branches. One engine measured, the exception named in the contract, and the cross-product question
  raised as an ADR when a second product enters the build rather than answered by a `switch` on a product name.

Negative, stated because they are real:
- A non-idempotent migration that fails halfway can leave the retry colliding with its own orphan (the `CREATE TABLE`
  that now already exists fails on the second attempt). The rule that prevents it is stated in the contract, not
  enforced by code — the framework cannot read an author's intent — so the honest form of the guarantee is
  "recorded ⇒ applied", with "applied ⇒ schema intact" explicitly the product's answer.
- The run holds one connection across every migration it applies. A long migration holds it long; the framework
  says no timeout because it cannot know what an index rebuild costs on a customer's machine.
- `applied_at` comes from the migrator's `Clock` — injectable, defaulting to `Clock.systemUTC()` — so a history row
  is as trustworthy as the host's clock. It is an audit aid, not a security record.
- Version identity is unenforced after the fact: editing an applied migration's SQL is invisible here. The checksum
  design that would catch it was declined (option B), and the rule is stated instead.

## Security implications

- `history()` is read-only by construction: the typed list is the only view of the table, so an application cannot
  write a history row behind a migration's back — the record cannot drift from the rule that maintains it.
- No discovery, no scanning, no classpath magic: the migration list arrives from assembly code (ADR-0013), so a
  migration that "appeared" on the classpath is not a thing this design can run, and nothing reaches a database from
  an environment variable (ADR-0038's environment-reading ban covers the migrator too).
- No logging in main sources, so a failed migration's SQL — which may contain backfill data values — reaches the
  caller only as a translated exception naming version and description, never to a log file the framework wrote.

## Testing

`MigratorTest` (23): Fresh 3 (order proven through a `tracked()` decorator, not asserted from a list), Existing 2,
Duplicate 1, Failure 5 (history empty, run stopped, **orphan survives — the measured claim**, commit and rollback
failures both reported, lost commit response resolved from history), OutOfOrder 3 (lower
pending refused, higher pending after a gap allowed, the up-to-date-list regression pinned), Validation 3, History 3,
Construction 2, RunEffect 1. The mutation harness (`/tmp/p212/mut.py`) covers this ADR's decisions directly: M9
(apply skipped but recorded) fails Fresh; M10 (descending run) fails the order probe; M11 (no out-of-order refusal)
and M12 (no duplicate check) fail their states; M13 (boundary relaxed to `version < 0`) fails Validation — all killed,
tree restored byte-identically. The two Context measurements are reproducible with `/tmp/DdlTx.java` and
`/tmp/DdlTx2.java` against the fetched H2 jar.

## Amendment (2026-11-27): a failed commit has an uncertain outcome, and rollback evidence is retained

`applyOne` previously called `rollbackQuietly()` and then claimed the migration "was rolled back", even when rollback
itself failed. A `SQLException` is now translated with the message "rollback was attempted"; a rollback `SQLException`
is attached to that `DataAccessException` as suppressed. Unchecked migration failures still propagate unchanged and
now retain the rollback `SQLException` as suppressed as well. No later migration runs after either failure.

The wording also marks a real limit: a driver can fail the commit response after the database has committed, so a
rollback cannot prove that the version is absent. The history table remains the authority. A later `migrate` call reads
it and either skips a recorded version or re-runs an unrecorded one; callers must not infer the outcome from the
exception alone. The new recorder test injects commit and rollback failures before the real commit, asserts one
suppressed rollback `SQLException`, an empty history and no leaked resources. A second recorder test lets the real H2
commit succeed before the proxy reports failure: history contains version 1 and the next `migrate` skips it. That test
turns the uncertain-outcome rule into an executable fact. `MigratorTest` grows from 21 to 23.

## Non-goals

- No down/undo migrations, no rollback scripts. A failed version re-runs forward; a broken schema is repaired by a
  new version, which keeps the history append-only and the recovery story one-directional.
- No migration checksums, no Java/SQL file parsing, no resource loading. A migration is code the application hands
  over, not a format the framework has to read.
- No parallel or concurrent migration runs. Two processes migrating one database at once is a deployment question
  (a lock, a managed rollout) and inventing a locking table here would be a second authority to trust.
- No per-dialect SQL rewriting, no schema diffing, no "generate migration from entities" — there are no entities
  (ADR-0038 §Non-goals).

## Reopening trigger

- A second product in this build's own tests reopens the exception paragraph per product: the fold rules and the DDL
  transactionality would each be measured, and a product where DDL *does* roll back would let the guarantee be stated
  stronger there rather than weakest-common-denominator everywhere.
- A real consumer with a long migration chain that must run bounded (a backlog of hundreds of versions at one startup)
  reopens the one-connection-for-the-whole-run decision — the alternative is per-migration connections and losing the
  shared history read, so the cost is real on both sides and belongs in a new ADR.
- A deployment where two instances boot simultaneously against one database reopens the no-concurrent-runs stance;
  the first shape of a fix is deployment ordering (a startup hook, ADR-0003's territory), not a framework lock.

## Related Decisions

ADR-0038 keeps this file's promises portable (no driver named, one translation point, no ambient state); ADR-0039
supplies the ownership rule the run inherits — the framework decides, the code it hands a resource to never does;
ADR-0003/0004 place the migration run where a startup failure is preserved rather than wrapped, and ADR-0013 is why
the list is handed in rather than discovered.
