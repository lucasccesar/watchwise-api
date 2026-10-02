# Feed Top 5 Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Include the followed user's current Top 5 in `TOP5_UPDATE` feed items.

**Architecture:** Extend `FeedItemDTO` with a nullable `top5` list that is populated only for `TOP5_UPDATE`. Resolve current entries in one batch query for the followed authors and types already selected by the feed, then map custom posters in one batch. Keep the feed event timestamp and cursor semantics based on the underlying `Top5Entry` row.

**Tech Stack:** Spring Boot, Java 21, Java records, JUnit 5, Mockito, Maven.

## Global Constraints

- The current Top 5 is a read-time preview; it is not a historical snapshot of the change.
- Non-`TOP5_UPDATE` feed items keep `top5 = null`.
- Multiple Top 5 events for the same author/type in one response must reuse one preview lookup.
- Update `openapi.yaml`, `business-rules.md`, `telas.md`, and `progress.md` with the implemented behavior; documentation remains uncommitted by repository convention.

---

### Task 1: Feed DTO and service behavior

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/feed/dto/FeedItemDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImplTest.java`

**Interfaces:**
- Consumes: `Top5EntryRepository.findCurrentPreviewsByUserIdsAndTypes(Collection<UUID>, Collection<ContentType>)` and `UserContentPosterService.findByUserAndContentPairs(Collection<UserContentPosterKey>)`.
- Produces: `FeedItemDTO.top5()` containing `List<Top5EntryResponseDTO>` only for `TOP5_UPDATE`.

- [ ] Write a failing test that stubs a current Top 5 preview and asserts that a `TOP5_UPDATE` item carries it.
- [ ] Run `mvnw.cmd test "-Dtest=FeedServiceImplTest"` and confirm the test fails because `FeedItemDTO` has no Top 5 preview field.
- [ ] Add the nullable `top5` field and wire a per-response map keyed by author UUID plus content type; load current entries and custom posters in batches.
- [ ] Update existing record-construction tests and assert non-Top-5 events keep the field null.
- [ ] Run `mvnw.cmd test "-Dtest=FeedServiceImplTest"` and confirm it passes.

### Task 2: Contract and domain documentation

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/telas.md`
- Modify: `docs/context/progress.md`

- [ ] Document `FeedItem.top5` as the current Top 5 preview, populated only for `TOP5_UPDATE`.
- [ ] Document deduplicated preview loading and the fact that older feed events can show the current list rather than a historical state.
- [ ] Append the shipped behavior to the current chronological progress entry.

### Task 3: Verification

- [ ] Run the focused feed service and controller tests.
- [ ] Run the full Maven test suite if the focused suite passes and the environment permits it.
- [ ] Inspect the diff for accidental changes and verify the existing `.gitignore` modification remains untouched.
