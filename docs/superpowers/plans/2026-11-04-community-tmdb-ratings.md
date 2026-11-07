# Community and TMDB Ratings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Return four recent episodes for series pages and include the matching TMDB vote average in every response that exposes a public community average.

**Architecture:** Keep community ratings in their current repositories and derive TMDB vote averages from the existing metadata resolver. Use the already bounded content-metadata executor for optional batch reads, reuse full detail/season payloads when available, and never persist TMDB ratings. Add nullable response fields to the DTOs that expose public averages and preserve existing personal-rating semantics.

**Tech Stack:** Java 21, Spring Boot, Maven wrapper, Jackson records, TMDB client/cache, OpenAPI YAML.

## Global Constraints

- Keep all work on the existing `main` branch.
- Do not add database columns or migrations; TMDB metadata remains external.
- Update `docs/context/openapi.yaml`, `docs/context/business-rules.md`, and `docs/context/progress.md` with the implementation.
- Do not add or run tests unless the user asks; compile with Maven using `-DskipTests`.
- Do not push. Commit the completed code with a one-line Conventional Commit and leave documentation files uncommitted, as required by `AGENTS.md`.
- Keep TMDB failures optional for stats/list enrichment: return a null TMDB average and preserve the community data.

---

### Task 1: Raise the series recent-episode limits

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentChildCardAssembler.java`
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`

**Interfaces:**
- The detail DTO and page section keep their existing `recentEpisodes` properties and ordering; each now allows four entries.

- [ ] Change `RECENT_EPISODES_LIMIT` from `3` to `4` in both the TMDB detail filtering method and the page child-card assembler.
- [ ] Keep the existing released-date filter, descending date/season/episode ordering, and eligibility checks intact.
- [ ] Update both `ContentDetailsDTO.recentEpisodes` and `ContentPageSectionsDTO.recentEpisodes` OpenAPI descriptions/limits to document up to four entries.
- [ ] Update the Content business-rule entry for `/contents/{contentId}/page` to say that SERIES returns up to four recent episodes.

### Task 2: Add a bounded TMDB vote-average resolver and enrich content stats

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/common/concurrent/ShutdownAwareRunnable.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCacheConfig.java`
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/impl/TmdbVoteAverageBatchResolver.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentDetailsDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentStatsResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentPageStatsDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/ContentStatsService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/ContentPageStatsService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentStatsServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentPageStatsServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentPageServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImpl.java`

**Interfaces:**
- Add `Map<ContentCoordinate, Double> resolve(Collection<ContentCoordinate> coordinates, String language)` to `TmdbVoteAverageBatchResolver`.
- Add `getCommunityStats(UUID)` and `getCommunityStatsBatch(List<UUID>)` to `ContentStatsService`; these return community data without an external lookup for internal card/home assembly.
- Keep `getStats(UUID)` and `getStatsBatch(List<UUID>)` as response methods and enrich their results with `tmdbVoteAverage`.
- Keep the existing `ContentPageStatsService.getStats(UUID)` interface. Append nullable `tmdbVoteAverage` to `ContentStatsResponseDTO`, `ContentPageStatsDTO`, and `ContentDetailsDTO`; retain delegating constructors and add `ContentPageStatsDTO.withTmdbVoteAverage(Double)`.

- [ ] Implement the batch resolver over `TmdbCardMetadataResolver` and the existing `contentCardMetadataResolutionExecutor`. Deduplicate coordinates, use `TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE` when no language is supplied, and turn missing/unavailable results or per-coordinate runtime failures into null values without failing the batch.
- [ ] Add a shutdown-aware task callback to the executor's CallerRunsPolicy. Complete aware tasks only when the policy discards them after shutdown; track whether `run()` started so an accepted/caller-run task is never mistaken for a discard.
- [ ] Populate `ContentDetailsDTO.tmdbVoteAverage` from the already-fetched `voteAverage` for MOVIE, SERIES, SEASON, and EPISODE details. Preserve existing constructors by delegating with null for legacy call shapes.
- [ ] Factor `ContentStatsServiceImpl` so the public stats methods add batch-resolved vote averages while `getCommunityStats*` performs only the existing repository aggregation.
- [ ] In `ContentPageStatsServiceImpl`, call the community-only stats method. In `ContentPageServiceImpl`, copy the returned stats with `withTmdbVoteAverage(details.tmdbVoteAverage())` so the page reuses the detail payload without changing the service interface or making another TMDB lookup.
- [ ] Preserve input order and duplicate IDs in batch stats responses; unknown content IDs receive a null TMDB average.

### Task 3: Pair ratings in card, home, and person-credit responses

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/ContentCardSpec.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/dto/EpisodeSummaryDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/dto/SeasonSummaryDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbSeasonSummary.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentCardAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentChildCardAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/HomeNextEpisodeAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/person/dto/PersonCreditViewDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/person/service/impl/PersonCreditViewAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/person/service/impl/PersonViewServiceImpl.java`

**Interfaces:**
- When `ContentCardFieldSet.STATS` is selected, `ContentCardAssembler` also returns the existing sibling field `ContentCardDTO.tmdbVoteAverage`.
- Add nullable `tmdbVoteAverage` to `PersonCreditViewDTO`.
- Use `List<PersonCreditViewDTO> enrichTmdbVoteAverages(List<PersonCreditViewDTO> pageCredits, String language)` and immutable copies through `PersonCreditViewDTO.withTmdbVoteAverage(Double)`.

- [ ] In `ContentCardAssembler.normalizedFields`, add `TMDB_VOTE_AVERAGE` whenever `STATS` is present; use `getCommunityStatsBatch` internally to avoid resolving the same rating twice.
- [ ] When `ContentChildCardAssembler` requests STATS without a viewer language, set `ContentCardContext.language` to `TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE` so its newly requested TMDB metadata lookup has a valid cache/request language.
- [ ] Keep the card's existing metadata resolver as the source of its sibling `tmdbVoteAverage` and preserve null behavior when metadata is unavailable.
- [ ] Carry TMDB vote averages from `TmdbEpisodeSummary`, `TmdbSeasonSummary`, and `TmdbSeasonFullDetails` through `EpisodeSummaryDTO`/`SeasonSummaryDTO` into child `CardSpec` and `ContentCardSpec`, including an explicit resolved flag so known-null scores remain distinct from unrequested scores. Preserve previous constructor forms.
- [ ] In `ContentCardAssembler`, use a supplied pre-resolved vote average (including known null) and skip per-child metadata lookup when the child summary already contains complete vote-average resolution. In `ContentChildCardAssembler`, copy `card.tmdbVoteAverage()` into nested `ContentStatsResponseDTO` and preserve the existing community counts.
- [ ] Submit `ContentCardAssembler.metadataByCoordinate` work as `ShutdownAwareRunnable`, so the shared CallerRunsPolicy completes rejected futures only when it actually rejects them after shutdown. Use a started flag to distinguish an executed task from a silent shutdown discard; preserve optional-metadata fallback and loader error propagation.
- [ ] Change `HomeNextEpisodeAssembler` to call `getCommunityStats`, since `ProgressEpisodeDTO.tmdbVoteAverage` already supplies the paired TMDB value.
- [ ] Add a post-pagination enrichment method to `PersonCreditViewAssembler`; resolve only the credit coordinates in the returned `PageResponseDTO`, pass the viewer's existing preferred language, and add the result with `PersonCreditViewDTO.withTmdbVoteAverage`.
- [ ] Keep person-credit filtering and `AVERAGE_SCORE` sorting based on community averages; do not resolve TMDB ratings for the full unpaged filmography.

### Task 4: Pair ratings in user-list item responses

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/mapper/UserListItemMapper.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemPageReaderImpl.java`

**Interfaces:**
- Append nullable `tmdbVoteAverage` to `UserListItemResponseDTO` and preserve existing constructor shapes.
- Add `withTmdbVoteAverage(Double)` for immutable enrichment.

- [ ] Resolve TMDB averages only after `UserListItemPageReaderImpl` selects the returned page, and only for rows with content coordinates; nested-list rows remain null.
- [ ] In `UserListItemMapper`, explicitly ignore the response-only `tmdbVoteAverage` field and `withTmdbVoteAverage` helper, following its existing explicit mapping policy.
- [ ] Skip the extra resolver path in `VISUAL` mode because `UserListDetailViewServiceImpl` already assembles a `ContentCardDTO` and the card's `STATS` selection now includes `tmdbVoteAverage`.
- [ ] Preserve `episodeAverageRating` as owner-specific and keep `globalEpisodeAverageRating`/`contentAverageRating` unchanged; add one TMDB vote average for the listed content item.

### Task 5: Pair ratings in episode-rating grids

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbTvDisplayDetails.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/EpisodeRatingsMetadata.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/EpisodeRatingsMetadataServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/EpisodeRatingsReleasedEpisodes.java`
- Create: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/ReleasedEpisodeRating.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/EpisodeRatingsGridResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/EpisodeScoreDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java`

**Interfaces:**
- Add `tmdbVoteAverage` to the episode-rating grid root and to each `EpisodeScoreDTO` row.
- Preserve the existing `Map<Integer, List<Integer>> load(String seriesTmdbId, List<Integer> regularSeasonNumbers, LocalDate today)` signature. Add `List<ReleasedEpisodeRating> loadVoteAverages(String seriesTmdbId, List<Integer> regularSeasonNumbers, LocalDate today)`; each record carries `seasonNumber`, `episodeNumber`, and nullable `tmdbVoteAverage` from already-fetched `TmdbSeasonFullDetails` data.

- [ ] Add `voteAverage` to `TmdbTvDisplayDetails` and preserve its prior constructors; map it into `EpisodeRatingsMetadata` from the existing `/tv/{id}` display response.
- [ ] Add nullable `tmdbVoteAverage` to the grid response and episode rows. Preserve old DTO constructor forms with delegating constructors.
- [ ] Preserve `load` for released episode coordinates and add `loadVoteAverages` for the same released coordinates with vote averages; do not make a separate TMDB request per episode.
- [ ] In `SummaryServiceImpl.getEpisodeRatingsGrid`, use the vote-average result when available, and fall back to the existing `load` coordinates when it is empty. Pair each public community episode average with the coordinate's TMDB average and pair `overallAverageScore` with the series TMDB average.
- [ ] Leave `EpisodeRatingsMap` unchanged because it exposes no community rating.

### Task 6: Synchronize contracts, business rules, progress, and finish

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

- [ ] Add nullable `tmdbVoteAverage` to the schemas for ContentDetails, ContentStats, ContentPageStats, ContentCard, PersonCreditView, UserListItem, and EpisodeRatingsGrid/episode rows. Confirm existing Trending, Watchlist, Home next-episode, and series-in-progress schemas already describe paired values.
- [ ] Update the Content business-rule entry: `STATS` now includes the card's TMDB vote average; optional TMDB lookup failure leaves community aggregates intact and returns null; TMDB values remain external and are not persisted.
- [ ] Append the shipped behavior under `## 2026-11-04` in `progress.md`.
- [ ] Run `mvnw.cmd -DskipTests compile` and inspect the output; do not add or run tests.
- [ ] Run `graphify update .` and inspect `git status` to confirm the local graph refresh did not add unrelated files.
- [ ] Commit only code files with `feat(content): return TMDB averages with community scores`; leave docs/plan/spec uncommitted and do not push.

---

## Plan self-review

- The four-episode change is covered by Task 1 and documented in OpenAPI and business rules.
- Public community averages are paired across stats, page stats, cards, person credits, list item rows, and episode ratings. Existing paired Trending/Watchlist/Home/series-progress responses are preserved.
- Personal ratings, owner-only list averages, rating maps without community averages, and review scores are explicitly excluded or unchanged.
- TMDB reads are bounded, paged list/credit responses resolve only returned rows, page details reuse the fetched average, and episode grids reuse season payloads.
- Missing TMDB ratings remain null without erasing or failing the community data; no persistence schema changes are included.
- No placeholders or undefined new APIs remain; the new helper and interface signatures are defined in their task sections.
