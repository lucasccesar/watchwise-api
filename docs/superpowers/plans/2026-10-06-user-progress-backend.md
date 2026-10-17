# User Progress Backend Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Make the existing series-progress response sufficient for the Progress screen without adding a new primary route, persisting TMDB metadata, exposing diary-entry state, or causing frontend N+1 lookups.

**Architecture:** Keep GET /users/{userId}/series-in-progress as the read interface. Add only the presentation fields the prototype renders: series title, series poster path, last watched episode title, and next episode data. Use one presentation enricher for the page and one shared next-episode resolver reused by Progress and Home. Existing progress, season totals, pagination, sorting, and diary write endpoints remain unchanged.

**Tech Stack:** Spring Boot 4.1, Java 21, Spring Data JPA, PostgreSQL, Caffeine-backed TmdbClient, JUnit 5, Mockito, MockMvc.

## Global Constraints

- Keep GET /users/{userId}/series-in-progress.
- Do not create a second primary Progress route.
- Do not add database columns or persist TMDB title, poster, season, or episode metadata.
- Do not add diaryEntryId, score, comment, or watched companions to the Progress response.
- Do not add a ContentRepository lookup to render the list. The screen already has seriesTmdbId and episode coordinates; local Content resolution remains lazy navigation behavior.
- Treat only regular seasons, seasonNumber greater than zero, as progress candidates.
- Use the target user's preferredLanguage for TMDB presentation data.
- Use one watched-coordinate query for the requested page; do not introduce one query per series or episode.
- Keep existing private-profile visibility checks and the existing 502 behavior for required snapshot hydration failures.
- Leave documentation-only changes uncommitted according to AGENTS.md.
- Use Conventional Commits and never push without explicit authorization.

---

## Minimal response contract

Add these fields to SeriesInProgressResponseDTO:

- seriesTitle: String
- seriesPosterPath: String
- lastWatchedEpisodeTitle: String
- nextEpisode: ProgressEpisodeDTO

Add ProgressEpisodeDTO:

- seasonNumber: Integer
- episodeNumber: Integer
- title: String
- releaseDate: LocalDate
- runtimeMinutes: Integer
- stillPath: String
- availableToWatch: Boolean

Keep these existing fields unchanged:

- seriesTmdbId
- maxSeasonNumber
- maxEpisodeNumber
- lastWatchedDate
- lastWatchedSeasonNumber
- lastWatchedEpisodeNumber
- watchedEpisodeCount
- totalEpisodeCount
- watchedPercentage
- seasonProgress
- remainingEpisodeCount
- remainingRuntimeMinutes
- customPosterUrl

The screen uses customPosterUrl as the poster override and seriesPosterPath as the TMDB fallback. No contentId is required to render this screen.

---

## File Map

Create:

- src/main/java/com/watchwise/watchwise_api/seriesprogress/dto/ProgressEpisodeDTO.java
- src/main/java/com/watchwise/watchwise_api/seriesprogress/service/SeriesProgressNextEpisodeResolver.java
- src/main/java/com/watchwise/watchwise_api/seriesprogress/service/impl/SeriesProgressNextEpisodeResolverImpl.java
- src/main/java/com/watchwise/watchwise_api/seriesprogress/service/SeriesProgressPresentationEnricher.java
- src/test/java/com/watchwise/watchwise_api/seriesprogress/service/impl/SeriesProgressNextEpisodeResolverImplTest.java
- src/test/java/com/watchwise/watchwise_api/seriesprogress/service/SeriesProgressPresentationEnricherTest.java
- src/test/java/com/watchwise/watchwise_api/diaryentry/dto/SeriesInProgressResponseDTOTest.java

Modify:

- src/main/java/com/watchwise/watchwise_api/diaryentry/dto/SeriesInProgressResponseDTO.java
- src/main/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImpl.java
- src/main/java/com/watchwise/watchwise_api/summary/service/impl/HomeNextEpisodeAssembler.java
- src/test/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImplTest.java
- src/test/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryControllerTest.java
- src/test/java/com/watchwise/watchwise_api/summary/service/impl/HomeNextEpisodeAssemblerTest.java
- docs/context/openapi.yaml
- docs/context/business-rules.md
- docs/context/tmdb-reqs-by-screen.md
- docs/context/progress.md

Do not modify ContentRepository or add a migration.

---

## Task 1: Add the smallest additive DTO contract

**Interfaces**

- ProgressEpisodeDTO is an immutable record with the seven fields listed above.
- Extend SeriesInProgressResponseDTO with the four new fields at the end.
- Existing compatibility constructors must set the new fields to null.
- Add SeriesInProgressResponseDTO.withPresentation(String seriesTitle, String seriesPosterPath, String lastWatchedEpisodeTitle, ProgressEpisodeDTO nextEpisode) so the existing SeriesProgressAssembler can remain focused on numeric progress.

- [ ] Step 1: Write failing DTO tests.

Assert that a response can carry series title, poster path, last episode title, and next episode data. Assert that old constructors still produce null for the new fields.

- [ ] Step 2: Run the focused test and verify failure.

~~~powershell
.\mvnw.cmd test "-Dtest=SeriesInProgressResponseDTOTest"
~~~

Expected: compilation failure because the new DTO fields do not exist.

- [ ] Step 3: Implement the record and compatibility constructors.

Do not add diary fields or Content IDs.

- [ ] Step 4: Run the focused test and expect PASS.

- [ ] Step 5: Commit.

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/seriesprogress/dto/ProgressEpisodeDTO.java src/main/java/com/watchwise/watchwise_api/diaryentry/dto/SeriesInProgressResponseDTO.java src/test/java/com/watchwise/watchwise_api/diaryentry/dto/SeriesInProgressResponseDTOTest.java
git commit -m "feat(progress): extend progress response"
~~~

---

## Task 2: Implement the shared next-episode resolver

**Interface**

ProgressEpisodeDTO resolveNext(
    TmdbTvFullDetails seriesDetails,
    String language,
    List<SeasonProgressDTO> seasonProgress,
    Set<WatchedEpisodeCoordinate> watchedCoordinates,
    LocalDate today
)

Rules:

- Select the first unrecorded regular episode in season/episode order.
- Ignore season 0.
- Include future episodes in the result.
- Set availableToWatch to false only when releaseDate is after today.
- Return null when there is no remaining episode.
- Use seasonProgress to skip seasons already completely watched and inspect only the first incomplete or next eligible season.
- Fetch detailed season data through the existing TmdbClient cache.
- Do not persist TMDB data.

- [ ] Step 1: Write failing tests for sequential watches, skipped episodes, out-of-order watches, future episodes, Specials, completed seasons, and no remaining episode.

- [ ] Step 2: Run:

~~~powershell
.\mvnw.cmd test "-Dtest=SeriesProgressNextEpisodeResolverImplTest"
~~~

Expected: compilation failure because the resolver does not exist.

- [ ] Step 3: Implement the resolver.

Use TmdbTvFullDetails season summaries to identify the first incomplete or next regular season. Use the existing watched-coordinate set to find the first missing episode within that season. Avoid scanning already complete seasons.

- [ ] Step 4: Run the focused test and expect PASS.

- [ ] Step 5: Commit.

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/seriesprogress/service src/test/java/com/watchwise/watchwise_api/seriesprogress/service/impl/SeriesProgressNextEpisodeResolverImplTest.java
git commit -m "feat(progress): resolve next episode"
~~~

---

## Task 3: Add one page-level presentation enricher

**Interface**

Create SeriesProgressPresentationEnricher as a Spring component with this method:

Map<String, Enrichment> enrich(
    User targetUser,
    List<SeriesProgressReadRepository.SeriesProgressCandidate> pageRows,
    Map<String, List<SeasonProgressDTO>> seasonProgressBySeries,
    Set<WatchedEpisodeCoordinate> watchedCoordinates,
    Map<String, String> customSeriesPosters
)

Define Enrichment as an immutable nested record with:

- seriesTitle
- seriesPosterPath
- lastWatchedEpisodeTitle
- nextEpisode

- [ ] Step 1: Write failing tests for:

1. localized series title and poster;
2. custom poster remaining separate from the TMDB poster;
3. last watched episode title resolution;
4. next episode delegation;
5. missing last episode coordinate;
6. TMDB not-found presentation data;
7. optional presentation TMDB failure returning null presentation fields.

- [ ] Step 2: Run:

~~~powershell
.\mvnw.cmd test "-Dtest=SeriesProgressPresentationEnricherTest"
~~~

Expected: compilation failure because the enricher does not exist.

- [ ] Step 3: Implement the enricher.

For each page series:

1. Read title and poster from TmdbClient.getTvFullDetails using the target user's preferred language.
2. Resolve lastWatchedEpisodeTitle only when lastWatchedSeasonNumber and lastWatchedEpisodeNumber are present.
3. Delegate next episode selection to SeriesProgressNextEpisodeResolver.
4. Reuse the existing TmdbClient cache.
5. Never call /contents/details.
6. Never create Content references in a GET.
7. If optional presentation data is unavailable, return the numeric row with null presentation fields. Required snapshot hydration failures continue to use the existing 502 path.

Do not add or change an executor for this feature. Use the existing TmdbClient cache synchronously; the resolver must avoid duplicate calls by sharing the already fetched series details within each row.

- [ ] Step 4: Run the focused test and expect PASS.

---

## Task 4: Integrate the enricher into the existing progress service

**Files**

- Modify DiaryEntryServiceImpl.java.
- Modify DiaryEntryServiceImplTest.java.
- Modify DiaryEntryControllerTest.java.

- [ ] Step 1: Write failing service tests.

Assert that:

- watched count, total released count, percentage, remaining episodes, remaining runtime, and season progress are unchanged;
- seriesTitle, seriesPosterPath, lastWatchedEpisodeTitle, and nextEpisode are populated;
- aggregate values still represent all eligible series, not only the current page;
- LAST_RELEASED still sorts using lastReleasedEpisodeDate before pagination;
- private-profile authorization is unchanged;
- null runtime and unavailable optional presentation fields remain representable.

- [ ] Step 2: Run:

~~~powershell
.\mvnw.cmd test "-Dtest=DiaryEntryServiceImplTest,DiaryEntryControllerTest"
~~~

Expected: failure because the enricher is not integrated.

- [ ] Step 3: Integrate without changing the endpoint signature.

After loading page rows, snapshots, season progress, and custom posters:

1. Call existing findWatchedEpisodeCoordinates once for the page series IDs.
2. Group the coordinates by series TMDB ID.
3. Convert the existing per-series SeasonProgress maps into immutable lists for the enricher.
4. Call SeriesProgressPresentationEnricher once for the page.
5. Pass the resulting enrichment to SeriesInProgressResponseDTO.withPresentation after the existing SeriesProgressAssembler creates the numeric response.
6. Keep all existing scalar progress and aggregate calculations unchanged.

Do not add diary-entry IDs, scores, comments, companions, database writes, or new Content lookups.

- [ ] Step 4: Run the focused tests and expect PASS.

- [ ] Step 5: Commit.

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImpl.java src/main/java/com/watchwise/watchwise_api/diaryentry/dto/SeriesInProgressResponseDTO.java src/test/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImplTest.java src/test/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryControllerTest.java
git commit -m "feat(progress): enrich in-progress series"
~~~

---

## Task 5: Make Home consume the shared result without changing its response

**Files**

- Modify HomeNextEpisodeAssembler.java.
- Modify HomeNextEpisodeAssemblerTest.java.

- [ ] Step 1: Add a regression test where an episode is skipped and Home receives the same next episode selected by Progress.

- [ ] Step 2: Run and verify failure:

~~~powershell
.\mvnw.cmd test "-Dtest=HomeNextEpisodeAssemblerTest"
~~~

- [ ] Step 3: Adapt Home.

Read nextEpisode and seriesTitle from the enriched progress row. Keep Home's existing preview DTO, community-score lookup, and response shape. Preserve its existing local Content lookup only for community stats; do not add that lookup to Progress.

- [ ] Step 4: Run:

~~~powershell
.\mvnw.cmd test "-Dtest=HomeNextEpisodeAssemblerTest,SummaryServiceImplTest"
~~~

Expected: PASS.

---

## Task 6: Update contract and domain documentation

**Files**

- Modify docs/context/openapi.yaml.
- Modify docs/context/business-rules.md.
- Modify docs/context/tmdb-reqs-by-screen.md.
- Modify docs/context/progress.md.

- [ ] Step 1: Add the four response fields and ProgressEpisode schema to OpenAPI. Document nullable nextEpisode, nullable presentation fields, future availableToWatch=false, and the fact that no Content reference is created during the GET.

- [ ] Step 2: Document the next-episode rule: first unrecorded regular episode ordered by season and episode, Specials excluded, completed seasons skipped, future episodes returned but unavailable.

- [ ] Step 3: Document that the Progress page uses one watched-coordinate query plus cached TMDB enrichment and does not require one contents/details call per row.

- [ ] Step 4: Append only the shipped implementation to the chronological progress log. Do not add roadmap or next-steps sections.

Leave documentation changes worktree-only according to AGENTS.md.

---

## Task 7: Verification gate

- [ ] Step 1: Run the focused suite.

~~~powershell
.\mvnw.cmd test "-Dtest=SeriesInProgressResponseDTOTest,SeriesProgressNextEpisodeResolverImplTest,SeriesProgressPresentationEnricherTest,DiaryEntryServiceImplTest,DiaryEntryControllerTest,HomeNextEpisodeAssemblerTest,SummaryServiceImplTest"
~~~

- [ ] Step 2: Run the full suite.

~~~powershell
.\mvnw.cmd test
~~~

Repository tests require Docker because they use Testcontainers.

- [ ] Step 3: Review the implementation.

Verify visibility checks, generic error handling, no N+1 database queries, no duplicate counting from rewatches, no Specials or future episodes inflating totals, aggregate scope across all rows, no TMDB persistence, and no diary state in the new response.

- [ ] Step 4: Inspect the final diff.

~~~powershell
git diff --check
git status --short
git diff --stat
~~~

Commit verified code changes only. Never push without explicit authorization.
