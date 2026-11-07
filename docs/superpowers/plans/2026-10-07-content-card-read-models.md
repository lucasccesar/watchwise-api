# Content Card Read Models Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace frontend-driven full-content enrichment with optimized backend read models for content cards across Home, Profile, Diary, Review, Month in Review, Year in Review, All Time Stats, Watchlist, Calendar, TMDB Trending, and Social Feed.

**Architecture:** Keep `ContentDetailsDTO` and `ContentPageDTO` for full content pages. Extract a shared internal `ContentCardAssembler` from the existing child-card assembler; it resolves only requested card facets by reusing batched state/stats services and canonical cached TMDB card metadata. Each screen exposes its own read DTO; no public generic `/contents/previews` endpoint is introduced. TMDB Trending remains TMDB-based, while Watchwise-owned viewer state is resolved separately without creating `Content` during reads.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL, Flyway, MapStruct, Lombok, Caffeine/TMDB proxy, Mockito, MockMvc and Testcontainers.

## Global Constraints

- Do not store movie/series titles, posters, cast or full TMDB records in `contents`.
- Do not create `Content` rows during any read request.
- Do not call TMDB directly from the frontend.
- Do not use `ContentDetailsDTO` or `/contents/{contentId}/page` to render collection cards.
- Do not create a public generic `/contents/previews` endpoint for frontend composition.
- Keep `GET /contents/{contentId}/details`, `GET /contents/details?ids=...` and `GET /contents/{contentId}/page` available for existing consumers and full-detail flows.
- Card resolution must support batch local queries, deduplication, request coalescing and Caffeine caching.
- Existing screen-specific endpoints should be enriched additively when that preserves their meaning; generic domain endpoints must not be overloaded with presentation concerns.
- Viewer state and custom posters are user-scoped and must not enter global content caches.
- Every new request binding failure must resolve through the existing `ApiError` handling.
- Use Java records for DTOs and the existing feature-package structure.
- Use Conventional Commits without a `Co-Authored-By` trailer; never push without explicit authorization.
- Update `docs/context/openapi.yaml`, `docs/context/business-rules.md` and `docs/context/progress.md` with shipped behavior.
- Documentation-only changes remain worktree-only under this repository's `AGENTS.md` convention.

---

## File Map

### Shared card module

- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentCardDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentCardStatsDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentCardViewerStateDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentCardFieldSet.java`
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentPreviewStatus.java`
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/ContentCardSpec.java`
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/ContentCardContext.java`
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentCardAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentChildCardAssembler.java`
- Create: `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentCardAssemblerTest.java`
- Create: `src/test/java/com/watchwise/watchwise_api/content/dto/ContentCardDTOTest.java`

### TMDB card adapter

- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCardMetadata.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCardMetadataKey.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClient.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCacheConfig.java`
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/impl/TmdbCardMetadataResolver.java`
- Create: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbCardMetadataTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientCachingTest.java`

### Screen read models

- Modify or create files under `summary`, `diaryentry`, `watchlist`, `calendar`, `trending` and `feed` DTO/service/controller packages.
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/telas-reqs.md`
- Modify: `docs/telas-reqs-definitivo.md`
- Modify: `docs/context/tmdb-reqs-by-screen.md`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

---

### Task 1: Freeze the shared card contracts and selective fields

**Files:**
- Create the shared card DTOs and enums from the File Map.
- Create: `src/test/java/com/watchwise/watchwise_api/content/dto/ContentCardDTOTest.java`

**Interfaces:**
- Reuses `ContentCoordinate` for content identity.
- Produces `ContentCardSpec` with a coordinate and optional already-known display metadata.
- Produces `ContentCardContext` with language, region, poster owner and viewer IDs.
- Produces `ContentCardFieldSet` with `BASIC_METADATA`, `STATS`, `VIEWER_STATE`, `WATCHLIST_PROGRESS` and `SOCIAL_METADATA`.
- Produces `ContentCardAssembler.assemble(Collection<ContentCardSpec>, ContentCardContext, ContentCardFieldSet)`.

- [ ] **Step 1: Write DTO serialization tests**

  Assert that card JSON contains identity, metadata, nullable stats/viewer state and `previewStatus`, and that absent optional fields serialize as null without inventing values.

- [ ] **Step 2: Run the DTO tests and verify failure**

  Run:

  ```powershell
  .\mvnw.cmd test "-Dtest=ContentCardDTOTest"
  ```

  Expected: compilation failure because the new DTOs do not exist.

- [ ] **Step 3: Implement the DTOs and field-set enum**

  Keep DTOs immutable records. Do not add TMDB-specific fields to `ContentRefDTO` or `ContentDetailsDTO`.

- [ ] **Step 4: Add the contract-evolution test**

  Serialize one existing response before and after the new optional card fields and assert that existing identity, pagination and domain fields retain their JSON names and meanings.

- [ ] **Step 5: Run the focused tests**

  Run the same command and expect PASS.

- [ ] **Step 6: Commit the shared contract**

  ```powershell
  git add src/main/java/com/watchwise/watchwise_api/content/dto src/main/java/com/watchwise/watchwise_api/content/service/ContentCardSpec.java src/main/java/com/watchwise/watchwise_api/content/service/ContentCardContext.java src/test/java/com/watchwise/watchwise_api/content/dto/ContentCardDTOTest.java
  git commit -m "feat(content): define card read contracts"
  ```

  Confirm that the commit contains no `Co-Authored-By` trailer.

### Task 2: Add canonical TMDB card metadata and cache behavior

**Files:**
- Create `TmdbCardMetadata` and `TmdbCardMetadataKey` from the File Map.
- Modify `TmdbClient.java` and `TmdbCacheConfig.java`.
- Create `TmdbCardMetadataResolver.java`.
- Modify `TmdbClientTest.java` and `TmdbClientCachingTest.java`.
- Create `TmdbCardMetadataTest.java`.

**Interfaces:**
- `TmdbClient.getCardMetadata(TmdbCardMetadataKey key, String language)` returns canonical cached card metadata.
- `TmdbCardMetadataResolver` converts `ContentCoordinate` into `TmdbCardMetadataKey`; `common.tmdb` does not depend on the content domain package.
- Existing full-detail methods populate the same canonical metadata cache after a successful response.
- Card resolution uses a lightweight TMDB request only when neither the metadata cache nor a prior full-detail request has supplied the card metadata.
- No collection-card path invokes `ContentDetailsService` or forces full-season expansion.

- [ ] **Step 1: Add failing canonical-cache and mapping tests**

  Test that a card lookup maps title/poster/date/runtime, that two card lookups with the same coordinate and language result in one external request, and that a successful full-detail lookup satisfies a later card lookup without a second external request.

- [ ] **Step 2: Run focused TMDB tests and verify failure**

  ```powershell
  .\mvnw.cmd test "-Dtest=TmdbCardMetadataTest,TmdbClientTest,TmdbClientCachingTest"
  ```

- [ ] **Step 3: Implement canonical metadata mapping and client method**

  Preserve `TmdbLookupResult` semantics. Map both lightweight and full-detail TMDB responses into `TmdbCardMetadata`. Return unavailable results without leaking dependency messages to the client.

- [ ] **Step 4: Add single-flight failure preservation and bound fan-out**

  Keep successful metadata in the Caffeine cache. Use a `ConcurrentHashMap<TmdbCardMetadataKey, CompletableFuture<TmdbLookupResult<TmdbCardMetadata>>>` as a short-lived single-flight table for in-flight card loads so concurrent `NotFound` and `Unavailable` callers receive the same result without persisting failures. Remove the future after the load completes. Use an existing bounded executor or a bounded new bean only when resolving several distinct uncached card keys in parallel.

- [ ] **Step 5: Run the focused tests and commit**

  ```powershell
  .\mvnw.cmd test "-Dtest=TmdbCardMetadataTest,TmdbClientTest,TmdbClientCachingTest"
  git add src/main/java/com/watchwise/watchwise_api/common/tmdb src/main/java/com/watchwise/watchwise_api/content/service/impl/TmdbCardMetadataResolver.java src/test/java/com/watchwise/watchwise_api/common/tmdb
  git commit -m "feat(content): add canonical card metadata"
  ```

  Confirm that the commit contains no `Co-Authored-By` trailer.

### Task 3: Extract the shared card assembler from existing batch services

**Files:**
- Create `ContentCardAssembler.java` and `ContentCardAssemblerTest.java`.
- Modify `ContentChildCardAssembler.java` to delegate shared card assembly.
- Modify `ContentRepository.java`, `ContentViewerStateServiceImpl.java` and `ContentStatsService` collaborators only where an existing batch query cannot serve the assembler.
- Modify `ContentChildCardAssemblerTest.java` and `ContentViewerStateServiceImplTest.java`.

**Interfaces:**
- `ContentCardAssembler.assemble(...)` delegates reference/state resolution to `ContentViewerStateService.resolve(...)`.
- `ContentCardAssembler.assemble(...)` delegates public stats to `ContentStatsService.getStatsBatch(...)`.
- `ContentCardAssembler.assemble(...)` delegates custom posters to `UserContentPosterService.findByUserAndContentIds(...)`.
- `ContentChildCardAssembler` remains responsible only for deriving child `CardSpec` values from `ContentDetailsDTO` and navigation sections.

- [ ] **Step 1: Write tests for deduplication, field selection and no-write reads**

  Cover duplicate keys, basic-only resolution, stats-only resolution, viewer-state isolation, missing local references, partial TMDB metadata and the guarantee that no repository save is called.

- [ ] **Step 2: Run focused tests and verify failure**

  ```powershell
  .\mvnw.cmd test "-Dtest=ContentCardAssemblerTest,ContentChildCardAssemblerTest,ContentViewerStateServiceImplTest"
  ```

- [ ] **Step 3: Reuse existing batch queries and add only missing poster batching**

  Verify `ContentViewerStateService.resolve(...)` and `ContentStatsService.getStatsBatch(...)` cover references, state and stats. Add only the batch poster lookup required by the new assembler. Avoid a repository call inside a card loop.

- [ ] **Step 4: Implement field-selective assembly and child delegation**

  Always resolve identity/basic metadata when requested. Only load stats, viewer state or social metadata when their field is present in `ContentCardFieldSet`.

- [ ] **Step 5: Add canonical-cache isolation tests**

  Assert that shared TMDB metadata can be reused across users, while viewer-specific facets use a separate key and cannot leak between users.

- [ ] **Step 6: Add partial-failure and privacy tests**

  Assert that one unavailable TMDB key produces `PARTIAL` or `UNAVAILABLE` for that card, that other cards still return, and that a private user's personal state is never included for another viewer.

- [ ] **Step 7: Run tests and commit**

  ```powershell
  .\mvnw.cmd test "-Dtest=ContentCardAssemblerTest,ContentChildCardAssemblerTest,ContentViewerStateServiceImplTest,ContentRepositoryTest,ContentStatsServiceImplTest"
  git add src/main/java/com/watchwise/watchwise_api/content src/test/java/com/watchwise/watchwise_api/content
  git commit -m "refactor(content): share card assembly"
  ```

  Confirm that the commit contains no `Co-Authored-By` trailer.

### Task 4: Migrate Home and Profile visual content

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/HomeRecentlyWatchedDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/HomeSocialActivityDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/HomeSummaryResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/ProfileSummaryReaderImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/ProfileSummaryDataReaderImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/RecentActivityItemDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/ProfileDiaryPreviewDTO.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/ProfileSummaryReaderImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/ProfileSummaryDataReaderImplTest.java`

**Interfaces:**
- Home and Profile readers call `ContentCardAssembler` internally for requested visual cards.
- Existing numeric summary fields remain unchanged.
- `HomeRecentlyWatchedDTO`, `HomeSocialActivityDTO`, `RecentActivityItemDTO` and `ProfileDiaryPreviewDTO` expose the card projection needed by their respective HTMLs.

- [ ] **Step 1: Add failing assertions that Home/Profile cards contain visual metadata**

  Assert that the service returns title/poster fields without invoking a frontend-style details batch and that summary-only blocks do not request stats or viewer state.

- [ ] **Step 2: Run focused summary tests and verify failure**

  ```powershell
  .\mvnw.cmd test "-Dtest=SummaryServiceImplTest,ProfileSummaryReaderImplTest,ProfileSummaryDataReaderImplTest"
  ```

- [ ] **Step 3: Inject the card resolver into the read services**

  Resolve only the content keys present in the final Home/Profile projection and keep user/profile visibility checks in their existing services.

- [ ] **Step 4: Update controller integration assertions**

  Verify exact JSON fields, null behavior and 200 responses when one optional TMDB card lookup is unavailable.

- [ ] **Step 5: Run tests and commit**

  ```powershell
  .\mvnw.cmd test "-Dtest=SummaryServiceImplTest,ProfileSummaryReaderImplTest,ProfileSummaryDataReaderImplTest,SummaryControllerIntegrationTest,UserControllerIntegrationTest"
  git add src/main/java/com/watchwise/watchwise_api/summary src/test/java/com/watchwise/watchwise_api/summary
  git commit -m "feat(summary): return visual content cards"
  ```

  Confirm that the commit contains no `Co-Authored-By` trailer.

### Task 5: Migrate Diary History and Diary Reviews

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/dto/DiaryEntryResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/dto/ContentReviewResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryController.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/mapper/DiaryEntryMapper.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryControllerIntegrationTest.java`

**Interfaces:**
- Paginated diary and review responses contain their screen card projection.
- Diary filters, daily summary and pagination remain server-side.
- Content review author/social fields remain separate from card metadata.

- [ ] **Step 1: Add failing service tests for card enrichment after pagination**

  Assert that only the selected page's unique content keys are resolved and that daily-summary filters are not computed from the selected page.

- [ ] **Step 2: Run focused tests and verify failure**

  ```powershell
  .\mvnw.cmd test "-Dtest=DiaryEntryServiceImplTest,DiaryEntryControllerIntegrationTest"
  ```

- [ ] **Step 3: Assemble cards inside the diary service**

  Keep `DiaryEntry` ownership, visibility, sorting and review semantics unchanged. Replace frontend detail-batch requirements with server-side card resolution.

- [ ] **Step 4: Test partial metadata and spoiler/comment behavior**

  Assert that card failure does not remove the diary row and that comment spoiler flags remain unchanged.

- [ ] **Step 5: Run tests and commit**

  ```powershell
  .\mvnw.cmd test "-Dtest=DiaryEntryServiceImplTest,DiaryEntryControllerIntegrationTest,CommentServiceImplTest"
  git add src/main/java/com/watchwise/watchwise_api/diaryentry src/test/java/com/watchwise/watchwise_api/diaryentry
  git commit -m "feat(diary): return visual content cards"
  ```

  Confirm that the commit contains no `Co-Authored-By` trailer.

### Task 6: Migrate Month in Review, Year in Review and All Time Stats

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/MonthInReviewResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/YearInReviewResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/AllTimeEditionStatsDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/AllTimeStatsReaderImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/AllTimeStatsReaderImplTest.java`
- Test: corresponding summary controller integration tests.

**Interfaces:**
- Ranking items expose card metadata directly in the edition response.
- Edition totals, charts and companion data remain unchanged.
- The card resolver receives only the ranking keys that the selected edition returns.

- [ ] **Step 1: Add failing tests for card-ready ranking items**

  Cover `recentWatched`, `firstWatched`, `lastWatched`, `topRated`, `bottomRated`, `longestWatched`, `topSeriesByWatchTime` and `mostLoggedContent`.

- [ ] **Step 2: Run focused tests and verify failure**

  ```powershell
  .\mvnw.cmd test "-Dtest=SummaryServiceImplTest,AllTimeStatsReaderImplTest,SummaryControllerIntegrationTest"
  ```

- [ ] **Step 3: Resolve card fields in the summary readers**

  Deduplicate IDs across all ranking blocks before resolving. Request only `BASIC_METADATA` and `STATS` when the edition displays score or runtime.

- [ ] **Step 4: Verify pagination and edition contracts**

  Assert that changing month/year/type reruns the summary endpoint without a frontend details batch and that missing cards preserve numeric statistics.

- [ ] **Step 5: Run tests and commit**

  ```powershell
  .\mvnw.cmd test "-Dtest=SummaryServiceImplTest,AllTimeStatsReaderImplTest,SummaryControllerIntegrationTest"
  git add src/main/java/com/watchwise/watchwise_api/summary src/test/java/com/watchwise/watchwise_api/summary
  git commit -m "feat(summary): enrich review and stats cards"
  ```

  Confirm that the commit contains no `Co-Authored-By` trailer.

### Task 7: Implement the Watchlist visual read model

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/watchlist/dto/WatchlistCardDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/watchlist/dto/WatchlistAggregateDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/watchlist/dto/WatchlistViewResponseDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/watchlist/dto/WatchlistSort.java`
- Create: `src/main/java/com/watchwise/watchwise_api/watchlist/dto/WatchlistStatus.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/watchlist/controller/WatchlistEntryController.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/watchlist/service/WatchlistEntryService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/watchlist/service/impl/WatchlistEntryServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/watchlist/repository/WatchlistEntryRepository.java`
- Modify: `src/main/resources/application-dev.properties`
- Modify: `src/main/resources/application-prod.properties`
- Modify: `src/test/java/com/watchwise/watchwise_api/watchlist/service/impl/WatchlistEntryServiceImplTest.java`, `src/test/java/com/watchwise/watchwise_api/watchlist/controller/WatchlistEntryControllerTest.java` and `src/test/java/com/watchwise/watchwise_api/watchlist/controller/WatchlistEntryControllerIntegrationTest.java`.

**Interfaces:**
- Add `GET /users/{userId}/watchlist/view` with `type`, `genre`, `status`, `sort`, `direction`, `page` and `size`.
- Preserve existing domain GET/POST/PATCH/DELETE contracts.
- Return aggregates calculated after filtering and before pagination.
- Do not cache the materialized user watchlist response; community rating can change independently of watchlist mutations.
- Reject `TITLE` and `RATING` when filtered candidates exceed `app.watchlist.view.max-materialized-sort-candidates` (default `1000`) with `BadRequestException`; all local sorts remain available without this materialization limit.

- [ ] **Step 1: Add failing tests for filter, order and aggregate semantics**

  Cover type, genre, upcoming/in-progress status, all five sorts, direction, stable tie-breaking, page metadata, total runtime, filtered upcoming count and rejection of `TITLE`/`RATING` above the configured candidate limit.

- [ ] **Step 2: Run focused Watchlist tests and verify failure**

  ```powershell
  .\mvnw.cmd test "-Dtest=WatchlistEntryServiceImplTest,WatchlistEntryControllerTest,WatchlistEntryControllerIntegrationTest"
  ```

- [ ] **Step 3: Implement candidate loading and card resolution**

  Load candidates, apply safe local filters, reject oversized `TITLE`/`RATING` candidate sets before TMDB resolution, resolve requested card facets, apply expensive ordering in the backend, calculate aggregates, then paginate.

- [ ] **Step 4: Add visibility and optional metadata failure tests**

  Verify private-profile access, custom poster ownership, placeholder status and no `Content` creation during the view read.

- [ ] **Step 5: Run tests and commit**

  ```powershell
  .\mvnw.cmd test "-Dtest=WatchlistEntryServiceImplTest,WatchlistEntryControllerTest,WatchlistEntryControllerIntegrationTest"
  git add src/main/java/com/watchwise/watchwise_api/watchlist src/test/java/com/watchwise/watchwise_api/watchlist
  git commit -m "feat(watchlist): add visual read model"
  ```

  Confirm that the commit contains no `Co-Authored-By` trailer.

### Task 8: Complete the Calendar read contract

**Files:**
- Optionally create: `src/main/java/com/watchwise/watchwise_api/calendar/dto/CalendarSummaryDTO.java` only if the frontend needs server-provided counters after the event contract is verified.
- Optionally create: `src/main/java/com/watchwise/watchwise_api/calendar/dto/CalendarReleaseContext.java` only for release-context data actually supplied by the external source.
- Modify: `CalendarEventDTO.java`, `CalendarResponseDTO.java` and the four calendar content DTOs only for additive, source-backed fields.
- Modify: `CalendarController.java`, `CalendarService.java`, `CalendarServiceImpl.java` and `CalendarEventAssembler.java`.
- Modify schedule snapshot/provider classes only for fields actually supplied by the external source.
- Modify Calendar service/controller integration tests.

**Interfaces:**
- Keep `GET /users/me/calendar?month=YYYY-MM`.
- Keep the current event response as the required contract.
- Add `granularity=AUTO|EPISODE` only if grouped events cannot be rendered correctly by the frontend without it.
- Return nullable release-time/network fields only when the external source supplies them; never fabricate them.
- Add `CalendarSummaryDTO` only as an additive optimization after the event response is stable.

- [ ] **Step 1: Add failing tests for the current event contract and optional source-backed fields**

  Cover grouped series/season events, episode-level events, source labels and absent release time/network. Do not make summary or granularity fields mandatory for the base response.

- [ ] **Step 2: Run focused Calendar tests and verify failure**

  ```powershell
  .\mvnw.cmd test "-Dtest=CalendarEventDTOTest,CalendarServiceImplTest,CalendarControllerIntegrationTest"
  ```

- [ ] **Step 3: Implement only the source-backed additive fields**

  Keep snapshots as the source of calendar facts. Do not call `ContentCardAssembler` or `contents/details` from Calendar.

- [ ] **Step 4: Run tests and commit**

  ```powershell
  .\mvnw.cmd test "-Dtest=CalendarEventDTOTest,CalendarServiceImplTest,CalendarControllerIntegrationTest"
  git add src/main/java/com/watchwise/watchwise_api/calendar src/test/java/com/watchwise/watchwise_api/calendar
  git commit -m "feat(calendar): complete visual release contract"
  ```

  Confirm that the commit contains no `Co-Authored-By` trailer.

### Task 9: Enrich TMDB Trending without changing its source

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/trending/dto/TrendingCardDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/trending/dto/TrendingResponseDTO.java` to keep the existing `movies` and `series` JSON arrays while changing their item type to `TrendingCardDTO` and adding only additive continuation metadata.
- Modify: `TrendingController.java`, `TrendingService.java` and `TrendingServiceImpl.java`.
- Modify: `TmdbTrendingMovieResult.java`, `TmdbTrendingTvResult.java` to retain required TMDB fields.
- Modify: `TmdbClient.java` so Trending requests accept the TMDB page argument and reuse the existing Trending caches with page in their keys.
- Modify Trending service/controller integration tests.

**Interfaces:**
- Keep TMDB as the source of the ranking.
- Keep `GET /trending?timeWindow=day|week&size=` for the initial movie and series sections.
- Add `GET /trending/{type}?timeWindow=day|week&page=&size=` for independent section continuation.
- Return `tmdbVoteAverage`, `popularity`, genres, optional runtime/season count and viewer state.
- Do not return Watchwise community viewer counts or call TMDB Trending data “community activity”.
- Do not delete `TrendingResponseDTO` or change the JSON shape of `movies` and `series` from arrays.

- [ ] **Step 1: Add failing tests for TMDB fields, page-aware cache and viewer state**

  Cover day/week, independent movie/series continuation, missing poster, cached repeated requests by page, optional card metadata and state lookups without `POST /contents/reference`.

- [ ] **Step 2: Run focused Trending tests and verify failure**

  ```powershell
  .\mvnw.cmd test "-Dtest=TrendingServiceImplTest,TrendingControllerTest,TrendingControllerIntegrationTest"
  ```

- [ ] **Step 3: Implement the TMDB card projection and section continuation**

  Map the cached TMDB Trending list first, then resolve optional canonical card metadata only for the returned page. Keep TMDB fields explicitly named in the response.

- [ ] **Step 4: Extend the existing Trending cache keys**

  Include TMDB page in the existing `(type, timeWindow, language)` cache key. Viewer state is resolved after the shared cache and is never stored in the shared Trending cache.

- [ ] **Step 5: Run tests and commit**

  ```powershell
  .\mvnw.cmd test "-Dtest=TrendingServiceImplTest,TrendingControllerTest,TrendingControllerIntegrationTest"
  git add src/main/java/com/watchwise/watchwise_api/trending src/main/java/com/watchwise/watchwise_api/common/tmdb src/test/java/com/watchwise/watchwise_api/trending src/test/java/com/watchwise/watchwise_api/common/tmdb
  git commit -m "feat(trending): add cached TMDB card projection"
  ```

  Confirm that the commit contains no `Co-Authored-By` trailer.

### Task 10: Enrich Social/Feed after cursor selection

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/feed/dto/FeedPageViewDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/feed/dto/FeedItemViewDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/feed/dto/FeedTop5PreviewDTO.java`
- Modify: `FeedController.java`, `FeedService.java`, `FeedServiceImpl.java` and `FeedItemDTO.java` as needed for additive compatibility.
- Modify Pick/Top5 preview DTOs only when a feed-specific visual projection cannot be represented additively.
- Modify Feed service/controller tests and integration tests.

**Interfaces:**
- Keep cursor pagination and pull aggregation.
- Enrich only the final page after cursor selection.
- Return `followingCount`, optional followed-user previews, content cards, Top 5 card previews and Pick target cards.
- Handle `DAILY_GAME_RESULT` explicitly.
- `FeedPageViewDTO` keeps `content`, `size`, `nextCursor` and `hasNext` at their existing JSON names and adds `followingCount` and `followedUsersPreview` only as additive fields.

- [ ] **Step 1: Add failing tests for post-selection enrichment**

  Assert that discarded cursor candidates do not trigger card resolution, repeated content keys are resolved once, comments/likes remain intact and every event type has a response shape.

- [ ] **Step 2: Run focused Feed tests and verify failure**

  ```powershell
  .\mvnw.cmd test "-Dtest=FeedServiceImplTest,FeedControllerTest,FeedControllerIntegrationTest"
  ```

- [ ] **Step 3: Implement feed-specific view assembly**

  Keep event ordering and visibility predicates unchanged. Use `ContentCardAssembler` only after the final page is selected.

- [ ] **Step 4: Add empty, loading-compatible, spoiler and daily-game contract tests**

  Verify empty accepted-following feeds, `nextCursor`, `containsSpoiler`, recent comments and explicit `DAILY_GAME_RESULT` rendering data.

- [ ] **Step 5: Run tests and commit**

  ```powershell
  .\mvnw.cmd test "-Dtest=FeedServiceImplTest,FeedControllerTest,FeedControllerIntegrationTest"
  git add src/main/java/com/watchwise/watchwise_api/feed src/main/java/com/watchwise/watchwise_api/pick src/main/java/com/watchwise/watchwise_api/top5entry src/test/java/com/watchwise/watchwise_api/feed
  git commit -m "feat(feed): return visual activity cards"
  ```

  Confirm that the commit contains no `Co-Authored-By` trailer.

### Task 11: Synchronize contracts, migrate client requirements and verify the full initiative

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/telas-reqs.md`
- Modify: `docs/telas-reqs-definitivo.md`
- Modify: `docs/context/tmdb-reqs-by-screen.md`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`
- Modify: affected DTO serialization and controller integration tests.

**Interfaces:**
- Document screen-specific visual responses and optional fields.
- Mark `/contents/details?ids=...` as a compatibility/detail mechanism, not a frontend card requirement.
- Document TMDB Trending semantics and distinguish TMDB popularity from Watchwise statistics.
- Document Calendar's snapshot/granularity behavior and the absence of fabricated release times.

- [ ] **Step 1: Update OpenAPI schemas and paths**

  Add the screen read models, request parameters, nullable fields, error contracts and compatibility notes. Keep existing generic detail schemas unchanged.

- [ ] **Step 2: Update screen requirement documents**

  Add Watchlist, Calendar, TMDB Trending and Social/Feed sections to `telas-reqs-definitivo.md`. Replace frontend detail-batch instructions for Home, Profile, Diary and review/stat screens with the screen response contracts.

- [ ] **Step 3: Update business rules and progress**

  Record visibility, aggregate semantics, cache/read-only behavior, TMDB Trending provenance and Calendar field limitations only after the code is shipped.

- [ ] **Step 4: Run contract and full verification**

  ```powershell
  git diff --check
  .\mvnw.cmd test
  git status --short
  ```

  Verify no client-facing flow requires `POST /contents/reference` during a read, no card path invokes full TMDB details, and no privacy-sensitive state enters shared caches.

- [ ] **Step 5: Commit the final code/document synchronization**

  ```powershell
  git add src/main/java src/test/java
  git commit -m "feat(content): migrate screens to card read models"
  ```

  Confirm that the commit contains no `Co-Authored-By` trailer. Leave every documentation update in the worktree, per repository convention. Do not push without explicit authorization.

## Plan self-review

- The plan covers the shared resolver, selective field loading, lightweight TMDB cache, all required screen migrations, Watchlist, Calendar, TMDB Trending, Feed, documentation and verification.
- TMDB Trending is explicitly kept external; Watchwise community ranking is not introduced.
- Calendar timing/network fields are optional and cannot block the screen.
- No task creates a public generic preview endpoint or writes content during reads.
- Existing Progress, Episode Ratings Map and Content Detail contracts are preserved.
- All tasks have concrete files, interfaces, tests, commands and commit boundaries.
