# Task 6 report

Status: DONE

## Fix completion

### Root cause

- The initial partial comparison path had inconsistent public work-key normalization: TV entries surfaced as `TV:<id>` and movie intersections could be empty when the secret snapshot and remote credit paths did not share one canonical key implementation.
- The aggregate person credit's `episode_count` is actor participation, not a series total. Major-role filtering therefore requires the cached `TmdbClient.getTvFullDetails(id, en-US)` `numberOfEpisodes` value for each distinct TV work. Movie credits do not make per-movie detail calls.
- Generation already needs complete frozen filmographies. Candidate actors with fewer than two valid normalized works must be rejected before a challenge is created.
- Attempt feedback was not yet integrated into the current-day response pipeline, so persisted intersections could not be re-projected when `majorRoles` changed.

### Delivered behavior

- Centralized `Work` public keys as `MOVIE:<id>` and `SERIES:<id>` for snapshot and remote-credit intersections.
- Added aggregate-credit fields for media type, genre IDs, participation episode count, roles, dates and character data, with model deserialization coverage.
- Snapshots deduplicate valid movie/series works, require two entries during actor challenge generation, and store the complete secret filmography only in the answer snapshot.
- Series Major-role filtering uses `ceil(total * .33)` through 6 episodes, `.40` through 20, and `.50` above 20; all-role mode retains every positive participation.
- Current-day submissions store both reveal-key variants. Reads re-project major/all entries from frozen snapshots and stored attempt details, without another TMDB lookup; open entries reveal only shared titles.
- `majorRoles` defaults to `true` on specific game reads. Movie filmography remains all-role regardless of the switch.

### Commands and results

```text
.\\mvnw.cmd test "-Dtest=DailyGameFilmographyServiceTest"
```

Initial handover reproduction: the earlier incomplete implementation reported the `TV:<id>` and movie-intersection failures. The completed focused service suite passed with 11 tests, 0 failures, 0 errors.

```text
.\\mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyGameFilmographyServiceTest,DailyGameServiceImplTest,DailyChallengeResponseAssemblerTest,TmdbSearchModelsTest"
```

Result: 72 tests, 0 failures, 0 errors.

### Commit

`f1432b5ded5f4f6f491546e43025dac6f3267a30` — `feat(daily-game): add filmography deductions`

The commit has no `Co-Authored-By` trailer or other self-attribution.

## Review fix round 2

`DailyGameServiceImpl` now passes the actual current or historical attempt route's `majorRoles` value into `DailyGameFilmographyService.compare`. Persisted feedback continues to include both major and all-role reveal-key sets; only the nested feedback entry projection now matches the requested mode.

Regression RED: an explicit `majorRoles=false` submission invoked `filmographyService.compare(..., true)`. The focused assertion now verifies `false` is passed through.

```text
.\\mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyGameFilmographyServiceTest,DailyGameServiceImplTest,DailyChallengeResponseAssemblerTest,TmdbSearchModelsTest"
```

Result: 74 tests, 0 failures, 0 errors.

Commit: `52ef4c983d8397d07d92a02a88d94d791072b463` — `fix(daily-game): preserve attempt role projection`

The commit has no `Co-Authored-By` trailer or other self-attribution.

## Review fix round 1

### Findings resolved

- Attempt routes now accept `majorRoles` with a default of `true` for current and historical submissions. The value passes through the service and response assembler, while both reveal-key sets remain persisted.
- Unknown `numberOfEpisodes` no longer falls back to actor participation. The work remains visible in all-role mode but is excluded from major-role filtering.
- Credits without a period/date are retained with nullable `period` and `year` metadata.
- Filmography generators now use only Spring constructor injection; test callers provide the dependency explicitly.

### Tests

```text
.\\mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyGameFilmographyServiceTest,DailyGameServiceImplTest,DailyChallengeResponseAssemblerTest,TmdbSearchModelsTest"
```

Result: 74 tests, 0 failures, 0 errors.

Additional controller unit coverage passed with the focused regression set (85 tests, 0 failures, 0 errors).

### Commit

`71944fde3df1d50e2638427d025c82879dda6649` — `fix(daily-game): project filmography attempt roles`

The commit has no `Co-Authored-By` trailer or other self-attribution.

## Prior handover report (superseded by Fix completion)

- `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbAggregateRole.java`
- `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbPersonAggregateCredit.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameFilmographyService.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameFilmographyServiceImpl.java`
- `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameFilmographyServiceTest.java`

This describes the incomplete handover state and is retained only as history. The Fix completion section above supersedes it.

## RED

Command:

```text
mvnw.cmd test "-Dtest=DailyGameFilmographyServiceTest"
```

Result: expected failure before implementation. Maven test compilation failed because `DailyGameFilmographyServiceImpl` did not exist:

```text
cannot find symbol
symbol: class DailyGameFilmographyServiceImpl
```

## GREEN attempt

Command:

```text
mvnw.cmd test "-Dtest=DailyGameFilmographyServiceTest"
```

Result: compilation succeeded, but 8 of 9 tests failed. The failures show that the current implementation still returns `TV:<id>` instead of the required `SERIES:<id>` key and does not match movie snapshot works in the intersection tests. The proportional-boundary tests also currently report `TV:<id>` rather than the expected series key.

## Handover concerns resolved

- Filmography behavior is implemented and covered by the focused suite.
- Public work-key normalization is centralized in `Work`.
- Movie snapshot/remote intersections are covered by the focused service tests.
- Total episode counts come from cached TV details; aggregate episode counts remain participation counts.
- No episode or info comparison logic was changed.
- The final commit is recorded in Fix completion and has no co-authorship trailer.
