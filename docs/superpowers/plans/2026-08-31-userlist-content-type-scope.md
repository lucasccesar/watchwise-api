# UserList Content-Type Scope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restrict a `UserList` to one content-type group per list (movie/série together, temporada alone, episódio alone, nested lists alone — already implemented), inferred from existing items with no schema change, and expose the current lock as a computed `itemScope` field so the frontend can warn before an insert that would be rejected.

**Architecture:** All new logic lives in the existing `userlist` feature package. No migration, no new column, no new endpoint. A `ContentType -> UserListItemScope` map is the single source of truth for both (a) rejecting an insert whose type doesn't match the list's already-established group and (b) computing the `itemScope` value exposed on list response DTOs. `addItems` (bulk) tracks the locked group as a running in-memory value seeded from the database, since it builds every row in memory before a single `saveAll` — a naive per-item DB check would miss a conflict between two items of the same bulk payload.

**Tech Stack:** Spring Boot 4.1 / Java 21, Spring Data JPA, MapStruct, JUnit 5 + Mockito (service unit tests), `@DataJpaTest` + Testcontainers (repository tests), `@SpringBootTest` + MockMvc + Testcontainers (controller integration tests).

## Global Constraints

- No code comments (self-explanatory names/small methods only) — see `CLAUDE.md`.
- Test method names: `should<ExpectedBehavior>When<Condition>` camelCase, no underscores.
- Test `@DisplayName` format: `"[methodUnderTest] Should <Behavior> - When <Condition>"` (Title Case, exception/DTO type names keep real casing).
- Conventional Commits (`type(scope): description`), one line, no `Co-Authored-By`, no "e.g./such as/for example/like" in the message.
- `docs/context/openapi.yaml`, `docs/context/business-rules.md`, `docs/context/business-rules-summary.md`, `docs/context/progress.md` all need updating in this same change per `CLAUDE.md` — this is Task 6.
- `docs/context/database-schema.md` is intentionally **not** touched — no schema change in this feature.
- Spec this plan implements: `docs/superpowers/specs/2026-08-31-userlist-content-type-scope-design.md`.

---

## Task 1: `UserListItemScope` enum with shared resolution logic

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemScope.java`
- Test: `src/test/java/com/watchwise/watchwise_api/userlist/dto/UserListItemScopeTest.java`

**Interfaces:**
- Produces:
  - `enum UserListItemScope { MOVIE_OR_SERIES, SEASON, EPISODE, LIST, MIXED }`
  - `static UserListItemScope forContentType(ContentType type)`
  - `static UserListItemScope resolve(Set<ContentType> distinctTypes, boolean hasNestedLists)`
  All three consumed by Task 3 (`UserListItemServiceImpl`), Task 4, and Task 5 (`UserListServiceImpl`) — **this is the single source of truth for the `ContentType -> UserListItemScope` mapping and the group-resolution rule (including the `MIXED` grandfather case). Neither `UserListItemServiceImpl` nor `UserListServiceImpl` may re-implement or duplicate this mapping/logic anywhere — every call site in both services calls these two static methods.**

`resolve` has real branching logic (three return paths plus the `MIXED` case), so it gets a real unit test here rather than being trusted to compile — a plain JUnit test with no mocks, since the enum has no collaborators.

- [ ] **Step 1: Write the failing test**

```java
package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserListItemScopeTest {

    @Test
    @DisplayName("[forContentType] Should Map Movie And Series To MovieOrSeries - When Given Either Type")
    void shouldMapMovieAndSeriesToMovieOrSeriesWhenGivenEitherType() {
        assertThat(UserListItemScope.forContentType(ContentType.MOVIE)).isEqualTo(UserListItemScope.MOVIE_OR_SERIES);
        assertThat(UserListItemScope.forContentType(ContentType.SERIES)).isEqualTo(UserListItemScope.MOVIE_OR_SERIES);
    }

    @Test
    @DisplayName("[forContentType] Should Map Season To Season - When Given Season")
    void shouldMapSeasonToSeasonWhenGivenSeason() {
        assertThat(UserListItemScope.forContentType(ContentType.SEASON)).isEqualTo(UserListItemScope.SEASON);
    }

    @Test
    @DisplayName("[forContentType] Should Map Episode To Episode - When Given Episode")
    void shouldMapEpisodeToEpisodeWhenGivenEpisode() {
        assertThat(UserListItemScope.forContentType(ContentType.EPISODE)).isEqualTo(UserListItemScope.EPISODE);
    }

    @Test
    @DisplayName("[resolve] Should Return List - When HasNestedLists Is True Regardless Of Distinct Types")
    void shouldReturnListWhenHasNestedListsIsTrueRegardlessOfDistinctTypes() {
        assertThat(UserListItemScope.resolve(Set.of(ContentType.MOVIE), true)).isEqualTo(UserListItemScope.LIST);
        assertThat(UserListItemScope.resolve(Set.of(), true)).isEqualTo(UserListItemScope.LIST);
    }

    @Test
    @DisplayName("[resolve] Should Return Null - When Distinct Types Is Empty And HasNestedLists Is False")
    void shouldReturnNullWhenDistinctTypesIsEmptyAndHasNestedListsIsFalse() {
        assertThat(UserListItemScope.resolve(Set.of(), false)).isNull();
    }

    @Test
    @DisplayName("[resolve] Should Return The Single Group - When All Distinct Types Share One Group")
    void shouldReturnTheSingleGroupWhenAllDistinctTypesShareOneGroup() {
        assertThat(UserListItemScope.resolve(Set.of(ContentType.MOVIE, ContentType.SERIES), false))
                .isEqualTo(UserListItemScope.MOVIE_OR_SERIES);
    }

    @Test
    @DisplayName("[resolve] Should Return Mixed - When Distinct Types Span More Than One Group")
    void shouldReturnMixedWhenDistinctTypesSpanMoreThanOneGroup() {
        assertThat(UserListItemScope.resolve(Set.of(ContentType.MOVIE, ContentType.EPISODE), false))
                .isEqualTo(UserListItemScope.MIXED);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `mvnw.cmd test -Dtest=UserListItemScopeTest`
Expected: compile error — `UserListItemScope` doesn't exist yet.

- [ ] **Step 3: Create the enum**

```java
package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.util.Set;
import java.util.stream.Collectors;

public enum UserListItemScope {
    MOVIE_OR_SERIES, SEASON, EPISODE, LIST, MIXED;

    public static UserListItemScope forContentType(ContentType type) {
        return switch (type) {
            case MOVIE, SERIES -> MOVIE_OR_SERIES;
            case SEASON -> SEASON;
            case EPISODE -> EPISODE;
        };
    }

    public static UserListItemScope resolve(Set<ContentType> distinctTypes, boolean hasNestedLists) {
        if (hasNestedLists) {
            return LIST;
        }
        if (distinctTypes.isEmpty()) {
            return null;
        }
        Set<UserListItemScope> groups = distinctTypes.stream()
                .map(UserListItemScope::forContentType)
                .collect(Collectors.toSet());
        return groups.size() > 1 ? MIXED : groups.iterator().next();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvnw.cmd test -Dtest=UserListItemScopeTest`
Expected: all seven tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemScope.java src/test/java/com/watchwise/watchwise_api/userlist/dto/UserListItemScopeTest.java
git commit -m "feat(userlist): add UserListItemScope with shared content-type group resolution"
```

---

## Task 2: Repository queries for distinct content types per list

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListItemRepository.java`
- Test: `src/test/java/com/watchwise/watchwise_api/userlist/repository/UserListItemRepositoryTest.java`

**Interfaces:**
- Consumes: nothing new (plain JPQL against the existing `UserListItem`/`Content` mapping).
- Produces:
  - `Set<ContentType> findDistinctContentTypesByUserListId(UUID userListId)`
  - `List<UserListContentType> findDistinctContentTypesByUserListIdIn(Collection<UUID> userListIds)`
  - `interface UserListContentType { UUID getUserListId(); ContentType getType(); }`

This project's repository tests hit a real Postgres via Testcontainers (`@DataJpaTest` + `@Testcontainers`), matching the existing `UserListItemRepositoryTest.java`.

- [ ] **Step 1: Write the failing repository tests**

Add to `UserListItemRepositoryTest.java`, after the existing `findByUserListIdOrderByPositionAsc` tests (following the file's existing `buildList`/`buildContent`/`buildContentItem` helpers already defined at the bottom of the class):

```java
@Test
@DisplayName("[findDistinctContentTypesByUserListId] Should Return Every Distinct Type Present - When List Has Items Of More Than One Type")
void shouldReturnEveryDistinctTypePresentWhenListHasItemsOfMoreThanOneType() {
    Content episode = contentRepository.save(buildEpisodeContent("1396", 1, 1));
    userListItemRepository.save(buildContentItem(scifi, fightClub, 1));
    userListItemRepository.saveAndFlush(buildContentItem(scifi, episode, 2));
    entityManager.clear();

    Set<ContentType> result = userListItemRepository.findDistinctContentTypesByUserListId(scifi.getId());

    assertThat(result).containsExactlyInAnyOrder(ContentType.MOVIE, ContentType.EPISODE);
}

@Test
@DisplayName("[findDistinctContentTypesByUserListId] Should Return Empty Set - When List Has No Content Items")
void shouldReturnEmptySetWhenListHasNoContentItemsForDistinctTypes() {
    Set<ContentType> result = userListItemRepository.findDistinctContentTypesByUserListId(scifi.getId());

    assertThat(result).isEmpty();
}

@Test
@DisplayName("[findDistinctContentTypesByUserListId] Should Ignore ChildList Items - When List Is A List Of Lists")
void shouldIgnoreChildListItemsWhenListIsAListOfLists() {
    userListItemRepository.saveAndFlush(buildChildListItem(scifi, nestedList, 1));
    entityManager.clear();

    Set<ContentType> result = userListItemRepository.findDistinctContentTypesByUserListId(scifi.getId());

    assertThat(result).isEmpty();
}

@Test
@DisplayName("[findDistinctContentTypesByUserListIdIn] Should Group Types By Their Own List - When Multiple Lists Are Requested")
void shouldGroupTypesByTheirOwnListWhenMultipleListsAreRequestedForDistinctTypes() {
    UserList horror = userListRepository.save(buildList(lucas, "Underrated horror"));
    userListItemRepository.save(buildContentItem(scifi, fightClub, 1));
    userListItemRepository.saveAndFlush(buildContentItem(horror, pulpFiction, 1));
    entityManager.clear();

    List<UserListItemRepository.UserListContentType> result = userListItemRepository
            .findDistinctContentTypesByUserListIdIn(List.of(scifi.getId(), horror.getId()));

    assertThat(result).extracting(UserListItemRepository.UserListContentType::getUserListId)
            .containsExactlyInAnyOrder(scifi.getId(), horror.getId());
    assertThat(result).extracting(UserListItemRepository.UserListContentType::getType)
            .containsOnly(ContentType.MOVIE);
}
```

Add this helper next to the existing `buildContent` overloads at the bottom of the class:

```java
private UserListItem buildChildListItem(UserList userList, UserList childList, Integer position) {
    LocalDateTime now = LocalDateTime.now();
    return UserListItem.builder()
            .userList(userList)
            .childList(childList)
            .position(position)
            .createdAt(now)
            .updatedAt(now)
            .build();
}

private Content buildEpisodeContent(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
    LocalDateTime now = LocalDateTime.now();
    return Content.builder()
            .type(ContentType.EPISODE)
            .seriesTmdbId(seriesTmdbId)
            .seasonNumber(seasonNumber)
            .episodeNumber(episodeNumber)
            .createdAt(now)
            .updatedAt(now)
            .build();
}
```

Add `import java.util.Set;` to the file's import list.

- [ ] **Step 2: Run the tests to verify they fail to compile**

Run: `mvnw.cmd test -Dtest=UserListItemRepositoryTest`
Expected: compile error — `findDistinctContentTypesByUserListId`/`findDistinctContentTypesByUserListIdIn`/`UserListContentType` don't exist yet.

- [ ] **Step 3: Add the queries to the repository**

In `UserListItemRepository.java`, add next to the existing `countNestedListsByUserListIdIn` query:

```java
@Query("""
        SELECT DISTINCT uli.content.type FROM UserListItem uli
        WHERE uli.userList.id = :userListId
        AND uli.content.id IS NOT NULL
        """)
Set<ContentType> findDistinctContentTypesByUserListId(@Param("userListId") UUID userListId);

@Query("""
        SELECT DISTINCT uli.userList.id AS userListId, uli.content.type AS type FROM UserListItem uli
        WHERE uli.userList.id IN :userListIds
        AND uli.content.id IS NOT NULL
        """)
List<UserListContentType> findDistinctContentTypesByUserListIdIn(@Param("userListIds") Collection<UUID> userListIds);
```

Add the projection interface next to `UserListCount`/`UserListSum`:

```java
interface UserListContentType {
    UUID getUserListId();
    ContentType getType();
}
```

Add imports: `com.watchwise.watchwise_api.content.entity.ContentType` and `java.util.Set`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvnw.cmd test -Dtest=UserListItemRepositoryTest`
Expected: all tests pass, including the four new ones (requires Docker running for Testcontainers).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListItemRepository.java src/test/java/com/watchwise/watchwise_api/userlist/repository/UserListItemRepositoryTest.java
git commit -m "feat(userlist): add distinct content type queries to UserListItemRepository"
```

---

## Task 3: Enforce the content-type group on `addItem`/`addItems`

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemServiceImplTest.java`

**Interfaces:**
- Consumes: `UserListItemScope.forContentType`/`UserListItemScope.resolve` (Task 1), `UserListItemRepository.findDistinctContentTypesByUserListId` (Task 2).
- Produces: private helpers `resolveExistingContentScope(UUID)`, `assertContentTypeGroupMatches(UserListItemScope, ContentType)` — keep these exact names, Task 4 calls `resolveExistingContentScope`'s sibling pattern.

`addItems` (bulk) builds every `UserListItem` in memory and only calls `saveAll` once at the end, so a per-item database check alone would miss a conflict **between two items of the same bulk payload**. The lock must be tracked as a running in-memory value seeded from the database, updated as the loop runs.

- [ ] **Step 1: Write the failing tests**

Add to `UserListItemServiceImplTest.java`, right after the existing `shouldInsertAtPositionOneWhenListIsEmpty` test:

```java
@Test
@DisplayName("[addItem] Should Throw BadRequestException - When Inserting An Episode Into A List That Already Has A Movie")
void shouldThrowBadRequestExceptionWhenInsertingAnEpisodeIntoAListThatAlreadyHasAMovie() {
    when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
    when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
    when(userListItemRepository.findDistinctContentTypesByUserListId(listId)).thenReturn(Set.of(ContentType.MOVIE));

    assertThatThrownBy(() -> userListItemService.addItem(
            lucasId, listId, new UserListItemCreationDTO(episodeRefCreation("1396", 1, 1), null, null, null)))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("different content type group");

    verifyNoInteractions(contentService);
}

@Test
@DisplayName("[addItem] Should Insert A Series - When List Already Has A Movie")
void shouldInsertASeriesWhenListAlreadyHasAMovie() {
    Content theWire = buildContent("1438", ContentType.SERIES);
    when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
    when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
    when(userListItemRepository.findDistinctContentTypesByUserListId(listId)).thenReturn(Set.of(ContentType.MOVIE));
    stubContentResolution(theWire, ContentType.SERIES);
    when(userListItemRepository.countByUserListId(listId)).thenReturn(1L);
    when(contentRepository.getReferenceById(theWire.getId())).thenReturn(theWire);
    when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

    userListItemService.addItem(lucasId, listId,
            new UserListItemCreationDTO(new ContentRefCreationDTO("1438", ContentType.SERIES, null, null, null, null, null), null, null, null));

    verify(userListItemRepository).save(itemCaptor.capture());
    assertThat(itemCaptor.getValue().getContent()).isEqualTo(theWire);
}

@Test
@DisplayName("[addItem] Should Allow Any Type - When List Has No Content Items Yet")
void shouldAllowAnyTypeWhenListHasNoContentItemsYet() {
    when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
    when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
    when(userListItemRepository.findDistinctContentTypesByUserListId(listId)).thenReturn(Set.of());
    stubContentResolution(fightClub, ContentType.MOVIE);
    when(userListItemRepository.countByUserListId(listId)).thenReturn(0L);
    when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
    when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

    userListItemService.addItem(lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, null, null));

    verify(userListItemRepository).save(itemCaptor.capture());
    assertThat(itemCaptor.getValue().getContent()).isEqualTo(fightClub);
}

@Test
@DisplayName("[addItems] Should Throw BadRequestException - When A Later Item In The Same Payload Violates The Group Established By An Earlier Item")
void shouldThrowBadRequestExceptionWhenALaterItemInTheSamePayloadViolatesTheGroupEstablishedByAnEarlierItem() {
    when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
    when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
    when(userListItemRepository.findDistinctContentTypesByUserListId(listId)).thenReturn(Set.of());
    stubContentResolution(fightClub, ContentType.MOVIE);
    when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);

    assertThatThrownBy(() -> userListItemService.addItems(lucasId, listId,
            new UserListItemBulkCreationDTO(List.of(contentRefCreation("550"), episodeRefCreation("1396", 1, 1)))))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("different content type group");

    verify(contentService, never()).getOrCreateReference(episodeRefCreation("1396", 1, 1));
}
```

The first item (`"550"`, a `MOVIE`) is stubbed to resolve successfully with `stubContentResolution` (this file's existing lenient `any(ContentRefCreationDTO.class)` helper) — without it, the loop would throw a `NullPointerException` while building the first item, never reaching the second item's group check, and the test would pass for the wrong reason.

Add this helper next to `contentRefCreation` at the bottom of the file:

```java
private ContentRefCreationDTO episodeRefCreation(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
    return new ContentRefCreationDTO(null, ContentType.EPISODE, seriesTmdbId, seasonNumber, episodeNumber, null, null);
}
```

Add `import java.util.Set;` to the file's import list.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvnw.cmd test "-Dtest=UserListItemServiceImplTest#shouldThrowBadRequestExceptionWhenInsertingAnEpisodeIntoAListThatAlreadyHasAMovie+shouldInsertASeriesWhenListAlreadyHasAMovie+shouldAllowAnyTypeWhenListHasNoContentItemsYet+shouldThrowBadRequestExceptionWhenALaterItemInTheSamePayloadViolatesTheGroupEstablishedByAnEarlierItem"`
Expected: FAIL — `findDistinctContentTypesByUserListId` stub is unused (`UnnecessaryStubbingException` or a mismatch failure) because the production code doesn't call it yet, and no `BadRequestException` is thrown for the mismatched-type cases.

- [ ] **Step 3: Implement the validation in `UserListItemServiceImpl`**

Add these methods near the top of the class, right after the `POSITION_PARK_OFFSET` constant. **These must call `UserListItemScope.forContentType`/`UserListItemScope.resolve` (Task 1) — do not re-implement the `ContentType -> UserListItemScope` mapping or the group-resolution branching here; that logic has exactly one home, the enum from Task 1.**

```java
private UserListItemScope resolveExistingContentScope(UUID listId) {
    return UserListItemScope.resolve(userListItemRepository.findDistinctContentTypesByUserListId(listId), false);
}

private void assertContentTypeGroupMatches(UserListItemScope lockedScope, ContentType candidateType) {
    UserListItemScope candidateScope = UserListItemScope.forContentType(candidateType);
    if (lockedScope != null && lockedScope != candidateScope) {
        throw new BadRequestException(
                "This list already contains items of a different content type group and cannot also contain " + candidateType);
    }
}
```

Add import: `com.watchwise.watchwise_api.userlist.dto.UserListItemScope`.

In `addItem`, inside the `if (userListItemCreationDTO.content() != null)` branch, right after `assertListIsNotLockedAsListOfLists(listId);`:

```java
if (userListItemCreationDTO.content() != null) {
    assertListIsNotLockedAsListOfLists(listId);
    assertContentTypeGroupMatches(resolveExistingContentScope(listId), userListItemCreationDTO.content().type());
    ContentRefDTO contentRef = contentService.getOrCreateReference(userListItemCreationDTO.content());
    ...
```

In `addItems`, replace:

```java
UserList userList = findOwnedList(userId, listId);
assertListIsNotLockedAsListOfLists(listId);

int position = (int) userListItemRepository.countByUserListId(listId) + 1;
LocalDateTime now = LocalDateTime.now();

List<UserListItem> newItems = new ArrayList<>();
for (ContentRefCreationDTO content : userListItemBulkCreationDTO.items()) {
    ContentRefDTO contentRef = contentService.getOrCreateReference(content);
```

with:

```java
UserList userList = findOwnedList(userId, listId);
assertListIsNotLockedAsListOfLists(listId);
UserListItemScope lockedScope = resolveExistingContentScope(listId);

int position = (int) userListItemRepository.countByUserListId(listId) + 1;
LocalDateTime now = LocalDateTime.now();

List<UserListItem> newItems = new ArrayList<>();
for (ContentRefCreationDTO content : userListItemBulkCreationDTO.items()) {
    assertContentTypeGroupMatches(lockedScope, content.type());
    if (lockedScope == null) {
        lockedScope = UserListItemScope.forContentType(content.type());
    }
    ContentRefDTO contentRef = contentService.getOrCreateReference(content);
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvnw.cmd test -Dtest=UserListItemServiceImplTest`
Expected: all tests pass, including the four new ones and every pre-existing `addItem`/`addItems` test (the pre-existing ones never stubbed `findDistinctContentTypesByUserListId`, so Mockito returns an empty `Set` by default for that unstubbed call — which resolves to `null`/unrestricted, matching their fixtures where the list is otherwise empty).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemServiceImpl.java src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemServiceImplTest.java
git commit -m "feat(userlist): restrict list items to one content type group"
```

---

## Task 4: `getItemScope` / `getItemScopeByListIds` service methods

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/service/UserListItemService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemServiceImplTest.java`

**Interfaces:**
- Consumes: `UserListItemScope.resolve` (Task 1), `UserListItemRepository.findDistinctContentTypesByUserListIdIn` (Task 2), `countNestedListsByUserListIdIn` (already exists on the repository).
- Produces:
  - `UserListItemScope getItemScope(UUID listId)`
  - `Map<UUID, UserListItemScope> getItemScopeByListIds(Collection<UUID> listIds)`
  Both consumed by Task 5.

- [ ] **Step 1: Write the failing tests**

Add to `UserListItemServiceImplTest.java`, in a new `// ---------- getItemScope / getItemScopeByListIds ----------` section right before the `addItem` tests:

`getItemScope` delegates to `getItemScopeByListIds(List.of(listId))`, so its tests stub the plural, `...IdIn` repository methods (with a singleton list argument), never the singular `findDistinctContentTypesByUserListId` — that singular method belongs only to Task 3's `resolveExistingContentScope`, a separate call path used for insert validation, not for scope exposition.

```java
@Test
@DisplayName("[getItemScope] Should Return Null - When List Has No Items")
void shouldReturnNullWhenListHasNoItemsForItemScope() {
    when(userListItemRepository.countNestedListsByUserListIdIn(List.of(listId))).thenReturn(List.of());
    when(userListItemRepository.findDistinctContentTypesByUserListIdIn(List.of(listId))).thenReturn(List.of());

    UserListItemScope result = userListItemService.getItemScope(listId);

    assertThat(result).isNull();
}

@Test
@DisplayName("[getItemScope] Should Return MovieOrSeries - When List Only Has Movies And Series")
void shouldReturnMovieOrSeriesWhenListOnlyHasMoviesAndSeries() {
    when(userListItemRepository.countNestedListsByUserListIdIn(List.of(listId))).thenReturn(List.of());
    when(userListItemRepository.findDistinctContentTypesByUserListIdIn(List.of(listId))).thenReturn(List.of(
            buildUserListContentType(listId, ContentType.MOVIE), buildUserListContentType(listId, ContentType.SERIES)));

    UserListItemScope result = userListItemService.getItemScope(listId);

    assertThat(result).isEqualTo(UserListItemScope.MOVIE_OR_SERIES);
}

@Test
@DisplayName("[getItemScope] Should Return List - When List Only Has Nested Lists")
void shouldReturnListWhenListOnlyHasNestedLists() {
    when(userListItemRepository.countNestedListsByUserListIdIn(List.of(listId)))
            .thenReturn(List.of(buildUserListCount(listId, 1L)));
    when(userListItemRepository.findDistinctContentTypesByUserListIdIn(List.of(listId))).thenReturn(List.of());

    UserListItemScope result = userListItemService.getItemScope(listId);

    assertThat(result).isEqualTo(UserListItemScope.LIST);
}

@Test
@DisplayName("[getItemScope] Should Return Mixed - When List Has Items From More Than One Content Type Group")
void shouldReturnMixedWhenListHasItemsFromMoreThanOneContentTypeGroup() {
    when(userListItemRepository.countNestedListsByUserListIdIn(List.of(listId))).thenReturn(List.of());
    when(userListItemRepository.findDistinctContentTypesByUserListIdIn(List.of(listId))).thenReturn(List.of(
            buildUserListContentType(listId, ContentType.MOVIE), buildUserListContentType(listId, ContentType.EPISODE)));

    UserListItemScope result = userListItemService.getItemScope(listId);

    assertThat(result).isEqualTo(UserListItemScope.MIXED);
}

@Test
@DisplayName("[getItemScopeByListIds] Should Resolve Each List's Scope Independently - When Multiple Lists Are Requested")
void shouldResolveEachListsScopeIndependentlyWhenMultipleListsAreRequested() {
    UUID nestedListId = UUID.randomUUID();
    when(userListItemRepository.countNestedListsByUserListIdIn(List.of(listId, nestedListId)))
            .thenReturn(List.of(buildUserListCount(nestedListId, 1L)));
    when(userListItemRepository.findDistinctContentTypesByUserListIdIn(List.of(listId, nestedListId)))
            .thenReturn(List.of(buildUserListContentType(listId, ContentType.EPISODE)));

    Map<UUID, UserListItemScope> result = userListItemService.getItemScopeByListIds(List.of(listId, nestedListId));

    assertThat(result.get(listId)).isEqualTo(UserListItemScope.EPISODE);
    assertThat(result.get(nestedListId)).isEqualTo(UserListItemScope.LIST);
}

@Test
@DisplayName("[getItemScopeByListIds] Should Return Empty Map - When No List Ids Are Given")
void shouldReturnEmptyMapWhenNoListIdsAreGivenForItemScope() {
    Map<UUID, UserListItemScope> result = userListItemService.getItemScopeByListIds(List.of());

    assertThat(result).isEmpty();
    verifyNoInteractions(userListItemRepository);
}
```

Add this helper next to `buildUserListCount`:

```java
private UserListItemRepository.UserListContentType buildUserListContentType(UUID userListId, ContentType type) {
    return new UserListItemRepository.UserListContentType() {
        @Override
        public UUID getUserListId() {
            return userListId;
        }

        @Override
        public ContentType getType() {
            return type;
        }
    };
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvnw.cmd test -Dtest=UserListItemServiceImplTest`
Expected: FAIL — `getItemScope`/`getItemScopeByListIds` don't exist on `UserListItemService`/`UserListItemServiceImpl` yet (compile error).

- [ ] **Step 3: Add the interface methods**

In `UserListItemService.java`, add next to `getTotalRuntimeMinutesByListIds`:

```java
UserListItemScope getItemScope(UUID listId);

Map<UUID, UserListItemScope> getItemScopeByListIds(Collection<UUID> listIds);
```

Add import: `com.watchwise.watchwise_api.userlist.dto.UserListItemScope`.

- [ ] **Step 4: Implement in `UserListItemServiceImpl`**

Add next to `getTotalRuntimeMinutesByListIds`:

```java
@Override
public UserListItemScope getItemScope(UUID listId) {
    return getItemScopeByListIds(List.of(listId)).get(listId);
}

@Override
public Map<UUID, UserListItemScope> getItemScopeByListIds(Collection<UUID> listIds) {
    if (listIds.isEmpty()) {
        return Map.of();
    }

    Set<UUID> nestedListLockedIds = userListItemRepository.countNestedListsByUserListIdIn(listIds).stream()
            .map(UserListItemRepository.UserListCount::getUserListId)
            .collect(Collectors.toSet());

    Map<UUID, Set<ContentType>> contentTypesByListId = new LinkedHashMap<>();
    for (UserListItemRepository.UserListContentType row : userListItemRepository.findDistinctContentTypesByUserListIdIn(listIds)) {
        contentTypesByListId.computeIfAbsent(row.getUserListId(), id -> new HashSet<>()).add(row.getType());
    }

    Map<UUID, UserListItemScope> scopeByListId = new LinkedHashMap<>();
    for (UUID listId : listIds) {
        boolean hasNestedLists = nestedListLockedIds.contains(listId);
        Set<ContentType> types = contentTypesByListId.getOrDefault(listId, Set.of());
        UserListItemScope scope = UserListItemScope.resolve(types, hasNestedLists);
        if (scope != null) {
            scopeByListId.put(listId, scope);
        }
    }
    return scopeByListId;
}
```

This always queries `findDistinctContentTypesByUserListIdIn` for every requested list, even ones already known to be nested-list-locked from the count query above — deliberately not special-cased to skip it for those ids. A nested-list-locked list has no content rows to return anyway, so the extra ids cost nothing but a few no-op comparisons, and skipping them would only add a branch to save a query that already returns nothing for them.

Add imports: `java.util.HashSet`, `java.util.LinkedHashMap` (likely already imported), `java.util.Set`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvnw.cmd test -Dtest=UserListItemServiceImplTest`
Expected: all tests pass, including the six new ones.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/watchwise/watchwise_api/userlist/service/UserListItemService.java src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemServiceImpl.java src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemServiceImplTest.java
git commit -m "feat(userlist): add getItemScope and getItemScopeByListIds"
```

---

## Task 5: Wire `itemScope` into the response DTOs

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListDetailedResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/mapper/UserListMapper.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java`

**Interfaces:**
- Consumes: `UserListItemService.getItemScope`/`getItemScopeByListIds` (Task 4).
- Produces: `UserListResponseDTO.itemScope()`, `UserListDetailedResponseDTO.itemScope()` — consumed by `openapi.yaml` (Task 6) and by any frontend reading these endpoints.

This task's blast radius is wide: `UserListMapper.userListToResponseDto`/`userListToDetailedResponseDto` are called from many places in `UserListServiceImpl` and stubbed/verified at ~30 call sites across `UserListServiceImplTest.java`, because MapStruct methods here take every computed field as an explicit positional parameter (see the existing `nestedListsCount`/`watchedPercentage`/`likedByMe`/... parameters). Adding one more parameter at the **end** of each method breaks every existing call at compile time — the compiler is what finds every site that needs fixing, so lean on it rather than searching by hand.

- [ ] **Step 1: Add the field to both DTOs**

`UserListResponseDTO.java` — add `UserListItemScope itemScope` as the last record component:

```java
public record UserListResponseDTO(
        UUID id,
        String name,
        String description,
        UserListVisibility visibility,
        Double watchedPercentage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ContentRefDTO> previewItems,
        long nestedListsCount,
        Integer likesCount,
        Boolean likedByMe,
        long itemsCount,
        long commentsCount,
        long totalRuntimeMinutes,
        Integer rank,
        UserListItemScope itemScope
) {
}
```

`UserListDetailedResponseDTO.java` — same, add `UserListItemScope itemScope` as the last record component:

```java
public record UserListDetailedResponseDTO(
        UUID id,
        String name,
        String description,
        UserListVisibility visibility,
        Double watchedPercentage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<UserListItemResponseDTO> items,
        Integer likesCount,
        Boolean likedByMe,
        long itemsCount,
        long commentsCount,
        long totalRuntimeMinutes,
        Integer rank,
        UserListItemScope itemScope
) {
}
```

Add `import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;` to both — actually both DTOs are already in package `com.watchwise.watchwise_api.userlist.dto`, so no import is needed (same package as the enum from Task 1).

- [ ] **Step 2: Update the mapper signatures**

In `UserListMapper.java`, append the new parameter to both methods:

```java
UserListResponseDTO userListToResponseDto(
        UserList userList, List<ContentRefDTO> previewItems, long nestedListsCount, double watchedPercentage, boolean likedByMe,
        long itemsCount, long commentsCount, long totalRuntimeMinutes, UserListItemScope itemScope);

UserListDetailedResponseDTO userListToDetailedResponseDto(
        UserList userList, List<UserListItemResponseDTO> items, double watchedPercentage, boolean likedByMe,
        long itemsCount, long commentsCount, long totalRuntimeMinutes, UserListItemScope itemScope);
```

Add `import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;` (redundant within the same package, but the file already imports sibling DTOs explicitly, e.g. `UserListDetailedResponseDTO` — follow that existing style and add the import anyway).

- [ ] **Step 3: Run compile to see every broken call site**

Run: `mvnw.cmd test-compile`
Expected: FAIL, with a list of every line in `UserListServiceImpl.java` and `UserListServiceImplTest.java` calling `userListToResponseDto(...)`/`userListToDetailedResponseDto(...)` with one argument too few.

- [ ] **Step 4: Fix the production call sites in `UserListServiceImpl.java`**

Four call sites, each gets `itemScope` computed and passed as the new last argument:

In `mapToResponseDtoPage`, add the batched lookup next to the other batched lookups and pass it through:

```java
private Page<UserListResponseDTO> mapToResponseDtoPage(Page<UserList> lists, UUID viewerId) {
    List<UUID> listIds = lists.getContent().stream().map(UserList::getId).toList();
    Map<UUID, List<ContentRefDTO>> previewsByListId = userListItemService.getPreviewItemsByListIds(listIds);
    Map<UUID, Long> nestedListsCountByListId = userListItemService.countNestedListsByListIds(listIds);
    Map<UUID, Double> watchedPercentageByListId = userListItemService.getWatchedPercentagesByListIds(listIds, viewerId);
    Set<UUID> likedListIds = likeService.getLikedListIds(viewerId, listIds);
    Map<UUID, Long> itemsCountByListId = userListItemService.getItemsCountByListIds(listIds);
    Map<UUID, Long> totalRuntimeMinutesByListId = userListItemService.getTotalRuntimeMinutesByListIds(listIds);
    Map<UUID, Long> commentsCountByListId = commentsCountByListIds(listIds);
    Map<UUID, UserListItemScope> itemScopeByListId = userListItemService.getItemScopeByListIds(listIds);

    return lists.map(list -> userListMapper.userListToResponseDto(
            list,
            previewsByListId.getOrDefault(list.getId(), List.of()),
            nestedListsCountByListId.getOrDefault(list.getId(), 0L),
            watchedPercentageByListId.getOrDefault(list.getId(), 0.0),
            likedListIds.contains(list.getId()),
            itemsCountByListId.getOrDefault(list.getId(), 0L),
            commentsCountByListId.getOrDefault(list.getId(), 0L),
            totalRuntimeMinutesByListId.getOrDefault(list.getId(), 0L),
            itemScopeByListId.get(list.getId())));
}
```

In `toResponseDto` (used right after `createUserList`'s `saveAndFlush` — a brand-new list always has zero items at that point):

```java
private UserListResponseDTO toResponseDto(UserList userList, UUID viewerId) {
    List<ContentRefDTO> previewItems = userListItemService.getPreviewItems(userList.getId());
    long nestedListsCount = userListItemService.countNestedLists(userList.getId());
    double watchedPercentage = userListItemService.getWatchedPercentage(userList.getId(), viewerId);
    boolean likedByMe = likeService.getLikedListIds(viewerId, List.of(userList.getId())).contains(userList.getId());
    long itemsCount = userListItemService.getItemsCount(userList.getId());
    long totalRuntimeMinutes = userListItemService.getTotalRuntimeMinutes(userList.getId());
    long commentsCount = commentRepository.countByListId(userList.getId());
    return userListMapper.userListToResponseDto(userList, previewItems, nestedListsCount, watchedPercentage, likedByMe,
            itemsCount, commentsCount, totalRuntimeMinutes, null);
}
```

In `getUserListById`, resolve the scope in-memory from the already-loaded `allItems` instead of a new query:

```java
List<UserListItemResponseDTO> allItems = userListItemService.getItems(viewerId, listId);
List<UserListItemResponseDTO> items = filterAndSortItems(
        allItems, type, genre, sortBy, sortDirection, userList.getUser().getId());
double watchedPercentage = userListItemService.getWatchedPercentage(listId, viewerId);
boolean likedByMe = likeService.getLikedListIds(viewerId, List.of(listId)).contains(listId);
long totalRuntimeMinutes = userListItemService.getTotalRuntimeMinutes(listId);
long commentsCount = commentRepository.countByListId(listId);
UserListItemScope itemScope = resolveItemScopeFromLoadedItems(allItems);

return userListMapper.userListToDetailedResponseDto(userList, items, watchedPercentage, likedByMe,
        allItems.size(), commentsCount, totalRuntimeMinutes, itemScope);
```

Add this private helper next to `filterAndSortItems` — it only adapts `UserListItemResponseDTO`s into the `(Set<ContentType>, boolean)` shape `UserListItemScope.resolve` (Task 1) expects; it must **not** re-derive the group mapping itself:

```java
private UserListItemScope resolveItemScopeFromLoadedItems(List<UserListItemResponseDTO> items) {
    boolean hasNestedLists = items.stream().anyMatch(item -> item.childList() != null);
    Set<ContentType> types = items.stream()
            .filter(item -> item.content() != null)
            .map(item -> item.content().type())
            .collect(Collectors.toSet());
    return UserListItemScope.resolve(types, hasNestedLists);
}
```

In `createUserListWithItems`, resolve the scope from the `items` list already returned by the `addItems` call:

```java
List<UserListItemResponseDTO> items = userListItemService.addItems(
        userId, savedList.getId(), new UserListItemBulkCreationDTO(userListBulkCreationDTO.items()));
double watchedPercentage = userListItemService.getWatchedPercentage(savedList.getId(), userId);
long totalRuntimeMinutes = items.stream()
        .filter(item -> item.content() != null && item.content().runtimeMinutes() != null)
        .mapToLong(item -> item.content().runtimeMinutes())
        .sum();
UserListItemScope itemScope = resolveItemScopeFromLoadedItems(items);

return userListMapper.userListToDetailedResponseDto(savedList, items, watchedPercentage, false,
        items.size(), 0L, totalRuntimeMinutes, itemScope);
```

Add imports to `UserListServiceImpl.java`: `com.watchwise.watchwise_api.userlist.dto.UserListItemScope`, `com.watchwise.watchwise_api.content.entity.ContentType` (verify it isn't already imported — it likely is, given `ContentType type` is already a parameter of `getUserListById`), `java.util.Set` (likely already imported).

- [ ] **Step 5: Run compile again**

Run: `mvnw.cmd test-compile`
Expected: FAIL only on `UserListServiceImplTest.java` now — every remaining error is a stub or verify call missing the new argument.

- [ ] **Step 6: Fix every remaining call site in `UserListServiceImplTest.java`**

Two call shapes appear in this file; fix each occurrence the compiler reports using the matching template:

**Literal-argument stubs/verifies** (all seven/eight prior arguments are concrete values, e.g. `list, List.of(), 0L, 0.0, false, 0L, 0L, 0L`) — append `null` as the new last argument. This is correct, not just compile-satisfying, for every one of these pre-existing fixtures: they all build lists with zero items (`nestedListsCount`/`itemsCount` literal `0L`), so the real `itemScope` for that scenario is genuinely `null`. Example transform:

```java
// before
when(userListMapper.userListToResponseDto(list, List.of(), 0L, 0.0, false, 0L, 0L, 0L)).thenReturn(dto);
// after
when(userListMapper.userListToResponseDto(list, List.of(), 0L, 0.0, false, 0L, 0L, 0L, null)).thenReturn(dto);
```

**Matcher-based stubs/verifies** (using `any()`/`anyLong()`/`anyDouble()`/`anyBoolean()`/`eq(...)` throughout) — append `any()`. Example transform:

```java
// before
when(userListMapper.userListToResponseDto(eq(list), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong()))
        .thenReturn(...);
// after
when(userListMapper.userListToResponseDto(eq(list), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any()))
        .thenReturn(...);
```

Apply the same two templates to every `userListMapper.userListToDetailedResponseDto(...)` call site.

- [ ] **Step 7: Run the full compile and test suite**

Run: `mvnw.cmd test-compile`
Expected: `BUILD SUCCESS` (no more missing-argument errors).

Run: `mvnw.cmd test -Dtest=UserListServiceImplTest`
Expected: mostly PASS. Any failure here means that specific test's fixture is **not** actually an empty list (e.g. it seeds preview items or a non-zero `itemsCount` some other way) and the blanket `null` from Step 6 doesn't match its real `itemScope`. For each such failure: read the test's setup to see what content types/nesting it actually seeds, replace its `null` argument (both in the `when(...)` stub and in any `verify(...)` assertion) with the correct literal `UserListItemScope` value or `any()`/`eq(UserListItemScope.X)`, and re-run until green.

- [ ] **Step 8: Add a test asserting the real value is threaded through**

Add to `UserListServiceImplTest.java`, near the other `getUserListById` tests:

```java
@Test
@DisplayName("[getUserListById] Should Pass The Resolved ItemScope To The Mapper - When List Has Content Items")
void shouldPassTheResolvedItemScopeToTheMapperWhenListHasContentItems() {
    UserList list = buildUserList(listId, lucas, "My list", UserListVisibility.PUBLIC);
    when(userListRepository.findById(listId)).thenReturn(Optional.of(list));
    UserListItemResponseDTO movieItem = new UserListItemResponseDTO(
            UUID.randomUUID(), buildContentRefDto(buildContent("550", ContentType.MOVIE)), null, 1, null,
            LocalDateTime.now(), LocalDateTime.now());
    when(userListItemService.getItems(lucasId, listId)).thenReturn(List.of(movieItem));
    when(userListMapper.userListToDetailedResponseDto(eq(list), anyList(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(),
            eq(UserListItemScope.MOVIE_OR_SERIES))).thenReturn(buildDetailedResponseDto(list, List.of(movieItem)));

    userListService.getUserListById(lucasId, listId, null, null, null, null);

    verify(userListMapper).userListToDetailedResponseDto(eq(list), anyList(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(),
            eq(UserListItemScope.MOVIE_OR_SERIES));
}
```

(Reuse this file's existing `buildUserList`/`buildContent`/`buildContentRefDto`/`buildDetailedResponseDto` helpers — check their exact signatures in this file before writing the test, they mirror the ones already shown in `UserListItemServiceImplTest.java`.)

- [ ] **Step 9: Run the full test class one more time**

Run: `mvnw.cmd test -Dtest=UserListServiceImplTest`
Expected: all tests pass, including the new one from Step 8.

- [ ] **Step 10: Run the entire module's test suite as a final safety net**

Run: `mvnw.cmd test`
Expected: `BUILD SUCCESS` — this also catches any other test file (e.g. `UserListItemMapperTest`, controller tests using mocked services) that might reference the old mapper signature.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListResponseDTO.java src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListDetailedResponseDTO.java src/main/java/com/watchwise/watchwise_api/userlist/mapper/UserListMapper.java src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java
git commit -m "feat(userlist): expose itemScope on UserList response DTOs"
```

---

## Task 6: Docs — `openapi.yaml`, `business-rules.md`, `business-rules-summary.md`, `progress.md`

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/business-rules-summary.md`
- Modify: `docs/context/progress.md`

**Interfaces:** none (documentation only). Not committed to git — `docs/` is gitignored in this project.

- [ ] **Step 1: Add `itemScope` to the OpenAPI schemas**

In `docs/context/openapi.yaml`, find the `UserList` and `UserListDetailed` (or equivalently-named) response schemas and add:

```yaml
itemScope:
  type: string
  nullable: true
  enum: [MOVIE_OR_SERIES, SEASON, EPISODE, LIST, MIXED]
  description: >
    The content-type group this list is currently locked to, inferred from its existing items.
    Null means the list has no items yet and accepts any type. MIXED means the list predates this
    restriction and already mixes more than one group — it can no longer accept new content items.
```

Add this property to both the `UserList` list-page schema and the `UserListDetailed` single-list schema (find both by searching for the existing `nestedListsCount`/`watchedPercentage` properties already in each).

- [ ] **Step 2: Update `business-rules.md`**

Find the existing `UserList`/`UserListItem` section's bullet starting `**Profundidade máxima de um nível e trava de tipo, implementadas em `UserListItemServiceImpl.addItem`**` (search for `Profundidade máxima`). Add a new bullet directly after it:

```markdown
- **Trava de grupo de tipo de conteúdo, no mesmo espírito da trava de conteúdo-vs-lista acima**
  (`UserListItemServiceImpl.assertContentTypeGroupMatches`/`resolveExistingContentScope`, adicionada em
  2026-08-31) — além de travar entre "conteúdo" e "lista aninhada", uma lista de conteúdo também trava
  entre três grupos fechados e sem sobreposição: `{MOVIE, SERIES}` juntos, `SEASON` sozinho, `EPISODE`
  sozinho (`UserListItemScope.forContentType`/`resolve`, única fonte de verdade reutilizada tanto por
  `UserListItemServiceImpl` quanto por `UserListServiceImpl`). O grupo é inferido do primeiro item de conteúdo, sem coluna nova —
  mesma técnica da trava de lista-de-listas. `addItems` (bulk) precisa rastrear esse grupo como um valor
  em memória, não só consultar o banco por item: como o método monta todos os `UserListItem` antes de um
  `saveAll` único, um conflito **entre dois itens do mesmo payload** nunca apareceria numa consulta ao
  banco (nenhum dos dois ainda está persistido). `getItemScope`/`getItemScopeByListIds`
  (`UserListItemService`) expõem o grupo atual como `itemScope` em `UserListResponseDTO`/
  `UserListDetailedResponseDTO`, pro front avisar antes de tentar inserir algo que violaria a trava.
  Uma lista criada antes dessa regra que já misturava grupos (nunca existiu essa restrição antes) resolve
  para `MIXED` em vez de reportar um grupo qualquer arbitrário — e fica, na prática, travada contra
  qualquer novo item de conteúdo, já que nenhum tipo candidato jamais bate com um grupo já misto; não há
  migração retroativa para essas listas.
```

- [ ] **Step 3: Update `business-rules-summary.md`**

Add a one-line pointer to the new rule in whatever summary format the file already uses for the `UserList` section (open the file and match its existing bullet style for this section — it should reference `UserListItemServiceImpl.assertContentTypeGroupMatches` and mention the `MIXED` grandfather case in one sentence).

- [ ] **Step 4: Append to `progress.md`**

Add to (or create, if none exists yet) the entry for the day this task is actually executed, in the same chronological format already used by the file (`## YYYY-MM-DD — ...` heading, prose paragraph describing what was built and why — not a bullet list, matching the file's existing day-entries). Cover: the four content-type groups, the inferred-lock mechanism (no migration), the bulk in-memory-tracking fix, the `itemScope` field and its `MIXED` grandfather value.

- [ ] **Step 5: No commit** — `docs/` is gitignored in this repository; these files are not tracked by git.

---

## Task 7: Integration tests

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListItemControllerIntegrationTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerIntegrationTest.java`

**Interfaces:** none new — exercises the full stack built in Tasks 3–5 through real HTTP requests against a real Postgres.

- [ ] **Step 1: Write the failing integration test for the enforcement rule**

Add to `UserListItemControllerIntegrationTest.java`, near the other `addItem` `400` tests:

```java
@Test
@DisplayName("[addItem] Should Return 400 And Leave The List Unchanged - When Content Type Group Conflicts With An Existing Item")
void shouldReturn400AndLeaveTheListUnchangedWhenContentTypeGroupConflictsWithAnExistingItem() throws Exception {
    RegisteredUser user = registerUser("groupmismatch");
    User entity = userRepository.findById(user.id()).orElseThrow();
    UserList list = persistList(entity, "Movies only", true);
    persistContentItem(list, persistContent("550"), 1);

    String episodeBody = """
            {
                "content": { "type": "EPISODE", "seriesTmdbId": "1396", "seasonNumber": 1, "episodeNumber": 1 }
            }
            """;

    mockMvc.perform(addItemRequest(user, list.getId(), episodeBody))
            .andExpect(status().isBadRequest());

    assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId())).hasSize(1);
}
```

- [ ] **Step 2: Run the test to verify it fails to compile or fails**

Run: `mvnw.cmd test -Dtest=UserListItemControllerIntegrationTest#shouldReturn400AndLeaveTheListUnchangedWhenContentTypeGroupConflictsWithAnExistingItem`
Expected: this should already PASS if Tasks 3–5 landed correctly (this is a regression-confirming test, not a red-first TDD test, since the underlying feature already exists by this point) — if it fails, that's a real bug in Task 3's implementation to go back and fix before continuing.

- [ ] **Step 3: Write the failing integration test for the exposed `itemScope` field**

Add to `UserListControllerIntegrationTest.java`, near the existing `getUserListById` tests:

```java
@Test
@DisplayName("[getUserListById] Should Report The Locked Content Type Group As ItemScope - When List Has A Movie Item")
void shouldReportTheLockedContentTypeGroupAsItemScopeWhenListHasAMovieItem() throws Exception {
    RegisteredUser user = registerUser("itemscopeowner");
    User entity = userRepository.findById(user.id()).orElseThrow();
    UserList list = persistList(entity, "My list", UserListVisibility.PUBLIC);
    persistContentItem(list, "550", 1);

    mockMvc.perform(getUserListByIdRequest(user, list.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.itemScope").value("MOVIE_OR_SERIES"));
}

@Test
@DisplayName("[getUserListById] Should Report Null ItemScope - When List Has No Items Yet")
void shouldReportNullItemScopeWhenListHasNoItemsYet() throws Exception {
    RegisteredUser user = registerUser("itemscopeempty");
    User entity = userRepository.findById(user.id()).orElseThrow();
    UserList list = persistList(entity, "Empty list", UserListVisibility.PUBLIC);

    mockMvc.perform(getUserListByIdRequest(user, list.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.itemScope").doesNotExist());
}
```

(`jsonPath("$.itemScope").doesNotExist()` is the correct assertion for a `null`-valued Jackson field when the project's `ObjectMapper` uses default null-handling — verify this matches how other nullable fields, like `description`, are already asserted absent elsewhere in this file; if this project configures `NON_NULL` inclusion differently, adjust to `jsonPath("$.itemScope").value(org.hamcrest.Matchers.nullValue()))` instead, whichever the existing nullable-field tests in this file use.)

- [ ] **Step 4: Write the failing integration test for bulk-create with mixed groups**

Add near the existing `createUserListWithItems` (`POST /users/me/lists/bulk`) tests in `UserListControllerIntegrationTest.java` (check the file for its existing bulk-create request-builder helper name and reuse it):

```java
@Test
@DisplayName("[createUserListWithItems] Should Return 400 And Create No List - When Submitted Items Span More Than One Content Type Group")
void shouldReturn400AndCreateNoListWhenSubmittedItemsSpanMoreThanOneContentTypeGroup() throws Exception {
    RegisteredUser user = registerUser("bulkgroupmismatch");

    String body = """
            {
                "name": "Mixed bulk list",
                "items": [
                    { "tmdbId": "550", "type": "MOVIE" },
                    { "type": "EPISODE", "seriesTmdbId": "1396", "seasonNumber": 1, "episodeNumber": 1 }
                ]
            }
            """;

    mockMvc.perform(createUserListWithItemsRequest(user, body))
            .andExpect(status().isBadRequest());

    assertThat(userListRepository.findByUserId(user.id(), org.springframework.data.domain.Pageable.unpaged()).getContent())
            .isEmpty();
}
```

(Replace `createUserListWithItemsRequest` with whatever this file's existing request-builder for `POST /users/me/lists/bulk` is actually named — check the file first.)

- [ ] **Step 5: Run all four new/verified tests**

Run: `mvnw.cmd test -Dtest=UserListItemControllerIntegrationTest,UserListControllerIntegrationTest`
Expected: all pass (requires Docker running for Testcontainers).

- [ ] **Step 6: Run the full suite one final time**

Run: `mvnw.cmd test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListItemControllerIntegrationTest.java src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerIntegrationTest.java
git commit -m "test(userlist): cover content-type scope enforcement and itemScope exposure end to end"
```

---

## Plan Self-Review Notes

- **Spec coverage:** Task 1 covers the enum; Task 2 covers the repository layer; Task 3 covers enforcement (including the bulk in-memory-tracking fix the spec called out); Task 4 covers the `itemScope` resolution methods; Task 5 covers wiring into both response DTOs plus all three production call sites (`mapToResponseDtoPage`, `toResponseDto`, `getUserListById`) plus `createUserListWithItems`; Task 6 covers every doc the spec named; Task 7 covers the spec's integration-test list. No spec section is without a task.
- **Dropped from the spec during translation:** the spec's "Content-type groups" section defined a second reverse map, `SCOPE_CONTENT_TYPES` (`UserListItemScope -> Set<ContentType>`), built off a per-service `CONTENT_TYPE_SCOPES` map. Once the bulk in-memory-tracking fix replaced the original `existsByUserListIdAndContentTypeNotIn`-based validation approach, nothing in the corrected design ever calls `SCOPE_CONTENT_TYPES` again. This plan omits the repository method `existsByUserListIdAndContentTypeNotIn` and the `SCOPE_CONTENT_TYPES` map entirely, since implementing dead code would fail its own purpose (nothing would ever call it, so no task could write a passing test against it).
- **Pre-flight change (human-directed, before Task 1 dispatch):** the spec/original plan draft had `UserListServiceImpl` (Task 5) re-implement the `ContentType -> UserListItemScope` mapping as its own private switch-shaped helper, duplicating `UserListItemServiceImpl`'s (Task 3's) `CONTENT_TYPE_SCOPES` map — flagged in the pre-flight conflict scan as something a reviewer would likely mark as DRY-violating duplication. Per the human partner's explicit instruction, this plan instead promotes the mapping and the group-resolution branching to two `static` methods directly on `UserListItemScope` (Task 1): `forContentType(ContentType)` and `resolve(Set<ContentType>, boolean)`. Both `UserListItemServiceImpl` (Task 3, Task 4) and `UserListServiceImpl` (Task 5) call these two methods exclusively — neither service holds its own copy of the mapping or the branching logic anymore. Task 1 gained a real unit test (`UserListItemScopeTest`) as a consequence, since the enum now carries actual branching logic worth verifying independently of any service.
- **Type consistency:** `UserListItemScope` (Task 1) is the type returned by `UserListItemScope.resolve`/`resolveExistingContentScope`/`getItemScope`/`getItemScopeByListIds` (Tasks 3–4) and is the exact type of the new `itemScope` record component and mapper parameter (Task 5) — verified consistent across every task, with the resolution logic itself now living in exactly one place (Task 1) rather than two.
