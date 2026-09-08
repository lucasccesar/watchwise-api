# Search TMDB Proxy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add typed, cached TMDB movie, series, person, and multi-search operations, then stop tracking the requested local tooling and documentation paths without deleting them locally.

**Architecture:** Extend `common.tmdb` with a generic page envelope and endpoint-specific result records. `TmdbClient` keeps the existing retry/result semantics and receives four dedicated Caffeine caches keyed by an immutable normalized search key. The feature and repository-cleanup changes are verified and committed separately.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring `RestClient`, Jackson, Caffeine, JUnit 5, AssertJ, Spring `MockRestServiceServer`, Maven Wrapper.

## Global Constraints

- Implement only phase 8, item 4; do not add `SearchService`, `SearchController`, local aggregation, or persistence.
- Send `query`, preferred language, 1-based page, and `include_adult=false` on every TMDB search request.
- Preserve the TMDB result order and retain `media_type` in multi-search results.
- Normalize cache queries with `trim().toLowerCase(Locale.ROOT)` while sending the caller's trimmed spelling to TMDB on cache misses.
- Key search caches by normalized query, type, language, and page; use a 10-minute TTL and a maximum of 10,000 entries per cache.
- Never cache `Unavailable`; retain the existing one-retry behavior and never persist TMDB responses.
- Keep `.mvn`, `mvnw`, and `mvnw.cmd` tracked.
- Preserve unrelated working-tree changes, especially `.codex/config.toml` and `.claude/settings.local.json`.
- Use Conventional Commits with no body, examples, purpose clauses, `Co-Authored-By`, or other self-attribution.

---

## File map

- Create `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbSearchPage.java`: generic TMDB pagination envelope.
- Create `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbMovieSearchResult.java`: typed movie result fields.
- Create `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbTvSearchResult.java`: typed series result fields.
- Create `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbPersonSearchResult.java`: typed person result fields.
- Create `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbMultiSearchResult.java`: mixed result fields including `mediaType`.
- Create `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbSearchType.java`: cache-key discriminator for `MOVIE`, `TV`, `PERSON`, and `MULTI`.
- Create `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbSearchCacheKey.java`: normalized immutable cache key.
- Modify `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClient.java`: four search methods using retry and Caffeine.
- Modify `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCacheConfig.java`: four typed search-cache beans with TTL and maximum size.
- Modify `src/main/resources/application-dev.properties`: active search-cache settings.
- Modify `src/main/resources/application-prod.properties`: production property template.
- Create `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbSearchModelsTest.java`: JSON contract tests.
- Modify `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientTest.java`: endpoint request and parsing tests.
- Modify `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientCachingTest.java`: real-cache behavior tests.
- Modify `docs/context/development-stages.md`: mark phase 8 item 4 implemented.
- Modify `docs/context/progress.md`: chronologically record the shipped proxy work.
- Modify `.gitignore`: ignore the approved local-only paths.
- Remove from Git index only: `.agents`, `.claude`, `.codex`, `.superpowers`, `docs`, `CLAUDE.md`, and `AGENTS.md`.

---

### Task 1: Add typed TMDB search response models

**Files:**

- Create: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbSearchModelsTest.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbSearchPage.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbMovieSearchResult.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbTvSearchResult.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbPersonSearchResult.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbMultiSearchResult.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbSearchType.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbSearchCacheKey.java`

**Interfaces:**

- Produces: `TmdbSearchPage<T>(int page, List<T> results, int totalPages, long totalResults)`.
- Produces: typed result accessors for TMDB IDs, display names, image paths, dates, and multi-search `mediaType`.
- Produces: `TmdbSearchCacheKey.of(String query, TmdbSearchType type, String language, int page)`.

- [ ] **Step 1: Write the failing model contract tests**

Add Jackson tests with hand-written JSON. The key mixed-page assertion is:

```java
TmdbSearchPage<TmdbMultiSearchResult> page = objectMapper.readValue(json,
        objectMapper.getTypeFactory().constructParametricType(TmdbSearchPage.class, TmdbMultiSearchResult.class));

assertThat(page.page()).isEqualTo(2);
assertThat(page.totalPages()).isEqualTo(7);
assertThat(page.totalResults()).isEqualTo(123L);
assertThat(page.results()).extracting(TmdbMultiSearchResult::mediaType)
        .containsExactly("movie", "person", "tv");
assertThat(page.results()).extracting(TmdbMultiSearchResult::id)
        .containsExactly("603", "287", "1396");
```

Also assert movie `title`/`poster_path`/`release_date`, series `name`/`poster_path`/`first_air_date`, person `name`/`profile_path`, and cache-key equality for `" Matrix "` versus `"matrix"`.

- [ ] **Step 2: Run the model test and verify RED**

Run:

```powershell
.\mvnw.cmd test "-Dtest=TmdbSearchModelsTest"
```

Expected: compilation fails because the new TMDB search model types do not exist.

- [ ] **Step 3: Add the minimal typed records and key**

Use `@JsonIgnoreProperties(ignoreUnknown = true)` on wire records and `@JsonProperty` for snake-case fields. The multi record must have this exact data surface:

```java
public record TmdbMultiSearchResult(
        String id,
        @JsonProperty("media_type") String mediaType,
        String title,
        String name,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("profile_path") String profilePath,
        @JsonProperty("release_date") String releaseDate,
        @JsonProperty("first_air_date") String firstAirDate) {
}
```

The cache key factory must be:

```java
public static TmdbSearchCacheKey of(String query, TmdbSearchType type, String language, int page) {
    return new TmdbSearchCacheKey(query.trim().toLowerCase(Locale.ROOT), type, language, page);
}
```

- [ ] **Step 4: Run the model test and verify GREEN**

Run the same focused Maven command. Expected: all `TmdbSearchModelsTest` tests pass with zero failures and errors.

---

### Task 2: Add cached TMDB search operations

**Files:**

- Modify: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientCachingTest.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClient.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCacheConfig.java`
- Modify: `src/main/resources/application-dev.properties`
- Modify: `src/main/resources/application-prod.properties`

**Interfaces:**

- Consumes: all Task 1 models and `TmdbSearchCacheKey`.
- Produces: `searchMovies`, `searchTv`, `searchPeople`, and `searchMulti`, each returning a typed `TmdbLookupResult<TmdbSearchPage<...>>`.

- [ ] **Step 1: Write failing endpoint tests**

Add one `TmdbClientTest` case per endpoint. Match all query parameters independently so parameter ordering does not make the tests brittle:

```java
mockServer.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/movie?")))
        .andExpect(queryParam("query", "The Matrix"))
        .andExpect(queryParam("language", "pt-BR"))
        .andExpect(queryParam("page", "2"))
        .andExpect(queryParam("include_adult", "false"))
        .andRespond(withSuccess(moviePageJson, MediaType.APPLICATION_JSON));
```

Assert parsed fields from each typed result. For `searchMulti`, use a literal movie/person/series response and assert the three result types remain in exactly that order. Add an unavailable test with two server errors and assert `isUnavailable()`.

- [ ] **Step 2: Write failing cache behavior tests**

Wire all four real cache beans in `TmdbClientCachingTest`, clear them in `@BeforeEach`, and set:

```java
@TestPropertySource(properties = {
        "app.tmdb.details-cache-ttl-hours=24",
        "app.tmdb.search-cache-ttl-minutes=10",
        "app.tmdb.search-cache-max-size=10000"
})
```

Cover these observable mutations:

- Two calls using `" Matrix "` and `"matrix"` make one real call.
- Changing language makes a second call.
- Changing page makes a second call.
- Movie and multi searches for the same text do not share a cache entry.
- Eight concurrent identical calls make one real call.
- An unavailable response is not cached, so the next call reaches TMDB and succeeds.

- [ ] **Step 3: Run focused tests and verify RED**

Run:

```powershell
.\mvnw.cmd test "-Dtest=TmdbClientTest,TmdbClientCachingTest"
```

Expected: compilation fails because the four client methods and search-cache dependencies do not exist.

- [ ] **Step 4: Implement the four cache beans**

Add one bean per response type. Use a shared helper with both bounds:

```java
private <T> Cache<TmdbSearchCacheKey, TmdbLookupResult<T>> newSearchCache(long ttlMinutes, long maximumSize) {
    return Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(ttlMinutes))
            .maximumSize(maximumSize)
            .build();
}
```

Inject `app.tmdb.search-cache-ttl-minutes` and `app.tmdb.search-cache-max-size` into each bean. Add active development values `10` and `10000`, and matching commented production template values.

- [ ] **Step 5: Implement typed cached calls in `TmdbClient`**

Inject the four new caches. Each public method trims the outgoing query, builds the typed key, and calls the existing generic `cachedLookup`. The movie method shape is:

```java
public TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>> searchMovies(
        String query, String language, int page) {
    String trimmedQuery = query.trim();
    TmdbSearchCacheKey key = TmdbSearchCacheKey.of(trimmedQuery, TmdbSearchType.MOVIE, language, page);
    return cachedLookup(tmdbMovieSearchCache, key, () -> callWithRetry(() -> tmdbRestClient.get()
            .uri(uriBuilder -> uriBuilder.path("/search/movie")
                    .queryParam("query", trimmedQuery)
                    .queryParam("language", language)
                    .queryParam("page", page)
                    .queryParam("include_adult", false)
                    .build())
            .retrieve()
            .body(new ParameterizedTypeReference<TmdbSearchPage<TmdbMovieSearchResult>>() {}),
            "movie search"));
}
```

Generalize `cachedLookup` from `Cache<String, ...>` to `Cache<K, ...>` so detail-cache behavior stays unchanged. Implement the equivalent typed paths `/search/tv`, `/search/person`, and `/search/multi` with `TV`, `PERSON`, and `MULTI` discriminators.

- [ ] **Step 6: Run focused tests and verify GREEN**

Run the Task 2 focused command. Expected: all model, client, and caching tests pass with zero failures and errors.

- [ ] **Step 7: Run sibling TMDB and compilation checks**

Run:

```powershell
.\mvnw.cmd test "-Dtest=TmdbSearchModelsTest,TmdbClientTest,TmdbClientCachingTest,ContentDetailsServiceImplTest"
```

Expected: all selected tests pass; existing detail calls still cache and retry correctly.

---

### Task 3: Synchronize documentation, verify, and commit the feature

**Files:**

- Modify: `docs/context/development-stages.md`
- Modify: `docs/context/progress.md`

**Interfaces:**

- Consumes: the verified proxy from Tasks 1 and 2.
- Produces: an accurate chronological implementation record and completed item marker.

- [ ] **Step 1: Update tracking documentation**

Mark only phase 8 item 4 as implemented with the date `2026-09-08`. Append a `## 2026-09-08 — Proxy TMDB de busca` entry to `progress.md` describing the four typed endpoints, mixed-order preservation, retry, cache key, 10-minute TTL, 10,000-entry bound, no persistence, and focused verification. Do not update `business-rules.md`: public search semantics are not implemented until item 5.

- [ ] **Step 2: Run static diff checks**

Run:

```powershell
git diff --check
git status --short
```

Confirm the only preexisting unrelated changes remain `.codex/config.toml` and `.claude/settings.local.json`.

- [ ] **Step 3: Run the complete Maven suite**

Run:

```powershell
.\mvnw.cmd test
```

Expected: build success with zero failures and errors. If Docker is unavailable, report the exact Testcontainers failure; do not claim the suite passed.

- [ ] **Step 4: Review recurring bug patterns**

Confirm: all new exceptions still resolve through the existing `TmdbLookupResult` path; concurrent identical cache loads collapse atomically; no cross-feature state exists; there are no request DTO fields to validate; and all four external search siblings apply the same language/page/adult/cache rules.

- [ ] **Step 5: Commit only the functional change**

Stage the exact source, test, property, and two tracking-doc files. Do not stage `.codex/config.toml` or `.claude/settings.local.json`.

```powershell
git commit -m "feat(search): add cached TMDB search proxy"
git show -s --format=full HEAD
```

Confirm aloud that the message has no `Co-Authored-By` or other self-attribution.

---

### Task 4: Ignore and untrack local-only project files

**Files:**

- Modify: `.gitignore`
- Remove from index only: `.agents`, `.claude`, `.codex`, `.superpowers`, `docs`, `CLAUDE.md`, `AGENTS.md`
- Preserve as tracked: `.mvn`, `mvnw`, `mvnw.cmd`

**Interfaces:**

- Consumes: the user's approved cleanup list.
- Produces: clean future clones without local agent configuration or project documentation, while retaining local copies in the current workspace.

- [ ] **Step 1: Add anchored ignore rules**

Add these exact root-level rules:

```gitignore
/.agents/
/.claude/
/.codex/
/.superpowers/
/docs/
/CLAUDE.md
/AGENTS.md
```

Do not add `.mvn`, `mvnw`, or `mvnw.cmd`.

- [ ] **Step 2: Remove tracked paths only from the index**

Resolve and confirm the repository root first, then run:

```powershell
git rm -r --cached -- .agents .claude .codex docs CLAUDE.md AGENTS.md
```

If `.superpowers` is tracked, run `git rm -r --cached -- .superpowers`; otherwise its ignore rule alone is sufficient. Do not use filesystem deletion commands.

- [ ] **Step 3: Prove files remain local and wrapper files remain tracked**

Run:

```powershell
Test-Path '.agents'
Test-Path '.claude'
Test-Path '.codex'
Test-Path 'docs'
Test-Path 'CLAUDE.md'
Test-Path 'AGENTS.md'
git ls-files -- .mvn mvnw mvnw.cmd
git check-ignore -v -- .agents .claude .codex .superpowers docs CLAUDE.md AGENTS.md
```

Expected: every `Test-Path` returns `True`; all three Maven Wrapper paths remain listed; every cleanup target matches a `.gitignore` rule.

- [ ] **Step 4: Inspect the staged cleanup**

Run:

```powershell
git diff --cached --stat
git diff --cached -- .gitignore
git status --short
```

Confirm the staged deletions exactly match the approved paths and `.gitignore`; `.codex/config.toml`'s local content and the untracked `.claude/settings.local.json` still exist on disk.

- [ ] **Step 5: Commit the repository cleanup**

```powershell
git commit -m "chore(repo): untrack local project files"
git show -s --format=full HEAD
```

Confirm aloud that the message has no `Co-Authored-By` or other self-attribution.

- [ ] **Step 6: Run final verification**

Run:

```powershell
.\mvnw.cmd test "-Dtest=TmdbSearchModelsTest,TmdbClientTest,TmdbClientCachingTest"
git status --short --ignored
git log -3 --oneline
```

Expected: focused tests pass; cleanup targets appear ignored rather than deleted locally; Maven Wrapper files remain usable; no push is performed.

---

## Self-review checklist

- Every requirement in phase 8 item 4 maps to Task 1 or Task 2.
- `LIST` and `USER` receive no client methods, preventing accidental external calls at this layer.
- Multi-search keeps its original ordered list and its discriminator; separation belongs to item 5.
- Cache failures are retried on the next request and concurrent cache misses are collapsed.
- The cache is both time-bounded and size-bounded.
- Documentation changes ship in the functional commit before `docs` is intentionally untracked.
- Repository cleanup removes paths only from the Git index and preserves the Maven Wrapper.
- No unrelated working-tree changes are staged.
