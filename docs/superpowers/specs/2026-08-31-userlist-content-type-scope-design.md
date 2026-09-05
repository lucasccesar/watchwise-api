# UserList content-type scope (filme/série, temporada, episódio, lista)

## Context

`UserList`/`UserListItem` currently accept any mix of `Content` types in the same list — `MOVIE`,
`SERIES`, `SEASON`, `EPISODE` can coexist freely, and the only existing restriction is the
already-implemented "content vs nested list" lock (`UserListItemServiceImpl.assertListIsNotLockedAsListOfLists`
/ `assertListIsNotLockedAsContentList`): once a list has a `content` item it can't also get a
`childList` item, and vice versa, inferred from whichever type the first item happened to be — no
schema column for it.

Per the user: a list should also be internally consistent by content type, split into four
mutually-exclusive groups:
- movie/série together (a movie list also accepts series, and a series list also accepts movies — same
  group from either angle)
- temporada (season) alone
- episódio (episode) alone
- lista (nested lists) alone — already implemented

The frontend, on the content screen's "add to list" flow, needs to know upfront which group a target
list is locked to, so it can show a warning/disable the action before the user attempts an insert that
would be rejected.

## Scope decisions (confirmed with user)

1. **Inferred from existing items, not a stored column.** Mirrors the existing content-vs-list lock: no
   migration, no new column on `UserList`. The group is derived by looking at whichever `Content` types
   are already present among a list's items.
2. **Exposed via a computed `itemScope` field**, added to both `UserListResponseDTO` and
   `UserListDetailedResponseDTO` (the list-of-lists views and the single-list detail view used by the
   "add item" screen) — computed the same way `nestedListsCount`/`watchedPercentage` already are today,
   not a new endpoint.
3. **Grandfathered mixed lists (created before this rule existed) get a distinct `MIXED` value**, rather
   than misleadingly reporting one arbitrary existing item's group. A list already containing more than
   one group can no longer accept *any* new content item (there is no longer a single valid group to
   compare a candidate against) — this falls out naturally from the validation logic below, no special
   branch needed for enforcement, but the exposed `itemScope` must say `MIXED` so the frontend can
   correctly disable adding anything, instead of showing one group as if it were still open.
   `database-schema.md` is intentionally left untouched by this change — there is nothing to migrate,
   and existing mixed lists are read-only with respect to future content inserts, not modified.

## Content-type groups

Single source of truth, a `ContentType -> UserListItemScope` map:

```java
private static final Map<ContentType, UserListItemScope> CONTENT_TYPE_SCOPES = Map.of(
        ContentType.MOVIE, UserListItemScope.MOVIE_OR_SERIES,
        ContentType.SERIES, UserListItemScope.MOVIE_OR_SERIES,
        ContentType.SEASON, UserListItemScope.SEASON,
        ContentType.EPISODE, UserListItemScope.EPISODE
);

private static final Map<UserListItemScope, Set<ContentType>> SCOPE_CONTENT_TYPES = CONTENT_TYPE_SCOPES
        .entrySet().stream()
        .collect(Collectors.groupingBy(Map.Entry::getValue,
                Collectors.mapping(Map.Entry::getKey, Collectors.toSet())));
```

`SCOPE_CONTENT_TYPES.get(CONTENT_TYPE_SCOPES.get(type))` gives the full set of `ContentType`s allowed
alongside a candidate `type` — used both for validation and for scope resolution.

## New enum

`UserListItemScope` — plain, non-persisted enum (computed only, never stored), placed in
`com.watchwise.watchwise_api.userlist.dto`:

```java
public enum UserListItemScope {
    MOVIE_OR_SERIES, SEASON, EPISODE, LIST, MIXED
}
```

`itemScope == null` on the response DTOs means "list has no items yet, any type is currently allowed" —
`null` is a valid, meaningful state and is not itself an enum value.

## Repository

Two new queries on `UserListItemRepository`, both returning only the distinct `ContentType`s already
present in a list (never touching `childList` rows — that split stays governed by the existing
`existsByUserListIdAndContentIdIsNotNull`/`existsByUserListIdAndChildListIdIsNotNull`):

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

interface UserListContentType {
    UUID getUserListId();
    ContentType getType();
}
```

The single-list detail view (`getUserListById`) does **not** need either query — it already loads every
item via `userListItemService.getItems(viewerId, listId)` before this change; the scope is derived
in-memory from that same list instead of hitting the database again.

## Service layer

**`UserListItemServiceImpl`**

`addItems` (bulk) builds every `UserListItem` in memory and only persists them all at the end
(`saveAll(newItems)`), so a naive per-item DB existence check would miss a conflict **between two items
of the same bulk payload** — neither is in the database yet when the other is checked. The validation
has to track the group as a running, in-memory value seeded from the database, not a fresh DB query per
item:

```java
private UserListItemScope resolveExistingContentScope(UUID listId) {
    return resolveItemScope(userListItemRepository.findDistinctContentTypesByUserListId(listId), false);
}

private void assertContentTypeGroupMatches(UserListItemScope lockedScope, ContentType candidateType) {
    UserListItemScope candidateScope = CONTENT_TYPE_SCOPES.get(candidateType);
    if (lockedScope != null && lockedScope != candidateScope) {
        throw new BadRequestException(
                "This list already contains items of a different content type group and cannot also contain " + candidateType);
    }
}
```

`resolveExistingContentScope` reuses `resolveItemScope` (below) passing `hasNestedLists = false` — safe
because by the time this runs, `assertListIsNotLockedAsListOfLists(listId)` has already confirmed the
list isn't nested-list-locked. Note this also transparently covers the `MIXED` grandfather case: a list
already spanning two groups resolves to `UserListItemScope.MIXED`, which never equals any real
candidate's scope, so every subsequent content insert is correctly rejected without a separate branch.

Usage — **`addItem`** (single), right after `assertListIsNotLockedAsListOfLists(listId)`:

```java
ContentType candidateType = userListItemCreationDTO.content().type();
assertContentTypeGroupMatches(resolveExistingContentScope(listId), candidateType);
```

Usage — **`addItems`** (bulk), once before the loop, then updated as the loop runs so later items in the
same payload are checked against earlier ones in that same payload:

```java
assertListIsNotLockedAsListOfLists(listId);
UserListItemScope lockedScope = resolveExistingContentScope(listId);
for (ContentRefCreationDTO content : userListItemBulkCreationDTO.items()) {
    assertContentTypeGroupMatches(lockedScope, content.type());
    if (lockedScope == null) {
        lockedScope = CONTENT_TYPE_SCOPES.get(content.type());
    }
    // existing getOrCreateReference(...) / builder logic follows, unchanged
}
```

`updateItem` (PATCH) needs no change — `UserListItemPatchDTO` never changes the `content`/`childList`
reference, only `position`/`description`/`customPosterUrl`.

New scope-resolution helper, shared by the validation path above and the `itemScope`-exposition path
below:

```java
private UserListItemScope resolveItemScope(Set<ContentType> distinctTypes, boolean hasNestedLists) {
    if (hasNestedLists) {
        return UserListItemScope.LIST;
    }
    if (distinctTypes.isEmpty()) {
        return null;
    }
    Set<UserListItemScope> groups = distinctTypes.stream().map(CONTENT_TYPE_SCOPES::get).collect(Collectors.toSet());
    return groups.size() > 1 ? UserListItemScope.MIXED : groups.iterator().next();
}
```

New service methods (interface `UserListItemService` + impl), matching the existing
single/batched-pair convention used by `getWatchedPercentage`/`getWatchedPercentagesByListIds`:

```java
UserListItemScope getItemScope(UUID listId);
Map<UUID, UserListItemScope> getItemScopeByListIds(Collection<UUID> listIds);
```

`getItemScopeByListIds` combines `findDistinctContentTypesByUserListIdIn` (grouped by `userListId` in
Java) with the nested-list presence already known from `countNestedListsByListIds` — no separate query
needed for the "has nested lists" flag in the batched path, it's already computed in
`UserListServiceImpl.mapToResponseDtoPage`.

**`UserListServiceImpl`**

- `mapToResponseDtoPage`: add `Map<UUID, UserListItemScope> itemScopeByListId =
  userListItemService.getItemScopeByListIds(listIds);` alongside the other batched lookups, pass
  `itemScopeByListId.get(list.getId())` (no default — absence means `null`/unrestricted) into the mapper
  call.
- `toResponseDto` (used only right after `createUserList`'s `saveAndFlush`, line 379): the list is
  always brand new with zero items at that point, so `itemScope` is always `null` there — pass `null`
  directly, no query needed.
- `getUserListById`: resolve `itemScope` locally from the already-loaded `allItems` list instead of a
  new query — reduce each item to its `content().type()` (skip nested-list items, whose presence instead
  sets `hasNestedLists = true`), then call `resolveItemScope`.
- `createUserListWithItems` (`POST /users/me/lists/bulk`) needs no new validation call: it already
  builds its items by delegating to `userListItemService.addItems(...)` (line 355), so the new check
  inside `addItems` covers this entry point automatically — it is not a separate/duplicated insertion
  path. It does need `itemScope` in its response, though: resolve it the same way as `getUserListById`,
  from the `items` list already returned by that `addItems` call (line 355-364), not a new query.

## DTOs / Mapper

- `UserListResponseDTO`: add `UserListItemScope itemScope`.
- `UserListDetailedResponseDTO`: add `UserListItemScope itemScope`.
- `UserListMapper.userListToResponseDto` / `userListToDetailedResponseDto`: add the new
  `UserListItemScope itemScope` parameter to both method signatures (MapStruct picks it straight through
  to the matching record component, same as every other computed field already passed in).

## Controllers

No new endpoint and no request-shape changes. `itemScope` rides the existing response bodies of every
endpoint that already returns `UserListResponseDTO`/`UserListDetailedResponseDTO`
(`GET /users/{userId}/lists`, `GET /users/me/lists`, `GET /lists/{listId}`, etc).

## Docs to update in the same change

- `openapi.yaml`: add `itemScope` (nullable enum `MOVIE_OR_SERIES | SEASON | EPISODE | LIST | MIXED`) to
  the `UserList` and `UserListDetailed` response schemas.
- `business-rules.md` + `business-rules-summary.md`: document the four content-type groups, the
  inferred-lock mechanism, and the `MIXED` grandfather case, cross-referenced next to the existing
  content-vs-nested-list lock entry.
- `progress.md`: append to the entry for the day this actually gets implemented.
- `database-schema.md`: intentionally **not** touched — no schema change.

## Testing (per CLAUDE.md's baseline checklist)

**`UserListItemServiceImplTest`**
- `addItem`: new cases — inserting `EPISODE` into a list already containing `MOVIE` → `BadRequestException`;
  inserting `SERIES` into a list already containing `MOVIE` → succeeds (same group); inserting `SEASON`
  into a list already containing `EPISODE` → `BadRequestException`; first item of any content type into
  an empty list → succeeds (no prior group to conflict with).
- `addItems` (bulk): same shape as above, applied per-item inside the loop — one case where an early
  item in the batch establishes the group and a later item in the same batch violates it.
- New `getItemScope`/`getItemScopeByListIds` tests: empty list → `null`; single-group list → that group;
  nested-list-only → `LIST`; a list manually seeded with two different content-type groups (simulating a
  grandfathered list) → `MIXED`.

**`UserListServiceImplTest`**
- `getUserListById` / list-page methods: extend existing happy-path assertions to check `itemScope` is
  populated correctly for a representative list.

**Controller integration tests**
- `UserListItemControllerIntegrationTest`: new `400` case — real duplicate-group insert through
  `POST /lists/{listId}/items`, asserting the DB row was not created.
- `UserListControllerIntegrationTest`: extend an existing `GET` happy-path assertion to check the new
  `itemScope` field appears correctly in the real response body; new `400` case for
  `POST /users/me/lists/bulk` (`createUserListWithItems`) when the submitted items themselves mix groups
  (e.g. a `MOVIE` and an `EPISODE` in the same bulk payload), asserting the list ends up with zero items
  rather than a partial insert.
