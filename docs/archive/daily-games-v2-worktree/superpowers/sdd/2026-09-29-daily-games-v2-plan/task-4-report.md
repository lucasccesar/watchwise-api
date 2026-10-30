# Task 4 implementation report

## Status

DONE_WITH_CONCERNS

## Files

Modified production files:

- `src/main/java/com/watchwise/watchwise_api/dailygame/generation/EpisodeByFrameGenerator.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeSnapshotAssembler.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java`
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAnswerDTO.java`

Modified test files:

- `src/test/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeGeneratorTest.java`
- `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssemblerTest.java`
- `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImplTest.java`

## Implemented behavior

- EpisodeByFrame now requires at least six unique, nonblank still paths from `getEpisodeImages`.
- Duplicate and blank still paths do not count toward eligibility; the previous one-image `still_path` fallback is removed for new candidates.
- Regular seasons, released episodes, and popular-series results use bounded shuffled fallback. Excluded composite episode identities remain respected.
- Episode snapshots retain `imagePath` as the first stored path and persist all stills in `displaySnapshot.imagePaths`.
- Episode answer snapshots freeze `seriesName`, `seriesPosterPath`, `seriesYear`, `episodeName`, `seasonNumber`, and `episodeNumber`.
- Terminal answers expose `seriesName`, `seriesPosterUrl`, and `seriesYear`; open states remain redacted.
- Legacy rows with only `imagePath` or the old snapshot fields remain readable.
- Terminal answer assembly reads only the stored snapshot and performs no TMDB call.
- Info-comparison and actor-filmography behavior were not changed.

## RED

Command:

```powershell
.\mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest"
```

Observed output: exit code `1`; `DailyChallengeGeneratorTest` ran 17 tests with 5 failures, 0 errors, and 0 skipped. The failures covered accepting five stills, missing frozen snapshot metadata, and the same-season, cross-season, and cross-series fallback cases.

## GREEN

Command:

```powershell
.\mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyChallengeResponseAssemblerTest,DailyGameServiceImplTest"
```

Observed output: exit code `0`; 50 tests ran with 0 failures, 0 errors, and 0 skipped. Maven reported `BUILD SUCCESS`.

## Self-review concerns

- The full Maven suite was not run; verification is limited to the focused generator/assembler/service command requested for this task.
- Maven emitted existing MapStruct processor and Mockito/ByteBuddy dynamic-agent warnings; no warning represented a test failure or Task 4 behavior failure.
- No independent reviewer subagent was available in the configured tool surface; the final review was performed manually against the brief, diff, and focused test output.
- No push was performed.

## Commit

`6f1b230` — `feat(daily-game): require six episode stills`

The commit message contains no `Co-Authored-By`, `Co-authored-by`, `Signed-off-by`, or other self-attribution trailer.
