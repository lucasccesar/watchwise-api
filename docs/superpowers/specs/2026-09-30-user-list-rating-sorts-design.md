# User List Rating Sorts Design

## Goal

Expand `GET /lists/{listId}` so list items can be ordered by episode ratings or by the ratings given directly to the item, while exposing the applicable averages in the response without leaking private-profile ratings.

## Current behavior

`sortBy=episodeAvgRating` already calculates the average of the list owner's scored episode diary entries for each series, season, or episode item. The calculation is batched by series and is used only by the in-memory comparator; the response does not expose the calculated value.

## API contract

`GET /lists/{listId}` accepts these additional sort values:

- `globalEpisodeAvgRating`: average scored episode ratings from users with public profiles, grouped by the list item scope.
- `contentAvgRating`: average scores given directly to the exact content item by users with public profiles.

The existing `episodeAvgRating` remains owner-scoped. It is available only when the viewer is the list owner, has a public owner profile, or follows the owner with `ACCEPTED` status. A request using this sort for an unauthorized viewer returns `403`, because the sort order itself could reveal private ratings.

Each content item in `UserListDetailedResponseDTO.items` exposes nullable values:

- `episodeAverageRating`: the owner's episode average when the viewer is authorized to see the owner's profile data.
- `globalEpisodeAverageRating`: the public-profile episode average.
- `contentAverageRating`: the public-profile average of direct ratings for the exact content reference.

All three fields are `null` when the aggregation does not apply to the item or has no scored entries. Nested-list items always return `null` for these fields. Direct content averages apply to `MOVIE`, `SERIES`, `SEASON`, and `EPISODE` references; episode averages apply to `SERIES`, `SEASON`, and `EPISODE` references.

The direction applies only among items with a value. Items without a value remain last for both ascending and descending order. Existing position, date, and duration ordering is unchanged.

## Aggregation and privacy

No new database columns or entities are required. The service enriches the mapped item DTOs with batched aggregate maps before applying the requested item sort.

- Owner episode averages reuse the current owner-scoped episode query and grouping rules, including separate series, season, and exact-episode keys.
- Global episode averages use one batched database aggregation restricted to `user.isProfilePublic = true`, returning score sums and counts grouped by series/season/episode; the service derives the same weighted series, season, and exact-episode averages without loading every diary entity.
- Direct content averages reuse a batched content-statistics query restricted to public profiles and keyed by the persisted `Content` UUID.
- `score IS NOT NULL` is required for episode aggregation. Direct content averages use the database average, which ignores null scores.
- Rewatch diary entries remain separate observations, preserving the current owner-scoped behavior.

The list's own visibility remains independent from profile visibility. A public list can still be opened by anyone, but private owner episode values are neither sorted nor returned to an unauthorized viewer. Public aggregate values may be returned whenever the list itself is visible.

## Implementation boundaries

- Add nullable rating components and a DTO enrichment method to `UserListItemResponseDTO`.
- Update `UserListItemMapper` to ignore the calculated response-only fields.
- Extend `UserListServiceImpl` to calculate the three maps in batch, enforce owner-rating authorization, enrich the response items, and support the two new sort values.
- Add repository support for public episode aggregation and reuse the existing public content-stats projection for direct ratings.
- Update `openapi.yaml`, `business-rules.md`, and `progress.md` to describe the new sort values, response fields, and privacy rule.

## Error handling

Invalid sort values continue to return `400`. Invalid sort directions continue to return `400`. An unauthorized request for owner-scoped `episodeAvgRating` returns the existing typed `403` error path; no controller-level error body is introduced.

## Testing

Add focused service tests for:

1. owner episode averages being returned and used for owner sorting;
2. public global episode averages being returned and used for global sorting;
3. direct public content averages for movies, series, seasons, and episodes;
4. null values and null-last ordering for unsupported/unrated items;
5. rejection and response redaction when the owner profile is private and the viewer is unauthorized;
6. accepted followers and the owner retaining access to owner-scoped averages.

Add controller/integration assertions for the new JSON fields, sort values, and `403` privacy behavior. Repository coverage should verify the public-profile predicate on the new episode aggregation query when the repository test environment is available.
