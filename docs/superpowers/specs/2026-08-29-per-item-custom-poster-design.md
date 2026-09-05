# Per-item custom poster (Top5Entry, UserListItem)

## Context

`customPosterUrl` already exists on `DiaryEntry` (nullable string, `@Size(max = 2048) @URL`, column
`custom_poster_url`) — a per-user, per-row cosmetic override of the poster shown for that entry, with
no server-side effect beyond storing and returning the value. `Top5Entry` and `UserListItem` have no
equivalent field today, in any layer (entity, DTO, `openapi.yaml`).

Per the user: custom poster should be choosable whenever a user logs a movie/series (already true),
puts one in their Top 5, or puts one in a list. This spec extends the existing `DiaryEntry` pattern to
those two remaining entities.

## Scope decisions (confirmed with user)

1. **Top5Entry gets a new PATCH endpoint.** Top5Entry today only supports POST (insert) and DELETE
   (remove) — no update, no "move" operation. Rather than forcing remove+reinsert to change a poster,
   add `PATCH /users/me/top5/{type}/{top5EntryId}` whose only mutable field is `customPosterUrl`.
2. **UserListItem: content items only.** A `UserListItem` is either a `content` (movie/series) or a
   `childList` (nested list) — never both. A nested list has no poster of its own, so `customPosterUrl`
   is rejected with 400 when provided alongside `childListId` (create) or against an item whose type is
   `childList` (patch) — the same shape as the existing `watchedInTheater`-is-MOVIE-only guard.
3. **Bulk endpoints stay untouched.** `POST /lists/{listId}/items/bulk` and `POST /users/me/lists/bulk`
   don't accept `customPosterUrl` per item — consistent with `description` already being absent from
   both today. To set a poster on a bulk-inserted item, `PATCH /lists/{listId}/items/{itemId}`
   afterward.

## Data model

Migration `V36__add-custom-poster-url-to-top5-entries-and-user-list-items.sql`:

```sql
ALTER TABLE top5_entries ADD COLUMN custom_poster_url VARCHAR(2048);
ALTER TABLE user_list_items ADD COLUMN custom_poster_url VARCHAR(2048);
```

No CHECK constraint for the content-only restriction on `user_list_items` — enforced at the service
layer only, matching how `watchedInTheater`'s MOVIE-only rule works on `DiaryEntry`.

- `Top5Entry` entity: new `@Column(name = "custom_poster_url", length = 2048) @Setter private String customPosterUrl;`
- `UserListItem` entity: same field/column, same annotations.

## DTOs

- `Top5EntryCreationDTO`: add `@Size(max = 2048) @URL String customPosterUrl` (nullable).
- New `Top5EntryPatchDTO(@Size(max = 2048) @URL String customPosterUrl)`.
- `Top5EntryResponseDTO`: add `customPosterUrl`.
- `UserListItemCreationDTO`: add `@Size(max = 2048) @URL String customPosterUrl` (nullable).
- `UserListItemPatchDTO`: add `@Size(max = 2048) @URL String customPosterUrl` (nullable).
- `UserListItemResponseDTO`: add `customPosterUrl`.

Mappers (`Top5EntryMapper`, `UserListItemMapper`) pick the new field up automatically — same name on
entity and DTO, no explicit mapping needed beyond what MapStruct already infers.

## Service layer

**Top5EntryServiceImpl**
- `insertEntry`: pass `top5EntryCreationDTO.customPosterUrl()` into the builder.
- New `updateEntry(userId, type, top5EntryId, Top5EntryPatchDTO)`: same ownership/type lookup as
  `removeEntry` (404 if not found / not owned / wrong type), no-op-safe (only sets `updatedAt` and the
  field when a non-null value is provided — null means "leave unchanged", matching
  `DiaryEntryUpdateDTO`'s semantics), no unique-constraint interaction since the field isn't part of any
  constraint.

**UserListItemServiceImpl**
- `addItem`: pass `customPosterUrl` into the builder, but only when `content` is set. If
  `customPosterUrl != null && childListId != null`, throw `BadRequestException` before building —
  extend the existing `validateExactlyOneTarget` step (or a sibling check right after it).
- `updateItem`: if `customPosterUrl` provided and `item.getChildList() != null`, throw
  `BadRequestException`. Otherwise treat it like `description` — changed/unchanged comparison, part of
  the existing "any field changed" branch (extends the current `descriptionChanged`/`positionChanged`
  no-op check to a three-way check).

## Controllers

- `Top5EntryController`: new `@PatchMapping("/me/top5/{type}/{top5EntryId}")` mirroring
  `removeEntry`'s path-variable shape, `@Valid @RequestBody Top5EntryPatchDTO`, returns `200` with the
  updated `Top5EntryResponseDTO`.
- `UserListItemController`: no new endpoint — `customPosterUrl` rides the existing
  `addItem`/`updateItem` request bodies.

## Docs to update in the same change

- `openapi.yaml`: `customPosterUrl` property on `Top5Entry`, the inline POST body schema for
  `/users/me/top5/{type}`, a new `Top5EntryPatch` schema, a new `patch` block under
  `/users/me/top5/{type}/{top5EntryId}`; `customPosterUrl` on `UserListItem`, the inline POST/PATCH body
  schemas for `/lists/{listId}/items` and `/lists/{listId}/items/{itemId}`.
- `database-schema.md`: add the column to `TOP5` and `ITEM_LISTA` (PT names in the logical model).
- `business-rules.md` + `business-rules-summary.md`: document the content-only restriction on
  `UserListItem.customPosterUrl` (`UserListItemServiceImpl.addItem`/`updateItem`), cross-referenced the
  same way `watchedInTheater`'s MOVIE-only rule already is.
- `progress.md`: append to the existing 2026-08-29 entry (or a new one if none exists yet for today).

## Testing (per CLAUDE.md's baseline checklist)

**Top5EntryServiceImplTest**
- `insertEntry` happy path already exists — extend/add a case asserting `customPosterUrl` is persisted
  when provided.
- New `updateEntry` tests: happy path (field changes), no-op when null, NotFound (wrong id / wrong
  owner / wrong type — 3 branches matching `removeEntry`'s existing guard tests).

**UserListItemServiceImplTest**
- `addItem`: extend happy path to assert `customPosterUrl` persisted for a content item; new case —
  `customPosterUrl` + `childListId` together → `BadRequestException`.
- `updateItem`: extend the "field changed" cases with `customPosterUrl` (changed / no-op with same
  value); new case — `customPosterUrl` patch against a childList item → `BadRequestException`.

**Controller unit tests**
- `Top5EntryControllerTest`: new happy-path test for the PATCH endpoint (correct status, delegates to
  service).
- `UserListItemControllerTest`: no new test needed — existing addItem/updateItem tests just gain the
  field.

**Integration tests**
- `Top5EntryControllerIntegrationTest`: new PATCH endpoint needs the full mandatory set — happy path,
  404, 401 (no cookie), 403 (missing CSRF), 400 (`customPosterUrl` too long / not a URL).
- `UserListItemControllerIntegrationTest`: extend existing addItem/updateItem happy paths with the
  field; new 400 case for the content-only guard on both create and patch.
