### Task 4: Require six episode stills during generation and enrich the terminal answer

Tasks 1–3 are committed through `6a654c1`: the current-day JSONB transport, specific reads, and hierarchical episode selection exist. This task changes only EpisodeByFrame generation/snapshots/terminal projection; do not implement info comparison or actor filmography logic.

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/EpisodeByFrameGenerator.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeSnapshotAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAnswerDTO.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeGeneratorTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssemblerTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImplTest.java`

**Interfaces:**
- `EpisodeByFrameGenerator` selects a released episode whose deduplicated image list has size `>= 6`.
- The candidate answer snapshot includes `seriesName`, `seriesPosterPath`, `seriesYear`, `episodeName`, `seasonNumber` and `episodeNumber`.
- `DailyGameAnswerDTO` exposes series name, poster URL and year for terminal episode answers; existing constructors/callers remain compatible.

- [ ] **Step 1: Write failing generator tests.** Cover an episode with five images being rejected, a second episode in the same season being selected, a second season being selected when the first has no eligible episode, and a new series being attempted after all seasons fail.

- [ ] **Step 2: Run the focused generator test and confirm RED.**

Run: `mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest"`

Expected: FAIL because the current generator accepts a single valid still and aggregates all released episodes from one series without the six-image threshold.

- [ ] **Step 3: Implement bounded randomized fallback.** Shuffle regular seasons and their released episodes, call `getEpisodeImages` once per candidate through the existing cache, keep unique nonblank paths, and continue season/series fallback until a candidate meets six images. Preserve the outer generation candidate limit to prevent unbounded TMDB calls. Do not use a one-image `still_path` fallback for a new candidate.

- [ ] **Step 4: Extend the episode snapshot and terminal answer.** Keep `imagePath` as the first path for legacy rows and persist all valid relative paths in `displaySnapshot.imagePaths`. Redact series metadata until the result is terminal. The final answer must derive series poster URL from `seriesPosterPath` and series year from the stored series year, without a TMDB call.

- [ ] **Step 5: Run generator/assembler/service tests and commit.**

Run: `mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyChallengeResponseAssemblerTest,DailyGameServiceImplTest"`

```bash
git add src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(daily-game): require six episode stills"
```

Confirm no co-authorship trailer was added.
