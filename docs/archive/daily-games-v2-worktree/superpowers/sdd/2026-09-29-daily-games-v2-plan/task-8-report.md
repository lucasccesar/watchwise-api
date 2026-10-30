# Task 8 Verification Report

Status: DONE

Worktree: `C:\Users\Lucas C\.codex\worktrees\daily-games-v2\watchwise-api`

Commit verified: `52ef4c9` (`fix(daily-game): preserve attempt role projection`)

No production code, tests, or `docs/context` files were modified in this task. No commit or push was performed.

## Maven verification

### 1. Focused daily-game suite

Exact command:

```text
mvnw.cmd test "-Dtest=DailyGameControllerTest,DailyGameControllerIntegrationTest,DailyGameSearchServiceImplTest,DailyGameServiceImplTest,DailyGameServiceImplIntegrationTest,DailyChallengeResponseAssemblerTest,DailyChallengeGeneratorTest,DailyChallengeGenerationServiceImplTest,DailyGameRankingServiceImplTest,DailyGameGenerationJobTest,DailyGameRepositoryTest,DailyGameAttemptDetailsCleanupServiceImplTest,DailyGameAttemptDetailsCleanupJobTest,DailyGameInfoComparisonServiceTest,DailyGameFilmographyServiceTest"
```

- Exit code: `0`
- Tests: `210`
- Failures: `0`
- Errors: `0`
- Skipped: `0`
- Result: Maven `BUILD SUCCESS`
- Docker/Testcontainers: available; Docker Desktop server `29.5.2`, PostgreSQL `16-alpine` containers started successfully.
- Flyway: validated 62 migrations and applied through schema version `V63` during the integration tests.

### 2. Full Maven test suite

Exact command:

```text
mvnw.cmd test
```

- Exit code: `0`
- Tests: `3,266`
- Failures: `0`
- Errors: `0`
- Skipped: `0`
- Result: Maven `BUILD SUCCESS`
- Duration reported by Maven: `10:54 min`
- Finished: `2026-09-29T22:22:15-03:00`
- Docker/Testcontainers: available throughout the run; repository and integration tests executed.

The captured log contains transient Hikari/Testcontainers connection-refused and closed-connection warnings while test application contexts and PostgreSQL containers were being cycled. Maven completed normally and reported zero failures/errors/skips, so this did not block verification.

### 3. Clean package verification

Exact command:

```text
mvnw.cmd clean package
```

- Exit code: `0`
- Tests: `3,266`
- Failures: `0`
- Errors: `0`
- Skipped: `0`
- Result: Maven `BUILD SUCCESS`
- Duration reported by Maven: `10:14 min`
- Finished: `2026-09-29T22:32:47-03:00`
- Flyway: repeatedly validated 62 migrations and applied 62 migrations to fresh Testcontainers databases, reaching `V63`.
- Hibernate/JPA: clean compilation and package verification completed with the JSONB `attempt_details` mapping exercised by the test suite.
- Failure markers: no `BUILD FAILURE`, non-zero failure count, non-zero error count, or Maven goal failure was found in the captured log.

Temporary full logs captured outside the worktree:

- `C:\Users\Lucas C\AppData\Local\Temp\watchwise-task8-full-test.log`
- `C:\Users\Lucas C\AppData\Local\Temp\watchwise-task8-clean-package.log`

## Security and integrity checklist

- Secret/answer is absent from open responses: `DailyChallengeResponseAssembler.view` returns the frozen answer only for terminal states (`src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java:164-190`). Open-state redaction is covered by `DailyChallengeResponseAssemblerTest` and `DailyGameRankingServiceImplTest`.
- Invalid attempts do not mutate state: candidate validation happens before `attemptsUsed` is incremented (`src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImpl.java:207-214`). Tests cover mismatched fields, missing candidates, and TMDB-unavailable candidates without consuming an attempt.
- Give-up is transactionally locked: both give-up overloads are `@Transactional` and use insert-if-absent followed by `findByUserIdAndDailyChallengeIdForUpdate` (`DailyGameServiceImpl.java:235-257`; `UserDailyGameResultRepository.java:37-44,100-129`). The service test also asserts the required transaction annotation.
- Cleanup is UTC-scoped: the scheduled job uses `zone = "UTC"` and `LocalDate.now(clock)` (`DailyGameAttemptDetailsCleanupJob.java:21-23`); the repository clears only challenges with `challenge_date < :currentDate` (`UserDailyGameResultRepository.java:49-57`). Repository and job tests cover the date boundary.
- Results are not duplicated per user/challenge: migration `V59__create-daily-games-tables.sql` defines `uq_user_daily_game_results_user_challenge`; `insertIfAbsent` uses `ON CONFLICT (user_id, daily_challenge_id) DO NOTHING`; repository tests cover the concurrent-existing-result case.
- No unrelated files are staged: `git diff --cached --stat` is empty.
- Documentation is present and aligned: `docs/context/openapi.yaml` documents the daily-game routes and redaction contract; `docs/context/business-rules.md` documents locking, invalid-attempt behavior, give-up, UTC cleanup, and redaction; `docs/context/progress.md` records the daily-game route and attempt-details updates. `docs/` is intentionally ignored by `.gitignore` and was not staged.

## Final Git state

```text
HEAD (no branch)
52ef4c9
git status --porcelain: empty
git diff --cached --stat: empty
```

