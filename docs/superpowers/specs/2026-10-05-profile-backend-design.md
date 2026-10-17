# Profile backend read model

## Status

Approved design for implementation. This document describes the backend additions needed to render the Profile screen for the authenticated user and for other visible users.

## Scope

The existing Profile design has two content tabs, Movies and Series. The backend must support:

- the profile header and the viewer's follow relation;
- watch-time and watch-count metrics for the selected tab;
- the tab-specific highlight cards;
- Top 5 cards with the user's score;
- the last four watched episodes on the Series tab;
- recent movie or series activity, including completed and dropped items, with status, score, and watch count;
- distinct genre counts matching the labels shown by the design;
- rating distribution plus total and average rating;
- recent review previews, including movies, series, seasons, and episodes in the Series tab, without review text.

No new persistence entity or migration is required. This is a read-model change over existing `User`, `Follower`, `DiaryEntry`, `DroppedEntry`, `Top5Entry`, and `Content` data.

## Existing seams to extend

Keep the current routes and extend their response contracts:

- `GET /users/{userId}` continues to provide the profile header and receives a viewer-relative follow relation.
- `GET /users/{userId}/summary?type=MOVIE|SERIES` remains the Profile body read model and delegates profile-specific work to an internal `ProfileSummaryReader` module.
- `GET /users/{userId}/top5/{type}` continues to provide Top 5 entries and adds the user's current score for each entry.
- `GET /contents/details?ids=...&posterUserId={userId}` remains responsible for TMDB title, year, image, and other display metadata. The profile response continues to expose `ContentRefDTO` references rather than duplicating TMDB details.

The frontend will call the profile header, the selected summary, the selected Top 5, and one batch content-details request. It must not reconstruct domain aggregates from raw diary pages.

Profile summary assembly is split into three internal stages: a database read module that returns immutable raw aggregates inside a short `@Transactional(readOnly = true)` boundary, a display-metadata resolver that runs after that transaction has ended, and a final assembler that builds the public DTO. No database transaction remains open while TMDB is called.

## Profile header and viewer relation

Extend `PublicUserProfileDTO` with a viewer-relative relation:

```text
SELF | FOLLOWING | NOT_FOLLOWING
```

Rules:

- `SELF` is returned when viewer and target are the same user.
- `FOLLOWING` means an `ACCEPTED` relation from viewer to target.
- `NOT_FOLLOWING` means no outgoing relation.
- The existing profile-visibility check runs before returning any profile data. A private profile remains `403` for a viewer who is neither the owner nor an accepted follower.

An outgoing `PENDING` request is not exposed by this profile response: a private target is not visible to a viewer who is not already an accepted follower, and public targets accept follow requests immediately. Pending requests remain represented by the existing follow-request endpoints.

The relation is viewer-specific and must be computed server-side. The frontend must not infer it from follower counts or from a failed follow action.

## Summary response

Extend `SummaryResponseDTO` with the following fields.

### Watch time and counts

Extend `WatchTimeDTO` with:

```text
totalWatchedCount
watchedCountLast30Days
```

For `type=MOVIE`, counts are `DiaryEntry` rows with `ContentType.MOVIE`. For `type=SERIES`, counts are `DiaryEntry` rows with `ContentType.EPISODE`. Rewatches count as additional watches. The 30-day window uses the same inclusive rolling-date semantics already used by the summary service.

### Highlights

Add a `ProfileHighlightsDTO` containing:

- `rewatch`: `MOST_REWATCHED` for Movies, `MOST_COMPLETE_REWATCHES` for Series;
- `longestWatch`: the longest movie or the series with the greatest accumulated episode runtime.

Each highlight contains a `ProfileHighlightContentDTO`. It may carry an internal `contentId` when one exists, or an external `seriesTmdbId` when the aggregate is a series without a stored `SERIES` reference. It also carries the minimal display metadata needed by the card: title, release year, poster path, and the target owner's custom poster override.

`MOST_REWATCHED` counts every movie diary entry for the content, including rewatches.

`MOST_COMPLETE_REWATCHES` counts completed `SERIES` diary passes by positive `watchNumber`. Manual and auto-generated completed series entries are valid completed passes.

For `longestWatch`:

- Movies use the greatest stored movie runtime.
- Series aggregate episode runtime by `seriesTmdbId`.
- Rewatched episode entries follow the existing all-time watch-time rule and contribute again.
- Ties are deterministic: most recent activity first, then stable content identity.

### Genres

Change the Profile summary's `genreCounts` semantics to count distinct display units and ignore rewatches.

- Movies count distinct movie `Content` references by genre.
- Series count distinct episode coordinates `(seriesTmdbId, seasonNumber, episodeNumber)` by the parent series genre.

The existing `PublicUserProfileDTO` genre fields remain separate legacy profile statistics until a dedicated compatibility decision is made; the Profile screen uses the corrected summary field.

### Ratings

Change `ratingsDistribution` so each display unit contributes at most one score, then add:

```text
ratingsSummary.totalRatings
ratingsSummary.averageScore
```

The distribution and summary use the same basis already used by the current summary: movie content for Movies and episode coordinates for Series. Rewatch rows do not increase the number of ratings. When a unit was rated more than once, use the score from the greatest positive `watchNumber`, matching the existing latest-rewatch rule. Null latest scores are excluded.

### Recent episodes

Keep `recentEpisodes` as an episode-only list for the Series tab. It is limited to four entries and does not require a review. Replace the broad `DiaryEntryResponseDTO` payload with a shared lightweight `ProfileDiaryPreviewDTO` that excludes `comment` and review text while retaining the diary identity, content reference, score, watched date, watch number, custom poster, and companion previews needed by the card. It may overlap with `recentReviews` when an episode has a comment; the sections answer different questions.

### Recent activity

Extend `RecentActivityItemDTO` with:

```text
score
timesWatched
```

Activity remains limited to top-level Movies or Series and includes both completed and dropped items:

- `DiaryEntry` of `MOVIE` or `SERIES` maps to `COMPLETED`;
- `DroppedEntry` of `MOVIE` or `SERIES` maps to `DROPPED`;
- episode and season diary rows do not become recent activity cards.

For a completed activity, `timesWatched` is the number of completed passes for that user and content. Movies count movie diary passes; Series count completed `SERIES` diary passes. The current diary entry's `watchNumber` is the pass that produced the event, while the aggregate count remains authoritative when older passes were removed.

For a dropped activity, `timesWatched` is the number of completed passes recorded before the drop. A dropped item still appears in the activity list. A series dropped after only partial episode progress, with no completed `SERIES` pass, therefore has `timesWatched = 0`; episode progress remains the responsibility of the Progress screen.

`score` is the score on the diary entry that produced the completed activity. Dropped activity has a null score.

The completed and dropped counts must be loaded in batches for the final activity contents. No per-card count query is allowed.

### Recent reviews

Keep the existing `recentReviews` field and change its Profile-summary selection rules. Return the shared lightweight `ProfileDiaryPreviewDTO`, not `DiaryEntryResponseDTO`, so review text is not included in the Profile payload.

- Movies: `MOVIE` entries with a non-blank comment.
- Series: `SERIES`, `SEASON`, and `EPISODE` entries with a non-blank comment.

The preview contains the diary entry identity, content reference, score, watched date, watch number, custom poster, and companion previews, but no `comment` text or social counters. The full review text and comment thread remain available through the existing diary detail/comments endpoints.

The Series list is one mixed list, limited to five total entries and ordered by the diary's recent-activity ordering. Each response keeps `content.type`, `seasonNumber`, and `episodeNumber`, allowing the frontend to render:

- a complete-series review;
- a season review;
- an episode review.

`recentReviews` must no longer be restricted to `EPISODE` merely because Series watch-time and ratings use episode entries. Those are separate aggregation rules. The preview contains no review body or social counters.

For `SEASON` and `EPISODE` details, extend `ContentDetailsDTO` with nullable `parentTitle` and `parentReleaseYear`. `ContentDetailsServiceImpl` already loads the parent TV details while resolving these child types, so this adds no extra TMDB call and lets the frontend render labels such as `Severance · S2E7`.

## Top 5 score

Extend `Top5EntryResponseDTO` with nullable `score`.

For each Top 5 entry, resolve the latest score for the same user, content, and content type using the greatest positive `watchNumber`. A missing score remains null; an older scored pass must not be used as a fallback when the latest pass has no score.

Scores must be resolved in one repository projection for the returned Top 5 content IDs, not with one query per card.

## Repository and service design

`SummaryServiceImpl` remains the public orchestration module for the Profile summary and delegates profile-specific work to `ProfileSummaryReader`/`ProfileSummaryReaderImpl`. The reader consumes `ProfileSummaryDataReader`, which uses a dedicated `ProfileSummaryQueryRepository` adapter for:

- counts by type and date window;
- movie rewatch counts;
- completed-series pass counts;
- longest movie and series watch aggregates;
- distinct movie and episode genre counts;
- distinct-unit latest-rating totals and averages;
- mixed-level recent reviews;
- batch activity watch counts;
- lightweight review previews without review text or social counters.

Profile-wide cross-aggregate SQL does not belong in `DiaryEntryRepository`; `ProfileSummaryQueryRepository` owns those read projections so diary domain writes and profile analytics remain separate modules.

`ProfileDisplayMetadataResolver` is an internal adapter used by the reader for highlight cards. It resolves stored content IDs through the existing content-details service and resolves external series identities through the existing TMDB client, with target-owner custom poster overrides. A transient TMDB failure may omit only the affected highlight card; it must not fabricate title or image data.

`Top5EntryServiceImpl` enriches Top 5 responses with the batch score projection. The mapper remains explicit under the project's MapStruct policy.

The new repository queries must use deterministic ordering and database aggregation. Controllers remain thin and must not perform aggregation or visibility decisions.

## Authorization and failure behavior

- Reuse the existing profile visibility rules for the header, summary, Top 5, diary, and content-detail requests.
- Resolve custom posters using the target profile owner as `posterUserId`.
- Do not expose viewer relation or profile aggregates before the visibility check.
- Preserve the existing `403` behavior for inaccessible private profiles.
- Preserve the existing generic `ApiError` handling for malformed parameters, invalid enum values, and unexpected repository/TMDB failures.
- A TMDB failure remains a failure of the content-details hydration request, not a reason to fabricate title or image data in the profile summary.

## Verification plan

Add or update:

- repository Testcontainers coverage for every aggregation and tie-break rule;
- Summary service tests for Movies and Series, including mixed recent-review levels;
- summary tests proving rewatches do not inflate genre or rating counts;
- lightweight preview tests proving review text is omitted while likes/comments counts remain;
- Top 5 service tests for latest-score selection and null-score behavior;
- profile controller integration tests for relation states and private-profile access;
- JSON contract tests for the new DTO fields;
- documentation updates in `openapi.yaml`, `telas.md`, `business-rules.md`, and `progress.md` after the implementation lands.

The implementation is complete only when the existing test suite and the new focused tests pass, and the endpoint documentation matches the shipped semantics.
