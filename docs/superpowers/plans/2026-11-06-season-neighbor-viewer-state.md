# Estado das temporadas vizinhas Implementation Plan

> **For agentic workers:** Use the approved season neighbor viewer state design at `docs/superpowers/specs/2026-11-06-season-neighbor-viewer-state-design.md`. Execute the tasks in order and keep each task's changes focused.

**Goal:** Make `GET /contents/{contentId}/page` include viewer state for all regular season cards on Season and Series pages without loading full parent-series details from a Season page or blocking on a cold schedule cache.

**Additional response field:** Season cards in `sections.seasons` include `airedEpisodeCount`, the number of distinct episodes with a known release date on or before today. Use the TMDB-derived count when available, otherwise use a complete, current local schedule snapshot; return null when neither source is reliable.

**Architecture:** Carry season summaries from the TV details call already made for the open Season into the page assembler. Resolve episode history, ratings, completion projections, and valid calendar snapshots in batches. Refresh only seasons with diary activity and missing or stale schedule data in the background; use `UNKNOWN` until a valid schedule is available.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, PostgreSQL, TMDB client/cache, existing calendar snapshot store and executor.

## Global Constraints

- Change only the existing `GET /contents/{contentId}/page` response contract.
- Keep `GET /contents/selection/series/{seriesTmdbId}/seasons` as selection metadata.
- Keep `ContentDetailsDTO.seasons` populated only for `SERIES`.
- Do not create `Content` rows or diary/rating records during reads.
- Exclude Specials (`seasonNumber = 0`) from `sections.seasons`.
- Populate season-card `airedEpisodeCount` from the TMDB-derived summary when available. For sibling seasons without that count, use complete, current calendar snapshots in a batch; return null when unavailable and do not make one TMDB request per season.
- Scope diary history and ratings to the authenticated viewer.
- Return `null` for `myRating` when the season has no local `Content` reference.
- A missing or incomplete schedule must not produce a false `WATCHED` state.
- Keep the separate worktree change in `SchedulingConfig.java` intact.
- Do not add or run tests unless the user asks for testing or verification.

---

## File map

- `content/service/ContentDetailsService.java`: add an internal page-details operation that returns public details plus sibling season summaries.
- `content/service/ContentPageDetails.java`: carry `ContentDetailsDTO` and internal `SeasonSummaryDTO` values without adding fields to `ContentDetailsDTO`.
- `content/service/impl/ContentDetailsServiceImpl.java`: build both values from the existing Season and TV TMDB responses.
- `content/service/impl/ContentPageServiceImpl.java`: consume the page-details result and remove the local parent-Series lookup.
- `content/dto/ContentChildCardDTO.java`: expose nullable `airedEpisodeCount` for season cards.
- `content/service/impl/ContentChildCardAssembler.java`: accept sibling summaries and overlay computed viewer watch state/date on season cards.
- `content/service/impl/ContentPageSeasonStateService.java` and implementation: resolve batch viewer data, read current completion projections, use valid schedules, and request background refresh when required.
- `diaryentry/repository/DiaryEntryRepository.java`: add a grouped latest-effective-watch-date query per season if existing projections do not supply the required date.
- `calendar/repository/CalendarScheduleCompletenessRepository.java` and `CalendarScheduleSnapshotRepository.java`: add batch reads restricted to the seasons with viewer history.
- `calendar/service/impl/CalendarScheduleRefreshService.java`: expose a deduplicated asynchronous refresh for one requested season through the existing provider, synchronizer, and bounded executor.
- `docs/context/openapi.yaml`, `docs/context/progress.md`, `docs/context/business-rules.md`: record the API condition, shipped behavior, and state-resolution rules.

## Task 1: Pass sibling season summaries into the page composition

**Files:**

- Create: `src/main/java/com/watchwise/watchwise_api/content/service/ContentPageDetails.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/ContentDetailsService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentPageServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentChildCardAssembler.java`

**Interface:**

```java
public record ContentPageDetails(
        ContentDetailsDTO details,
        List<SeasonSummaryDTO> siblingSeasons) {
    public ContentPageDetails {
        siblingSeasons = siblingSeasons == null ? List.of() : List.copyOf(siblingSeasons);
    }
}
```

Add `ContentPageDetails getPageDetails(UUID contentId, UUID requestingUserId)` to `ContentDetailsService`. Keep `getDetails(...)` behavior and the `ContentDetailsDTO` JSON unchanged. For a `SEASON`, construct `details` and `siblingSeasons` from the same `TmdbSeasonFullDetails` and `TmdbTvFullDetails` objects currently loaded by `buildSeasonDetails`; use an empty sibling list for other content types.

Update `ContentPageServiceImpl.getPage` to use the new page-details operation. Remove `parentSeriesDetails(...)` and do not call `contentDetailsService.getDetails` for the parent `SERIES`. Pass `siblingSeasons` to `assembleSections` for Season pages and `details.seasons()` for Series pages. Update `ContentChildCardAssembler.sectionSpecs` so a `SEASON` root creates season cards from the passed summaries even with no persisted parent row, and apply the same viewer-state overlay to `SERIES` root cards. Keep the existing positive season-number filter and metadata mapping.

Add nullable `airedEpisodeCount` to `ContentChildCardDTO` season cards. Use `SeasonSummaryDTO.airedEpisodeCount` when available, and otherwise count distinct released episode snapshots only when the local season schedule is complete, current, and consistent with the TMDB total. Non-season cards or seasons without a trustworthy source use null; do not add per-season TMDB calls.

The result is a Season page with the same metadata request count as before when a parent reference exists, while avoiding the parent's full runtime/details aggregation.

## Task 2: Resolve season viewer state with batched reads and per-season refresh

**Files:**

- Create: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentPageSeasonStateService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentChildCardAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/calendar/repository/CalendarScheduleSnapshotStore.java` only if needed for a grouped season snapshot read
- Modify: `src/main/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarScheduleRefreshService.java`

Resolve diary events for the viewer and series in one query, using distinct `(seasonNumber, episodeNumber)` coordinates. Resolve each season's latest effective date with `watchedDate`, falling back to `createdAt::date`. Reuse the existing `ContentViewerStateService` card resolution for local season ratings and other viewer fields; do not add one rating query per season. Apply this resolution to season cards on both Season and Series pages.

For each card:

1. If there are no watched episode coordinates for its season, set `watchStatus=UNWATCHED` and `lastWatchedDate=null`; do not request a calendar refresh.
2. If a current `DiaryCompletionProjection` exists for a local season reference, preserve its authoritative `WATCHED` status. Keep `lastWatchedDate` based on the latest episode event.
3. Otherwise, read the season's completeness marker and episode snapshots for the authenticated viewer's locale. Trust the schedule only when the marker is complete, its expected episode count matches `SeasonSummaryDTO.episodeCount`, the present snapshots contain that many distinct positive episode numbers, all their release dates are known, and none of their `nextCheckAt` values is due. Intersect watched coordinates with regular episodes whose release date is not after today.
4. Map an empty intersection to `UNWATCHED`, a partial intersection to `PARTIALLY_WATCHED`, and complete coverage to `WATCHED`. A zero released-episode set, mismatch with TMDB's current episode count, missing release date, due snapshot, missing marker, or incomplete marker yields `UNKNOWN`.
5. For `UNKNOWN` caused by missing or stale schedule data, enqueue one refresh keyed by `(seriesTmdbId, seasonNumber, preferredRegion, preferredLanguage)` and return immediately. Use `CalendarScheduleProvider.loadSeason`, `CalendarScheduleSynchronizer`, the existing bounded calendar refresh executor, and existing schedule locking/cadence. Apply a short bounded in-memory cooldown after an unsuccessful refresh so repeated page reads do not hammer an unavailable TMDB response; never mark incomplete data complete. Log and contain refresh failures so the page response still succeeds.

Overlay only `watchStatus` and `lastWatchedDate` onto each season card's existing `ContentViewerStateDTO`. Preserve `myRating` from existing viewer-state resolution; if `contentId` is null, `myRating` remains null. Keep empty state arrays and booleans consistent with the current `emptyState()` behavior.

## Task 3: Update the documented API and shipped behavior

**Files:**

- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/progress.md`
- Modify: `docs/context/business-rules.md`

Update `/contents/{contentId}/page`, `ContentPageDTO`, and `ContentChildCardDTO` descriptions: a `SEASON` page exposes regular sibling seasons from the current season's series metadata even when the parent `SERIES` reference is absent; cards retain nullable `contentId`, include nullable `airedEpisodeCount`, and expose `viewerState` for the authenticated viewer, which may be `UNKNOWN` while a missing/stale calendar refreshes. Do not alter `ContentSeasonSelectionDTO` or its route description. Append a `2026-11-06` progress entry and document the schedule, diary-history, aired-count, and nullable-rating rules in the relevant business-rules section.

## Completion review

- Review the diff to confirm no second parent-Series details load remains on a Season page.
- Confirm no route or public `ContentDetailsDTO` field was added.
- Confirm only the authenticated viewer's episode history and rating feed the cards.
- Confirm missing/stale/incomplete calendars cannot yield `WATCHED` from a count-only comparison.
- Compile with the Maven wrapper and `-DskipTests`; do not add or run tests unless the user asks.
