# Home `nextEpisodes` Implementation Plan

> **For agentic workers:** Use the executing-plans skill to apply this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Return up to four valid Home `nextEpisodes` cards in order of each series' most recently watched episode.

**Architecture:** Keep using the existing series-progress service and preview assembler. The Home reads additional pages only when earlier candidates cannot produce a preview; the repository uses the selected episode's effective watch date and diary creation time to order series deterministically.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA native PostgreSQL query, Maven.

## Global Constraints

- Keep the existing response schema and the maximum of four `nextEpisodes` cards.
- Preserve the existing Home behavior when TMDB is unavailable before any preview is assembled.
- Preserve already assembled previews if a later page encounters TMDB unavailability.
- Keep series-progress authorization checks and visibility behavior unchanged.
- Update `docs/context/business-rules.md` and `docs/context/progress.md` with the shipped behavior.
- Do not add or run tests in this task; the active instruction set requires tests only when requested.
- Do not commit documentation-only changes; the repository instructions require documentation to remain worktree-only.

---

## File Structure

- `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java` owns Home orchestration and will page until it has four assembled previews or exhausts candidates.
- `src/main/java/com/watchwise/watchwise_api/seriesprogress/repository/SeriesProgressReadRepository.java` owns candidate ordering and will add the selected event's creation timestamp as a tie-breaker.
- `docs/context/business-rules.md` records the Home preview selection and ordering rule.
- `docs/context/business-rules-summary.md` has a stale `size=6` description and should match the current four-card behavior.
- `docs/context/progress.md` receives a dated entry for the behavior change.

### Task 1: Fill Home previews from later progress pages

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java`

**Interfaces:**
- Consumes: `DiaryEntryService.getSeriesInProgress(viewerId, userId, pageNumber, pageSize)` returning a `Page<SeriesInProgressResponseDTO>`.
- Produces: a Home list of at most four `SeriesInProgressPreviewDTO` values assembled in page and candidate order.

- [ ] Replace the one-page fetch in `getHomeSummary` with a private loader that starts at page 1, requests four candidates per page, and appends previews from `HomeNextEpisodeAssembler` until the output has four entries or `Page.hasNext()` is false.
- [ ] Track `seriesTmdbId` values already appended so concurrent changes between page requests cannot duplicate a card.
- [ ] Catch `TmdbUnavailableException` around each page read; stop loading and return accumulated previews, which is empty when page 1 fails.
- [ ] Stop immediately when a page has no candidates, even if an inconsistent page implementation reports a following page.

### Task 2: Order by the actual most recent diary event

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/seriesprogress/repository/SeriesProgressReadRepository.java`

**Interfaces:**
- Consumes: `episode_entries` values `effective_date`, `created_at`, and `diary_entry_id`.
- Produces: `LAST_WATCHED` candidate order by effective date descending, creation time descending, and series TMDB ID ascending.

- [ ] Project `lw.created_at` from `last_watched_ranked` into `candidate_rows` as the timestamp of the selected latest event.
- [ ] Change the `LAST_WATCHED` `ORDER BY` clauses to compare effective watch date first and the selected event timestamp second, preserving series TMDB ID as a stable final tie-breaker.
- [ ] Keep within-series selection ordered by effective date, creation timestamp, and diary-entry ID descending so the timestamp belongs to the selected latest event.

### Task 3: Synchronize implemented documentation

**Files:**
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/business-rules-summary.md`
- Modify: `docs/context/progress.md`

- [ ] Update the Home `nextEpisodes` rule to describe filling up to four cards by continuing through ordered candidates whose next episode resolves.
- [ ] Correct the summary document's stale `size=6` note to the implemented four-card behavior and recent-watch ordering.
- [ ] Append a `2026-10-29` progress entry describing only the code behavior shipped.

### Task 4: Inspect the final change

- [ ] Review the resulting diff for query syntax, stable pagination behavior, preserved authorization, and the four-card cap.
- [ ] Confirm no files outside the two implementation classes and the three specified context documents changed.

## Plan self-review

- The design's fill-until-four behavior maps to Task 1, including exhaustion, duplicate pages, and TMDB failures.
- The same-day ordering requirement maps to Task 2, with deterministic final ordering.
- Documentation coverage maps to Task 3, including correction of the stale summary note.
- No schema, route, DTO, or permission changes are introduced.
