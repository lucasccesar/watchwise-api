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

Create nested `Window<T>` and `Range<T>` results and accept a loader function rather than injecting `TmdbClient`. Both methods load source page 1 first, derive the total from it, and avoid a requested source page whose first index is already beyond that total. The reader calculates source indexes from `(visualPage - 1) * visualSize`, groups valid indexes by `(index / 20) + 1`, invokes each needed source page at most once, and orders retained results by logical source index. `readPage` derives `totalPages` from `ceil(totalResults / visualSize)`, never from TMDB's own 20-item page count. `readRange` returns the indexed items and total for the Content planner. An unavailable lookup throws `TmdbUnavailableException`; an empty first page returns an empty result.

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

