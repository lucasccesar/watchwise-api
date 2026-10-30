# Search Screen Read Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a paginated `GET /search/view` projection that renders the Search Content, Movies, Series, and People screens without frontend N+1 detail requests.

**Architecture:** Keep `GET /search` and `SearchResultDTO` unchanged as the compact existing contract. Add a separate search-view service, DTO family, and a pure planner that creates a correct combined Content page from TMDB movie and TV result streams. Reuse `ContentCardAssembler` once per visual response for cached metadata and viewer-specific state.

**Tech Stack:** Java 21, Spring Boot, Spring MVC validation, Spring Data JPA, Caffeine-backed TMDB client, Mockito/JUnit 5, AssertJ, Maven.

## Global Constraints

- Preserve `GET /search` and `SearchResultDTO`; existing callers must observe no behavior or schema regression.
- Add `GET /search/view`; it accepts only `CONTENT`, `MOVIE`, `SERIES`, and `PERSON` scopes.
- `q` is trimmed and must contain 3 through 100 characters; `page` is 1 through 500; `size` defaults to 18 and is 1 through 20. `totalResults` remains exact, while `totalPages` and `hasNext` are capped at the 500 accessible pages.
- Do not create `Content` rows while reading external search results.
- Use `ContentCardAssembler` with `BASIC_METADATA`, `VIEWER_STATE`, and `WATCHLIST_PROGRESS`; do not make client-side content-details calls.
- Keep TMDB `voteAverage` distinct from Watchwise statistics.
- Content scope order is movie 1, series 1, movie 2, series 2 until one source ends, then the remaining source; total is the sum of both TMDB totals.
- Map visual page/size to the TMDB fixed page size of 20; never pass visual page numbers directly to TMDB when `size < 20`.
- Keep external calls bounded: a direct scope reads source page 1 for its total plus at most two overlapping TMDB pages; Content reads the two source totals and only source pages containing the requested card positions.
- Follow the project `ApiError` error contract, existing search throttle, JWT security model, and preferred
  TMDB language/region where the endpoint supports it. Movie search accepts the preferred region; TMDB TV
  and person search accept language only. Card enrichment uses both preferences.
- Use TDD. Every code commit uses Conventional Commits with no `Co-Authored-By` or other self-attribution. Do not push.
- Update `docs/context/openapi.yaml`, `docs/context/business-rules.md`, `docs/context/progress.md`, and `docs/telas-reqs-definitivo.md`, but do not stage those documentation files under the repository's documentation policy.

---

## File structure

| File | Responsibility |
|---|---|
| `search/service/SearchViewScope.java` | Four scopes admitted by the visual endpoint. |
| `search/dto/SearchViewRequestDTO.java` | Validated query parameters with visual defaults. |
| `search/dto/SearchViewDTO.java` | Page metadata and typed visual result collections. |
| `search/dto/SearchContentViewDTO.java` | Purpose-built search-card projection assembled from `ContentCardDTO`, with raw TMDB fallbacks. |
| `search/dto/SearchPersonViewDTO.java` | Person grid card with resolved profile URL and department. |
| `search/service/ContentSearchPagePlanner.java` | Pure mapping from combined page indexes to TMDB source indexes. |
| `search/service/TmdbSearchWindowReader.java` | Deep module that reads an exact visual window from one fixed-size TMDB result stream. |
| `search/service/SearchViewPagination.java` | Shared maximum for accessible visual pages. |
| `search/service/SearchViewService.java` | Public visual-read boundary. |
| `search/service/impl/SearchViewServiceImpl.java` | TMDB paging, card assembly, and visual DTO mapping. |
| `search/service/impl/SearchContentViewAssembler.java` | One batched content-card assembly per response. |
| `search/controller/SearchController.java` | New route, shared validation and throttling. |
| `common/tmdb/Tmdb*SearchResult.java` | Extra TMDB search fields required by the visual cards. |

## Task 1: Define the visual API types and controller boundary

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/search/service/SearchViewScope.java`
- Create: `src/main/java/com/watchwise/watchwise_api/search/dto/SearchViewRequestDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/search/dto/SearchViewDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/search/dto/SearchContentViewDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/search/dto/SearchPersonViewDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/search/service/SearchViewService.java`
- Test: `src/test/java/com/watchwise/watchwise_api/search/dto/SearchViewRequestDTOTest.java`

**Interfaces:**
- Produces `SearchViewService.search(UUID viewerId, String query, SearchViewScope scope, int page, int size): SearchViewDTO`.
- Produces the visual request/response types consumed by the later controller integration task without changing `GET /search`.

- [ ] **Step 1: Write the failing DTO validation tests**

~~~java
@Test
void shouldRejectSizeAboveTwenty() {
    Set<ConstraintViolation<SearchViewRequestDTO>> violations = validator.validate(
            new SearchViewRequestDTO("night", SearchViewScope.CONTENT, 1, 21));

    assertThat(violations).extracting(ConstraintViolation::getMessage)
            .contains("size must be less than or equal to 20");
}

@Test
void shouldRequireVisualScope() {
    Set<ConstraintViolation<SearchViewRequestDTO>> violations = validator.validate(
            new SearchViewRequestDTO("night", null, 1, 18));

    assertThat(violations).extracting(ConstraintViolation::getMessage)
            .contains("scope must not be null");
}
~~~

- [ ] **Step 2: Run the DTO test to verify it fails**

Run: `./mvnw.cmd test "-Dtest=SearchViewRequestDTOTest"`

Expected: compilation failure because `SearchViewRequestDTO` and `SearchViewScope` do not exist.

- [ ] **Step 3: Define the request, response, and service interface**

~~~java
public record SearchViewRequestDTO(
        @NotBlank @Size(min = 3, max = 100) String q,
        @NotNull SearchViewScope scope,
        @Min(1) @Max(500) Integer page,
        @Min(1) @Max(20) Integer size) { }

public interface SearchViewService {
    SearchViewDTO search(UUID viewerId, String query, SearchViewScope scope, int page, int size);
}
~~~

Use the explicit response fields from the design. `SearchContentViewDTO` holds `String tmdbId`, `MovieOrSeriesType type`, `String title`, `String posterUrl`, `Integer releaseYear`, `List<String> genres`, `Integer runtimeMinutes`, `Integer numberOfSeasons`, `Double voteAverage`, `ContentCardViewerStateDTO viewerState`, and `ContentPreviewStatus previewStatus`. `SearchPersonViewDTO` holds `String tmdbId`, `String name`, `String photoUrl`, and `String knownForDepartment`.

- [ ] **Step 4: Add DTO tests for trimmed boundaries and defaults**

~~~java
assertThat(validator.validate(new SearchViewRequestDTO(" " + "n".repeat(100) + " ",
        SearchViewScope.CONTENT, 1, 18))).isEmpty();
assertThat(validator.validate(new SearchViewRequestDTO("night", SearchViewScope.CONTENT, 0, 18)))
        .isNotEmpty();
~~~

The request DTO must not reject a 100-character trimmed query merely because surrounding whitespace makes the raw parameter longer. The controller will enforce the post-trim maximum when the route is wired in Task 6.

- [ ] **Step 5: Run the DTO tests**

Run: `./mvnw.cmd test "-Dtest=SearchViewRequestDTOTest"`

Expected: PASS, with the boundary assertions and no Spring application-context change.

- [ ] **Step 6: Commit the API contract**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/search src/test/java/com/watchwise/watchwise_api/search
git commit -m "feat(search): add visual search endpoint contract"
git show -s --format=%B HEAD
~~~

Expected: the commit message has no `Co-Authored-By` trailer or other self-attribution.

## Task 2: Extend TMDB search models without changing existing search behavior

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbMovieSearchResult.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbTvSearchResult.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbPersonSearchResult.java`
- Test: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/search/service/impl/SearchServiceImplTest.java`

**Interfaces:**
- Consumes the current cached `TmdbClient.searchMovies`, `searchTv`, and `searchPeople` methods.
- Produces `voteAverage` for content candidates and `knownForDepartment` for people.
- Does not change `SearchContentDTO`, `SearchPersonDTO`, or the legacy response schema.

- [ ] **Step 1: Write failing JSON mapping tests**

~~~java
assertThat(moviePage.results().getFirst().voteAverage()).isEqualTo(8.4d);
assertThat(personPage.results().getFirst().knownForDepartment()).isEqualTo("Acting");
~~~

- [ ] **Step 2: Run the focused TMDB test to verify it fails**

Run: `./mvnw.cmd test "-Dtest=TmdbClientTest"`

Expected: compilation failure because the record accessors do not exist.

- [ ] **Step 3: Add Jackson-mapped optional fields and compatibility constructors**

~~~java
@JsonProperty("vote_average") Double voteAverage
~~~

Add the optional score after the current core identifiers. Preserve existing convenience constructors by passing null for the new optional field. Add `@JsonProperty("known_for_department") String knownForDepartment` to the person record.

- [ ] **Step 4: Add a raw-search regression test and run focused tests**

~~~java
SearchResultDTO result = service.search(viewerId, "night", SearchType.MOVIE, 1, 20);

assertThat(result.contents()).singleElement()
        .extracting(SearchContentDTO::title)
        .isEqualTo("Night Shift");
~~~

Run: `./mvnw.cmd test "-Dtest=TmdbClientTest,SearchServiceImplTest"`

Expected: PASS; additional TMDB fields are additive and legacy DTOs remain unchanged.

- [ ] **Step 5: Commit the TMDB model extension**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/common/tmdb src/test/java/com/watchwise/watchwise_api/common/tmdb src/test/java/com/watchwise/watchwise_api/search
git commit -m "feat(tmdb): expose search card metadata"
git show -s --format=%B HEAD
~~~

Expected: the commit message has no `Co-Authored-By` trailer or other self-attribution.

## Task 3: Read exact visual windows from TMDB's fixed-size pages

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/search/service/TmdbSearchWindowReader.java`
- Test: `src/test/java/com/watchwise/watchwise_api/search/service/TmdbSearchWindowReaderTest.java`

**Interfaces:**
- Produces `TmdbSearchWindowReader.readPage(int visualPage, int visualSize, IntFunction<TmdbLookupResult<TmdbSearchPage<T>>> loader): Window<T>` and `readRange(long firstSourceIndex, int count, IntFunction<TmdbLookupResult<TmdbSearchPage<T>>> loader): Range<T>`.
- `Window<T>` exposes ordered results for the exact visual page plus `totalResults`, `totalPages`, and `hasNext`.
- Calculates TMDB pages from zero-based source indexes and the fixed source page size of 20. It always reads source page 1 before calculating the remaining valid page set.

- [ ] **Step 1: Write the failing fixed-page-window tests**

~~~java
IntFunction<TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>>> loader =
        sourcePage -> {
            requestedSourcePages.add(sourcePage);
            return found(tmdbPage(sourcePage, 22, movies(sourcePage)));
        };

Window<TmdbMovieSearchResult> window = reader.readPage(2, 18, loader);

assertThat(window.results()).extracting(TmdbMovieSearchResult::id)
        .containsExactly("19", "20", "21", "22");
assertThat(window.totalResults()).isEqualTo(22L);
assertThat(window.totalPages()).isEqualTo(2);
assertThat(window.hasNext()).isFalse();
assertThat(requestedSourcePages).containsExactly(1, 2);
~~~

- [ ] **Step 2: Run the window-reader test to verify it fails**

Run: `./mvnw.cmd test "-Dtest=TmdbSearchWindowReaderTest"`

Expected: compilation failure because `TmdbSearchWindowReader` does not exist.

- [ ] **Step 3: Implement the generic, side-effect-free reader**

Create nested `Window<T>` and `Range<T>` results and accept a loader function rather than injecting `TmdbClient`. Both methods load source page 1 first, derive the total from it, and avoid a requested source page whose first index is already beyond that total. The reader calculates source indexes from `(visualPage - 1) * visualSize`, groups valid indexes by `(index / 20) + 1`, invokes each needed source page at most once, and orders retained results by logical source index. `readPage` derives `totalPages` from `ceil(totalResults / visualSize)`, never from TMDB's own 20-item page count, then caps it at the route's maximum of 500. `readRange` returns the indexed items and total for the Content planner. An unavailable lookup throws `TmdbUnavailableException`; an empty first page returns an empty result.

- [ ] **Step 4: Add edge tests and run the reader suite**

~~~java
assertThat(reader.readPage(1, 18, sourcePage -> found(tmdbPage(sourcePage, 0, List.of()))))
        .extracting(Window::totalResults, Window::totalPages, Window::hasNext)
        .containsExactly(0L, 0, false);

assertThat(reader.readPage(500, 18, sourcePage -> found(tmdbPage(1, 1, List.of(movie("1")))))
        .extracting(Window::totalResults, Window::results)
        .containsExactly(1L, List.of());

assertThatThrownBy(() -> reader.readPage(1, 18, page -> unavailable()))
        .isInstanceOf(TmdbUnavailableException.class);
~~~

Run: `./mvnw.cmd test "-Dtest=TmdbSearchWindowReaderTest"`

Expected: PASS.

- [ ] **Step 5: Commit the window reader**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/search/service/TmdbSearchWindowReader.java src/test/java/com/watchwise/watchwise_api/search/service/TmdbSearchWindowReaderTest.java
git commit -m "feat(search): read exact tmdb result windows"
git show -s --format=%B HEAD
~~~

Expected: the commit message has no `Co-Authored-By` trailer or other self-attribution.

## Task 4: Implement deterministic combined Content paging

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/search/service/ContentSearchPagePlanner.java`
- Test: `src/test/java/com/watchwise/watchwise_api/search/service/ContentSearchPagePlannerTest.java`

**Interfaces:**
- Produces `ContentSearchPagePlanner.Plan plan(long movieTotal, long seriesTotal, int page, int size)`.
- `Plan` exposes `long totalResults()`, `int totalPages()`, `boolean hasNext()`, and `List<Slot> slots()`.
- `Slot` identifies `MovieOrSeriesType type` and zero-based `long sourceIndex`.

- [ ] **Step 1: Write failing planner tests for both-source and exhausted-source pages**

~~~java
assertThat(planner.plan(5, 3, 1, 6).slots()).containsExactly(
        new Slot(MovieOrSeriesType.MOVIE, 0),
        new Slot(MovieOrSeriesType.SERIES, 0),
        new Slot(MovieOrSeriesType.MOVIE, 1),
        new Slot(MovieOrSeriesType.SERIES, 1),
        new Slot(MovieOrSeriesType.MOVIE, 2),
        new Slot(MovieOrSeriesType.SERIES, 2));

assertThat(planner.plan(5, 2, 2, 4).slots()).containsExactly(
        new Slot(MovieOrSeriesType.MOVIE, 2),
        new Slot(MovieOrSeriesType.MOVIE, 3),
        new Slot(MovieOrSeriesType.MOVIE, 4));
~~~

- [ ] **Step 2: Run the planner test to verify it fails**

Run: `./mvnw.cmd test "-Dtest=ContentSearchPagePlannerTest"`

Expected: compilation failure because `ContentSearchPagePlanner` does not exist.

- [ ] **Step 3: Implement the pure planner**

Implement `sourceAt(long globalIndex, long movieTotal, long seriesTotal)` with these exact branches:

~~~java
long paired = Math.min(movieTotal, seriesTotal);
if (globalIndex < paired * 2) {
    return globalIndex % 2 == 0
            ? new Slot(MOVIE, globalIndex / 2)
            : new Slot(SERIES, globalIndex / 2);
}
if (movieTotal > seriesTotal) {
    return new Slot(MOVIE, globalIndex - seriesTotal);
}
return new Slot(SERIES, globalIndex - movieTotal);
~~~

The controller already bounds page and size to 500 and 20, so the planner can use a long calculation without an unnecessary overflow-specific request path. The planner retains the exact combined result total but caps accessible `totalPages` and `hasNext` at 500, preventing a `hasNext` response from pointing to a page the controller rejects. A page beyond the end is valid and has an empty slot list while retaining the exact result total.

- [ ] **Step 4: Add boundary tests and run the planner suite**

~~~java
assertThat(planner.plan(0, 0, 1, 18))
        .extracting(Plan::totalResults, Plan::totalPages, Plan::hasNext)
        .containsExactly(0L, 0, false);
assertThat(planner.plan(2, 5, 2, 4).slots())
        .containsExactly(new Slot(MovieOrSeriesType.SERIES, 2),
                new Slot(MovieOrSeriesType.SERIES, 3),
                new Slot(MovieOrSeriesType.SERIES, 4));
~~~

Run: `./mvnw.cmd test "-Dtest=ContentSearchPagePlannerTest"`

Expected: PASS.

- [ ] **Step 5: Commit the pager**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/search/service/ContentSearchPagePlanner.java src/test/java/com/watchwise/watchwise_api/search/service/ContentSearchPagePlannerTest.java
git commit -m "feat(search): plan combined content pages"
git show -s --format=%B HEAD
~~~

Expected: the commit message has no `Co-Authored-By` trailer or other self-attribution.

## Task 5: Assemble search cards from TMDB candidates and viewer state

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/search/service/impl/SearchContentViewAssembler.java`
- Test: `src/test/java/com/watchwise/watchwise_api/search/service/impl/SearchContentViewAssemblerTest.java`

**Interfaces:**
- Consumes ordered movie/series TMDB candidates, `User viewer`, and `ContentCardAssembler`.
- Produces `List<SearchContentViewDTO> assemble(User viewer, List<SearchContentCandidate> candidates)` preserving candidate order.
- Requests `BASIC_METADATA`, `VIEWER_STATE`, and `WATCHLIST_PROGRESS` exactly once for all distinct coordinates.

- [ ] **Step 1: Write a failing assembler test that captures one batched card request**

~~~java
List<SearchContentViewDTO> cards = assembler.assemble(viewer, List.of(movieCandidate, seriesCandidate));

assertThat(cards).extracting(SearchContentViewDTO::posterUrl)
        .containsExactly("https://image.tmdb.org/t/p/w500/movie.jpg", "https://image.tmdb.org/t/p/w500/series.jpg");
assertThat(cards).extracting(SearchContentViewDTO::voteAverage).containsExactly(8.1d, 7.4d);
verify(contentCardAssembler).assemble(specs.capture(), context.capture(), fields.capture());
assertThat(fields.getValue()).containsExactlyInAnyOrder(
        ContentCardFieldSet.BASIC_METADATA,
        ContentCardFieldSet.VIEWER_STATE,
        ContentCardFieldSet.WATCHLIST_PROGRESS);
~~~

- [ ] **Step 2: Run the assembler test to verify it fails**

Run: `./mvnw.cmd test "-Dtest=SearchContentViewAssemblerTest"`

Expected: compilation failure because `SearchContentViewAssembler` does not exist.

- [ ] **Step 3: Implement candidate conversion and card assembly**

Create a package-private `SearchContentCandidate` record with coordinate, title, posterPath, releaseDate, runtime, voteAverage, and fallback metadata. Convert all candidates to `ContentCardSpec` values, invoke `ContentCardAssembler` once with the viewer's language, region, and id, then map in original candidate order. Resolve `posterUrl` through `TmdbImageUrlBuilder.posterUrl`, using the assembled card poster path before the search candidate fallback. Never persist a missing coordinate.

- [ ] **Step 4: Cover partial TMDB metadata and run tests**

~~~java
when(contentCardAssembler.assemble(any(), any(), any())).thenReturn(Map.of());

assertThat(assembler.assemble(viewer, List.of(movieCandidate))).singleElement()
        .satisfies(card -> {
            assertThat(card.title()).isEqualTo("Night Shift");
            assertThat(card.type()).isEqualTo(MovieOrSeriesType.MOVIE);
            assertThat(card.posterUrl()).isEqualTo("https://image.tmdb.org/t/p/w500/movie.jpg");
            assertThat(card.previewStatus()).isEqualTo(ContentPreviewStatus.PARTIAL);
        });
~~~

Run: `./mvnw.cmd test "-Dtest=SearchContentViewAssemblerTest,ContentCardAssemblerTest"`

Expected: PASS; an external card remains renderable when optional enrichment is unavailable.

- [ ] **Step 5: Commit the search-card assembler**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/search/service/impl/SearchContentViewAssembler.java src/test/java/com/watchwise/watchwise_api/search/service/impl/SearchContentViewAssemblerTest.java
git commit -m "feat(search): assemble visual content cards"
git show -s --format=%B HEAD
~~~

Expected: the commit message has no `Co-Authored-By` trailer or other self-attribution.

## Task 6: Implement the visual search service and integrate every scope

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/search/service/impl/SearchViewServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/search/controller/SearchController.java`
- Test: `src/test/java/com/watchwise/watchwise_api/search/service/impl/SearchViewServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/search/controller/SearchControllerIntegrationTest.java`

**Interfaces:**
- Consumes `SearchViewService`, `TmdbSearchWindowReader`, `ContentSearchPagePlanner`, `SearchContentViewAssembler`, `TmdbClient`, and `UserRepository`.
- Produces a fully populated `SearchViewDTO` for each admitted scope.

- [ ] **Step 1: Write failing service tests for each direct scope and correct page metadata**

~~~java
SearchViewDTO result = service.search(viewerId, "night", SearchViewScope.MOVIE, 2, 18);

assertThat(result.contents()).hasSize(4);
assertThat(result.totalResults()).isEqualTo(22L);
assertThat(result.totalPages()).isEqualTo(2);
assertThat(result.hasNext()).isFalse();
verify(tmdbClient).searchMovies("night", "pt-BR", 1);
verify(tmdbClient).searchMovies("night", "pt-BR", 2);

SearchViewDTO people = service.search(viewerId, "night", SearchViewScope.PERSON, 1, 18);
assertThat(people.people()).singleElement()
        .extracting(SearchPersonViewDTO::knownForDepartment)
        .isEqualTo("Acting");
~~~

- [ ] **Step 2: Run the service test to verify it fails**

Run: `./mvnw.cmd test "-Dtest=SearchViewServiceImplTest"`

Expected: compilation failure because `SearchViewServiceImpl` does not exist.

- [ ] **Step 3: Implement MOVIE, SERIES, and PERSON scopes**

Wire `@GetMapping("/search/view")` only after `SearchViewServiceImpl` exists in this task, so every committed intermediate task remains bootable. Trim `q`, enforce the post-trim 3-through-100 range, apply the existing throttle key/window, and default omitted page/size to 1/18. Use `TmdbSearchWindowReader` rather than passing the visual page directly to TMDB. This guarantees that `page = 2, size = 18` yields results 19 through 36, not TMDB results 21 through 40. For movie and series, call `SearchContentViewAssembler`; for people, create `SearchPersonViewDTO` values directly with `TmdbImageUrlBuilder.profileUrl`. Populate page metadata from the reader's logical-window totals.

- [ ] **Step 4: Write the failing CONTENT scope test for source paging and exhaustion**

~~~java
SearchViewDTO result = service.search(viewerId, "night", SearchViewScope.CONTENT, 2, 4);

assertThat(result.totalResults()).isEqualTo(7L);
assertThat(result.contents()).extracting(SearchContentViewDTO::type)
        .containsExactly(ContentType.MOVIE, ContentType.MOVIE, ContentType.MOVIE);
verify(tmdbClient, atLeastOnce()).searchMovies("night", "pt-BR", 1);
verify(tmdbClient, atLeastOnce()).searchTv("night", "pt-BR", 1);
~~~

- [ ] **Step 5: Implement CONTENT source fetches using the planner**

First call `readRange(0, 1, loader)` for movies and TV to obtain source totals. Feed those totals to `ContentSearchPagePlanner`. Group planned slots by type, use `readRange(firstSourceIndex, count, loader)` to load only each type's required contiguous source positions, then map slots back to candidates in slot order. The TMDB cache makes the repeated source-page-one access local to the process. If a source page changes during the request and a planned source item is absent, omit that card but retain the source metadata observed for the request; do not substitute an item from a different rank.

- [ ] **Step 6: Run the complete visual search test suite**

Run: `./mvnw.cmd test "-Dtest=SearchViewServiceImplTest,SearchContentViewAssemblerTest,ContentSearchPagePlannerTest,SearchControllerTest,SearchControllerIntegrationTest"`

Expected: PASS, including unavailable TMDB, empty pages, one content-card assembly per response, no `Content` creation, and unchanged `GET /search` behavior.

- [ ] **Step 7: Run HTTP binding and application-context tests**

~~~java
mockMvc.perform(get("/search/view").param("q", " night ").param("scope", "MOVIE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.query").value("night"));
mockMvc.perform(get("/search/view").param("q", "x".repeat(101)).param("scope", "MOVIE"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
mockMvc.perform(get("/search").param("q", "night").param("type", "MOVIE"))
        .andExpect(status().isOk());
~~~

Run: `./mvnw.cmd test "-Dtest=SearchControllerTest,SearchControllerIntegrationTest,SearchEndToEndIntegrationTest"`

Expected: PASS; Spring starts with `SearchViewServiceImpl`, invalid binding returns `ApiError`, and the legacy route remains available.

- [ ] **Step 8: Commit the visual service**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/search src/test/java/com/watchwise/watchwise_api/search
git commit -m "feat(search): add paginated visual search views"
git show -s --format=%B HEAD
~~~

Expected: the commit message has no `Co-Authored-By` trailer or other self-attribution.

## Task 7: Synchronize documentation and verify the repository

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`
- Modify: `docs/telas-reqs-definitivo.md`
- Test: `src/test/java/com/watchwise/watchwise_api/search/controller/SearchEndToEndIntegrationTest.java`

**Interfaces:**
- Documents the exact production route delivered by Tasks 1 through 5.
- Does not add documentation files to a code commit.

- [ ] **Step 1: Add an end-to-end contract test**

~~~java
mockMvc.perform(get("/search/view")
                .cookie(authCookie)
                .param("q", "night")
                .param("scope", "CONTENT")
                .param("page", "1")
                .param("size", "18"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalResults").isNumber())
        .andExpect(jsonPath("$.contents").isArray())
        .andExpect(jsonPath("$.people").isEmpty());
~~~

- [ ] **Step 2: Run the end-to-end test with Docker available**

Run: `./mvnw.cmd test "-Dtest=SearchEndToEndIntegrationTest"`

Expected: PASS using the configured Testcontainers/PostgreSQL environment.

- [ ] **Step 3: Update local documentation**

Document the scope parameter, defaults, response schemas, mixed-content ordering, TMDB score meaning, no-write behavior, card enrichment fallback, and screen-to-endpoint mapping. Append only the actually implemented work to the current-day progress entry. Add the business rule under Search, because the ordering and paging are search semantics.

- [ ] **Step 4: Run final focused and full verification**

Run: `./mvnw.cmd test "-Dtest=TmdbClientTest,SearchViewServiceImplTest,SearchContentViewAssemblerTest,ContentSearchPagePlannerTest,SearchControllerTest,SearchControllerIntegrationTest,SearchEndToEndIntegrationTest"`

Run: `./mvnw.cmd test`

Expected: both commands PASS.

- [ ] **Step 5: Inspect the final diff and working tree**

~~~powershell
git diff --check
git status --short
git log -5 --format='%h %s%n%b'
~~~

Expected: no whitespace errors; code and tests are committed; the four documentation files remain intentional local-only modifications; no commit message has a `Co-Authored-By` trailer or other self-attribution.

## Plan self-review

- **Spec coverage:** Task 1 creates the additive route and validation; Task 2 provides TMDB fields; Task 3 makes all visual page sizes exact against TMDB's fixed paging; Task 4 provides exact Content totals/page order; Task 5 reuses the card read model; Task 6 maps all four scopes and failure paths; Task 7 verifies and synchronizes required local documentation.
- **No placeholders:** The route, DTO shape, scope values, page ordering, validation ranges, source-page behavior, tests, commands, and commits are specified explicitly.
- **Type consistency:** `SearchViewService.search`, `SearchViewDTO`, `SearchContentViewDTO`, `SearchPersonViewDTO`, `ContentSearchPagePlanner.Plan`, and `ContentSearchPagePlanner.Slot` keep the same names across the tasks.
- **Deliberate trade-off:** Content scope uses stable alternating source relevance rather than TMDB multi-search relevance because the latter cannot provide a correct content-only total. The raw `/search` endpoint keeps multi-search for callers that prefer that behavior.

