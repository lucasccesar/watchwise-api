# Search Service Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the service-level test coverage required by phase 8, item 6, for the existing `SearchServiceImpl` implementation.

**Architecture:** Keep validation of the minimum query length at the future controller boundary through a validated request-parameter DTO. Extend the existing Mockito-based `SearchServiceImplTest` only for service orchestration, mapping, pagination, visibility delegation, batch enrichment, empty results, and TMDB failure behavior. Preserve the current uncommitted Search implementation and shared `PageRequestFactory` changes.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, AssertJ, Spring Data `PageImpl`/`Pageable`, Maven Wrapper.

## Global Constraints

- `SearchServiceImpl` must not add a duplicate minimum-query-length rule; the future controller validates `q` with `@Valid`, `@NotBlank`, and `@Size(min = 3)`.
- `SearchServiceImpl` must use the authenticated `viewerId` when querying visible lists.
- `LIST` and `USER` searches must not call `TmdbClient`.
- TMDB unavailability must remain `TmdbUnavailableException`; the controller will map it to HTTP 502 in phase 8, item 7.
- Local list previews and nested-list counts must be obtained through the existing batch methods, not one query per list.
- Do not modify unrelated uncommitted files: `mvnw.cmd`, `PageRequestFactory.java`, or `PageRequestFactoryTest.java`.
- Use the Maven wrapper and route its local repository to a writable temporary directory when the default Maven home is not writable.

---

### Task 1: Complete local-search orchestration coverage

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/search/service/impl/SearchServiceImplTest.java`

**Interfaces:**
- Consumes: `SearchServiceImpl.search(UUID, String, SearchType, Integer, Integer)`, `UserRepository.findByUsernameStartingWithIgnoreCase(...)`, `UserListRepository.findVisibleByNameContainingIgnoreCase(...)`, and the two batch methods on `UserListItemService`.
- Produces: Tests proving local list/user searches use trimmed and escaped input, the normalized page size of 20, the viewer ID, safe previews, and no TMDB interaction.

- [ ] **Step 1: Add a test for an empty local page.**

Add this test to `SearchServiceImplTest`:

```java
@Test
@DisplayName("[search] Should Return Empty Local Arrays And Batch Empty Enrichment - When List Page Is Empty")
void shouldReturnEmptyLocalArraysAndBatchEmptyEnrichmentWhenListPageIsEmpty() {
    stubViewer();
    when(userListRepository.findVisibleByNameContainingIgnoreCase(eq(viewerId), eq("Nope"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
    when(userListItemService.getPreviewItemsByListIds(List.of())).thenReturn(Map.of());
    when(userListItemService.countNestedListsByListIds(List.of())).thenReturn(Map.of());

    SearchResultDTO result = service.search(viewerId, " Nope ", SearchType.LIST, 1, 20);

    assertThat(result.contents()).isEmpty();
    assertThat(result.people()).isEmpty();
    assertThat(result.lists()).isEmpty();
    assertThat(result.users()).isEmpty();
    verify(userListItemService).getPreviewItemsByListIds(List.of());
    verify(userListItemService).countNestedListsByListIds(List.of());
    verifyNoInteractions(tmdbClient);
}
```

- [ ] **Step 2: Run the focused test class and verify the new test passes.**

Run:

```powershell
$mavenCache = Join-Path $env:TEMP 'watchwise-m2'
$env:MAVEN_USER_HOME = $mavenCache
.\mvnw.cmd -q -Dtest=SearchServiceImplTest test
```

Expected: the test passes and confirms that an empty list page produces empty arrays while both batch methods receive an empty ID collection.

- [ ] **Step 3: Add a test for missing batch-map entries.**

Add this test:

```java
@Test
@DisplayName("[search] Should Use Empty Enrichment Defaults - When A List Is Missing From Batch Maps")
void shouldUseEmptyEnrichmentDefaultsWhenAListIsMissingFromBatchMaps() {
    stubViewer();
    UUID listId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    User owner = User.builder().id(ownerId).username("owner").build();
    UserList list = UserList.builder().id(listId).user(owner).name("Sci-fi").build();
    when(userListRepository.findVisibleByNameContainingIgnoreCase(eq(viewerId), eq("Sci-fi"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(list)));
    when(userListItemService.getPreviewItemsByListIds(List.of(listId))).thenReturn(Map.of());
    when(userListItemService.countNestedListsByListIds(List.of(listId))).thenReturn(Map.of());

    SearchResultDTO result = service.search(viewerId, "Sci-fi", SearchType.LIST, 1, 20);

    assertThat(result.lists()).containsExactly(new SearchUserListDTO(
            listId,
            new UserPreviewDTO(ownerId, "owner", null, null),
            "Sci-fi",
            List.of(),
            0L));
}
```

- [ ] **Step 4: Run the focused test class again.**

Run the same Maven command. Expected: all existing and new service tests pass. Do not change production code because the current `getOrDefault` behavior already satisfies this case.

### Task 2: Pin local pagination and external/local failure boundaries

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/search/service/impl/SearchServiceImplTest.java`

**Interfaces:**
- Consumes: `PageRequestFactory.build(..., 20)`, `UserListRepository.findVisibleByNameContainingIgnoreCase(...)`, and the four external methods on `TmdbClient`.
- Produces: Tests that pin page normalization, viewer-scoped visibility delegation, all external failure branches, and the absence of TMDB calls for local-only searches.

- [ ] **Step 1: Add a test for the local page cap and viewer scope.**

Add this test:

```java
@Test
@DisplayName("[search] Should Clamp Local Page And Pass Viewer Scope - When Searching Lists")
void shouldClampLocalPageAndPassViewerScopeWhenSearchingLists() {
    stubViewer();
    when(userListRepository.findVisibleByNameContainingIgnoreCase(eq(viewerId), eq("Sci-fi"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
    when(userListItemService.getPreviewItemsByListIds(List.of())).thenReturn(Map.of());
    when(userListItemService.countNestedListsByListIds(List.of())).thenReturn(Map.of());

    service.search(viewerId, " Sci-fi ", SearchType.LIST, 2, 99);

    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(userListRepository).findVisibleByNameContainingIgnoreCase(
            eq(viewerId), eq("Sci-fi"), pageableCaptor.capture());
    assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
    assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
    verifyNoInteractions(tmdbClient);
}
```

- [ ] **Step 2: Run the focused test and verify page normalization.**

Run:

```powershell
$mavenCache = Join-Path $env:TEMP 'watchwise-m2'
$env:MAVEN_USER_HOME = $mavenCache
.\mvnw.cmd -q -Dtest=SearchServiceImplTest test
```

Expected: the captured local page is Spring page 1, corresponding to client page 2, and its size is capped at 20.

- [ ] **Step 3: Add explicit unavailable-TMDB tests for the omitted-type branch and the remaining typed branches.**

Keep the existing person failure test and add these tests, using the same `stubViewer()` setup and asserting the same exception message:

```java
@Test
@DisplayName("[search] Should Throw TMDB Unavailable - When Movie Search Is Unavailable")
void shouldThrowTmdbUnavailableWhenMovieSearchIsUnavailable() {
    stubViewer();
    when(tmdbClient.searchMovies("Alien", "pt-BR", 1))
            .thenReturn(new TmdbLookupResult.Unavailable<>());

    assertThatThrownBy(() -> service.search(viewerId, "Alien", SearchType.MOVIE, 1, 20))
            .isInstanceOf(TmdbUnavailableException.class)
            .hasMessage("TMDB is currently unavailable");
}

@Test
@DisplayName("[search] Should Throw TMDB Unavailable - When Series Search Is Unavailable")
void shouldThrowTmdbUnavailableWhenSeriesSearchIsUnavailable() {
    stubViewer();
    when(tmdbClient.searchTv("Alien", "pt-BR", 1))
            .thenReturn(new TmdbLookupResult.Unavailable<>());

    assertThatThrownBy(() -> service.search(viewerId, "Alien", SearchType.SERIES, 1, 20))
            .isInstanceOf(TmdbUnavailableException.class)
            .hasMessage("TMDB is currently unavailable");
}

@Test
@DisplayName("[search] Should Throw TMDB Unavailable - When Multi Search Is Unavailable")
void shouldThrowTmdbUnavailableWhenMultiSearchIsUnavailable() {
    stubViewer();
    when(tmdbClient.searchMulti("Alien", "pt-BR", 1))
            .thenReturn(new TmdbLookupResult.Unavailable<>());

    assertThatThrownBy(() -> service.search(viewerId, "Alien", null, 1, 20))
            .isInstanceOf(TmdbUnavailableException.class)
            .hasMessage("TMDB is currently unavailable");
}
```

The existing typed `PERSON` failure test completes the external type matrix. The existing `LIST` and `USER` tests must continue to verify `verifyNoInteractions(tmdbClient)`.

- [ ] **Step 4: Run the focused test class and verify the complete failure matrix.**

Run the focused Maven command. Expected: all external branches throw `TmdbUnavailableException`, while local-only branches remain independent of TMDB.

### Task 3: Run regression verification and commit only the Search service tests

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/search/service/impl/SearchServiceImplTest.java`

**Interfaces:**
- Consumes: the completed Search service test suite and the existing shared pagination tests.
- Produces: a verified local commit containing only Search service test coverage.

- [ ] **Step 1: Run the Search service and pagination test classes together.**

Run:

```powershell
$mavenCache = Join-Path $env:TEMP 'watchwise-m2'
$env:MAVEN_USER_HOME = $mavenCache
.\mvnw.cmd -q -Dtest=SearchServiceImplTest,PageRequestFactoryTest test
```

Expected: all selected tests pass. Existing Mockito/JDK warnings may appear, but no test failure or compilation error is acceptable.

- [ ] **Step 2: Run the full Maven test suite when Docker and dependencies are available.**

Run:

```powershell
$mavenCache = Join-Path $env:TEMP 'watchwise-m2'
$env:MAVEN_USER_HOME = $mavenCache
.\mvnw.cmd -q test
```

Expected: the full suite passes. If repository tests cannot start because Docker is unavailable, report that environmental limitation separately from unit-test results.

- [ ] **Step 3: Review the diff and confirm unrelated work remains untouched.**

Run:

```powershell
git diff -- src/test/java/com/watchwise/watchwise_api/search/service/impl/SearchServiceImplTest.java
git status --short
```

Confirm that the implementation changes already present in `mvnw.cmd`, `PageRequestFactory.java`, `PageRequestFactoryTest.java`, and the Search service files are not staged by this task.

- [ ] **Step 4: Commit only the Search service test changes.**

Run:

```powershell
git add -- src/test/java/com/watchwise/watchwise_api/search/service/impl/SearchServiceImplTest.java
git commit -m "test(search): cover service orchestration"
```

Expected: one local Conventional Commit with no `Co-Authored-By` trailer and no unrelated files staged. Do not push.

