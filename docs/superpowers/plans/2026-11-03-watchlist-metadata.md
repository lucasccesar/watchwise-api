# Watchlist Metadata Implementation Plan

> **For agentic workers:** Implement this plan inline in the current session. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Return each watchlist item's TMDB episode count, TMDB vote average, and public community average while preserving existing watchlist behavior.

**Architecture:** Extend the shared TMDB card metadata pipeline with series episode count and map it through `ContentCardAssembler`. Use the assembler's existing public stats and TMDB rating fields to enrich the requested watchlist page, then expose those values in its response DTO; the watchlist view uses the same card fields.

**Tech Stack:** Java 21, Spring Boot 4.1, MapStruct, Jackson, TMDB client, OpenAPI YAML.

## Global Constraints

- Movies, series, cast, and awards are never stored in the database; content data comes from TMDB.
- The community average uses the existing public content statistics aggregate.
- Java classes and fields use English names.
- `GET /users/{userId}/watchlist` retains its current visibility, pagination, ordering, release-date, and `upcomingCount` behavior.
- Documentation changes remain uncommitted; code changes may be committed without documentation files.
- Do not add or run tests for this task.

---

### Task 1: Carry episode count through shared TMDB card metadata

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCardMetadata.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClient.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentCardAssembler.java`

**Interfaces:**
- `TmdbCardMetadata` gains nullable `Integer numberOfEpisodes`.
- `ContentCardDTO.numberOfEpisodes` receives this value for series metadata; other types remain null.
- `ContentCardDTO.tmdbVoteAverage` remains populated only when `TMDB_VOTE_AVERAGE` is requested.

- [ ] Add `numberOfEpisodes` as the final component of `TmdbCardMetadata` and preserve existing constructor overloads so current callers continue compiling with null for the new value.
- [ ] In `TmdbClient.tvCardMetadata`, pass `TmdbTvFullDetails.numberOfEpisodes()` into the metadata record. Leave movie, season, and episode metadata constructors with a null episode count.
- [ ] In `ContentCardAssembler.toCard`, map `metadata.numberOfEpisodes()` into `ContentCardDTO.numberOfEpisodes` instead of the current null literal.
- [ ] Inspect all `new TmdbCardMetadata(...)` call sites and ensure their existing argument meaning is unchanged.

### Task 2: Enrich watchlist page entries and watchlist view cards

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/watchlist/dto/WatchlistEntryResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/watchlist/mapper/WatchlistEntryMapper.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/watchlist/service/impl/WatchlistEntryServiceImpl.java`

**Interfaces:**
- Each `WatchlistEntryResponseDTO` gains nullable `Integer numberOfEpisodes`, `Double tmdbVoteAverage`, and `Double communityAverageScore`.
- Existing short constructors remain source-compatible and initialize the added fields to null.
- Series `numberOfEpisodes` is TMDB's reported total; unavailable metadata is represented as null.
- `communityAverageScore` is the existing public aggregate and is null when no public score exists.

- [ ] Add the three nullable response fields and retain overloads for existing six- and seven-argument constructor calls.
- [ ] In `WatchlistEntryMapper`, explicitly ignore the new enrichment-only properties when mapping a persisted `WatchlistEntry`; their values are populated by the service after mapping.
- [ ] In `WatchlistEntryServiceImpl.getWatchlist`, assemble metadata and stats for entries on the requested page using the target user's preferred language and `ContentCardFieldSet.BASIC_METADATA`, `TMDB_VOTE_AVERAGE`, and `STATS`.
- [ ] For every mapped page entry, copy `numberOfEpisodes`, `tmdbVoteAverage`, and `card.stats().averageScore()` into the response DTO. For movies the episode count remains null; ratings still resolve.
- [ ] Keep metadata enrichment optional: absent TMDB values remain null, the public average remains null without public scores, and existing release-date resolution and `upcomingCount` calculation remain unchanged.
- [ ] Add `ContentCardFieldSet.TMDB_VOTE_AVERAGE` to `VIEW_CARD_FIELDS`; `STATS` is already requested there. This populates the existing `numberOfEpisodes`, `tmdbVoteAverage`, and `stats.averageScore` fields in watchlist view cards.

### Task 3: Synchronize API and project documentation

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

- [ ] Add nullable `numberOfEpisodes`, `tmdbVoteAverage`, and `communityAverageScore` properties and descriptions to the `WatchlistEntry` schema; clarify the existing `ContentCard` fields' sources and null behavior.
- [ ] Add the implemented watchlist metadata behavior under the existing business rules format, including public-only community scores, TMDB-sourced values, and null behavior for missing values.
- [ ] Add a `2026-11-03` progress entry describing the response enrichment and shared cached metadata path; update the document's last-updated date.
- [ ] Preserve the existing user changes in all three documentation files and leave documentation uncommitted.

### Task 4: Review and commit code changes

**Files:**
- Commit only the implementation files from Tasks 1 and 2.

- [ ] Review the final diff for all `TmdbCardMetadata` constructors, mapper ignores, watchlist response constructors, and OpenAPI field names; confirm existing user changes remain present.
- [ ] Run `graphify update .` to refresh the repository knowledge graph as required by the project's graphify instructions.
- [ ] Commit the finished code change using a short Conventional Commit message; do not stage documentation files and do not add a `Co-Authored-By` trailer.
- [ ] After the commit, confirm that no co-authorship or other self-attribution trailer was added.
