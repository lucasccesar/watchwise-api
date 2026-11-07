# Diary Series and Score Filters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add exact-score filtering to the diary, series filtering across all related content levels, and an ordered series-options endpoint for the diary dropdown.

**Architecture:** Keep the feature inside `diaryentry`. The repository normalizes `SERIES`, `SEASON`, and `EPISODE` into one series key; the service validates filters, enforces diary visibility, resolves titles through the existing `TmdbClient`, and assembles DTOs; the controller exposes the query parameters and the new endpoint.

**Tech Stack:** Spring Boot 4.1, Java 21, Spring Data JPA, PostgreSQL, `TmdbClient`, JUnit 5, Mockito, AssertJ, MockMvc, and Testcontainers.

## Global Constraints

- Count every `DiaryEntry` related to a series: `SERIES`, `SEASON`, and `EPISODE`.
- Count each rewatch as a separate entry.
- Treat `score` as an exact integer filter in the inclusive range 1 to 10.
- Apply `assertCanViewDiary` before exposing counts or calling TMDB.
- Resolve titles from TMDB using the target user's preferred language; do not persist titles.
- Keep a local aggregate with `title: null` when TMDB returns `NotFound`; map TMDB unavailability to `TmdbUnavailableException` and HTTP 502.
- The options endpoint is not paginated and ignores date, year, type, review, and score filters.
- Do not create a migration, entity, table, or new cache.
- Update `openapi.yaml`, `business-rules.md`, and `progress.md`; leave documentation-only changes uncommitted by repository convention.
- Use Conventional Commits without a `Co-Authored-By` trailer. Never push without explicit permission.

---

### Task 1: Add repository aggregation and filters

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java`

**Interfaces:**
- Add nested projection `DiarySeriesCount` with `getSeriesTmdbId()` and `getEntriesCount()`.
- Add `List<DiarySeriesCount> findSeriesEntryCountsByUserId(UUID userId)`.
- Extend `findByUserIdWithFilters` with `String seriesTmdbId` and `Integer score` before `Pageable`.

- [ ] **Step 1: Write the failing aggregation test**

Persist for Lucas one `SERIES`, one `SEASON`, and two `EPISODE` entries for `1399`, with the same episode logged twice, plus one episode for `1396` and one movie. Call `findSeriesEntryCountsByUserId` and assert `1399 -> 4`, `1396 -> 1`, with no movie row.

```java
@Test
@DisplayName("[findSeriesEntryCountsByUserId] Should Count Every Related Entry And Rewatch")
void shouldCountEveryRelatedEntryAndRewatch() {
    Content series = contentRepository.save(buildContent("1399", ContentType.SERIES));
    Content season = contentRepository.save(buildSeason("1399", 1));
    Content episode = contentRepository.save(buildEpisode("1399", 1, 1));
    Content otherEpisode = contentRepository.save(buildEpisode("1396", 1, 1));

    diaryEntryRepository.save(buildEntry(lucas, series));
    diaryEntryRepository.save(buildEntry(lucas, season));
    diaryEntryRepository.save(buildEntry(lucas, episode, 1));
    diaryEntryRepository.saveAndFlush(buildEntry(lucas, episode, 2));
    diaryEntryRepository.save(buildEntry(lucas, otherEpisode));
    diaryEntryRepository.saveAndFlush(buildEntry(lucas, fightClub));

    List<DiaryEntryRepository.DiarySeriesCount> result =
            diaryEntryRepository.findSeriesEntryCountsByUserId(lucas.getId());

    assertThat(result)
            .extracting(DiaryEntryRepository.DiarySeriesCount::getSeriesTmdbId,
                    DiaryEntryRepository.DiarySeriesCount::getEntriesCount)
            .containsExactly(tuple("1399", 4L), tuple("1396", 1L));
}
```

- [ ] **Step 2: Run the test and verify the expected failure**

Run `./mvnw.cmd test "-Dtest=DiaryEntryRepositoryTest"` from Bash or `.\mvnw.cmd test "-Dtest=DiaryEntryRepositoryTest"` from PowerShell. Expected: compilation failure because the projection and method are absent.

- [ ] **Step 3: Write the failing exact-filter test**

Persist score-10 and score-9 entries for `1399`, plus a score-10 entry for `1396`. Call:

```java
Page<DiaryEntry> result = diaryEntryRepository.findByUserIdWithFilters(
        lucas.getId(), null, null, null, null, "1399", 10, PageRequest.of(0, 10));
```

Assert that only the score-10 entry for `1399` is returned.

- [ ] **Step 4: Run the test and verify the missing-signature failure**

Run the focused repository test again. Expected: compilation failure until the method signature and query are updated.

- [ ] **Step 5: Implement the repository projection and queries**

Add:

```java
interface DiarySeriesCount {
    String getSeriesTmdbId();
    Long getEntriesCount();
}
```

Implement the aggregate with a native query over `diary_entries d JOIN contents c`:

```sql
CASE WHEN c.type = 'SERIES' THEN c.tmdb_id ELSE c.series_tmdb_id END
```

Filter `c.type IN ('SERIES', 'SEASON', 'EPISODE')`, group by the normalized expression, and order by `COUNT(d.id) DESC, series_tmdb_id ASC`.

Extend the existing filtered JPQL query with:

```java
AND (:seriesTmdbId IS NULL OR (
    (d.content.type = com.watchwise.watchwise_api.content.entity.ContentType.SERIES
        AND d.content.tmdbId = :seriesTmdbId)
    OR (d.content.type IN (
        com.watchwise.watchwise_api.content.entity.ContentType.SEASON,
        com.watchwise.watchwise_api.content.entity.ContentType.EPISODE)
        AND d.content.seriesTmdbId = :seriesTmdbId)
))
AND (:score IS NULL OR d.score = :score)
```

The final method signature is:

```java
Page<DiaryEntry> findByUserIdWithFilters(
        UUID userId, ContentType type, LocalDate watchedDateStart, LocalDate watchedDateEnd,
        Boolean hasReview, String seriesTmdbId, Integer score, Pageable pageable);
```

Keep the existing date, type, review, fetch-join, and ordering clauses unchanged.

- [ ] **Step 6: Update existing repository tests and verify green**

Pass `null, null` to every existing `findByUserIdWithFilters` call and run `.\mvnw.cmd test "-Dtest=DiaryEntryRepositoryTest"`. Expected: all repository tests pass.

- [ ] **Step 7: Commit the repository slice**

```powershell
git add src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java
git commit -m "feat(diary): add series aggregation filters"
```

Immediately inspect the commit message and confirm it has no `Co-Authored-By` trailer or other self-attribution. Do not push.

### Task 2: Add the DTO and service behavior

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/diaryentry/dto/DiarySeriesOptionDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/service/DiaryEntryService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImplTest.java`

**Interfaces:**
- Add `DiarySeriesOptionDTO(String seriesTmdbId, String title, Long entriesCount)`.
- Add `List<DiarySeriesOptionDTO> getDiarySeriesOptions(UUID viewerId, UUID userId)`.
- Extend `getDiaryEntries` with `String seriesTmdbId` and `Integer score` after `Boolean hasReview`.

- [ ] **Step 1: Write the failing service forwarding test**

Call:

```java
diaryEntryService.getDiaryEntries(
        lucasId, lucasId, null, 1, 10, null, null, null, null, "1399", 10);
```

Stub and verify `findByUserIdWithFilters(lucasId, null, null, null, null, "1399", 10, any(PageRequest.class))`. The test must fail to compile before the new signatures exist.

- [ ] **Step 2: Write failing service option tests**

Cover these behaviors:

- two projections become DTOs sorted by count descending, title ascending, and ID ascending;
- `Found` uses `TmdbTvFullDetails.name()`;
- `NotFound` produces a DTO with `title == null`;
- `Unavailable` throws `TmdbUnavailableException`;
- the target user's `preferredLanguage` is forwarded to `getTvFullDetails`;
- invalid score and blank series ID throw the specified `BadRequestException` messages;
- private-profile authorization happens before repository or TMDB interaction.

- [ ] **Step 3: Run the service tests and verify the expected failure**

Run `.\mvnw.cmd test "-Dtest=DiaryEntryServiceImplTest"`. Expected: compilation failure because the DTO and service methods do not exist.

- [ ] **Step 4: Add the DTO and service signatures**

Create:

```java
public record DiarySeriesOptionDTO(String seriesTmdbId, String title, Long entriesCount) {}
```

Update the interface to:

```java
Page<DiaryEntryResponseDTO> getDiaryEntries(
        UUID viewerId, UUID userId, Integer year, Integer pageNumber, Integer pageSize,
        ContentType type, LocalDate dateFrom, LocalDate dateTo, Boolean hasReview,
        String seriesTmdbId, Integer score);

List<DiarySeriesOptionDTO> getDiarySeriesOptions(UUID viewerId, UUID userId);
```

- [ ] **Step 5: Implement filter validation and query selection**

Trim a non-null `seriesTmdbId`, reject a blank result with `BadRequestException("seriesTmdbId cannot be blank")`, reject scores outside 1–10 with `BadRequestException("score must be between 1 and 10")`, include both values in `hasExtraFilters`, and pass them to the repository. Preserve the current no-extra-filter query when all filters remain absent.

- [ ] **Step 6: Implement the series-option assembler**

Load the target user and call `assertCanViewDiary` before `findSeriesEntryCountsByUserId`. Resolve every distinct projection with `tmdbClient.getTvFullDetails(row.getSeriesTmdbId(), target.getPreferredLanguage())`. Map `Found` to `name()`, `NotFound` to null, and `Unavailable` to `tmdbUnavailable()`. Sort by count descending, case-insensitive title ascending with nulls last, then series ID ascending.

- [ ] **Step 7: Run focused service verification**

Run `.\mvnw.cmd test "-Dtest=DiaryEntryServiceImplTest"`. Expected: all service tests pass, including existing date, type, review, pagination, poster, and visibility behavior.

- [ ] **Step 8: Commit the service slice**

```powershell
git add src/main/java/com/watchwise/watchwise_api/diaryentry/dto/DiarySeriesOptionDTO.java src/main/java/com/watchwise/watchwise_api/diaryentry/service/DiaryEntryService.java src/main/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImpl.java src/test/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImplTest.java
git commit -m "feat(diary): resolve series filter options"
```

Confirm the commit has no `Co-Authored-By` trailer or other self-attribution. Do not push.

### Task 3: Expose the controller endpoints

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryController.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryControllerTest.java`

**Interfaces:**
- Add optional `seriesTmdbId` and `score` parameters to `GET /users/{userId}/diary`.
- Add `GET /users/{userId}/diary/series` returning `List<DiarySeriesOptionDTO>`.

- [ ] **Step 1: Write the failing controller tests**

Add a test that calls the direct controller method with `"1399"` and `10`, stubs the 11-argument service call, and verifies both values are forwarded. Add the new-endpoint test:

```java
List<DiarySeriesOptionDTO> expected =
        List.of(new DiarySeriesOptionDTO("1399", "The Office", 235L));
when(diaryEntryService.getDiarySeriesOptions(currentUserId, targetUserId)).thenReturn(expected);

ResponseEntity<List<DiarySeriesOptionDTO>> result =
        diaryEntryController.getDiarySeriesOptions(targetUserId);

assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
assertThat(result.getBody()).containsExactlyElementsOf(expected);
verify(diaryEntryService).getDiarySeriesOptions(currentUserId, targetUserId);
```

- [ ] **Step 2: Run the controller tests and verify the expected failure**

Run `.\mvnw.cmd test "-Dtest=DiaryEntryControllerTest"`. Expected: compilation failure because the controller signature and route do not exist.

- [ ] **Step 3: Implement the controller changes**

Add after `hasReview`:

```java
@RequestParam(required = false) String seriesTmdbId,
@RequestParam(required = false) Integer score
```

Forward the values to the service after `hasReview`. Add:

```java
@GetMapping("/users/{userId}/diary/series")
public ResponseEntity<List<DiarySeriesOptionDTO>> getDiarySeriesOptions(@PathVariable UUID userId) {
    return ResponseEntity.ok(diaryEntryService.getDiarySeriesOptions(getCurrentUserId(), userId));
}
```

- [ ] **Step 4: Update existing controller tests and verify green**

Pass `null, null` to current direct `getDiaryEntries` test calls, then run `.\mvnw.cmd test "-Dtest=DiaryEntryControllerTest"`. Expected: all controller unit tests pass.

- [ ] **Step 5: Commit the controller slice**

```powershell
git add src/main/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryController.java src/test/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryControllerTest.java
git commit -m "feat(diary): expose series and score filters"
```

Confirm the commit has no `Co-Authored-By` trailer or other self-attribution. Do not push.

### Task 4: Add integration coverage and synchronized documentation

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryControllerIntegrationTest.java`
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

- [ ] **Step 1: Write failing integration tests**

Persist `SERIES`, `SEASON`, and `EPISODE` entries for `1399`, one rewatch, score 10 and score 9, an episode for `1396` with score 10, and a movie with score 10. Assert:

```java
mockMvc.perform(getDiaryRequest(user, user.id())
        .param("seriesTmdbId", "1399")
        .param("score", "10"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.content.length()").value(1))
    .andExpect(jsonPath("$.content[0].content.seriesTmdbId").value("1399"))
    .andExpect(jsonPath("$.content[0].score").value(10));
```

Add endpoint coverage for title/count mapping, TMDB `NotFound`, TMDB `Unavailable`, invalid score, blank series ID, missing user, private profile, and missing authentication.

- [ ] **Step 2: Run the integration tests and verify the expected failure**

Run `.\mvnw.cmd test "-Dtest=DiaryEntryControllerIntegrationTest"`. Expected: failure until the new route and behavior are implemented.

- [ ] **Step 3: Update `openapi.yaml`**

Document `seriesTmdbId` and `score` on `GET /users/{userId}/diary`, the exact-score semantics, the existing `400`/`403`/`404` errors, and the new route. Add:

```yaml
DiarySeriesOption:
  type: object
  required: [seriesTmdbId, entriesCount]
  properties:
    seriesTmdbId: { type: string }
    title: { type: string, nullable: true }
    entriesCount: { type: integer, format: int64, minimum: 1 }
```

Document that options count `SERIES`, `SEASON`, and `EPISODE` rows, count rewatches, are ordered by count descending, and are not paginated.

- [ ] **Step 4: Update `business-rules.md`**

Under the existing DiaryEntry section, document the normalized series identity, all-three-content-level counting, rewatch counting, preferred-language title lookup, null title for TMDB `NotFound`, and `502` for TMDB unavailability.

- [ ] **Step 5: Update `progress.md`**

Append `## 2026-10-01 - Diary series and score filters` with only the shipped behavior and test coverage. Do not add a next-steps section or a cross-cutting section.

- [ ] **Step 6: Run focused integration verification**

Run:

```powershell
.\mvnw.cmd test "-Dtest=DiaryEntryControllerIntegrationTest,DiaryEntryControllerTest,DiaryEntryServiceImplTest,DiaryEntryRepositoryTest"
```

Expected: exit code 0, zero failures, zero errors.

- [ ] **Step 7: Commit only code and tests**

```powershell
git add src/main/java src/test/java
git commit -m "feat(diary): add series and score filters"
```

Leave `docs/context/` and `docs/superpowers/` uncommitted. Confirm the commit has no `Co-Authored-By` trailer or other self-attribution. Do not push.

### Task 5: Final verification and loophole audit

**Files:**
- Verify: `src/main/java/com/watchwise/watchwise_api/diaryentry/`
- Verify: `src/test/java/com/watchwise/watchwise_api/diaryentry/`
- Verify: `docs/context/openapi.yaml`
- Verify: `docs/context/business-rules.md`
- Verify: `docs/context/progress.md`

- [ ] **Step 1: Inspect the final worktree**

Run `git status --short`, `git diff --check`, and `git diff --stat`. Confirm only intended documentation changes and the pre-existing `.gitignore`, `.m2/`, and `.testcontainers.properties` changes remain uncommitted.

- [ ] **Step 2: Run the complete test suite**

Run `.\mvnw.cmd test`. Expected: exit code 0 with zero failures and zero errors. Docker must be available for repository tests.

- [ ] **Step 3: Audit recurring bug patterns**

Confirm that invalid values use project `ApiError`, user scoping is present in both queries, all three content levels are normalized consistently, rewatches are not deduplicated, absent filters preserve null semantics, and TMDB `NotFound` remains distinct from `Unavailable`.

- [ ] **Step 4: Record evidence before reporting completion**

Record fresh Maven output, final commit hashes, and exact remaining worktree changes. Do not claim completion until those commands have run successfully.
