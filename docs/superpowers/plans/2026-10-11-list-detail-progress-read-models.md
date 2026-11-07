# List Detail and Progress Read Models Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the visual read models required to build List, Episodes List, List of Lists and List Progress without per-item `/contents/details` calls or incomplete `ContentRef` payloads.

**Architecture:** Extract the existing raw list-item reading rules into one deep `UserListItemPageReader` module. Both the existing raw endpoint and the additive visual endpoint will call that module, so visibility, filters, ratings, sorting, status filtering and pagination cannot drift. The visual adapter will reuse `ContentCardAssembler` only after the reader selects its final page; nested lists will receive bounded visual previews with the same visibility rules. The existing progress endpoint will remain separate and will gain type-specific aggregates from data it already loads, while its series-progress enrichment continues to use shared snapshots.

**Tech Stack:** Spring Boot 4.1, Java 21, Spring Data JPA, PostgreSQL native/JPA queries, MapStruct, Maven, Testcontainers, JUnit 5, Mockito, TMDB card metadata resolver and the existing `ContentCardAssembler`.

## Global Constraints

- Do not use `GET /contents/details?ids=...` for list item cards.
- Do not create `Content` references during a read operation.
- Reuse `ContentCardAssembler` and its bounded metadata executor; deduplicate repeated content coordinates before TMDB resolution.
- Keep `GET /lists/{listId}` and `GET /lists/{listId}/progress` backward compatible; new visual detail behavior is additive.
- Apply list visibility, type, genre and `watchStatus` filters before pagination. One shared reader must calculate the visible filtered collection, total and final page for both raw and visual representations.
- `GET /lists/{listId}/progress` remains calculated for the authenticated viewer, not for the list owner.
- A list of lists remains incompatible with the progress endpoint and returns `400`.
- Preserve the current list limits: 30 items for normal list scopes and 24 items for `EPISODE` scope.
- Preserve the current raw sort semantics: `position`, `dateAdded`, `duration`, `episodeAvgRating`, `globalEpisodeAvgRating` and `contentAvgRating`.
- Server-side watch filtering uses `WATCHED`, `PARTIALLY_WATCHED`, `UNWATCHED` or `UNKNOWN`; the UI's fade behavior remains presentation-only.
- `ContentCardDTO.previewStatus` must remain `AVAILABLE`, `PARTIAL` or `UNAVAILABLE`; unavailable TMDB metadata must not remove the list item.
- Preserve privacy for `PUBLIC`, `FOLLOWERS` and `PRIVATE` lists, nested lists, owner ratings and viewer-specific state.
- Every new route and response field must be documented in `docs/context/openapi.yaml` and `docs/telas-reqs-definitivo.md`.
- Documentation and plan files remain worktree-only unless the user explicitly requests a documentation commit.
- Use TDD, run the relevant test red before implementation, run focused tests after each task, and commit each completed task with Conventional Commits without `Co-Authored-By`.

---

## File map

### New files

- `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemPageRequest.java` — normalized request shared by raw and visual item reads.
- `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemPage.java` — internal result with list metadata, filtered item DTOs and page metadata.
- `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListDetailViewResponseDTO.java` — list metadata plus the visual item page.
- `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListDetailItemDTO.java` — one content or nested-list item in the visual page.
- `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListNestedDetailDTO.java` — bounded nested-list card with owner and content previews.
- `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListProgressAggregateDTO.java` — type-specific progress totals.
- `src/main/java/com/watchwise/watchwise_api/userlist/service/UserListItemPageReader.java` — shared read seam for raw and visual list item pages.
- `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemPageReaderImpl.java` — extracted visibility, state, filtering, rating, sorting and pagination behavior.
- `src/main/java/com/watchwise/watchwise_api/userlist/service/UserListDetailViewService.java` — visual detail use-case seam.
- `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListDetailViewServiceImpl.java` — access, pagination and assembly orchestration.
- `src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemPageReaderImplTest.java` — shared filter, visibility, ratings and pagination coverage.
- `src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListDetailViewServiceImplTest.java` — request validation and assembly coverage.
- `src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListDetailViewControllerIntegrationTest.java` — end-to-end visual detail coverage.

### Existing files to modify

- `src/main/java/com/watchwise/watchwise_api/userlist/controller/UserListController.java` — add `GET /lists/{listId}/view`.
- `src/main/java/com/watchwise/watchwise_api/userlist/service/UserListService.java` and `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java` — preserve the raw endpoint by delegating its read path to `UserListItemPageReader`; add the progress aggregates.
- `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListProgressResponseDTO.java` — add `movieProgress` and `seriesProgressAggregate`.
- `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java` — compute type-specific progress totals in batch.
- `src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListItemRepository.java` — add bounded child-list content-preview query.
- `src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerIntegrationTest.java` — progress aggregate and existing raw-route regression coverage.
- `src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java` — progress aggregate tests.
- `docs/context/openapi.yaml` — route and schema documentation.
- `docs/context/business-rules.md` — visual detail privacy, preview caps and progress aggregate rules.
- `docs/context/progress.md` — chronological implementation entry after the code lands.
- `docs/telas-reqs-definitivo.md` — requirements for List, Episodes List, List of Lists and List Progress.

---

### Task 1: Define the shared item-page and visual-detail contracts

**Files:**

- Create: `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemPageRequest.java`
- Create: `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemPage.java`
- Create: `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListDetailViewResponseDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListDetailItemDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListNestedDetailDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/userlist/service/UserListItemPageReader.java`
- Create: `src/main/java/com/watchwise/watchwise_api/userlist/service/UserListDetailViewService.java`
- Test: `src/test/java/com/watchwise/watchwise_api/userlist/dto/UserListItemPageRequestTest.java`

**Interfaces:**

- `UserListItemPageRequest` consumes `viewerId`, `listId`, optional `ContentType type`, trimmed `genre`, `sortBy`, `sortDirection`, optional `WatchStatus watchStatus`, `page` and `size`.
- `UserListItemPageReader.read(UserListItemPageRequest request): UserListItemPage` owns visibility, state resolution, filters, rating enrichment, sorting and final pagination.
- `UserListDetailItemDTO` produces `id`, `ContentCardDTO card`, `UserListNestedDetailDTO nestedList`, `position`, `description`, `createdAt`, `updatedAt`, `customPosterUrl`, `ContentStateDTO contentState`, `episodeAverageRating`, `globalEpisodeAverageRating` and `contentAverageRating`. Exactly one of `card` and `nestedList` is non-null.
- `UserListNestedDetailDTO` produces `id`, owner preview, `name`, `description`, `visibility`, `createdAt`, `updatedAt`, `itemsCount`, `likesCount`, `commentsCount`, `itemScope`, and at most five `ContentCardDTO previewCards`.
- `UserListDetailViewResponseDTO` produces the existing list metadata (`id`, `name`, `description`, `visibility`, `watchedPercentage`, `createdAt`, `updatedAt`, `likesCount`, `likedByMe`, `itemsCount`, `commentsCount`, `totalRuntimeMinutes`, `rank`, `itemScope`) plus `PageResponseDTO<UserListDetailItemDTO> items`.

- `UserListDetailViewService.getView(UserListItemPageRequest request): UserListDetailViewResponseDTO` is the visual adapter seam; its implementation is created in Task 3.

- [ ] **Step 1: Write failing DTO contract tests**

Test immutable collections, genre normalization, valid `WatchStatus` values, page defaults, and the invariant that a visual item cannot contain both `card` and `nestedList`.

- [ ] **Step 2: Run the tests and verify the intended failures**

Run:

```powershell
.\mvnw.cmd test "-Dtest=UserListItemPageRequestTest"
```

Expected: compilation failure because the shared request/page records and reader interfaces do not exist.

- [ ] **Step 3: Add the records and validation seam**

Implement records with immutable collections and add both single-method interfaces. Normalize `genre` with trim, map `sortDirection` to Spring `Sort.Direction`, and apply page defaults. The reader, after it knows the resolved list scope, enforces 30 for normal scopes and 24 for `EPISODE`; DTO constructors never resolve TMDB data.

- [ ] **Step 4: Run the focused tests**

Run the same command and verify all contract tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/watchwise/watchwise_api/userlist/dto src/main/java/com/watchwise/watchwise_api/userlist/service/UserListItemPageReader.java src/main/java/com/watchwise/watchwise_api/userlist/service/UserListDetailViewService.java src/test/java/com/watchwise/watchwise_api/userlist/dto/UserListItemPageRequestTest.java
git commit -m "feat(userlist): define shared list item page contracts"
```

---

### Task 2: Extract the shared item-page reader from the raw endpoint

**Files:**

- Create: `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemPageReaderImpl.java`
- Create: `src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemPageReaderImplTest.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java`

**Interfaces:**

- `UserListItemPageReader.read(UserListItemPageRequest request): UserListItemPage` is the only read seam used by both list representations.
- The reader applies existing list visibility, type, inherited genre rules, viewer state, rating enrichment, sorting, watch-status filtering and final pagination once.
- `UserListServiceImpl.getUserListById(...)` builds a request with `watchStatus = null` and maps the shared page to the existing raw DTO shape.

- [ ] **Step 1: Write failing reader tests**

Cover:

1. a normal list returns MOVIE/SERIES content items in position order;
2. `type=EPISODE` returns only episodes and uses the 24-item maximum;
3. `genre` matches MOVIE/SERIES directly and SEASON/EPISODE through the persisted SERIES parent;
4. `WATCHED`, `PARTIALLY_WATCHED`, `UNWATCHED` and `UNKNOWN` filter before final pagination;
5. `dateAdded`, `duration`, `episodeAvgRating`, `globalEpisodeAvgRating` and `contentAvgRating` preserve existing sort rules;
6. a list-of-lists returns child-list rows and does not join recursively into descendants;
7. private/follower-only lists are rejected before item state or card metadata is resolved;
8. list visibility is enforced for owner, public viewer, accepted follower and unauthorized viewer;
9. page boundaries remain correct when filters exclude rows before pagination.

- [ ] **Step 2: Run reader tests red**

```powershell
.\mvnw.cmd test "-Dtest=UserListItemPageReaderImplTest"
```

Expected: compilation failure because the reader implementation does not exist.

- [ ] **Step 3: Extract the existing read behavior**

Move the current raw getUserListById read path into UserListItemPageReaderImpl: list visibility, one getItemsWithState call, rating enrichment, sorting and pagination. Resolve SEASON/EPISODE genre filters with one batched ContentRepository lookup of their persisted SERIES parents, then apply watchStatus to UserListItemResponseDTO.contentState before sorting and final pagination. Omit inaccessible nested-list rows before visual pagination and reject watchStatus for a LIST scope. UserListServiceImpl must call this reader with watchStatus null, preserving the raw response shape and all current behavior except the documented inherited-genre correction. This keeps one implementation of rules that already depend on batched viewer state instead of adding duplicated SQL.

- [ ] **Step 4: Run reader and raw-route regression tests**

```powershell
.\mvnw.cmd test "-Dtest=UserListItemPageReaderImplTest,UserListServiceImplTest"
```

Expected: reader and raw-route tests pass with unchanged legacy behavior.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/watchwise/watchwise_api/userlist/service src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemPageReaderImplTest.java src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java
git commit -m "refactor(userlist): share item page reading"
```

---

### Task 3: Assemble content and nested-list cards in batches

**Files:**

- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListItemRepository.java`
- Create: `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListDetailViewServiceImpl.java`
- Reuse: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentCardAssembler.java` without changing its public interface
- Create: `src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListDetailViewServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListDetailViewControllerIntegrationTest.java`

**Interfaces:**

- `UserListDetailViewService.getView(UserListItemPageRequest request): UserListDetailViewResponseDTO`.
- The service consumes the item page from Task 2 and produces the complete visual response.

- [ ] **Step 1: Write failing assembler/service tests**

Assert that repeated content coordinates are sent to `ContentCardAssembler` once, cards retain item order, `PARTIAL` and `UNAVAILABLE` cards remain in the response, viewer state and rating fields are preserved, nested-list previews are capped at five visible children, and `watchStatus` filtering is applied before the final page is cut.

- [ ] **Step 2: Run tests red**

```powershell
.\mvnw.cmd test "-Dtest=UserListDetailViewServiceImplTest"
```

Expected: failures because the visual service and assembly path do not exist.

- [ ] **Step 3: Implement the batch assembly**

Build one `ContentCardSpec` per distinct content coordinate and call `ContentCardAssembler` with `BASIC_METADATA`, `STATS`, `VIEWER_STATE` and `SOCIAL_METADATA`. Use the viewer as `ContentCardContext.viewerId()` and the list owner as the poster-owner context when a custom poster is allowed. Preserve the original item order when mapping cards back.

UserListItemPageReader already filters `watchStatus` before returning its final page. Assemble cards only for that final page, so discarded items never trigger TMDB metadata lookup.

For nested lists, query only the first five visible child content items per child list, deduplicate their coordinates across all child previews, assemble cards in one batch, and preserve each child list's original position. Never recursively load a child of a child.

- [ ] **Step 4: Run service tests green**

```powershell
.\mvnw.cmd test "-Dtest=UserListDetailViewServiceImplTest,UserListViewAssemblerTest,ContentCardAssemblerTest"
```

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListItemRepository.java src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListDetailViewServiceImpl.java src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListDetailViewServiceImplTest.java
git commit -m "feat(userlist): assemble visual list detail cards"
```

---

### Task 4: Add the visual detail endpoint and HTTP contract

**Files:**

- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/controller/UserListController.java`
- Create: `src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListDetailViewControllerIntegrationTest.java`
- Modify: `docs/context/openapi.yaml`

**Interfaces:**

```http
GET /lists/{listId}/view?type=&genre=&watchStatus=&sortBy=&sortDirection=&page=&size=
```

The endpoint returns `UserListDetailViewResponseDTO`. It is additive; `GET /lists/{listId}` keeps returning `UserListDetailedResponseDTO`.

- [ ] **Step 1: Write failing HTTP tests**

Cover:

- a normal list returns visual content cards with title/poster and pagination metadata;
- `type=EPISODE` returns at most 24 items and includes episode title, TMDB still exposed through `ContentCard.posterPath`, and air date;
- a list-of-lists returns nested-list cards with bounded content previews;
- `watchStatus=WATCHED`, `PARTIALLY_WATCHED`, `UNWATCHED` and `UNKNOWN` filter before pagination;
- visible public/follower/private behavior matches `GET /lists/{listId}`;
- comments and likes remain available through their existing routes;
- an unavailable TMDB lookup returns an item with `previewStatus=UNAVAILABLE`, not a missing item;
- anonymous access, invalid UUID, invalid sort and invalid page values return the project's `ApiError` shape.

- [ ] **Step 2: Run HTTP tests red**

```powershell
.\mvnw.cmd test "-Dtest=UserListDetailViewControllerIntegrationTest"
```

- [ ] **Step 3: Add the controller mapping**

Construct `UserListItemPageRequest` from the authenticated viewer and query parameters, delegate to `UserListDetailViewService`, and return the response without building cards in the controller.

- [ ] **Step 4: Document the route**

Add the route, query parameters, response schemas, 24/30 page limits, visibility errors and `previewStatus` behavior to `docs/context/openapi.yaml`.

- [ ] **Step 5: Run HTTP tests green**

```powershell
.\mvnw.cmd test "-Dtest=UserListDetailViewControllerIntegrationTest"
```

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/watchwise/watchwise_api/userlist/controller/UserListController.java src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListDetailViewControllerIntegrationTest.java
git commit -m "feat(userlist): expose visual list detail endpoint"
```

---

### Task 5: Add type-specific progress aggregates

**Files:**

- Create: `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListProgressAggregateDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListProgressResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerIntegrationTest.java`

**Interfaces:**

Add these response fields without removing existing ones:

```java
UserListProgressAggregateDTO movieProgress;
SeriesInProgressAggregate seriesProgressAggregate;
```

`UserListProgressAggregateDTO` contains `totalItems`, `watchedItems`, `remainingItems`, `totalRuntimeMinutes`, `watchedRuntimeMinutes` and `remainingRuntimeMinutes`.

- [ ] **Step 1: Write failing aggregate tests**

Use a list containing watched and unwatched movies plus multiple series. Assert movie totals and runtimes are calculated for the viewer, series aggregate sums `SeriesInProgressResponseDTO.remainingEpisodeCount` and `remainingRuntimeMinutes`, and list-of-lists still returns `400`.

- [ ] **Step 2: Run tests red**

```powershell
.\mvnw.cmd test "-Dtest=UserListServiceImplTest,UserListControllerIntegrationTest"
```

Expected: compilation or assertion failure because type-specific aggregates do not exist.

- [ ] **Step 3: Implement batched aggregates**

Reuse the contentItems, watchedDirectContentIds and seriesProgressById collections already loaded by getUserListProgress. Sum persisted MOVIE runtimes for movieProgress and derive seriesProgressAggregate from the existing SeriesInProgressResponseDTO values. Do not add a repository query, call TMDB once per movie/episode, or change the viewer-scoped semantics of existing progress fields.

- [ ] **Step 4: Run tests green**

```powershell
.\mvnw.cmd test "-Dtest=UserListServiceImplTest,UserListControllerIntegrationTest"
```

- [ ] **Step 5: Update OpenAPI and commit**

Document the two additive aggregate fields in `UserListProgress`. Then commit:

```powershell
git add src/main/java/com/watchwise/watchwise_api/userlist src/test/java/com/watchwise/watchwise_api/userlist
git commit -m "feat(userlist): add progress aggregates"
```

---

### Task 6: Synchronize screen requirements and business rules

**Files:**

- Modify: `docs/telas-reqs-definitivo.md`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

- [ ] **Step 1: Document List and Episodes List**

State that the visual detail route returns cards, viewer state, ratings, filters and pagination; normal scopes use 30 items and `EPISODE` uses 24. Document that `fade` is client presentation while server-side `watchStatus` filtering is applied before pagination. Correct the raw route documentation and behavior together so SEASON/EPISODE genre filtering inherits from the persisted SERIES parent.

- [ ] **Step 2: Document List of Lists**

State the five-child preview cap, one-level nesting limit, child-list visibility rules and the fact that nested previews are assembled with bounded content cards rather than recursively loading the tree.

- [ ] **Step 3: Document List Progress**

Document existing series/season progress fields and the new movie/series aggregates. Explicitly state that progress is calculated for the viewer, that list-of-lists returns `400`, and that the endpoint does not provide individual movie/episode watch state.

- [ ] **Step 4: Synchronize business rules and progress**

Record the new read-model privacy rule, preview caps, batch-card rule and type-specific progress aggregation in `business-rules.md`; add a chronological entry to `progress.md` containing only behavior actually implemented.

- [ ] **Step 5: Validate documentation**

Run:

```powershell
rg -n "lists/\{listId\}/view|UserListDetailView|UserListProgressAggregate|watchStatus|previewStatus|nestedList" docs/context/openapi.yaml docs/context/business-rules.md docs/telas-reqs-definitivo.md
git diff --check
```

Do not commit documentation-only changes unless explicitly requested.

---

### Task 7: Full verification and independent review

**Files:**

- Verify all Java, migration and test files from Tasks 1–5.
- Verify `docs/context/openapi.yaml`, `business-rules.md`, `progress.md` and `docs/telas-reqs-definitivo.md`.

- [ ] **Step 1: Run the focused list suite**

```powershell
.\mvnw.cmd test "-Dtest=UserListItemPageReaderImplTest,UserListDetailViewServiceImplTest,UserListDetailViewControllerIntegrationTest,UserListServiceImplTest,UserListControllerIntegrationTest,UserListItemControllerIntegrationTest,ContentCardAssemblerTest"
```

Expected: zero failures and zero errors.

- [ ] **Step 2: Run the complete suite**

```powershell
.\mvnw.cmd clean test
```

Expected: `BUILD SUCCESS` and zero failed or errored tests.

- [ ] **Step 3: Review the final implementation independently**

Confirm that:

- the detail endpoint never calls `/contents/details?ids=...`;
- content coordinates are deduplicated before TMDB resolution;
- filters and `watchStatus` apply before pagination;
- count and page predicates match;
- private/follower-only lists and nested previews cannot leak through either query or assembler;
- normal list pages cap at 30 and episode pages cap at 24;
- List of Lists never recursively loads descendants;
- progress aggregates are viewer-scoped and list-of-lists remains rejected;
- raw routes and existing comments/likes/actions remain compatible;
- all new routes and response fields are documented;
- all new tests use the repository's required `@DisplayName` format.

- [ ] **Step 4: Inspect the final diff**

```powershell
git diff --check
git status --short
git diff --stat
```

Confirm that implementation commits contain only code, migrations and tests; documentation remains worktree-only unless explicitly requested.

- [ ] **Step 5: Confirm commit trailers**

```powershell
git log --format='%h %s%n%b' -n 8
```

Confirm no `Co-Authored-By` trailer or self-attribution appears.
