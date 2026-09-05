# Budget/Revenue/Production Companies/Crew/Videos in Content Details Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose `budget`, `revenue`, `productionCompanies`, job-filtered `crew`, and `videos` on `GET /contents/{contentId}/details` / `GET /contents/details`, sourced from TMDB via the existing `TmdbClient` full-details calls (no new HTTP calls — just wider `append_to_response`).

**Architecture:** Purely additive to the existing TMDB proxy (`TmdbClient` → `ContentDetailsServiceImpl` → `ContentDetailsDTO`). New fields ride on the same cached `getMovieFullDetails`/`getTvFullDetails` responses (`videos` added to `append_to_response`; `credits`/`aggregate_credits` already fetched, just not fully mapped yet). `SEASON`/`EPISODE` inherit all 5 new fields from the already-fetched `SERIES` `TmdbTvFullDetails`, mirroring how `genres`/`countries`/`creators` already work.

**Tech Stack:** Spring Boot 4.1 / Java 21, Jackson (`@JsonProperty`/`@JsonIgnoreProperties(ignoreUnknown = true)` records), JUnit 5 + Mockito + AssertJ, `MockRestServiceServer`.

## Global Constraints

- English-only code (classes/fields/DTOs) — see `CLAUDE.md`.
- No code comments — self-explanatory names only.
- `docs/` is gitignored in this repo — write/update `openapi.yaml`, `business-rules.md`, `business-rules-summary.md`, `progress.md` normally, but **never** `git add`/`git commit` anything under `docs/`. Only the `src/` changes get committed.
- Test method naming: `should<ExpectedBehavior>When<Condition>` (camelCase). `@DisplayName("[methodUnderTest] Should <behavior> - When <condition>")`.
- Allowed crew jobs (exact TMDB `job` strings): `Director`, `Screenplay`, `Executive Producer`, `Production Manager`, `First Assistant Director`, `Director of Photography`, `Supervising Art Director`.
- `budget`/`revenue` of `0` from TMDB means "not informed" → map to `null`. `SERIES`/`SEASON`/`EPISODE` always get `null` `budget`/`revenue` (TMDB's `/tv/{id}` doesn't return these fields).
- `SEASON`/`EPISODE` inherit `budget`, `revenue`, `productionCompanies`, `crew`, `videos` from the `SERIES`-type `TmdbTvFullDetails` already fetched for `genres`/`countries`/`creators` — no new TMDB call.
- Full spec: `docs/superpowers/specs/2026-09-01-content-details-budget-crew-videos-design.md`.

---

## Task 1: TMDB wire-format DTOs — crew, budget/revenue, production companies, videos

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCrewMember.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbAggregateCrewJob.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbAggregateCrewMember.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbProductionCompany.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbVideo.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbVideos.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCredits.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbAggregateCredits.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbMovieFullDetails.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbTvFullDetails.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClient.java`
- Test: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientCachingTest.java`
- Modify (fix compile after arity changes, no new tests): `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImplTest.java`
- Modify (fix compile after arity changes, no new tests): `src/test/java/com/watchwise/watchwise_api/content/controller/ContentControllerIntegrationTest.java`

**Interfaces:**
- Produces: `TmdbCredits(List<TmdbCastMember> cast, List<TmdbCrewMember> crew)`, `TmdbCrewMember(Integer id, String name, String job, String profilePath)`, `TmdbAggregateCredits(List<TmdbAggregateCastMember> cast, List<TmdbAggregateCrewMember> crew)`, `TmdbAggregateCrewMember(Integer id, String name, String profilePath, List<TmdbAggregateCrewJob> jobs)`, `TmdbAggregateCrewJob(String job)`, `TmdbProductionCompany(Integer id, String name, String logoPath, String originCountry)`, `TmdbVideo(String key, String name, String site, String type, Boolean official, String isoCode639_1, String publishedAt)`, `TmdbVideos(List<TmdbVideo> results)`. `TmdbMovieFullDetails` gains trailing `Long budget, Long revenue, List<TmdbProductionCompany> productionCompanies, TmdbVideos videos`. `TmdbTvFullDetails` gains trailing `List<TmdbProductionCompany> productionCompanies, TmdbVideos videos`. Both `TmdbClient.getMovieFullDetails`/`getTvFullDetails` now request `append_to_response` with `,videos` appended.
- Consumes: nothing new from other tasks (this is the foundation layer).

- [ ] **Step 1: Create the 6 new TMDB response records**

`src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCrewMember.java`:
```java
package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbCrewMember(
        Integer id,
        String name,
        String job,
        @JsonProperty("profile_path") String profilePath) {
}
```

`src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbAggregateCrewJob.java`:
```java
package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbAggregateCrewJob(String job) {
}
```

`src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbAggregateCrewMember.java`:
```java
package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbAggregateCrewMember(
        Integer id,
        String name,
        @JsonProperty("profile_path") String profilePath,
        List<TmdbAggregateCrewJob> jobs) {
}
```

`src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbProductionCompany.java`:
```java
package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbProductionCompany(
        Integer id,
        String name,
        @JsonProperty("logo_path") String logoPath,
        @JsonProperty("origin_country") String originCountry) {
}
```

`src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbVideo.java`:
```java
package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbVideo(
        String key,
        String name,
        String site,
        String type,
        Boolean official,
        @JsonProperty("iso_639_1") String isoCode639_1,
        @JsonProperty("published_at") String publishedAt) {
}
```

`src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbVideos.java`:
```java
package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbVideos(List<TmdbVideo> results) {
}
```

- [ ] **Step 2: Add `crew` to `TmdbCredits` and `TmdbAggregateCredits`**

Replace `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCredits.java` entirely with:
```java
package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbCredits(List<TmdbCastMember> cast, List<TmdbCrewMember> crew) {
}
```

Replace `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbAggregateCredits.java` entirely with:
```java
package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbAggregateCredits(List<TmdbAggregateCastMember> cast, List<TmdbAggregateCrewMember> crew) {
}
```

No source code anywhere directly constructs `new TmdbCredits(...)` (movie credits always arrive via Jackson deserialization), so this alone does not break any existing caller. `TmdbAggregateCredits` **is** constructed directly in one test — fixed in Step 6 below.

- [ ] **Step 3: Add `budget`/`revenue`/`productionCompanies`/`videos` to `TmdbMovieFullDetails`**

Replace `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbMovieFullDetails.java` entirely with:
```java
package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbMovieFullDetails(
        String id,
        String title,
        @JsonProperty("original_title") String originalTitle,
        String overview,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("backdrop_path") String backdropPath,
        @JsonProperty("release_date") String releaseDate,
        Integer runtime,
        List<TmdbGenre> genres,
        @JsonProperty("production_countries") List<TmdbProductionCountry> productionCountries,
        TmdbCredits credits,
        @JsonProperty("watch/providers") TmdbWatchProviders watchProviders,
        @JsonProperty("alternative_titles") TmdbMovieAlternativeTitles alternativeTitles,
        Long budget,
        Long revenue,
        @JsonProperty("production_companies") List<TmdbProductionCompany> productionCompanies,
        TmdbVideos videos) {
}
```

- [ ] **Step 4: Add `productionCompanies`/`videos` to `TmdbTvFullDetails`**

Replace `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbTvFullDetails.java` entirely with:
```java
package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbTvFullDetails(
        String id,
        String name,
        @JsonProperty("original_name") String originalName,
        String overview,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("backdrop_path") String backdropPath,
        @JsonProperty("first_air_date") String firstAirDate,
        @JsonProperty("episode_run_time") List<Integer> episodeRunTime,
        List<TmdbGenre> genres,
        @JsonProperty("production_countries") List<TmdbProductionCountry> productionCountries,
        @JsonProperty("created_by") List<TmdbCreator> createdBy,
        List<TmdbSeasonSummary> seasons,
        @JsonProperty("next_episode_to_air") TmdbNextEpisode nextEpisodeToAir,
        @JsonProperty("aggregate_credits") TmdbAggregateCredits aggregateCredits,
        @JsonProperty("watch/providers") TmdbWatchProviders watchProviders,
        @JsonProperty("alternative_titles") TmdbTvAlternativeTitles alternativeTitles,
        @JsonProperty("number_of_seasons") Integer numberOfSeasons,
        @JsonProperty("number_of_episodes") Integer numberOfEpisodes,
        @JsonProperty("production_companies") List<TmdbProductionCompany> productionCompanies,
        TmdbVideos videos) {
}
```

- [ ] **Step 5: Add `videos` to `append_to_response` in `TmdbClient`**

In `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClient.java`, change:
```java
    @Cacheable(cacheNames = "tmdbMovieFullDetails", key = "#tmdbId + '|' + #language", unless = "#result == null")
    public Optional<TmdbMovieFullDetails> getMovieFullDetails(String tmdbId, String language) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/movie/{id}")
                                .queryParam("append_to_response", "credits,watch/providers,alternative_titles")
                                .queryParam("language", language)
                                .build(tmdbId))
                        .retrieve()
                        .body(TmdbMovieFullDetails.class),
                "movie full details " + tmdbId);
    }
```
to:
```java
    @Cacheable(cacheNames = "tmdbMovieFullDetails", key = "#tmdbId + '|' + #language", unless = "#result == null")
    public Optional<TmdbMovieFullDetails> getMovieFullDetails(String tmdbId, String language) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/movie/{id}")
                                .queryParam("append_to_response", "credits,watch/providers,alternative_titles,videos")
                                .queryParam("language", language)
                                .build(tmdbId))
                        .retrieve()
                        .body(TmdbMovieFullDetails.class),
                "movie full details " + tmdbId);
    }
```

And change:
```java
    @Cacheable(cacheNames = "tmdbTvFullDetails", key = "#tmdbId + '|' + #language", unless = "#result == null")
    public Optional<TmdbTvFullDetails> getTvFullDetails(String tmdbId, String language) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/tv/{id}")
                                .queryParam("append_to_response", "aggregate_credits,watch/providers,alternative_titles")
                                .queryParam("language", language)
                                .build(tmdbId))
                        .retrieve()
                        .body(TmdbTvFullDetails.class),
                "tv full details " + tmdbId);
    }
```
to:
```java
    @Cacheable(cacheNames = "tmdbTvFullDetails", key = "#tmdbId + '|' + #language", unless = "#result == null")
    public Optional<TmdbTvFullDetails> getTvFullDetails(String tmdbId, String language) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/tv/{id}")
                                .queryParam("append_to_response", "aggregate_credits,watch/providers,alternative_titles,videos")
                                .queryParam("language", language)
                                .build(tmdbId))
                        .retrieve()
                        .body(TmdbTvFullDetails.class),
                "tv full details " + tmdbId);
    }
```
`getSeasonFullDetails`/`getEpisodeFullDetails` are unchanged.

- [ ] **Step 6: Fix the two direct `TmdbAggregateCredits` constructions in `ContentDetailsServiceImplTest`**

Both are inside `shouldReturnCastFromSeasonScopedAggregateCreditsNotTheSeriesWideOnesWhenContentIsASeason`. `TmdbAggregateCredits` went from 1 field (`cast`) to 2 (`cast`, `crew`) — append `, null` as the new trailing `crew` argument to each of the 2 occurrences:

```java
                new TmdbAggregateCredits(List.of(new TmdbAggregateCastMember(
                        17419, "Bryan Cranston", "/cranston.jpg",
                        List.of(new TmdbAggregateRole("Walter White")), 2))),
```
→
```java
                new TmdbAggregateCredits(List.of(new TmdbAggregateCastMember(
                        17419, "Bryan Cranston", "/cranston.jpg",
                        List.of(new TmdbAggregateRole("Walter White")), 2)), null),
```

```java
                new TmdbAggregateCredits(List.of(new TmdbAggregateCastMember(
                        17419, "Bryan Cranston", "/cranston.jpg",
                        List.of(new TmdbAggregateRole("Walter White")), 62))),
```
→
```java
                new TmdbAggregateCredits(List.of(new TmdbAggregateCastMember(
                        17419, "Bryan Cranston", "/cranston.jpg",
                        List.of(new TmdbAggregateRole("Walter White")), 62)), null),
```

- [ ] **Step 7: Fix every `new TmdbMovieFullDetails(...)` call site (arity 13 → 17)**

Run `grep -n "new TmdbMovieFullDetails(" src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImplTest.java src/test/java/com/watchwise/watchwise_api/content/controller/ContentControllerIntegrationTest.java` to confirm you have all 10 occurrences (7 in `ContentDetailsServiceImplTest`, 3 in `ContentControllerIntegrationTest`).

For **every** occurrence, append exactly `, null, null, null, null` (the new `budget, revenue, productionCompanies, videos` trailing args) right before the closing `)));` of that `new TmdbMovieFullDetails(...)` call. None of these tests assert on the new fields, so `null` is correct everywhere here — dedicated assertions come in Task 3.

Two worked examples (`ContentDetailsServiceImplTest`):

```java
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", "The Matrix", "The Matrix", "A hacker discovers reality is a simulation",
                "/poster.jpg", "/backdrop.jpg", "1999-03-31", 136,
                List.of(), List.of(), null, null, null)));
```
→
```java
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", "The Matrix", "The Matrix", "A hacker discovers reality is a simulation",
                "/poster.jpg", "/backdrop.jpg", "1999-03-31", 136,
                List.of(), List.of(), null, null, null,
                null, null, null, null)));
```

```java
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", "First", "First", null, null, null, null, null, List.of(), List.of(), null, null, null)));
        when(tmdbClient.getMovieFullDetails("604", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "604", "Second", "Second", null, null, null, null, null, List.of(), List.of(), null, null, null)));
```
→
```java
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", "First", "First", null, null, null, null, null, List.of(), List.of(), null, null, null,
                null, null, null, null)));
        when(tmdbClient.getMovieFullDetails("604", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "604", "Second", "Second", null, null, null, null, null, List.of(), List.of(), null, null, null,
                null, null, null, null)));
```

Apply the same trailing `, null, null, null, null` to the remaining occurrences: `shouldFallBackToAlternativeTitleForRegionWhenTranslatedTitleIsBlank`, `shouldFallBackToOriginalTitleWhenTranslatedTitleBlankAndNoAlternativeMatchesRegion`, `shouldFilterWatchProvidersByUserRegionWhenMultipleRegionsArePresent`, `shouldReturnEmptyWatchProvidersWhenUserRegionHasNoEntry` in `ContentDetailsServiceImplTest`, and `shouldReturnMovieDetailsResolvedViaTmdbClientWhenContentExists`, `shouldReturnOneEntryPerRequestedIdPreservingOrderWhenDetailsBatchCalled` (2 calls) in `ContentControllerIntegrationTest`.

- [ ] **Step 8: Fix every `new TmdbTvFullDetails(...)` call site (arity 18 → 20)**

Run `grep -n "new TmdbTvFullDetails(" src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImplTest.java` to confirm all 9 occurrences (all in this one file — `ContentControllerIntegrationTest` never constructs `TmdbTvFullDetails` directly).

For **every** occurrence, append exactly `, null, null` (the new `productionCompanies, videos` trailing args) right before the closing `)));`.

Worked example (`shouldReturnRuntimeTotalsNumberOfSeasonsEpisodesAndAiredEpisodeCountPerSeasonWhenContentIsASeries`):
```java
        when(tmdbClient.getTvFullDetails("1396", "en-US")).thenReturn(Optional.of(new TmdbTvFullDetails(
                "1396", "Breaking Bad", "Breaking Bad", null, null, null, "2008-01-20", null,
                List.of(), List.of(), null,
                List.of(new TmdbSeasonSummary(1, "Season 1", null, "2008-01-20", 2, null),
                        new TmdbSeasonSummary(2, "Season 2", null, "2009-03-08", 2, null),
                        new TmdbSeasonSummary(3, "Season 3", null, "2010-01-01", 1, null)),
                null, null, null, null, 3, 5)));
```
→
```java
        when(tmdbClient.getTvFullDetails("1396", "en-US")).thenReturn(Optional.of(new TmdbTvFullDetails(
                "1396", "Breaking Bad", "Breaking Bad", null, null, null, "2008-01-20", null,
                List.of(), List.of(), null,
                List.of(new TmdbSeasonSummary(1, "Season 1", null, "2008-01-20", 2, null),
                        new TmdbSeasonSummary(2, "Season 2", null, "2009-03-08", 2, null),
                        new TmdbSeasonSummary(3, "Season 3", null, "2010-01-01", 1, null)),
                null, null, null, null, 3, 5, null, null)));
```

Apply the same trailing `, null, null` to the remaining 8 occurrences, in: `shouldReturnNullAiredEpisodeCountWhenThatSeasonsFullDetailsFailedToFetch`, `shouldReturnLast3AlreadyAiredEpisodesSortedByDateDescWhenContentIsASeries`, `shouldExcludeSpecialsSeasonZeroFromRecentEpisodesAndRuntimeWhenContentIsASeries`, `shouldReturnNullTotalAndAverageRuntimeWhenNoEpisodeHasAKnownRuntime`, `shouldReturnCastFromSeasonScopedAggregateCreditsNotTheSeriesWideOnesWhenContentIsASeason` (the *outer* `TmdbTvFullDetails` call in this test — already edited in Step 6 for its nested `TmdbAggregateCredits`; here you're additionally appending `, null, null` at the very end of the *outer* call, after its already-updated `new TmdbAggregateCredits(..., null)` argument and the trailing `null, null, null, null` for `watchProviders`/`alternativeTitles`/`numberOfSeasons`/`numberOfEpisodes`), `shouldReturnRuntimeMinutesTotalRuntimeMinutesNumberOfEpisodesAndCreatorsWhenContentIsASeason`, `shouldAggregateGuestStarsAcrossEpisodesWithPerSeasonEpisodeCountWhenContentIsASeason`, `shouldParseGuestStarsAndReuseSeriesCastWhenContentIsAnEpisode`.

- [ ] **Step 9: Compile to confirm all call sites are fixed**

Run: `mvnw.cmd test-compile`
Expected: BUILD SUCCESS, no compilation errors about constructor arity.

- [ ] **Step 10: Write the new `TmdbClientTest` cases**

Add these test methods to `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientTest.java` (inside the class, e.g. after `shouldParseGuestStarsWhenEpisodeFullDetailsCalledWithoutAppend`):

```java
    @Test
    @DisplayName("[getMovieFullDetails] Should Request Videos Appended - When Called")
    void shouldRequestVideosAppendedWhenMovieFullDetailsCalled() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": "603", "title": "The Matrix"}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbMovieFullDetails> result = tmdbClient.getMovieFullDetails("603", "en-US");

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("[getMovieFullDetails] Should Parse Budget Revenue Production Companies Crew And Videos - When TMDB Responds")
    void shouldParseBudgetRevenueProductionCompaniesCrewAndVideosWhenMovieFullDetailsResponds() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": "603", "title": "The Matrix", "budget": 63000000, "revenue": 463500000,
                         "production_companies": [{"id": 79, "name": "Village Roadshow Pictures", "logo_path": "/village.png", "origin_country": "US"}],
                         "credits": {"cast": [], "crew": [
                            {"id": 10, "name": "Lana Wachowski", "job": "Director", "profile_path": "/lana.jpg"},
                            {"id": 11, "name": "Best Boy Grip", "job": "Best Boy Grip", "profile_path": null}]},
                         "videos": {"results": [
                            {"key": "vKQi3bBA1y8", "name": "Trailer", "site": "YouTube", "type": "Trailer",
                             "official": true, "iso_639_1": "en", "published_at": "1999-03-01T00:00:00.000Z"}]}}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbMovieFullDetails> result = tmdbClient.getMovieFullDetails("603", "en-US");

        assertThat(result).isPresent();
        assertThat(result.get().budget()).isEqualTo(63000000L);
        assertThat(result.get().revenue()).isEqualTo(463500000L);
        assertThat(result.get().productionCompanies()).extracting("id", "name", "logoPath", "originCountry")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(79, "Village Roadshow Pictures", "/village.png", "US"));
        assertThat(result.get().credits().crew()).extracting("id", "name", "job")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(10, "Lana Wachowski", "Director"),
                        org.assertj.core.groups.Tuple.tuple(11, "Best Boy Grip", "Best Boy Grip"));
        assertThat(result.get().videos().results()).extracting("key", "name", "site", "type", "official", "isoCode639_1")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("vKQi3bBA1y8", "Trailer", "YouTube", "Trailer", true, "en"));
        assertThat(result.get().videos().results().get(0).publishedAt()).isEqualTo("1999-03-01T00:00:00.000Z");
    }

    @Test
    @DisplayName("[getTvFullDetails] Should Request Videos Appended - When Called")
    void shouldRequestVideosAppendedWhenTvFullDetailsCalled() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396?append_to_response=aggregate_credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": "1396", "name": "Breaking Bad"}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbTvFullDetails> result = tmdbClient.getTvFullDetails("1396", "en-US");

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("[getTvFullDetails] Should Parse Production Companies And Aggregate Crew Jobs - When TMDB Responds")
    void shouldParseProductionCompaniesAndAggregateCrewJobsWhenTvFullDetailsResponds() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396?append_to_response=aggregate_credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": "1396", "name": "Breaking Bad",
                         "production_companies": [{"id": 11073, "name": "Sony Pictures Television", "logo_path": "/sony.png", "origin_country": "US"}],
                         "aggregate_credits": {"cast": [], "crew": [
                            {"id": 66633, "name": "Vince Gilligan", "profile_path": "/vince.jpg",
                             "jobs": [{"job": "Director"}, {"job": "Executive Producer"}]},
                            {"id": 99999, "name": "Random Grip", "profile_path": null,
                             "jobs": [{"job": "Grip"}]}]}}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbTvFullDetails> result = tmdbClient.getTvFullDetails("1396", "en-US");

        assertThat(result).isPresent();
        assertThat(result.get().productionCompanies()).extracting("id", "name")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(11073, "Sony Pictures Television"));
        assertThat(result.get().aggregateCredits().crew()).extracting("id", "name")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(66633, "Vince Gilligan"),
                        org.assertj.core.groups.Tuple.tuple(99999, "Random Grip"));
        assertThat(result.get().aggregateCredits().crew().get(0).jobs()).extracting("job")
                .containsExactly("Director", "Executive Producer");
    }
```

Update the existing `shouldRequestCreditsWatchProvidersAndAlternativeTitlesAppendedWhenCalled`, `shouldReturnEmptyWhenMovieFullDetailsFailsTwiceInARow` and `shouldRequestAggregateCreditsWatchProvidersAndAlternativeTitlesAppendedWhenCalled` tests: every `requestTo("https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles&language=en-US")` becomes `requestTo("https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles,videos&language=en-US")`, and the tv one gets `,videos` appended the same way.

- [ ] **Step 11: Update `TmdbClientCachingTest`'s 4 URL assertions**

In `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientCachingTest.java`, every occurrence of:
- `"https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles&language=en-US"` → `"https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles,videos&language=en-US"` (2 occurrences: `shouldNotThrowAndShouldCacheWhenMovieFullDetailsRespondsSuccessfully`, `shouldNotThrowAndShouldNotCacheWhenMovieFullDetailsFailsTwiceInARow` — the second one appears twice in that same test).
- `"https://api.themoviedb.org/3/tv/1396?append_to_response=aggregate_credits,watch/providers,alternative_titles&language=en-US"` → `"https://api.themoviedb.org/3/tv/1396?append_to_response=aggregate_credits,watch/providers,alternative_titles,videos&language=en-US"` (2 occurrences: `shouldNotThrowAndShouldCacheWhenTvFullDetailsRespondsSuccessfully`, `shouldNotThrowAndShouldNotCacheWhenTvFullDetailsFailsTwiceInARow` — also appears twice).

- [ ] **Step 12: Run the full test suite for this task**

Run: `mvnw.cmd test "-Dtest=TmdbClientTest,TmdbClientCachingTest,ContentDetailsServiceImplTest,ContentControllerIntegrationTest"`
Expected: BUILD SUCCESS, all tests pass (`ContentControllerIntegrationTest` requires Docker running for Testcontainers).

- [ ] **Step 13: Commit**

```bash
git add src/main/java/com/watchwise/watchwise_api/common/tmdb src/test/java/com/watchwise/watchwise_api/common/tmdb src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImplTest.java src/test/java/com/watchwise/watchwise_api/content/controller/ContentControllerIntegrationTest.java
git commit -m "feat(content): parse crew, budget, revenue, production companies and videos from TMDB"
```

---

## Task 2: `content/dto` — CrewMemberDTO, ProductionCompanyDTO, VideoDTO, and 5 new `ContentDetailsDTO` fields

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/CrewMemberDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/ProductionCompanyDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/VideoDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentDetailsDTO.java`

**Interfaces:**
- Consumes: nothing (pure DTOs).
- Produces: `CrewMemberDTO(Integer id, String name, String profilePath, List<String> jobs)`, `ProductionCompanyDTO(Integer id, String name, String logoPath, String originCountry)`, `VideoDTO(String key, String name, String site, String type, Boolean official, String language, Instant publishedAt)`. `ContentDetailsDTO` gains 5 trailing fields, in this exact order: `Long budget, Long revenue, List<ProductionCompanyDTO> productionCompanies, List<CrewMemberDTO> crew, List<VideoDTO> videos`. Task 3 constructs `ContentDetailsDTO` with these 5 args.

- [ ] **Step 1: Create the 3 new content DTOs**

`src/main/java/com/watchwise/watchwise_api/content/dto/CrewMemberDTO.java`:
```java
package com.watchwise.watchwise_api.content.dto;

import java.util.List;

public record CrewMemberDTO(Integer id, String name, String profilePath, List<String> jobs) {
}
```

`src/main/java/com/watchwise/watchwise_api/content/dto/ProductionCompanyDTO.java`:
```java
package com.watchwise.watchwise_api.content.dto;

public record ProductionCompanyDTO(Integer id, String name, String logoPath, String originCountry) {
}
```

`src/main/java/com/watchwise/watchwise_api/content/dto/VideoDTO.java`:
```java
package com.watchwise.watchwise_api.content.dto;

import java.time.Instant;

public record VideoDTO(String key, String name, String site, String type, Boolean official, String language, Instant publishedAt) {
}
```

- [ ] **Step 2: Add the 5 new fields to `ContentDetailsDTO`**

Replace `src/main/java/com/watchwise/watchwise_api/content/dto/ContentDetailsDTO.java` entirely with:
```java
package com.watchwise.watchwise_api.content.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ContentDetailsDTO(
        UUID contentId,
        ContentType type,
        String title,
        String overview,
        String posterPath,
        String backdropPath,
        LocalDate releaseDate,
        Integer runtimeMinutes,
        Integer totalRuntimeMinutes,
        Integer numberOfSeasons,
        Integer numberOfEpisodes,
        List<String> genres,
        List<String> countries,
        List<CastMemberDTO> cast,
        List<CastMemberDTO> guestStars,
        List<CreatorDTO> creators,
        List<WatchProviderDTO> watchProviders,
        List<SeasonSummaryDTO> seasons,
        List<EpisodeSummaryDTO> episodes,
        List<EpisodeSummaryDTO> recentEpisodes,
        Long budget,
        Long revenue,
        List<ProductionCompanyDTO> productionCompanies,
        List<CrewMemberDTO> crew,
        List<VideoDTO> videos) {
}
```

This will not compile on its own yet — `ContentDetailsServiceImpl`'s 4 `new ContentDetailsDTO(...)` calls need the 5 new trailing args, which Task 3 provides. Do not run the build after this step in isolation; Steps 2 and the whole of Task 3 land together.

- [ ] **Step 3: Commit together with Task 3**

This task has no independent green build (see note above) — its commit happens as part of Task 3's Step 6.

---

## Task 3: `ContentDetailsServiceImpl` — crew filtering, and MOVIE/SERIES wiring for all 5 new fields

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImplTest.java`

**Interfaces:**
- Consumes: `TmdbCredits.crew()`, `TmdbAggregateCredits.crew()`, `TmdbMovieFullDetails.budget()/revenue()/productionCompanies()/videos()`, `TmdbTvFullDetails.productionCompanies()/videos()` (Task 1); `ContentDetailsDTO`'s 5 new trailing fields, `CrewMemberDTO`, `ProductionCompanyDTO`, `VideoDTO` (Task 2).
- Produces: `ContentDetailsServiceImpl.ALLOWED_CREW_JOBS` (package-visible `static final Set<String>`, for the test to reference if needed), private helpers `crewFromCredits(TmdbCredits)`, `crewFromAggregateCredits(TmdbAggregateCredits)`, `productionCompanies(List<TmdbProductionCompany>)`, `videos(TmdbVideos)`, `nullIfZero(Long)`. `buildSeasonDetails`/`buildEpisodeDetails` still pass `null` for `budget`, `revenue`, `productionCompanies`, `crew`, `videos` at the end of this task — Task 4 replaces those `null`s with inherited values.

- [ ] **Step 1: Add imports and the allowed-jobs constant**

In `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImpl.java`, add these imports (alongside the existing `com.watchwise.watchwise_api.common.tmdb.*` and `content.dto.*` imports):
```java
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCrewJob;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCrewMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbCrewMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbProductionCompany;
import com.watchwise.watchwise_api.common.tmdb.TmdbVideo;
import com.watchwise.watchwise_api.common.tmdb.TmdbVideos;
import com.watchwise.watchwise_api.content.dto.CrewMemberDTO;
import com.watchwise.watchwise_api.content.dto.ProductionCompanyDTO;
import com.watchwise.watchwise_api.content.dto.VideoDTO;
```
and:
```java
import java.time.Instant;
import java.util.Set;
```
(`java.util.ArrayList`, `java.util.LinkedHashMap`, `java.util.Map`, `java.time.format.DateTimeParseException` are already imported.)

Add the constant right after the existing `RECENT_EPISODES_LIMIT` constant:
```java
    private static final Set<String> ALLOWED_CREW_JOBS = Set.of(
            "Director", "Screenplay", "Executive Producer", "Production Manager",
            "First Assistant Director", "Director of Photography", "Supervising Art Director");
```

- [ ] **Step 2: Add crew, production companies, videos and budget/revenue helper methods**

Add these private methods to `ContentDetailsServiceImpl` (e.g. right after `creators(...)`):
```java
    private List<CrewMemberDTO> crewFromCredits(TmdbCredits credits) {
        if (credits == null || credits.crew() == null) {
            return List.of();
        }
        Map<Integer, CrewAccumulator> accumulators = new LinkedHashMap<>();
        for (TmdbCrewMember member : credits.crew()) {
            if (!ALLOWED_CREW_JOBS.contains(member.job())) {
                continue;
            }
            accumulators.computeIfAbsent(member.id(), id -> new CrewAccumulator(id, member.name(), member.profilePath()))
                    .jobs.add(member.job());
        }
        return accumulators.values().stream()
                .map(accumulator -> new CrewMemberDTO(accumulator.id, accumulator.name, accumulator.profilePath, accumulator.jobs))
                .toList();
    }

    private List<CrewMemberDTO> crewFromAggregateCredits(TmdbAggregateCredits credits) {
        if (credits == null || credits.crew() == null) {
            return List.of();
        }
        List<CrewMemberDTO> result = new ArrayList<>();
        for (TmdbAggregateCrewMember member : credits.crew()) {
            List<String> matchingJobs = member.jobs() == null
                    ? List.of()
                    : member.jobs().stream().map(TmdbAggregateCrewJob::job).filter(ALLOWED_CREW_JOBS::contains).toList();
            if (!matchingJobs.isEmpty()) {
                result.add(new CrewMemberDTO(member.id(), member.name(), member.profilePath(), matchingJobs));
            }
        }
        return result;
    }

    private List<ProductionCompanyDTO> productionCompanies(List<TmdbProductionCompany> companies) {
        if (companies == null) {
            return List.of();
        }
        return companies.stream()
                .map(company -> new ProductionCompanyDTO(company.id(), company.name(), company.logoPath(), company.originCountry()))
                .toList();
    }

    private List<VideoDTO> videos(TmdbVideos videos) {
        if (videos == null || videos.results() == null) {
            return List.of();
        }
        return videos.results().stream()
                .map(video -> new VideoDTO(
                        video.key(), video.name(), video.site(), video.type(), video.official(),
                        video.isoCode639_1(), parseInstant(video.publishedAt())))
                .toList();
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Long nullIfZero(Long value) {
        return value == null || value == 0L ? null : value;
    }
```

Add this private static nested class at the bottom of the class, right before the final closing `}` (it captures `id` too, since `computeIfAbsent`'s map key is the id but the accumulator itself also needs to remember it to build the final `CrewMemberDTO`):
```java
    private static final class CrewAccumulator {
        private final Integer id;
        private final String name;
        private final String profilePath;
        private final List<String> jobs = new ArrayList<>();

        private CrewAccumulator(Integer id, String name, String profilePath) {
            this.id = id;
            this.name = name;
            this.profilePath = profilePath;
        }
    }
```

- [ ] **Step 3: Wire `buildMovieDetails`**

Change the `return new ContentDetailsDTO(...)` in `buildMovieDetails` from:
```java
        return new ContentDetailsDTO(
                content.getId(),
                ContentType.MOVIE,
                resolveMovieTitle(details, region),
                details.overview(),
                details.posterPath(),
                details.backdropPath(),
                parseDate(details.releaseDate()),
                details.runtime(),
                null,
                null,
                null,
                genreNames(details.genres()),
                countryCodes(details.productionCountries()),
                castFromCredits(details.credits()),
                null,
                null,
                watchProviders(details.watchProviders(), region),
                null,
                null,
                null);
```
to:
```java
        return new ContentDetailsDTO(
                content.getId(),
                ContentType.MOVIE,
                resolveMovieTitle(details, region),
                details.overview(),
                details.posterPath(),
                details.backdropPath(),
                parseDate(details.releaseDate()),
                details.runtime(),
                null,
                null,
                null,
                genreNames(details.genres()),
                countryCodes(details.productionCountries()),
                castFromCredits(details.credits()),
                null,
                null,
                watchProviders(details.watchProviders(), region),
                null,
                null,
                null,
                nullIfZero(details.budget()),
                nullIfZero(details.revenue()),
                productionCompanies(details.productionCompanies()),
                crewFromCredits(details.credits()),
                videos(details.videos()));
```

- [ ] **Step 4: Wire `buildSeriesDetails`**

Change the `return new ContentDetailsDTO(...)` in `buildSeriesDetails` from:
```java
        return new ContentDetailsDTO(
                content.getId(),
                ContentType.SERIES,
                resolveTvTitle(details, region),
                details.overview(),
                details.posterPath(),
                details.backdropPath(),
                parseDate(details.firstAirDate()),
                averageRuntime(episodeRuntimes),
                totalRuntimeMinutes(episodeRuntimes),
                details.numberOfSeasons(),
                details.numberOfEpisodes(),
                genreNames(details.genres()),
                countryCodes(details.productionCountries()),
                castFromAggregateCredits(details.aggregateCredits()),
                null,
                creators(details.createdBy()),
                watchProviders(details.watchProviders(), region),
                seasonSummaries(details.seasons(), allSeasons),
                null,
                recentlyAiredEpisodes(allSeasons));
```
to:
```java
        return new ContentDetailsDTO(
                content.getId(),
                ContentType.SERIES,
                resolveTvTitle(details, region),
                details.overview(),
                details.posterPath(),
                details.backdropPath(),
                parseDate(details.firstAirDate()),
                averageRuntime(episodeRuntimes),
                totalRuntimeMinutes(episodeRuntimes),
                details.numberOfSeasons(),
                details.numberOfEpisodes(),
                genreNames(details.genres()),
                countryCodes(details.productionCountries()),
                castFromAggregateCredits(details.aggregateCredits()),
                null,
                creators(details.createdBy()),
                watchProviders(details.watchProviders(), region),
                seasonSummaries(details.seasons(), allSeasons),
                null,
                recentlyAiredEpisodes(allSeasons),
                null,
                null,
                productionCompanies(details.productionCompanies()),
                crewFromAggregateCredits(details.aggregateCredits()),
                videos(details.videos()));
```

- [ ] **Step 5: Wire `buildSeasonDetails` and `buildEpisodeDetails` with temporary `null`s**

Change the `return new ContentDetailsDTO(...)` in `buildSeasonDetails` from:
```java
        return new ContentDetailsDTO(
                content.getId(),
                ContentType.SEASON,
                season.name(),
                season.overview(),
                season.posterPath(),
                null,
                parseDate(season.airDate()),
                averageRuntime(episodeRuntimes),
                totalRuntimeMinutes(episodeRuntimes),
                null,
                numberOfEpisodes(season.episodes()),
                genreNames(series.genres()),
                countryCodes(series.productionCountries()),
                castFromAggregateCredits(season.aggregateCredits()),
                seasonGuestStars(season.episodes()),
                creators(series.createdBy()),
                watchProviders(season.watchProviders(), region),
                null,
                episodeSummaries(season.seasonNumber(), season.episodes()),
                null);
```
to:
```java
        return new ContentDetailsDTO(
                content.getId(),
                ContentType.SEASON,
                season.name(),
                season.overview(),
                season.posterPath(),
                null,
                parseDate(season.airDate()),
                averageRuntime(episodeRuntimes),
                totalRuntimeMinutes(episodeRuntimes),
                null,
                numberOfEpisodes(season.episodes()),
                genreNames(series.genres()),
                countryCodes(series.productionCountries()),
                castFromAggregateCredits(season.aggregateCredits()),
                seasonGuestStars(season.episodes()),
                creators(series.createdBy()),
                watchProviders(season.watchProviders(), region),
                null,
                episodeSummaries(season.seasonNumber(), season.episodes()),
                null,
                null,
                null,
                null,
                null,
                null);
```
(6 trailing `null`s: the existing `recentEpisodes` was already `null`, plus the 5 new fields — this compiles and keeps `SEASON` behavior unchanged until Task 4.)

Change the `return new ContentDetailsDTO(...)` in `buildEpisodeDetails` from:
```java
        return new ContentDetailsDTO(
                content.getId(),
                ContentType.EPISODE,
                episode.name(),
                episode.overview(),
                episode.stillPath(),
                null,
                parseDate(episode.airDate()),
                episode.runtime(),
                null,
                null,
                null,
                genreNames(series.genres()),
                countryCodes(series.productionCountries()),
                castFromAggregateCredits(series.aggregateCredits()),
                guestStars(episode.guestStars()),
                null,
                List.of(),
                null,
                null,
                null);
```
to:
```java
        return new ContentDetailsDTO(
                content.getId(),
                ContentType.EPISODE,
                episode.name(),
                episode.overview(),
                episode.stillPath(),
                null,
                parseDate(episode.airDate()),
                episode.runtime(),
                null,
                null,
                null,
                genreNames(series.genres()),
                countryCodes(series.productionCountries()),
                castFromAggregateCredits(series.aggregateCredits()),
                guestStars(episode.guestStars()),
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
```

- [ ] **Step 6: Run the full test suite and commit Tasks 2+3 together**

Run: `mvnw.cmd test "-Dtest=ContentDetailsServiceImplTest,ContentControllerIntegrationTest"`
Expected: BUILD SUCCESS — existing tests still pass (all still assert only pre-existing fields, and `SEASON`/`EPISODE` still have `null` for the 5 new fields).

```bash
git add src/main/java/com/watchwise/watchwise_api/content/dto/CrewMemberDTO.java src/main/java/com/watchwise/watchwise_api/content/dto/ProductionCompanyDTO.java src/main/java/com/watchwise/watchwise_api/content/dto/VideoDTO.java src/main/java/com/watchwise/watchwise_api/content/dto/ContentDetailsDTO.java src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImpl.java
git commit -m "feat(content): expose budget, revenue, production companies, job-filtered crew and videos for movies and series"
```

- [ ] **Step 7: Write the failing tests for the new MOVIE/SERIES behavior**

Add these test methods to `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImplTest.java` (add the needed imports: `com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCrewJob`, `TmdbAggregateCrewMember`, `TmdbCrewMember`, `TmdbProductionCompany`, `TmdbVideo`, `TmdbVideos`, and `java.time.Instant`):

```java
    @Test
    @DisplayName("[getDetails] Should Return Budget Revenue Production Companies Crew And Videos - When Content Is A Movie")
    void shouldReturnBudgetRevenueProductionCompaniesCrewAndVideosWhenContentIsAMovie() {
        UUID contentId = UUID.randomUUID();
        Content movie = Content.builder().id(contentId).type(ContentType.MOVIE).tmdbId("603").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(movie));
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", "The Matrix", "The Matrix", null, null, null, null, null, List.of(), List.of(),
                new com.watchwise.watchwise_api.common.tmdb.TmdbCredits(List.of(), List.of(
                        new TmdbCrewMember(10, "Lana Wachowski", "Director", "/lana.jpg"),
                        new TmdbCrewMember(11, "Best Boy Grip", "Best Boy Grip", null))),
                null, null,
                63000000L, 463500000L,
                List.of(new TmdbProductionCompany(79, "Village Roadshow Pictures", "/village.png", "US")),
                new TmdbVideos(List.of(new TmdbVideo("vKQi3bBA1y8", "Trailer", "YouTube", "Trailer", true, "en",
                        "1999-03-01T00:00:00.000Z"))))));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.budget()).isEqualTo(63000000L);
        assertThat(result.revenue()).isEqualTo(463500000L);
        assertThat(result.productionCompanies()).extracting("id", "name", "logoPath", "originCountry")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(79, "Village Roadshow Pictures", "/village.png", "US"));
        assertThat(result.crew()).extracting("id", "name", "profilePath", "jobs")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(10, "Lana Wachowski", "/lana.jpg", List.of("Director")));
        assertThat(result.videos()).extracting("key", "name", "site", "type", "official", "language", "publishedAt")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "vKQi3bBA1y8", "Trailer", "YouTube", "Trailer", true, "en", Instant.parse("1999-03-01T00:00:00.000Z")));
    }

    @Test
    @DisplayName("[getDetails] Should Return Null Budget And Revenue - When TMDB Reports Them As Zero")
    void shouldReturnNullBudgetAndRevenueWhenTmdbReportsThemAsZero() {
        UUID contentId = UUID.randomUUID();
        Content movie = Content.builder().id(contentId).type(ContentType.MOVIE).tmdbId("603").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(movie));
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", "The Matrix", "The Matrix", null, null, null, null, null, List.of(), List.of(), null, null, null,
                0L, 0L, null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.budget()).isNull();
        assertThat(result.revenue()).isNull();
    }

    @Test
    @DisplayName("[getDetails] Should Group Multiple Matching Jobs For The Same Crew Member - When Content Is A Movie")
    void shouldGroupMultipleMatchingJobsForTheSameCrewMemberWhenContentIsAMovie() {
        UUID contentId = UUID.randomUUID();
        Content movie = Content.builder().id(contentId).type(ContentType.MOVIE).tmdbId("603").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(movie));
        when(tmdbClient.getMovieFullDetails("603", "en-US")).thenReturn(Optional.of(new TmdbMovieFullDetails(
                "603", "The Matrix", "The Matrix", null, null, null, null, null, List.of(), List.of(),
                new com.watchwise.watchwise_api.common.tmdb.TmdbCredits(List.of(), List.of(
                        new TmdbCrewMember(10, "Lana Wachowski", "Director", "/lana.jpg"),
                        new TmdbCrewMember(10, "Lana Wachowski", "Screenplay", "/lana.jpg"))),
                null, null, null, null, null, null)));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.crew()).extracting("id", "jobs")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(10, List.of("Director", "Screenplay")));
    }

    @Test
    @DisplayName("[getDetails] Should Return Production Companies Aggregate Crew And Videos But Null Budget And Revenue - When Content Is A Series")
    void shouldReturnProductionCompaniesAggregateCrewAndVideosButNullBudgetAndRevenueWhenContentIsASeries() {
        UUID contentId = UUID.randomUUID();
        Content series = Content.builder().id(contentId).type(ContentType.SERIES).tmdbId("1396").build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(series));
        when(tmdbClient.getTvFullDetails("1396", "en-US")).thenReturn(Optional.of(new TmdbTvFullDetails(
                "1396", "Breaking Bad", "Breaking Bad", null, null, null, "2008-01-20", null,
                List.of(), List.of(), null, List.of(), null,
                new TmdbAggregateCredits(List.of(), List.of(
                        new TmdbAggregateCrewMember(66633, "Vince Gilligan", "/vince.jpg",
                                List.of(new TmdbAggregateCrewJob("Director"), new TmdbAggregateCrewJob("Executive Producer"))),
                        new TmdbAggregateCrewMember(99999, "Random Grip", null, List.of(new TmdbAggregateCrewJob("Grip"))))),
                null, null, null, null,
                List.of(new TmdbProductionCompany(11073, "Sony Pictures Television", "/sony.png", "US")),
                new TmdbVideos(List.of(new TmdbVideo("abc123", "Official Trailer", "YouTube", "Trailer", true, "en",
                        "2008-01-01T00:00:00.000Z"))))));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.budget()).isNull();
        assertThat(result.revenue()).isNull();
        assertThat(result.productionCompanies()).extracting("id", "name")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(11073, "Sony Pictures Television"));
        assertThat(result.crew()).extracting("id", "jobs")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(66633, List.of("Director", "Executive Producer")));
        assertThat(result.videos()).extracting("key", "name").containsExactly(org.assertj.core.groups.Tuple.tuple("abc123", "Official Trailer"));
    }
```

- [ ] **Step 8: Run the new tests and confirm they pass**

Run: `mvnw.cmd test "-Dtest=ContentDetailsServiceImplTest"`
Expected: BUILD SUCCESS, all tests pass including the 4 new ones.

- [ ] **Step 9: Commit**

```bash
git add src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImplTest.java
git commit -m "test(content): cover budget, revenue, production companies, crew filtering and videos for movies and series"
```

---

## Task 4: SEASON/EPISODE inherit budget/revenue/productionCompanies/crew/videos from SERIES

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImplTest.java`

**Interfaces:**
- Consumes: `productionCompanies(List<TmdbProductionCompany>)`, `crewFromAggregateCredits(TmdbAggregateCredits)`, `videos(TmdbVideos)` from Task 3, applied to the already-fetched `series` (`TmdbTvFullDetails`) object in `buildSeasonDetails`/`buildEpisodeDetails`.
- Produces: `SEASON`/`EPISODE` `ContentDetailsDTO` responses now carry the parent series' `productionCompanies`/`crew`/`videos` (`budget`/`revenue` stay `null`, same as `SERIES`).

- [ ] **Step 1: Write the failing inheritance tests**

Add to `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImplTest.java`:

```java
    @Test
    @DisplayName("[getDetails] Should Inherit Production Companies Crew And Videos From Series - When Content Is A Season")
    void shouldInheritProductionCompaniesCrewAndVideosFromSeriesWhenContentIsASeason() {
        UUID contentId = UUID.randomUUID();
        Content season = Content.builder().id(contentId).type(ContentType.SEASON)
                .seriesTmdbId("1396").seasonNumber(1).build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(season));
        when(tmdbClient.getSeasonFullDetails("1396", 1, "en-US")).thenReturn(Optional.of(new TmdbSeasonFullDetails(
                3572, "Season 1", "First season", "/season1.jpg", "2008-01-20", 1, List.of(
                        new TmdbEpisodeSummary(1, "Pilot", null, "2008-01-20", 58, null, null)),
                null, null)));
        when(tmdbClient.getTvFullDetails("1396", "en-US")).thenReturn(Optional.of(new TmdbTvFullDetails(
                "1396", "Breaking Bad", "Breaking Bad", null, null, null, "2008-01-20", null,
                List.of(), List.of(), null, List.of(), null,
                new TmdbAggregateCredits(List.of(), List.of(
                        new TmdbAggregateCrewMember(66633, "Vince Gilligan", "/vince.jpg",
                                List.of(new TmdbAggregateCrewJob("Director"))))),
                null, null, null, null,
                List.of(new TmdbProductionCompany(11073, "Sony Pictures Television", "/sony.png", "US")),
                new TmdbVideos(List.of(new TmdbVideo("abc123", "Official Trailer", "YouTube", "Trailer", true, "en",
                        "2008-01-01T00:00:00.000Z"))))));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.budget()).isNull();
        assertThat(result.revenue()).isNull();
        assertThat(result.productionCompanies()).extracting("id", "name")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(11073, "Sony Pictures Television"));
        assertThat(result.crew()).extracting("id", "jobs")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(66633, List.of("Director")));
        assertThat(result.videos()).extracting("key").containsExactly("abc123");
    }

    @Test
    @DisplayName("[getDetails] Should Inherit Production Companies Crew And Videos From Series - When Content Is An Episode")
    void shouldInheritProductionCompaniesCrewAndVideosFromSeriesWhenContentIsAnEpisode() {
        UUID contentId = UUID.randomUUID();
        Content episode = Content.builder().id(contentId).type(ContentType.EPISODE)
                .seriesTmdbId("1396").seasonNumber(1).episodeNumber(1).build();
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(episode));
        when(tmdbClient.getEpisodeFullDetails("1396", 1, 1, "en-US")).thenReturn(Optional.of(new TmdbEpisodeFullDetails(
                62085, "Pilot", "First episode", "2008-01-20", 1, 1, 58, "/still.jpg", List.of())));
        when(tmdbClient.getTvFullDetails("1396", "en-US")).thenReturn(Optional.of(new TmdbTvFullDetails(
                "1396", "Breaking Bad", "Breaking Bad", null, null, null, "2008-01-20", null,
                List.of(), List.of(), null, List.of(), null,
                new TmdbAggregateCredits(List.of(), List.of(
                        new TmdbAggregateCrewMember(66633, "Vince Gilligan", "/vince.jpg",
                                List.of(new TmdbAggregateCrewJob("Director"))))),
                null, null, null, null,
                List.of(new TmdbProductionCompany(11073, "Sony Pictures Television", "/sony.png", "US")),
                new TmdbVideos(List.of(new TmdbVideo("abc123", "Official Trailer", "YouTube", "Trailer", true, "en",
                        "2008-01-01T00:00:00.000Z"))))));

        ContentDetailsDTO result = contentDetailsService.getDetails(contentId, requestingUserId);

        assertThat(result.budget()).isNull();
        assertThat(result.revenue()).isNull();
        assertThat(result.productionCompanies()).extracting("id", "name")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(11073, "Sony Pictures Television"));
        assertThat(result.crew()).extracting("id", "jobs")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(66633, List.of("Director")));
        assertThat(result.videos()).extracting("key").containsExactly("abc123");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvnw.cmd test "-Dtest=ContentDetailsServiceImplTest#shouldInheritProductionCompaniesCrewAndVideosFromSeriesWhenContentIsASeason+shouldInheritProductionCompaniesCrewAndVideosFromSeriesWhenContentIsAnEpisode"`
Expected: FAIL — `productionCompanies()`/`crew()`/`videos()` come back empty (`null` fields default to `List.of()` when accessed via the mapping helpers, since `buildSeasonDetails`/`buildEpisodeDetails` still pass literal `null` for those constructor args from Task 3).

- [ ] **Step 3: Replace the temporary `null`s in `buildSeasonDetails` with values inherited from `series`**

Change the tail of the `return new ContentDetailsDTO(...)` in `buildSeasonDetails` from:
```java
                null,
                episodeSummaries(season.seasonNumber(), season.episodes()),
                null,
                null,
                null,
                null,
                null,
                null);
```
to:
```java
                null,
                episodeSummaries(season.seasonNumber(), season.episodes()),
                null,
                null,
                null,
                productionCompanies(series.productionCompanies()),
                crewFromAggregateCredits(series.aggregateCredits()),
                videos(series.videos()));
```

- [ ] **Step 4: Replace the temporary `null`s in `buildEpisodeDetails` with values inherited from `series`**

Change the tail of the `return new ContentDetailsDTO(...)` in `buildEpisodeDetails` from:
```java
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
```
to:
```java
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                productionCompanies(series.productionCompanies()),
                crewFromAggregateCredits(series.aggregateCredits()),
                videos(series.videos()));
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvnw.cmd test "-Dtest=ContentDetailsServiceImplTest"`
Expected: BUILD SUCCESS, all tests pass (existing + the 2 new inheritance tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImpl.java src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImplTest.java
git commit -m "feat(content): inherit production companies, crew and videos from series for season and episode details"
```

---

## Task 5: Docs — openapi.yaml, business-rules.md, business-rules-summary.md, progress.md

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/business-rules-summary.md`
- Modify: `docs/context/progress.md`

**Interfaces:**
- Consumes: nothing (docs only, no code).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add the 5 new properties to `ContentDetailsDTO` in `openapi.yaml`**

In `docs/context/openapi.yaml`, inside the `ContentDetailsDTO` schema's `properties:` block, immediately after the existing `recentEpisodes:` property (right before the `EpisodeSummary:` schema definition that follows it), insert:
```yaml
        budget:
          type: integer
          format: int64
          nullable: true
          description: >
            Só preenchido em MOVIE — orçamento em dólares americanos, do TMDB. TMDB usa 0 pra "não
            informado", tratado como null pelo backend (não um orçamento real de $0). Sempre null em
            SERIES/SEASON/EPISODE — o endpoint /tv/{id} do TMDB não tem esse campo.
        revenue:
          type: integer
          format: int64
          nullable: true
          description: Mesma regra de budget (0 do TMDB vira null), também só preenchido em MOVIE.
        productionCompanies:
          type: array
          description: >
            Em MOVIE/SERIES vem do próprio corpo base do TMDB, sem chamada extra. Em SEASON/EPISODE,
            herdado da SERIES do mesmo seriesTmdbId (mesmo padrão de genres/countries/creators).
          items:
            type: object
            properties:
              id: { type: integer }
              name: { type: string }
              logoPath: { type: string, nullable: true }
              originCountry: { type: string, nullable: true }
        crew:
          type: array
          description: >
            Equipe técnica filtrada por uma lista fixa de jobs (Director, Screenplay, Executive
            Producer, Production Manager, First Assistant Director, Director of Photography,
            Supervising Art Director) — pessoas com nenhum job dessa lista não aparecem. MOVIE usa
            credits.crew; SERIES usa aggregate_credits.crew da própria série; SEASON/EPISODE herdam
            o aggregate_credits.crew da SERIES do mesmo seriesTmdbId. Um registro por pessoa — jobs
            agrupados numa lista quando a mesma pessoa tem mais de um job que bate no filtro.
          items:
            type: object
            properties:
              id: { type: integer, description: "TMDB person id." }
              name: { type: string }
              profilePath: { type: string, nullable: true }
              jobs: { type: array, items: { type: string } }
        videos:
          type: array
          description: >
            Todos os vídeos que o TMDB retorna (trailers, teasers, clipes etc), sem filtro por type/
            official/site — o cliente decide o que exibir. MOVIE/SERIES usam o próprio videos.results;
            SEASON/EPISODE herdam o da SERIES do mesmo seriesTmdbId (TMDB não tem vídeos por
            temporada/episódio).
          items:
            type: object
            properties:
              key:
                type: string
                description: "Identificador usado pra montar a URL de reprodução (ex. https://www.youtube.com/watch?v={key} quando site=YouTube)."
              name: { type: string }
              site: { type: string, description: "Ex. YouTube, Vimeo." }
              type: { type: string, description: "Ex. Trailer, Teaser, Clip, Featurette, Behind the Scenes, Bloopers." }
              official: { type: boolean }
              language: { type: string, nullable: true, description: "iso_639_1 do TMDB." }
              publishedAt: { type: string, format: date-time, nullable: true }
```

- [ ] **Step 2: Update the `ContentDetailsDTO` schema's top-level `description` in `openapi.yaml`**

In the same schema block, append this sentence to the end of the existing `description:` block (right before its closing `>` block ends, i.e. as a new trailing sentence in that same YAML folded string):
```
        budget/revenue só existem em MOVIE (0 do TMDB vira null); productionCompanies/crew/videos
        existem em MOVIE/SERIES e são herdados por SEASON/EPISODE da SERIES do mesmo seriesTmdbId;
        crew é filtrado por uma lista fixa de jobs (ver seção Content de business-rules.md).
```

- [ ] **Step 3: Add the new business rules to `business-rules.md`**

In `docs/context/business-rules.md`, inside the `## Content` section, immediately after the existing bullet `- Capado em 100 ids por chamada em GET /contents/details, mesmo limite/mensagem de GET /contents/stats (ContentDetailsServiceImpl.MAX_BATCH_IDS).` (and before the `## User / Auth` heading), insert:
```markdown
  - **`crew` é filtrado por uma lista fixa de jobs, não o crew inteiro do TMDB**
    (`ContentDetailsServiceImpl.ALLOWED_CREW_JOBS`, adicionado 2026-09-01, a pedido do usuário):
    `Director`, `Screenplay`, `Executive Producer`, `Production Manager`, `First Assistant Director`,
    `Director of Photography`, `Supervising Art Director` — strings exatas do campo `job` do TMDB.
    Pessoa sem nenhum job dessa lista não aparece em `crew`. MOVIE lê `credits.crew` (1 linha por
    job no TMDB — agrupado por `id`, jobs batidos juntados numa lista por pessoa,
    `ContentDetailsServiceImpl.crewFromCredits`); SERIES lê `aggregate_credits.crew` (já vem com
    `jobs[]` por pessoa no TMDB, só filtra os que batem, descarta a pessoa se nenhum bater,
    `crewFromAggregateCredits`). Um registro por pessoa em ambos os casos, nunca um por job —
    decisão deliberada pra não duplicar `id`/`name`/`profilePath` de quem tem múltiplos jobs
    filtrados (ex: Director e Executive Producer na mesma pessoa).
  - **`budget`/`revenue` só existem em MOVIE — o endpoint `/tv/{id}` do TMDB não retorna esses
    campos** (confirmado com o usuário 2026-09-01) — ficam sempre `null` em SERIES/SEASON/EPISODE.
    Em MOVIE, `0` do TMDB é tratado como "não informado" e vira `null`
    (`ContentDetailsServiceImpl.nullIfZero`) — convenção do próprio TMDB, não um filme que
    literalmente arrecadou/custou `$0`.
  - **`productionCompanies`/`crew`/`videos` de SEASON/EPISODE são herdados da `SERIES` do mesmo
    `seriesTmdbId`, mesmo padrão já usado por `genres`/`countries`/`creators`** — sem chamada TMDB
    extra, reaproveitam o mesmo `TmdbTvFullDetails` já buscado pra esses outros campos.
  - **`videos` não tem filtro de `type`/`official`/`site`** (confirmado com o usuário 2026-09-01) —
    devolve tudo que `videos.results` do TMDB trouxer; o cliente decide o que exibir. `key` é
    incluído mesmo não tendo sido pedido explicitamente, porque sem ele o vídeo não é reproduzível
    (é o identificador usado pra montar a URL de reprodução, ex. `youtube.com/watch?v={key}`).
    `publishedAt` usa `Instant` (não `LocalDate` como `releaseDate`/`airDate`) porque o TMDB retorna
    timestamp completo, não só data.
  - **`videos`/`credits`/`aggregate_credits` continuam custando 1 única chamada HTTP por
    MOVIE/SERIES** — `videos` foi só mais um valor em `append_to_response` de `getMovieFullDetails`/
    `getTvFullDetails` (já existentes), e `crew` já vinha embutido nas respostas de `credits`/
    `aggregate_credits` que o backend já buscava — só não estava mapeado nos DTOs antes de
    2026-09-01.
```

- [ ] **Step 4: Update `business-rules-summary.md`**

In `docs/context/business-rules-summary.md`, inside the `## Content` section, append a new bullet right after the existing `GET /contents/{contentId}/details`/`GET /contents/details` line:
```markdown
- `budget`/`revenue`/`productionCompanies`/`crew`/`videos` em `/contents/{contentId}/details` (2026-09-01): `budget`/`revenue` só em MOVIE (TMDB não tem isso pra série), `0` do TMDB vira `null`. `crew` filtrado por lista fixa de jobs (Director, Screenplay, Executive Producer, Production Manager, First Assistant Director, Director of Photography, Supervising Art Director), um registro por pessoa com jobs agrupados. `productionCompanies`/`crew`/`videos` de SEASON/EPISODE herdados da SERIES, sem chamada TMDB extra. `videos` sem filtro de type/official/site, inclui `key` pra montar a URL de reprodução.
```

- [ ] **Step 5: Add today's entry to `progress.md`**

In `docs/context/progress.md`, append a new section at the end of the file (today is `2026-09-01`; the file already has `## 2026-09-01` and `## 2026-09-01 (2)` entries, so this is `(3)`):
```markdown
## 2026-09-01 (3) — Budget, revenue, production companies, crew filtrado por job e videos em `/contents/{contentId}/details`

Ampliado o proxy de detalhes do TMDB (`ContentDetailsDTO`/`ContentDetailsServiceImpl`/`TmdbClient`)
com 5 campos novos, sem nenhuma chamada HTTP nova ao TMDB: `budget`/`revenue` (só MOVIE — TMDB não
tem esses campos por série — `0` tratado como não informado), `productionCompanies` (MOVIE/SERIES,
herdado por SEASON/EPISODE), `crew` (equipe técnica filtrada por uma lista fixa de 7 jobs — Director,
Screenplay, Executive Producer, Production Manager, First Assistant Director, Director of
Photography, Supervising Art Director — um registro por pessoa com jobs agrupados; MOVIE lê
`credits.crew`, SERIES lê `aggregate_credits.crew`, ambos já buscados hoje só sem o campo mapeado) e
`videos` (todos os vídeos do TMDB sem filtro de type/official/site, campo `key` incluído pra montar a
URL de reprodução — adicionado ao `append_to_response` de `getMovieFullDetails`/`getTvFullDetails`).
SEASON/EPISODE herdam `productionCompanies`/`crew`/`videos` da SERIES do mesmo `seriesTmdbId`, mesmo
padrão já usado por `genres`/`countries`/`creators` — `budget`/`revenue` continuam `null` pra esses
tipos. `openapi.yaml`/`business-rules.md`/`business-rules-summary.md` atualizados junto.
```

- [ ] **Step 6: No commit for this task** — `docs/` is gitignored in this repo (confirmed via `.gitignore` line 5). Do not run `git add`/`git commit` on any file under `docs/`.

---

## Task 6: Full build and test suite verification

**Files:** none (verification only).

**Interfaces:** none.

- [ ] **Step 1: Run the full test suite**

Run: `mvnw.cmd test`
Expected: BUILD SUCCESS, all tests pass (Docker must be running for `*RepositoryTest`/`ContentControllerIntegrationTest` Testcontainers).

- [ ] **Step 2: Run a full package build**

Run: `mvnw.cmd clean package`
Expected: BUILD SUCCESS, jar produced under `target/`.

- [ ] **Step 3: Confirm no stray `docs/` changes are staged**

Run: `git status`
Expected: only `src/` files appear as changed/staged from this plan's work; any `docs/` changes are untracked/ignored (not staged), consistent with `docs/` being gitignored in this repo.

- [ ] **Step 4: Push**

Ask the user before pushing (per this session's standing instructions on remote-visible actions), then:
```bash
git push
```
