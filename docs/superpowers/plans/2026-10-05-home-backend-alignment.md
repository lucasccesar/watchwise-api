# Home Backend Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Alinhar o contrato e a implementação da Home com o HTML de referência, cobrindo estatísticas, Continue watching, calendário, atividade social e os casos em que o backend já retorna dados diferentes do que a tela rotula.

**Implementation status:** Backend concluído. O repositório não contém um frontend executável; `Home-DS.dc.html` é uma referência externa em `Downloads`. A integração dos bindings visuais precisa ser aplicada no projeto cliente quando ele existir.

**Architecture:** Manter `GET /users/{userId}/summary/home` como o agregado principal da Home, com DTOs específicos para as partes que precisam de payload reduzido. `nextEpisodes` será limitado a quatro; `recentlyWatched`, `socialActivities`, `viewer` e `hasUnreadNotifications` não reutilizarão DTOs completos de outras telas. O calendário e o feed continuam sendo fontes especializadas: a Home consulta o calendário para hoje e os quatro dias seguintes, usa `CalendarSource` em vez de inventar plataformas de streaming e busca somente as três atividades mais recentes do feed. Nenhuma informação de TMDB será persistida como metadado completo em `Content`, e a leitura de `nextEpisodes` não criará referências como efeito colateral.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring MVC, Spring Data JPA, PostgreSQL/Testcontainers, Flyway, Caffeine/TMDB proxy, Lombok, MapStruct, JUnit 5, Mockito, MockMvc e Maven Wrapper.

## Global Constraints

- Preservar `GET /users/{userId}/summary/home` como a rota principal; novos campos devem ser aditivos durante a migração do cliente.
- Não permitir chamadas diretas do frontend ao TMDB; usar `TmdbClient` e os serviços de proxy existentes.
- Não persistir título, elenco, pôster ou outros metadados completos de filme/série em `Content`.
- Não criar referências `Content` durante uma leitura da Home; a leitura deve ser sem efeitos colaterais.
- `nextEpisodes` deve retornar no máximo quatro itens.
- A resposta específica da Home não deve expor email, descrição, preferências, seguidores, banner, comentários textuais, likes detalhados, `recentComments`, nomes de companheiros, `createdAt` ou `updatedAt` quando esses dados não são usados pelo desenho; os companheiros da seção Recently watched ficam limitados às fotos de perfil.
- Os endpoints completos de usuário, feed, notificações, diário e calendário permanecem disponíveis para suas telas próprias; a redução de payload vale para a projeção da Home.
- Manter as métricas existentes de Month/Year/All Time e criar para a Home uma métrica explícita de gêneros por episódio.
- Corrigir a janela para exatamente 30 datas inclusivas, contando hoje e os 29 dias anteriores.
- Rotas, schemas, `docs/context/openapi.yaml`, `docs/context/telas.md`, `docs/context/telas-reqs.md`, `business-rules.md` e `progress.md` devem permanecer sincronizados com o código.
- Alterações em documentação permanecem fora de commits por padrão, conforme `AGENTS.md`.
- Testes novos devem seguir o padrão de `@DisplayName` do projeto e ser escritos antes da implementação correspondente.
- Falhas de TMDB no enriquecimento de `nextEpisodes` devem omitir somente o card afetado e manter o restante da Home em `200`; nunca retornar dados de catálogo inventados ou parcialmente incorretos.

## Decisions and Scope

### Decisões adotadas

1. Os totais ausentes entram em `HomeSummaryResponseDTO` como campos novos, sem remover imediatamente os campos existentes.
2. `nextEpisodes` continuará sendo o campo usado pelo Continue watching, mas seus itens serão enriquecidos na própria resposta de `summary/home`, limitados a quatro, para que o cliente não precise criar referências de conteúdo nem chamar o TMDB.
3. A seção Upcoming exibirá `WATCHLIST` e `IN_PROGRESS` vindos de `CalendarEvent.sources`, filtrados para hoje e os quatro dias seguintes. Os textos `Max`, `Disney+` e `Apple TV+` do protótipo não serão tratados como dados reais até que exista um contrato explícito de provedores no calendário.
4. A Home terá uma métrica própria de gêneros por episódio. Ela contará cada `DiaryEntry` de `EPISODE`, usando os gêneros da série-pai; não reutilizará a query de títulos distintos usada por Month/Year/All Time.
5. A seção Social será apenas um resumo fixo das três atividades mais recentes de `/feed`. A tela completa de Social continuará responsável por paginação, comentários, curtidas e demais interações.
6. `TOP5_UPDATE` continuará mostrando o Top 5 atual, não um snapshot histórico. A UI e a documentação devem deixar essa semântica explícita.
7. A Home exibirá `viewer.name`, `viewer.username` e `viewer.profilePicture`. O restante do contrato completo de `User` não será repetido na Home.
8. `hasUnreadNotifications` será um booleano calculado por uma consulta de existência; a Home não receberá objetos completos de notificação.

### Decisões confirmadas

- O enriquecimento TMDB de `nextEpisodes` é best-effort: falha em um item omite esse card e não derruba a Home inteira.
- A Home usa as fontes `Watchlist`/`In progress`, não plataformas de streaming.
- O campo `name` é obrigatório em novos cadastros, independente e não exclusivo; `username` continua sendo o identificador único. A migration preenche `name` com `username` apenas para usuários já existentes.

## File Map

### User display name

- Create: `src/main/resources/db/migration/V67__add-name-to-users.sql` — adicionar `users.name`, preencher registros existentes com `username` e aplicar `NOT NULL` sem unicidade.
- Modify: `src/main/java/com/watchwise/watchwise_api/user/entity/User.java` — persistir `name` como campo de exibição independente.
- Modify: `src/main/java/com/watchwise/watchwise_api/user/dto/PostUserDTO.java` — exigir `name` não vazio com limite de tamanho.
- Modify: `src/main/java/com/watchwise/watchwise_api/user/dto/PatchUserDTO.java` — permitir alterar `name` sem alterar `username`.
- Modify: `src/main/java/com/watchwise/watchwise_api/user/dto/UserResponseDTO.java` — expor `name` no contrato completo do usuário.
- Modify: `src/main/java/com/watchwise/watchwise_api/user/dto/PublicUserProfileDTO.java` — expor `name` no perfil público.
- Modify: `src/main/java/com/watchwise/watchwise_api/user/dto/PublicUserDTO.java` — expor `name` no DTO público usado em interações.
- Modify: `src/main/java/com/watchwise/watchwise_api/user/dto/UserPreviewDTO.java` — expor `name` nos previews sociais.
- Modify: `src/main/java/com/watchwise/watchwise_api/user/mapper/UserMapper.java` — mapear `name` explicitamente conforme `unmappedTargetPolicy = ERROR`.
- Modify: `src/main/java/com/watchwise/watchwise_api/user/service/impl/UserServiceImpl.java` — normalizar e atualizar `name`, mantendo username separado.

### Home-specific compact projections

- Create: `src/main/java/com/watchwise/watchwise_api/summary/dto/HomeViewerDTO.java` — somente `name`, `username` e `profilePicture`.
- Create: `src/main/java/com/watchwise/watchwise_api/summary/dto/HomeRecentlyWatchedDTO.java` — somente conteúdo exibível, score, `watchedDate` e fotos de perfil dos companheiros.
- Create: `src/main/java/com/watchwise/watchwise_api/summary/dto/HomeSocialActivityDTO.java` — `name` e `username` do autor, tipo, conteúdo, horário, `likesCount` e `commentsCount`, sem texto de comentário, likes detalhados, `recentComments` ou nomes de companheiros.
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/HomeSummaryResponseDTO.java` — adicionar `viewer`, `hasUnreadNotifications`, `socialActivities` e trocar `recentlyWatched` para o DTO compacto.
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java` — montar as projeções compactas sem repassar DTOs completos.
- Modify: `src/main/java/com/watchwise/watchwise_api/feed/service/FeedService.java` and `src/main/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImpl.java` — disponibilizar os três eventos mais recentes para a projeção Home sem alterar a resposta completa de `/feed`.
- Modify: `src/main/java/com/watchwise/watchwise_api/notification/repository/NotificationRepository.java` — adicionar consulta `existsByUserIdAndIsReadFalse` ou equivalente.
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java` — preencher `hasUnreadNotifications` pela consulta de existência, sem carregar notificações completas.

### Home summary and aggregates

- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/HomeSummaryResponseDTO.java` — adicionar série distinta, totais da janela de 30 dias e contagem de gêneros por episódio.
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/SeriesInProgressPreviewDTO.java` — adicionar os metadados do card sem trocar o tipo já usado por `nextEpisodes`.
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java` — calcular a janela exata, agregar novos totais e delegar o enriquecimento.
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/SummaryService.java` — manter a assinatura pública e documentar o novo agregado.
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java` — consultas de contagem por tipo, minutos por janela e séries distintas.
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/controller/SummaryController.java` — nenhuma mudança de rota; atualizar somente se a assinatura de resposta exigir validação adicional.

### Continue watching enrichment

- Create: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/HomeNextEpisodeAssembler.java` — resolver o próximo episódio, buscar metadados pelo proxy TMDB e combinar progresso, avaliação e estatísticas locais.
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/ContentDetailsService.java` — expor uma leitura por chave natural de episódio sem criar `Content`.
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImpl.java` — usar `TmdbClient` cacheado para título, still, runtime e data.
- Modify: `src/main/java/com/watchwise/watchwise_api/content/repository/ContentRepository.java` — localizar uma referência existente por `seriesTmdbId + seasonNumber + episodeNumber + type` quando for necessário consultar estatísticas.
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java` — localizar o estado do episódio para o usuário sem depender de `contentId` criado durante a leitura.

### Calendar and feed contracts

- Modify: `src/main/java/com/watchwise/watchwise_api/calendar/dto/CalendarEventDTO.java` only if the final contract needs a display-oriented field; otherwise keep the existing DTO and use `sources` in the client.
- Modify: `src/main/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarEventAssembler.java` only if a status/source mapping needs correction; do not add streaming providers in this change.
- Modify: `src/main/java/com/watchwise/watchwise_api/feed/dto/FeedItemDTO.java` only if contract tests reveal a missing field required by the compact three-item Home summary; the current DTO already contains user, event type, timestamp and preview data.
- Modify: `src/main/java/com/watchwise/watchwise_api/notification/repository/NotificationRepository.java` — add the indexed unread-existence query used by the Home projection.

### Tests

- Modify: `src/test/java/com/watchwise/watchwise_api/user/service/UserServiceImplTest.java` — required name validation, update and preservation of username uniqueness.
- Modify: `src/test/java/com/watchwise/watchwise_api/user/controller/UserControllerIntegrationTest.java` — registration, patch and response fields for `name`.
- Modify: `src/test/java/com/watchwise/watchwise_api/user/repository/UserRepositoryTest.java` — migration-backed persistence and required `name` behavior.
- Create or modify: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/HomeSummaryProjectionTest.java` — compact viewer, recently watched, social and unread-notification projections.
- Modify: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java` — summary aggregates, date window, privacy and Continue watching assembly.
- Modify: `src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerTest.java` — response compatibility and current-user delegation.
- Modify: `src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerIntegrationTest.java` — JSON contract, 30-day boundaries, auth and visibility.
- Modify: `src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java` — aggregate queries and distinct-series semantics.
- Create or modify: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/HomeNextEpisodeAssemblerTest.java` — natural-key resolution, TMDB mapping, local rating/statistics and failure behavior.
- Modify: `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImplTest.java` — read-only episode preview and cache/error behavior.
- Modify: `src/test/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarEventAssemblerTest.java` — sources and watch/release statuses used by Upcoming.
- Modify: `src/test/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImplTest.java` and `src/test/java/com/watchwise/watchwise_api/feed/controller/FeedControllerIntegrationTest.java` — keep Home-compatible feed event shapes and cursor behavior covered.

### Documentation and client contract

- Modify: `docs/context/openapi.yaml` — new HomeSummary and `SeriesInProgressPreview` fields, best-effort next-episode enrichment and clarified calendar/feed semantics.
- Modify: `docs/context/database-schema.md` and `docs/context/database-schema.html` — add the non-unique `users.name` column and its migration/backfill rule.
- Modify: `docs/context/telas.md` — mark calendar as implemented, describe the exact Home data semantics, the episode-based Home genre count and the compact projections.
- Modify: `docs/context/telas-reqs.md` — remove the impossible `GET /contents/details` flow for raw `nextEpisodes` IDs and document the enriched `nextEpisodes` response.
- Modify: `docs/context/business-rules.md` — exact 30-day window, no read-time Content creation, next-episode resolution and Top 5 current-preview semantics.
- Modify: `docs/context/progress.md` — chronological entry after implementation only; do not record this plan as shipped work.
- Reference only: `D:/Users/Lucas C/Downloads/Home-DS.dc.html` — update the frontend implementation/design source outside this repository after the backend contract is approved.

---

### Task 0: Add the display name to the user model

**Files:**
- Create: `src/main/resources/db/migration/V67__add-name-to-users.sql`
- Modify: `src/main/java/com/watchwise/watchwise_api/user/entity/User.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/user/dto/PostUserDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/user/dto/PatchUserDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/user/dto/UserResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/user/dto/PublicUserProfileDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/user/dto/PublicUserDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/user/dto/UserPreviewDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/user/mapper/UserMapper.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/user/service/impl/UserServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/user/service/UserServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/user/controller/UserControllerIntegrationTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/user/repository/UserRepositoryTest.java`

**Interfaces:**
- Consumes: the current `username` field and the user registration/update flow.
- Produces: independent `name` and `username` values, with `name` required and non-unique.

- [x] **Step 1: Write RED tests for required name and update.**

  Assert that a registration without `name` returns validation `400`, a registration with `name` stores the trimmed display value, and a patch changes `name` without changing `username`.

- [x] **Step 2: Add the migration.**

  Create `V67__add-name-to-users.sql` with this sequence: add `name` as nullable, set existing rows to `username`, then set `name` to `NOT NULL`. Do not add a unique constraint or index for `name`.

- [x] **Step 3: Add the entity, request DTO and response fields.**

  Use `@Column(length = 120, nullable = false)` on `User.name`. Add `@NotBlank @Size(max = 120)` to registration and `@Size(max = 120)` to the patch DTO. Existing database rows remain valid through the migration backfill; new registrations must provide `name`.

- [x] **Step 4: Update mapping and service behavior.**

  Map `name` explicitly in `UserMapper`, trim it in `UserServiceImpl`, reject missing values through bean validation, and leave the existing username uniqueness/conflict behavior unchanged.

- [x] **Step 5: Run the user tests.**

  ```powershell
  .\mvnw.cmd test "-Dtest=UserServiceImplTest,UserControllerIntegrationTest,UserRepositoryTest"
  ```

### Task 1: Freeze the Home contract and write RED tests

**Files:**
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/controller/SummaryControllerIntegrationTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java`
- Modify: `docs/context/openapi.yaml`

**Interfaces:**
- Consumes: the existing `HomeSummaryResponseDTO`, `SummaryServiceImpl.getHomeSummary`, `DiaryEntryRepository` query contracts and the HTML fields at lines 43, 48, 55, 82, 124, 144 and 191 of `Home-DS.dc.html`.
- Produces: failing tests that define the additive fields and the exact date/label semantics before production code changes.

- [x] **Step 1: Define the additive response shape in the tests.**

  Assert that `GET /users/{userId}/summary/home` will expose:

  ```json
  {
    "totalSeriesWatched": 347,
    "totalMinutesWatchedLast30Days": 375,
    "totalMoviesWatchedLast30Days": 9,
    "totalEpisodesWatchedLast30Days": 61,
    "totalDistinctMoviesWatched": 280,
    "nextEpisodes": [],
    "viewer": { "name": "Lucas C", "username": "lucas.c", "profilePicture": "https://default-image.png" },
    "hasUnreadNotifications": false,
    "recentlyWatched": [],
    "socialActivities": []
  }
  ```

  Keep all existing aggregate fields in the response. Use values that are intentionally different from the all-time totals so a test cannot pass by copying the wrong aggregate. Assert that `totalDistinctMoviesWatched` counts unique movie TMDB references, `nextEpisodes` contains at most four items, `recentlyWatched` contains only `watchedDate`, score, display content and companion profile photos, `socialActivities` contains author `name` and `username` plus only likes/comments counts, and the viewer projection contains only `name`, `username` and `profilePicture`.

- [x] **Step 2: Add RED unit tests for the exact 30-day window.**

  Inject a fixed date through the existing test setup or the planned `Clock`, persist or mock entries on `today`, `today.minusDays(29)` and `today.minusDays(30)`, and assert that only the first two dates are included. Assert separate movie and episode counts and summed minutes.

- [x] **Step 3: Add RED repository tests for the new aggregates.**

  Cover: movie count by window, episode count by window, runtime sum by window, distinct watched-series count, an episode on the first included day, and an entry one day before the window. Add a separate Home test proving the new genre query counts every episode entry; keep the existing distinct-series tests for Month/Year/All Time unchanged.

- [x] **Step 4: Add RED tests for the enriched `nextEpisodes` contract shape.**

  Assert that an enriched item can represent the existing progress fields, including `watchedPercentage`, plus series/episode titles, still, runtime, release date, nullable local `contentId`, nullable community score and whether it is available to watch. Do not expose a user score or rating action because this block contains only episodes the user has not watched yet. Assert that a missing local `Content` row does not cause the read path to create one.

- [x] **Step 5: Run the focused tests and record the expected RED state.**

  Run:

  ```powershell
  .\mvnw.cmd test "-Dtest=SummaryServiceImplTest,SummaryControllerTest,SummaryControllerIntegrationTest,DiaryEntryRepositoryTest"
  ```

  Expected: compilation or assertion failures because the additive DTO fields and repository methods do not exist yet.

- [x] **Step 6: Update the OpenAPI schema to describe the intended shape before implementation.**

  Add the new properties to `HomeSummary` and `SeriesInProgressPreview`, document nullable local IDs and scores, document the exact 30 inclusive dates and document that `nextEpisodes` is the enriched Continue watching payload.

### Task 2: Implement exact Home aggregates and date semantics

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/HomeSummaryResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/SeriesInProgressPreviewDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/SummaryService.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepositoryTest.java`

**Interfaces:**
- Consumes: the RED tests from Task 1 and the existing `DiaryEntryRepository` queries.
- Produces: `HomeSummaryResponseDTO` with all-time series count, exact last-30-day totals and an empty or enriched `nextEpisodes` list supplied by the assembler.

- [x] **Step 1: Add the DTO fields and compatibility constructors.**

  Add these fields to `HomeSummaryResponseDTO` without removing the existing fields:

  ```java
  long totalSeriesWatched,
  long totalDistinctMoviesWatched,
  long totalMinutesWatchedLast30Days,
  long totalMoviesWatchedLast30Days,
  long totalEpisodesWatchedLast30Days,
  List<SeriesInProgressPreviewDTO> nextEpisodes,
  List<GenreCountDTO> genreCountsEpisodesLast30Days,
  HomeViewerDTO viewer,
  boolean hasUnreadNotifications,
  List<HomeRecentlyWatchedDTO> recentlyWatched,
  List<HomeSocialActivityDTO> socialActivities
  ```

  Keep the record’s list values non-null. `HomeRecentlyWatchedDTO` must contain `watchedDate`, score, display content and `List<String> companionProfilePictures`, but no companion names, comments, likes, `createdAt` or `updatedAt`. `HomeSocialActivityDTO` must contain the author’s `name` and `username` plus only `likesCount` and `commentsCount`. Update every direct constructor call in unit and controller tests in the same task so MapStruct is not involved in this DTO.

- [x] **Step 2: Add repository methods with explicit type and date predicates.**

  Add methods equivalent to:

  ```java
  long countByUserIdAndContentTypeAndWatchedDateBetween(UUID userId, ContentType type, LocalDate start, LocalDate end);
  long sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(UUID userId, ContentType type, LocalDate start, LocalDate end);
  long countDistinctSeriesByUserId(UUID userId);
  long countDistinctMoviesByUserId(UUID userId);
  List<GenreCount> countEpisodeEntriesByGenreAndUserIdForSeriesAndWatchedDateBetween(
          UUID userId, LocalDate start, LocalDate end);
  ```

  Use the existing content rules: movies count diary plays, episodes count diary entries, and distinct series use the series TMDB key from `SERIES` or `EPISODE` rows without double-counting a direct series log and its episodes.

- [x] **Step 3: Make the window exactly 30 inclusive dates.**

  Replace the current `windowEnd.minusDays(30)` calculation with a fixed-clock calculation equivalent to:

  ```java
  LocalDate windowEnd = LocalDate.now(clock);
  LocalDate windowStart = windowEnd.minusDays(29);
  ```

  If `SummaryServiceImpl` does not already receive a `Clock`, inject the application `Clock` bean and use it only for Home date calculations. Do not change Month/Year in Review date semantics.

- [x] **Step 4: Populate the new aggregates without changing genre semantics.**

  Sum movie and episode minutes separately into `totalMinutesWatchedLast30Days`, count each type separately, add `totalDistinctMoviesWatched`, and add `genreCountsEpisodesLast30Days` for the Home. This new list counts `EPISODE` diary entries by the parent series genres; do not reuse or rename the distinct-series metric used by Month/Year/All Time.

- [x] **Step 5: Run the focused unit and repository tests to reach GREEN.**

  Run:

  ```powershell
  .\mvnw.cmd test "-Dtest=SummaryServiceImplTest,DiaryEntryRepositoryTest"
  ```

  Expected: all updated Home and repository tests pass, including the date boundary and distinct-series cases. Repository tests require Docker/Testcontainers.

### Task 3: Enrich `nextEpisodes` without read-time writes

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/HomeNextEpisodeAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/ContentDetailsService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/repository/ContentRepository.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/HomeNextEpisodeAssemblerTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentDetailsServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java`

**Interfaces:**
- Consumes: the existing in-progress query, `TmdbClient` cached episode lookups, content natural-key queries, diary score/state queries and `ContentStatsService` data when a local reference exists.
- Produces: `List<SeriesInProgressPreviewDTO>` with at most four enriched items and no database writes.

- [x] **Step 1: Write assembler RED tests for normal and boundary coordinates.**

  Cover:

  - last watched `S2 E4` resolves to `S2 E5`;
  - the last episode of a season advances to the first episode of the next released season;
  - a series with no resolvable next episode is omitted rather than producing an invented coordinate;
  - the result is capped at four and preserves `LAST_WATCHED DESC` order;
  - an existing local episode reference may supply `contentId` and community score, but never a user score in this future-only card;
  - a missing local reference leaves those fields null and does not call `save`, `saveAndFlush` or `POST /contents/reference` logic;
  - TMDB-unavailable behavior matches the approved contract: the affected item is omitted and the rest of the Home response remains `200`. Do not silently fabricate title or image data.

- [x] **Step 2: Define the focused preview contract.**

  Use a small internal method that accepts `seriesTmdbId`, `seasonNumber`, `episodeNumber` and the viewer/user ID. It must return the episode title, series title, still/poster path, runtime, release date and the parent series progress percentage from TMDB/local progress data, plus nullable local identifiers and community score. It must not return a user score or rating action. It must reuse the existing Caffeine-backed `TmdbClient`; it must not create or mutate a `Content` row.

- [x] **Step 3: Implement natural-key lookup and local state resolution.**

  Resolve an existing episode row through `ContentRepository` using the composite key. Query the viewer’s diary state by that key. Resolve community statistics only when a local `contentId` exists; otherwise return a null community score and let the client hide that badge.

- [x] **Step 4: Implement the assembler and wire it into `getHomeSummary`.**

  Read the four most recent in-progress rows, resolve the next episode using TMDB schedule information when the current season boundary is reached, map the DTO and return the list. Keep this logic in the assembler rather than growing `SummaryServiceImpl` into a second TMDB client.

- [x] **Step 5: Run assembler and summary tests.**

  Run:

  ```powershell
  .\mvnw.cmd test "-Dtest=HomeNextEpisodeAssemblerTest,SummaryServiceImplTest,ContentDetailsServiceImplTest"
  ```

  Expected: all `nextEpisodes` mapping, cache/error and no-write tests pass.

### Task 4: Align Calendar and Home display semantics

**Files:**
- Test: `src/test/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarEventAssemblerTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/calendar/controller/CalendarControllerIntegrationTest.java`
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/telas.md`
- Modify: `docs/context/telas-reqs.md`
- Modify: `docs/context/business-rules.md`

**Interfaces:**
- Consumes: `CalendarResponseDTO`, `CalendarEventDTO`, `CalendarEventContentDTO`, `releaseStatus`, `watchStatus` and `sources`.
- Produces: a documented client mapping for the five-day Home preview without adding unsupported provider data.

- [x] **Step 1: Add contract tests for the fields the Home actually uses.**

  Assert that movie, episode, season and series events expose the fields needed for the Home card: date, title, season/episode or episode-count data, poster/still, `releaseStatus`, `watchStatus` and `sources`, including an event with both `WATCHLIST` and `IN_PROGRESS` sources.

- [x] **Step 2: Preserve the calendar endpoint as the source of the preview.**

  Do not add a new Home-specific calendar query. The client requests the current month and, when the five-day window crosses a month boundary, the next month; it filters events from today through today plus four days and renders the returned statuses.

- [x] **Step 3: Remove the unsupported provider assumption from the client contract.**

  Document that the badge shown in the Home preview must be derived from `sources`, with labels `Watchlist` and `In progress`. If both sources are present, render both or a combined label. Do not map `WATCHLIST` to a streaming service.

  Keep `releaseStatus` and `watchStatus` available to the server-side assembler so the Home can style or disable an event correctly: `releaseStatus` distinguishes `RELEASED` from `UPCOMING`, while `watchStatus` distinguishes `WATCHED`, `UNWATCHED` and `PARTIALLY_WATCHED`. `region` remains useful to resolve the correct local release schedule, but is not returned in the compact Home projection because it is not displayed.

- [x] **Step 4: Correct stale docs.**

  Mark Calendar as implemented in `telas.md`, remove the old statement that the backend only exposes raw lists, and update `telas-reqs.md` to use the current calendar response. Keep the separate limitation that Content metadata is not persisted.

- [x] **Step 5: Run calendar tests.**

  Run:

  ```powershell
  .\mvnw.cmd test "-Dtest=CalendarEventAssemblerTest,CalendarControllerIntegrationTest"
  ```

### Task 5: Align Feed, notifications and recent-content hydration

**Files:**
- Test: `src/test/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/feed/controller/FeedControllerIntegrationTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/notification/controller/NotificationControllerIntegrationTest.java`
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/telas.md`
- Modify: `docs/context/telas-reqs.md`

**Interfaces:**
- Consumes: the existing feed aggregation, `FeedItemDTO` internals, `NotificationRepository` unread-existence query and content preview data needed by the compact Home cards.
- Produces: an integration checklist and contract coverage for the existing Social section; no duplicate feed endpoint.

- [x] **Step 1: Keep the Home Social section on the existing feed route.**

  Reuse the feed aggregation with an effective limit of exactly three for the Home projection. Render a compact activity row for each returned event, showing actor, event type, target preview, timestamp, `likesCount` and `commentsCount`. Do not expose comment text, individual likes, `recentComments` or `watchedWith`. The Home summary does not own pagination, comments or likes; those remain on the Social screen. Every event type returned by the feed must still have a compact fallback representation instead of being silently discarded.

- [x] **Step 2: Keep event-specific interaction data out of the Home response.**

  Use `eventType` only to choose the compact label and target preview. Do not copy interaction routes, comment bodies or spoiler previews into `HomeSocialActivityDTO`; the full feed remains responsible for those details.

- [x] **Step 3: Hydrate only the content fields needed by the compact row.**

  Collect local content IDs from diary, Top 5 and Pick previews, resolve only title/poster/episode-label fields needed by the Home row, and prefer `customPosterUrl` when supplied. Never call TMDB directly from the browser.

- [x] **Step 4: Return only the unread-notification boolean.**

  Query `NotificationRepository` with an indexed existence check and map the result to `hasUnreadNotifications`. Do not load or serialize `NotificationResponseDTO` objects in the Home response.

- [x] **Step 5: Document Top 5 semantics.**

  State in the UI and docs that `FeedItem.top5` is the author’s current ordered Top 5 at read time, not a historical snapshot. Do not create a snapshot entity in this Home alignment.

### Task 6: Update the Home client contract and documentation

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/telas.md`
- Modify: `docs/context/telas-reqs.md`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md` after implementation only
- Reference: `D:/Users/Lucas C/Downloads/Home-DS.dc.html`

**Interfaces:**
- Consumes: the final JSON contracts from Tasks 2–5.
- Produces: a field-by-field implementation checklist for the frontend and synchronized project documentation.

> The field mapping is complete in the backend documentation. Rendering these mappings requires the external frontend project; this repository has no executable client application.

- [x] **Step 1: Replace static Home values with API mappings.**

  Map the design as follows:

  | Design block | Contract source |
  |---|---|
  | profile picture, name and username | `summary/home.viewer` |
  | notification dot | `summary/home.hasUnreadNotifications` |
  | all-time cards | `summary/home` all-time fields plus `totalSeriesWatched` |
  | Continue watching | `summary/home.nextEpisodes` enriched payload |
  | Upcoming | `/users/me/calendar` filtered to the five-day window |
  | Last 30 days chart | `watchCountByDayLast30Days`, filling absent dates with zero |
  | Last 30 days totals | the three new `...Last30Days` fields |
  | genre cards | movie counts and episode counts from `genreCountsEpisodesLast30Days` |
  | Recently watched | compact `recentlyWatched` projection |
  | Social summary | compact `socialActivities` projection with three items |

- [ ] **Step 2: Correct the HTML assumptions.**

  Remove static `CONTINUE` data, static numbers, the `Rate` action and hardcoded calendar providers. Render four cards from `summary/home.nextEpisodes`, including the series progress percentage and only the `Mark as watched` action. Render `summary/home.viewer` with name, username and avatar; use `hasUnreadNotifications` for the dot; use `sources` for the calendar badge; support `MOVIE`, `EPISODE`, `SEASON` and `SERIES` calendar events; display episode-based genre counts from `genreCountsEpisodesLast30Days`; show companion profile photos in Recently watched; and render only three compact Social activity rows with author name, username, like count and comment count.

- [x] **Step 3: Update documentation only after behavior is implemented.**

  Add a chronological `progress.md` entry describing shipped behavior. Update `business-rules.md` with only rules that landed in code; do not record this plan or unimplemented alternatives.

### Task 7: Full verification and handoff

**Files:**
- Test: all files modified by Tasks 1–6
- Review: `docs/context/openapi.yaml`, `docs/context/database-schema.md`, `docs/context/business-rules.md`, `docs/context/telas.md`, `docs/context/telas-reqs.md`, `docs/context/progress.md`

**Interfaces:**
- Consumes: all implementation tasks and the external Home design reference.
- Produces: a verified backend contract ready for frontend implementation.

- [x] **Step 1: Run focused tests.**

  ```powershell
  .\mvnw.cmd test "-Dtest=UserServiceImplTest,UserControllerIntegrationTest,UserRepositoryTest,HomeSummaryProjectionTest,SummaryServiceImplTest,SummaryControllerTest,SummaryControllerIntegrationTest,DiaryEntryRepositoryTest,HomeNextEpisodeAssemblerTest,ContentDetailsServiceImplTest,CalendarEventAssemblerTest,CalendarControllerIntegrationTest,FeedServiceImplTest,FeedControllerIntegrationTest,NotificationControllerIntegrationTest"
  ```

- [x] **Step 2: Run the full test suite.**

  ```powershell
  .\mvnw.cmd test
  ```

  Docker must be running because repository and integration tests use Testcontainers.

- [x] **Step 3: Verify the API contract and error behavior.**

  Confirm that:

  - authenticated self-view returns the new Home fields;
  - private-profile visibility still returns `403` for another viewer;
  - missing target user returns `404`;
  - invalid UUID, unreadable JSON and TMDB-unavailable paths still use the project `ApiError` shape;
  - no Home GET path inserts into `contents` or `diary_entries`;
  - the calendar still returns events with source/status fields;
  - feed cursors and event-specific social targets remain unchanged.

- [x] **Step 4: Review the diff and working tree.**

  Use `git diff --check`, inspect only the intended Java/tests/docs changes, and preserve unrelated existing changes in `.gitignore`, `.m2*`, `.obsidian` and `.testcontainers.properties`.

- [x] **Step 5: Update progress and prepare a Conventional Commit.**

  If implementation is approved and complete, use a short message such as:

  ```text
  feat(home): align summary and activity contracts
  ```

  Do not commit documentation-only changes separately unless the user explicitly requests it. Do not push without explicit permission.

## Self-review checklist

- [x] The plan covers every gap identified from the HTML: persistent display `name`, distinct movie count, four enriched `nextEpisodes` with series progress percentage and no user rating, five-day calendar preview, calendar provider mismatch, episode-based Home genre counts, 30-day boundary, compact recently watched data with companion avatars, three-item Social summary with author name plus username, boolean notification state and minimal viewer data.
- [x] No step requires the frontend to call TMDB directly or to create Content references during a read.
- [x] No new database table is required; the existing Content reference remains lightweight.
- [x] The plan keeps distinct-series metrics for Month/Year/All Time and introduces a separate episode-based Home genre metric.
- [x] Tests cover name migration/backfill, required name validation, privacy, authentication, date boundaries, no-write behavior, TMDB failure, compact projections and cursor-based feed behavior.
- [x] Documentation updates are tied to shipped behavior and remain worktree-only by default.
