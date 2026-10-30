# Jogos diários: imagens de episódios, histórico jogável e ranking Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

Goal: Congelar imagens de episódios no desafio diário, permitir jogar dias anteriores sem guardar palpites individuais e retornar gamesPlayed nos rankings.

Architecture: Consultar o TMDB somente durante a geração, com cache Caffeine de 24 horas. Persistir apenas file_path relativos em display_snapshot.imagePaths; montar URLs e imagens visíveis a partir de attemptsUsed. O resultado do usuário permanecerá agregado e o ranking fará COUNT(*) dos resultados terminais.

Tech Stack: Spring Boot 4.1, Java 21, Spring Data JPA, PostgreSQL, Flyway, Caffeine, RestClient, JUnit 5, AssertJ, MockMvc e Testcontainers.

## Global Constraints

- Manter Controller → Service → ServiceImpl → Repository e DTOs como records.
- Não criar histórico de palpites, tabela de tentativas ou JSON por usuário.
- Inverter a ordem recebida em stills e limitar EPISODE_BY_FRAME a 6 tentativas.
- Não alterar a modificação preexistente em .gitignore.
- Atualizar openapi.yaml, database-schema.md, business-rules.md e progress.md sem commit.
- Usar Conventional Commits sem Co-Authored-By e nunca executar git push sem autorização.

## File Map

TMDB/generation: TmdbClient.java, TmdbCacheConfig.java, application-dev.properties, application-prod.properties,
new TmdbEpisodeImages.java and TmdbStill.java, EpisodeByFrameGenerator.java, DailyChallengeSnapshotAssembler.java,
DailyGameType.java and DailyChallengeGenerationServiceImpl.java.

State/history: new DailyGameGuessFeedbackDTO.java, DailyGameAttemptResponseDTO.java, DailyGameStateDTO.java,
DailyChallengeResponseAssembler.java, DailyGameService.java, DailyGameServiceImpl.java and DailyGameController.java.

Ranking: DailyGameRankingEntryDTO.java, UserDailyGameResultRepository.java and DailyGameRankingServiceImpl.java.

Tests/docs: focused dailygame and common tmdb tests plus the four context documents.

---

### Task 1: Establish failing image and six-attempt contracts

Files: DailyChallengeGeneratorTest.java, DailyChallengeResponseAssemblerTest.java and DailyGameServiceImplTest.java.

- [ ] Add failing assertions for maxAttempts equal to 6, reversed imagePaths, visibleImageUrls and terminal imageUrls.
- [ ] Run .\mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyChallengeResponseAssemblerTest,DailyGameServiceImplTest" and confirm RED caused by missing contracts.
- [ ] Do not add production code before this failing run is observed.

### Task 2: Add the cached TMDB episode-images client

Files: new TmdbEpisodeImages.java and TmdbStill.java; modify TmdbClient.java, TmdbCacheConfig.java,
application-dev.properties, application-prod.properties, TmdbClientTest.java and TmdbClientCachingTest.java.

Interface: add TmdbClient.getEpisodeImages(seriesTmdbId, seasonNumber, episodeNumber) returning
TmdbLookupResult<TmdbEpisodeImages>. Cache by composite episode identity for 24 hours.

- [ ] Add a failing JSON parsing and cache-hit test for a response with stills and file_path.
- [ ] Run .\mvnw.cmd test "-Dtest=TmdbClientTest,TmdbClientCachingTest" and confirm RED.
- [ ] Implement JsonIgnoreProperties models, the cache bean and the GET call to
      /tv/{seriesId}/season/{seasonNumber}/episode/{episodeNumber}/images without a language filter.
- [ ] Run the same focused tests and confirm GREEN.

### Task 3: Persist compact reversed image paths during generation

Files: EpisodeByFrameGenerator.java, DailyChallengeSnapshotAssembler.java, DailyChallengeGenerationServiceImpl.java,
DailyGameType.java, DailyChallengeGeneratorTest.java and DailyChallengeGenerationServiceImplTest.java.

Interface: make the episode candidate carry imagePath as the first relative path and
displaySnapshot.imagePaths as the complete normalized reversed list.

- [ ] Add failing tests for reversed order, blank-path removal, exact duplicate removal and fallback to the
      episode still_path when the image endpoint is empty/unavailable.
- [ ] Run .\mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyChallengeGenerationServiceImplTest" and confirm RED.
- [ ] Implement normalization after selecting the eligible episode. Keep only paths, not complete CDN URLs.
- [ ] Run the focused generator tests and confirm GREEN.

### Task 4: Expose current and previous images with transient feedback

Files: new DailyGameGuessFeedbackDTO.java; modify DailyGameAttemptResponseDTO.java, DailyGameStateDTO.java,
DailyChallengeResponseAssembler.java, DailyGameServiceImpl.java, DailyChallengeResponseAssemblerTest.java
and DailyGameServiceImplTest.java.

Interface: open responses expose visibleImageUrls; a COMPLETED attempt additionally exposes imageUrls;
attempt responses expose DailyGameGuessFeedbackDTO. No feedback is persisted.

- [ ] Add failing tests for six distinct positions, fewer-than-six repetition, legacy imagePath fallback,
      previous-image visibility, independent field feedback and exactMatch requiring all three fields.
- [ ] Run .\mvnw.cmd test "-Dtest=DailyChallengeResponseAssemblerTest,DailyGameServiceImplTest" and confirm RED.
- [ ] Implement defensive reading of displaySnapshot.imagePaths, current index min(attemptsUsed, size - 1),
      visible prefix generation and terminal full-list exposure.
- [ ] Run the focused response/service tests and confirm GREEN.

### Task 5: Make historical daily games playable

Files: DailyGameService.java, DailyGameServiceImpl.java, DailyGameController.java,
DailyGameServiceImplTest.java, DailyGameControllerTest.java and DailyGameControllerIntegrationTest.java.

Interface: add getDay(userId, challengeDate) and submitAttempt(userId, challengeDate, gameType, request).
Keep getToday and the existing today attempt route as UTC-date delegates.
Add GET /games/{challengeDate} and POST /games/{challengeDate}/{gameType}/attempt.

- [ ] Add failing tests for past-day reads, NOT_PLAYED state, resuming IN_PROGRESS, terminal replay conflict
      and future-date rejection.
- [ ] Run .\mvnw.cmd test "-Dtest=DailyGameServiceImplTest,DailyGameControllerTest,DailyGameControllerIntegrationTest"
      and confirm RED.
- [ ] Implement date validation, exact daily-set loading and existing row lock/idempotent result creation.
      Invalid attempts must not consume a try.
- [ ] Run the focused service/controller tests and confirm GREEN.

### Task 6: Return gamesPlayed in rankings

Files: DailyGameRankingEntryDTO.java, UserDailyGameResultRepository.java, DailyGameRankingServiceImpl.java,
DailyGameRepositoryTest.java, DailyGameRankingServiceImplTest.java, DailyGameControllerTest.java and
DailyGameControllerIntegrationTest.java.

Interface: replace DailyGameRankingEntryDTO.attemptsUsed with gamesPlayed and projection getter
getGamesPlayed(). Count only terminal rows; general ranking includes all types and modality ranking filters
the requested game type.

- [ ] Add failing tests for COUNT(*) across modalities, COUNT(*) for one modality, exclusion of IN_PROGRESS,
      score aggregation and JSON property gamesPlayed.
- [ ] Run .\mvnw.cmd test "-Dtest=DailyGameRepositoryTest,DailyGameRankingServiceImplTest,DailyGameControllerTest,DailyGameControllerIntegrationTest"
      and confirm RED.
- [ ] Implement COUNT(*) AS games_played while retaining SUM(attempts_used) only for the existing tie-break,
      and map the new projection field.
- [ ] Run the focused ranking tests and confirm GREEN.

### Task 7: Synchronize context documentation

Files: docs/context/openapi.yaml, database-schema.md, business-rules.md and progress.md.

- [ ] Document visibleImageUrls, terminal imageUrls, transient feedback, six attempts, playable dates,
      no terminal replay and gamesPlayed.
- [ ] Document display_snapshot.imagePaths as relative reversed TMDB paths and cache as a generation optimization.
- [ ] Remove outdated statements that EPISODE_BY_FRAME has 10 attempts or history is read-only.
- [ ] Leave documentation changes uncommitted.

### Task 8: Verify and commit production/test changes

- [ ] Run the focused daily-game/TMDB suite:
      .\mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyChallengeGenerationServiceImplTest,DailyGameServiceImplTest,DailyGameServiceImplIntegrationTest,DailyChallengeResponseAssemblerTest,DailyGameRankingServiceImplTest,DailyGameRepositoryTest,DailyGameControllerTest,DailyGameControllerIntegrationTest,TmdbClientTest,TmdbClientCachingTest"
- [ ] Run .\mvnw.cmd test. Docker-dependent failures must be reported as environment limitations.
- [ ] Run git diff --check, git status --short and git diff --stat. Confirm .gitignore is untouched by this work.
- [ ] Commit only src/main and src/test with:
      git add src/main src/test
      git commit -m "feat(daily-game): add episode image rotation and game counts"
- [ ] After commit, verify no Co-Authored-By trailer. Never push.
