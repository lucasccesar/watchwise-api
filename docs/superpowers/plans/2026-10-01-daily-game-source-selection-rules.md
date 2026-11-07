# Daily Game Source Selection Rules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Update every daily-game source draw to use top-rated pages 1–20, reject Asian-language source works, restrict actor selection to the first four eligible cast members, suppress secret actor images, guarantee six episode stills, and cap info-game candidate casts at ten names.

**Architecture:** Keep the existing generator/service layering and snapshots. Add original-language data to TMDB models, centralize source-language and page policy in `DailyChallengeGenerationSupport`, apply source filters before candidate selection, and preserve filmography work metadata while suppressing only the actor-game answer image. Episode generation and snapshot reads will share a six-still validity rule.

**Tech Stack:** Java 21, Spring Boot, Jackson records, existing `TmdbClient`, JUnit 5, Mockito, Maven Wrapper, Markdown project documentation.

## Global Constraints

- Apply the top-rated/page-1-to-20 policy to every movie/series source draw in `dailygame.generation`.
- Reject Asian-language source works by normalized TMDB `original_language`; do not filter works inside an actor’s filmography.
- Preserve TMDB response order when building actor eligibility; randomly choose only from the first four eligible actors.
- Do not expose the secret actor profile image in actor-filmography responses, including terminal answers.
- A generated episode is valid only when `getEpisodeImages()` yields at least six distinct nonblank still paths.
- Limit only candidate cast feedback in `MOVIE_BY_INFO` and `SERIES_BY_INFO` to the first ten normalized names; preserve secret snapshots.
- Update `business-rules.md`, `openapi.yaml`, and the current `progress.md` entry in the same change; documentation remains worktree-only.

---

## File map

Modify TMDB models and compatibility constructors:

- `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbMovieSearchResult.java`
- `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbTvSearchResult.java`
- `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbMovieFullDetails.java`
- `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbTvFullDetails.java`

Modify generation and response behavior:

- `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeGenerationSupport.java`
- All eight generator classes under `dailygame/generation/`
- `DailyChallengeSnapshotRepairServiceImpl.java`
- `DailyChallengeResponseAssembler.java`
- `DailyGameInfoComparisonServiceImpl.java`

Modify focused tests:

- `src/test/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeGeneratorTest.java`
- `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientTest.java`
- `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssemblerTest.java`
- `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameInfoComparisonServiceTest.java`
- Add model/policy tests where constructor or language-filter behavior is isolated.

## Task 1: Add TMDB language fields and centralized source policy

**Interfaces:**

- `TmdbMovieSearchResult.originalLanguage()` and `TmdbTvSearchResult.originalLanguage()` map `original_language`.
- Full-detail models expose the same field while preserving existing constructor overloads with `null` defaults.
- `DailyChallengeGenerationSupport.randomPage()` returns a value in `[1, 20]`.
- `DailyChallengeGenerationSupport.isAsianOriginalLanguage(String)` normalizes the language before checking the project’s explicit Asian-language set.

- [ ] Write tests proving `original_language` JSON deserializes for movie and TV search responses and that page bounds are 1 through 20.
- [ ] Run the focused TMDB/model tests and observe the new assertions fail before implementation.
- [ ] Add the fields, compatibility constructors, page bound, and language policy.
- [ ] Run the focused tests and verify they pass.

## Task 2: Switch every source draw to top-rated and reject Asian source works

**Interfaces:**

- Movie source generators call `getTopRatedMovies`.
- Series source generators call `getTopRatedSeries`.
- Every source predicate rejects `originalLanguage` values handled by `isAsianOriginalLanguage`.
- Filmography entries themselves are not filtered by this policy.

- [ ] Add failing generator tests that expect top-rated methods, pages `1..20`, and Asian-language source candidates to be rejected.
- [ ] Run `DailyChallengeGeneratorTest` and confirm the old popular/pagination behavior fails the new assertions.
- [ ] Update `MovieByPosterGenerator`, `MovieByInfoGenerator`, `PersonByFaceGenerator`, and `ActorByMovieFilmographyGenerator` to use top-rated movie sources and source-language filtering.
- [ ] Update `SeriesByPosterGenerator`, `SeriesByInfoGenerator`, `PersonByFaceGenerator`, `EpisodeByFrameGenerator`, and `ActorBySeriesFilmographyGenerator` to use top-rated series sources and source-language filtering.
- [ ] Run focused generator tests and confirm all source policies pass.

## Task 3: Limit actor selection and suppress actor-game images

**Interfaces:**

- Both actor generators preserve the filtered TMDB cast order, call `.limit(4)`, then use the existing random-item selection over those four.
- `DailyChallengeResponseAssembler.toAnswer()` returns `imageUrl = null` for both actor-filmography game types, including terminal states.
- Filmography work posters remain available in `DailyGameFilmographyEntryDTO`.

- [ ] Add failing tests with five or more eligible actors whose first four are distinguishable, asserting the selected actor is always within the first four.
- [ ] Add a failing response-assembler test asserting actor-filmography terminal answers have no image URL.
- [ ] Implement the first-four selection and response redaction.
- [ ] Run focused actor generator and response assembler tests.

## Task 4: Enforce six stills during generation and legacy snapshot handling

**Interfaces:**

- `EpisodeByFrameGenerator` accepts an episode only when its deduplicated nonblank still paths count is at least six.
- A failed episode candidate is skipped and the generator continues through the shuffled episode/season/series pools.
- `DailyChallengeSnapshotRepairServiceImpl` repairs an existing incomplete episode display snapshot when a fresh image lookup provides six or more paths.
- `DailyChallengeResponseAssembler` does not serve an episode snapshot with fewer than six valid paths; it raises the existing daily-games-unavailable domain path instead of fabricating repeated frames.

- [ ] Add a failing test with a five-still episode followed by a six-still episode and assert the latter is selected.
- [ ] Add a failing test for a legacy episode snapshot with fewer than six paths and assert it is not rendered as a valid frame sequence.
- [ ] Implement shared still normalization/deduplication and apply it to generation, repair, and response validation.
- [ ] Run the episode generator, repair, and response assembler tests.

## Task 5: Limit candidate cast feedback to ten names

**Interfaces:**

- `DailyGameInfoComparisonServiceImpl` truncates the normalized candidate cast list to the first ten names before constructing the `cast` comparison cell.
- Secret cast snapshots remain unchanged, so comparison semantics continue to use the frozen secret data.

- [ ] Add failing movie and series comparison tests with more than ten candidate cast members.
- [ ] Implement the limit after candidate normalization and before `castCell`.
- [ ] Run `DailyGameInfoComparisonServiceTest`.

## Task 6: Synchronize documentation and run verification

- [ ] Update the Daily Games rules with top-rated pages 1–20, source-only Asian-language exclusion, actor first-four selection, actor-image redaction, ten-name candidate casts, and the six-still invariant.
- [ ] Update OpenAPI descriptions for actor-game image redaction and the episode image guarantee where the existing text claims terminal image exposure.
- [ ] Append the shipped behavior to the current day in `docs/context/progress.md`.
- [ ] Run Maven compile and focused daily-game tests.
- [ ] Run the full Maven suite with the workspace Maven repository and report any Testcontainers/Docker-only failures separately.
- [ ] Run `git diff --check`, review the diff for unrelated files, and create one Conventional Commit for the code change if the focused verification passes.
