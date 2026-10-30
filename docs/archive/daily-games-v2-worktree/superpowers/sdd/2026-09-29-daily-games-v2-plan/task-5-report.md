# Task 5 Report — Structured Movie/Series by Info Comparisons

Status: DONE_WITH_CONCERNS

## Implemented files

- Created `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameInfoComparisonService.java`.
- Created `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameInfoComparisonServiceImpl.java`.
- Updated Movie/Series info generators and `DailyChallengeSnapshotAssembler` to freeze normalized lists, year, BR certification, director/creators, cast, production companies, and Movie revenue or Series season count in the existing answer snapshot.
- Updated `DailyGameServiceImpl` to compare only after candidate validation and before attempts/details mutate, preserving TMDB-unavailable attempt state.
- Updated `DailyChallengeResponseAssembler` and typed attempt tests so current-day state/attempt responses expose `infoFeedback` through the existing JSONB codec without open-state secret leakage.
- Added comparison, generator, service integration, response redaction, threshold, direction, and `NO_DATA` tests.
- Actor filmography behavior was not changed.

## RED evidence

Command:

```text
.\mvnw.cmd test "-Dtest=DailyGameInfoComparisonServiceTest,DailyChallengeGeneratorTest"
```

Result: exit code 1 during `testCompile` because the requested production contract did not exist yet:

```text
cannot find symbol
  symbol:   class DailyGameInfoComparisonService
```

## GREEN evidence

Command:

```text
.\mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyGameInfoComparisonServiceTest,DailyGameServiceImplTest,DailyChallengeResponseAssemblerTest"
```

Latest result: exit code 0, `Tests run: 66, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`. The full suite was not run.

## Self-review concerns

- An earlier focused run hit a pre-existing randomized EpisodeByFrame test failure:

  `DailyChallengeGeneratorTest.shouldUseCompositeEpisodeIdentityWhenSpecialsFutureEpisodesAndMissingStillsExist`

  Mockito reported `PotentialStubbingProblem`: the test invoked `getEpisodeImages("1396", 1, 1)` while stubbing `getEpisodeImages("1396", 1, 3)`. The test was not changed; the exact focused command was rerun and passed with all 66 tests green.
- Maven emitted existing Mockito dynamic-agent and MapStruct processor warnings. They did not fail the focused build.
- The isolated worktree does not contain the documented `docs/context/progress.md` or `docs/context/business-rules.md` paths, so no additional context-doc synchronization was possible here.
- The full repository test suite was intentionally not run per the user's instruction.

## Commit

Commit hash: `6916f34`.

Commit message: `feat(daily-game): compare info game guesses`

No `Co-Authored-By` trailer is included.
