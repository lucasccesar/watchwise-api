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

