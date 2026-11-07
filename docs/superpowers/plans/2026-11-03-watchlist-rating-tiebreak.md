# Watchlist Rating Tie-Break Implementation Plan

> **For agentic workers:** Execute inline in the current checkout. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sort the visual watchlist by Watchwise public average first and TMDB vote average second when community averages tie.

**Architecture:** Extend the existing in-memory `RATING` comparator to apply the two nullable score keys in sequence. Materialize public stats and cached TMDB basic metadata for the existing candidate set; retain its cap and final UUID tie-break.

**Tech Stack:** Java 21, Spring Boot 4.1, ContentCardAssembler, OpenAPI YAML, Markdown.

## Global Constraints

- `RATING` primary key is the existing public `ContentCard.stats.averageScore`.
- `tmdbVoteAverage` is the secondary key and uses the same ASC/DESC direction.
- Null score values remain last in either direction; UUID ascending remains the final tie-break.
- `GET /users/{userId}/watchlist` retains global position ordering; only `/watchlist/view?sort=RATING` changes.
- Preserve the existing materialized candidate limit and all other sort modes.
- Documentation stays uncommitted; commit only code files.
- Do not add or run tests for this task.

---

### Task 1: Update the RATING comparator and its metadata fields

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/watchlist/service/impl/WatchlistEntryServiceImpl.java`

**Interfaces:**
- `materializedSortCards` requests `STATS`, `BASIC_METADATA`, and `TMDB_VOTE_AVERAGE` for `RATING` candidates.
- `sortCandidates` compares public average, then TMDB vote average, then entry UUID ascending.

- [ ] Extend the `RATING` materialization field set so the card contains both the public average and TMDB vote average. `ContentCardAssembler` requires `BASIC_METADATA` to resolve `TMDB_VOTE_AVERAGE`.
- [ ] In the `RATING` comparator branch, compare `card.stats().averageScore()` first and compare `card.tmdbVoteAverage()` only if the public averages compare equal. Use the existing nullable comparator and the same direction for both keys.
- [ ] Preserve null-last behavior independently for both score keys and preserve the existing UUID ascending final tie-break.
- [ ] Leave the candidate cap, page endpoint ordering, all other sort modes, and aggregate calculation unchanged.

### Task 2: Synchronize Watchlist documentation

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

- [ ] Describe the RATING primary and secondary keys, direction, null behavior, and final UUID tie-break in the visual watchlist contract.
- [ ] Update the WatchlistEntry business rule to name the community-first/TMDB-second comparator and the materialized fields.
- [ ] Add the shipped sort change to the existing 2026-11-03 progress entry without replacing prior text.
- [ ] Preserve all existing uncommitted doc changes and leave documentation uncommitted.

### Task 3: Verify and commit code

- [ ] Run compile-only verification and `git diff --check`; do not run tests.
- [ ] Review the exact diff for unchanged pagination, candidate cap, null placement, direction, and UUID tie-breaking.
- [ ] Commit only `WatchlistEntryServiceImpl.java` with a Conventional Commit message and no co-authorship trailer.
