# Daily Game Feed Answer Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redact the daily-game answer in feed items for viewers who have not reached a terminal result for the same challenge, while keeping the shared result visible.

**Architecture:** Keep the existing pull-based feed and `UserDailyGameResult` source query. After selecting the page, batch-load the authenticated viewer's results for the selected daily challenges, derive the terminal challenge IDs, and pass that viewer-specific decision into the daily-game feed mapper. Apply the same helper to both `getFeed` and `getFeedView` so neither read path can bypass the redaction.

**Tech Stack:** Spring Boot 4.1, Java 21, Spring Data JPA, Mockito/JUnit 5, AssertJ, MockMvc, PostgreSQL Testcontainers, Maven Wrapper.

## Global Constraints

- `answer` is visible only for viewer results with status `COMPLETED` or `FAILED`.
- No result or `IN_PROGRESS` viewer result produces `answer: null`.
- The feed item, author metadata, author status, attempts used, and score remain visible.
- Viewer-result lookup must be batched; no per-item query is allowed.
- The existing `UserDailyGameResultRepository.findByUserIdAndDailyChallengeIdIn` method must be reused.
- The source result remains immutable; no migration or new persistence field is needed.
- Update `docs/context/openapi.yaml`, `docs/context/business-rules.md`, and `docs/context/progress.md` in the same change; documentation remains uncommitted unless explicitly requested.
- Use Conventional Commits and do not add a `Co-Authored-By` trailer.

---

### Task 1: Add failing feed-service and integration regression tests

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImplTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/feed/controller/FeedControllerIntegrationTest.java`

**Interfaces:**
- Consumes: Existing `FeedServiceImpl`, `UserDailyGameResultRepository.findByUserIdAndDailyChallengeIdIn`, and daily-game fixture helpers.
- Produces: Failing tests that define viewer-specific answer visibility for both feed service read paths and the serialized `/feed` response.

- [ ] **Step 1: Add a service test for a viewer without a result**

Extend the existing daily-game feed tests with a `getFeedView` case that persists or stubs one shared terminal result from the followed user, leaves the authenticated viewer without a result, and asserts:

```java
FeedItemViewDTO item = feedService.getFeedView(viewerId, null, null).content().getFirst();

assertThat(item.eventType()).isEqualTo(FeedEventType.DAILY_GAME_RESULT);
assertThat(item.dailyGameResult().status()).isEqualTo(DailyGameViewStatus.COMPLETED);
assertThat(item.dailyGameResult().answer()).isNull();
verify(dailyGameResultRepository).findByUserIdAndDailyChallengeIdIn(
        eq(viewerId), eq(List.of(challenge.getId())));
```

Keep the author result's status, attempts, and score assertions so the test proves that only the answer is redacted.

- [ ] **Step 2: Add service tests for terminal and non-terminal viewer results**

Add one `getFeed` test with a viewer result in `COMPLETED` and one with a viewer result in `IN_PROGRESS`. Both tests must use the same challenge ID as the shared result and assert respectively:

```java
assertThat(item.dailyGameResult().answer()).isNotNull();
```

and:

```java
assertThat(item.dailyGameResult().answer()).isNull();
```

Use a fixture helper that accepts the result owner so the existing followed-user result and authenticated viewer result cannot be confused. Add a `FAILED` terminal assertion if the existing completed case does not already cover both terminal statuses.

- [ ] **Step 3: Update the existing integration contract test**

In `FeedControllerIntegrationTest`, change the current shared daily-game feed test so the viewer has no terminal result and expects:

```java
.andExpect(jsonPath("$.content[0].dailyGameResult.answer").value(nullValue()))
```

Add a second integration case that creates a terminal `UserDailyGameResult` for the viewer on the same `DailyChallenge` and asserts the answer title is serialized. Keep the accepted-follower visibility assertion and add a non-follower or pending-follower assertion only if the existing test does not already cover the author visibility boundary.

- [ ] **Step 4: Run the focused tests and verify the expected red failure**

Run:

```powershell
.\\mvnw.cmd test "-Dtest=FeedServiceImplTest,FeedControllerIntegrationTest"
```

Expected result: the new service assertions fail because `FeedServiceImpl.toDailyGameFeedItem` currently always maps the source challenge answer, while unrelated feed tests continue to compile and run.

### Task 2: Implement batched viewer-specific answer redaction

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImpl.java`

**Interfaces:**
- Consumes: Selected `UserDailyGameResult` candidates and `viewerId`.
- Produces: `FeedItemDTO`/ `FeedItemViewDTO` daily-game previews whose answer is conditional on the viewer's terminal result.

- [ ] **Step 1: Add a helper that derives terminal viewer challenge IDs**

Add a private helper with this behavior:

```java
private Set<UUID> loadViewerTerminalDailyChallengeIds(
        UUID viewerId, Collection<UserDailyGameResult> dailyGameEntries) {
    List<UUID> challengeIds = dailyGameEntries.stream()
            .map(result -> result.getDailyChallenge().getId())
            .distinct()
            .toList();
    if (challengeIds.isEmpty()) {
        return Set.of();
    }
    return dailyGameResultRepository.findByUserIdAndDailyChallengeIdIn(viewerId, challengeIds).stream()
            .filter(result -> result.getStatus() == DailyGameResultStatus.COMPLETED
                    || result.getStatus() == DailyGameResultStatus.FAILED)
            .map(result -> result.getDailyChallenge().getId())
            .collect(Collectors.toSet());
}
```

Use the existing `Collection`, `Set`, `List`, and `Collectors` imports or add only the imports required by the current file. The helper must return an empty set without querying when the selected page contains no daily-game items.

- [ ] **Step 2: Apply the helper in `getFeed`'s read path**

In `readFeed`, derive the terminal viewer challenge IDs after trimming `dailyGameRaw` to the selected page. Pass the boolean membership decision to the daily-game mapper while preserving the current source ordering, cursor values, and author fields.

- [ ] **Step 3: Apply the helper in `getFeedView`'s read path**

In `enrichSelectedPage`, collect the selected `DAILY_GAME_RESULT` source objects, load the same terminal challenge ID set, and pass it through `toEnrichedFeedItem`. This keeps answer visibility consistent between `getFeed` and `getFeedView`.

- [ ] **Step 4: Make the daily-game mapper conditional**

Change the mapper signature to accept `boolean viewerCanSeeAnswer` and construct the preview with:

```java
viewerCanSeeAnswer ? dailyChallengeResponseAssembler.toAnswer(challenge) : null
```

Do not derive visibility from the author's status alone. The feed query already guarantees that the source result was shared; the new boolean must come from the authenticated viewer's own result.

- [ ] **Step 5: Run the focused tests and verify green**

Run:

```powershell
.\\mvnw.cmd test "-Dtest=FeedServiceImplTest,FeedControllerIntegrationTest"
```

Expected result: all feed service and controller integration tests pass, including redaction for no result and `IN_PROGRESS`, answer visibility for terminal viewer results, and existing accepted-follower filtering.

### Task 3: Synchronize API and business documentation

**Files:**
- Modify: `docs/context/openapi.yaml:7478-7492`
- Modify: `docs/context/business-rules.md` in `## Feed`
- Modify: `docs/context/progress.md` with a new `## 2026-10-10 — ...` entry

**Interfaces:**
- Consumes: The implemented viewer-specific feed behavior from Task 2.
- Produces: Contract and chronological project documentation that no longer promises an unconditional answer to every follower.

- [ ] **Step 1: Update the OpenAPI preview description**

Change `DailyGameResultPreviewDTO` to state that the answer comes from the frozen challenge snapshot and is returned only when the authenticated viewer has a terminal result for that challenge; otherwise `answer` is `null`. Keep the preview terminal-only and TMDB-free.

- [ ] **Step 2: Add the business rule**

Under `## Feed`, add a rule pointing to `FeedServiceImpl` and `UserDailyGameResultRepository.findByUserIdAndDailyChallengeIdIn`: the shared daily-game result remains visible to accepted followers, but the answer is redacted per viewer until that viewer reaches `COMPLETED` or `FAILED` for the same challenge. Mention that the author remains eligible because sharing occurs only after a terminal result.

- [ ] **Step 3: Append the shipped progress entry**

Add a chronological 2026-10-10 entry describing the spoiler fix, the batched viewer-result lookup, and the regression coverage. Do not add a next-steps section or a generic coverage section.

- [ ] **Step 4: Review documentation consistency**

Search for feed descriptions that say the daily-game answer is always exposed and update only the affected wording. Do not change the database schema or the daily-game answer-retention documentation.

### Task 4: Verify, review, and commit the code change

**Files:**
- Review: all files changed by Tasks 1–3

- [ ] **Step 1: Run focused tests again after documentation changes**

```powershell
.\\mvnw.cmd test "-Dtest=FeedServiceImplTest,FeedControllerIntegrationTest"
```

- [ ] **Step 2: Run the full test suite**

```powershell
.\\mvnw.cmd test
```

If repository tests require Docker and Docker is unavailable, report that limitation separately while preserving the focused test result.

- [ ] **Step 3: Inspect the final diff for loopholes**

Confirm that:

- no feed path maps the answer without the viewer-terminal decision;
- `IN_PROGRESS` cannot reveal the answer even when attempt details exist;
- no N+1 viewer-result query was introduced;
- the author still sees their own answer;
- accepted-follower visibility and `sharedAt` publication rules are unchanged;
- the API documentation matches the serialized behavior.

- [ ] **Step 4: Commit only code and test changes**

Leave documentation changes worktree-only as required by the repository instructions. Commit the implementation and tests with:

```powershell
git add src/main/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImpl.java src/test/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImplTest.java src/test/java/com/watchwise/watchwise_api/feed/controller/FeedControllerIntegrationTest.java
git commit -m "fix(feed): redact viewer-ineligible game answers"
```

After committing, verify the commit message has no `Co-Authored-By` trailer or other self-attribution. Do not push without explicit user authorization.

