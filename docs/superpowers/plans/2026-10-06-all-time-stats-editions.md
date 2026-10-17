# All Time Stats Editions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ] syntax) for tracking.

**Goal:** Add a clean typed All Time Edition endpoint for the Movie and TV tabs, keep the existing combined All Time endpoint unchanged, and expose the display identifiers needed by all three review screens.

**Architecture:** The existing combined endpoint remains a compatibility seam. A new All Time Edition endpoint returns one typed projection per request behind a small AllTimeStatsReader interface. Its database adapter is isolated in AllTimeStatsQueryRepository. SummaryServiceImpl keeps only visibility and endpoint orchestration. Month and Year retain their existing endpoints and only gain the missing internal content IDs required for batch display details.

**Tech Stack:** Spring Boot 4.1, Java 21, Spring Data JPA, PostgreSQL, Flyway, Maven Wrapper, JUnit 5, Mockito, AssertJ, MockMvc, Testcontainers.

## Global Constraints

- Keep GET /users/{userId}/summary/all-time and AllTimeStatsResponseDTO unchanged.
- Add only GET /users/{userId}/summary/all-time/edition?type=MOVIE|SERIES for the new screen-specific contract.
- Validate the new endpoint type in the service and reject missing, SEASON, or EPISODE with BadRequestException("type must be one of: MOVIE, SERIES").
- Use MOVIE diary entries for Movie Edition and EPISODE diary entries for TV watch/runtime aggregates.
- Use parent SERIES content for TV title, poster, genre, country, decade, and title-level ranking data.
- Do not remove topWatchCompanions or fields from DiaryEntryResponseDTO.
- Keep totalTheaterVisits; the typed DTO returns the movie count and zero for TV Edition.
- Do not store TMDB display metadata in Content; use existing content details and batch endpoints.
- Keep Java names in English and follow the existing summary package structure.
- Update openapi.yaml, telas.md, telas-reqs.md, tmdb-reqs-by-screen.md, progress.md, and business-rules.md after the code is shipped.
- Documentation-only changes remain uncommitted under repository instructions.
- Use Conventional Commits without a Co-Authored-By trailer; never push without explicit approval.

---

## Current File Map

- src/main/java/com/watchwise/watchwise_api/summary/controller/SummaryController.java
- src/main/java/com/watchwise/watchwise_api/summary/service/SummaryService.java
- src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java
- src/main/java/com/watchwise/watchwise_api/summary/dto/AllTimeStatsResponseDTO.java
- src/main/java/com/watchwise/watchwise_api/summary/dto/SeriesWatchTimeDTO.java
- src/main/java/com/watchwise/watchwise_api/summary/dto/LongestWatchedItemDTO.java
- src/main/java/com/watchwise/watchwise_api/summary/repository/ProfileSummaryQueryRepository.java
- src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java
- src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java
- src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerTest.java
- src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerIntegrationTest.java
- src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java

## Task 1: Add the typed contract and endpoint

**Files:**

- Create: src/main/java/com/watchwise/watchwise_api/summary/dto/AllTimeEditionStatsDTO.java
- Modify: src/main/java/com/watchwise/watchwise_api/summary/service/SummaryService.java
- Modify: src/main/java/com/watchwise/watchwise_api/summary/controller/SummaryController.java
- Test: src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerTest.java

**Interface:**

~~~java
public record AllTimeEditionStatsDTO(
        ContentType type,
        long watchedCount,
        long minutesWatched,
        long totalTheaterVisits,
        double averageMinutesPerMonth,
        double averageMinutesPerWeek,
        double averageMinutesPerDay,
        List<YearCountDTO> watchCountByYear,
        List<DecadeCountDTO> watchCountByDecade,
        List<CountryCountDTO> watchCountByCountry,
        List<ContentWatchCountDTO> mostLoggedContent,
        List<GenreCountDTO> genreCounts,
        List<RatingCountDTO> ratingsDistribution,
        List<DiaryEntryResponseDTO> topRated,
        List<DiaryEntryResponseDTO> bottomRated) {
}
~~~

- [ ] Write a controller test for Movie Edition delegation.
- [ ] Run: .\mvnw.cmd test "-Dtest=SummaryControllerTest". Expected: the new test fails because the route and service method do not exist.
- [ ] Add SummaryService.getAllTimeStatsEdition(viewerId, userId, type).
- [ ] Add GET /users/{userId}/summary/all-time/edition. Declare type as optional at binding level and validate it in the service so missing values use the project BadRequestException format.
- [ ] Run the focused controller test again. Expected: PASS.
- [ ] Commit source and test changes with: feat(summary): add all-time edition endpoint.
- [ ] Confirm the commit has no Co-Authored-By trailer.

## Task 2: Isolate typed queries behind a read module

**Files:**

- Create: src/main/java/com/watchwise/watchwise_api/summary/repository/AllTimeStatsQueryRepository.java
- Create: src/main/java/com/watchwise/watchwise_api/summary/service/AllTimeStatsReader.java
- Create: src/main/java/com/watchwise/watchwise_api/summary/service/impl/AllTimeStatsReaderImpl.java
- Create: src/test/java/com/watchwise/watchwise_api/summary/repository/AllTimeStatsQueryRepositoryTest.java
- Create: src/test/java/com/watchwise/watchwise_api/summary/service/impl/AllTimeStatsReaderImplTest.java
- Modify: src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java
- Modify: src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java

**Interfaces:**

~~~java
AllTimeEditionStatsDTO read(UUID userId, ContentType type);
~~~

- [ ] Write reader tests for missing/invalid type, empty data, Movie data, and TV data.
- [ ] Write repository tests for type isolation, first-watch date, yearly counts, decade/country deduplication, most-logged parent series, typed rankings, and theater visits.
- [ ] Run: .\mvnw.cmd test "-Dtest=AllTimeStatsReaderImplTest,AllTimeStatsQueryRepositoryTest". Expected: FAIL before implementation. Repository tests require Docker.
- [ ] Add explicit query methods for Movie and Series instead of a stringly typed SQL switch:
  - movie and episode count/runtime sums;
  - first watched date by watched content type;
  - yearly watch counts;
  - distinct movie titles by decade/country;
  - distinct parent-series titles by decade/country;
  - most-logged movies by content ID;
  - most-logged series by parent content ID while counting episode entries;
  - title-level top and bottom Movie/Series rankings;
  - theater visits scoped to movies.
- [ ] Reuse ProfileSummaryQueryRepository.countLatestScoresByUserIdAndContentType for the latest-score histogram; do not duplicate its SQL.
- [ ] Implement AllTimeStatsReaderImpl with the existing mapper, poster, Top5, content, and user collaborators needed to assemble the response.
- [ ] Keep SummaryServiceImpl.getAllTimeStats unchanged. Make getAllTimeStatsEdition perform user lookup, privacy authorization, type validation, and delegation to AllTimeStatsReader.
- [ ] Run: .\mvnw.cmd test "-Dtest=AllTimeStatsReaderImplTest,SummaryServiceImplTest". Expected: PASS, including all existing combined-summary tests.
- [ ] Commit with: feat(summary): add typed all-time reader.
- [ ] Confirm the commit has no Co-Authored-By trailer.

## Task 3: Expose content IDs for Month and Year enrichment

**Files:**

- Modify: src/main/java/com/watchwise/watchwise_api/summary/dto/SeriesWatchTimeDTO.java
- Modify: src/main/java/com/watchwise/watchwise_api/summary/dto/LongestWatchedItemDTO.java
- Modify: src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java
- Modify: src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java
- Modify: src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java
- Modify: src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java

- [ ] Write tests asserting Month TV rankings expose the parent series UUID and Year longest rankings expose the movie or parent-series UUID.
- [ ] Run: .\mvnw.cmd test "-Dtest=SummaryServiceImplTest". Expected: FAIL before the DTO/projection changes.
- [ ] Add UUID contentId to SeriesWatchTimeDTO and LongestWatchedItemDTO.
- [ ] Extend DiaryEntryRepository.SeriesRuntime with getContentId() and join the parent SERIES content in the existing runtime query.
- [ ] Update service mapping without changing ordering or limits.
- [ ] Run: .\mvnw.cmd test "-Dtest=DiaryEntryRepositoryTest,SummaryServiceImplTest". Expected: PASS.
- [ ] Commit with: feat(summary): expose ranked content identifiers.
- [ ] Confirm the commit has no Co-Authored-By trailer.

## Task 4: Prove the HTTP and security contracts

**Files:**

- Modify: src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerTest.java
- Modify: src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerIntegrationTest.java

- [ ] Test the unchanged combined endpoint and assert its existing JSON fields and totalTheaterVisits remain unchanged.
- [ ] Test /summary/all-time/edition?type=MOVIE with movie and episode fixtures; assert only Movie Edition data.
- [ ] Test /summary/all-time/edition?type=SERIES with duplicate episodes from one series; assert parent-series deduplication and typed ratings.
- [ ] Test missing/invalid type, no access-token cookie, and private-profile access. Assert the project's ApiError shape.
- [ ] Run: .\mvnw.cmd test "-Dtest=SummaryControllerTest,SummaryControllerIntegrationTest". Expected: PASS.
- [ ] Commit with: test(summary): cover all-time edition endpoint.
- [ ] Confirm the commit has no Co-Authored-By trailer.

## Task 5: Synchronize the documented screen contract

**Files:**

- Modify: docs/context/openapi.yaml
- Modify: docs/context/telas.md
- Modify: docs/context/telas-reqs.md
- Modify: docs/context/tmdb-reqs-by-screen.md
- Modify: docs/context/progress.md
- Modify: docs/context/business-rules.md

- [ ] Document the new /users/{userId}/summary/all-time/edition route and AllTimeEditionStats schema.
- [ ] Leave the old combined endpoint schema and behavior documented unchanged.
- [ ] Document that the frontend calls the new endpoint once per edition and batches content IDs through GET /contents/details?ids=....
- [ ] Document the Movie versus EPISODE/SERIES split, series deduplication, latest-score histogram, typed-average denominator, and theater-count scope.
- [ ] Add the shipped behavior to progress.md only after implementation and verification.
- [ ] Search the docs for old claims that All Time only has one screen response and replace them with the combined-endpoint plus typed-edition contract.

No documentation commit is created for this task.

## Task 6: Complete verification

- [ ] Run summary tests:

~~~powershell
.\mvnw.cmd test "-Dtest=SummaryControllerTest,SummaryControllerIntegrationTest,SummaryServiceImplTest,AllTimeStatsReaderImplTest,AllTimeStatsQueryRepositoryTest,DiaryEntryRepositoryTest,ProfileSummaryDataReaderImplTest,ProfileSummaryReaderImplTest"
~~~

- [ ] Run the complete suite:

~~~powershell
.\mvnw.cmd test
~~~

Expected result: PASS. Repository tests require Docker.

- [ ] Inspect the final worktree:

~~~powershell
git status --short
git diff --check
git log -8 --oneline
~~~

Confirm only intended files changed and every local code commit has no co-authorship trailer.

- [ ] Run graphify update after code changes:

~~~powershell
graphify update .
~~~

## Plan Self-Review

- The legacy combined endpoint and DTO are untouched.
- The typed screen contract is direct: one endpoint, one DTO, one reader interface.
- New typed SQL is isolated in AllTimeStatsQueryRepository.
- totalTheaterVisits remains in scope; unused companions and generic diary fields are preserved but not added to the screen.
- Month/Year identifier enrichment is included for the existing details batch flow.
- Each implementation area has tests and an explicit verification command.

