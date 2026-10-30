# Person Visual Read Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox - [ ] syntax for tracking.

**Goal:** Add a presentation-ready Person screen read model that renders the hero, follow count, viewer progress, filterable and sortable credit cards without breaking the existing raw person endpoint.

**Architecture:** Keep GET /people/{personTmdbId} and its DTOs unchanged. Add authenticated GET /people/{personTmdbId}/view backed by a focused PersonViewService: one cached TMDB person aggregate supplies identity, hero metadata and normalized credits; batched local readers supply follow count, content statistics and viewer state before filtering, sorting and pagination. The view never persists Content, invokes /contents/details, or performs a TMDB lookup per credit.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, Jackson records, Maven, JUnit 5, Mockito, Testcontainers PostgreSQL.

## Global Constraints

- GET /people/{personTmdbId}/view is an additive API contract; preserve the raw /people/{personTmdbId} route, raw DTO field meanings and default ordering.
- Add the route and schemas to docs/context/openapi.yaml in the same change; this is an intentional API-contract extension.
- The visual read uses one TmdbClient.getPersonAggregate lookup and aggregate credit data only. Never call /contents/details, persist Content, or call TMDB once per card.
- Select hero data from aggregate backdrop_path/popularity. If no usable backdrop exists, return hero = null; the frontend renders its fallback.
- Every local enrichment query is set-based. Chunk ContentStatsService.getStatsBatch calls at its current maximum of 100 IDs.
- Apply participation, watchStatus, inWatchlist, sort and direction to the complete normalized filmography before response pagination.
- The visual page defaults to 24 cards and has a maximum of 24. A page value of zero remains an alias for the first page.
- creditedEpisodeCount means episodes credited to the person by TMDB, never the series total episode count.
- Viewer watchlist and custom-list membership stay separate. Never reinterpret isInList as watchlist.
- Series status combines the existing local ContentViewerStateService state with batched DiaryEntry episode counts and persisted SeriesProgressMetadata. If released-episode metadata is unavailable, return WatchStatus.UNKNOWN and null progress counts. Do not make synchronous full-series schedule requests during this read.
- followerCount is an aggregate only. The route never exposes identities of users following the person.
- Existing POST/DELETE /users/me/follow-people/{personTmdbId} behavior, CSRF and rate limiting remain unchanged.
- Documentation files change with code but stay uncommitted unless the user explicitly requests a documentation commit.
- Commit code and tests with Conventional Commits, one-line subjects, no Co-Authored-By and no push.

---

## File structure

- Create person/dto/PersonViewDTO.java, PersonHeroDTO.java, PersonCreditViewDTO.java, PersonCreditSort.java and PersonCreditSortDirection.java.
- Create person/service/PersonViewService.java plus person/service/impl/PersonViewServiceImpl.java.
- Create person/service/impl/PersonAggregateReader.java and PersonFilmographyNormalizer.java, shared by raw and visual reads.
- Create person/service/impl/PersonCreditViewAssembler.java to combine aggregate credit data with batch local enrichments.
- Create person/service/impl/PersonCreditViewerStateResolver.java to supplement common viewer state with local series progress, without remote schedule reads.
- Modify common/tmdb/TmdbPersonAggregateCredit.java to map backdropPath and popularity.
- Create migration V71__add-followed-people-person-index.sql and extend FollowedPersonRepository.
- Modify PersonServiceImpl only to reuse the aggregate reader and normalizer while retaining PersonResponseDTO behavior.
- Modify DiaryEntryRepository and SeriesProgressMetadataRepository only to add filtered batch projections required by PersonCreditViewerStateResolver.
- Modify PersonController to add the visual GET route.
- Update OpenAPI, business rules, progress and telas-reqs-definitivo.md after behavior lands.

### Task 1: Share aggregate reading and preserve complete credit source data

**Files:**
- Create: src/main/java/com/watchwise/watchwise_api/person/service/impl/PersonAggregateReader.java
- Create: src/main/java/com/watchwise/watchwise_api/person/service/impl/PersonFilmographyNormalizer.java
- Modify: src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbPersonAggregateCredit.java
- Modify: src/main/java/com/watchwise/watchwise_api/person/service/impl/PersonServiceImpl.java
- Test: src/test/java/com/watchwise/watchwise_api/person/service/impl/PersonAggregateReaderTest.java
- Test: src/test/java/com/watchwise/watchwise_api/person/service/impl/PersonFilmographyNormalizerTest.java
- Test: src/test/java/com/watchwise/watchwise_api/person/service/impl/PersonServiceImplTest.java

**Consumes:** TmdbClient.getPersonAggregate, TmdbLookupResult and PersonParticipation.

**Produces:** one validated aggregate reader and deterministic normalized MOVIE/SERIES credits retaining hero/sort source metadata, without changing PersonResponseDTO.

- [ ] **Step 1: Write failing reader and normalizer tests**

~~~
assertThatThrownBy(() -> reader.read("invalid", "en-US"))
        .isInstanceOf(BadRequestException.class);
assertThatThrownBy(() -> reader.read("287", "en-US"))
        .isInstanceOf(NotFoundException.class);
assertThatThrownBy(() -> reader.read("287", "en-US"))
        .isInstanceOf(TmdbUnavailableException.class);
~~~

Stub Found, NotFound, Unavailable and Found(null) aggregate lookups. Verify a 20+-digit ID is rejected before the TMDB client is invoked.

Add normalizer coverage proving cast/crew merge by (MovieOrSeriesType, tmdbId), all unique characters/jobs survive, movie/TV invalid IDs are discarded, and first usable backdropPath, popularity and episodeCount survive duplicate credits.

- [ ] **Step 2: Run the focused tests and verify failure**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PersonAggregateReaderTest,PersonFilmographyNormalizerTest,PersonServiceImplTest"
~~~

Expected: compilation fails because the reader/normalizer and aggregate metadata fields do not exist.

- [ ] **Step 3: Implement reader, normalizer and TMDB metadata**

Implement:

~~~java
TmdbPersonAggregate read(String personTmdbId, String language);
~~~

Move ID validation and Found/NotFound/Unavailable mapping from PersonServiceImpl into PersonAggregateReader. A Found(null) result is TmdbUnavailableException.

Append these fields to TmdbPersonAggregateCredit:

~~~java
@JsonProperty("backdrop_path") String backdropPath,
Double popularity
~~~

Keep its existing nine-argument convenience constructor, delegating trailing fields as null.

Move PersonServiceImpl's mutable cast/crew deduplication into PersonFilmographyNormalizer.normalize(TmdbPersonAggregate). Return immutable credits with title, poster path, backdrop path, release date, popularity, credited episode count, roles and participation.

- [ ] **Step 4: Refactor raw reading with a compatibility regression**

Make PersonServiceImpl call the reader and normalizer. Retain its existing local watched/list queries, fixed release-date ordering, participation filtering, pagination and PersonResponseDTO output.

Add a regression test proving raw PersonResponseDTO never exposes hero, popularity, backdropPath, creditedEpisodeCount, visual stats or visual viewer state.

- [ ] **Step 5: Run focused tests and verify pass**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PersonAggregateReaderTest,PersonFilmographyNormalizerTest,PersonServiceImplTest,PersonControllerTest"
~~~

Expected: all selected tests pass.

- [ ] **Step 6: Commit code and tests**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/person src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbPersonAggregateCredit.java src/test/java/com/watchwise/watchwise_api/person
git commit -m "refactor(person): share aggregate filmography reading"
~~~

Confirm the commit has no Co-Authored-By trailer.

### Task 2: Add an efficient aggregate follower count

**Files:**
- Create: src/main/resources/db/migration/V71__add-followed-people-person-index.sql
- Modify: src/main/java/com/watchwise/watchwise_api/followedperson/repository/FollowedPersonRepository.java
- Test: src/test/java/com/watchwise/watchwise_api/followedperson/repository/FollowedPersonRepositoryTest.java

**Consumes:** followed_people and its existing unique (user_id, person_tmdb_id) constraint.

**Produces:** indexed countByPersonTmdbId used by the Person hero.

- [ ] **Step 1: Write a failing repository test**

~~~
assertThat(repository.countByPersonTmdbId("287")).isEqualTo(2L);
assertThat(repository.countByPersonTmdbId("999")).isZero();
~~~

Persist two different users following person 287 and one following another person. Assert no follower identity is returned.

- [ ] **Step 2: Run the test and verify failure**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=FollowedPersonRepositoryTest"
~~~

Expected: compilation fails because the count method and person-ID index do not exist.

- [ ] **Step 3: Add index and repository method**

Create V71:

~~~sql
CREATE INDEX idx_followed_people_person_tmdb_id
    ON followed_people (person_tmdb_id);
~~~

Add:

~~~java
long countByPersonTmdbId(String personTmdbId);
~~~

Do not alter the uniqueness constraint or follow/unfollow writes.

- [ ] **Step 4: Run the test and verify pass**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=FollowedPersonRepositoryTest"
~~~

Expected: Testcontainers/PostgreSQL repository tests pass.

- [ ] **Step 5: Commit code and tests**

~~~powershell
git add src/main/resources/db/migration/V71__add-followed-people-person-index.sql src/main/java/com/watchwise/watchwise_api/followedperson/repository/FollowedPersonRepository.java src/test/java/com/watchwise/watchwise_api/followedperson/repository/FollowedPersonRepositoryTest.java
git commit -m "feat(person): count person followers"
~~~

Confirm the commit has no Co-Authored-By trailer.

### Task 3: Define additive visual DTOs and query vocabulary

**Files:**
- Create: src/main/java/com/watchwise/watchwise_api/person/dto/PersonViewDTO.java
- Create: src/main/java/com/watchwise/watchwise_api/person/dto/PersonHeroDTO.java
- Create: src/main/java/com/watchwise/watchwise_api/person/dto/PersonCreditViewDTO.java
- Create: src/main/java/com/watchwise/watchwise_api/person/dto/PersonCreditSort.java
- Create: src/main/java/com/watchwise/watchwise_api/person/dto/PersonCreditSortDirection.java
- Create: src/main/java/com/watchwise/watchwise_api/person/service/PersonViewService.java
- Test: src/test/java/com/watchwise/watchwise_api/person/dto/PersonViewDTOTest.java

**Consumes:** ContentCardStatsDTO, ContentCardViewerStateDTO, ContentPreviewStatus, WatchStatus, PersonParticipation and PageResponseDTO.

**Produces:** a presentation contract separate from PersonResponseDTO.

- [ ] **Step 1: Write failing DTO tests**

~~~
PersonCreditViewDTO credit = new PersonCreditViewDTO(
        "550", MovieOrSeriesType.MOVIE, "Fight Club", "https://image.tmdb.org/t/p/w342/x.jpg",
        LocalDate.of(1999, 10, 15), List.of("Narrator"), List.of(), PersonParticipation.CAST,
        null, 42.0d, new ContentCardStatsDTO(8.2d, 12L, 4L, 3L), viewerState, false,
        ContentPreviewStatus.AVAILABLE);

assertThat(credit.creditedEpisodeCount()).isNull();
assertThat(credit.viewerState().inWatchlist()).isFalse();
~~~

Test defensive copies for role lists, page metadata retention and that PersonViewDTO permits hero null when no backdrop exists.

- [ ] **Step 2: Run the tests and verify failure**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PersonViewDTOTest"
~~~

Expected: compilation fails because the visual records/enums do not exist.

- [ ] **Step 3: Create records, enums and service interface**

Implement:

~~~java
public record PersonHeroDTO(
        MovieOrSeriesType type, String tmdbId, String title, Integer releaseYear, String backdropUrl) {}

public record PersonCreditViewDTO(
        String tmdbId, MovieOrSeriesType type, String title, String posterUrl, LocalDate releaseDate,
        List<String> characters, List<String> jobs, PersonParticipation participation,
        Integer creditedEpisodeCount, Double popularity, ContentCardStatsDTO stats,
        ContentCardViewerStateDTO viewerState, boolean isInAnyList,
        ContentPreviewStatus previewStatus) {}

public record PersonViewDTO(
        PersonDetailsDTO person, Long followersCount, PersonHeroDTO hero, PersonProgressDTO progress,
        PageResponseDTO<PersonCreditViewDTO> credits) {}
~~~

Define PersonCreditSort as RELEASE_DATE, TITLE, POPULARITY, AVERAGE_SCORE and CREDITED_EPISODE_COUNT. Define PersonCreditSortDirection as ASC and DESC.

Define:

~~~java
PersonViewDTO getPersonView(
        UUID viewerId, String personTmdbId, PersonParticipation participation, WatchStatus watchStatus,
        Boolean inWatchlist, PersonCreditSort sortBy, PersonCreditSortDirection sortDirection,
        Integer page, Integer size);
~~~

- [ ] **Step 4: Run the tests and verify pass**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PersonViewDTOTest"
~~~

Expected: all selected tests pass.

- [ ] **Step 5: Commit code and tests**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/person/dto src/main/java/com/watchwise/watchwise_api/person/service/PersonViewService.java src/test/java/com/watchwise/watchwise_api/person/dto/PersonViewDTOTest.java
git commit -m "feat(person): add visual read contracts"
~~~

Confirm the commit has no Co-Authored-By trailer.

### Task 4: Assemble visual credits with batched local enrichment

**Files:**
- Create: src/main/java/com/watchwise/watchwise_api/person/service/impl/PersonCreditViewAssembler.java
- Create: src/main/java/com/watchwise/watchwise_api/person/service/impl/PersonCreditViewerStateResolver.java
- Create: src/main/java/com/watchwise/watchwise_api/person/service/impl/PersonViewServiceImpl.java
- Modify: src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java
- Modify: src/main/java/com/watchwise/watchwise_api/seriesprogress/repository/SeriesProgressMetadataRepository.java
- Test: src/test/java/com/watchwise/watchwise_api/person/service/impl/PersonCreditViewAssemblerTest.java
- Test: src/test/java/com/watchwise/watchwise_api/person/service/impl/PersonCreditViewerStateResolverTest.java
- Test: src/test/java/com/watchwise/watchwise_api/person/service/impl/PersonViewServiceImplTest.java

**Consumes:** Tasks 1-3, ContentViewerStateService, ContentStatsService, DiaryEntryRepository, SeriesProgressMetadataRepository, FollowedPersonRepository, UserRepository and PageRequestFactory.

**Produces:** one visual read that batches state/statistics, chooses a deterministic hero and filters/sorts before pagination.

- [ ] **Step 1: Write failing enrichment tests**

~~~
assertThat(view.credits().content().getFirst().stats().averageScore()).isEqualTo(8.5d);
assertThat(view.credits().content().getFirst().viewerState().myRating()).isEqualTo(9);
assertThat(view.credits().content().getFirst().viewerState().lastWatchedDate())
        .isEqualTo(LocalDate.of(2026, 9, 20));
assertThat(view.credits().content().getFirst().viewerState().inWatchlist()).isTrue();
assertThat(view.credits().content().getFirst().isInAnyList()).isTrue();
~~~

Cover these cases:

- aggregate-only movie returns its title/poster/date, null average score and zero counts without creating Content;
- series with complete local progress returns PARTIALLY_WATCHED or WATCHED with counts;
- series lacking released-episode metadata returns UNKNOWN and null counts without ContentScheduleReader interaction;
- a direct SERIES diary entry returns WATCHED before schedule-derived state;
- no usable backdrop returns hero null;
- hero preference is usable backdrop, then higher popularity, newer release date, case-insensitive title, type and TMDB ID;
- stats requests contain only existing local content IDs and batches contain at most 100 IDs;
- AVERAGE_SCORE puts nulls last in either direction and always uses title/type/TMDB ID tie-breakers;
- watchStatus and inWatchlist filters occur before page slicing.

- [ ] **Step 2: Run focused tests and verify failure**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PersonCreditViewAssemblerTest,PersonCreditViewerStateResolverTest,PersonViewServiceImplTest"
~~~

Expected: compilation fails because the state resolver, assembler and visual service do not exist.

- [ ] **Step 3: Implement the batch Person credit state resolver**

Add these filtered local projections:

~~~java
List<SeriesEpisodeCount> findEpisodeSeriesCountsByUserIdAndSeriesTmdbIdIn(
        UUID userId, Collection<String> seriesTmdbIds);

List<SeriesProgressMetadataProjection> findAllBySeriesTmdbIdIn(
        Collection<String> seriesTmdbIds);
~~~

PersonCreditViewerStateResolver first calls ContentViewerStateService.resolve for common local fields:
rating, last watched date, watchlist and custom-list memberships. It then reads the current
DiaryEntryRepository.findWatchedMediaForPersonCredits result, filtered episode counts and persisted
SeriesProgressMetadata in batch.

For a series, a direct SERIES diary entry is WATCHED. With episode activity and known
regularReleasedEpisodeCount, watched count equal to or above released count is WATCHED; a lower positive
count is PARTIALLY_WATCHED. With episode activity and absent metadata, return UNKNOWN and null counts.
Without any series activity, return UNWATCHED. Do not change ContentViewerStateServiceImpl, whose generic
schedule-backed semantics remain intact.

- [ ] **Step 4: Implement PersonCreditViewAssembler**

For all normalized credits, build ContentCoordinate values and call:

~~~java
ContentViewerStateService.Resolution resolution =
        contentViewerStateService.resolve(viewerId, coordinates, Map.of());
~~~

Use resolution.existingContentIdsByCoordinate only for local stats. Request stats through ContentStatsService.getStatsBatch in chunks of 100. Use PersonCreditViewerStateResolver to map watchStatus, myRating, lastWatchedDate, inWatchlist, episode counts and isInAnyList.

Keep title, poster, date, popularity and creditedEpisodeCount from the aggregate. Convert the poster path with TmdbImageUrlBuilder.posterUrl. Mark a row PARTIAL when title, poster or date is absent, but retain it in the response.

Do not use ContentCardAssembler: it may resolve card metadata remotely and its DTO lacks the person-specific roles. Do not use TmdbCardMetadataResolver, ContentService.getOrCreateReference, ContentScheduleReader or /contents/details.

- [ ] **Step 5: Implement PersonViewServiceImpl**

Read viewer preferences and aggregate through PersonAggregateReader. Normalize once, assemble all visual cards, then execute this order:

1. participation default ALL;
2. optional watchStatus equality;
3. optional inWatchlist equality;
4. requested sort/direction;
5. 1-based page slice with default/max size 24;
6. PageResponseDTO construction.

The default order is RELEASE_DATE descending. Release date, popularity, average score and credited episode count keep nulls last for either direction. Title is case-insensitive. Every comparator ends with title, type and TMDB ID.

Set followersCount from countByPersonTmdbId. Reuse FollowedPersonService.isFollowing. Build hero only from a normalized credit with usable backdropPath; use TmdbImageUrlBuilder.backdropUrl and make no additional TMDB request.

- [ ] **Step 6: Run focused tests and verify pass**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PersonCreditViewAssemblerTest,PersonCreditViewerStateResolverTest,PersonViewServiceImplTest,PersonServiceImplTest"
~~~

Expected: all selected tests pass; raw-person contract tests stay green.

- [ ] **Step 7: Commit code and tests**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/person src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java src/main/java/com/watchwise/watchwise_api/seriesprogress/repository/SeriesProgressMetadataRepository.java src/test/java/com/watchwise/watchwise_api/person
git commit -m "feat(person): assemble visual credit cards"
~~~

Confirm the commit has no Co-Authored-By trailer.

### Task 5: Add the visual route, document it and verify compatibility

**Files:**
- Modify: src/main/java/com/watchwise/watchwise_api/person/controller/PersonController.java
- Modify: docs/context/openapi.yaml
- Modify: docs/context/business-rules.md
- Modify: docs/context/progress.md
- Modify: docs/telas-reqs-definitivo.md
- Test: src/test/java/com/watchwise/watchwise_api/person/controller/PersonControllerTest.java
- Test: src/test/java/com/watchwise/watchwise_api/person/controller/PersonControllerIntegrationTest.java
- Test: src/test/java/com/watchwise/watchwise_api/followedperson/controller/FollowedPersonControllerIntegrationTest.java

**Consumes:** PersonViewService from Tasks 3-4.

**Produces:** authenticated GET /people/{personTmdbId}/view with validated parameters, documented visual semantics and unchanged raw/follow routes.

- [ ] **Step 1: Write failing MVC and compatibility tests**

~~~java
mockMvc.perform(get("/people/287/view")
        .param("participation", "CAST")
        .param("watchStatus", "PARTIALLY_WATCHED")
        .param("inWatchlist", "true")
        .param("sortBy", "AVERAGE_SCORE")
        .param("sortDirection", "DESC")
        .param("page", "2")
        .param("size", "24")
        .with(authentication(authentication)))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.followersCount").value(2481))
    .andExpect(jsonPath("$.credits.page").value(2));

mockMvc.perform(get("/people/287").with(authentication(authentication)))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.hero").doesNotExist())
    .andExpect(jsonPath("$.followersCount").doesNotExist())
    .andExpect(jsonPath("$.credits.content[0].stats").doesNotExist());
~~~

Add integration tests for 401, invalid TMDB ID/enum/page/size as 400, a visual size above 24 clamped to 24, unknown TMDB person as 404, unavailable aggregate as 502 and hero-null fallback. Retain existing CSRF, 204 and rate-limit tests for follow/unfollow.

- [ ] **Step 2: Run controller tests and verify failure**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PersonControllerTest,PersonControllerIntegrationTest,FollowedPersonControllerIntegrationTest"
~~~

Expected: requests to the visual route return 404.

- [ ] **Step 3: Add the controller route**

~~~java
@GetMapping("/{personTmdbId}/view")
public ResponseEntity<PersonViewDTO> getPersonView(
        @PathVariable String personTmdbId,
        @RequestParam(required = false) PersonParticipation participation,
        @RequestParam(required = false) WatchStatus watchStatus,
        @RequestParam(required = false) Boolean inWatchlist,
        @RequestParam(required = false) PersonCreditSort sortBy,
        @RequestParam(required = false) PersonCreditSortDirection sortDirection,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size) {
    return ResponseEntity.ok(personViewService.getPersonView(
            currentUserId(), personTmdbId, participation, watchStatus, inWatchlist,
            sortBy, sortDirection, page, size));
}
~~~

Extract the current security-context cast into one private currentUserId() method and use it for both raw and visual handlers. Do not change the raw handler signature or response type.

- [ ] **Step 4: Update API and screen documentation**

Document the new route, parameter enums, max page size 24, PersonViewDTO, hero fallback, aggregate-only follower count, creditedEpisodeCount semantics, UNKNOWN series fallback and raw compatibility.

Add a Person section to telas-reqs-definitivo.md with main request, hero, progress, credit-card fields, filters/sorts/pagination, follow actions, loading order and errors. State that the frontend does not call /contents/details or persist Content while rendering credits.

- [ ] **Step 5: Run focused and full verification**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PersonAggregateReaderTest,PersonFilmographyNormalizerTest,PersonCreditViewAssemblerTest,PersonCreditViewerStateResolverTest,PersonViewServiceImplTest,PersonServiceImplTest,PersonControllerTest,PersonControllerIntegrationTest,FollowedPersonRepositoryTest,FollowedPersonControllerIntegrationTest"
.\mvnw.cmd test
~~~

Expected: zero failures and zero errors. If Surefire logs delayed fork shutdown after System.exit(0), record Maven exit status and final totals.

- [ ] **Step 6: Commit code and tests only**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/person src/main/java/com/watchwise/watchwise_api/followedperson src/main/java/com/watchwise/watchwise_api/content src/main/resources/db/migration/V71__add-followed-people-person-index.sql src/test/java/com/watchwise/watchwise_api/person src/test/java/com/watchwise/watchwise_api/followedperson src/test/java/com/watchwise/watchwise_api/content
git commit -m "feat(person): add visual person screen route"
~~~

Leave documentation changes uncommitted unless the user explicitly requests a documentation commit. Confirm the commit has no Co-Authored-By trailer.

## Plan self-review

- **Structure:** raw and visual contracts are separated; aggregate reading, credit normalization, local enrichment and HTTP handling have distinct responsibilities.
- **Performance:** one cached person aggregate, no per-credit TMDB detail read, no Content persistence, set-based local state, stats batched at 100 and follower count indexed.
- **Compatibility:** the existing raw person endpoint and follow mutations remain unchanged. The visual contract is opt-in.
- **Correctness:** filters and sort precede pagination; null handling is deterministic; watchlist and custom-list membership remain independent; credited episode count is semantically explicit.
- **Cross-feature consistency:** the plan improves the shared direct-series state rule instead of inventing a Person-specific state interpretation.
- **Security:** the visual read uses existing cookie authentication; it reveals only follower totals, not identities; follow writes retain CSRF/rate limits.
- **Scope:** no new domain entity exists; V71 contains only the index needed by the aggregate count.
- **Placeholder scan:** every task supplies file paths, interfaces, failing assertions, commands, expected outcomes and commit boundaries.

## Execution handoff

Plan complete and saved to docs/superpowers/plans/2026-10-16-person-view-read-model.md. Two execution options:

1. **Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?

