### Task 3: Replace episode text search with series -> seasons -> episodes

Task 2 is committed as `7ddfb1f`: shared response transport, current-day codec, specific reads, and give-up routes already exist. This task changes only the episode selection/search flow; generation with six stills is Task 4.

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameSeasonOptionDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameEpisodeOptionDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameSearchService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameController.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerIntegrationTest.java`

**Interfaces:**
- `List<DailyGameSeasonOptionDTO> listEpisodeSeasons(UUID userId, String seriesTmdbId)`.
- `List<DailyGameEpisodeOptionDTO> listEpisodeEpisodes(UUID userId, String seriesTmdbId, Integer seasonNumber)`.
- `DailyGameSeasonOptionDTO(seasonNumber, name, episodeCount)`.
- `DailyGameEpisodeOptionDTO(seriesTmdbId, seasonNumber, episodeNumber, name, LocalDate airDate)`.

- [ ] **Step 1: Write failing tests for the two dropdown reads.** Assert that seasons omit season zero and specials, episodes omit future episodes, neither response contains image URLs, and invalid IDs/season coordinates are rejected.

- [ ] **Step 2: Run `mvnw.cmd test "-Dtest=DailyGameSearchServiceImplTest"` and confirm the new tests fail.** Existing `searchEpisodes` tests must be changed to express the new behavior, not left asserting the removed `q` route.

- [ ] **Step 3: Implement season and episode option reads.** Load the selected series with `getTvFullDetails`, map regular seasons without images, load one season with `getSeasonFullDetails`, filter released episodes using the injected `Clock`, and map only identity/name/date. Keep the existing `searchEpisodeSeries` behavior and positive numeric ID validation. Translate TMDB unavailable to the existing `TmdbUnavailableException` path.

- [ ] **Step 4: Remove the controller/service route that accepts `/search/episodes?seriesTmdbId=...&q=...`.** Keep `/search/series` and the generic search rejection for `EPISODE_BY_FRAME`.

- [ ] **Step 5: Run search/controller tests and commit.**

Run: `mvnw.cmd test "-Dtest=DailyGameSearchServiceImplTest,DailyGameControllerTest,DailyGameControllerIntegrationTest"`

```bash
git add src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(daily-game): add hierarchical episode selection"
```

Confirm no co-authorship trailer was added.
