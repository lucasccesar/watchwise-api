# Task 2 implementation report

## Scope delivered

Task 2 adds typed shared daily-game transport, immutable current-day attempt-details encoding/decoding, specific game reads, and current/historical give-up actions. The implementation does not add episode hierarchy, info comparison behavior, or filmography behavior from later tasks.

Give-up and attempt submission use the same transactional service boundary and the existing `insertIfAbsent` plus pessimistic `findByUserIdAndDailyChallengeIdForUpdate` path. Give-up creates a missing result, does not consume an attempt, sets `FAILED` with score zero, reveals the challenge answer, and rejects terminal results with `ConflictException`.

Open states continue to redact the challenge answer. Current-day responses decode the transient attempt list; historical responses omit transient attempt details. Invalid candidate validation occurs before attempt increment or JSONB append. The controller preserves `/games/today` precedence, adds specific reads, applies UTC date selection for the today-specific route, and applies CSRF/rate limiting through the existing security path for give-up writes.

## Files changed

### Production

- `src/main/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameController.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameActorGuessDTO.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAttemptDTO.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAttemptResponseDTO.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameCandidateDTO.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameComparisonCellDTO.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameComparisonDirection.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameComparisonStatus.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameFilmographyEntryDTO.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameFilmographyFeedbackDTO.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameFilmographyStateDTO.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameInfoFeedbackDTO.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameStateDTO.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameAttemptDetailsCodec.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameService.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImpl.java`

### Tests

- `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerIntegrationTest.java`
- `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerTest.java`
- `src/test/java/com/watchwise/watchwise_api/dailygame/service/DailyGameAttemptDetailsCodecTest.java`
- `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssemblerTest.java`
- `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImplTest.java`

## TDD evidence

### RED

Command:

```powershell
.\mvnw.cmd test "-Dtest=DailyGameServiceImplTest#shouldFailWithoutConsumingAnAttemptWhenTheUserGivesUp"
```

Observed result: exit code `1`, Maven `BUILD FAILURE` during test compilation. The expected missing production methods were reported:

```text
cannot find symbol: method giveUp(UUID, DailyGameType)
cannot find symbol: method getGame(UUID, LocalDate, DailyGameType, boolean)
```

The codec-first RED command was also run before the codec and transport types existed:

```powershell
.\mvnw.cmd test "-Dtest=DailyGameAttemptDetailsCodecTest"
```

Observed result: exit code `1`, Maven `BUILD FAILURE` during test compilation with missing `DailyGameAttemptDTO`, `DailyGameCandidateDTO`, and `DailyGameAttemptDetailsCodec` symbols.

### GREEN

Final focused command:

```powershell
.\mvnw.cmd test "-Dtest=DailyGameAttemptDetailsCodecTest,DailyGameServiceImplTest,DailyChallengeResponseAssemblerTest,DailyGameControllerTest,DailyGameControllerIntegrationTest"
```

Observed result: exit code `0`, Maven `BUILD SUCCESS`.

```text
DailyGameControllerIntegrationTest: Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
DailyGameControllerTest:            Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
DailyGameAttemptDetailsCodecTest:   Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0
DailyChallengeResponseAssemblerTest: Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
DailyGameServiceImplTest:           Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
Total:                               Tests run: 60, Failures: 0, Errors: 0, Skipped: 0
```

Docker/Testcontainers was available and the integration test PostgreSQL container started successfully. The full Maven suite was not run, per the task instruction.

## Self-review concerns

- The new codec owns one JSONB map with the `attempts` key and returns defensive copies/immutable reads. Future comparison and filmography tasks must extend this same shape rather than introducing a second persistence structure.
- The shared info and filmography DTOs are transport types only in this task. Their comparison, redaction projection, Major-role filtering, and TMDB behavior remain intentionally unimplemented for later tasks.
- `DailyGameServiceImpl.getGame(..., majorRoles)` accepts the contract flag but does not project filmography yet because that behavior is outside Task 2.
- The focused suite passed, but the full Maven suite was intentionally not executed, so unrelated modules were not revalidated here.
- The checkout is a managed detached worktree. No push was performed.
- No `docs/context` directory is present in this checkout, so no OpenAPI/schema/business-rule/progress synchronization was possible here.
- The configured tool surface did not expose a subagent dispatch API for the requested independent code-review skill; the final review was performed read-only against the staged/committed diff and focused tests.

## Commits

- Base: `58497c7` — Task 1 (`feat(daily-game): retain current-day attempt details`)
- Task 2: `7ddfb1fa2b18a7ac5729adc6fb7adfb238e27bd6` — `feat(daily-game): add specific game reads and give-up`

The Task 2 commit message contains no `Co-Authored-By`, `Co-authored-by`, `Signed-off-by`, or other self-attribution trailer.
