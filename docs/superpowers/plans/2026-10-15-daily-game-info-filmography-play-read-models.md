# Daily Game Info and Filmography Play Read Models Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing gameplay read model and actions so all eight daily-game modalities can render their dedicated screens from one presentation-ready response.

**Architecture:** Keep the raw game routes unchanged and expand the additive `/games/{gameType}/play` family. Freeze new presentation metadata into challenge snapshots and attempt details, then assemble typed information and filmography sections without TMDB reads during visual game reads.

**Tech Stack:** Java 21, Spring Boot, Jackson records, Spring MVC, Mockito, JUnit 5, Testcontainers PostgreSQL, Maven.

## Global Constraints

- Preserve raw route paths, raw DTO field meanings, and direct-image behavior.
- Support all values of `DailyGameType` through `/games/{gameType}/play`; unsupported types must no longer be rejected by the visual route.
- Do not persist `Content`, call `/contents/details`, or call TMDB while assembling a visual read.
- Preserve protected grants for terminal answer media and return no media URL for open information or filmography games.
- Duplicate candidate rejection applies to `/play/attempt` for the current GMT day; raw attempt routes retain existing behavior.
- JSONB additions decode legacy attempt details and snapshots safely; absent presentation-only values remain null and are never rehydrated during a read.
- Documentation files are updated with code but remain uncommitted unless the user explicitly requests a documentation commit.
- Commit code and test tasks with Conventional Commits, one-line subjects, no `Co-Authored-By`, and no push.

---

## File structure

- Modify `dailygame/generation/DailyChallengeSnapshotAssembler.java` to freeze series episode totals.
- Modify `dailygame/dto/DailyGameInfoFeedbackDTO.java`, `dailygame/service/impl/DailyGameInfoComparisonServiceImpl.java`, and `dailygame/service/DailyGameAttemptDetailsCodec.java` to retain the series episode display value across reloads.
- Create visual DTOs in `dailygame/dto/` for actor previews, filmography work rows, and filmography play state.
- Modify `dailygame/service/impl/DailyGamePlayServiceImpl.java` and `DailyGamePlayAssembler.java` to project all eight game types.
- Keep `DailyGameFilmographyServiceImpl.java` and `DailyChallengeResponseAssembler.java` on the raw contract; a visual-only assembler reads frozen period data for play responses.
- Modify `dailygame/controller/DailyGameController.java` only if controller-level validation still rejects a supported `/play` game type.
- Update `docs/context/openapi.yaml`, `docs/context/business-rules.md`, `docs/context/progress.md`, and `docs/telas-reqs-definitivo.md` after behavior lands.

### Task 1: Freeze presentation data required by Series by info

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeSnapshotAssembler.java:154-179`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameInfoFeedbackDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameInfoComparisonServiceImpl.java:104-130`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameAttemptDetailsCodec.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeGeneratorTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/DailyGameAttemptDetailsCodecTest.java`

**Produces:** the frozen secret episode total and the candidate episode total survive generation, comparison, detail encoding, and decode without a later TMDB read; legacy missing values decode as null.

- [ ] **Step 1: Write failing snapshot tests**

```java
assertThat(candidate.answerSnapshot()).containsEntry("seasons", 3)
        .containsEntry("episodes", 24);
```

Add a codec round-trip test asserting `infoFeedback().candidateEpisodeCount() == 24` after `encode` then `read`. Add a legacy JSON test without `candidateEpisodeCount` asserting the codec returns null rather than rejecting the detail.

- [ ] **Step 2: Run the focused tests and verify failure**

Run: `./mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyGameAttemptDetailsCodecTest"`

Expected: the new assertions fail because the snapshot and attempt detail do not retain the episode total.

- [ ] **Step 3: Extend frozen and transient candidate metadata**

Add nullable `Integer candidateEpisodeCount` as the final field of `DailyGameInfoFeedbackDTO`, preserving the existing eight-field constructor by delegating with null. Store `episodes` in `seriesInfo` snapshots. In `compareSeries`, pass `TmdbTvFullDetails.numberOfEpisodes()` as the presentation value, then encode/decode it in `DailyGameAttemptDetailsCodec`; legacy JSON without the field must decode as null.

- [ ] **Step 4: Run focused tests and verify pass**

Run: `./mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyGameAttemptDetailsCodecTest,DailyGameInfoComparisonServiceTest,DailyGameServiceImplTest"`

Expected: all selected tests pass.

- [ ] **Step 5: Commit code and tests**

```powershell
git add src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(dailygame): retain info game presentation metadata"
```

Confirm the commit has no `Co-Authored-By` trailer.

### Task 2: Define typed visual contracts for information and filmography games

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameFilmographyActorViewDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameFilmographyWorkViewDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameFilmographyPlayStateDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameInfoPlayAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAttemptViewDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGamePlayViewDTO.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameInfoPlayAssemblerTest.java`

**Produces:** the additive DTOs and information-attempt projection used by the shared play module.

- [ ] **Step 1: Write failing DTO/assembler tests**

```java
assertThat(view.attempts().getFirst().infoFeedback().candidateEpisodeCount()).isEqualTo(24);
```

- [ ] **Step 2: Run the test and verify failure**

Run: `./mvnw.cmd test "-Dtest=DailyGameInfoPlayAssemblerTest"`

Expected: compilation fails because the visual DTO fields do not exist.

- [ ] **Step 3: Add presentation-only records**

Create `DailyGameInfoPlayAssembler` to map existing typed information feedback into a visual attempt. Create the three filmography DTOs without an assembler implementation yet. Make `DailyGameAttemptViewDTO` carry nullable `DailyGameInfoFeedbackDTO infoFeedback`. Make `DailyGamePlayViewDTO` carry nullable `DailyGameFilmographyPlayStateDTO filmography`. Preserve every existing field and JSON name.

- [ ] **Step 4: Run focused tests and verify pass**

Run: `./mvnw.cmd test "-Dtest=DailyGameInfoPlayAssemblerTest"`

Expected: all selected tests pass.

- [ ] **Step 5: Commit code and tests**

```powershell
git add src/main/java/com/watchwise/watchwise_api/dailygame/dto src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameInfoPlayAssembler.java src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameInfoPlayAssemblerTest.java
git commit -m "feat(dailygame): add info and filmography play contracts"
```

Confirm the commit has no `Co-Authored-By` trailer.

### Task 3: Preserve raw compatibility while assembling filmography presentation

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameFilmographyPlayAssembler.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameFilmographyPlayAssemblerTest.java`

**Produces:** actor cards and rows required by both filmography prototypes, assembled only from snapshots and persisted attempts while the raw filmography DTO stays unchanged.

- [ ] **Step 1: Write failing projection tests**

```java
assertThat(view.filmography().entries().getFirst().period()).isEqualTo("2018-01-01");
assertThat(view.filmography().guessedActors().getFirst().ageAtChallengeDate()).isEqualTo(62);
assertThat(view.filmography().guessedActors().getFirst().sharedWorksCount()).isEqualTo(3);
```

Include a series test with `majorRoles=true` that verifies `majorRoleSharedWorksCount` differs from the all-role count when appropriate.

Include a legacy snapshot test without `period`; assert the visual work returns null for `period` and the assembler has no TMDB dependency.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./mvnw.cmd test "-Dtest=DailyGameFilmographyPlayAssemblerTest"`

Expected: assertions fail because period and actor presentation data are not projected.

- [ ] **Step 3: Implement snapshot-only projection**

Build actor previews by joining `attempts[].candidate` with `attempts[].filmographyFeedback`; calculate age from persisted birth date and `challengeDate`. Read the stored snapshot `period` and role `episodeCount` into the visual work DTO. Invert `sharedAllRoleWorkKeys` and `sharedMajorRoleWorkKeys` into each work's `sharedWith` list. Do not change `DailyChallengeResponseAssembler` or call TMDB in this read path.

- [ ] **Step 4: Run focused tests and verify pass**

Run: `./mvnw.cmd test "-Dtest=DailyGameFilmographyPlayAssemblerTest,DailyChallengeSnapshotRepairServiceImplTest"`

Expected: all selected tests pass.

- [ ] **Step 5: Commit code and tests**

```powershell
git add src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(dailygame): project filmography play metadata"
```

Confirm the commit has no `Co-Authored-By` trailer.

### Task 4: Extend the additive play service to all eight game types

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGamePlayServiceImpl.java:48-201`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGamePlayAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGamePlayProjection.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGamePlayServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerIntegrationTest.java`

**Consumes:** Tasks 1-3 visual DTOs and persisted metadata.

**Produces:** `/play`, `/play/attempt`, and `/play/give-up` work for every `DailyGameType` without changing raw routes.

- [ ] **Step 1: Write failing controller and service tests**

```java
assertThat(service.readToday(userId, DailyGameType.MOVIE_BY_INFO).info()).isNotNull();
assertThat(service.readToday(userId, DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY).filmography()).isNotNull();
mockMvc.perform(get("/games/MOVIE_BY_INFO/play").with(authentication(authentication)))
        .andExpect(status().isOk());
```

Add tests proving open info/filmography states return `media = []`, `answer = null`, and no direct TMDB image URL.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./mvnw.cmd test "-Dtest=DailyGamePlayServiceImplTest,DailyGameControllerIntegrationTest"`

Expected: the service rejects these types with `Unsupported visual daily game type`.

- [ ] **Step 3: Generalize the play projection**

Replace `requireVisual` with a validator that accepts every enum value. For information and filmography games, return no open-state media; issue an ORIGINAL protected answer image only after a terminal result. Preserve the four existing image-game media rules. Carry `info` and `filmography` through the projection and assembler.

- [ ] **Step 4: Preserve action behavior**

Route the new game types through the existing locked mutation path with `rejectDuplicate = true`. Keep raw `POST /attempt` behavior unchanged. Ensure a failed visual response cannot commit a result before its presentation projection is valid.

- [ ] **Step 5: Run focused tests and verify pass**

Run: `./mvnw.cmd test "-Dtest=DailyGamePlayServiceImplTest,DailyGameControllerIntegrationTest,DailyGameServiceImplIntegrationTest"`

Expected: all selected tests pass.

- [ ] **Step 6: Commit code and tests**

```powershell
git add src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(dailygame): extend play routes to info games"
```

Confirm the commit has no `Co-Authored-By` trailer.

### Task 5: Verify raw compatibility, contract documentation, and full behavior

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`
- Modify: `docs/telas-reqs-definitivo.md`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImplTest.java`

**Produces:** documented additive `/play` contract and regression coverage proving raw routes retain their shape and semantics.

- [ ] **Step 1: Write raw-compatibility tests**

```java
assertThat(rawState.filmography().entries().getFirst().period()).isNull();
assertThat(playState.filmography().entries().getFirst().period()).isEqualTo("2018-01-01");
assertThat(rawAttempt.currentAttempt().infoFeedback().revenueOrSeasons().displayValue()).isEqualTo(3);
```

Also verify raw `POST /games/{gameType}/attempt` still allows its existing duplicate behavior while `/play/attempt` returns 409 for the same current-day candidate.

- [ ] **Step 2: Run focused tests and verify failure where expected**

Run: `./mvnw.cmd test "-Dtest=DailyGameControllerTest,DailyGameServiceImplTest"`

Expected: new additive-field assertions fail before the implementation is complete; existing raw assertions remain green.

- [ ] **Step 3: Update API and screen documentation**

Document all eight supported `/play` types, nullable attempt-level `infoFeedback`, nullable `filmography`, protected terminal answer media, series episode display metadata, actor previews, `sharedWith`, `majorRoles`, and raw compatibility rules.

- [ ] **Step 4: Run the focused daily-game suite**

Run:

```powershell
.\mvnw.cmd test "-Dtest=DailyGamePlayAssemblerTest,DailyGameInfoPlayAssemblerTest,DailyGameFilmographyPlayAssemblerTest,DailyGamePlayServiceImplTest,DailyGameMediaServiceTest,DailyGameSearchServiceImplTest,DailyGameServiceImplTest,DailyGameServiceImplIntegrationTest,DailyGameControllerTest,DailyGameControllerIntegrationTest,DailyChallengeGeneratorTest,DailyChallengeSnapshotRepairServiceImplTest,DailyGameRepositoryTest"
```

Expected: zero failures and zero errors.

- [ ] **Step 5: Run the full suite**

Run: `./mvnw.cmd test`

Expected: all Surefire reports have zero failures and errors. If Surefire reports a delayed fork shutdown after `System.exit(0)`, record the totals and Maven exit code.

- [ ] **Step 6: Commit code and tests only**

```powershell
git add src/main/java/com/watchwise/watchwise_api src/test/java/com/watchwise/watchwise_api
git commit -m "test(dailygame): cover info and filmography play routes"
```

Leave documentation changes uncommitted unless the user explicitly requests a documentation commit. Confirm the commit has no `Co-Authored-By` trailer.

## Plan self-review

- **Spec coverage:** Task 1 covers the missing Series by info display value. Task 2 creates visual DTOs and the information projection. Task 3 creates the filmography projection from frozen state without altering the raw route. Task 4 generalizes the play seam and its action/media policy. Task 5 proves compatibility, documents the contract, and verifies the full build.
- **Depth:** `DailyGamePlayService` remains the only caller-facing module for screen reads and visual actions. Information and filmography assemblers keep formatting, joins, age calculation, and shared-key inversion out of the controller and frontend.
- **Performance:** play reads use challenge snapshots and persisted attempt details only. They perform no TMDB lookup, no Content persistence, and no `/contents/details` fan-out.
- **Compatibility:** raw routes keep their current JSON meaning. The only raw addition is nullable `candidateEpisodeCount`; old JSONB rows decode it as null. Filmography period remains visual-only.
- **Placeholder scan:** every task names concrete source files, tests, invariants, expected command results, and commit boundaries.
