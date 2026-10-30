### Task 6: Add shared movie/series filmography deductions and proportional Major roles

Tasks 1–5 are committed through `6916f34`. Task 2 already provides filmography DTO shells, current-day attempt transport and `majorRoles` query plumbing; this task fills the actor filmography snapshots, intersections and series filtering. Do not change episode generation or info comparison rules.

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameFilmographyService.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameFilmographyServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbPersonAggregateCredit.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbAggregateRole.java`
- Modify related TMDB tests/models as needed under `src/test/java/com/watchwise/watchwise_api/common/tmdb/`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/ActorByMovieFilmographyGenerator.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/ActorBySeriesFilmographyGenerator.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeSnapshotAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeGeneratorTest.java`
- Create: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameFilmographyServiceTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssemblerTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbSearchModelsTest.java`

**Interfaces:**
- `DailyGameFilmographyFeedbackDTO compare(DailyChallenge challenge, String guessedPersonTmdbId, boolean majorRoles)`.
- `DailyGameFilmographyFeedbackDTO` contains the guessed actor, shared work keys for `majorRoles=true` and `false`, and the redacted work list for the selected mode.
- `DailyGameFilmographyStateDTO(boolean majorRoles, List<DailyGameActorGuessDTO> guessedActors, List<DailyGameFilmographyEntryDTO> entries)` is assembled from the challenge snapshot and current-day attempt details.
- `DailyGameFilmographyEntryDTO` contains work ID, nullable title, revealed/highlighted flags, year, genres, poster, episode count, period and character.

**Major-role formula:** for a series with `totalEpisodes`, use `ceil(totalEpisodes * 0.33)` for up to 6 episodes, `ceil(totalEpisodes * 0.40)` for 7–20 episodes and `ceil(totalEpisodes * 0.50)` above 20 episodes. With `majorRoles=false`, any participation with at least one episode is eligible.

- [ ] **Step 1: Write failing tests for movie intersections and series filtering.** Cover no shared works, one shared work, multiple shared works, hidden titles, default `majorRoles=true`, all appearances when false, and exact thresholds at 6, 7, 20 and 21 total episodes.

- [ ] **Step 2: Run `mvnw.cmd test "-Dtest=DailyGameFilmographyServiceTest"` and confirm RED.**

- [ ] **Step 3: Extend aggregate-credit deserialization without per-movie TMDB calls.** Read `genre_ids`, `episode_count`, `media_type`, dates and available roles; for distinct TV works, use the existing cached `getTvFullDetails` lookup to obtain `numberOfEpisodes`, which is the denominator of the proportional Major-role formula. Preserve nulls where TMDB does not provide a period. Use the existing person aggregate cache.

- [ ] **Step 4: Store complete secret filmographies in the challenge answer snapshot and require at least two eligible works during generation.** Deduplicate by media type and TMDB ID; exclude invalid IDs and entries without a usable title/date where the corresponding clue requires them.

- [ ] **Step 5: Implement feedback for both modes.** Filter secret and guessed series filmographies by the proportional threshold, compute shared work IDs, store both major/all reveal key sets in current-day attempt details, and project only the selected mode. Movie filmography always uses all eligible movie credits. A guessed actor equal to the secret completes the game; all other open responses keep the secret actor/title hidden.

- [ ] **Step 6: Add query parameter handling.** `majorRoles` defaults to `true` on specific series-filmography reads and attempts; other game types keep their default projection. Verify toggling changes presentation without another TMDB call when the same current-day attempt details are available.

- [ ] **Step 7: Run focused tests and commit.**

Run: `mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyGameFilmographyServiceTest,DailyGameServiceImplTest,DailyChallengeResponseAssemblerTest,TmdbSearchModelsTest"`

```bash
git add src/main/java/com/watchwise/watchwise_api/common/tmdb src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/common/tmdb src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(daily-game): add filmography deductions"
```

Confirm no co-authorship trailer was added.
