# ADR-0038: Persistence Enters Through One javax.sql Boundary; the Framework Names No Driver, Owns No Connection Factory, and Translates Every SQLException Into One Unchecked Type

## Status

accepted — 2026-10-03

Related: ADR-0009 (unchecked failure taxonomy), ADR-0013 (configuration is read in assembly and handed over, never reached for), ADR-0006 (no service locator), ADR-0039 (what happens inside this boundary's transactions), ADR-0040 (what happens to its schema).

## Context

Phase 2.11 gave the framework a security module that depends on one thing: the web contract. Phase 2.12 gives it a
data module, and the same question arrives with sharper edges — where does JDBC live, who owns a connection, and what
does a database failure look like to a caller? A framework that answers these badly answers them permanently, because
persistence code is where applications grow dependencies fastest and where "it worked against H2 in the tests" most
often becomes "it broke against the customer's PostgreSQL."

The reconnaissance measured two facts before deciding anything. (1) The build is offline: a database driver cannot be
assumed available, but `dependency:get` reaches Maven Central, so H2 2.2.224 was fetched into the local repository and
smoke-tested on JDK 17 (`jdbc:h2:mem:` create/insert/count printed `rows=1`). A test-scope driver is therefore real.
(2) H2 commits DDL implicitly: a `CREATE TABLE` inside a transaction that is rolled back still leaves the table
visible, measured on the same JVM (see ADR-0040, which this fact shapes). A boundary designed against the JDBC
specification's promises and against the products' actual behaviour is what both measurements buy, and both are
recorded below rather than smoothed over.

## Problem

1. Who creates the `Connection` — the framework, or the application?
2. What type does a persistence failure reach an HTTP handler as, and who translates it?
3. How does the data module stay buildable with no driver in the reactor?
4. What stops the test database from leaking into production code?

## Options Considered

**A — a framework-owned connection factory configured by the framework.** `Database.of(configuration)` reads
`barezen.datasource.url`, loads the driver, builds connections. Rejected on three grounds, all measured or
mechanical, none stylistic. (1) It forces the framework to know a driver at compile time or to
`Class.forName` one at runtime — a URL that works in tests and fails in deployment is the exact shape of that
failure, and the data module's ArchUnit rules forbid both forms. (2) It reads configuration behind its own
signature, which ADR-0013 refused for the assembler and ADR-0006 for every type. (3) The question the phase asks —
"how does production code stay independent of the test database" — has no answer in this shape: a framework that
builds its own connections has already decided which database it can talk to, and every application inherits that
decision.

**B — a full repository abstraction: entities, query DSL, mapping registry.** Rejected because it is the
Spring-shaped answer the max names — a data layer that grows an object model is a framework rather than a library —
and because every one of its promises (portability, type safety, change tracking) requires a dependency and a
lifecycle the framework's size argument refuses to take. The boundary below covers the ground the directive asks
for — CRUD, transactions, migration — with six public types instead of an ecosystem.

**C — `javax.sql.DataSource` supplied by the application, one boundary type, chosen.** The data module's compile
dependencies are none: it speaks `javax.sql` from the JDK and receives a finished `DataSource`. The application's
assembly code reads its own configuration (keys it declares, per ADR-0013), builds a `DataSource` — H2's own class
in tests, whatever pooled implementation production chooses, the framework never asks which — and hands it to
`new Database(dataSource)`. Pooling is a property of the source, not a concept of the framework, and that sentence
is the portability argument: `Database` cannot ask a source anything beyond `getConnection()`.

The test database cannot leak into production, because there is no path: nothing in `src/main` may name `org.h2..`
(an ArchUnit rule with the vendor list as a second, redundant sentence, and `DriverManager`/`Class.forName` banned
by overload), and no configuration key exists for the framework to read a URL into.

## Decision

**The boundary is `Database(DataSource)`.** Three access methods: `query(sql, mapper, params...)` and
`executeUpdate(sql, params...)` borrow a connection for the call and close it before returning — they are for
statements that are units by themselves; `transaction(callback)` opens one connection, hands the callback a
`Transaction`, and commits when the callback returns (ADR-0039 owns the semantics). `RowMapper<T>` reads one
positioned row and returns one value; the framework owns the cursor and its closing.

**`DataAccessException` is the one translation point.** Every `SQLException` that leaves the module arrives as this
unchecked type, named by the operation and the SQL, with the driver exception preserved as the cause. It extends
`RuntimeException` directly and not anything in `com.barezen.core.error`, because those types describe failures
fixed by editing application code (assembly, lifecycle, configuration) while a persistence failure is usually fixed
by the database or the query; a shared parent would let one catch block claim both are the same problem, and ADR-0009's
taxonomy fold is the place to decide that if it ever should be. A runtime exception thrown by an application's own
transaction callback is *not* translated — it stays itself; wrapping an `IllegalStateException` from business code
in a persistence exception would report a database failure that never happened (this is tested:
`runtimeFailureRollsBackAndIsNotTranslated`).

**No ambient anything.** No `ThreadLocal`, no executor, no `System.getProperty`, no logger in main sources — each a
separate ArchUnit rule, each proven to bite by a violating class (`EvilDataProbe`, deleted after its run; the probe
made all seven rules fail at once and their messages name it).

## Consequences

Positive:
- The data module has zero compile dependencies. Its BOM entry imports no driver, and `mvn dependency:tree` for
  `barezen-data` (test scope excluded) is empty of third-party artifacts — the portability claim is mechanical.
- An application swaps databases by swapping the `DataSource` object; no framework code changes because no framework
  code can see the difference.
- Every persistence failure is one type to catch, with the SQL and the driver exception inside it. A handler that
  answers 500 does not need JDBC to explain itself.
- The test database is structurally unreachable from production: the ban is enforced by rules, not by review.

Negative, stated because they are real:
- A `Database` that receives a source which creates one connection per call is correct but slow; the framework says
  no "use a pool" because it cannot test every pool. The javadoc states pooling is the application's decision, and
  the `DataSource` seam is where the application makes it.
- `RowMapper` is one row: callers compose lists and never see the cursor. A caller that wants streaming reads (a
  `ResultSet`-consuming API) is a different design with a different leak surface; nothing asked for it, and §21's
  debt list is where it would be named if a consumer appears.
- Parameters bind with `setObject(int, Object)`, so a `null` binds as SQL `NULL` without a type hint. That is the
  portable choice for an API that does not know the column type, and it is also the reason a `null` parameter into
  a column whose driver needs a type can fail on that driver — translated like every other failure.

## Security implications

- No logger in main sources (the rule the security module established, ADR-0037 §Security implications, and a data
  layer is one debug statement away from writing row contents to a log file — SQL and its parameters *are* the data).
- Failure messages name the SQL but never the bound parameter values, so a translated failure in a log (written by
  the application's own logging, if it has any) states what query broke without exposing what the rows contain.
- The environment-reading ban closes a configuration-injection path: a datasource URL or credential cannot be
  smuggled into the framework through a system property a test or an operator sets.

## Testing

Sixty-four tests (DatabaseTest 8, DatabaseLifecycleTest 11, TransactionTest 13, MigratorTest 21, ArchUnit 7,
ClassMajor 4), against a real H2 in-memory database, with a `RecordingDataSource` (a
JDK-proxy wrapper around the real source) proving what JDBC calls the framework actually makes. The mutation set
M1–M16 (§Phase record) covers the directive's eight named mutations and the boundary, negative, and regression
probes: deleting rollback, deleting commit, skipping a migration's apply, reversing version order, leaking the
connection, leaking the statement, committing after an exception, and making commit public again each fail tests;
all sixteen were killed and every mutated file restored byte-identically (SHA-256 verified by the harness,
`/tmp/p212/mut.py`).

## Non-goals

- No ORM, no entity model, no query builder, no repository base class. The boundary is SQL-shaped because a layer
  that hides SQL is a layer whose cost is invisible, and invisible costs get added.
- No connection pooling inside the framework. `DataSource` is the seam the pooling world already agrees on.
- No reactive or async data access; no thread machinery of any kind, per the rule and its reason above.
- No multi-tenancy, no read/write splitting, no sharding. Each is a `DataSource` composition decision the
  application can make outside this boundary, which is the reason they do not need to exist inside it.
- No schema diffing, no "generate migration from entity changes." A migration is SQL an author wrote; the history
  table is the framework's, and the gap between them is exactly where ADR-0040 lives.

## Reopening trigger

- A consumer for streaming reads (a table export, a migration that must not hold a full result set) reopens
  `RowMapper`'s one-row shape; the leak surface grows when the cursor escapes, which is the argument the design
  makes now and would have to re-make then.
- A deployment that cannot build a `DataSource` in its assembly code (a managed container that injects one by
  name/lookup) reopens option A's configuration reading — and would need a *mechanism* for injection rather than
  framework-side `System.getProperty`, the distinction ADR-0013's argument turns on.
- A second database product in the build's own tests reopens the vendor list: one engine was measured (H2's implicit
  DDL commit, in the Context above and in full in ADR-0040), and cross-product behaviour (DDL transactionality in
  particular) is asserted per product from measurement, not assumed.

## Related Decisions

ADR-0039 is the inside of this boundary — what `Database.transaction` promises and who may decide a transaction's
fate. ADR-0040 is the schema side — the migration history and its measured limits. ADR-0013/0006 supply the
configuration and service-locator arguments that make option A dead, ADR-0009 the unchecked-exception posture this
layer follows with its own type, and ADR-0037 the logging-facade ban this module inherits rather than restates.
