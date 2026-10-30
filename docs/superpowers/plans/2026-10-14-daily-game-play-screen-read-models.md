# Daily Game Play Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build secure backend support for Movie by Poster, Series by Poster, Person by Face and Episode by Frame.

**Architecture:** Add one deep `DailyGamePlayService` module for the four daily play screens. Its three methods read state, submit a guess and give up; its implementation hides repositories, locking, duplicate detection, snapshot decoding, answer redaction and visual mapping. Existing raw routes and DTOs remain compatibility adapters. A dedicated media module serves short-lived, user-bound image grants, never a direct hidden TMDB asset.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, PostgreSQL JSONB, Caffeine, `TmdbClient`, Java ImageIO, Maven, JUnit 5, Mockito and Testcontainers.

## Global Constraints

- Scope is only `MOVIE_BY_POSTER`, `SERIES_BY_POSTER`, `PERSON_BY_FACE` and `EPISODE_BY_FRAME`; do not add a generic variant registry in this delivery.
- Keep raw routes, DTOs and direct-image semantics unchanged: `/games/today`, `/games/{gameType}/today`, `/games/{challengeDate}/{gameType}`, raw attempts, give-up and raw searches.
- Visual routes below use protected media URLs. The visual overview `/games/today/view` must also stop returning direct poster/profile URLs while games are open.
- Visual GETs perform no writes, create no `Content`, and perform no TMDB metadata lookup.
- Presentation metadata is frozen in `answerSnapshot` and `displaySnapshot` during generation. Legacy snapshots remain readable with null/empty optional presentation fields.
- Use the injected UTC `Clock` for challenge date and `nextResetAt`; never calculate a local midnight in the client.
- Both `COMPLETED` and `FAILED` are terminal. Terminal visual responses may expose the answer and all six episode frames.
- Episode by Frame stores exactly six distinct frame paths. While open, slots `0..min(attemptsUsed, 5)` are allowed. Terminal results allow all six.
- Duplicate guess rejection applies only to current-day visual actions, where canonical attempt identities are persisted. Historical raw routes retain their existing behavior because old attempt details are cleaned up.
- A repeated current-day candidate returns `409 Conflict` before changing attempts, score, sharing state or history.
- Search candidate images remain allowed: they represent user-selected candidates, not the hidden challenge media.
- Visual search is capped at six entries, deduplicates IDs before enrichment and uses existing cached/single-flight TMDB metadata. Never call `/contents/details?ids=...`.
- Explicit terminal CTA clicks may use existing `POST /contents/reference`; visual reads may not. Person CTAs use the existing person route.
- Documentation updates stay uncommitted unless the user requests a documentation commit.
- Every commit uses Conventional Commits and has no `Co-Authored-By` or other self-attribution.

---

## File Structure

- Create: `dailygame/dto/DailyGameMediaRefDTO.java` — protected media URL, slot and visual variant.
- Create: `dailygame/dto/DailyGameCandidateViewDTO.java` — reused search and attempt candidate model.
- Create: `dailygame/dto/DailyGameAnswerPreviewDTO.java` — terminal answer and navigation reference.
- Create: `dailygame/dto/DailyGameAttemptOutcomeDTO.java` — correctness feedback.
- Create: `dailygame/dto/DailyGameAttemptViewDTO.java` — visual attempt row.
- Create: `dailygame/dto/DailyGamePlayViewDTO.java` — full screen read model.
- Create: `dailygame/dto/DailyGamePlayActionResponseDTO.java` — updated state after an action.
- Create: `dailygame/dto/DailyGameMediaResponseDTO.java` — internal media bytes plus media type.
- Create: `dailygame/media/DailyGameMediaSlot.java` and `DailyGameImageVariant.java` — closed image-slot policy.
- Create: `dailygame/service/DailyGamePlayService.java` — gameplay module seam.
- Create: `dailygame/service/DailyGameMediaService.java` — grant issue/fetch seam.
- Create: `dailygame/service/impl/DailyGamePlayServiceImpl.java` — state loading and action adaptation.
- Create: `dailygame/service/impl/DailyGamePlayProjection.java` — package-private immutable assembler input.
- Create: `dailygame/service/impl/DailyGamePlayAssembler.java` — package-private visual mapper.
- Create: `dailygame/service/impl/DailyGameMediaServiceImpl.java` — grants, authorization, transformation and cache.
- Create: `dailygame/controller/DailyGameMediaController.java` — opaque media transport adapter.
- Modify: `dailygame/controller/DailyGameController.java` — visual read/action/search routes.
- Modify: `dailygame/service/impl/DailyGameServiceImpl.java` — package-private mutation result shared by raw and visual adapters.
- Modify: `dailygame/service/impl/DailyChallengeResponseAssembler.java` — visual overview media grants; raw mapping unchanged.
- Modify: `dailygame/generation/DailyChallengeSnapshotAssembler.java`, `MovieByPosterGenerator.java`, `SeriesByPosterGenerator.java`, `PersonByFaceGenerator.java`, `EpisodeByFrameGenerator.java` — v2 frozen presentation snapshots.
- Modify: `dailygame/service/impl/DailyChallengeSnapshotRepairServiceImpl.java` — preserve v1/v2 snapshots without repairing them during gameplay reads.
- Modify: `common/tmdb/TmdbClient.java` and `TmdbCacheConfig.java` — allowlisted image bytes and bounded caches.
- Modify: `dailygame/service/impl/DailyGameSearchServiceImpl.java` — visual search mode with capped enrichment.
- Modify: daily-game tests and `docs/context/openapi.yaml`, `progress.md`, `business-rules.md`, `telas-reqs-definitivo.md`.

## Public Interfaces

```java
public interface DailyGamePlayService {
    DailyGamePlayViewDTO readToday(UUID userId, DailyGameType gameType);

    DailyGamePlayActionResponseDTO submitToday(
            UUID userId, DailyGameType gameType, DailyGameAttemptRequest request);

    DailyGamePlayActionResponseDTO giveUpToday(UUID userId, DailyGameType gameType);
}

public interface DailyGameMediaService {
    DailyGameMediaRefDTO issue(
            UUID userId, UUID challengeId, DailyGameMediaSlot slot, DailyGameImageVariant variant);

    DailyGameMediaResponseDTO fetch(UUID userId, String grantToken);
}
```

```java
public record DailyGameMediaRefDTO(
        DailyGameMediaSlot slot, String url, DailyGameImageVariant variant, boolean revealed) {}

public record DailyGameCandidateViewDTO(
        DailyGameTargetKind targetKind, String tmdbId, String personTmdbId,
        String seriesTmdbId, Integer seasonNumber, Integer episodeNumber,
        String title, String imageUrl, Integer year, List<String> genres,
        Integer numberOfSeasons) {}

public record DailyGameAttemptOutcomeDTO(
        boolean correct, Boolean seriesCorrect, Boolean seasonCorrect, Boolean episodeCorrect) {}

public record DailyGameAttemptViewDTO(
        int attemptNumber, DailyGameCandidateViewDTO candidate,
        DailyGameAttemptOutcomeDTO outcome) {}

public record DailyGameAnswerPreviewDTO(
        DailyGameTargetKind targetKind, String tmdbId, String personTmdbId,
        String seriesTmdbId, Integer seasonNumber, Integer episodeNumber,
        String title, DailyGameMediaRefDTO image, Integer year, List<String> genres,
        String director, List<String> creators, List<String> knownForTitles,
        String seriesName, DailyGameMediaRefDTO seriesPoster, Integer seriesYear,
        LocalDate airDate) {}

public record DailyGamePlayViewDTO(
        LocalDate challengeDate, Instant nextResetAt, DailyGameType gameType,
        DailyGameTargetKind targetKind, int maxAttempts, int attemptsUsed,
        int attemptsRemaining, DailyGameViewStatus status, int score, int potentialScore,
        List<DailyGameMediaRefDTO> media, List<DailyGameAttemptViewDTO> attempts,
        DailyGameAnswerPreviewDTO answer, boolean canAttempt, boolean canGiveUp,
        boolean shareOnCompletion, boolean sharedToFeed) {}

public record DailyGamePlayActionResponseDTO(
        DailyGamePlayViewDTO state, DailyGameAttemptViewDTO currentAttempt) {}

public record DailyGameMediaResponseDTO(
        byte[] body, org.springframework.http.MediaType mediaType) {}
```

## Task 1: Define the visual contracts and one deep gameplay seam

**Files:**
- Create: all DTOs above except `DailyGameMediaResponseDTO`.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameMediaRefDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/media/DailyGameMediaSlot.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/media/DailyGameImageVariant.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGamePlayService.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGamePlayProjection.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGamePlayAssembler.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGamePlayAssemblerTest.java`

**Consumes:** `DailyChallenge`, `UserDailyGameResult`, decoded `DailyGameAttemptDTO`, frozen snapshots, media references and UTC time.

**Produces:** `DailyGamePlayAssembler.assemble(DailyGamePlayProjection projection)`; callers never pass individual state flags or score fields to the assembler.

- [ ] **Step 1: Write failing assembler tests**

Cover a movie on attempt two, a completed series, a person with no known-for entries and an episode on attempt three:

```java
assertThat(view.challengeDate()).isEqualTo(LocalDate.of(2026, 10, 14));
assertThat(view.nextResetAt()).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"));
assertThat(view.potentialScore()).isEqualTo(4);
assertThat(view.answer()).isNull();
assertThat(view.attempts()).hasSize(2);
```

Assert Episode feedback booleans are populated and the three non-episode feedback fields are null rather than false.

- [ ] **Step 2: Run the failing test**

```powershell
.\mvnw.cmd test "-Dtest=DailyGamePlayAssemblerTest"
```

Expected: compilation failure because the visual contracts and assembler do not exist.

- [ ] **Step 3: Implement the immutable projection and assembler**

Place date, reset instant, terminal answer, allowed media, decoded attempts and sharing state in `DailyGamePlayProjection`. Set `potentialScore` to `maxAttempts - attemptsUsed` only for `NOT_PLAYED` or `IN_PROGRESS`; otherwise set it to zero. Set `canAttempt` only for open results and `canGiveUp` only for an open result with attempts remaining.

- [ ] **Step 4: Verify and commit**

```powershell
.\mvnw.cmd test "-Dtest=DailyGamePlayAssemblerTest"
git add src/main/java/com/watchwise/watchwise_api/dailygame/dto src/main/java/com/watchwise/watchwise_api/dailygame/service src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGamePlayAssemblerTest.java
git commit -m "feat(dailygame): add play view contracts"
git log -1 --format=%B
```

Expected: tests pass and the commit has no self-attribution trailer.

## Task 2: Freeze complete presentation metadata at generation

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeSnapshotAssembler.java`
- Modify: `MovieByPosterGenerator.java`, `SeriesByPosterGenerator.java`, `PersonByFaceGenerator.java`, `EpisodeByFrameGenerator.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeSnapshotRepairServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeGeneratorTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeSnapshotRepairServiceImplTest.java`

**Consumes:** full movie/series details, `TmdbPersonAggregate`, episode hierarchy details and existing JSONB columns.

**Produces:** a backwards-compatible v2 snapshot envelope inside the existing JSON maps. Existing flat identity keys remain for the raw routes; the new nested maps are read by the visual gameplay module.

```json
{
  "schemaVersion": 2,
  "identity": {},
  "presentation": {},
  "variant": {}
}
```

`answerSnapshot` keeps its existing flat identity/title/image keys and adds `schemaVersion` plus a nested `presentation` map. `displaySnapshot` keeps `imageUrl`/`imagePaths` and adds `schemaVersion` plus a nested `variant` map. Do not replace or move the existing flat keys in this task.

- [ ] **Step 1: Write failing generation tests**

```java
assertThat(snapshot.answerSnapshot()).containsEntry("schemaVersion", 2);
assertThat((Map<?, ?>) snapshot.answerSnapshot().get("presentation"))
        .containsEntry("year", 1968)
        .containsEntry("director", "Stanley Kubrick")
        .containsEntry("creators", List.of("Dan Erickson"))
        .containsEntry("knownForTitles", List.of("The Office"))
        .containsEntry("airDate", "2006-05-11");
assertThat(snapshot.displaySnapshot()).containsEntry("schemaVersion", 2);
assertThat((Map<?, ?>) snapshot.displaySnapshot().get("variant"))
        .containsEntry("framePaths", List.of("/sixth.jpg", "/fifth.jpg", "/fourth.jpg", "/third.jpg", "/second.jpg", "/first.jpg"));
assertThat(snapshot.answerSnapshot()).containsEntry("title", "...");
```

Also assert a v1 snapshot is readable with null/empty optional metadata and is never rewritten by a gameplay GET.

- [ ] **Step 2: Run the failing tests**

```powershell
.\mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyChallengeSnapshotRepairServiceImplTest"
```

Expected: FAIL because existing snapshots do not contain the required presentation fields or schema version.

- [ ] **Step 3: Implement v2 snapshot generation**

After selecting a movie or series, use the cached full-detail lookup once; skip that candidate when required presentation data is unavailable. Derive person known-for titles from `TmdbPersonAggregate` credits. Carry episode `airDate` into the episode candidate. Persist the first six nonblank, distinct still paths in frozen order. Keep a v1 decoder that reads existing flat keys and returns absent optional fields without live TMDB reads or writes.

- [ ] **Step 4: Verify and commit**

```powershell
.\mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyChallengeGenerationServiceImplTest,DailyChallengeSnapshotRepairServiceImplTest"
git add src/main/java/com/watchwise/watchwise_api/dailygame/generation src/test/java/com/watchwise/watchwise_api/dailygame/generation src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeSnapshotRepairServiceImplTest.java
git commit -m "feat(dailygame): freeze play presentation data"
git log -1 --format=%B
```

Expected: tests pass and the commit has no self-attribution trailer.

## Task 3: Add protected media grants and bounded transformations

**Files:**
- Create: `dailygame/dto/DailyGameMediaResponseDTO.java`
- Create: `dailygame/service/DailyGameMediaService.java`
- Create: `dailygame/service/impl/DailyGameMediaServiceImpl.java`
- Create: `dailygame/controller/DailyGameMediaController.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClient.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCacheConfig.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameMediaServiceTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameMediaControllerIntegrationTest.java`

**Produces:**

```http
GET /games/play/media/{grantToken}
```

The opaque grant binds user ID, challenge ID, slot, variant, image fingerprint and expiry.

- [ ] **Step 1: Write failing media tests**

Verify that open poster/person games receive blur variants, not direct TMDB URLs; open episode games receive grants only for slots `0..min(attemptsUsed, 5)`; and both terminal states receive six distinct episode grants. Assert expired/foreign/stale grants fail. Assert untrusted hosts, redirects, invalid media types, oversized byte streams and oversized decoded images fail before transformation. Assert authorization happens before transformed-byte cache lookup.

- [ ] **Step 2: Run the failing tests**

```powershell
.\mvnw.cmd test "-Dtest=DailyGameMediaServiceTest,DailyGameMediaControllerIntegrationTest"
```

Expected: compilation failure because grants, media policy and transport do not exist.

- [ ] **Step 3: Add an allowlisted image operation to TmdbClient**

Add a typed method accepting only a stored TMDB-relative path. It must enforce HTTPS, the exact `image.tmdb.org` host when normalizing legacy absolute URLs, no query/fragment, no cross-host redirects, connection/read timeouts and a maximum byte count. `DailyGameMediaServiceImpl` must depend on this method, never construct an unrestricted `RestClient`.

- [ ] **Step 4: Implement grants, variants and caches**

Use `BLUR_26`, `BLUR_20`, `BLUR_14`, `BLUR_9`, `BLUR_5`, `BLUR_2` and `ORIGINAL`. Poster/person games use the blur level matching consumed attempts; terminal answers use original. Episode frames are original only after their slot is allowed. Re-encode transformed bytes to a permitted format without metadata, limit decoded pixels, and cache by challenge ID, slot, variant and source fingerprint using maximum weight plus TTL. Return `Cache-Control: private, no-store` for every media response and apply a dedicated media throttle.

- [ ] **Step 5: Verify and commit**

```powershell
.\mvnw.cmd test "-Dtest=DailyGameMediaServiceTest,DailyGameMediaControllerIntegrationTest,DailyChallengeResponseAssemblerTest"
git add src/main/java/com/watchwise/watchwise_api/dailygame src/main/java/com/watchwise/watchwise_api/common/tmdb src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(dailygame): protect play media"
git log -1 --format=%B
```

Expected: tests pass and the commit has no self-attribution trailer.

## Task 4: Add visual read and action routes through the gameplay module

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGamePlayServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameController.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameOverviewCardDTO.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGamePlayServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerIntegrationTest.java`

**Produces:**

```http
GET  /games/{gameType}/play
POST /games/{gameType}/play/attempt
POST /games/{gameType}/play/give-up
```

- [ ] **Step 1: Write failing service tests**

For every supported type, assert `readToday` returns UTC date/reset, visual media references, decoded current-day attempts and no open answer. Assert terminal results contain frozen answer metadata. Assert `submitToday` returns committed `currentAttempt` and updated state without a second state read. Assert `giveUpToday` returns score zero, a terminal answer and six frames for Episode by Frame.

- [ ] **Step 2: Run failing tests**

```powershell
.\mvnw.cmd test "-Dtest=DailyGamePlayServiceImplTest,DailyGameControllerIntegrationTest"
```

Expected: compilation failure because the service and visual routes do not exist.

- [ ] **Step 3: Extract one package-private mutation result**

Inside `DailyGameServiceImpl`, create an immutable result holding the locked challenge, persisted result, decoded current attempt and feedback. Both existing raw DTO mapping and `DailyGamePlayServiceImpl` must consume it. Do not duplicate candidate validation, locks, score, sharing or terminal transitions.

- [ ] **Step 4: Implement read/action behavior**

Reject types outside the four-screen scope with `BadRequestException`. Read exactly one challenge and viewer result, decode current-day attempt details and issue media grants. Map through `DailyGamePlayAssembler`. Keep raw endpoints unchanged. Replace non-terminal image URLs in `/games/today/view` with protected media grants while retaining its existing field names.

- [ ] **Step 5: Add controller tests**

Verify 401 unauthenticated responses, 400 unsupported type, 503 incomplete daily set, existing raw route JSON compatibility and protected `/games/play/media/` URLs on visual routes. Verify POST uses established CSRF behavior.

- [ ] **Step 6: Verify and commit**

```powershell
.\mvnw.cmd test "-Dtest=DailyGamePlayServiceImplTest,DailyGameControllerIntegrationTest,DailyGameControllerTest,DailyGameServiceImplTest"
git add src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(dailygame): add visual play routes"
git log -1 --format=%B
```

Expected: tests pass and the commit has no self-attribution trailer.

## Task 5: Reject repeated current-day guesses in the locked action path

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImplIntegrationTest.java`

- [ ] **Step 1: Write failing duplicate tests**

Submit the same movie, series, person and episode twice. Assert the second current-day action returns 409 and does not mutate the result:

```java
assertThat(result.getAttemptsUsed()).isEqualTo(1);
assertThat(result.getScore()).isZero();
assertThat(codec.read(result.getAttemptDetails())).hasSize(1);
```

Add a concurrent same-candidate test and assert only one transaction persists an attempt.

- [ ] **Step 2: Run failing tests**

```powershell
.\mvnw.cmd test "-Dtest=DailyGameServiceImplTest,DailyGameServiceImplIntegrationTest"
```

Expected: the second guess currently consumes an attempt.

- [ ] **Step 3: Implement canonical duplicate detection**

Inside the locked transaction and before incrementing attempts, compare the validated candidate key with decoded current-day keys:

```text
MOVIE:<tmdbId>
SERIES:<tmdbId>
PERSON:<personTmdbId>
EPISODE:<seriesTmdbId>:<seasonNumber>:<episodeNumber>
```

Throw `ConflictException("This candidate was already guessed")` on a match. Do not add historical duplicate guarantees.

- [ ] **Step 4: Verify and commit**

```powershell
.\mvnw.cmd test "-Dtest=DailyGameServiceImplTest,DailyGameServiceImplIntegrationTest,DailyGameControllerIntegrationTest"
git add src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "fix(dailygame): reject repeated play guesses"
git log -1 --format=%B
```

Expected: tests pass and the commit has no self-attribution trailer.

## Task 6: Add bounded visual search enrichment

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameSearchResultDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameController.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCacheConfig.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerIntegrationTest.java`

**Produces:**

```http
GET /games/{gameType}/search?q=...&page=1&size=6&view=true
GET /games/EPISODE_BY_FRAME/search/series?q=...&page=1&size=6&view=true
```

- [ ] **Step 1: Write failing tests**

Assert `view=true` returns at most six entries, retains IDs and title/image/year, enriches movie/series genres and season count from cached card metadata, leaves person search free of unnecessary detail calls, and enriches a repeated ID only once. Assert cold cache detail lookups never exceed six and unavailable decorative metadata returns null fields without failing search.

- [ ] **Step 2: Run failing tests**

```powershell
.\mvnw.cmd test "-Dtest=DailyGameSearchServiceImplTest,DailyGameControllerIntegrationTest"
```

Expected: visual mode and metadata fields do not exist.

- [ ] **Step 3: Implement capped view mode**

For `view=true`, clamp the page size to six, deduplicate movie/series coordinates and use the existing cached `TmdbClient.getCardMetadata` path. Preserve `view=false` output unchanged. Episode by Frame continues to use its current series → season → episode hierarchy; do not add another episode search route.

- [ ] **Step 4: Bound cache use, verify and commit**

```powershell
.\mvnw.cmd test "-Dtest=DailyGameSearchServiceImplTest,DailyGameControllerIntegrationTest,TmdbClientCachingTest"
git add src/main/java/com/watchwise/watchwise_api/dailygame src/main/java/com/watchwise/watchwise_api/common/tmdb src/test/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/common/tmdb
git commit -m "feat(dailygame): enrich play search results"
git log -1 --format=%B
```

Expected: tests pass and the commit has no self-attribution trailer.

## Task 7: Update documentation and verify independently

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/progress.md`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/telas-reqs-definitivo.md`
- Review: all files changed in Tasks 1-6.

- [ ] **Step 1: Update contracts and rules**

Document visual read/action/search routes, protected overview images, opaque grants, v2 snapshots, six-frame episode behavior, current-day duplicate scope and explicit content-reference navigation. Keep raw route schemas and direct-image semantics documented separately.

- [ ] **Step 2: Update screen requirements and progress**

Add individual sections for the four play screens covering initial read, search, attempt, terminal state, media policy, failed episode frames and CTA behavior. Add chronological progress only after the code works.

- [ ] **Step 3: Validate docs**

```powershell
python -c "import yaml; yaml.safe_load(open('docs/context/openapi.yaml', encoding='utf-8')); print('openapi yaml: OK')"
rg -n "/games/.*/play|/games/play/media|view=true|DailyGamePlayViewDTO|DailyGameMediaRefDTO|six" docs/context/openapi.yaml docs/context/business-rules.md docs/telas-reqs-definitivo.md
git diff --check
```

Expected: valid YAML, complete contracts and no whitespace errors.

- [ ] **Step 4: Run focused and full suites**

```powershell
.\mvnw.cmd test "-Dtest=DailyGamePlayAssemblerTest,DailyGamePlayServiceImplTest,DailyGameMediaServiceTest,DailyGameMediaControllerIntegrationTest,DailyGameSearchServiceImplTest,DailyGameServiceImplTest,DailyGameServiceImplIntegrationTest,DailyGameControllerIntegrationTest,DailyGameControllerTest,DailyChallengeGeneratorTest,DailyChallengeGenerationServiceImplTest,DailyChallengeSnapshotRepairServiceImplTest,TmdbClientCachingTest"
.\mvnw.cmd test
```

Expected: all reports have zero failures and errors. If Surefire has a fork-shutdown timeout after clean reports, record exact totals and timeout.

- [ ] **Step 5: Perform independent review**

Reject the implementation if any condition is false:

- raw routes changed DTO shape or direct-image behavior;
- visual routes expose a hidden original answer asset;
- a grant is reusable by another user, expired or accepted after source change;
- arbitrary/oversized media reaches ImageIO;
- an open episode reveals more than six frames or a terminal episode has duplicate frames;
- a visual GET writes data or calls content details;
- visual search exceeds six enrichment lookups or loses a usable result on decorative metadata failure;
- a repeated current-day guess consumes an attempt;
- changed commits contain `Co-Authored-By`.

- [ ] **Step 6: Keep docs uncommitted and report evidence**

Do not add docs to code commits without explicit authorization. Report routes, compatibility, image policy, focused/full test results and any Surefire limitation. Do not push without explicit authorization.
