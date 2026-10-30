# Task 3 report: hierarchical episode selection

Status: `DONE`

## Files

Created:

- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameSeasonOptionDTO.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameEpisodeOptionDTO.java`

Modified:

- `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameSearchService.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImpl.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameController.java`
- `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImplTest.java`
- `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerTest.java`
- `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerIntegrationTest.java`

## RED

Command:

```text
./mvnw.cmd test "-Dtest=DailyGameSearchServiceImplTest"
```

Output:

```text
BUILD FAILURE
Failed during testCompile with 4 expected cannot-find-symbol errors for
DailyGameSeasonOptionDTO and DailyGameEpisodeOptionDTO.
```

The failing tests were written before the production DTOs and new service methods.

## GREEN

Command:

```text
./mvnw.cmd test "-Dtest=DailyGameSearchServiceImplTest,DailyGameControllerTest,DailyGameControllerIntegrationTest"
```

Output:

```text
BUILD SUCCESS
Tests run: 63, Failures: 0, Errors: 0, Skipped: 0
```

Focused test counts:

- `DailyGameSearchServiceImplTest`: 32
- `DailyGameControllerTest`: 11
- `DailyGameControllerIntegrationTest`: 20
- Total: 63

The post-commit rerun produced the same result. Docker/Testcontainers PostgreSQL was available for the integration suite.

## Implemented behavior

- Added series → seasons and seasons → episodes list reads.
- Omitted season zero and other non-positive season coordinates.
- Filtered episodes with missing/invalid dates or future air dates using the injected `Clock`.
- Kept released episodes without still images eligible for the dropdown.
- Returned only the specified season/episode identity, name, episode count, and air date; no image fields are exposed.
- Preserved generic `EPISODE_BY_FRAME` rejection and `searchEpisodeSeries` behavior.
- Removed the q-based episode service method and replaced the episode selection route contract.
- Kept both new reads authenticated and behind the existing daily-game search throttle.
- Preserved positive numeric series-ID validation and TMDB-unavailable exception handling.
- Did not change generation, comparison, filmography, or six-image behavior.

## Self-review concerns

- `docs/context/progress.md` and `docs/context/business-rules.md` are not present in this isolated worktree, so the repository documentation could not be synchronized here. This is a worktree-context concern, not an implementation failure.
- No independent reviewer subagent was available in this environment; the final review was performed manually against the brief, diff, route references, and focused test output.
- Maven emitted existing compiler/Mockito/JDK warnings during tests; no test failure or task-specific warning was introduced.

## Commit

`6a654c1b00b130a635fed84b5c21364a075a4470` — `feat(daily-game): add hierarchical episode selection`

The commit message contains no `Co-Authored-By` trailer or other self-attribution. Nothing was pushed.
