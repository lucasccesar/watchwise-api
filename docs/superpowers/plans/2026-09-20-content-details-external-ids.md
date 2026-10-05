# Content Details External IDs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Include TMDB IMDb IDs for movie, series, and episode content details, plus Facebook, Instagram, and Twitter IDs for movies and series, using the existing details requests.

**Architecture:** Add one shared `TmdbExternalIds` record and attach it to the existing movie, TV, and episode full-detail records. Extend each current detail request's `append_to_response` list with `external_ids`, then map the nested values directly into `ContentDetailsDTO`; no separate client method, cache, or HTTP call is introduced.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring `RestClient`, Jackson records, JUnit 5, Mockito, MockRestServiceServer, AssertJ, Maven.

## Global Constraints

- Use the existing `TmdbClient` retry/not-found behavior without changing it.
- Missing TMDB external identifiers must be returned as `null`.
- Preserve the existing `ContentDetailsDTO` field order contract except for adding the four identifier fields at the end.
- Use the official TMDB `external_ids` JSON property and snake_case field names.
- Do not add separate HTTP requests for external IDs.

## Files

- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbExternalIds.java` for the nested TMDB payload.
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbMovieFullDetails.java`, `TmdbTvFullDetails.java`, and `TmdbEpisodeFullDetails.java` to expose nested external IDs.
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClient.java` to append `external_ids` to existing detail requests.
- Modify: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentDetailsDTO.java` to expose response fields.
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImpl.java` to map identifiers for movie, series, and episode details.
- Test: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientTest.java` for request URLs and deserialization.
- Test: `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImplTest.java` for DTO mapping.

### Task 1: Add the external ID model and detail request coverage

**Interfaces:**
- Produces `TmdbExternalIds(String imdbId, String facebookId, String instagramId, String twitterId)` with `@JsonProperty` annotations for `imdb_id`, `facebook_id`, `instagram_id`, and `twitter_id`.
- Adds `externalIds` properties to the three existing full-detail records, mapped from `external_ids`.
- `TmdbClient.getMovieFullDetails` requests `credits,watch/providers,alternative_titles,videos,external_ids`.
- `TmdbClient.getTvFullDetails` requests `aggregate_credits,watch/providers,alternative_titles,videos,external_ids`.
- `TmdbClient.getEpisodeFullDetails` requests `external_ids` together with its existing `language` query parameter.

- [ ] **Step 1: Write failing client tests**

Add tests in `TmdbClientTest` that expect the three exact URLs and return payloads such as:

```json
{"id":"603","external_ids":{"imdb_id":"tt0133093","facebook_id":"thematrixmovie","instagram_id":"thematrixmovie","twitter_id":"thematrixmovie"}}
```

Assert the parsed record values and use `mockServer.verify()` so a request without the appended parameter fails the test.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```powershell
./mvnw.cmd -Dmaven.repo.local=C:\Users\ggluk\.m2\repository -Dtest=TmdbClientTest test
```

Expected: failures because the current request URLs omit `external_ids` and the detail records do not expose `externalIds`.

- [ ] **Step 3: Implement the minimum TMDB model and request changes**

Create the record with Jackson annotations, append the nested property to the three full-detail records, and add `external_ids` to the three existing client URI builders. Do not introduce another client method or cache.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the same Maven command. Expected: all `TmdbClientTest` tests pass.

- [ ] **Step 5: Commit the client/model change**

```powershell
git add src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbExternalIds.java src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbMovieFullDetails.java src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbTvFullDetails.java src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbEpisodeFullDetails.java src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClient.java src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientTest.java
git commit -m "feat(tmdb): append external ids to content details"
```

### Task 2: Map identifiers into content details

**Interfaces:**
- Extend `ContentDetailsDTO` with `String imdbId`, `String facebookId`, `String instagramId`, and `String twitterId`.
- Movie and series details map all four fields from their nested `TmdbExternalIds` value.
- Episode details map only `imdbId`; its social fields are `null`.
- Season details map all four fields as `null`.
- A null nested `external_ids` object or null individual value remains null without affecting the response.

- [ ] **Step 1: Write failing service tests**

Add one focused test for each content type in `ContentDetailsServiceImplTest`. Configure the existing full-detail stubs with a `TmdbExternalIds` value and assert the returned DTO fields. For a missing nested value, assert nulls. Keep existing scheduling/runtime stubs consistent with neighboring tests.

Representative assertions:

```java
assertThat(result.imdbId()).isEqualTo("tt0133093");
assertThat(result.facebookId()).isEqualTo("thematrixmovie");
assertThat(result.instagramId()).isEqualTo("thematrixmovie");
assertThat(result.twitterId()).isEqualTo("thematrixmovie");
```

- [ ] **Step 2: Run the focused service tests and verify RED**

Run:

```powershell
./mvnw.cmd -Dmaven.repo.local=C:\Users\ggluk\.m2\repository -Dtest=ContentDetailsServiceImplTest test
```

Expected: compilation/test failures because `ContentDetailsDTO` has no identifier accessors and the service does not pass identifier values.

- [ ] **Step 3: Implement the minimum DTO and mapping changes**

Add the four fields to the DTO record and update each `new ContentDetailsDTO(...)` call in `ContentDetailsServiceImpl`. Use small null-safe accessors or direct conditional expressions consistent with the existing service style; do not alter persistence or scheduling behavior.

- [ ] **Step 4: Run the focused service tests and verify GREEN**

Run the same Maven command. Expected: all content detail service tests pass.

- [ ] **Step 5: Commit the DTO mapping change**

```powershell
git add src/main/java/com/watchwise/watchwise_api/content/dto/ContentDetailsDTO.java src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImpl.java src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImplTest.java
git commit -m "feat(content): expose TMDB external ids in details"
```

### Task 3: Run regression verification

- [ ] **Step 1: Check formatting and the complete diff**

Run `git diff --check` and inspect `git diff HEAD~2..HEAD` for field ordering, request parameters, and accidental changes outside the planned files.

- [ ] **Step 2: Run all tests**

```powershell
./mvnw.cmd -Dmaven.repo.local=C:\Users\ggluk\.m2\repository test
```

Expected: Maven exits with code 0 and all test cases pass.

- [ ] **Step 3: Verify the final requirement mapping**

Confirm movie and series JSON expose `imdbId`, `facebookId`, `instagramId`, and `twitterId`; episode JSON exposes `imdbId` with social values null; and no `external_ids`-specific client method or extra HTTP request exists.
