# Daily Games v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Evoluir os jogos diários com estado específico por modalidade, desistência, seleção hierárquica de episódios, comparação estruturada de metadados e filmografias compartilhadas, preservando o endpoint agregado e o resumo histórico.

**Architecture:** O núcleo `DailyChallenge`/`UserDailyGameResult` permanece responsável por desafio, pontuação e bloqueio. Um único campo JSONB transitório no resultado guarda os detalhes mínimos das tentativas somente para a data GMT atual; `DailyChallenge.answerSnapshot` continua sendo a única cópia da resposta congelada. Comparadores separados para informação e filmografia produzem blocos tipados que o assembler redige quando a partida está aberta.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL/Flyway, Jackson JSONB, TMDB via `TmdbClient`, JUnit 5, AssertJ, Mockito, MockMvc e Testcontainers.

## Global Constraints

- Usar `mvnw.cmd` e Java 21; não depender de Maven global.
- Seguir o fluxo Controller → Service → ServiceImpl → Repository/Mapper/DTO/Entity.
- Manter `GET /games/today`, histórico e ranking compatíveis.
- A data de jogo é GMT/UTC; a limpeza deve executar explicitamente com `zone = "UTC"`.
- `EPISODE_BY_FRAME`, `MOVIE_BY_POSTER`, `SERIES_BY_POSTER` e `PERSON_BY_FACE` têm seis tentativas; as outras modalidades têm dez.
- Tentativa inválida não incrementa `attemptsUsed` nem altera `attemptDetails`.
- Nenhum estado aberto pode revelar a resposta, valores secretos ou o nome do ator secreto.
- Uma resposta correta é armazenada uma vez em `daily_challenges.answer_snapshot`, nunca duplicada por usuário.
- Falhas TMDB devem resultar em `TmdbUnavailableException`/`502`; IDs inexistentes devem seguir o erro de domínio já usado pelo módulo.
- Cada mudança de código deve ter teste escrito antes da implementação e ciclo RED → GREEN → REFACTOR.
- Atualizar `openapi.yaml`, `database-schema.md`, `database-schema.html`, `business-rules.md` e `progress.md` no mesmo trabalho; manter documentação no worktree sem commit, conforme `AGENTS.md`.
- Usar Conventional Commits sem corpo e sem trailer `Co-Authored-By`; nunca executar `git push` sem autorização.

## File Map

### Persistence and lifecycle

- Create: `src/main/resources/db/migration/V63__add-daily-game-attempt-details.sql` — adiciona o JSONB transitório.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/entity/UserDailyGameResult.java` — mapeia `attempt_details`.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/repository/UserDailyGameResultRepository.java` — limpa detalhes expirados.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameAttemptDetailsCleanupService.java` — contrato de limpeza.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameAttemptDetailsCleanupServiceImpl.java` — limpeza transacional.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/tracking/DailyGameAttemptDetailsCleanupJob.java` — execução UTC após a virada.
- Modify: `src/main/resources/application-dev.properties` — cron da limpeza.

### Shared API and game state

- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAttemptDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameCandidateDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameComparisonStatus.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameComparisonDirection.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameComparisonCellDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameInfoFeedbackDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameFilmographyFeedbackDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameFilmographyStateDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameFilmographyEntryDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameActorGuessDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameAttemptDetailsCodec.java` — codifica e decodifica o JSONB.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameStateDTO.java` — inclui detalhes do jogo atual.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAttemptResponseDTO.java` — inclui detalhes e a tentativa recém-calculada.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAnswerDTO.java` — inclui metadados terminais da série e dos jogos de informação.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameService.java` — leitura específica e desistência.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImpl.java` — lock, append transitório e desistência.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java` — redaction, tentativas atuais e respostas terminais.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameController.java` — rotas específicas e `give-up`.

### Episode search and generation

- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameSeasonOptionDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameEpisodeOptionDTO.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameSearchService.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImpl.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/EpisodeByFrameGenerator.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeSnapshotAssembler.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClient.java` only if a missing cached lookup is needed; reuse existing season and episode image calls first.

### Metadata and filmography

- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameInfoComparisonService.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameInfoComparisonServiceImpl.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameFilmographyService.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameFilmographyServiceImpl.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/MovieByInfoGenerator.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/SeriesByInfoGenerator.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/ActorByMovieFilmographyGenerator.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/ActorBySeriesFilmographyGenerator.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeSnapshotAssembler.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbPersonAggregateCredit.java` and related aggregate records to read genre IDs, episode counts and available role fields.

### Tests and documentation

- Modify/add tests under `src/test/java/com/watchwise/watchwise_api/dailygame/**`, `src/test/java/com/watchwise/watchwise_api/common/tmdb/**`, and the repository integration tests.
- Modify: `docs/context/openapi.yaml`.
- Modify: `docs/context/database-schema.md` and `docs/context/database-schema.html`.
- Modify: `docs/context/business-rules.md`.
- Modify: `docs/context/progress.md`.

---

### Task 1: Persist only current-day attempt details and clean them at UTC midnight

**Files:**
- Create: `src/main/resources/db/migration/V63__add-daily-game-attempt-details.sql`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/entity/UserDailyGameResult.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/repository/UserDailyGameResultRepository.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameAttemptDetailsCleanupService.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameAttemptDetailsCleanupServiceImpl.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/tracking/DailyGameAttemptDetailsCleanupJob.java`
- Modify: `src/main/resources/application-dev.properties`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/repository/DailyGameRepositoryTest.java`
- Create: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameAttemptDetailsCleanupServiceImplTest.java`
- Create: `src/test/java/com/watchwise/watchwise_api/dailygame/tracking/DailyGameAttemptDetailsCleanupJobTest.java`

**Interfaces:**
- `UserDailyGameResult.attemptDetails` is a nullable `Map<String, Object>` mapped with `@JdbcTypeCode(SqlTypes.JSON)`.
- `UserDailyGameResultRepository.clearAttemptDetailsBefore(LocalDate currentDate)` updates only rows joined to challenges with `challenge_date < currentDate`.
- `DailyGameAttemptDetailsCleanupService.cleanup(LocalDate currentDate)` is transactional and delegates to the repository.

- [ ] **Step 1: Write the failing JSONB/repository tests.** Assert that the entity exposes a nullable JSONB map, the cleanup query clears expired challenge rows, and leaves current-day rows untouched.

```java
@Test
void shouldClearAttemptDetailsOnlyBeforeTheCurrentGmtDate() {
    int cleared = repository.clearAttemptDetailsBefore(LocalDate.of(2026, 9, 30));

    assertThat(cleared).isEqualTo(1);
    assertThat(expiredResult.getAttemptDetails()).isNull();
    assertThat(currentResult.getAttemptDetails()).containsKey("attempts");
}
```

- [ ] **Step 2: Run the focused repository test and confirm RED.**

Run: `mvnw.cmd test "-Dtest=DailyGameRepositoryTest#shouldClearAttemptDetailsOnlyBeforeTheCurrentGmtDate"`

Expected: FAIL because `attempt_details`, the entity field and cleanup query do not exist yet.

- [ ] **Step 3: Add the migration and entity mapping.** Use:

```sql
ALTER TABLE user_daily_game_results
    ADD COLUMN attempt_details JSONB;
```

Add the nullable field with `@JdbcTypeCode(SqlTypes.JSON)`, `@Column(columnDefinition = "jsonb")` and a setter so the locked service can replace the map.

- [ ] **Step 4: Add repository cleanup, service and UTC job.** Configure `app.daily-games.attempt-details-cleanup.cron=0 5 0 * * *` and schedule with `zone = "UTC"`; the job passes `LocalDate.now(clock)` to the service. The JSONB codec is introduced with the shared DTOs in Task 2.

- [ ] **Step 5: Run the focused tests and confirm GREEN.**

Run: `mvnw.cmd test "-Dtest=DailyGameRepositoryTest,DailyGameAttemptDetailsCleanupServiceImplTest,DailyGameAttemptDetailsCleanupJobTest"`

- [ ] **Step 6: Commit the code migration and lifecycle support.**

```bash
git add src/main/resources/db/migration/V63__add-daily-game-attempt-details.sql src/main/java/com/watchwise/watchwise_api/dailygame src/main/resources/application-dev.properties src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(daily-game): retain current-day attempt details"
```

After the commit, verify that its message contains no `Co-Authored-By` trailer or other self-attribution.

### Task 2: Add typed shared game details, specific reads and give-up

**Files:**
- Create the shared DTOs listed in the file map under `dailygame/dto/`.
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameAttemptDetailsCodec.java`
- Modify: `DailyGameStateDTO.java`, `DailyGameAttemptResponseDTO.java`, `DailyGameAnswerDTO.java`
- Modify: `DailyGameService.java`, `DailyGameServiceImpl.java`, `DailyChallengeResponseAssembler.java`, `DailyGameController.java`
- Test: `DailyGameServiceImplTest.java`, `DailyChallengeResponseAssemblerTest.java`, `DailyGameControllerTest.java`, `DailyGameControllerIntegrationTest.java`

**Interfaces:**
- Add `DailyGameStateDTO getGame(UUID userId, LocalDate challengeDate, DailyGameType gameType, boolean majorRoles)`.
- Add `DailyGameAttemptResponseDTO giveUp(UUID userId, DailyGameType gameType)` and the historical overload with `LocalDate`.
- Add `DailyGameAttemptDTO(int attemptNumber, DailyGameCandidateDTO candidate, DailyGameGuessFeedbackDTO episodeFeedback, DailyGameInfoFeedbackDTO infoFeedback, DailyGameFilmographyFeedbackDTO filmographyFeedback)`.
- Add `DailyGameCandidateDTO(targetKind, tmdbId, personTmdbId, seriesTmdbId, seasonNumber, episodeNumber, title, imageUrl, date)`.
- Add `DailyGameAttemptDetailsCodec.read(Map<String,Object>)` returning an immutable list and `append(Map<String,Object>, DailyGameAttemptDTO)` returning a fresh map without mutating caller-owned collections.
- Add `DailyGameStateDTO` fields `attempts` and nullable `DailyGameFilmographyStateDTO filmography`; preserve existing constructor overloads so unrelated call sites remain source-compatible.
- Add `GET /games/{gameType}/today`, `GET /games/{challengeDate}/{gameType}`, `POST /games/{gameType}/give-up` and the historical give-up route.

- [ ] **Step 1: Write failing service tests for a specific read and give-up.** Cover a `NOT_PLAYED` specific state, a give-up without a prior result, a give-up from `IN_PROGRESS`, no attempt increment, score zero, terminal answer, and `409` on a terminal result.

```java
@Test
void shouldFailWithoutConsumingAnAttemptWhenTheUserGivesUp() {
    DailyGameAttemptResponseDTO response = service().giveUp(USER_ID, DailyGameType.MOVIE_BY_INFO);

    assertThat(response.status()).isEqualTo(DailyGameViewStatus.FAILED);
    assertThat(response.attemptsUsed()).isZero();
    assertThat(response.score()).isZero();
    assertThat(response.answer()).isNotNull();
}
```

- [ ] **Step 2: Run the focused test and confirm RED.**

Run: `mvnw.cmd test "-Dtest=DailyGameServiceImplTest#shouldFailWithoutConsumingAnAttemptWhenTheUserGivesUp"`

Expected: FAIL because no give-up service method or route exists.

- [ ] **Step 3: Implement specific reads and the shared response fields.** Reuse the existing `getDay` challenge/result loading logic for one game, require that the requested `gameType` exists for the date, and pass the `majorRoles` flag only to the series-filmography projection.

- [ ] **Step 4: Implement give-up in the same `@Transactional` boundary as attempts.** Insert the result if absent, load it with `findByUserIdAndDailyChallengeIdForUpdate`, reject terminal results with `ConflictException`, set `FAILED`, `score = 0`, and return the terminal answer from the challenge snapshot.

- [ ] **Step 5: Add controller tests for route precedence, CSRF/rate limiting and `ApiError` conflict responses.** Verify `/games/today` still resolves to the aggregate route and `/games/{gameType}/today` resolves to the specific route.

- [ ] **Step 6: Run focused tests and commit.**

Run: `mvnw.cmd test "-Dtest=DailyGameServiceImplTest,DailyChallengeResponseAssemblerTest,DailyGameControllerTest,DailyGameControllerIntegrationTest"`

```bash
git add src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(daily-game): add specific game reads and give-up"
```

Confirm no co-authorship trailer was added.

### Task 3: Replace episode text search with series → seasons → episodes

**Files:**
- Create: `DailyGameSeasonOptionDTO.java`, `DailyGameEpisodeOptionDTO.java`
- Modify: `DailyGameSearchService.java`, `DailyGameSearchServiceImpl.java`, `DailyGameController.java`
- Modify: `docs/context/openapi.yaml` later in Task 7
- Test: `DailyGameSearchServiceImplTest.java`, `DailyGameControllerTest.java`, `DailyGameControllerIntegrationTest.java`

**Interfaces:**
- `List<DailyGameSeasonOptionDTO> listEpisodeSeasons(UUID userId, String seriesTmdbId)`.
- `List<DailyGameEpisodeOptionDTO> listEpisodeEpisodes(UUID userId, String seriesTmdbId, Integer seasonNumber)`.
- `DailyGameSeasonOptionDTO(seasonNumber, name, episodeCount)`.
- `DailyGameEpisodeOptionDTO(seriesTmdbId, seasonNumber, episodeNumber, name, LocalDate airDate)`.

- [ ] **Step 1: Write failing tests for the two dropdown reads.** Assert that seasons omit season zero and specials, episodes omit future episodes, neither response contains image URLs, and invalid IDs/season coordinates are rejected.

- [ ] **Step 2: Run `mvnw.cmd test "-Dtest=DailyGameSearchServiceImplTest"` and confirm the new tests fail.** Existing `searchEpisodes` tests should be changed to express the new behavior, not left asserting the removed `q` route.

- [ ] **Step 3: Implement season and episode option reads.** Load the selected series with `getTvFullDetails`, map regular seasons without images, load one season with `getSeasonFullDetails`, filter released episodes using the injected `Clock`, and map only identity/name/date.

- [ ] **Step 4: Remove the controller/service route that accepts `/search/episodes?seriesTmdbId=...&q=...`.** Keep `/search/series` and the generic search rejection for `EPISODE_BY_FRAME`.

- [ ] **Step 5: Run search/controller tests and commit.**

Run: `mvnw.cmd test "-Dtest=DailyGameSearchServiceImplTest,DailyGameControllerTest,DailyGameControllerIntegrationTest"`

```bash
git add src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(daily-game): add hierarchical episode selection"
```

Confirm no co-authorship trailer was added.

### Task 4: Require six episode stills during generation and enrich the terminal answer

**Files:**
- Modify: `EpisodeByFrameGenerator.java`, `DailyChallengeSnapshotAssembler.java`, `DailyChallengeResponseAssembler.java`, `DailyGameAnswerDTO.java`
- Test: `DailyChallengeGeneratorTest.java`, `DailyChallengeResponseAssemblerTest.java`, `DailyGameServiceImplTest.java`

**Interfaces:**
- `EpisodeByFrameGenerator` must select a released episode whose deduplicated image list has size `>= 6`.
- The candidate answer snapshot must include `seriesName`, `seriesPosterPath`, `seriesYear`, `episodeName`, `seasonNumber` and `episodeNumber`.
- `DailyGameAnswerDTO` must expose the series name, poster URL and year for terminal episode answers.

- [ ] **Step 1: Write failing generator tests.** Cover an episode with five images being rejected, a second episode in the same season being selected, a second season being selected when the first has no eligible episode, and a new series being attempted after all seasons fail.

- [ ] **Step 2: Run the focused generator test and confirm RED.**

Run: `mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest"`

Expected: FAIL because the current generator accepts a single valid still and aggregates all released episodes from one series without the six-image threshold.

- [ ] **Step 3: Implement bounded randomized fallback.** Shuffle regular seasons and their released episodes, call `getEpisodeImages` once per candidate through the existing cache, keep unique nonblank paths, and continue season/series fallback until a candidate meets six images. Preserve the outer generation candidate limit to prevent unbounded TMDB calls.

- [ ] **Step 4: Extend the episode snapshot and terminal answer.** Keep `imagePath` as the first path for legacy rows and persist all valid relative paths in `displaySnapshot.imagePaths`. Redact series metadata until the result is terminal.

- [ ] **Step 5: Run generator/assembler/service tests and commit.**

Run: `mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyChallengeResponseAssemblerTest,DailyGameServiceImplTest"`

```bash
git add src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(daily-game): require six episode stills"
```

Confirm no co-authorship trailer was added.

### Task 5: Build structured Movie/Series by Info comparisons

**Files:**
- Create: `DailyGameInfoComparisonService.java`, `DailyGameInfoComparisonServiceImpl.java`
- Modify: `MovieByInfoGenerator.java`, `SeriesByInfoGenerator.java`, `DailyChallengeSnapshotAssembler.java`, `DailyGameServiceImpl.java`, `DailyChallengeResponseAssembler.java`
- Test: `DailyChallengeGeneratorTest.java`, `DailyGameInfoComparisonServiceTest.java`, `DailyGameServiceImplTest.java`, `DailyChallengeResponseAssemblerTest.java`

**Interfaces:**
- `DailyGameInfoFeedbackDTO compare(DailyChallenge challenge, DailyGameCandidateIdentity candidate)`.
- `DailyGameComparisonCellDTO` contains `status`, optional `direction`, display value, matched values and match count.
- `DailyGameInfoFeedbackDTO` contains cells for platforms, genres, year, certification, director/creators, cast, production companies and revenue/seasons.

- [ ] **Step 1: Write failing comparison tests for all agreed rules.** Test exact/partial/no set overlap, BR certification, director/creator overlap, cast counts 1–2 versus 3+, year direction and one-year tolerance, revenue 10%/30% thresholds, seasons direction, and `NO_DATA`.

```java
@Test
void shouldMarkTwoSharedCastMembersAsPartial() {
    DailyGameInfoFeedbackDTO feedback = comparison.compare(challengeWithCast("A", "B", "C"), candidateWithCast("B", "C"));

    assertThat(feedback.cast().status()).isEqualTo(DailyGameComparisonStatus.PARTIAL);
    assertThat(feedback.cast().matchedValues()).containsExactly("B", "C");
}
```

- [ ] **Step 2: Run `mvnw.cmd test "-Dtest=DailyGameInfoComparisonServiceTest"` and confirm RED.**

- [ ] **Step 3: Replace comma-only info snapshots with normalized lists and numeric values.** Preserve null fields, keep movie revenue separate from series season count, and retain existing TMDB region `BR` for certification/providers.

- [ ] **Step 4: Implement the comparator with set normalization and explicit thresholds.** Use case-insensitive trimmed names for equality, calculate relative revenue difference against the larger absolute value, and return `NO_DATA` whenever either required side lacks a value.

- [ ] **Step 5: Append typed info feedback to current-day attempt details and expose it in the attempt/state response without leaking the secret.** Terminal answers may reveal the complete normalized snapshot.

- [ ] **Step 6: Run focused tests and commit.**

Run: `mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyGameInfoComparisonServiceTest,DailyGameServiceImplTest,DailyChallengeResponseAssemblerTest"`

```bash
git add src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(daily-game): compare info game guesses"
```

Confirm no co-authorship trailer was added.

### Task 6: Add shared movie/series filmography deductions and proportional Major roles

**Files:**
- Create: `DailyGameFilmographyService.java`, `DailyGameFilmographyServiceImpl.java`, `DailyGameFilmographyEntryDTO.java`, `DailyGameActorGuessDTO.java`
- Modify: `TmdbPersonAggregateCredit.java`, `TmdbAggregateRole.java`, related TMDB tests/models, `ActorByMovieFilmographyGenerator.java`, `ActorBySeriesFilmographyGenerator.java`, `DailyChallengeSnapshotAssembler.java`, `DailyGameServiceImpl.java`, `DailyChallengeResponseAssembler.java`
- Test: `DailyChallengeGeneratorTest.java`, `DailyGameFilmographyServiceTest.java`, `DailyGameServiceImplTest.java`, `DailyChallengeResponseAssemblerTest.java`, `TmdbSearchModelsTest.java`

**Interfaces:**
- `DailyGameFilmographyFeedbackDTO compare(DailyChallenge challenge, String guessedPersonTmdbId, boolean majorRoles)`.
- `DailyGameFilmographyFeedbackDTO` contains the guessed actor, shared work keys for `majorRoles=true` and `false`, and the redacted work list for the selected mode.
- `DailyGameFilmographyStateDTO(boolean majorRoles, List<DailyGameActorGuessDTO> guessedActors, List<DailyGameFilmographyEntryDTO> entries)` is the read projection assembled from the challenge snapshot and current-day attempt details.
- `DailyGameFilmographyEntryDTO` contains work ID, nullable title, revealed/highlighted flags, year, genres, poster, episode count, period and character.
- For a series with `totalEpisodes`, `majorRoles` uses `ceil(totalEpisodes * 0.33)` for up to 6 episodes, `ceil(totalEpisodes * 0.40)` for 7–20 and `ceil(totalEpisodes * 0.50)` above 20.

- [ ] **Step 1: Write failing tests for movie intersections and series filtering.** Cover no shared works, one shared work, multiple shared works, hidden titles, default `majorRoles=true`, all appearances when false, and the exact proportional thresholds at 6, 7, 20 and 21 episodes.

- [ ] **Step 2: Run `mvnw.cmd test "-Dtest=DailyGameFilmographyServiceTest"` and confirm RED.**

- [ ] **Step 3: Extend aggregate-credit deserialization without per-movie TMDB calls.** Read `genre_ids`, `episode_count`, `media_type`, dates and available roles; for distinct TV works, use the existing cached `getTvFullDetails` lookup to obtain `numberOfEpisodes`, which is the denominator of the proportional Major-role formula. Preserve nulls where TMDB does not provide a period. Use the existing person aggregate cache.

- [ ] **Step 4: Store complete secret filmographies in the challenge answer snapshot and require at least two eligible works during generation.** Deduplicate by media type and TMDB ID; exclude invalid IDs and entries without a usable title/date where the corresponding clue requires them.

- [ ] **Step 5: Implement feedback for both modes.** Filter secret and guessed series filmographies by the proportional threshold, compute shared work IDs, store both major/all reveal key sets in current-day attempt details, and project only the selected mode. Movie filmography always uses all eligible movie credits.

- [ ] **Step 6: Add query parameter handling.** `majorRoles` defaults to `true` on specific series-filmography reads and attempts; other game types keep their default projection. Verify toggling changes presentation without another TMDB call when the same current-day attempt details are available.

- [ ] **Step 7: Run focused tests and commit.**

Run: `mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyGameFilmographyServiceTest,DailyGameServiceImplTest,DailyChallengeResponseAssemblerTest,TmdbSearchModelsTest"`

```bash
git add src/main/java/com/watchwise/watchwise_api/common/tmdb src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/common/tmdb src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(daily-game): add filmography deductions"
```

Confirm no co-authorship trailer was added.

### Task 7: Synchronize the public contract and project context documentation

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/database-schema.md`
- Modify: `docs/context/database-schema.html`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

- [ ] **Step 1: Update OpenAPI paths.** Document specific state routes, both give-up routes, the three episode selection routes, removal of episode `q` search, `majorRoles`, attempt details, comparison cells, redacted filmographies, episode series metadata and terminal answer fields.

- [ ] **Step 2: Update schema documentation.** Add nullable `attempt_details JSONB` to `user_daily_game_results`, explain that it is populated only for the current GMT date and cleared after midnight, and keep the single-copy `daily_challenges.answer_snapshot` rule.

- [ ] **Step 3: Update business rules.** Record give-up semantics, six-still generation, hierarchical episode selection, info comparison thresholds, `NO_DATA`, actor intersection behavior and the proportional Major roles formula. Remove the stale rule that says no attempt details are ever stored and the stale episode text-search rule.

- [ ] **Step 4: Append the shipped changes to the current `2026-09-29` section in `progress.md`.** Do not add a next-steps section or log design-only decisions.

- [ ] **Step 5: Run documentation consistency searches.**

Run: `rg -n "search/episodes|attempt_details|give-up|majorRoles|six still|filmography|NO_DATA" docs/context/openapi.yaml docs/context/database-schema.md docs/context/database-schema.html docs/context/business-rules.md docs/context/progress.md`

Expected: every new route/rule appears in the contract and no stale `/search/episodes?...&q=` behavior remains documented.

### Task 8: Full verification and final review

**Files:**
- No production-file changes planned; inspect all modified files and generated migration state.
- Test: all existing daily-game tests plus the full Maven suite.

- [ ] **Step 1: Run the focused daily-game suite.**

Run: `mvnw.cmd test "-Dtest=DailyGameControllerTest,DailyGameControllerIntegrationTest,DailyGameSearchServiceImplTest,DailyGameServiceImplTest,DailyGameServiceImplIntegrationTest,DailyChallengeResponseAssemblerTest,DailyChallengeGeneratorTest,DailyChallengeGenerationServiceImplTest,DailyGameRankingServiceImplTest,DailyGameGenerationJobTest,DailyGameRepositoryTest,DailyGameAttemptDetailsCleanupServiceImplTest,DailyGameAttemptDetailsCleanupJobTest,DailyGameInfoComparisonServiceTest,DailyGameFilmographyServiceTest"`

Expected: exit code 0 and no test failures. Repository tests require Docker/Testcontainers.

- [ ] **Step 2: Run the full Maven suite.**

Run: `mvnw.cmd test`

Expected: exit code 0; if Docker is unavailable, report the exact repository/integration tests that could not run rather than claiming the suite passed.

- [ ] **Step 3: Run a clean package verification.**

Run: `mvnw.cmd clean package`

Expected: exit code 0 and Flyway/Hibernate validation accepting the new migration and entity field.

- [ ] **Step 4: Inspect the final diff and security checklist.** Confirm no open response contains the secret, no invalid attempt mutates the result, give-up is locked transactionally, current-day JSONB cleanup is UTC-scoped, no response duplicates the challenge answer, and no unrelated user changes were staged.

- [ ] **Step 5: Report evidence.** Include the exact test/build commands and observed exit status, list any Docker limitation, summarize committed code changes, and state that no push was performed.
