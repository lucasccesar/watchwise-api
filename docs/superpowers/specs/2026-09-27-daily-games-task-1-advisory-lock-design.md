# Daily Games Task 1: Advisory Lock Design

## Goal

Centralize the PostgreSQL transaction-scoped advisory-lock contract and implementation so calendar and future Daily Games code share the same database lock mechanism, while preserving calendar lock identities and UTC persistence boundaries.

## Design

`AdvisoryLock` is a small functional contract with `void lock(String identity)`. `PostgresAdvisoryLock` is the single Spring repository bean implementing it with the existing `SELECT pg_advisory_xact_lock(hashtext(?))` statement through `JdbcTemplate`. Because the SQL uses the transaction-scoped PostgreSQL function, the caller's transaction owns the lock until commit or rollback; no JVM monitor or process-local state is introduced.

`CalendarScheduleSnapshotStore` will depend on `AdvisoryLock` instead of the calendar-specific interface. Its identity construction remains unchanged: movies use `movie|<tmdbId>|<region>|<language>` and series use `series|<tmdbId>|<region>|<language>`. The three-argument constructor keeps its no-op lock used by unit tests, and the four-argument constructor remains available to integration tests.

The two calendar-specific lock types will be deleted. Any direct test reference will be migrated to the common contract and bean without changing calendar setup or assertions.

## Verification

`PostgresAdvisoryLockTest` will run against a real PostgreSQL 16 Testcontainers database. Each test will execute two `TransactionTemplate` transactions concurrently, record their PostgreSQL backend process IDs, and use `pg_locks` plus latches to prove the behavior across connections:

- identical identities: the second transaction reaches a non-granted advisory-lock row and only acquires the lock after the first transaction is released;
- different identities: the second transaction acquires its lock while the first transaction still holds the other identity.

If Docker is unavailable, Maven output will be recorded verbatim in the task report, and non-container compilation/tests will still be run.
