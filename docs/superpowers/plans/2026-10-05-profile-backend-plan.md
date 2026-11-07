# Profile Backend Read Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** Extend the existing profile, summary, Top 5, and activity contracts so the Profile design renders the authenticated user's profile and visible profiles of other users without reconstructing aggregates in the frontend.

**Architecture:** Keep the existing REST routes and preserve \`SummaryService\` as the public seam, but delegate Profile-specific work to a focused \`ProfileSummaryReader\` module. Add one shared \`ProfileDiaryPreviewDTO\` for the two diary-based profile sections, repository-level aggregate projections for distinct units and rewatch-aware metrics, and a display-metadata adapter for highlights whose series identity has no stored \`SERIES\` reference. Reuse current visibility checks and the existing TMDB content-details batch endpoint.

**Tech Stack:** Spring Boot 4.1, Java 21, Spring Data JPA, PostgreSQL, MapStruct, Lombok, JUnit 5, Mockito, Spring MockMvc, Testcontainers.

## Global Constraints

- No new persistence entity or Flyway migration.
- \`GET /users/{userId}/summary?type=MOVIE|SERIES\` remains the Profile body read model.
- \`recentEpisodes\` is episode-only and excludes review text.
- \`recentReviews\` uses MOVIE for Movies and SERIES/SEASON/EPISODE for Series, but excludes review text and social counters.
- Movie genres count distinct movie contents; Series genres count distinct episode coordinates and ignore rewatches.
- Ratings count one latest score per distinct movie or episode unit and ignore rewatches.
- Recent activity includes completed and dropped top-level movies/series, with \`score\` and \`timesWatched\`.
- All aggregation and batch enrichment must happen in repository/service code, never in controllers or frontend pagination workarounds.
- Every production behavior must have a failing test observed before implementation.
- Update \`openapi.yaml\`, \`telas.md\`, \`business-rules.md\`, and \`progress.md\`; documentation remains worktree-only and is not committed by repository convention.
- Use Conventional Commits and do not add a \`Co-Authored-By\` trailer.

## File Map

Create:

- \`src/main/java/com/watchwise/watchwise_api/summary/dto/ProfileDiaryPreviewDTO.java\` — shared lightweight payload for \`recentEpisodes\` and \`recentReviews\`.
- \`src/main/java/com/watchwise/watchwise_api/summary/dto/ProfileHighlightsDTO.java\` — rewatch and longest-watch fields.
- \`src/main/java/com/watchwise/watchwise_api/summary/dto/ProfileRewatchHighlightDTO.java\` — rewatch highlight type, content reference, and count.
- \`src/main/java/com/watchwise/watchwise_api/summary/dto/ProfileLongestWatchDTO.java\` — longest-watch content and runtime metrics.
- \`src/main/java/com/watchwise/watchwise_api/summary/dto/ProfileHighlightContentDTO.java\` — minimal hydrated display metadata for a highlight, supporting an internal content ID or an external series identity.
- \`src/main/java/com/watchwise/watchwise_api/summary/dto/RatingsSummaryDTO.java\` — total ratings and average score.
- \`src/main/java/com/watchwise/watchwise_api/user/dto/ViewerProfileRelation.java\` — viewer-relative profile relation enum.
- \`src/main/java/com/watchwise/watchwise_api/summary/service/ProfileSummaryReader.java\` — focused interface for the Profile summary read model.
- \`src/main/java/com/watchwise/watchwise_api/summary/service/ProfileSummaryDataReader.java\` — short transactional database snapshot seam.
- \`src/main/java/com/watchwise/watchwise_api/summary/service/ProfileDisplayMetadataResolver.java\` — internal seam for resolving highlight titles, years, posters, and owner overrides.
- \`src/main/java/com/watchwise/watchwise_api/summary/repository/ProfileSummaryQueryRepository.java\` — cross-aggregate read projections for the Profile.
- \`src/main/java/com/watchwise/watchwise_api/summary/service/impl/ProfileSummaryReaderImpl.java\` — Profile-specific orchestration and preview assembly.
- \`src/main/java/com/watchwise/watchwise_api/summary/service/impl/ProfileSummaryDataReaderImpl.java\` — transactional raw-data reader.

Modify:

- \`PublicUserProfileDTO.java\` and \`UserMapper.java\` — add \`viewerRelation\`.
- \`UserService.java\` and \`UserServiceImpl.java\` — compute the relation after visibility authorization.
- \`FollowerRepository.java\` — reuse the current viewer/target relation lookup.
- \`WatchTimeDTO.java\` — add total and last-30-day watch counts.
- \`RecentActivityItemDTO.java\` — add \`score\` and \`timesWatched\`.
- \`SummaryResponseDTO.java\` and \`SummaryServiceImpl.java\` — add metrics, highlights, ratings summary, lightweight previews, and mixed-level reviews.
- \`SummaryServiceImpl.java\` — delegate \`getSummary\` to \`ProfileSummaryReader\` while retaining the other summary variants.
- \`SummaryController.java\` — preserve route and validation.
- \`DiaryEntryRepository.java\` — keep existing diary-domain queries; do not add profile-wide cross-aggregate SQL here.
- \`DiaryEntryMapper.java\` — add explicit preview mapping if entity-to-preview mapping is used.
- \`ContentDetailsDTO.java\` and \`ContentDetailsServiceImpl.java\` — expose nullable \`parentTitle\` and \`parentReleaseYear\` for season/episode display labels without another TMDB call.
- \`Top5EntryResponseDTO.java\`, \`Top5EntryMapper.java\`, and \`Top5EntryServiceImpl.java\` — add batch-resolved latest personal scores.
- Context documentation after code is complete.

Test:

- \`SummaryServiceImplTest.java\`, \`SummaryControllerTest.java\`, and a Testcontainers test for the dedicated profile read adapter's aggregate queries.
- \`ProfileSummaryReaderImplTest.java\` and \`ProfileDisplayMetadataResolverTest.java\` — focused reader and highlight hydration tests.
- \`Top5EntryServiceImplTest.java\` and \`Top5EntryControllerTest.java\`.
- \`UserServiceImplTest.java\`, \`UserVisibilityServiceImplTest.java\`, and \`UserControllerIntegrationTest.java\`.

## Task 1: Lock the public DTO contract with failing tests

**Interfaces:**

- \`SummaryResponseDTO\` exposes \`WatchTimeDTO\`, \`ProfileHighlightsDTO\`, \`RatingsSummaryDTO\`, \`List<ProfileDiaryPreviewDTO> recentEpisodes\`, \`List<ProfileDiaryPreviewDTO> recentReviews\`, and \`List<RecentActivityItemDTO> recentActivity\`.
- \`ProfileDiaryPreviewDTO\` contains \`UUID id\`, \`ContentRefDTO content\`, \`Integer score\`, \`LocalDate watchedDate\`, \`Integer watchNumber\`, \`String customPosterUrl\`, and \`List<UserPreviewDTO> watchedWith\`; it has no \`comment\`, likes, or comment-count fields.
- \`RecentActivityItemDTO\` contains content, status, score, timesWatched, comment, and activityDate.
- \`Top5EntryResponseDTO\` adds nullable \`Integer score\`.
- \`PublicUserProfileDTO\` adds \`ViewerProfileRelation viewerRelation\`.

- [ ] **Step 1: Write failing DTO contract tests.**

Assert that the preview type has no review text/social counters, activity exposes \`timesWatched\`, and the watch-time DTO exposes both new counts.

~~~java
assertThat(new ProfileDiaryPreviewDTO(id, content, 9, watchedDate, 2, null, List.of()))
        .isNotNull();
assertThat(new RecentActivityItemDTO(content, RecentActivityStatus.DROPPED, null, 0L, null, activityDate)
        .timesWatched()).isZero();
assertThat(new WatchTimeDTO(120L, 30L, 4L, 1L).totalWatchedCount()).isEqualTo(4L);
~~~

- [ ] **Step 2: Run the focused tests and verify they fail for missing contract members.**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=SummaryServiceImplTest,SummaryControllerTest,Top5EntryControllerTest"
~~~

Expected result: compilation or assertion failure because the new DTO fields and types do not exist.

- [ ] **Step 3: Add the minimal DTO/enum definitions and compatibility constructors.**

Update MapStruct mappings with explicit fields and ignored/default fields as required by the existing error policy.

- [ ] **Step 4: Run the focused tests again.**

The tests may now fail because service code still constructs the old response shape. Keep that red state for Task 2.

## Task 2: Implement relation-aware public profile access

**Interfaces:**

- \`UserService.getUserById(viewerId, targetUserId)\` remains the controller seam.
- Relation values are \`SELF\`, \`FOLLOWING\`, and \`NOT_FOLLOWING\`. Pending outgoing requests remain represented by the existing follow-request endpoints because an unauthorized private target returns \`403\` before this profile response is available.

- [ ] **Step 1: Write failing service tests.**

Cover own profile, accepted outgoing follow, no relation, and private-profile rejection before relation data is returned. Do not expose a pending outgoing state through this response; pending requests use the existing follow-request contract.

- [ ] **Step 2: Run the relation tests and verify the expected failures.**

~~~powershell
.\mvnw.cmd test "-Dtest=UserServiceImplTest,UserVisibilityServiceImplTest,UserControllerIntegrationTest"
~~~

- [ ] **Step 3: Implement relation computation after visibility authorization.**

Use \`FollowerRepository.findByFollowerIdAndFollowedId(viewerId, targetUserId)\`. Do not expose relation data for an unauthorized private profile.

- [ ] **Step 4: Run the relation tests and the existing user test slice.**

~~~powershell
.\mvnw.cmd test "-Dtest=UserServiceImplTest,UserVisibilityServiceImplTest,UserControllerTest,UserControllerIntegrationTest"
~~~

## Task 3: Add repository projections for distinct profile metrics

**Interfaces:**

- \`ProfileSummaryQueryRepository\` returns aggregates keyed by content ID or series identity, with counts and deterministic ordering.
- Distinct movie units use \`Content.id\`.
- Distinct episode units use \`(seriesTmdbId, seasonNumber, episodeNumber)\`.
- Latest ratings use the greatest positive \`watchNumber\` per user/content unit.

- [ ] **Step 1: Write failing Testcontainers tests.**

Create fixtures with one movie logged twice with different scores, a second movie, a series with two episodes and one repeated episode, one completed series pass, one dropped series with partial episode history, genres, and dates inside/outside the 30-day window.

Assert that movie genres count distinct movies, Series genres count distinct episodes, latest scores win, repeated rows do not increase rating counts, and dropped activity counts only completed top-level passes.

- [ ] **Step 2: Run the repository tests and observe the red state.**

~~~powershell
.\mvnw.cmd test "-Dtest=SummaryProfileRepositoryTest"
~~~

- [ ] **Step 3: Implement JPQL/native projections in the dedicated profile read adapter.**

Keep aggregation in PostgreSQL. Use grouped queries and a deterministic latest-score selection. Do not load the complete diary into Java for profile-wide metrics. Keep the adapter separate from \`DiaryEntryRepository\` so diary-domain writes and profile analytics do not share an ever-growing repository interface.

- [ ] **Step 4: Run the repository tests until green.**

~~~powershell
.\mvnw.cmd test "-Dtest=SummaryProfileRepositoryTest"
~~~

Verify that card counts are batch-loaded and no per-card query is introduced.

## Task 4: Extract the ProfileSummaryReader seam

**Interfaces:**

- \`SummaryService.getSummary(viewerId, userId, type)\` remains the public summary seam and delegates to \`ProfileSummaryReader.read(viewerId, userId, type)\`.
- \`ProfileSummaryReader\` returns the complete \`SummaryResponseDTO\` for the Profile body.
- \`ProfileDisplayMetadataResolver\` resolves highlight display metadata from an internal content ID or an external series identity and applies the target owner's custom poster.
- Both \`recentEpisodes\` and \`recentReviews\` use \`ProfileDiaryPreviewDTO\`.

- [ ] **Step 1: Write failing reader tests.**

Cover distinct movie/episode genres, latest distinct-unit ratings, recent episodes without review text, mixed Series reviews across SERIES/SEASON/EPISODE, completed and dropped activity with score/timesWatched, and highlights/watch counts.

- [ ] **Step 2: Run the reader tests and verify the expected red state.**

~~~powershell
.\mvnw.cmd test "-Dtest=SummaryServiceImplTest,SummaryControllerTest"
~~~

- [ ] **Step 3: Move Profile-specific orchestration out of \`SummaryServiceImpl\`.**

Keep Home, Month, Year, All-Time, and episode-rating-map behavior in \`SummaryServiceImpl\`. Delegate only \`getSummary\` to \`ProfileSummaryReaderImpl\`, which first calls \`ProfileSummaryDataReader\` for an immutable database snapshot.

- [ ] **Step 4: Implement the short transactional data reader.**

Annotate \`ProfileSummaryDataReaderImpl.read(...)\` with \`@Transactional(readOnly = true)\`. It may call \`ProfileSummaryQueryRepository\`, diary companions, and poster ownership queries, but it must return before \`ProfileDisplayMetadataResolver\` makes any TMDB request.

- [ ] **Step 5: Implement summary orchestration in the reader.**

Use separate repository methods for episode-only recent activity, mixed-level recent reviews with a non-blank comment, distinct metrics, and completed/dropped activity counts. Map only fields in \`ProfileDiaryPreviewDTO\`; do not query or return likes/comments data.

- [ ] **Step 6: Add batch custom-poster enrichment.**

Collect all diary content IDs from both preview lists, call \`UserContentPosterService.findByUserAndContentIds\` once, and apply target-profile poster overrides.

- [ ] **Step 7: Add child-content labels and highlight metadata resolution.**

Populate \`parentTitle\` and \`parentReleaseYear\` in \`ContentDetailsDTO\` for SEASON/EPISODE using the parent TV details already loaded by \`ContentDetailsServiceImpl\`. Resolve highlight cards through \`ProfileDisplayMetadataResolver\`; use stored content details when \`contentId\` exists and the TMDB client for an external \`seriesTmdbId\`. If a highlight's TMDB lookup fails, omit only that highlight and keep the rest of the summary response valid.

- [ ] **Step 8: Run the summary tests until green.**

~~~powershell
.\mvnw.cmd test "-Dtest=SummaryServiceImplTest,SummaryControllerTest"
~~~

## Task 5: Add latest personal scores to Top 5

- [ ] **Step 1: Write failing tests.**

Cover highest-watch-number score selection, no score returning null, and no fallback to an older score when the latest watch has no score.

- [ ] **Step 2: Verify the red state.**

~~~powershell
.\mvnw.cmd test "-Dtest=Top5EntryServiceImplTest,Top5EntryControllerTest"
~~~

- [ ] **Step 3: Implement one batch score lookup and DTO enrichment.**

Resolve all returned Top 5 content IDs in one repository call. Update \`withCustomPosterUrl\` and compatibility constructors.

- [ ] **Step 4: Run Top 5 and feed tests.**

~~~powershell
.\mvnw.cmd test "-Dtest=Top5EntryServiceImplTest,Top5EntryControllerTest,FeedServiceImplTest"
~~~

## Task 6: Update contracts, documentation, and integration verification

- [ ] **Step 1: Write failing JSON contract assertions.**

Assert \`viewerRelation\`, watch counts, highlights, ratings summary, activity score/timesWatched, nullable Top 5 score, mixed Series review content types, parent series labels for child content, and absence of \`comment\`, likes, and comment counts from profile previews.

- [ ] **Step 2: Run integration tests and observe the expected failures.**

~~~powershell
rg --files src/test/java | rg "summary.*IntegrationTest"
.\mvnw.cmd test "-Dtest=UserControllerIntegrationTest,Top5EntryControllerIntegrationTest"
~~~

Run the discovered summary integration test class in the same command when it exists.

- [ ] **Step 3: Update OpenAPI and context docs.**

Document distinct movie/episode units, latest-score selection, dropped activity counts, lightweight previews without review text/social counters, and relation states. Append only shipped behavior to the current date section of \`progress.md\` and add the non-obvious rules to \`business-rules.md\`.

- [ ] **Step 4: Run the complete verification suite.**

~~~powershell
.\mvnw.cmd test
~~~

Docker must be running for Testcontainers tests.

- [ ] **Step 5: Inspect the final diff and commit source/test changes.**

~~~powershell
git diff --check
git status --short
git diff --stat
git add src/main/java src/test/java
git commit -m "feat(profile): extend profile read model"
~~~

Keep documentation files worktree-only. After committing, explicitly verify that no \`Co-Authored-By\` trailer or other self-attribution was added.

## Final verification checklist

- [ ] Every new behavior has a test that failed before implementation.
- [ ] Movie genres count distinct movies and ignore rewatches.
- [ ] Series genres count distinct episode coordinates and ignore rewatches.
- [ ] Ratings use one latest score per distinct unit and ignore rewatches.
- [ ] Dropped activity remains visible and has the defined pre-drop watch count.
- [ ] Recent episodes and reviews omit review text and social counters.
- [ ] Recent reviews include Series, Season, and Episode content levels.
- [ ] Top 5 scores are batch-resolved and nullable.
- [ ] Profile visibility and viewer relation are enforced server-side.
- [ ] Season/episode display labels include parent series title/year without an extra TMDB request per card.
- [ ] Series highlight cards work even when no stored \`SERIES\` Content reference exists.
- [ ] No N+1 queries are introduced for cards or aggregates.
- [ ] Full Maven test suite passes.
