# Daily Game Feed Sharing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist the user's explicit daily-game sharing toggle and expose each permanently shared terminal result as a new event in the followers' feed.

**Architecture:** Extend `UserDailyGameResult` with a pending sharing preference and an immutable publication timestamp. The existing transactional attempt flow sets the timestamp only when the final attempt produces `COMPLETED` or `FAILED` and the toggle is enabled. The existing pull-based feed gains a sixth repository source keyed by `sharedAt` and maps it to a dedicated daily-game preview block.

**Tech Stack:** Spring Boot 4.1, Java 21, Spring Data JPA, PostgreSQL/Flyway, JUnit 5, Mockito, MockMvc, Testcontainers, Maven Wrapper.

## Global Constraints

- Sharing is per daily-game modality, manual, permanent, and allowed for both `COMPLETED` and `FAILED` terminal results.
- `IN_PROGRESS` results never appear in the feed.
- The client sends the current `shareOnCompletion` toggle state with attempts; omitted values preserve the stored preference for older clients.
- The result update and publication timestamp are written inside the existing pessimistic-lock transaction.
- Feed visibility remains limited to users followed with `FollowStatus.ACCEPTED`.
- Daily-game feed events do not receive likes or comments.
- Do not add a share endpoint, a share entity/table, or a materialized feed table.
- Update `openapi.yaml`, both database schema documents, `business-rules-summary.md`, and `progress.md` with the implementation.
- Use `mvnw.cmd` on Windows and keep all new domain errors in the existing `ApiError` handling flow.
- Documentation-only files remain uncommitted under this repository's `AGENTS.md` convention.

---

## File map

### Persistence and daily-game domain

- Create `src/main/resources/db/migration/V61__add-daily-game-feed-sharing.sql` for the two result columns, terminal-state check, and feed index.
- Modify `src/main/java/com/watchwise/watchwise_api/dailygame/entity/UserDailyGameResult.java` to store the pending toggle and immutable `sharedAt`.
- Modify `src/main/java/com/watchwise/watchwise_api/dailygame/repository/UserDailyGameResultRepository.java` with the feed candidate query.
- Modify `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAttemptRequest.java` with the optional toggle field.
- Modify `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameStateDTO.java`, `DailyGameAttemptResponseDTO.java`, and `DailyGameHistoryDTO.java` with sharing state.
- Create `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameResultPreviewDTO.java` for the feed payload.
- Modify `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java` and `DailyGameServiceImpl.java` to carry and persist the state.

### Feed

- Modify `src/main/java/com/watchwise/watchwise_api/feed/dto/FeedEventType.java` with `DAILY_GAME_RESULT`.
- Modify `src/main/java/com/watchwise/watchwise_api/feed/dto/FeedItemDTO.java` with `dailyGameResult`.
- Modify `src/main/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImpl.java` to fetch, merge, and map the sixth source.

### Tests

- Modify `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImplTest.java` for the red-green service cases.
- Modify `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssemblerTest.java` for DTO mapping.
- Modify `src/test/java/com/watchwise/watchwise_api/dailygame/repository/DailyGameRepositoryTest.java` for migration defaults, constraints, and feed candidates.
- Modify `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerTest.java` and `DailyGameControllerIntegrationTest.java` for request/response binding and CSRF.
- Modify `src/test/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImplTest.java` and feed integration tests for six-source merging, cursor propagation, and visibility.

### Documentation

- Modify `docs/context/openapi.yaml`.
- Modify `docs/context/database-schema.md` and `docs/context/database-schema.html`.
- Modify `docs/context/business-rules-summary.md`.
- Modify `docs/context/progress.md`.

---

### Task 1: Add immutable sharing state to daily-game results

**Files:**
- Create: `src/main/resources/db/migration/V61__add-daily-game-feed-sharing.sql`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/entity/UserDailyGameResult.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/repository/UserDailyGameResultRepository.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/repository/DailyGameRepositoryTest.java`

**Interfaces:**
- Produces `UserDailyGameResult.isShareOnCompletion()`, `getSharedAt()`, `setShareOnCompletion(boolean)`, and `markSharedAt(LocalDateTime)`.
- Produces `UserDailyGameResultRepository.findFeedCandidates(Collection<UUID>, LocalDateTime, UUID, Pageable)` returning `List<UserDailyGameResult>`.

- [ ] **Step 1: Write the failing repository tests**

Add tests named `shouldDefaultSharingFieldsForNewResult`, `shouldFindOnlySharedResultsForFeed`, and `shouldRejectSharedInProgressResult`. The first should build and save a result without sharing fields and assert `false` and `null`; the second should save one shared terminal result, one unshared terminal result, and one shared result with a different followed user, then assert the query returns only the matching shared terminal result; the third should persist a result with `status = IN_PROGRESS` and `sharedAt != null` through native SQL and assert the database rejects it with `ck_user_daily_game_results_shared_terminal`.

```java
List<UserDailyGameResult> candidates = resultRepository.findFeedCandidates(
        List.of(followedUser.getId()), null, null, PageRequest.of(0, 10));

assertThat(candidates).extracting(UserDailyGameResult::getId)
        .containsExactly(sharedResult.getId());
```

- [ ] **Step 2: Run the focused repository test and verify the expected failure**

Run:

```powershell
.\mvnw.cmd test "-Dtest=DailyGameRepositoryTest"
```

Expected: compilation fails because the new fields/query/migration do not exist yet, not because Docker/Testcontainers cannot start.

- [ ] **Step 3: Add the migration**

Create `V61__add-daily-game-feed-sharing.sql` with:

```sql
ALTER TABLE user_daily_game_results
    ADD COLUMN share_on_completion BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN shared_at TIMESTAMP;

ALTER TABLE user_daily_game_results
    ADD CONSTRAINT ck_user_daily_game_results_shared_terminal CHECK (
        shared_at IS NULL OR status IN ('COMPLETED', 'FAILED')
    );

CREATE INDEX idx_user_daily_game_results_feed_shared
    ON user_daily_game_results (user_id, shared_at DESC, id DESC)
    WHERE shared_at IS NOT NULL;
```

- [ ] **Step 4: Add entity state without an unshare operation**

Add a Lombok-builder-defaulted `boolean shareOnCompletion` mapped to `share_on_completion` and a nullable `LocalDateTime sharedAt` mapped to `shared_at`. Keep the `sharedAt` field without a public setter. Implement:

```java
public void setShareOnCompletion(boolean shareOnCompletion) {
    if (sharedAt == null) {
        this.shareOnCompletion = shareOnCompletion;
    }
}

public void markSharedAt(LocalDateTime timestamp) {
    if (sharedAt == null) {
        this.sharedAt = timestamp;
    }
}
```

- [ ] **Step 5: Add the feed candidate query**

Add a JPQL query that fetches `user` and `dailyChallenge`, filters `sharedAt IS NOT NULL` and the supplied user IDs, applies the existing keyset condition using `sharedAt` and `id`, and orders by `sharedAt DESC, id DESC`:

```java
@Query("""
        SELECT result FROM UserDailyGameResult result
        JOIN FETCH result.user
        JOIN FETCH result.dailyChallenge
        WHERE result.user.id IN :userIds
          AND result.sharedAt IS NOT NULL
          AND (
              CAST(:cursorCreatedAt AS timestamp) IS NULL
              OR result.sharedAt < :cursorCreatedAt
              OR (result.sharedAt = :cursorCreatedAt
                  AND :cursorId IS NOT NULL
                  AND result.id < :cursorId)
          )
        ORDER BY result.sharedAt DESC, result.id DESC
        """)
List<UserDailyGameResult> findFeedCandidates(
        @Param("userIds") Collection<UUID> userIds,
        @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
        @Param("cursorId") UUID cursorId,
        Pageable pageable);
```

- [ ] **Step 6: Run the focused repository test and verify it passes**

Run:

```powershell
.\mvnw.cmd test "-Dtest=DailyGameRepositoryTest"
```

Expected: all `DailyGameRepositoryTest` tests pass, including the migration constraint test. If Docker/Testcontainers is unavailable, record that as an environment blocker and continue with unit tests; do not weaken the test.

- [ ] **Step 7: Commit the code and migration**

```powershell
git add src/main/resources/db/migration/V61__add-daily-game-feed-sharing.sql src/main/java/com/watchwise/watchwise_api/dailygame/entity/UserDailyGameResult.java src/main/java/com/watchwise/watchwise_api/dailygame/repository/UserDailyGameResultRepository.java src/test/java/com/watchwise/watchwise_api/dailygame/repository/DailyGameRepositoryTest.java
git commit -m "feat(daily-game): persist feed sharing state"
```

After committing, confirm explicitly that the commit message has no `Co-Authored-By` trailer or other self-attribution.

### Task 2: Carry the toggle through attempts and daily-game responses

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAttemptRequest.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameStateDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAttemptResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameHistoryDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssemblerTest.java`

**Interfaces:**
- `DailyGameAttemptRequest` gains `Boolean shareOnCompletion` as its last component.
- `DailyGameStateDTO` and `DailyGameAttemptResponseDTO` gain primitive `boolean shareOnCompletion` and `boolean sharedToFeed` after `answer`.
- `DailyGameHistoryDTO` gains primitive `boolean sharedToFeed` after `answer`.

- [ ] **Step 1: Write failing service tests for completed sharing**

Add `shouldShareCompletedResultWhenToggleIsEnabledOnFinalAttempt`. Build an open result, submit a correct candidate with `shareOnCompletion = true`, assert `status() == COMPLETED`, `sharedToFeed() == true`, `result.getSharedAt() == NOW`, and `result.isShareOnCompletion()` is true.

Add `shouldShareFailedResultWhenToggleIsEnabledOnFinalAttempt`. Build a result one attempt before the limit, submit a valid wrong candidate with `shareOnCompletion = true`, and assert `status() == FAILED`, `sharedToFeed() == true`, and a non-null `sharedAt`.

```java
DailyGameAttemptRequest request = request("550", null, null, null, null, true);
DailyGameAttemptResponseDTO response = service().submitAttempt(
        USER_ID, DailyGameType.MOVIE_BY_INFO, request);

assertThat(response.status()).isEqualTo(DailyGameViewStatus.COMPLETED);
assertThat(response.sharedToFeed()).isTrue();
assertThat(result.getSharedAt()).isEqualTo(NOW);
```

- [ ] **Step 2: Write failing service tests for pending and disabled sharing**

Add `shouldPersistToggleUntilTheResultBecomesTerminal`, submitting a wrong non-final attempt with `true` and a later final attempt with `null`; assert the first response is `IN_PROGRESS` with `sharedToFeed == false` and the second shares the result. Add `shouldNotShareTerminalResultWhenToggleIsDisabled`, using `false` on the final attempt and asserting `sharedAt == null`. Add `shouldLeaveSharingPreferenceUntouchedWhenCandidateIsInvalid`, asserting both the preference and `sharedAt` remain unchanged after `BadRequestException`.

- [ ] **Step 3: Run the focused service tests and verify the expected failure**

Run:

```powershell
.\mvnw.cmd test "-Dtest=DailyGameServiceImplTest,DailyChallengeResponseAssemblerTest"
```

Expected: compilation or assertion failures because the request/response fields and terminal sharing behavior do not exist yet.

- [ ] **Step 4: Extend the request and response records**

Add `Boolean shareOnCompletion` to `DailyGameAttemptRequest`, preserving the existing five-argument constructor as an overload that delegates with `null` so current test fixtures and older Java callers remain source-compatible. Add the response booleans and update all assembler/test constructors.

- [ ] **Step 5: Update the response assembler**

When no result exists, return `shareOnCompletion = false` and `sharedToFeed = false`. For an existing result, map `result.isShareOnCompletion()` and `result.getSharedAt() != null`. Keep answer redaction unchanged for `IN_PROGRESS`; only terminal responses expose the answer.

- [ ] **Step 6: Implement transactional preference and publication logic**

In `DailyGameServiceImpl.submitAttempt`, preserve the existing order through `assertOpenAndHasAttempts`, validate the candidate before mutating the result, then apply the nullable request preference:

```java
if (request.shareOnCompletion() != null) {
    result.setShareOnCompletion(request.shareOnCompletion());
}
```

After assigning the new status, set `sharedAt` only when the result is terminal and `result.isShareOnCompletion()` is true:

```java
if (result.getStatus() != DailyGameResultStatus.IN_PROGRESS
        && result.isShareOnCompletion()) {
    result.markSharedAt(now);
}
```

Do not change `sharedAt` on an already shared result, and do not change the preference when candidate validation throws.

- [ ] **Step 7: Run the focused service tests and verify they pass**

Run:

```powershell
.\mvnw.cmd test "-Dtest=DailyGameServiceImplTest,DailyChallengeResponseAssemblerTest"
```

Expected: all focused daily-game service and assembler tests pass with no new warnings.

- [ ] **Step 8: Commit the daily-game behavior**

```powershell
git add src/main/java/com/watchwise/watchwise_api/dailygame/dto src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImpl.java src/test/java/com/watchwise/watchwise_api/dailygame/service/impl
git commit -m "feat(daily-game): apply feed sharing toggle"
```

After committing, confirm explicitly that the commit message has no `Co-Authored-By` trailer or other self-attribution.

### Task 3: Add the daily-game event to the pull-based feed

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameResultPreviewDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/feed/dto/FeedEventType.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/feed/dto/FeedItemDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/feed/controller/FeedControllerIntegrationTest.java`

**Interfaces:**
- `DailyGameResultPreviewDTO` is a record with `LocalDate challengeDate`, `DailyGameType gameType`, `DailyGameTargetKind targetKind`, `int maxAttempts`, `DailyGameViewStatus status`, `int attemptsUsed`, `int score`, and `DailyGameAnswerDTO answer`.
- `FeedItemDTO` gains nullable `DailyGameResultPreviewDTO dailyGameResult` before `createdAt`.
- `FeedServiceImpl` depends on `UserDailyGameResultRepository` and maps shared results without TMDB calls.

- [ ] **Step 1: Write failing feed tests**

Add `shouldMergeDailyGameResultWithExistingSources`, stubbing one shared `UserDailyGameResult` and one diary event at different timestamps, then assert the daily-game event is ordered by `sharedAt` and its preview fields are mapped. Assert `content`, `score`, `comment`, `likesCount`, `likedByMe`, `watchedWith`, `pick`, and `picksTemplate` are null.

Add `shouldReportHasNextWhenDailyGameSourceHasMoreCandidates`, returning two daily-game results for a page size of one and asserting `hasNext()` and `nextCursor()`.

Add `shouldPassCursorToDailyGameRepository`, using the same base64 cursor fixture as the existing feed test and verifying `findFeedCandidates` receives its decoded timestamp and UUID.

```java
assertThat(result.content()).extracting(FeedItemDTO::eventType)
        .containsExactly(FeedEventType.DAILY_GAME_RESULT);
FeedItemDTO item = result.content().getFirst();
assertThat(item.dailyGameResult().status()).isEqualTo(DailyGameViewStatus.FAILED);
assertThat(item.likesCount()).isNull();
assertThat(item.createdAt()).isEqualTo(sharedResult.getSharedAt());
```

- [ ] **Step 2: Run the focused feed test and verify the expected failure**

Run:

```powershell
.\mvnw.cmd test "-Dtest=FeedServiceImplTest"
```

Expected: compilation failures for the new repository dependency/DTO fields or assertion failures because the sixth source is not implemented.

- [ ] **Step 3: Create the preview DTO and extend the feed contract**

Create the record with the exact fields in the interface block. Add `DAILY_GAME_RESULT` to the enum and `dailyGameResult` to `FeedItemDTO`. Update all existing positional constructors so their new field is `null`.

- [ ] **Step 4: Add the sixth source to `FeedServiceImpl`**

Fetch daily-game candidates with `fetchLimit` and the same cursor values as the other sources. Track `dailyGameHasMore`, trim the result list, build `FeedCandidate(result.getSharedAt(), result.getId(), toDailyGameFeedItem(result))`, and include `dailyGameHasMore` in `hasNext`.

Map the preview from the challenge and result:

```java
private FeedItemDTO toDailyGameFeedItem(UserDailyGameResult result) {
    DailyChallenge challenge = result.getDailyChallenge();
    DailyGameResultPreviewDTO preview = new DailyGameResultPreviewDTO(
            challenge.getChallengeDate(), challenge.getGameType(), challenge.getTargetKind(),
            challenge.getGameType().maxAttempts(), toViewStatus(result.getStatus()),
            result.getAttemptsUsed(), result.getScore(), toAnswer(challenge));
    return new FeedItemDTO(
            FeedEventType.DAILY_GAME_RESULT, result.getId(),
            userMapper.userToUserPreviewDto(result.getUser()), null, null, null, null,
            null, null, null, null, null, preview, result.getSharedAt());
}
```

Reuse the existing answer snapshot/image URL logic through a focused helper or assembler method; do not call TMDB while reading the feed.

- [ ] **Step 5: Update existing feed stubs and run focused tests**

Update `stubEmptySources`, `stubEmptyDroppedAndTop5`, and all direct `FeedItemDTO` constructors to stub the daily-game repository with an empty list unless a test is specifically exercising it. Run:

```powershell
.\mvnw.cmd test "-Dtest=FeedServiceImplTest,FeedControllerTest,FeedControllerIntegrationTest"
```

Expected: all feed unit/controller tests pass, including the new six-source cases.

- [ ] **Step 6: Commit the feed behavior**

```powershell
git add src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameResultPreviewDTO.java src/main/java/com/watchwise/watchwise_api/feed/dto src/main/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImpl.java src/test/java/com/watchwise/watchwise_api/feed
git commit -m "feat(feed): expose shared daily-game results"
```

After committing, confirm explicitly that the commit message has no `Co-Authored-By` trailer or other self-attribution.

### Task 4: Update HTTP contracts and integration coverage

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerIntegrationTest.java`
- Modify: `docs/context/openapi.yaml`

- [ ] **Step 1: Add failing controller contract assertions**

Update the unit response fixture to include `shareOnCompletion` and `sharedToFeed`. Add an integration request with JSON `{"tmdbId":"550","shareOnCompletion":true}` and assert the response exposes `shareOnCompletion` and `sharedToFeed` when the result becomes terminal. Keep the existing missing-CSRF request and assert it remains `403`.

- [ ] **Step 2: Run the controller tests and verify the expected failure**

Run:

```powershell
.\mvnw.cmd test "-Dtest=DailyGameControllerTest,DailyGameControllerIntegrationTest"
```

Expected: failures until the DTO signatures and response mapping are updated; the CSRF regression test must continue to pass once implementation is complete.

- [ ] **Step 3: Update OpenAPI**

In `docs/context/openapi.yaml`:

1. Document `shareOnCompletion` in `DailyGameAttemptRequest` as an optional nullable boolean that preserves the previous preference when omitted.
2. Add `shareOnCompletion` and `sharedToFeed` to `DailyGameStateDTO` and `DailyGameAttemptResponseDTO`.
3. Add `sharedToFeed` to `DailyGameHistoryDTO`.
4. Add `DAILY_GAME_RESULT` to the `FeedItem.eventType` enum.
5. Add `dailyGameResult` referencing a new `DailyGameResultPreviewDTO` schema and state that it is populated only for `DAILY_GAME_RESULT`.
6. Define `DailyGameResultPreviewDTO` with the exact preview fields and terminal-only answer semantics.
7. Update the `/games/{gameType}/attempt` and `/feed` descriptions to describe explicit toggle-based publication and accepted-follower visibility.

- [ ] **Step 4: Run controller tests again and verify they pass**

Run:

```powershell
.\mvnw.cmd test "-Dtest=DailyGameControllerTest,DailyGameControllerIntegrationTest"
```

Expected: all controller tests pass with the documented JSON shape and no default Spring error body.

### Task 5: Synchronize domain documentation and verify the complete change

**Files:**
- Modify: `docs/context/database-schema.md`
- Modify: `docs/context/database-schema.html`
- Modify: `docs/context/business-rules-summary.md`
- Modify: `docs/context/progress.md`

- [ ] **Step 1: Update the database schema documents**

Document `share_on_completion`, `shared_at`, `ck_user_daily_game_results_shared_terminal`, and `idx_user_daily_game_results_feed_shared` under `user_daily_game_results`. State that `shared_at` is immutable at the application level, can only exist for terminal statuses, and is the feed event timestamp.

- [ ] **Step 2: Update business rules**

Add a Daily Games entry stating that a nullable request toggle is persisted while a game is open, publication happens once at terminal completion for either status, invalid candidates do not alter the preference, and no unshare operation exists. Extend the Feed entry to include `DAILY_GAME_RESULT`, `sharedAt` keyset ordering, accepted-follower visibility, frozen answer snapshots, and the deliberate absence of likes/comments.

- [ ] **Step 3: Append the shipped feature to progress**

Append a `## 2026-09-29 — Compartilhamento de jogos diários no feed` section describing only the implemented migration, toggle behavior, terminal publication, feed event, visibility, and tests. Do not add a next-steps section.

- [ ] **Step 4: Run focused and full verification**

Run the complete relevant suite:

```powershell
.\mvnw.cmd test "-Dtest=DailyGameRepositoryTest,DailyGameServiceImplTest,DailyChallengeResponseAssemblerTest,DailyGameControllerTest,DailyGameControllerIntegrationTest,FeedServiceImplTest,FeedControllerTest,FeedControllerIntegrationTest"
```

Then run the full suite:

```powershell
.\mvnw.cmd test
```

Expected: exit code `0`, zero failures and zero errors. Repository tests require Docker/Testcontainers; if the environment blocks them, report the exact failed command and complete all non-container verification without claiming the full suite passed.

- [ ] **Step 5: Inspect the final diff and commit only code changes**

Run:

```powershell
git diff --check
git status --short
git diff --stat
```

Confirm that `docs/context/` and `docs/superpowers/` remain worktree-only per `AGENTS.md`, while Java, SQL, and test changes are committed. Do not push without explicit user approval.
