# All Time Stats Editions Design

**Status:** Proposed

## Goal

Make the Month in Review, Year in Review, and All Time Stats screens renderable from stable backend contracts, with All Time Stats supporting separate Movie Edition and TV Edition data without changing the existing combined endpoint.

## Scope

This change covers the summary API data needed by the three screens. It does not build a frontend, remove fields from shared DTOs, or add the unused All Time `topWatchCompanions` block to the design.

## Decisions

### Existing endpoints

Month and Year keep their current contracts and remain scoped by `type`:

- `GET /users/{userId}/summary/month?type=MOVIE|SERIES&month=YYYY-MM`
- `GET /users/{userId}/summary/year?type=MOVIE|SERIES&year=YYYY`

All Time keeps its current combined endpoint and response for compatibility:

- `GET /users/{userId}/summary/all-time` returns the existing combined response.

The screen-specific endpoint is separate:

- `GET /users/{userId}/summary/all-time/edition?type=MOVIE|SERIES`

It returns `AllTimeEditionStatsDTO` directly. The client calls it once when opening each edition or switching tabs. The legacy endpoint is not called by the screen and is not modified.

### Typed All Time response

`AllTimeEditionStatsDTO` is the screen-facing typed projection. It contains:

- `type`;
- `watchedCount` and `minutesWatched`;
- `totalTheaterVisits`, meaningful for Movie Edition and zero for TV Edition;
- `averageMinutesPerMonth`, `averageMinutesPerWeek`, and `averageMinutesPerDay`;
- `watchCountByYear`;
- `watchCountByDecade`;
- `watchCountByCountry`;
- `mostLoggedContent`;
- `genreCounts`;
- `ratingsDistribution`;
- `topRated`;
- `bottomRated`.

`topWatchCompanions` remains in the existing combined response for compatibility, but is not added to the All Time screen.

### Type semantics

- `MOVIE` uses `MOVIE` diary entries for counts, runtime, ratings, rankings, genres, countries, and release decades.
- `SERIES` uses `EPISODE` diary entries for watched counts, runtime, and rating distribution. Series-level `Content` rows identified by `seriesTmdbId` provide the title, poster, genres, countries, and release year for title-level aggregations and cards.
- `SERIES` `topRated` and `bottomRated` remain title-level rankings over `SERIES` diary entries, matching the existing Month and Year contracts. `mostLoggedContent` groups episode logs by parent `seriesTmdbId` and returns the parent series reference with the episode-log count.
- Rewatches follow the existing query semantics of each aggregation. Runtime and watch counts count diary entries; title-level genre, decade, country, and series rankings deduplicate by movie ID or series TMDB ID.
- Typed averages use the first watched date for the requested edition as their denominator, matching the existing combined-average calculation.

### Rating distribution

`ratingsDistribution` is added to the typed All Time projection. It follows the existing summary convention: scores are grouped from 1 through 10, using the latest score for each distinct watched movie or episode coordinate, so rewatches do not duplicate the histogram.

### Display metadata

The backend returns internal content identifiers for all ranked items that the client must enrich with title and poster data. `SeriesWatchTimeDTO` and `LongestWatchedItemDTO` gain the internal `contentId` for the corresponding movie or parent series while retaining their TMDB identifiers and watch-time values. The client can then use the existing `GET /contents/details?ids=...` batch endpoint.

This avoids making the client create references from external TMDB IDs solely to obtain display metadata.

## Data flow

1. The client loads the user header through the user profile endpoint.
2. The client calls Month or Year once per selected edition, or All Time Edition once per selected edition with `type`.
3. The client collects internal content IDs from ranked response items.
4. The client calls the existing content-details batch endpoint with those IDs and the target user's poster scope.
5. The client fills missing chart buckets with zeroes for days, months, years, or scores that have no rows.

## Backend boundaries

The summary service owns visibility and endpoint orchestration. A dedicated `AllTimeStatsReader` module owns typed all-time assembly, while `AllTimeStatsQueryRepository` owns its database projections and deduplication. Existing combined all-time queries remain where they are. Content details remain owned by `ContentDetailsService`; the all-time reader and Month/Year summary mapping only expose enough identifiers for the existing batch enrichment flow.

The implementation must not remove `DiaryEntryResponseDTO` fields or `topWatchCompanions`. Those fields are shared by other consumers and unused fields are harmless for this screen.

## Error and empty-state behavior

- Missing or invalid `type` values on the All Time Edition endpoint are rejected with the same `BadRequestException` convention used by Month and Year.
- Existing privacy and user-not-found checks remain unchanged.
- Typed responses return empty lists and zero totals when the user has no entries for that edition.
- Missing TMDB-derived metadata does not fabricate titles, posters, countries, or decades; the client renders the established empty or unavailable state.

## Testing strategy

- Unit-test repository projections for movie and series filters, parent-series resolution, title deduplication, country/decade aggregation, typed averages, ratings distribution, and ranking limits.
- Unit-test `AllTimeStatsReader` for typed Movie and TV responses, theater-visit preservation, and content-ID enrichment.
- Add controller tests for the required All Time Edition `type`, invalid `type`, and unchanged legacy combined endpoint behavior.
- Add integration tests proving the typed JSON shape, privacy rules, empty editions, and batch-enrichment identifiers.
- Update the existing Month and Year assertions where the new content IDs are added to DTOs.

## Documentation updates

Update `docs/context/openapi.yaml`, `docs/context/telas.md`, `docs/context/telas-reqs.md`, `docs/context/tmdb-reqs-by-screen.md`, `docs/context/progress.md`, and `docs/context/business-rules.md` in the same change. The OpenAPI description must document the legacy combined endpoint and the separate typed All Time Edition endpoint.

## Alternatives rejected

- Adding a nullable `edition` field to the existing response would preserve compatibility but mix two semantic contracts, duplicate work, and make the response larger than either screen needs.
- Filtering the combined response in the client cannot reconstruct type-specific decades, countries, averages, ratings, or rankings once the backend has merged them.
- Removing unused response fields would create compatibility risk without helping the screen.
