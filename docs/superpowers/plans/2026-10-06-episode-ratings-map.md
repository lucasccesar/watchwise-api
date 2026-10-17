# Episode Ratings Map Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir o contrato de backend necessário para as telas `Episode Ratings Map` e `Episode Ratings Map (Show)`, incluindo metadados das séries, paginação, notas pessoais, médias comunitárias e regras de visibilidade.

**Architecture:** Estender os dois endpoints existentes do domínio `summary`. O mapa passa a devolver uma página de séries, metadados de exibição e totais globais; a tela de uma série passa a devolver a grade completa de episódios regulares lançados, a nota mais recente do usuário consultado e a média pública deduplicada por usuário/episódio. O TMDB continua sendo usado somente pelo backend para metadados, por meio de uma consulta leve cacheada; todas as notas e contagens de avaliações vêm do PostgreSQL.

**Tech Stack:** Spring Boot 4.1, Java 21, Spring Data JPA, PostgreSQL, Flyway, Caffeine/TMDB proxy existente, JUnit 5, Mockito, AssertJ, MockMvc e Testcontainers.

## Global Constraints

- Usar o fluxo existente `Controller → Service → ServiceImpl → Repository/DTO`.
- Não criar entidade ou migration: as médias serão projeções de `DiaryEntry` e `Content` existentes.
- Não fazer chamadas diretas do cliente ao TMDB.
- Não usar `getTvFullDetails` para cards ou cabeçalhos; criar uma leitura TMDB leve que não solicite créditos, vídeos, providers ou temporadas completas.
- Séries e episódios continuam identificados no banco por `seriesTmdbId`, `seasonNumber` e `episodeNumber`; não armazenar metadados completos do TMDB em `Content`.
- A avaliação comunitária considera somente usuários com `isProfilePublic = true`.
- Para cada usuário e coordenada de episódio, considerar somente a avaliação mais recente: maior `watchNumber`, depois `createdAt` mais recente e, por fim, maior `DiaryEntry.id` como desempate determinístico.
- Rewatches não aumentam `ratingCount` nem influenciam `averageScore`.
- Temporada `0`, episódios futuros e episódios sem `air_date` válida ficam fora da grade e das médias exibidas.
- `totalEpisodeCount` permanece anulável; ausência de snapshot não pode ser convertida silenciosamente em zero.
- Atualizar `openapi.yaml`, `telas.md`, `telas-reqs.md`, `business-rules.md` e `progress.md` junto com o código, mas deixar documentação e plano sem commit conforme a convenção deste repositório.
- Não executar `git push`; commits locais de código devem usar Conventional Commits e não podem conter `Co-Authored-By`.

## File Map

### Existing files to modify

- `src/main/java/com/watchwise/watchwise_api/summary/controller/SummaryController.java` — aceitar `page` e `size` no mapa e manter o endpoint da grade.
- `src/main/java/com/watchwise/watchwise_api/summary/service/SummaryService.java` — atualizar as assinaturas dos dois casos de uso.
- `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java` — montar resposta paginada do mapa e a grade completa com notas pessoais/comunitárias.
- `src/main/java/com/watchwise/watchwise_api/summary/dto/EpisodeRatingsMapItemDTO.java` — adicionar metadados visuais da série.
- `src/main/java/com/watchwise/watchwise_api/summary/dto/EpisodeRatingsMapResponseDTO.java` — adicionar paginação e totais globais.
- `src/main/java/com/watchwise/watchwise_api/summary/dto/EpisodeRatingsGridResponseDTO.java` — adicionar metadados da série e avaliação geral.
- `src/main/java/com/watchwise/watchwise_api/summary/dto/EpisodeScoreDTO.java` — adicionar média pública e quantidade de avaliações da célula.
- `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java` — adicionar projeções de avaliação pública deduplicada sem alterar a consulta usada pelo sort de listas.
- `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClient.java` — adicionar `getTvDisplayDetails(String tmdbId, String language)` com cache próprio.
- `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCacheConfig.java` — registrar o cache de metadados leves.
- `docs/context/openapi.yaml` — documentar parâmetros, resposta, campos nulos e erros `502`.
- `docs/context/telas-reqs.md` — corrigir o fluxo para usar o proxy do backend e registrar o shape completo das duas telas.
- `docs/context/telas.md` — atualizar o status funcional e as regras de médias.
- `docs/context/business-rules.md` — registrar a deduplicação por usuário/episódio, privacidade e exclusão de rewatches.
- `docs/context/progress.md` — registrar somente o que for implementado durante a execução.

### New files to create

- `src/main/java/com/watchwise/watchwise_api/summary/service/EpisodeRatingsMetadataService.java` — interface para resolver metadados de séries por `seriesTmdbId`.
- `src/main/java/com/watchwise/watchwise_api/summary/service/impl/EpisodeRatingsMetadataServiceImpl.java` — implementação usando `TmdbClient`, cache já configurado e `TmdbImageUrlBuilder`.
- `src/main/java/com/watchwise/watchwise_api/summary/service/EpisodeRatingsMetadata.java` — record interno com `seriesTmdbId`, título, ano, poster e backdrop.
- `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbTvDisplayDetails.java` — shape mínimo para título, data e imagens.
- `src/test/java/com/watchwise/watchwise_api/summary/service/impl/EpisodeRatingsMetadataServiceImplTest.java` — testes do resolver de metadados e das falhas do TMDB.
- `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientDisplayDetailsTest.java` — teste da URL leve e do cache de metadados.

### Existing tests to modify

- `src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java` — testar perfil público, rewatches e desempate da avaliação mais recente.
- `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java` — testar mapa paginado, totais, grade completa e médias.
- `src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerTest.java` — testar parâmetros de paginação e delegação atualizada.
- `src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerIntegrationTest.java` — testar contrato HTTP, visibilidade, validação e resposta de TMDB indisponível.

---

### Task 1: Fixar os DTOs e o contrato de resposta

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/EpisodeRatingsMapItemDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/EpisodeRatingsMapResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/EpisodeRatingsGridResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/EpisodeScoreDTO.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/dto/EpisodeRatingsContractTest.java`

**Interfaces:**
- Produces `EpisodeRatingsMapItemDTO` com `seriesTmdbId`, `watchedEpisodeCount`, `totalEpisodeCount`, `customPosterUrl`, `title`, `releaseYear` e `posterPath`.
- Produces `EpisodeRatingsMapResponseDTO` com `series`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`, `totalWatchedEpisodeCount` e `totalEpisodeCount` anulável.
- Produces `EpisodeScoreDTO` com `seasonNumber`, `episodeNumber`, `score`, `averageScore` anulável e `ratingCount`.
- Produces `EpisodeRatingsGridResponseDTO` com os campos atuais mais `title`, `releaseYear`, `posterPath`, `backdropPath`, `overallAverageScore` anulável e `overallRatingCount`.

- [ ] **Step 1: Escrever testes de contrato que falham.**

  Cobrir:

  ```java
  assertThat(new EpisodeScoreDTO(1, 1, 8, 8.5, 2L).averageScore()).isEqualTo(8.5);
  assertThat(new EpisodeRatingsMapResponseDTO(List.of(), 1, 12, 0, 0, false, 0L, null).totalEpisodeCount())
          .isNull();
  ```

  Verificar que as listas recebidas pelos records continuam imutáveis e que os construtores antigos usados pelos testes atuais continuam compilando quando isso não conflitar com o novo contrato.

- [ ] **Step 2: Executar os testes de contrato para confirmar a falha.**

  Run:

  ```powershell
  .\mvnw.cmd test "-Dtest=EpisodeRatingsContractTest"
  ```

  Expected: FAIL porque os novos campos e construtores ainda não existem.

- [ ] **Step 3: Implementar os records.**

  Manter `customPosterUrl` como override opcional. Usar `Long`/`Double` anuláveis somente onde ausência tem significado; `ratingCount` deve ser `long` e valer `0` quando não houver avaliação. Manter construtores de compatibilidade para `EpisodeScoreDTO(season, episode, score)`, `EpisodeRatingsGridResponseDTO(seriesTmdbId, episodes)` e `EpisodeRatingsMapItemDTO(seriesTmdbId, watched, total)`.

- [ ] **Step 4: Executar os testes de contrato para confirmar a passagem.**

  Run:

  ```powershell
  .\mvnw.cmd test "-Dtest=EpisodeRatingsContractTest"
  ```

  Expected: PASS.

- [ ] **Step 5: Fazer commit somente do código e testes desta tarefa.**

  ```powershell
  git add src/main/java/com/watchwise/watchwise_api/summary/dto src/test/java/com/watchwise/watchwise_api/summary/dto/EpisodeRatingsContractTest.java
  git commit -m "feat(summary): extend episode ratings contracts"
  ```

  Depois do commit, confirmar explicitamente que não há trailer `Co-Authored-By`.

### Task 2: Criar as projeções de banco para avaliações públicas sem rewatches

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java`

**Interfaces:**
- Produces `DiaryEntryRepository.LatestPublicEpisodeRatingAggregate` com `seriesTmdbId`, `seasonNumber`, `episodeNumber`, `scoreSum` e `scoreCount`.
- Produces `DiaryEntryRepository.LatestUserEpisodeScore` com `seasonNumber`, `episodeNumber` e `score`, já deduplicado para um usuário.
- Keeps `findPublicEpisodeRatingAggregatesBySeriesTmdbIdIn` unchanged because it is used by `UserListServiceImpl` and currently has different semantics.

- [ ] **Step 1: Escrever o teste Testcontainers da deduplicação.**

  Criar dados com:

  - dois `DiaryEntry` públicos do mesmo usuário e episódio, com `watchNumber` 1 e 2 e scores 4 e 8;
  - um segundo usuário público com score 6 no mesmo episódio;
  - um usuário privado com score 10;
  - um episódio da temporada 0;
  - uma entrada com `score = null`.

  O resultado esperado para o episódio regular é `scoreSum = 14` e `scoreCount = 2`. O resultado não pode incluir o score 4 do rewatch antigo, o score do perfil privado, a temporada 0 ou a entrada sem score.

  Adicionar também uma asserção para a projeção pessoal: para o mesmo usuário e episódio, o método retorna somente o score 8 do maior `watchNumber`.

- [ ] **Step 2: Executar o teste para confirmar a falha.**

  Run:

  ```powershell
  .\mvnw.cmd test "-Dtest=DiaryEntryRepositoryTest#shouldAggregateLatestPublicEpisodeRatingsWithoutRewatch"
  ```

  Expected: FAIL porque a projeção/query ainda não existe.

- [ ] **Step 3: Implementar a query nativa com `ROW_NUMBER()`.**

  Usar esta forma de consulta, adaptando apenas nomes já existentes no schema:

  ```sql
  WITH ranked AS (
      SELECT c.series_tmdb_id,
             c.season_number,
             c.episode_number,
             d.score,
             ROW_NUMBER() OVER (
                 PARTITION BY d.user_id,
                              c.series_tmdb_id,
                              c.season_number,
                              c.episode_number
                 ORDER BY d.watch_number DESC,
                          d.created_at DESC,
                          d.id DESC
             ) AS row_number
      FROM diary_entries d
      JOIN contents c ON c.id = d.content_id
      JOIN users u ON u.id = d.user_id
      WHERE c.type = 'EPISODE'
        AND c.season_number > 0
        AND d.score IS NOT NULL
        AND u.is_profile_public = true
        AND c.series_tmdb_id = :seriesTmdbId
  )
  SELECT series_tmdb_id AS seriesTmdbId,
         season_number AS seasonNumber,
         episode_number AS episodeNumber,
         SUM(score) AS scoreSum,
         COUNT(score) AS scoreCount
  FROM ranked
  WHERE row_number = 1
  GROUP BY series_tmdb_id, season_number, episode_number
  ORDER BY season_number, episode_number
  ```

  Criar também uma consulta dedicada à nota pessoal da série:

  ```sql
  WITH ranked AS (
      SELECT c.season_number,
             c.episode_number,
             d.score,
             ROW_NUMBER() OVER (
                 PARTITION BY c.season_number, c.episode_number
                 ORDER BY d.watch_number DESC,
                          d.created_at DESC,
                          d.id DESC
             ) AS row_number
      FROM diary_entries d
      JOIN contents c ON c.id = d.content_id
      WHERE d.user_id = :userId
        AND c.type = 'EPISODE'
        AND c.series_tmdb_id = :seriesTmdbId
        AND c.season_number > 0
  )
  SELECT season_number AS seasonNumber,
         episode_number AS episodeNumber,
         score
  FROM ranked
  WHERE row_number = 1
  ORDER BY season_number, episode_number
  ```

  Não alterar a consulta existente usada por `UserListServiceImpl`: o novo endpoint precisa da semântica sem rewatches, enquanto o sort de listas mantém seu contrato atual.

- [ ] **Step 4: Executar o teste de repositório e os testes de regressão relacionados.**

  ```powershell
  .\mvnw.cmd test "-Dtest=DiaryEntryRepositoryTest,UserListServiceImplTest"
  ```

  Expected: PASS, com a consulta antiga de ordenação de listas permanecendo inalterada.

- [ ] **Step 5: Fazer commit do código e testes.**

  ```powershell
  git add src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java
  git commit -m "feat(diary): aggregate latest public episode ratings"
  ```

  Depois do commit, confirmar explicitamente a ausência de `Co-Authored-By`.

### Task 3: Criar o resolver de metadados de série pelo proxy do TMDB

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/summary/service/EpisodeRatingsMetadataService.java`
- Create: `src/main/java/com/watchwise/watchwise_api/summary/service/EpisodeRatingsMetadata.java`
- Create: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/EpisodeRatingsMetadataServiceImpl.java`
- Create: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/EpisodeRatingsMetadataServiceImplTest.java`

**Interfaces:**
- Consumes `TmdbClient.getTvDisplayDetails`, `TmdbLookupResult`, `TmdbTvDisplayDetails` e `TmdbImageUrlBuilder`.
- Produces:

  ```java
  public interface EpisodeRatingsMetadataService {
      Map<String, EpisodeRatingsMetadata> resolve(Collection<String> seriesTmdbIds, String language);
      EpisodeRatingsMetadata resolve(String seriesTmdbId, String language);
  }
  ```

  ```java
  public record EpisodeRatingsMetadata(
          String seriesTmdbId,
          String title,
          Integer releaseYear,
          String posterPath,
          String backdropPath) {
  }
  ```

- [ ] **Step 1: Escrever testes para a consulta TMDB leve, cache e falhas.**

  Verificar que:

  - a URL chamada é `/tv/{id}?language={language}`, sem `append_to_response`;
  - `name`, `first_air_date`, `poster_path` e `backdrop_path` são convertidos para o record;
  - poster e backdrop passam pelo mesmo builder de URLs usado pelo proxy de conteúdo;
  - `first_air_date = null` produz `releaseYear = null`;
  - `TmdbLookupResult.NotFound` vira `NotFoundException`;
  - `TmdbLookupResult.Unavailable` vira `TmdbUnavailableException` para o handler produzir `502`;
  - IDs em branco são rejeitados antes da chamada externa.

- [ ] **Step 2: Executar os testes para confirmar a falha.**

  ```powershell
  .\mvnw.cmd test "-Dtest=EpisodeRatingsMetadataServiceImplTest"
  ```

  Expected: FAIL porque o serviço ainda não existe.

- [ ] **Step 3: Implementar o client e o serviço.**

  Adicionar `TmdbTvDisplayDetails`, um cache Caffeine separado e `TmdbClient.getTvDisplayDetails`. Normalizar IDs, remover duplicados, usar a linguagem preferida do viewer e delegar o cache ao `TmdbClient`. Não criar `Content`, não chamar `POST /contents/reference` e não solicitar o payload completo da série; esta é uma leitura pura.

- [ ] **Step 4: Executar os testes para confirmar a passagem.**

  ```powershell
  .\mvnw.cmd test "-Dtest=EpisodeRatingsMetadataServiceImplTest"
  ```

  Expected: PASS.

- [ ] **Step 5: Fazer commit.**

  ```powershell
  git add src/main/java/com/watchwise/watchwise_api/summary/service src/main/java/com/watchwise/watchwise_api/common/tmdb src/test/java/com/watchwise/watchwise_api/summary/service/impl/EpisodeRatingsMetadataServiceImplTest.java src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientDisplayDetailsTest.java
  git commit -m "feat(summary): resolve episode ratings metadata"
  ```

  Confirmar ausência de `Co-Authored-By`.

### Task 4: Implementar o mapa paginado e os totais globais

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/SummaryService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/controller/SummaryController.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerTest.java`

**Interfaces:**
- Changes the service signature to:

  ```java
  EpisodeRatingsMapResponseDTO getEpisodeRatingsMap(
          UUID viewerId, UUID userId, Integer page, Integer size);
  ```

- Changes the controller route to accept optional `page` and `size`, both 1-based at the HTTP boundary, using `PageRequestFactory` for validation.

- [ ] **Step 1: Atualizar testes de serviço e controller para o novo contrato.**

  Add cases for:

  - default page size `12`;
  - page 2 returning only the second slice;
  - `totalSeriesCount`, `totalWatchedEpisodeCount` and the sum of known totals;
  - `totalEpisodeCount = null` when at least one series lacks a total;
  - ordering by watched ratio descending, then watched count descending, then `seriesTmdbId` ascending;
  - metadata from `EpisodeRatingsMetadataService` only for the current page;
  - custom poster resolved for the target profile;
  - page `0`, negative page, `size = 0` and oversized size producing the existing `BadRequestException` contract.

- [ ] **Step 2: Executar os testes para confirmar a falha.**

  ```powershell
  .\mvnw.cmd test "-Dtest=SummaryServiceImplTest,SummaryControllerTest"
  ```

  Expected: FAIL because the service signature and response assembly still use the old unpaged list.

- [ ] **Step 3: Implementar o caso de uso.**

  Execution order:

  1. Load the target user and call `assertCanViewSummary` before loading diary data.
  2. Load the viewer's preferred language for metadata resolution.
  3. Read the existing compact series-count aggregate and the progress snapshots required to calculate global totals and ratios. This is intentionally an in-memory ranking step because the denominator is maintained by shared TMDB progress snapshots, not by `DiaryEntry`.
  4. Sort the compact aggregate deterministically.
  5. Apply the validated page slice before resolving display metadata.
  6. Resolve lightweight TMDB metadata and custom posters only for the page slice.
  7. Return page fields plus global totals.

  Keep the existing semantics of `watchedEpisodeCount`: distinct regular episode coordinates, independent of rewatches. Treat a missing total as unknown instead of adding zero.

- [ ] **Step 4: Implementar a delegação no controller.**

  Use a dedicated map page-size cap of `100`; default the screen to `12`. This is response pagination over the compact aggregate, not a claim that the database query itself is page-ordered by watched ratio. Preserve the existing `403`, `404` and `502` behavior and add `400` to the documented invalid page/size cases.

- [ ] **Step 5: Executar os testes para confirmar a passagem.**

  ```powershell
  .\mvnw.cmd test "-Dtest=SummaryServiceImplTest,SummaryControllerTest"
  ```

  Expected: PASS.

- [ ] **Step 6: Fazer commit do código e testes.**

  ```powershell
  git add src/main/java/com/watchwise/watchwise_api/summary/service src/main/java/com/watchwise/watchwise_api/summary/controller/SummaryController.java src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerTest.java
  git commit -m "feat(summary): paginate episode ratings map"
  ```

  Confirmar ausência de `Co-Authored-By`.

### Task 5: Implementar a grade completa com notas pessoais e médias Watchwise

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/EpisodeRatingsGridResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/EpisodeScoreDTO.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerIntegrationTest.java`

**Interfaces:**
- Keeps `GET /users/{userId}/series/{seriesTmdbId}/episode-ratings` as the route.
- The response `episodes` list becomes the canonical flat grid source: one item for every regular episode launched through today.
- `EpisodeScoreDTO.score` is the latest personal score; `averageScore` and `ratingCount` are the public Watchwise aggregate for the same coordinate.

- [ ] **Step 1: Escrever testes unitários da grade completa.**

  Cover:

  - one season with five released episodes where only episodes 1 and 3 were rated by the target user;
  - blank cells are returned with `score = null` rather than omitted;
  - a target-user rewatch returns only the highest `watchNumber` score;
  - public community ratings use one row per public user and coordinate;
  - a private user's rating is excluded;
  - the old public rating is excluded when that same public user has a newer rewatch;
  - `averageScore` is null and `ratingCount = 0` when no public rating exists;
  - `overallAverageScore` is the weighted average of all deduplicated public ratings, not the average of episode averages;
  - `overallRatingCount` counts deduplicated user/episode ratings;
  - specials and future episodes are not emitted;
  - `customPosterUrl` belongs to the target profile, not the viewer's poster collection.

- [ ] **Step 2: Executar os testes para confirmar a falha.**

  ```powershell
  .\mvnw.cmd test "-Dtest=SummaryServiceImplTest#getEpisodeRatingsGrid*"
  ```

  Expected: FAIL because the response currently contains only logged personal episodes and no community aggregates.

- [ ] **Step 3: Implementar a leitura pessoal e a grade.**

  In `SummaryServiceImpl.getEpisodeRatingsGrid`:

  1. Normalize and validate `seriesTmdbId` as today.
  2. Enforce target-profile visibility before any rating query.
  3. Load the viewer language and resolve series display metadata.
  4. Load the progress snapshot for the series and derive valid coordinates from regular seasons and `regularReleasedEpisodeCount`.
  5. Load the `LatestUserEpisodeScore` projection instead of materializing every target-user `DiaryEntry`, already ordered by season and episode.
  6. Load `LatestPublicEpisodeRatingAggregate` rows and discard coordinates outside the valid released set.
  7. Build one `EpisodeScoreDTO` per valid coordinate. Use null personal score for an episode the target did not rate.
  8. Compute overall sum/count from the filtered aggregate rows and return metadata plus poster override.

  Do not use `GET /contents/stats` internally: it cannot see every episode that has no internal `Content` reference and its current semantics include rewatches.

- [ ] **Step 4: Add the integration cases.**

  Through MockMvc/Testcontainers, verify:

  - `200` includes title, year, poster, backdrop, personal score, public average and overall rating fields;
  - `403` remains enforced for an inaccessible private profile;
  - `404` remains returned for an unknown user;
  - `502` is returned when the series metadata/snapshot cannot be hydrated and no usable cached data exists;
  - the response never exposes private users or their scores.

- [ ] **Step 5: Executar os testes para confirmar a passagem.**

  ```powershell
  .\mvnw.cmd test "-Dtest=SummaryServiceImplTest,SummaryControllerIntegrationTest"
  ```

  Expected: PASS.

- [ ] **Step 6: Fazer commit do código e testes.**

  ```powershell
  git add src/main/java/com/watchwise/watchwise_api/summary src/test/java/com/watchwise/watchwise_api/summary
  git commit -m "feat(summary): add episode rating aggregates"
  ```

  Confirmar ausência de `Co-Authored-By`.

### Task 6: Atualizar o contrato OpenAPI e as regras de tela

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/telas-reqs.md`
- Modify: `docs/context/telas.md`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

**Interfaces:**
- Documentation becomes the source of truth for the frontend integration and must match the Java records exactly.

- [ ] **Step 1: Documentar o mapa.**

  Add optional `page` and `size`, default size `12`, max size `100`, the page fields, the global totals and the enriched series item. Document that `totalEpisodeCount` and metadata fields can be null only under the existing TMDB/snapshot fallback contract.

- [ ] **Step 2: Documentar a grade.**

  Replace the current statement that the client must fetch series information directly from TMDB. Document that the backend returns title, year, poster, backdrop, all regular released episode coordinates, personal score, public average, rating count, overall average and overall rating count.

- [ ] **Step 3: Documentar as regras de negócio.**

  Add an Episode Ratings section pointing to `SummaryServiceImpl` and `DiaryEntryRepository`:

  - only public profiles contribute to community averages;
  - rewatches are deduplicated per user and coordinate;
  - the highest `watchNumber`, then timestamp and ID, wins;
  - season 0 and unreleased episodes are excluded;
  - absent scores remain null and are not converted to zero.

- [ ] **Step 4: Atualizar `progress.md`.**

  Append a new `## 2026-10-06 — ...` entry only after the implementation and verification actually land. Record the implemented endpoints, privacy rule, deduplication rule and tests. Do not add roadmap items.

- [ ] **Step 5: Verificar que a documentação não contém o contrato antigo.**

  ```powershell
  rg -n "cliente.*TMDB|direct.*TMDB|EpisodeRatingsMap|EpisodeRatingsGrid|averageScore|ratingCount" docs/context/openapi.yaml docs/context/telas.md docs/context/telas-reqs.md docs/context/business-rules.md
  ```

  Expected: every occurrence describes the new backend-proxy contract; no screen requirement instructs the frontend to call TMDB directly.

  These documentation files remain worktree-only and are not added to code commits.

### Task 7: Integrar as duas telas no consumidor frontend

**Files:**
- Frontend repository files are outside the current `watchwise-api` workspace and must be selected when the frontend repository is provided.
- Reference: `D:/Users/Lucas C/Downloads/Episode-Ratings-Map.dc.html`
- Reference: `D:/Users/Lucas C/Downloads/Episode-Ratings-Map-Breaking-Bad.dc.html`

**Interfaces:**
- Consumes `GET /users/{userId}` for the profile header.
- Consumes the paginated map response for cards and global counters.
- Consumes the enriched grid response for both tabs and the share modal.

- [ ] **Step 1: Wire the map screen.**

  Replace hardcoded cards, counters and pagination with:

  - `series` for the current page;
  - `totalSeriesCount`, `totalWatchedEpisodeCount` and `totalEpisodeCount` for the summary strip;
  - `customPosterUrl ?? posterPath` for the image;
  - `title`, `releaseYear`, `watchedEpisodeCount` and `totalEpisodeCount` for each card;
  - `/users/{userId}/series/{seriesTmdbId}/episode-ratings` as the card destination.

  When `totalEpisodeCount` is null, show an unknown denominator and an indeterminate progress state rather than zero percent.

- [ ] **Step 2: Wire the show screen.**

  Replace the hardcoded Breaking Bad arrays with `episodes` grouped by `seasonNumber`:

  - `score` drives `My ratings`;
  - `averageScore` drives `Average ratings`;
  - null values render empty cells;
  - season averages ignore null values;
  - overall rating and count use `overallAverageScore` and `overallRatingCount`;
  - poster uses the custom override before the TMDB poster;
  - hero uses `backdropPath` and title/year from the response.

- [ ] **Step 3: Keep share generation client-side.**

  The modal may switch between the personal and community arrays, hide seasons, reposition/zoom the backdrop and download an image without a new backend endpoint. Ensure the downloaded representation uses the same null filtering and one-decimal formatting as the main grid.

- [ ] **Step 4: Add frontend loading/error/empty states.**

  Handle `401`, `403`, `404`, `502`, empty `series`, an empty episode list and null aggregate scores. Do not show private-profile data after a failed authorization response.

### Task 8: Run the complete verification and handoff

**Files:**
- Verify all modified Java, test and documentation files from Tasks 1–7.

- [ ] **Step 1: Run focused tests.**

  ```powershell
  .\mvnw.cmd test "-Dtest=DiaryEntryRepositoryTest,SummaryServiceImplTest,SummaryControllerTest,SummaryControllerIntegrationTest,EpisodeRatingsMetadataServiceImplTest,EpisodeRatingsContractTest"
  ```

  Expected: exit code `0` and zero failing tests.

- [ ] **Step 2: Run the complete Maven test suite.**

  ```powershell
  .\mvnw.cmd test
  ```

  Expected: exit code `0`. Repository tests require Docker/Testcontainers.

- [ ] **Step 3: Check formatting and accidental changes.**

  ```powershell
  git diff --check
  git status --short
  ```

  Expected: no whitespace errors, only intended code/test changes plus the synchronized worktree-only documentation and plan.

- [ ] **Step 4: Confirmar o limite de custo da leitura.**

  Verificar nos testes e no mock HTTP que:

  - o mapa resolve metadados somente para os itens da página atual;
  - cada item de metadado usa a URL TMDB leve, sem `append_to_response`;
  - uma segunda leitura do mesmo ID usa o cache;
  - a grade usa duas projeções SQL compactas, pessoal e comunitária, sem carregar todas as entidades `DiaryEntry` para deduplicar em Java;
  - não existe uma chamada TMDB por episódio.

  Expected: custo de metadata proporcional à página e custo de ratings proporcional às projeções SQL, sem N+1 por episódio.

- [ ] **Step 5: Rebuild the graph after code changes.**

  ```powershell
  & 'C:/Users/Lucas C/AppData/Roaming/uv/tools/graphifyy/Scripts/graphify.exe' update .
  ```

  Expected: graphify updates the repository graph without changing application source files.

- [ ] **Step 6: Inspect the final contract against both screen references.**

  Confirm each rendered field has one source:

  | Screen field | Source |
  |---|---|
  | Profile header | `GET /users/{userId}` |
  | Map counters | `EpisodeRatingsMapResponseDTO` global totals |
  | Map card metadata | `EpisodeRatingsMapItemDTO` |
  | Personal cell | `EpisodeScoreDTO.score` |
  | Community cell | `EpisodeScoreDTO.averageScore` |
  | Cell count/tooltips | `EpisodeScoreDTO.ratingCount` |
  | Show rating | `EpisodeRatingsGridResponseDTO.overallAverageScore` and `overallRatingCount` |
  | Show poster/backdrop | `customPosterUrl`, `posterPath`, `backdropPath` |

- [ ] **Step 7: Leave push and frontend repository changes for explicit authorization.**

  Report the local commits, the worktree-only documentation files and the exact frontend repository still needed for the UI implementation. Do not run `git push`.

## Execution order and checkpoints

1. Tasks 1–3 establish the DTO, database and metadata boundaries.
2. Task 4 makes the list screen usable without the show screen.
3. Task 5 makes the show screen usable and adds the community tab.
4. Task 6 synchronizes the contract documentation.
5. Task 7 is executed in the frontend repository once it is available.
6. Task 8 is the release gate.

Each code task ends with a focused test run and a local code commit. Documentation is synchronized in the same worktree but is intentionally not committed under the repository convention.
