# Daily Games Task 1: Advisory Lock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Centralize the transaction-scoped PostgreSQL advisory lock and migrate calendar to it without changing calendar identities or behavior.

**Architecture:** Keep one functional `AdvisoryLock` contract in `common.transaction` and one Spring `PostgresAdvisoryLock` implementation using the existing `JdbcTemplate` SQL. Inject the common contract into `CalendarScheduleSnapshotStore`; retain its unit-test no-op constructor and move all integration-test wiring to the common bean.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring JDBC/JPA, PostgreSQL 16, JUnit 5, AssertJ, Testcontainers, Maven Wrapper.

## Global Constraints

- Preserve the existing calendar identities `movie|...` and `series|...`.
- Use `pg_advisory_xact_lock(hashtext(?))` inside the caller's database transaction.
- Do not use a JVM-only lock or add code comments.
- Follow existing test names and `[method] Should ... - When ...` `@DisplayName` conventions.
- Do not touch the pre-existing `.gitignore` modification.
- Commit with Conventional Commits and without a `Co-Authored-By` trailer.

### Task 1: Add the common lock contract and prove PostgreSQL serialization

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/common/transaction/AdvisoryLock.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/transaction/PostgresAdvisoryLock.java`
- Test: `src/test/java/com/watchwise/watchwise_api/common/transaction/PostgresAdvisoryLockTest.java`

**Interfaces:**
- Produces `AdvisoryLock.lock(String identity)` for calendar and future Daily Games callers.
- Produces a Spring bean whose SQL is `SELECT pg_advisory_xact_lock(hashtext(?))`.

- [ ] **Step 1: Write the failing integration tests**

Create a `@DataJpaTest` using a PostgreSQL 16 `@Container`, `@Testcontainers`, `@AutoConfigureTestDatabase(replace = NONE)`, `@Transactional(propagation = NOT_SUPPORTED)`, and `@Import(PostgresAdvisoryLock.class)`. Inject `AdvisoryLock`, `JdbcTemplate`, and `PlatformTransactionManager`. Use two `TransactionTemplate` executions in a fixed thread pool. For the same identity, hold the first transaction after `lock`, start the second, poll `pg_locks` for the second backend PID with `locktype = 'advisory' AND granted = false`, assert it is waiting, then release the first and assert the second acquires. For different identities, hold the first transaction and assert the second acquires before releasing the first. Assert backend PIDs differ in both cases.

Use these test names and display names:

```java
@Test
@DisplayName("[lock] Should Serialize Transactions - When They Use The Same Identity")
void shouldSerializeTransactionsWhenTheyUseTheSameIdentity() throws Exception

@Test
@DisplayName("[lock] Should Not Serialize Transactions - When They Use Different Identities")
void shouldNotSerializeTransactionsWhenTheyUseDifferentIdentities() throws Exception
```

- [ ] **Step 2: Run the focused test to verify RED**

Run `.\mvnw.cmd test "-Dtest=PostgresAdvisoryLockTest"`. Expected: the test cannot compile because `PostgresAdvisoryLock` and `AdvisoryLock` do not exist, or after only the test scaffolding is compilable it fails because the bean/contract is missing. If Docker is unavailable, record the exact Testcontainers startup limitation and continue with compile verification.

- [ ] **Step 3: Add the minimal common implementation**

Create the functional interface and a `@Repository` class with constructor injection. Implement only:

```java
public void lock(String identity) {
    jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", String.class, identity);
}
```

- [ ] **Step 4: Run the focused test to verify GREEN**

Run the same focused Maven command and require both real PostgreSQL tests to pass when Docker is available. If unavailable, run `.\mvnw.cmd -DskipTests compile` and record the limitation rather than claiming integration success.

### Task 2: Migrate calendar and remove calendar-specific lock types

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/calendar/repository/CalendarScheduleSnapshotStore.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/calendar/repository/CalendarScheduleCompletenessRepositoryTest.java`
- Delete: `src/main/java/com/watchwise/watchwise_api/calendar/repository/CalendarScheduleIdentityLock.java`
- Delete: `src/main/java/com/watchwise/watchwise_api/calendar/repository/PostgresCalendarScheduleIdentityLock.java`

**Interfaces:**
- Consumes `AdvisoryLock` and `PostgresAdvisoryLock` from Task 1.
- Preserves all calendar lock identity strings and existing constructor behavior.

- [ ] **Step 1: Update the calendar production dependency type**

Import `com.watchwise.watchwise_api.common.transaction.AdvisoryLock`, change the `scheduleLock` field and four-argument constructor parameter to `AdvisoryLock`, and leave the three-argument constructor's no-op lambda unchanged.

- [ ] **Step 2: Migrate direct test references**

In `CalendarScheduleCompletenessRepositoryTest`, import the common types, import `PostgresAdvisoryLock.class`, and change the injected field to `AdvisoryLock`. Keep the existing Testcontainers, transaction, and calendar assertions unchanged.

- [ ] **Step 3: Delete obsolete types and check references**

Delete both calendar-specific lock files, then run `rg -n "CalendarScheduleIdentityLock|PostgresCalendarScheduleIdentityLock" src/main src/test`. Expected: no matches.

- [ ] **Step 4: Run focused calendar tests and compile**

Run `.\mvnw.cmd test "-Dtest=CalendarScheduleCompletenessRepositoryTest,CalendarScheduleSnapshotRepositoryTest,CalendarScheduleSynchronizerImplTest"` and `.\mvnw.cmd -DskipTests compile`. Record exact results, including any Docker limitation.

### Task 3: Review, report, and commit

**Files:**
- Create: `.superpowers/sdd/daily-games/task-1-report.md`

- [ ] **Step 1: Re-check the diff and requirements**

Confirm only the requested code/test files plus the report and uncommitted planning documentation changed; confirm `.gitignore` remains untouched. Review transaction scoping, SQL, preserved identities, test synchronization, no comments, and all exception/validation concerns relevant to this task.

- [ ] **Step 2: Run final verification**

Run the focused commands again as needed, inspect their exit codes and full result counts, and run `git diff --check`. Do not claim completion without fresh output.

- [ ] **Step 3: Write the detailed report**

Write `.superpowers/sdd/daily-games/task-1-report.md` with implementation, changed files, RED/GREEN evidence, exact commands/results, self-review, concerns, and Docker/Testcontainers limitation if applicable.

- [ ] **Step 4: Commit the implementation**

Stage only the implementation, tests, and report. Use `git commit -m "feat(transaction): centralize advisory lock"`.

- [ ] **Step 5: Verify commit authorship and final state**

Run `git show -s --format=%B HEAD` and `git status --short`. Confirm the commit message has no `Co-Authored-By` or other self-attribution, the pre-existing `.gitignore` modification remains uncommitted, and report the commit hash.
