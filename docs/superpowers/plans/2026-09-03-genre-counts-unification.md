# genreCounts Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every `genreCounts`-family field in the API count movies by `DiaryEntry` row (rewatch sums) and series by distinct title "started" (rewatch never sums again), consistently across all 6 endpoints that expose one today, and rename every field currently called `...Episodes...` to `...Series...`.

**Architecture:** Pure read-side change — no schema migration, no new entities. Swap which existing/new `DiaryEntryRepository` native query backs each DTO field, rename 4 record components, and update the two docs (`openapi.yaml`, `business-rules.md`) that describe this behavior.

**Tech Stack:** Spring Data JPA native `@Query`, MapStruct (`UserMapper`), JUnit 5 + AssertJ + Mockito, Testcontainers (`postgres:16-alpine`) for repository tests.

## Global Constraints

- No code comments (project convention — self-explanatory naming instead).
- Test method naming: `should<ExpectedBehavior>When<Condition>` (camelCase, no underscores).
- `@DisplayName` format: `"[methodUnderTest] Should <Behavior> - When <Condition>"` (method name in brackets exactly as written; Title Case elsewhere except real type/exception identifiers).
- No `Co-Authored-By` trailer on commits (this repo's own convention overrides generic attribution rules); Conventional Commits (`type(scope): description`), one short line, no body.
- Do not `git add`/commit anything under `docs/` (gitignored in this repo) — commit only `src/` changes per task.
- Every entity/DTO field must stay explicitly mapped in MapStruct mappers (`unmappedTargetPolicy = ReportingPolicy.ERROR`) — a rename on one side without the other fails the build.
- `docs/context/openapi.yaml`, `docs/context/business-rules.md`, `docs/context/progress.md` must be updated in the same change as the code, per this repo's `CLAUDE.md`.

---

## Task 1: `DiaryEntryRepository` — swap the query set

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java`

**Interfaces:**
- Produces (for Tasks 2-6 to call):
  - `List<GenreCount> countEntriesByGenreAndUserIdForMovies(UUID userId)` — already exists, unchanged.
  - `List<GenreCount> countDistinctTitlesByGenreAndUserIdForSeries(UUID userId)` — already exists, unchanged.
  - `List<GenreCount> countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(UUID userId, LocalDate start, LocalDate end)` — already exists, unchanged.
  - `List<GenreCount> countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(UUID userId, LocalDate start, LocalDate end)` — **new in this task**.
- Removes (must have zero callers after Task 6): `countDistinctTitlesByGenreAndUserIdForMovies`, `countEntriesByGenreAndUserIdForSeries`, `countEntriesByGenreAndUserIdForSeriesAndWatchedDateBetween`.

### Step 1: Remove the now-obsolete `countDistinctTitlesByGenreAndUserIdForMovies` tests

Delete these 4 tests from `DiaryEntryRepositoryTest.java` (they cover a query being removed):
`shouldCountOnlyMovieTitles`, `shouldGroupByGenreWhenMovieHasMultipleGenres`,
`shouldCountEachMovieOnceWhenUserRewatchedIt`, `shouldOmitContentWhenMovieHasNoGenres` — the block
currently reads:

```java
    @Test
    @DisplayName("[countDistinctTitlesByGenreAndUserIdForMovies] Should Count Only Movie Titles")
    void shouldCountOnlyMovieTitles() {
        Content movie = contentRepository.save(buildContent("9001", ContentType.MOVIE, 139, List.of("Drama")));
        Content episode = contentRepository.save(buildEpisode("1399", 1, 1, 55));
        contentRepository.save(buildContent("1399", ContentType.SERIES, null, List.of("Drama")));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, episode));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForMovies(lucas.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[countDistinctTitlesByGenreAndUserIdForMovies] Should Group By Genre - When Movie Has Multiple Genres")
    void shouldGroupByGenreWhenMovieHasMultipleGenres() {
        Content movie = contentRepository.save(buildContent("9001", ContentType.MOVIE, 139, List.of("Drama", "Thriller")));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForMovies(lucas.getId());

        assertThat(result).extracting(DiaryEntryRepository.GenreCount::getGenre).containsExactlyInAnyOrder("Drama", "Thriller");
        assertThat(result).allSatisfy(row -> assertThat(row.getCount()).isEqualTo(1L));
    }

    @Test
    @DisplayName("[countDistinctTitlesByGenreAndUserIdForMovies] Should Count Each Movie Once - When User Rewatched It")
    void shouldCountEachMovieOnceWhenUserRewatchedIt() {
        Content movie = contentRepository.save(buildContent("9001", ContentType.MOVIE, 139, List.of("Drama")));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie, 2));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForMovies(lucas.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[countDistinctTitlesByGenreAndUserIdForMovies] Should Omit Content - When Movie Has No Genres")
    void shouldOmitContentWhenMovieHasNoGenres() {
        Content movieWithoutGenres = contentRepository.save(buildContent("9001", ContentType.MOVIE));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movieWithoutGenres));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForMovies(lucas.getId());

        assertThat(result).isEmpty();
    }
```

Delete the whole block above.

### Step 2: Write the replacement failing tests for `countEntriesByGenreAndUserIdForMovies`

`countEntriesByGenreAndUserIdForMovies` already exists in the repository (used today only by
`AllTimeStatsResponseDTO`) but has zero repository-level tests. Add this block in the same spot the
deleted one occupied:

```java
    @Test
    @DisplayName("[countEntriesByGenreAndUserIdForMovies] Should Count Only Movie Titles")
    void shouldCountOnlyMovieTitlesByEntry() {
        Content movie = contentRepository.save(buildContent("9001", ContentType.MOVIE, 139, List.of("Drama")));
        Content episode = contentRepository.save(buildEpisode("1399", 1, 1, 55));
        contentRepository.save(buildContent("1399", ContentType.SERIES, null, List.of("Drama")));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, episode));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository.countEntriesByGenreAndUserIdForMovies(lucas.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[countEntriesByGenreAndUserIdForMovies] Should Group By Genre - When Movie Has Multiple Genres")
    void shouldGroupByGenreWhenMovieHasMultipleGenresByEntry() {
        Content movie = contentRepository.save(buildContent("9001", ContentType.MOVIE, 139, List.of("Drama", "Thriller")));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository.countEntriesByGenreAndUserIdForMovies(lucas.getId());

        assertThat(result).extracting(DiaryEntryRepository.GenreCount::getGenre).containsExactlyInAnyOrder("Drama", "Thriller");
        assertThat(result).allSatisfy(row -> assertThat(row.getCount()).isEqualTo(1L));
    }

    @Test
    @DisplayName("[countEntriesByGenreAndUserIdForMovies] Should Count Every Entry - When User Rewatched It")
    void shouldCountEveryEntryWhenUserRewatchedTheMovie() {
        Content movie = contentRepository.save(buildContent("9001", ContentType.MOVIE, 139, List.of("Drama")));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie, 2));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository.countEntriesByGenreAndUserIdForMovies(lucas.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("[countEntriesByGenreAndUserIdForMovies] Should Omit Content - When Movie Has No Genres")
    void shouldOmitContentWhenMovieHasNoGenresByEntry() {
        Content movieWithoutGenres = contentRepository.save(buildContent("9001", ContentType.MOVIE));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, movieWithoutGenres));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository.countEntriesByGenreAndUserIdForMovies(lucas.getId());

        assertThat(result).isEmpty();
    }
```

These already compile and pass against the existing `countEntriesByGenreAndUserIdForMovies` query —
this step is not red/green since the method already exists; it only backfills missing coverage. Run
them now to confirm green before moving on:

Run: `mvnw.cmd test "-Dtest=DiaryEntryRepositoryTest#shouldCountOnlyMovieTitlesByEntry+shouldGroupByGenreWhenMovieHasMultipleGenresByEntry+shouldCountEveryEntryWhenUserRewatchedTheMovie+shouldOmitContentWhenMovieHasNoGenresByEntry"`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

### Step 3: Remove the now-obsolete `countEntriesByGenreAndUserIdForSeries*` tests

Delete these 2 tests (their queries are being removed):

```java
    @Test
    @DisplayName("[countEntriesByGenreAndUserIdForSeriesAndWatchedDateBetween] Should Count A Directly Logged SERIES Entry - When No Episode Entries Exist")
    void shouldCountADirectlyLoggedSeriesEntryInDateRangeWhenNoEpisodeEntriesExist() {
        contentRepository.deleteAll();
        Content series = contentRepository.save(buildContent("1399", ContentType.SERIES, null, List.of("Drama")));
        LocalDate today = LocalDate.now();
        diaryEntryRepository.save(withWatchedDate(buildEntry(lucas, series), today));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository
                .countEntriesByGenreAndUserIdForSeriesAndWatchedDateBetween(lucas.getId(), today.minusDays(1), today.plusDays(1));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getGenre()).isEqualTo("Drama");
        assertThat(result.getFirst().getCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[countEntriesByGenreAndUserIdForSeries] Should Count A Directly Logged SERIES Entry - When No Episode Entries Exist")
    void shouldCountADirectlyLoggedSeriesEntryWhenNoEpisodeEntriesExist() {
        contentRepository.deleteAll();
        Content series = contentRepository.save(buildContent("1399", ContentType.SERIES, null, List.of("Drama")));
        diaryEntryRepository.saveAndFlush(buildEntry(lucas, series));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository.countEntriesByGenreAndUserIdForSeries(lucas.getId());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getGenre()).isEqualTo("Drama");
        assertThat(result.getFirst().getCount()).isEqualTo(1L);
    }
```

Delete both. Leave the `countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween` test immediately
above them (`shouldCountEveryEntryWhenTheSameGenreIsWatchedTwice`) untouched — that query is unchanged.

### Step 4: Write the failing tests for the new windowed distinct-series query

Add this block where the deleted tests from Step 3 were:

```java
    @Test
    @DisplayName("[countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween] Should Count A Series Once - When Watched Via Episode Entries In Range")
    void shouldCountASeriesOnceInDateRangeWhenWatchedViaEpisodeEntries() {
        contentRepository.deleteAll();
        contentRepository.save(buildContent("1399", ContentType.SERIES, null, List.of("Drama")));
        Content episode1 = contentRepository.save(buildEpisode("1399", 1, 1));
        Content episode2 = contentRepository.save(buildEpisode("1399", 1, 2));
        LocalDate today = LocalDate.now();
        diaryEntryRepository.save(withWatchedDate(buildEntry(lucas, episode1), today));
        diaryEntryRepository.save(withWatchedDate(buildEntry(lucas, episode2), today));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository
                .countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(lucas.getId(), today.minusDays(1), today.plusDays(1));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getGenre()).isEqualTo("Drama");
        assertThat(result.getFirst().getCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween] Should Count A Directly Logged SERIES Entry - When No Episode Entries Exist In Range")
    void shouldCountADirectlyLoggedSeriesEntryInDateRangeWhenNoEpisodeEntriesExist() {
        contentRepository.deleteAll();
        Content series = contentRepository.save(buildContent("1399", ContentType.SERIES, null, List.of("Drama")));
        LocalDate today = LocalDate.now();
        diaryEntryRepository.save(withWatchedDate(buildEntry(lucas, series), today));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository
                .countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(lucas.getId(), today.minusDays(1), today.plusDays(1));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getGenre()).isEqualTo("Drama");
        assertThat(result.getFirst().getCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween] Should Not Count Again - When The Series Is Rewatched Inside The Same Window")
    void shouldNotCountAgainWhenTheSeriesIsRewatchedInsideTheSameWindow() {
        contentRepository.deleteAll();
        contentRepository.save(buildContent("1399", ContentType.SERIES, null, List.of("Drama")));
        Content episode = contentRepository.save(buildEpisode("1399", 1, 1));
        LocalDate today = LocalDate.now();
        diaryEntryRepository.save(withWatchedDate(buildEntry(lucas, episode), today));
        diaryEntryRepository.save(withWatchedDate(buildEntry(lucas, episode, 2), today));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository
                .countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(lucas.getId(), today.minusDays(1), today.plusDays(1));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween] Should Exclude Entries Outside The Watched Date Range")
    void shouldExcludeSeriesEntriesOutsideTheWatchedDateRange() {
        contentRepository.deleteAll();
        contentRepository.save(buildContent("1399", ContentType.SERIES, null, List.of("Drama")));
        Content episode = contentRepository.save(buildEpisode("1399", 1, 1));
        diaryEntryRepository.save(withWatchedDate(buildEntry(lucas, episode), LocalDate.of(2023, 6, 1)));

        List<DiaryEntryRepository.GenreCount> result = diaryEntryRepository
                .countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(
                        lucas.getId(), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(result).isEmpty();
    }
```

### Step 5: Run the tests to verify they fail (method doesn't exist yet)

Run: `mvnw.cmd test "-Dtest=DiaryEntryRepositoryTest"`
Expected: **compile error** — `countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween` and
`countEntriesByGenreAndUserIdForSeries`/`...AndWatchedDateBetween` don't resolve (the latter two
still exist at this point since Step 6 hasn't removed them — so the compile error is specifically
about the missing new method; if it compiles because the deletions in Step 3 already ran, it will
instead fail at runtime with `PropertyReferenceException`/similar). Either failure mode confirms the
new method doesn't exist yet.

### Step 6: Implement the repository changes

In `DiaryEntryRepository.java`, remove `countDistinctTitlesByGenreAndUserIdForMovies` (the query
currently at lines 295-305):

```java
    @Query(value = """
            SELECT genre AS genre, COUNT(DISTINCT c.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            CROSS JOIN LATERAL unnest(c.genres) AS genre
            WHERE d.user_id = :userId
            AND c.type = 'MOVIE'
            GROUP BY genre
            ORDER BY count DESC
            """, nativeQuery = true)
    List<GenreCount> countDistinctTitlesByGenreAndUserIdForMovies(@Param("userId") UUID userId);
```

Delete this method entirely. Leave `countDistinctTitlesByGenreAndUserIdForSeries` immediately below
it untouched.

Remove `countEntriesByGenreAndUserIdForSeriesAndWatchedDateBetween` and
`countEntriesByGenreAndUserIdForSeries` (currently at lines 534-547 and 561-572):

```java
    @Query(value = """
            SELECT genre AS genre, COUNT(d.id) AS count
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
    List<GenreCount> countEntriesByGenreAndUserIdForSeriesAndWatchedDateBetween(
            @Param("userId") UUID userId, @Param("start") LocalDate start, @Param("end") LocalDate end);
```

and

```java
    @Query(value = """
            SELECT genre AS genre, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            LEFT JOIN contents sc ON c.type = 'EPISODE' AND sc.tmdb_id = c.series_tmdb_id AND sc.type = 'SERIES'
            CROSS JOIN LATERAL unnest(CASE WHEN c.type = 'EPISODE' THEN sc.genres ELSE c.genres END) AS genre
            WHERE d.user_id = :userId
            AND c.type IN ('EPISODE', 'SERIES')
            GROUP BY genre
            ORDER BY count DESC
            """, nativeQuery = true)
    List<GenreCount> countEntriesByGenreAndUserIdForSeries(@Param("userId") UUID userId);
```

Delete both methods entirely. Leave `countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween`
and `countEntriesByGenreAndUserIdForMovies` (the two "count entries" movie queries) untouched.

In the gap left where `countEntriesByGenreAndUserIdForSeries` was removed, add the new query,
modeled on `countDistinctTitlesByGenreAndUserIdForSeries` with a `watched_date` window added:

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

### Step 7: Run the full repository test class to verify green

Run: `mvnw.cmd test "-Dtest=DiaryEntryRepositoryTest"`
Expected: `BUILD SUCCESS`, all tests passing (Docker must be running — this class uses Testcontainers).

### Step 8: Commit

```bash
git add src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java
git commit -m "refactor(diary): unify genre count queries around entry-count for movies and windowed distinct-series for series"
```

---

## Task 2: Profile (`UserResponseDTO`/`PublicUserProfileDTO`) — rename and re-wire

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/user/dto/UserResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/user/dto/PublicUserProfileDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/user/mapper/UserMapper.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/user/service/impl/UserServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/user/mapper/UserMapperTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/user/service/UserServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/user/controller/UserControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `DiaryEntryRepository.countEntriesByGenreAndUserIdForMovies(UUID)`,
  `DiaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeries(UUID)` (both from Task 1,
  unchanged signatures).
- Produces: `UserResponseDTO.genreCountsSeries()`, `PublicUserProfileDTO.genreCountsSeries()` —
  replaces `genreCountsEpisodes()` on both records, same `List<GenreCountDTO>` type, same position.

### Step 1: Flip the failing service test

In `UserServiceImplTest.java`, the test at line ~369-394
(`shouldPropagateComputedWatchStatsToTheMapperWhenDiaryHasEntries`) currently stubs
`diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForMovies(id)`. Change it to stub the
entries query instead — since Task 1 already removed the old method, this test currently fails to
compile:

```java
        when(diaryEntryRepository.countEntriesByGenreAndUserIdForMovies(id)).thenReturn(List.of(actionGenre));
```

(Replaces the old `countDistinctTitlesByGenreAndUserIdForMovies(id)` stub — same line, same
surrounding test body otherwise.)

### Step 2: Update the mapper test

In `UserMapperTest.java`, rename the two `genresEpisodes` local variables and their assertions
(around lines 174-194 and 199-231) from `genreCountsEpisodes()` to `genreCountsSeries()`:

```java
        List<GenreCountDTO> genresMovies = List.of(new GenreCountDTO("Action", 5L));
        List<GenreCountDTO> genresSeries = List.of(new GenreCountDTO("Drama", 2L));

        UserResponseDTO result = userMapper.userToUserResponseDto(user, 500L, 90L, 8L, genresMovies, genresSeries, 12L, 7L);
```

and further down:

```java
        assertThat(result.genreCountsMovies()).isEqualTo(genresMovies);
        assertThat(result.genreCountsSeries()).isEqualTo(genresSeries);
```

Apply the same rename to the `userToPublicUserProfileDto` test (the `genresEpisodes`/`genresMovies`
pair around lines 199-231): rename the local variable and its assertion the same way.

### Step 3: Update the controller integration test assertions

In `UserControllerIntegrationTest.java`, three `jsonPath` assertions currently reference
`$.genreCountsEpisodes` (lines ~130, ~704, ~733-734). Rename each to `$.genreCountsSeries`:

```java
                .andExpect(jsonPath("$.genreCountsSeries").isEmpty());
```

(line ~130, inside the "Should Include Watch Time Stats" test)

```java
                .andExpect(jsonPath("$.genreCountsSeries").isEmpty());
```

(line ~704, inside the public-profile test)

```java
                .andExpect(jsonPath("$.genreCountsSeries[*].genre", org.hamcrest.Matchers.containsInAnyOrder("Drama", "Action")))
                .andExpect(jsonPath("$.genreCountsSeries[*].count", org.hamcrest.Matchers.containsInAnyOrder(1, 1)));
```

(lines ~733-734)

Also update the first integration test at line ~128-130 (`$.genreCountsMovies[0].genre` assertion)
— no rename needed there since `genreCountsMovies` keeps its name, but note that once the service
change lands in Step 5, a rewatch fixture would now assert `count` reflecting entry count, not
distinct titles. This specific test only logs one movie once, so its existing assertion
(`$.genreCountsMovies[0].count` value `1`) stays correct unchanged — no edit needed for that
assertion.

### Step 4: Run the tests to verify they fail

Run: `mvnw.cmd test "-Dtest=UserMapperTest,UserServiceImplTest"`
Expected: compile errors (`genreCountsSeries()` doesn't exist on `UserResponseDTO`/
`PublicUserProfileDTO` yet, `countEntriesByGenreAndUserIdForMovies` stub target type mismatch with
`UserMapper.userToUserResponseDto`'s current parameter name doesn't matter for compilation but the
`genreCountsSeries()` accessor call must fail to compile).

### Step 5: Implement the rename and query switch

In `UserResponseDTO.java`, rename the field (keep its position):

```java
        List<GenreCountDTO> genreCountsMovies,
        List<GenreCountDTO> genreCountsSeries,
```

In `PublicUserProfileDTO.java`, same rename:

```java
        List<GenreCountDTO> genreCountsMovies,
        List<GenreCountDTO> genreCountsSeries,
```

In `UserMapper.java`, rename the `genreCountsEpisodes` parameter to `genreCountsSeries` on both
methods:

```java
    UserResponseDTO userToUserResponseDto(User user, long totalMinutesWatched, long minutesWatchedLast30Days,
            long totalTheaterVisits, List<GenreCountDTO> genreCountsMovies, List<GenreCountDTO> genreCountsSeries,
            long followersCount, long followingCount);

    UserPreviewDTO userToUserPreviewDto(User user);

    PublicUserDTO userToPublicUserDto(User user);

    PublicUserProfileDTO userToPublicUserProfileDto(User user, long totalMinutesWatched, long minutesWatchedLast30Days,
            long totalTheaterVisits, List<GenreCountDTO> genreCountsMovies, List<GenreCountDTO> genreCountsSeries,
            long followersCount, long followingCount);
```

In `UserServiceImpl.java`, rename `ProfileStats.genreCountsEpisodes` to `genreCountsSeries` and
switch the movies query in `computeProfileStats`:

```java
        List<GenreCountDTO> genreCountsMovies = toGenreCountDtos(
                diaryEntryRepository.countEntriesByGenreAndUserIdForMovies(userId));
        List<GenreCountDTO> genreCountsSeries = toGenreCountDtos(
                diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeries(userId));
```

and the record:

```java
    private record ProfileStats(long totalMinutesWatched, long minutesWatchedLast30Days, long totalTheaterVisits,
            List<GenreCountDTO> genreCountsMovies, List<GenreCountDTO> genreCountsSeries, long followersCount,
            long followingCount) {
        static final ProfileStats EMPTY = new ProfileStats(0L, 0L, 0L, List.of(), List.of(), 0L, 0L);
    }
```

Update the two call sites (`getCurrentUser`/`toUserResponseDto` area, both already passing
`stats.genreCountsMovies()`/`stats.genreCountsEpisodes()` positionally) to use
`stats.genreCountsSeries()` instead of `stats.genreCountsEpisodes()` — same two spots the original
split commit touched (`userMapper.userToPublicUserProfileDto(...)` and
`toUserResponseDto`/`userMapper.userToUserResponseDto(...)`).

### Step 6: Run the tests to verify they pass

Run: `mvnw.cmd test "-Dtest=UserMapperTest,UserServiceImplTest"`
Expected: `BUILD SUCCESS`.

Run: `mvnw.cmd test "-Dtest=UserControllerIntegrationTest"`
Expected: `BUILD SUCCESS` (Docker required — Testcontainers-backed `@SpringBootTest`).

### Step 7: Commit

```bash
git add src/main/java/com/watchwise/watchwise_api/user src/test/java/com/watchwise/watchwise_api/user
git commit -m "feat(user): rename genreCountsEpisodes to genreCountsSeries and count movie rewatches by entry"
```

---

## Task 3: `/summary?type=` — movie branch switch

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java`

**Interfaces:**
- Consumes: `DiaryEntryRepository.countEntriesByGenreAndUserIdForMovies(UUID)` (Task 1).
- No new produced signature — `SummaryResponseDTO.genreCounts` keeps its name and type
  (`List<GenreCountDTO>`), only its MOVIE-branch data source changes.

### Step 1: Flip the failing test

In `SummaryServiceImplTest.java`, the test at lines 215-225
(`shouldUseTheMovieGenreQueryWhenTypeIsMovie`) currently stubs
`countDistinctTitlesByGenreAndUserIdForMovies` — that method no longer exists after Task 1, so this
test fails to compile. Change it to:

```java
    @Test
    @DisplayName("[getSummary] Should Use The Movie Genre Query - When Type Is MOVIE")
    void shouldUseTheMovieGenreQueryWhenTypeIsMovie() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.countEntriesByGenreAndUserIdForMovies(lucasId))
                .thenReturn(List.of(genreCount("Drama", 3L)));

        SummaryResponseDTO result = summaryService.getSummary(lucasId, lucasId, ContentType.MOVIE);

        assertThat(result.genreCounts()).containsExactly(new GenreCountDTO("Drama", 3L));
    }
```

Leave `shouldUseTheSeriesGenreQueryWhenTypeIsSeries` (lines 227-237) untouched — its query
(`countDistinctTitlesByGenreAndUserIdForSeries`) doesn't change.

### Step 2: Run to verify it fails

Run: `mvnw.cmd test "-Dtest=SummaryServiceImplTest#shouldUseTheMovieGenreQueryWhenTypeIsMovie"`
Expected: FAIL — `computeGenreCounts` still calls the old query, so the new stub is never hit and
`result.genreCounts()` comes back empty.

### Step 3: Implement

In `SummaryServiceImpl.java`, `computeGenreCounts` (around line 489-495):

```java
    private List<GenreCountDTO> computeGenreCounts(UUID userId, ContentType type) {
        List<DiaryEntryRepository.GenreCount> rows = type == ContentType.MOVIE
                ? diaryEntryRepository.countEntriesByGenreAndUserIdForMovies(userId)
                : diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeries(userId);

        return rows.stream().map(row -> new GenreCountDTO(row.getGenre(), row.getCount())).toList();
    }
```

(Only the MOVIE branch's method name changes from `countDistinctTitlesByGenreAndUserIdForMovies` to
`countEntriesByGenreAndUserIdForMovies`.)

### Step 4: Run to verify it passes

Run: `mvnw.cmd test "-Dtest=SummaryServiceImplTest"`
Expected: `BUILD SUCCESS`.

### Step 5: Commit

```bash
git add src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java
git commit -m "fix(summary): count movie rewatches by entry in /summary genreCounts"
```

---

## Task 4: Month/Year in Review — series branch switch

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java`

**Interfaces:**
- Consumes: `DiaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(UUID, LocalDate, LocalDate)` (Task 1).
- No renamed field — `MonthInReviewResponseDTO.genreCounts` and `YearInReviewResponseDTO.genreCounts`
  keep their names; only the SERIES-branch data source changes.

Neither `getMonthInReview` nor `getYearInReview` has an existing genre-count-specific unit test in
`SummaryServiceImplTest` today (confirmed: only `getSummary`'s and `getHomeSummary`'s branches are
covered there) — this task adds the first ones for both. Confirmed signatures (from
`SummaryService.java`): `getMonthInReview(UUID viewerId, UUID userId, ContentType type, YearMonth month)`
and `getYearInReview(UUID viewerId, UUID userId, ContentType type, Integer year)`; existing tests for
both (e.g. `shouldThrowNotFoundExceptionWhenUserDoesNotExistForMonthInReview`) already call them with
`userRepository.findById(lucasId)` stubbed and `YearMonth.of(2026, 8)`/plain `int` year literals, no
`any()`-matched date-range stubbing needed beyond the genre query itself since the service computes
`start`/`end` internally from the `month`/`year` argument.

### Step 1: Write the failing tests

Add to `SummaryServiceImplTest.java`, near the other `getMonthInReview` tests:

```java
    @Test
    @DisplayName("[getMonthInReview] Should Use The Distinct Series Genre Query - When Type Is SERIES")
    void shouldUseTheDistinctSeriesGenreQueryInMonthInReviewWhenTypeIsSeries() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(eq(lucasId), any(), any()))
                .thenReturn(List.of(genreCount("Sci-Fi", 2L)));

        MonthInReviewResponseDTO result = summaryService.getMonthInReview(lucasId, lucasId, ContentType.SERIES, YearMonth.of(2026, 8));

        assertThat(result.genreCounts()).containsExactly(new GenreCountDTO("Sci-Fi", 2L));
        verify(diaryEntryRepository, never()).countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(any(), any(), any());
    }
```

```java
    @Test
    @DisplayName("[getYearInReview] Should Use The Distinct Series Genre Query - When Type Is SERIES")
    void shouldUseTheDistinctSeriesGenreQueryInYearInReviewWhenTypeIsSeries() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(eq(lucasId), any(), any()))
                .thenReturn(List.of(genreCount("Sci-Fi", 4L)));

        YearInReviewResponseDTO result = summaryService.getYearInReview(lucasId, lucasId, ContentType.SERIES, 2026);

        assertThat(result.genreCounts()).containsExactly(new GenreCountDTO("Sci-Fi", 4L));
    }
```

Both signatures are confirmed against `SummaryService.java`:
`getMonthInReview(UUID viewerId, UUID userId, ContentType type, YearMonth month)` and
`getYearInReview(UUID viewerId, UUID userId, ContentType type, Integer year)` — no adjustment needed.

### Step 3: Run to verify they fail

Run: `mvnw.cmd test "-Dtest=SummaryServiceImplTest#shouldUseTheDistinctSeriesGenreQueryInMonthInReviewWhenTypeIsSeries+shouldUseTheDistinctSeriesGenreQueryInYearInReviewWhenTypeIsSeries"`
Expected: FAIL — both methods still call `countEntriesByGenreAndUserIdForSeriesAndWatchedDateBetween`
(compile error once Task 1 has removed it — confirm Task 1 is merged first) and the stubbed distinct
query is never hit.

### Step 4: Implement

In `SummaryServiceImpl.java`, `getMonthInReview` (around line 228-231):

```java
        List<GenreCountDTO> genreCounts = (type == ContentType.MOVIE
                ? diaryEntryRepository.countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(userId, start, end)
                : diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(userId, start, end))
                .stream().map(row -> new GenreCountDTO(row.getGenre(), row.getCount())).toList();
```

`getYearInReview` (around line 305-308), same change:

```java
        List<GenreCountDTO> genreCounts = (type == ContentType.MOVIE
                ? diaryEntryRepository.countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(userId, start, end)
                : diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(userId, start, end))
                .stream().map(row -> new GenreCountDTO(row.getGenre(), row.getCount())).toList();
```

(Only the SERIES branch's method name changes in both.)

### Step 5: Run to verify they pass

Run: `mvnw.cmd test "-Dtest=SummaryServiceImplTest"`
Expected: `BUILD SUCCESS`.

### Step 6: Commit

```bash
git add src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java
git commit -m "fix(summary): count series once per genre in month/year in review instead of per episode"
```

---

## Task 5: All-Time Stats — rename and re-wire

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/AllTimeStatsResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java`

**Interfaces:**
- Consumes: `DiaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeries(UUID)` (already
  exists, unchanged from Task 1).
- Produces: `AllTimeStatsResponseDTO.genreCountsSeries()` — replaces `genreCountsEpisodes()`, same
  position, same type.

No existing test in `SummaryServiceImplTest` exercises `getAllTimeStats`'s genre fields directly —
this task adds the first one.

### Step 1: Write the failing test

```java
    @Test
    @DisplayName("[getAllTimeStats] Should Use The Distinct Series Genre Query")
    void shouldUseTheDistinctSeriesGenreQueryInAllTimeStats() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeries(lucasId))
                .thenReturn(List.of(genreCount("Sci-Fi", 6L)));

        AllTimeStatsResponseDTO result = summaryService.getAllTimeStats(lucasId, lucasId);

        assertThat(result.genreCountsSeries()).containsExactly(new GenreCountDTO("Sci-Fi", 6L));
        verify(diaryEntryRepository, never()).countEntriesByGenreAndUserIdForSeries(any());
    }
```

Confirm `getAllTimeStats`'s actual parameter list against `SummaryServiceImpl.getAllTimeStats`
(`getAllTimeStats(UUID viewerId, UUID userId)`, already confirmed during planning) before finalizing.

### Step 2: Run to verify it fails

Run: `mvnw.cmd test "-Dtest=SummaryServiceImplTest#shouldUseTheDistinctSeriesGenreQueryInAllTimeStats"`
Expected: compile error (`genreCountsSeries()` doesn't exist on `AllTimeStatsResponseDTO` yet).

### Step 3: Implement

In `AllTimeStatsResponseDTO.java`, rename the field:

```java
        List<GenreCountDTO> genreCountsMovies,
        List<GenreCountDTO> genreCountsSeries,
```

In `SummaryServiceImpl.java`, `getAllTimeStats` (around lines 355-358 and the `new
AllTimeStatsResponseDTO(...)` construction at line 370):

```java
        List<GenreCountDTO> genreCountsMovies = diaryEntryRepository.countEntriesByGenreAndUserIdForMovies(userId)
                .stream().map(row -> new GenreCountDTO(row.getGenre(), row.getCount())).toList();
        List<GenreCountDTO> genreCountsSeries = diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeries(userId)
                .stream().map(row -> new GenreCountDTO(row.getGenre(), row.getCount())).toList();
```

Update the `new AllTimeStatsResponseDTO(...)` call to pass `genreCountsSeries` where it previously
passed `genreCountsEpisodes` (same positional slot).

### Step 4: Run to verify it passes

Run: `mvnw.cmd test "-Dtest=SummaryServiceImplTest"`
Expected: `BUILD SUCCESS`.

### Step 5: Commit

```bash
git add src/main/java/com/watchwise/watchwise_api/summary src/test/java/com/watchwise/watchwise_api/summary
git commit -m "feat(summary): rename genreCountsEpisodes to genreCountsSeries in all-time stats"
```

---

## Task 6: Home Summary — rename and re-wire

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/HomeSummaryResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java`

**Interfaces:**
- Consumes: `DiaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(UUID, LocalDate, LocalDate)` (Task 1).
- Produces: `HomeSummaryResponseDTO.genreCountsSeriesLast30Days()` — replaces
  `genreCountsEpisodesLast30Days()`, same position, same type.

### Step 1: Flip the failing test

In `SummaryServiceImplTest.java`, `shouldReturnTotalsNextEpisodesRollingStatsAndGenreCountsForHomeSummary`
(around lines 373-399) currently stubs
`countEntriesByGenreAndUserIdForSeriesAndWatchedDateBetween` and asserts
`result.genreCountsEpisodesLast30Days()`. Update both:

```java
        when(diaryEntryRepository.countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(
                eq(lucasId), any(), any())).thenReturn(List.of(genreCount("Action", 2L)));
        when(diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(
                eq(lucasId), any(), any())).thenReturn(List.of(genreCount("Drama", 5L)));
```

```java
        assertThat(result.genreCountsMoviesLast30Days()).containsExactly(new GenreCountDTO("Action", 2));
        assertThat(result.genreCountsSeriesLast30Days()).containsExactly(new GenreCountDTO("Drama", 5));
```

(Keep the rest of the test — totals, next episodes, rolling stats assertions — unchanged; only the
series-genre stub's method name and the final assertion's accessor name change.)

### Step 2: Run to verify it fails

Run: `mvnw.cmd test "-Dtest=SummaryServiceImplTest#shouldReturnTotalsNextEpisodesRollingStatsAndGenreCountsForHomeSummary"`
Expected: compile error (`genreCountsSeriesLast30Days()` doesn't exist yet).

### Step 3: Implement

In `HomeSummaryResponseDTO.java`, rename the field:

```java
        List<GenreCountDTO> genreCountsMoviesLast30Days,
        List<GenreCountDTO> genreCountsSeriesLast30Days,
```

In `SummaryServiceImpl.java`, `getHomeSummary` (around lines 149-161):

```java
        List<GenreCountDTO> genreCountsMoviesLast30Days = diaryEntryRepository
                .countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(userId, windowStart, windowEnd).stream()
                .map(row -> new GenreCountDTO(row.getGenre(), row.getCount())).toList();
        List<GenreCountDTO> genreCountsSeriesLast30Days = diaryEntryRepository
                .countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(userId, windowStart, windowEnd).stream()
                .map(row -> new GenreCountDTO(row.getGenre(), row.getCount())).toList();
```

Update the `new HomeSummaryResponseDTO(...)` construction to pass `genreCountsSeriesLast30Days`
where it previously passed `genreCountsEpisodesLast30Days` (same positional slot).

### Step 4: Run to verify it passes

Run: `mvnw.cmd test "-Dtest=SummaryServiceImplTest"`
Expected: `BUILD SUCCESS`.

### Step 5: Commit

```bash
git add src/main/java/com/watchwise/watchwise_api/summary src/test/java/com/watchwise/watchwise_api/summary
git commit -m "feat(summary): rename genreCountsEpisodesLast30Days to genreCountsSeriesLast30Days and count series once per watch window"
```

---

## Task 7: Docs — `openapi.yaml`, `business-rules.md`, `progress.md`

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

No tests — documentation only. Do not `git add`/commit these (this repo's `docs/` is gitignored and
this repo's convention is to never stage anything under it).

### Step 1: Update `openapi.yaml` — `WatchTimeStats` schema (profile fields, ~line 2465-2504)

Replace the `genreCountsMovies`/`genreCountsEpisodes` block with:

```yaml
        genreCountsMovies:
          type: array
          description: >
            Quantidade de DiaryEntry de MOVIE por gênero (all-time, sem
            recorte de tempo), ordenado do maior pro menor. Cada rewatch
            soma de novo. Mesma semântica de AllTimeStats.genreCountsMovies.
          items:
            type: object
            properties:
              genre: { type: string }
              count: { type: integer }
        genreCountsSeries:
          type: array
          description: >
            Quantidade de SERIES distintas iniciadas por gênero (all-time,
            sem recorte de tempo, não precisa ter sido concluída),
            ordenado do maior pro menor. Um rewatch de uma série já
            iniciada não soma de novo. Gênero de um episódio é resolvido a
            partir do Content SERIES com o mesmo seriesTmdbId (episódio
            não carrega genres próprio). Mesma semântica de
            AllTimeStats.genreCountsSeries.
          items:
            type: object
            properties:
              genre: { type: string }
              count: { type: integer }
```

### Step 2: Update `openapi.yaml` — `Summary` schema (~line 2773-2782)

Replace the `genreCounts` description:

```yaml
        genreCounts:
          type: array
          description: >
            Do type pedido. MOVIE: quantidade de DiaryEntry por gênero
            (rewatch soma). SERIES: quantidade de séries distintas
            iniciadas por gênero (rewatch não soma de novo) — ver
            WatchTimeStats.genreCountsMovies/genreCountsSeries.
          items:
            type: object
            properties:
              genre: { type: string }
              count: { type: integer }
```

### Step 3: Update `openapi.yaml` — `MonthInReview` schema (~line 2833-2836)

```yaml
        genreCounts:
          type: array
          description: >
            Do type pedido, escopado ao mês. MOVIE: quantidade de
            DiaryEntry por gênero no mês (rewatch soma). SERIES:
            quantidade de séries distintas com pelo menos uma DiaryEntry
            no mês, por gênero (rewatch de uma série já contada no mês
            não soma de novo).
          items: { type: object, properties: { genre: { type: string }, count: { type: integer } } }
```

### Step 4: Update `openapi.yaml` — `YearInReview` schema (~line 2889-2891)

```yaml
        genreCounts:
          type: array
          description: Mesma regra de MonthInReview.genreCounts, escopado pro ano pedido.
          items: { type: object, properties: { genre: { type: string }, count: { type: integer } } }
```

### Step 5: Update `openapi.yaml` — `AllTimeStats` schema (~line 2936-2941)

```yaml
        genreCountsMovies:
          type: array
          description: Mesma regra de WatchTimeStats.genreCountsMovies, sem recorte de tempo.
          items: { type: object, properties: { genre: { type: string }, count: { type: integer } } }
        genreCountsSeries:
          type: array
          description: Mesma regra de WatchTimeStats.genreCountsSeries, sem recorte de tempo.
          items: { type: object, properties: { genre: { type: string }, count: { type: integer } } }
```

### Step 6: Update `openapi.yaml` — `HomeSummary` schema (~line 3245-3251)

```yaml
        genreCountsMoviesLast30Days:
          type: array
          description: Mesma regra de MonthInReview.genreCounts pra MOVIE, mas em janela rolante de 30 dias.
          items: { type: object, properties: { genre: { type: string }, count: { type: integer } } }
        genreCountsSeriesLast30Days:
          type: array
          description: Mesma regra de MonthInReview.genreCounts pra SERIES, mas em janela rolante de 30 dias.
          items: { type: object, properties: { genre: { type: string }, count: { type: integer } } }
```

### Step 7: Update `business-rules.md` — § User (~lines 424-442)

Rewrite the `genreCountsMovies`/`genreCountsEpisodes` paragraph. Replace the sentence starting
"`genreCountsMovies`/`genreCountsEpisodes` contam `MOVIE`/`SERIES` distintos por gênero..." through
the end of that bullet (ending "...nem na primeira vez nem num reassistir).") with:

```markdown
  `genreCountsMovies` (`DiaryEntryRepository.countEntriesByGenreAndUserIdForMovies`) conta cada
  `DiaryEntry` `MOVIE` por gênero, all-time — um rewatch soma de novo. `genreCountsSeries`
  (renomeado de `genreCountsEpisodes` em 2026-09-03; `DiaryEntryRepository.
  countDistinctTitlesByGenreAndUserIdForSeries`) conta `SERIES` distintas **iniciadas** por gênero,
  all-time — não precisa ter sido concluída, mas um rewatch de uma série já iniciada não soma de
  novo; gênero de um `EPISODE` é resolvido via o `Content` `SERIES` do mesmo `seriesTmdbId`. Uma
  `DiaryEntry` logada diretamente sobre um `Content` tipo `SERIES` (via `POST /diary` com
  `content.type=SERIES`, sem passar por episódio nenhum) também conta em `genreCountsSeries`, lendo
  o gênero do próprio `Content` `SERIES`; dedupe entre o log direto e os `EPISODE`s da mesma série
  usa a `tmdbId` da `SERIES`/`seriesTmdbId` do episódio como a mesma chave, então a série nunca é
  contada duas vezes mesmo logada dos dois jeitos. Antes de 2026-09-03, ambos os campos deduplicavam
  por título distinto (um rewatch de filme nunca somava de novo no perfil); a mudança unificou a
  semântica de filme com a já usada por `AllTimeStats`/`MonthInReview`/`YearInReview`/`HomeSummary`
  (ver § Summary abaixo), que já contavam filme por entrada mas ainda contavam série por episódio
  individual em vez de por série iniciada — as duas inconsistências foram corrigidas juntas.
```

### Step 8: Update `business-rules.md` — § Summary (~lines 1616-1628)

Replace the `genreCounts` bullet (starting "**`genreCounts` também usa `EPISODE` pra resolver...**")
with:

```markdown
- **`genreCounts` (Summary, MonthInReview, YearInReview) e `genreCountsMovies`/`genreCountsSeries`
  (AllTimeStats, HomeSummary's `...Last30Days` variants) share one rule since 2026-09-03**: MOVIE
  counts every `DiaryEntry` (rewatch sums), SERIES counts distinct series *started* — `EPISODE` or a
  direct `SERIES`-type log both count, a rewatch of an already-started series never sums again.
  Windowed variants (`MonthInReview`/`YearInReview`/`...Last30Days`) apply the same distinct-series
  rule scoped to `watchedDate BETWEEN` the window — any activity inside the window counts the series
  once, regardless of when it was actually first started. Before this change, the windowed and
  all-time "Episodes" fields counted every individual `EPISODE` `DiaryEntry` (a rewatched 10-episode
  season added +10 to a genre instead of +1), while the profile/`/summary` fields deduplicated by
  distinct title for both MOVIE and SERIES (a movie rewatch never added anything) — two different,
  undocumented-as-different behaviors under similarly named fields. Dedupe between a direct `SERIES`
  log and its `EPISODE`s uses the `tmdbId` of the `SERIES`/`seriesTmdbId` of the episode as the same
  key, so a series is never counted twice even when logged both ways.
  `countDistinctTitlesByGenreAndUserIdForSeries`/`...AndWatchedDateBetween` and
  `countEntriesByGenreAndUserIdForMovies`/`...AndWatchedDateBetween` remain four separate native
  queries (not parameterized into one) because the `JOIN` to resolve genre differs: movie reads
  `c.genres` directly, series resolves via the `Content` `SERIES` row sharing `seriesTmdbId` (an
  episode never carries `genres` of its own) or, for a direct `SERIES` log, from that `Content` row
  itself.
```

### Step 9: Update `business-rules.md` — § Summary Home (~lines 1650-1658)

In the bullet starting "**`GET /users/{userId}/summary/home` usa janela rolante...**", the sentence
"A tela Home reaproveita `countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween`/`...ForSeriesAndWatchedDateBetween` (mesmas queries do Month in Review)..." now names a removed query for the
series half. Replace that sentence with:

```markdown
  A tela Home reaproveita `countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween` (movies) e
  `countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween` (series) — mesmas queries do
  Month in Review — só trocando o range de datas; nenhuma query nova pros dois campos de gênero da
  Home especificamente (a query distinct-series windowed em si é nova em 2026-09-03, mas
  compartilhada com Month/Year in Review, não exclusiva da Home).
```

### Step 10: Add a `progress.md` entry for today

Append to (or create, if today has no section yet) the `## 2026-09-03` section in
`docs/context/progress.md`, following the file's existing chronological format (what/why/how), a
short paragraph covering: unified `genreCounts` semantics across profile, `/summary`,
month/year-in-review, all-time-stats, and home-summary (movie = count by `DiaryEntry`, series =
count distinct titles started); renamed `genreCountsEpisodes`/`genreCountsEpisodesLast30Days` to
`genreCountsSeries`/`genreCountsSeriesLast30Days` on `UserResponseDTO`, `PublicUserProfileDTO`,
`AllTimeStatsResponseDTO`, `HomeSummaryResponseDTO`; added
`countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween`, removed
`countDistinctTitlesByGenreAndUserIdForMovies`/`countEntriesByGenreAndUserIdForSeries`/`...AndWatchedDateBetween`.

---

## Task 8: Full verification

**Files:** none (verification only).

### Step 1: Run the full test suite

Run: `mvnw.cmd test`
Expected: `BUILD SUCCESS`, zero failures. Docker must be running (multiple `*RepositoryTest` and
`*ControllerIntegrationTest` classes use Testcontainers).

### Step 2: Confirm no leftover references to the removed/renamed identifiers

Run: `grep -rn "genreCountsEpisodes\|countDistinctTitlesByGenreAndUserIdForMovies\|countEntriesByGenreAndUserIdForSeries" src/`
Expected: no output (empty). If anything prints, it's a missed call site or test — fix it and rerun
Step 1.

### Step 3: Build the jar

Run: `mvnw.cmd clean package`
Expected: `BUILD SUCCESS` (this also re-runs the full test suite; redundant with Step 1 but confirms
the packaged build is clean).

No commit for this task — it's a verification checkpoint, not a code change.
