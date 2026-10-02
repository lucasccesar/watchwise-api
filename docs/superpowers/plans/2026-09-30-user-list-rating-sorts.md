# User List Rating Sorts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add owner-scoped and public-profile rating averages to list-item responses and support sorting by public episode averages and direct content averages without exposing private-profile ratings.

**Architecture:** Keep all ratings derived from `DiaryEntry`; add nullable response-only fields to `UserListItemResponseDTO`, enrich mapped items in `UserListServiceImpl`, and keep sorting in memory as the existing list endpoint does. Use a batched database projection of public episode score sums/counts and reuse the existing public content-statistics projection for direct content averages; enforce owner-profile visibility before owner-scoped sorting or values.

**Tech Stack:** Spring Boot 4.1, Java 21, Spring Data JPA, PostgreSQL, MapStruct, JUnit 5, Mockito, AssertJ, MockMvc, Maven Wrapper.

## Global Constraints

- No new database columns or entities are required.
- `episodeAvgRating` remains owner-scoped and is available only to the owner, a viewer of a public owner profile, or an accepted follower.
- `globalEpisodeAvgRating` and `contentAvgRating` aggregate only diary entries from users with public profiles.
- Public episode aggregation returns grouped score sums/counts from the database instead of loading every public episode diary entity; series/season averages preserve rewatch weighting by summing those aggregates before division.
- `episodeAverageRating`, `globalEpisodeAverageRating`, and `contentAverageRating` are nullable and remain `null` for unsupported or unrated items.
- Items without a value remain last for both ascending and descending rating sorts.
- Rewatch diary entries remain separate observations in averages, matching the existing owner-scoped behavior.
- Do not change unrelated list ordering, list visibility, or database schema.
- Follow the repository's existing typed exception, MapStruct, DTO-record, and feature-package patterns.
- Update `docs/context/openapi.yaml`, `docs/context/business-rules.md`, and `docs/context/progress.md` in the same change; documentation remains worktree-only and is not included in the code commit.

---

## File Map

**Modify:**

- `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemResponseDTO.java` — add nullable rating fields and a response-enrichment method while preserving existing convenience constructors.
- `src/main/java/com/watchwise/watchwise_api/userlist/mapper/UserListItemMapper.java` — ignore calculated response-only fields and enrichment methods under `ReportingPolicy.ERROR`.
- `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java` — add the public-profile episode aggregate projection/query.
- `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java` — calculate rating maps, enforce owner-rating access, enrich items, and add sort values.
- `src/test/java/com/watchwise/watchwise_api/userlist/mapper/UserListItemMapperTest.java` — verify mapped items start with nullable rating fields.
- `src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java` — verify aggregation, sorting, redaction, and authorization.
- `src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerIntegrationTest.java` — verify JSON fields, sort parameters, and the privacy response.
- `src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java` — verify the public-profile predicate on public episode aggregation when Testcontainers is available.
- `docs/context/openapi.yaml` — document sort values, response fields, nullable behavior, and the owner-rating `403` case.
- `docs/context/business-rules.md` — extend the list-rating rule with public global averages and private-profile protection.
- `docs/context/progress.md` — append the shipped change under `2026-09-30`.

**Create:** None.

## Interfaces

The implementation must expose these exact response components on `UserListItemResponseDTO`:

```java
Double episodeAverageRating,
Double globalEpisodeAverageRating,
Double contentAverageRating
```

The response DTO must provide:

```java
UserListItemResponseDTO withRatingAverages(
        Double episodeAverageRating,
        Double globalEpisodeAverageRating,
        Double contentAverageRating)
```

`UserListServiceImpl` must accept these `sortBy` values in `GET /lists/{listId}`:

```text
position, dateAdded, duration, episodeAvgRating, globalEpisodeAvgRating, contentAvgRating
```

---

### Task 1: Add response-only rating fields

**Files:**

- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/mapper/UserListItemMapper.java`
- Test: `src/test/java/com/watchwise/watchwise_api/userlist/mapper/UserListItemMapperTest.java`

**Interfaces:**

- Consumes: Existing `UserListItem` mapping and `customPosterUrl`/`contentState` enrichment behavior.
- Produces: The three nullable response components and `withRatingAverages(...)` for the service layer.

- [ ] **Step 1: Write the failing mapper test**

Extend the content-item mapping test with assertions that a freshly mapped item has no calculated rating values:

```java
assertThat(result.episodeAverageRating()).isNull();
assertThat(result.globalEpisodeAverageRating()).isNull();
assertThat(result.contentAverageRating()).isNull();
```

Add a separate test that constructs a response item with an existing custom poster and content state, calls `withRatingAverages(7.5, 8.0, 7.0)`, and asserts that all original fields plus all three values are preserved.

- [ ] **Step 2: Run the mapper test and verify the red failure**

Run:

```powershell
.\mvnw.cmd test "-Dtest=UserListItemMapperTest"
```

Expected: compilation fails because the new accessors and `withRatingAverages` do not yet exist. Confirm the failure names the missing response API rather than a test setup typo.

- [ ] **Step 3: Add the three nullable record components**

Append the components after `contentState` and update the canonical constructor calls in `withCustomPosterUrl` and all convenience constructors. Keep existing constructor signatures by delegating them to the new canonical constructor with three trailing `null` values.

- [ ] **Step 4: Add the enrichment method**

Implement the method with a single new record instance that preserves `id`, `content`, `childList`, `position`, `description`, `createdAt`, `updatedAt`, `customPosterUrl`, and `contentState`.

- [ ] **Step 5: Update MapStruct ignores**

Add explicit ignores for `episodeAverageRating`, `globalEpisodeAverageRating`, `contentAverageRating`, and `withRatingAverages`. These values are calculated by the service after mapping and must never be inferred from `UserListItem`.

- [ ] **Step 6: Run the mapper test and verify green**

Run the same Maven command. Expected: all `UserListItemMapperTest` tests pass.

- [ ] **Step 7: Commit the focused code change**

```powershell
git add src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemResponseDTO.java src/main/java/com/watchwise/watchwise_api/userlist/mapper/UserListItemMapper.java src/test/java/com/watchwise/watchwise_api/userlist/mapper/UserListItemMapperTest.java
git commit -m "feat(userlist): add item rating fields"
```

The commit message must not contain a `Co-Authored-By` trailer or any other self-attribution.

---

### Task 2: Add public episode aggregation support

**Files:**

- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java`

**Interfaces:**

- Consumes: `DiaryEntry.content.type = EPISODE`, `DiaryEntry.score`, `DiaryEntry.user.isProfilePublic`, and a collection of series TMDB ids.
- Produces: `List<DiaryEntryRepository.PublicEpisodeRatingAggregate> findPublicEpisodeRatingAggregatesBySeriesTmdbIdIn(Collection<String> seriesTmdbIds)`.

- [ ] **Step 1: Write the failing repository test**

Add a Testcontainers-backed test that persists two scored episode entries from a public user for the same episode, one scored episode entry from a private user, and one unscored episode entry from a public user for the same series. Call the new repository method and assert one aggregate row with the public sum/count; private and unscored entries must not contribute.

Use distinct users and entries so the assertion checks public-profile filtering, `score IS NOT NULL`, and database grouping:

```java
List<DiaryEntryRepository.PublicEpisodeRatingAggregate> result =
        repository.findPublicEpisodeRatingAggregatesBySeriesTmdbIdIn(List.of("100"));

assertThat(result).singleElement().satisfies(aggregate -> {
    assertThat(aggregate.getSeriesTmdbId()).isEqualTo("100");
    assertThat(aggregate.getSeasonNumber()).isEqualTo(1);
    assertThat(aggregate.getEpisodeNumber()).isEqualTo(1);
    assertThat(aggregate.getScoreSum()).isEqualTo(14L);
    assertThat(aggregate.getScoreCount()).isEqualTo(2L);
});
```

- [ ] **Step 2: Run the repository test and verify the red failure**

Run:

```powershell
.\mvnw.cmd test "-Dtest=DiaryEntryRepositoryTest"
```

Expected: compilation fails because the repository method does not exist. If Docker is unavailable, record that infrastructure failure and still add a service-level Mockito test for the query contract; do not weaken the repository assertion.

- [ ] **Step 3: Add the JPQL query**

Add the projection and query beside the existing owner-scoped episode query:

```java
interface PublicEpisodeRatingAggregate {
    String getSeriesTmdbId();
    Integer getSeasonNumber();
    Integer getEpisodeNumber();
    Long getScoreSum();
    Long getScoreCount();
}

@Query("""
        SELECT d.content.seriesTmdbId AS seriesTmdbId,
               d.content.seasonNumber AS seasonNumber,
               d.content.episodeNumber AS episodeNumber,
               SUM(d.score) AS scoreSum,
               COUNT(d.score) AS scoreCount
        FROM DiaryEntry d
        WHERE d.content.type = com.watchwise.watchwise_api.content.entity.ContentType.EPISODE
        AND d.content.seriesTmdbId IN :seriesTmdbIds
        AND d.score IS NOT NULL
        AND d.user.isProfilePublic = true
        GROUP BY d.content.seriesTmdbId, d.content.seasonNumber, d.content.episodeNumber
        """)
List<PublicEpisodeRatingAggregate> findPublicEpisodeRatingAggregatesBySeriesTmdbIdIn(
        @Param("seriesTmdbIds") Collection<String> seriesTmdbIds);
```

- [ ] **Step 4: Run the repository test and verify green**

Run the same Maven command. Expected: the public scored entry is returned and private/unscored entries are excluded. If Testcontainers remains unavailable, compile the repository and run the focused service tests after Task 3, then report the environment limitation.

- [ ] **Step 5: Commit the repository change**

```powershell
git add src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java
git commit -m "feat(diaryentry): query public episode ratings"
```

The commit message must not contain a `Co-Authored-By` trailer or any other self-attribution.

---

### Task 3: Calculate averages, enforce privacy, and sort

**Files:**

- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java`

**Interfaces:**

- Consumes: `findScoredEpisodeEntriesByUserIdAndSeriesTmdbIdIn`, `findScoredPublicEpisodeEntriesBySeriesTmdbIdIn`, `findContentStatsByContentIdIn`, `FollowerRepository.existsByFollowerIdAndFollowedIdAndStatus`, and `UserListItemResponseDTO.withRatingAverages(...)`.
- Produces: Enriched `UserListDetailedResponseDTO.items` and sorting for `globalEpisodeAvgRating` and `contentAvgRating`, while preserving `episodeAvgRating` semantics with authorization.

- [ ] **Step 1: Write the failing service tests**

Add these tests to `UserListServiceImplTest` before changing production code:

1. `shouldReturnOwnerAndPublicRatingAveragesForEachApplicableItem` — build a list containing a series, season, episode, movie, and nested list; stub owner episode entries, public episode entries, and `ContentStats`; call `getUserListById` without `sortBy`; assert the three fields on each returned item, with `null` for the nested list and unsupported averages.
2. `shouldSortByGlobalEpisodeAverageRatingAndKeepUnratedItemsLast` — provide two series with global episode scores whose order differs from the owner scores; call with `globalEpisodeAvgRating` in both directions; assert rated items are ordered by global values and unrated items remain last.
3. `shouldSortByDirectContentAverageRating` — provide public direct content stats for a movie and series; call with `contentAvgRating`; assert descending and ascending order.
4. `shouldRejectOwnerEpisodeAverageSortWhenPrivateOwnerIsNotVisibleToViewer` — create a private owner, a different viewer, no accepted follower relation, and call with `episodeAvgRating`; assert `ForbiddenException` and no mapper invocation.
5. `shouldRedactOwnerEpisodeAverageForUnauthorizedViewer` — call without an owner sort for a public list owned by a private profile; assert `episodeAverageRating` is `null` while public aggregate fields remain populated.
6. `shouldAllowOwnerEpisodeAverageForAcceptedFollower` — stub an accepted follower relation and assert the owner average is returned and owner sorting succeeds.

For aggregate projections, mock `DiaryEntryRepository.ContentStats` and stub `getContentId()`/`getAverageScore()` rather than constructing a database projection in the service test.

- [ ] **Step 2: Run the service tests and verify the red failure**

Run:

```powershell
.\mvnw.cmd test "-Dtest=UserListServiceImplTest"
```

Expected: compilation failures for the new DTO accessors/sort values, followed by assertion failures after Task 1 is present. Confirm the failing assertions are the missing averages, global ordering, or privacy behavior.

- [ ] **Step 3: Expand sort validation**

Change `ITEM_SORT_FIELDS` and the invalid-sort error text to include `globalEpisodeAvgRating` and `contentAvgRating` exactly. Preserve `sortDirection` validation and the existing position/date/duration branches.

- [ ] **Step 4: Add owner-rating visibility resolution**

Before calculating or sorting owner averages, determine whether the viewer can see the list owner's profile:

```java
private boolean canViewOwnerRatings(UUID viewerId, User owner) {
    return viewerId.equals(owner.getId())
            || Boolean.TRUE.equals(owner.getIsProfilePublic())
            || followerRepository.existsByFollowerIdAndFollowedIdAndStatus(
                    viewerId, owner.getId(), FollowStatus.ACCEPTED);
}
```

When `sortBy=episodeAvgRating` and this returns `false`, throw `ForbiddenException` before loading list item state. When the list is otherwise readable but owner ratings are not visible, leave `episodeAverageRating` null and continue with public aggregates.

- [ ] **Step 5: Extract reusable episode average grouping**

Refactor the current `computeEpisodeAverageRatings` into a helper that accepts the scored episode entries and returns a map keyed by list-item UUID. Keep the existing series/season/episode key construction and average calculation. Call it once for owner entries when authorized and once for public entries from the new repository method.

Do not query episode entries when no filtered item is a `SERIES`, `SEASON`, or `EPISODE`. Do not calculate owner entries for an unauthorized viewer.

- [ ] **Step 6: Build direct content averages**

Collect persisted content UUIDs from filtered content items, call `findContentStatsByContentIdIn` once when the set is non-empty, and map `ContentStats.getAverageScore()` by content UUID. Do not assign a value to nested-list items.

- [ ] **Step 7: Enrich items before sorting**

For every filtered item, call `withRatingAverages(ownerAverage, globalEpisodeAverage, contentAverage)`. Pass `null` for any unavailable map entry. Use the enriched list both for the response and for the requested comparator.

- [ ] **Step 8: Add the two rating comparators**

Use the existing null-last comparator shape for both new sort values:

```java
Comparator<UserListItemResponseDTO> ratingComparator = Comparator.comparing(
        item -> ratingByItemId.get(item.id()),
        Comparator.nullsLast(directionComparator));
```

Use `episodeAverageRating` for `episodeAvgRating`, `globalEpisodeAverageRating` for `globalEpisodeAvgRating`, and `contentAverageRating` for `contentAvgRating`. Keep nulls last after reversing the non-null value comparator.

- [ ] **Step 9: Run the service tests and verify green**

Run the same Maven command. Expected: all existing list tests and the new average, ordering, and privacy tests pass.

- [ ] **Step 10: Commit the service change**

```powershell
git add src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java
git commit -m "feat(userlist): sort items by rating averages"
```

The commit message must not contain a `Co-Authored-By` trailer or any other self-attribution.

---

### Task 4: Verify the HTTP contract and synchronize documentation

**Files:**

- Modify: `src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerIntegrationTest.java`
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

**Interfaces:**

- Consumes: The service behavior from Task 3.
- Produces: Documented JSON fields, accepted sort values, and privacy/error behavior.

- [ ] **Step 1: Write the failing integration assertions**

Extend the list-detail integration coverage to request `sortBy=globalEpisodeAvgRating` and `sortBy=contentAvgRating`, then assert the JSON fields:

```java
andExpect(jsonPath("$.items[0].episodeAverageRating").value(7.5))
andExpect(jsonPath("$.items[0].globalEpisodeAverageRating").value(8.0))
andExpect(jsonPath("$.items[0].contentAverageRating").value(7.0));
```

Add a private-owner/non-authorized-viewer request with `sortBy=episodeAvgRating` and assert `403`. Add a normal list read for the same visibility case and assert `episodeAverageRating` is null.

- [ ] **Step 2: Run the integration tests and verify the red failure**

Run:

```powershell
.\mvnw.cmd test "-Dtest=UserListControllerIntegrationTest"
```

Expected: the new JSON paths or sort requests fail until the OpenAPI-facing behavior is implemented. If Docker is unavailable, record the Testcontainers limitation and rely on the service tests for the behavioral red/green cycle.

- [ ] **Step 3: Update `openapi.yaml`**

Add `globalEpisodeAvgRating` and `contentAvgRating` to the `sortBy` enum and describe their scopes. Add nullable `episodeAverageRating`, `globalEpisodeAverageRating`, and `contentAverageRating` properties to `UserListItem`, including their applicable content types and privacy behavior. Document `403` for unauthorized owner-scoped sorting.

- [ ] **Step 4: Update `business-rules.md`**

Extend the existing list-item rating rule with the two new sorting keys, the three response fields, public-profile filtering for global/direct averages, and the owner-profile authorization guard. Explicitly document that a public list does not make a private owner's ratings visible.

- [ ] **Step 5: Update `progress.md`**

Append a `2026-09-30` entry describing the implemented list rating sort values, response averages, batched aggregation, and private-profile protection. Record only behavior that shipped.

- [ ] **Step 6: Run the focused regression suite**

Run:

```powershell
.\mvnw.cmd test "-Dtest=UserListItemMapperTest,UserListServiceImplTest,UserListControllerIntegrationTest,DiaryEntryRepositoryTest"
```

Expected: all available focused tests pass. If Testcontainers cannot start, separate the infrastructure failure from Java test failures in the final report.

- [ ] **Step 7: Inspect the final diff and commit code only**

Verify that the diff contains no changes to the user's pre-existing `.gitignore`, `.m2/`, or `.testcontainers.properties` changes. Stage only Java production/test files for the feature; leave `docs/context/` and `docs/superpowers/` changes uncommitted per repository instructions.

```powershell
git status --short
git diff --check
git add src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemResponseDTO.java src/main/java/com/watchwise/watchwise_api/userlist/mapper/UserListItemMapper.java src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java src/test/java/com/watchwise/watchwise_api/userlist/mapper/UserListItemMapperTest.java src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerIntegrationTest.java
git commit -m "feat(userlist): expose rating-based list sorting"
```

The commit message must not contain a `Co-Authored-By` trailer or any other self-attribution. Do not push without explicit user approval.

---

## Final Verification

After the last commit, run the complete test suite when Docker is available:

```powershell
.\mvnw.cmd test
```

Before claiming completion, confirm the output and report any Testcontainers-only limitation separately from code failures.
