# Daily Games v2 final fix wave

Date: 2026-09-30

## Changes

- `DailyGameServiceImpl`: removed the enclosing submission transaction. Result creation and final persistence use `NewTransactionExecutor`; the preflight result read is non-locking. Candidate validation and TMDB-backed comparisons happen outside the persistence transaction. The final transaction reacquires the pessimistic lock and rechecks terminal status and remaining attempts before mutation. The response carries `currentAttempt` separately from current-day-only JSONB attempt history.
- `DailyGameAttemptResponseDTO`, `DailyChallengeResponseAssembler`, and the OpenAPI schema: expose nullable `currentAttempt`, including on historical submissions, without returning earlier attempt history.
- `DailyGameFilmographyServiceImpl`: comparison uses the configured bounded detail lookup budget. Series-filmography generation checks the default Major projection and requires two valid Major works while retaining the full snapshot for the all-roles toggle.
- Regression coverage added/updated for historical current-attempt response, bounded comparison lookups, Major eligibility after budget exhaustion, no method-level submission transaction, and exact give-up answer fields.
- Removed root `final-fix-report.md`.

## Decisions and concerns

- The existing `NewTransactionExecutor` (`REQUIRES_NEW`) keeps database writes and the final lock window independent from external TMDB latency. A submission that races another is rechecked after taking the final lock, preserving the attempt cap and terminal-state rules.
- No database schema change was needed. The additive response field is documented in the ignored worktree OpenAPI context file.
- Docker/Testcontainers were unavailable. The Daily Games suite therefore could not run three integration/repository classes. The full `clean package` run also failed: 286 tests reported, 19 failures and 243 errors, including extensive class-loading/configuration errors in addition to Docker-related failures. This remains the primary verification concern.
- No push performed.
- Commit `f2794dc7a7afa70fffc9a86d6a0aa9d107ce5bfa` (`fix(dailygame): finalize attempt and filmography handling`) contains the full fix wave. It has no commit body and no `Co-Authored-By` or other self-attribution trailer.

## Verification

- `mvnw.cmd test "-Dtest=DailyGameServiceImplTest"` — exit 0; 32 tests, 0 failures, 0 errors.
- `mvnw.cmd test "-Dtest=DailyGameServiceImplTest,DailyGameFilmographyServiceTest,DailyChallengeGeneratorTest" -DfailIfNoTests=false` — final run exit 0; Maven reported 66 tests, 0 failures, 0 errors.
- `mvnw.cmd test "-Dtest=*DailyGame*,*DailyChallenge*" -DfailIfNoTests=false` — exit 1; 172 tests, 0 failures, 3 errors because Docker was unavailable for Testcontainers integration/repository tests.
- `mvnw.cmd clean package` — exit 1; 286 tests, 19 failures, 243 errors. The failures include unrelated class-loading/configuration errors; Testcontainers also could not find Docker.
- `git diff --check` — exit 0.
- The non-locking preflight read and updated repository mocks are included in the commit; final `DailyGameServiceImplTest` passed 32/32 after those edits.

Commit SHA: `f2794dc7a7afa70fffc9a86d6a0aa9d107ce5bfa`.

Final status: `DONE` after compatibility follow-up commit `330afbea2b392609126d2f8d8e509bc6d2cf20b3` restored the legacy 16-argument constructor. The focused compatibility test passed, the independent scoped review found no Critical/Important regressions, the final full suite passed 3,277/3,277, and `mvnw.cmd clean package` passed with Flyway v63 validation and JAR packaging. No push was performed.

## Final verification after compatibility follow-up

- `.\mvnw.cmd test "-Dtest=DailyGameAttemptResponseDTOTest,DailyGameServiceImplTest,DailyGameFilmographyServiceTest,DailyChallengeGeneratorTest" "-DfailIfNoTests=false"` — exit 0; 67 tests, 0 failures, 0 errors.
- `.\mvnw.cmd test` — exit 0; 3,277 tests, 0 failures, 0 errors.
- `.\mvnw.cmd clean package` — exit 0; 3,277 tests, 0 failures, 0 errors; Flyway validated/applied v63 and the Spring Boot JAR was packaged.
- Independent scoped review of `f2794dc..330afbe` — Ready, no Critical/Important findings.
