# Diary Screens Backend Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the Watchwise API so the Diary History, Diary Reviews, and Review detail screens can load their required counts, direct review data, stable pagination, server-side ordering, daily history totals, and header statistics without introducing a new persisted media model.

**Architecture:** Keep DiaryEntry as the source of truth for watches and reviews. Add read-only projections and additive response fields around the existing Diary, Comment, Content Details, and Summary services. Use batch comment-count queries and a dedicated diary read-query component for dynamic filters/order instead of N+1 lookups or storing TMDB titles in PostgreSQL.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, PostgreSQL, MapStruct, Lombok, MockMvc, Mockito, Testcontainers, Flyway/OpenAPI documentation.

## Global Constraints

- Keep the four content types and the existing Portuguese-schema-to-English Java naming convention.
- Do not add title, poster, backdrop, or other TMDB media columns to `contents`; media details continue to come from TMDB through `ContentDetailsService`.
- Preserve the existing visibility rule: the owner, a public profile, or an accepted follower may read diary data.
- Preserve `watchNumber` immutability; the backend must not turn the edit screen's rewatch toggle into a mutation.
- Do not add a separate Review entity or review-creation route; a review remains a `DiaryEntry.comment`.
- Do not add `TITLE_ASC` server sorting. Content titles are not persisted, and resolving all TMDB titles before pagination would create an unbounded and slow read path. The API contract must expose only the supported server sorts and the UI contract must disable title sorting until a separate design is approved.
- No database migration is expected; all new values are calculated from existing `diary_entries`, `comments`, and `contents` rows.
- Update `docs/context/openapi.yaml`, `docs/context/telas-reqs.md`, `docs/context/business-rules.md`, and `docs/context/progress.md` in the same implementation change, but leave documentation-only files unstaged unless the user explicitly requests a documentation commit.
- Use Conventional Commits without a `Co-Authored-By` trailer. Never push without explicit user approval.

## API shape after implementation

The implementation must provide these additive capabilities:

```text
GET /users/{userId}/diary
  query: type, dateFrom, dateTo, hasReview, seriesTmdbId, score,
         scoreFrom, scoreTo,
         sortBy=NEWEST|OLDEST|RATING_DESC|RATING_ASC, page, size
  response: PageResponse<DiaryEntry> with commentsCount per item

GET /diary/{diaryEntryId}
  response: DiaryEntry with commentsCount and the existing visibility rules

GET /users/{userId}/diary/daily-summary
  query: type, dateFrom, dateTo, hasReview, seriesTmdbId, score,
         scoreFrom, scoreTo
  response: DiaryDaySummary[] ordered newest day first

GET /users/{userId}/diary/series
  response: DiarySeriesOption[] with seriesTmdbId, title, and entriesCount

GET /contents/{contentId}/reviews
  response: ContentReview items with commentsCount for DIARY and DROPPED sources

GET /users/{userId}/summary/home
  response: existing fields plus distinctSeriesWatched
```

The diary-day bucket is `watchedDate` when present and the calendar date of `createdAt` when `watchedDate` is null. The daily summary counts diary rows, including rewatches and automatically generated completion rows, because the History screen represents logged diary entries rather than distinct media.

The existing `GET /users/{userId}/summary/home` remains the source for the Diary header's global movie, episode, play, and minute totals. Its existing `watchCountByDayLast30Days` is reusable for a 30-day count-only chart, but it cannot replace the History daily summary because it has no per-day minutes, no arbitrary date range, and no History filters.

The History and Reviews filters reuse the existing Diary query contract: `type` accepts `MOVIE`, `SERIES`, `SEASON`, and `EPISODE`; `dateFrom` and `dateTo` are inclusive `watchedDate` bounds; and `seriesTmdbId` uses the normalized series identity. `GET /users/{userId}/diary/series` supplies the selectable series, including each series title and entry count, without requiring the client to reconstruct those options from paginated diary pages.

The existing `year` parameter remains supported on `GET /users/{userId}/diary` for backward compatibility, but it is not part of the Diary screen filter model and is not added to the new daily-summary route. The screens use `dateFrom` and `dateTo` exclusively.

---

### Task 1: Add batched review comment counts to Diary and Content Review responses

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/dto/DiaryEntryResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/dto/ContentReviewResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/mapper/DiaryEntryMapper.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/mapper/DiaryEntryMapperTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryControllerIntegrationTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/comment/service/impl/CommentPreviewAssemblerTest.java`

**Interfaces:**
- Consumes: lightweight count-only methods added to `CommentPreviewAssembler`: `countDiaryEntries(Collection<UUID>)` and `countDroppedEntries(Collection<UUID>)`.
- Produces: `DiaryEntryResponseDTO.commentsCount()` and `ContentReviewResponseDTO.commentsCount()` as non-negative `long` values.

- [ ] **Step 1: Extend the response records while preserving existing Java test constructors**

  Add `commentsCount` beside the existing social counters. Keep the existing convenience constructors and make them delegate with `0L`, so unrelated Summary and service fixtures remain source-compatible. Add `withCommentsCount(long)` to `DiaryEntryResponseDTO`, and make `withCustomPosterUrl` preserve the new field.

  Update `ContentReviewResponseDTO.fromDiary` to copy `entry.commentsCount()`. The dropped-review constructor path must also populate the new field.

- [ ] **Step 2: Update MapStruct mappings and mapper tests**

  Mark the derived `commentsCount` target as ignored in `DiaryEntryMapper`, because it is not a `DiaryEntry` column. Assert that mapping still produces zero before service enrichment and that `withCustomPosterUrl` does not reset the count.

- [ ] **Step 3: Write failing service tests for batched counts**

  Add tests proving that:

  - a diary page with multiple entries calls the count-only assembler method once with all entry IDs and maps missing IDs to zero;
  - a content review page maps diary and dropped comment counts independently;
  - a newly created or updated diary entry returns zero or the current count without making one comment query per item;
  - `ContentReviewResponseDTO.fromDiary` preserves `commentsCount`.

- [ ] **Step 4: Implement one enrichment map per response page**

  Add count-only methods to `CommentPreviewAssembler` that execute only the existing grouped count queries; do not load recent comments or comment likes for this screen. In `DiaryEntryServiceImpl`, load the resulting count maps in batch beside likes, companions, and custom posters. Pass the count into the existing response-enrichment path. Reuse the same map in `getReviewsForContent`; load dropped-entry counts for dropped reviews. Keep creation responses at zero because a new diary row has no comments, and query the current count for update/single-read responses.

- [ ] **Step 5: Verify the focused tests**

  Run:

  ```powershell
  .\mvnw.cmd test "-Dtest=DiaryEntryMapperTest,DiaryEntryServiceImplTest,CommentPreviewAssemblerTest,DiaryEntryControllerIntegrationTest"
  ```

  Expected: PASS, with no per-item comment-count query introduced.

- [ ] **Step 6: Commit the code and tests**

  ```powershell
  git add src/main/java/com/watchwise/watchwise_api/diaryentry src/test/java/com/watchwise/watchwise_api/diaryentry src/test/java/com/watchwise/watchwise_api/comment/service/impl/CommentPreviewAssemblerTest.java
  git commit -m "feat(diary): expose review comment counts"
  ```

  After committing, confirm that the commit has no `Co-Authored-By` trailer or other self-attribution.

---

### Task 2: Add a direct, visibility-aware DiaryEntry read endpoint

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/service/DiaryEntryService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryController.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryControllerTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryControllerIntegrationTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImplTest.java`

**Interfaces:**
- Consumes: the existing `DiaryEntryRepository.findByIdWithContentAndUser`, diary visibility checks, likes, companions, custom-poster lookup, and Task 1 comment-count enrichment.
- Produces: `DiaryEntryService.getDiaryEntry(UUID viewerId, UUID diaryEntryId)` and `GET /diary/{diaryEntryId}`.

- [ ] **Step 1: Write failing controller and service tests**

  Cover these cases:

  - the owner receives the enriched entry;
  - a public-profile viewer receives the entry;
  - an accepted follower receives the entry;
  - a viewer without access receives `403` with the same message used by the existing diary list;
  - an unknown UUID receives `404`;
  - the response contains `commentsCount`, `likesCount`, `likedByMe`, `watchedWith`, and the owner's `customPosterUrl`.

- [ ] **Step 2: Add the service contract and controller mapping**

  Add:

  ```java
  DiaryEntryResponseDTO getDiaryEntry(UUID viewerId, UUID diaryEntryId);
  ```

  and:

  ```java
  @GetMapping("/diary/{diaryEntryId}")
  public ResponseEntity<DiaryEntryResponseDTO> getDiaryEntry(@PathVariable UUID diaryEntryId)
  ```

  Resolve the viewer from the existing security context, matching the other Diary and Comment controllers.

- [ ] **Step 3: Implement owner-based visibility and enrichment**

  Load the entry with content and user. Call the existing `assertCanViewDiary(viewerId, entry.user.id, entry.user)` before returning data. Load the viewer's like state, companions, owner poster override, and comment count. Do not expose the entry through a separate public share route; this endpoint remains behind the existing authenticated visibility policy.

- [ ] **Step 4: Update OpenAPI and verify the endpoint**

  Document the route, `401`, `403`, and `404` responses in `docs/context/openapi.yaml`. Run:

  ```powershell
  .\mvnw.cmd test "-Dtest=DiaryEntryControllerTest,DiaryEntryControllerIntegrationTest,DiaryEntryServiceImplTest"
  ```

  Expected: PASS.

- [ ] **Step 5: Commit the code and tests**

  ```powershell
  git add src/main/java/com/watchwise/watchwise_api/diaryentry src/test/java/com/watchwise/watchwise_api/diaryentry
  git commit -m "feat(diary): add direct entry reads"
  ```

  After committing, confirm that the commit has no `Co-Authored-By` trailer or other self-attribution.

---

### Task 3: Add stable server-side Diary ordering for supported sort modes

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntrySort.java`
- Create: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntrySearchCriteria.java`
- Create: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryReadRepository.java`
- Create: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryReadRepositoryImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryController.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/service/DiaryEntryService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryReadRepositoryTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryControllerTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryControllerIntegrationTest.java`

**Interfaces:**
- Consumes: the existing Diary filters: `year`, `type`, `dateFrom`, `dateTo`, `hasReview`, `seriesTmdbId`, and `score`, plus the inclusive range filters `scoreFrom` and `scoreTo`.
- Produces: `DiaryEntrySort.NEWEST`, `OLDEST`, `RATING_DESC`, and `RATING_ASC`, with deterministic pagination.

- [ ] **Step 1: Define the supported sort contract**

  Create:

  ```java
  public enum DiaryEntrySort {
      NEWEST,
      OLDEST,
      RATING_DESC,
      RATING_ASC
  }
  ```

  Define `NEWEST` as `createdAt DESC, id DESC`, `OLDEST` as `createdAt ASC, id ASC`, and rating modes as score descending/ascending with null scores last and `createdAt`/`id` as deterministic tie-breakers. Do not add `TITLE_ASC` because titles are not stored locally.

  Preserve the existing exact `score` filter for compatibility. Add optional inclusive `scoreFrom` and `scoreTo` filters, each constrained to 1–10. Reject `score` combined with either range bound, reject `scoreFrom > scoreTo`, and keep the existing exact-score behavior when only `score` is provided.

- [ ] **Step 2: Write repository tests before replacing the fixed-order query**

  Using the existing Testcontainers repository-test pattern, persist entries with identical timestamps, null and non-null scores, different owners, and each filter dimension. Assert:

  - each sort order is global across pages;
  - null scores stay at the end of rating sorts;
  - ties are stable across repeated requests;
  - all existing filters retain their current semantics;
  - `type` filters the content level;
  - `dateFrom` and `dateTo` filter `watchedDate` inclusively;
  - `seriesTmdbId` filters `SERIES` by its own TMDB ID and `SEASON`/`EPISODE` by their parent series TMDB ID;
  - the legacy `year` path remains mutually exclusive with `dateFrom` and `dateTo` on the existing diary endpoint;
  - `scoreFrom=7&scoreTo=10` includes scores 7, 8, 9, and 10 and excludes nulls and scores below 7;
  - exact `score` cannot be combined with `scoreFrom` or `scoreTo`;
  - invalid bounds and reversed ranges return the project's `ApiError` format;
  - rows from another user never enter the result.

- [ ] **Step 3: Implement a Criteria-based read repository**

  Move the optional-filter query into `DiaryEntryReadRepositoryImpl` using `EntityManager` Criteria queries. Build one content-fetch query and one count query from `DiaryEntrySearchCriteria`; include predicates for exact score and the inclusive score range, apply the sort order explicitly, add `JOIN FETCH content`, and create `PageImpl<DiaryEntry>` from the requested offset and total count. Keep the existing `DiaryEntryRepository` for writes and specialized queries.

- [ ] **Step 4: Thread `sortBy` through the API without breaking Java callers**

  Add `DiaryEntrySort sortBy` and `scoreFrom`/`scoreTo` to the full `DiaryEntryService.getDiaryEntries` signature and preserve the existing overload with `NEWEST` and null range bounds as defaults. Keep the legacy `year` parameter available on the existing controller route, but do not expose it as a screen filter. Keep `PageResponseDTO` unchanged.

- [ ] **Step 5: Verify controller binding and pagination**

  Add MockMvc assertions for each enum value, omitted `sortBy`, invalid enum input, and page metadata. Run:

  ```powershell
  .\mvnw.cmd test "-Dtest=DiaryEntryReadRepositoryTest,DiaryEntryControllerTest,DiaryEntryControllerIntegrationTest"
  ```

  Expected: PASS. Docker must be running for the repository test.

- [ ] **Step 6: Commit the code and tests**

  ```powershell
  git add src/main/java/com/watchwise/watchwise_api/diaryentry src/test/java/com/watchwise/watchwise_api/diaryentry
  git commit -m "feat(diary): add server-side entry sorting"
  ```

  After committing, confirm that the commit has no `Co-Authored-By` trailer or other self-attribution.

---

### Task 4: Add daily Diary aggregates for History grouping

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/diaryentry/dto/DiaryDaySummaryDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryController.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/service/DiaryEntryService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryReadRepository.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryReadRepositoryImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryReadRepositoryTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryControllerIntegrationTest.java`

**Interfaces:**
- Consumes: the same user and filters as `GET /users/{userId}/diary`, including `scoreFrom` and `scoreTo`.
- Produces: `DiaryDaySummaryDTO(LocalDate date, long plays, long totalMinutes)` from `GET /users/{userId}/diary/daily-summary`.

- [ ] **Step 1: Write the failing aggregate tests**

  Assert that the endpoint:

  - groups by `watchedDate` when it exists;
  - uses `createdAt.toLocalDate()` when `watchedDate` is null;
  - sums `content.runtimeMinutes`, treating null runtime as zero;
  - counts rewatches and auto-generated rows as separate plays;
  - applies type, date range, year, review, series, score, visibility, and user filters;
  - orders days newest first and returns an empty list when no rows match.

- [ ] **Step 2: Implement the projection and service method**

  Add the DTO and a repository projection/query in `DiaryEntryReadRepositoryImpl`. Reuse the same type, date, review, series, exact-score, and score-range filter-building code from Task 3 so the list and daily-summary endpoints cannot drift. Do not add the legacy `year` parameter to this new route. Return `List<DiaryDaySummaryDTO>` from the service.

- [ ] **Step 3: Add the controller route and OpenAPI contract**

  Add:

  ```java
  @GetMapping("/users/{userId}/diary/daily-summary")
  public ResponseEntity<List<DiaryDaySummaryDTO>> getDiaryDailySummary(...)
  ```

  Use the same optional query parameters as the diary list except pagination and sorting. Document the null-date fallback explicitly.

- [ ] **Step 4: Verify integration behavior**

  Run:

  ```powershell
  .\mvnw.cmd test "-Dtest=DiaryEntryReadRepositoryTest,DiaryEntryControllerIntegrationTest"
  ```

  Expected: PASS.

- [ ] **Step 5: Commit the code and tests**

  ```powershell
  git add src/main/java/com/watchwise/watchwise_api/diaryentry src/test/java/com/watchwise/watchwise_api/diaryentry
  git commit -m "feat(diary): add daily history summaries"
  ```

  After committing, confirm that the commit has no `Co-Authored-By` trailer or other self-attribution.

---

### Task 5: Expose the distinct-series count used by the Diary header

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/HomeSummaryResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java`
- Modify: `docs/context/openapi.yaml`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerIntegrationTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java`

**Interfaces:**
- Consumes: the existing normalized-series identity used by `DiarySeriesOption`.
- Produces: `HomeSummaryResponseDTO.distinctSeriesWatched()`.

- [ ] **Step 1: Define the metric precisely in tests**

  The metric counts distinct normalized series identities across `SERIES`, `SEASON`, and `EPISODE` diary rows. A `SERIES` row uses `content.tmdbId`; `SEASON` and `EPISODE` rows use `content.seriesTmdbId`. Movies contribute nothing. Rewatch rows do not increase the count.

- [ ] **Step 2: Extend the home aggregate projection and DTO**

  Add `distinctSeriesWatched` to the `HomeWatchAggregate` projection and its native query. Add the field to `HomeSummaryResponseDTO`, preserving the old convenience constructor with a default value so existing unit fixtures remain readable.

- [ ] **Step 3: Thread the value through `SummaryServiceImpl`**

  Read the projection value beside the existing movie and episode totals and pass it to the response. Do not change the privacy check or the existing home-summary lists.

- [ ] **Step 4: Verify the metric and JSON contract**

  Assert the value in repository and MockMvc tests, including duplicate rewatches and the same series represented at episode, season, and series levels. Run:

  ```powershell
  .\mvnw.cmd test "-Dtest=DiaryEntryRepositoryTest,SummaryControllerIntegrationTest,SummaryControllerTest,SummaryServiceImplTest"
  ```

- [ ] **Step 5: Commit the code and tests**

  ```powershell
  git add src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java src/main/java/com/watchwise/watchwise_api/summary src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java src/test/java/com/watchwise/watchwise_api/summary
  git commit -m "feat(summary): expose distinct watched series"
  ```

  After committing, confirm that the commit has no `Co-Authored-By` trailer or other self-attribution.

---

### Task 6: Synchronize the API and domain documentation

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/telas-reqs.md`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

**Interfaces:**
- Consumes: the final Java endpoints and DTOs from Tasks 1–5.
- Produces: documentation that matches the shipped backend contract and explicitly records unsupported title sorting, daily-date fallback, and distinct-series semantics.

- [ ] **Step 1: Update OpenAPI paths and schemas**

  Add the direct Diary GET route, the daily-summary route, the existing diary-series option route, `sortBy` and its enum values, `scoreFrom`/`scoreTo` with inclusive-range validation, `commentsCount` to `DiaryEntry` and `ContentReview`, `distinctSeriesWatched` to `HomeSummary`, and the correct `401`/`403`/`404`/`400` responses. Document type, series, and inclusive date filters for the screens, and retain the legacy `year`/date-range mutual exclusion only on the existing diary endpoint.

- [ ] **Step 2: Update screen requirements**

  In `docs/context/telas-reqs.md`, replace the previous client-only assumptions with the new routes. State that content titles/posters/backdrops still come from `GET /contents/details`, that comment counts are included in Diary/Review responses, and that Title A–Z is not a backend-supported sort.

- [ ] **Step 3: Update business rules and progress**

  Add entries under `DiaryEntry` and `Summary` describing batched comment counts, visibility on direct reads, stable sort semantics, daily bucket fallback, and normalized distinct-series counting. Append the shipped changes to the current date section of `docs/context/progress.md`; do not add a future-work section.

- [ ] **Step 4: Review documentation consistency**

  Run:

  ```powershell
  rg -n "commentsCount|daily-summary|distinctSeriesWatched|sortBy|TITLE_ASC|createdAt.toLocalDate" docs/context/openapi.yaml docs/context/telas-reqs.md docs/context/business-rules.md docs/context/progress.md
  git diff --check
  ```

  Expected: every new response field and route appears consistently, and `git diff --check` reports no whitespace errors. Keep these documentation files unstaged unless the user requests otherwise.

---

### Task 7: Run the complete verification suite and perform the backend loophole audit

**Files:**
- Test: all existing Maven tests
- Review: `src/main/java/com/watchwise/watchwise_api/diaryentry`, `src/main/java/com/watchwise/watchwise_api/comment`, `src/main/java/com/watchwise/watchwise_api/summary`, and the synchronized documentation

**Interfaces:**
- Consumes: all implementation commits and documentation from Tasks 1–6.
- Produces: verified backend behavior ready for frontend integration.

- [ ] **Step 1: Run the focused regression tests once more**

  ```powershell
  .\mvnw.cmd test "-Dtest=DiaryEntryControllerIntegrationTest,DiaryEntryControllerTest,DiaryEntryServiceImplTest,DiaryEntryReadRepositoryTest,DiaryEntryRepositoryTest,SummaryControllerIntegrationTest,SummaryControllerTest,SummaryServiceImplTest,CommentPreviewAssemblerTest,DiaryEntryMapperTest"
  ```

- [ ] **Step 2: Run the full suite**

  ```powershell
  .\mvnw.cmd test
  ```

  Expected: PASS. Docker must be running because repository tests use Testcontainers.

- [ ] **Step 3: Audit the recurring bug patterns**

  Confirm that:

  - every new controller binding failure still resolves to the project's `ApiError` format;
  - comment counts are loaded in batches and never in a loop;
  - direct reads apply the same privacy rule as list reads;
  - daily summaries and paginated rows use the identical filters;
  - null score, null runtime, null watched date, rewatches, auto-generated rows, and duplicate normalized series identities have explicit tests;
  - no endpoint exposes another user's custom poster override;
  - no title data is persisted in the `contents` table;
  - docs and OpenAPI match the actual method signatures and JSON names.

- [ ] **Step 4: Inspect the final worktree and report evidence**

  ```powershell
  git status --short
  git log -5 --oneline
  ```

  Report the test command and result, the local commit hashes, any intentionally unstaged documentation files, and the remaining product limitation: Title A–Z sorting and public share links are outside this backend plan.
