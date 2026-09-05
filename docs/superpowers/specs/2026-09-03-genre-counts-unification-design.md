# genreCounts unification — design

## Goal

Every `genreCounts`-family field in the API currently uses one of two inconsistent semantics
depending on which endpoint it's on, and the naming doesn't match either one consistently. Unify
all of them behind one rule:

- **Movies**: count by `DiaryEntry` row. A rewatch always adds 1 to its genres.
- **Series**: count distinct series *started* by the user (at least one `EPISODE` or direct
  `SERIES` diary entry logged — completion not required). A rewatch of an already-started series
  never adds again. Field name is `genreCountsSeries` everywhere it's split by type (previously
  `genreCountsEpisodes` in some places), never "Episodes".

## Current state (why this needs unifying)

| Endpoint | Field | Movie semantics today | Series semantics today |
|---|---|---|---|
| `/users/me`, `/users/{id}` (`UserResponseDTO`/`PublicUserProfileDTO`) | `genreCountsMovies` / `genreCountsEpisodes` | distinct title (dedup) | distinct title (dedup) |
| `/summary?type=` (`SummaryResponseDTO.genreCounts`) | `genreCounts` | distinct title (dedup) | distinct title (dedup) |
| `/summary/month`, `/summary/year` (`MonthInReviewResponseDTO`/`YearInReviewResponseDTO.genreCounts`) | `genreCounts` | count entries (correct already) | count entries **per episode** |
| `/summary/all-time` (`AllTimeStatsResponseDTO`) | `genreCountsMovies` / `genreCountsEpisodes` | count entries (correct already) | count entries **per episode** |
| `/summary/home` (`HomeSummaryResponseDTO`) | `genreCountsMoviesLast30Days` / `genreCountsEpisodesLast30Days` | count entries (correct already) | count entries **per episode** |

Two problems: (1) a movie rewatch never shows up in profile/`/summary`, only in the
time-windowed/all-time endpoints; (2) a series rewatch (or a season logged episode by episode)
inflates the "per episode" fields by the episode count instead of counting once — and
`openapi.yaml`/`business-rules.md` already claim (incorrectly, for those fields) that this has
"the same semantics" as the deduped profile field.

## Target state

| Endpoint | Movie field/query | Series field/query |
|---|---|---|
| Profile (`UserResponseDTO`/`PublicUserProfileDTO`) | `genreCountsMovies` → `countEntriesByGenreAndUserIdForMovies` | `genreCountsEpisodes` → **`genreCountsSeries`**, `countDistinctTitlesByGenreAndUserIdForSeries` (unchanged query) |
| `/summary?type=` | `genreCounts` → `countEntriesByGenreAndUserIdForMovies` | `genreCounts` → `countDistinctTitlesByGenreAndUserIdForSeries` (unchanged) |
| `/summary/month`, `/summary/year` | `genreCounts` → unchanged (`countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween`) | `genreCounts` → new `countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween` |
| `/summary/all-time` | `genreCountsMovies` → unchanged | `genreCountsEpisodes` → **`genreCountsSeries`**, `countDistinctTitlesByGenreAndUserIdForSeries` |
| `/summary/home` | `genreCountsMoviesLast30Days` → unchanged | `genreCountsEpisodesLast30Days` → **`genreCountsSeriesLast30Days`**, new `countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween` |

The generic `genreCounts` fields (`SummaryResponseDTO`, `MonthInReviewResponseDTO`,
`YearInReviewResponseDTO` — already type-scoped by the `type` query param, one combined list, no
"Movies"/"Episodes" suffix) keep their name; only the query behind the SERIES branch changes.

**Windowed "started" semantics**: for `/summary/month`, `/summary/year`, and the Last30Days home
fields, a series counts if it has *any* diary entry (`EPISODE` or direct `SERIES`) with
`watchedDate` inside the requested window — not only if the series' first-ever entry falls inside
the window. Matches the existing convention every other windowed field in these endpoints already
uses (filter `watchedDate BETWEEN`, no "first occurrence" tracking).

## Repository changes (`DiaryEntryRepository`)

**Remove** (no callers left after this change):
- `countDistinctTitlesByGenreAndUserIdForMovies` — nothing dedups movies anymore.
- `countEntriesByGenreAndUserIdForSeries` — replaced by the distinct version.
- `countEntriesByGenreAndUserIdForSeriesAndWatchedDateBetween` — replaced by the new distinct
  windowed version.

**Add** — new native query, same JOIN shape as the existing
`countDistinctTitlesByGenreAndUserIdForSeries`, with a `watched_date` window added:

```java
@Query(value = """
        SELECT genre AS genre,
               COUNT(DISTINCT CASE WHEN c.type = 'SERIES' THEN c.tmdb_id ELSE c.series_tmdb_id END) AS count
        FROM diary_entries d
        JOIN contents c ON c.id = d.content_id
        LEFT JOIN contents sc ON c.type = 'EPISODE' AND sc.tmdb_id = c.series_tmdb_id AND sc.type = 'SERIES'
        CROSS JOIN LATERAL unnest(CASE WHEN c.type = 'EPISODE' THEN sc.genres ELSE c.genres END) AS genre
        WHERE d.user_id = :userId
        AND c.type IN ('EPISODE', 'SERIES')
        AND d.watched_date BETWEEN :start AND :end
        GROUP BY genre
        ORDER BY count DESC
        """, nativeQuery = true)
List<GenreCount> countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(
        @Param("userId") UUID userId, @Param("start") LocalDate start, @Param("end") LocalDate end);
```

**Unchanged, reused as-is**: `countEntriesByGenreAndUserIdForMovies`,
`countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween`,
`countDistinctTitlesByGenreAndUserIdForSeries`.

## DTO / field renames

`genreCountsEpisodes` → `genreCountsSeries` (record component rename, same position):
- `UserResponseDTO`
- `PublicUserProfileDTO`
- `AllTimeStatsResponseDTO`

`genreCountsEpisodesLast30Days` → `genreCountsSeriesLast30Days`:
- `HomeSummaryResponseDTO`

No rename needed on `SummaryResponseDTO.genreCounts`, `MonthInReviewResponseDTO.genreCounts`,
`YearInReviewResponseDTO.genreCounts` — only their SERIES-branch query changes.

## Service layer changes

- `UserServiceImpl.ProfileStats`: rename `genreCountsEpisodes` field to `genreCountsSeries`;
  `computeProfileStats` calls `countEntriesByGenreAndUserIdForMovies` instead of
  `countDistinctTitlesByGenreAndUserIdForMovies` for the movies list.
- `UserMapper`: rename the `genreCountsEpisodes` parameter to `genreCountsSeries` on both
  `userToUserResponseDto` and `userToPublicUserProfileDto`.
- `SummaryServiceImpl`:
  - `computeGenreCounts` (backs `SummaryResponseDTO.genreCounts`): MOVIE branch switches to
    `countEntriesByGenreAndUserIdForMovies`.
  - `getMonthInReview`/`getYearInReview`: SERIES branch switches to the new
    `countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween`.
  - `getAllTimeStats`: rename local variable/field to `genreCountsSeries`, switch its query to
    `countDistinctTitlesByGenreAndUserIdForSeries`.
  - `getHomeSummary`: rename local variable/field to `genreCountsSeriesLast30Days`, switch its
    query to the new windowed distinct query.

## Testing

For each of the 5 affected endpoints, replace/add repository + service tests proving both halves
of the rule:

- **Movie rewatch sums**: log the same movie twice (`buildEntry(user, movie)`,
  `buildEntry(user, movie, 2)`), assert the genre count is 2 — mirrors the existing
  `shouldCountOnlyMovieTitles`-style tests but flips the expected outcome from today's
  `shouldCountEachMovieOnceWhenUserRewatchedIt` (that test's assertion inverts: same setup, now
  expects count `2L`, not `1L`).
- **Series rewatch does not sum**: log the same series/episode twice, assert genre count stays 1
  — the current `shouldCountEachSeriesOnceResolvingGenresFromTheSeriesContentForSummary`-style
  tests already prove this for the unwindowed query; add the equivalent for the new windowed one.
- **Series counts as "started" without finishing**: a single `EPISODE` entry (no full-season
  completion) is enough for the series to appear — already covered by the existing distinct-series
  tests, extend to the new windowed query.
- **Windowed query excludes entries outside the range**: same pattern as the existing
  `sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween` boundary tests.

Touch: `DiaryEntryRepositoryTest` (rewrite the removed/added query tests),
`UserServiceImplTest`, `UserMapperTest`, `UserControllerIntegrationTest`, `UserControllerTest`,
`SummaryServiceImplTest`.

## Docs to update alongside the code change

- `docs/context/openapi.yaml`: rename the 4 fields (`genreCountsEpisodes` →
  `genreCountsSeries` on `UserResponseDTO`/`PublicUserProfileDTO`/`AllTimeStatsResponseDTO`,
  `genreCountsEpisodesLast30Days` → `genreCountsSeriesLast30Days` on `HomeSummaryResponseDTO`);
  rewrite every `genreCounts*` description to state the new movie/series rule instead of "rewatches
  count once" / "distinct titles" for both types.
- `docs/context/business-rules.md`: rewrite the `genreCountsMovies`/`genreCountsEpisodes` entry
  (§ User) and the `/summary/all-time` and `/summary/month`/`/summary/year` genreCounts entries
  (§ Summary) to describe the unified rule and the new/removed queries.
- `docs/context/progress.md`: entry for today under the existing chronological log.

## Out of scope

- No change to `watchCountByDecade`/`watchCountByCountry` (they already dedupe by distinct title
  for both types and weren't part of this complaint).
- No API versioning/back-compat shim for the renamed fields — matches this project's existing
  convention of not carrying backward-compatibility hacks pre-launch.
