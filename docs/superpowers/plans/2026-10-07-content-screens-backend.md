# Content Detail Screens Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entregar uma leitura específica para montar Movie, Series, Season e Episode sem deixar mais pesados os endpoints genéricos usados por perfil, feed, listas e histórico.

**Architecture:** Preservar `GET /contents/{contentId}/details`, `GET /contents/{contentId}/stats`, reviews e comments como contratos genéricos. Criar `GET /contents/{contentId}/page` como um BFF (backend-for-frontend, uma resposta agregada para uma tela) que combina metadata adicional, estatísticas da página, estado do viewer e cards filhos. O BFF nunca cria `Content` durante uma leitura; filhos ainda não referenciados são identificados por coordenadas TMDB.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL, Flyway, MapStruct, Lombok, Caffeine/TMDB proxy, Mockito, MockMvc e Testcontainers.

## Global Constraints

- Não armazenar título, poster, backdrop, elenco ou registro completo do TMDB na tabela `contents`.
- Não alterar `ContentDetailsDTO` nem `ContentStatsResponseDTO` para adicionar dados exclusivos da tela; consumidores existentes não devem pagar consultas extras.
- Não criar referências `Content` como efeito colateral de `GET /contents/{contentId}/page`.
- Estatísticas públicas continuam usando `isProfilePublic = true`; estado pessoal só pode ser resolvido para o usuário autenticado.
- Reviews continuam sendo `DiaryEntry` ou `DroppedEntry`; não criar uma entidade `Review`.
- `containsSpoiler` continua sendo regra de comentários neste plano. Spoiler em review fica fora do caminho crítico e exige um plano de domínio separado.
- A nota mostrada na tela será a nota Watchwise. Nota externa deve ser identificada separadamente como TMDB/IMDb se for adicionada no futuro.
- Nenhuma chamada de TMDB deve ser feita uma vez por filho; filhos usam as respostas já carregadas pelo `ContentDetailsService` e consultas locais em lote.
- Atualizar `docs/context/openapi.yaml`, `docs/context/business-rules.md` e `docs/context/progress.md` na implementação. Atualizar `database-schema.md` somente se uma mudança persistente for aprovada fora deste plano.
- Usar Conventional Commits, sem `Co-Authored-By`; nunca executar `git push` sem autorização explícita.

## Optimized API Shape

Adicionar somente:

```text
GET /contents/{contentId}/page
```

```java
public record ContentPageDTO(
        ContentDetailsDTO details,
        ContentPageMetadataDTO metadata,
        ContentPageStatsDTO stats,
        long visibleReviewsCount,
        ContentViewerStateDTO viewerState,
        ContentNavigationDTO navigation,
        ContentPageSectionsDTO sections) {
}
```

O BFF não retorna o corpo de reviews ou comentários. Essas coleções continuam paginadas em suas rotas existentes.

```java
public record ContentPageMetadataDTO(
        String originalLanguage,
        String certification,
        String homepageUrl,
        String tmdbUrl,
        String imdbUrl,
        List<ContentPageWatchProviderDTO> watchProviders,
        String presentationPosterPath,
        List<CrewMemberDTO> presentationCrew,
        Boolean crewInherited) {
}

public record ContentPageWatchProviderDTO(
        Integer providerId,
        String providerName,
        String logoPath,
        String type,
        String watchUrl) {
}

public record ContentPageStatsDTO(
        UUID contentId,
        Double averageScore,
        long ratingsCount,
        List<RatingDistributionDTO> ratingsDistribution,
        long playsCount,
        long commentsCount) {
}

public record RatingDistributionDTO(Integer score, long count) {
}
```

`ratingsCount` e `ratingsDistribution` usam os mesmos DiaryEntry públicos que alimentam o `averageScore` atual. `GET /contents/{id}/stats` permanece sem distribuição e sem nova consulta.

```java
public record ContentViewerStateDTO(
        WatchStatus watchStatus,
        Integer myRating,
        String myReview,
        LocalDate lastWatchedDate,
        Integer lastWatchNumber,
        Boolean watchedInTheater,
        Integer playsCount,
        UUID latestDiaryEntryId,
        Boolean inWatchlist,
        UUID watchlistEntryId,
        Boolean dropped,
        UUID droppedEntryId,
        List<ContentListMembershipDTO> lists,
        Integer watchedEpisodeCount,
        Integer releasedEpisodeCount) {
}

public record ContentListMembershipDTO(
        UUID listId,
        String name,
        UserListVisibility visibility) {
}
```

```java
public record ContentChildCardDTO(
        UUID contentId,
        ContentType type,
        String tmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        String title,
        String posterPath,
        LocalDate releaseDate,
        Integer runtimeMinutes,
        ContentStatsResponseDTO stats,
        ContentViewerStateDTO viewerState) {
}

public record ContentPageSectionsDTO(
        List<ContentChildCardDTO> seasons,
        List<ContentChildCardDTO> episodes,
        List<ContentChildCardDTO> recentEpisodes) {
}

public record ContentNavigationDTO(
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        Integer seasonEpisodeCount,
        ContentChildCardDTO previousEpisode,
        ContentChildCardDTO nextEpisode) {
}
```

`contentId` pode ser `null` para um filho ainda não referenciado. A coordenada TMDB permanece preenchida para que o frontend chame `POST /contents/reference` somente quando o usuário executar uma ação. `wikipediaUrl` não entra no contrato porque o backend atual não possui fonte confiável para resolvê-lo.

Quando uma página SEASON/EPISODE não possui a referência parent persistida, o backend não cria essa linha durante a leitura; nesse caso, `sections.seasons` ou `navigation` podem ficar vazios e o frontend deve tratar a ausência como estado incompleto, não como erro. Para EPISODE, `crewInherited=true` identifica que a equipe exibida pertence à série pai e não ao episódio.

## File Map

- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentPageDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentPageMetadataDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentPageWatchProviderDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentPageStatsDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/RatingDistributionDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentViewerStateDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentListMembershipDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentChildCardDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentPageSectionsDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentNavigationDTO.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/ContentCoordinate.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/ContentPageService.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/ContentPageMetadataService.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/ContentPageStatsService.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/ContentViewerStateService.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentPageServiceImpl.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentPageMetadataServiceImpl.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentPageStatsServiceImpl.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentViewerStateServiceImpl.java`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/ContentChildCardAssembler.java` — montagem local de cards filhos.
- Modify: `src/main/java/com/watchwise/watchwise_api/content/controller/ContentController.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbMovieFullDetails.java` e `TmdbTvFullDetails.java` — homepage necessária à metadata da página.
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbProvider.java` e `TmdbRegionProviders.java` — provider id/link.
- Modify: `src/main/java/com/watchwise/watchwise_api/content/repository/ContentRepository.java` — lookups batch de referências existentes.
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java` — stats, estado pessoal e review count/sort.
- Modify: `src/main/java/com/watchwise/watchwise_api/watchlist/repository/WatchlistEntryRepository.java` — lookup pessoal em lote.
- Modify: `src/main/java/com/watchwise/watchwise_api/dropped/repository/DroppedEntryRepository.java` — lookup pessoal em lote.
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListItemRepository.java` — memberships em lote.
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/dto/ContentReviewResponseDTO.java` — autor da review.
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryController.java` — sort de reviews.
- Test: `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentPageServiceImplTest.java`.
- Test: `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentPageMetadataServiceImplTest.java`.
- Test: `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentViewerStateServiceImplTest.java`.
- Test: `src/test/java/com/watchwise/watchwise_api/content/controller/ContentControllerIntegrationTest.java`.
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImplTest.java`.

---

### Task 1: Freeze the page contract without changing existing DTOs

**Files:**
- Create: the page DTOs in `File Map`.
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/ContentPageService.java`.
- Modify: `docs/context/openapi.yaml` — schemas only; the route is added with Task 4 when the bean exists.
- Test: `src/test/java/com/watchwise/watchwise_api/content/dto/ContentPageDTOTest.java`.

**Interfaces:**
- Consumes: existing `ContentDetailsDTO`, `ContentStatsResponseDTO`, `ContentType`, `WatchStatus` and `UserListVisibility`.
- Produces: `ContentPageDTO ContentPageService.getPage(UUID contentId, UUID viewerId)`.

- [ ] **Step 1: Add DTO serialization tests**

  Assert exact JSON names, empty collections instead of null, nullable child `contentId`, and that existing `ContentDetailsDTO` and `ContentStatsResponseDTO` shapes remain unchanged.

- [ ] **Step 2: Add the service interface without wiring the controller yet**

  Add `ContentPageService.getPage(UUID contentId, UUID viewerId)`. Do not add a controller dependency until `ContentPageServiceImpl` exists in Task 4; this keeps the application context and all existing tests green between tasks.

- [ ] **Step 3: Document the schemas without publishing the route yet**

  Add the new schemas to `openapi.yaml`. Add the `/contents/{contentId}/page` path in Task 4 alongside the controller implementation. Document that the endpoint does not create child references and leaves reviews/comments as separate paginated calls.

- [ ] **Step 4: Run the contract tests**

  ```powershell
  .\mvnw.cmd test "-Dtest=ContentPageDTOTest,ContentControllerTest"
  ```

---

### Task 2: Add page-only metadata and root statistics

**Files:**
- Create: `ContentPageMetadataService`, `ContentPageMetadataServiceImpl`, `ContentPageStatsService`, `ContentPageStatsServiceImpl`, `ContentPageStatsDTO`, `RatingDistributionDTO` and their tests.
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbMovieFullDetails.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbTvFullDetails.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbProvider.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbRegionProviders.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java`.
- Test: `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentPageMetadataServiceImplTest.java`.
- Test: `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentPageStatsServiceImplTest.java`.

**Interfaces:**
- Consumes: existing cached full-detail calls, `getMovieReleaseDates`, `getTvContentRatings`, preferred language/region, and public DiaryEntry aggregates.
- Produces: page-only certification/language/links/providers and root `ratingsCount`/distribution.

- [ ] **Step 1: Test metadata mapping for all four types**

  Cover movie certification, TV certification, inherited season/episode certification, original language, homepage, IMDb/TMDB URLs, missing optional fields and provider link/id. Wikipedia must remain absent rather than fabricated.

- [ ] **Step 2: Implement metadata without changing `ContentDetailsDTO`**

  Reuse existing TMDB caches. Add only `homepage`, provider id and provider link to internal TMDB records. Select the viewer's preferred region and return nullable fields when TMDB lacks data.

- [ ] **Step 3: Test and implement page-only rating distribution**

  Add one grouped repository query for public DiaryEntry rows with non-null scores. Build buckets 1–10 in memory. Do not modify `ContentStatsResponseDTO` or the existing `/contents/stats` route.

- [ ] **Step 4: Verify no regression**

  ```powershell
  .\mvnw.cmd test "-Dtest=ContentPageMetadataServiceImplTest,ContentPageStatsServiceImplTest,ContentDetailsServiceImplTest,ContentStatsServiceImplTest,TmdbClientCachingTest"
  ```

- [ ] **Step 5: Commit**

  ```powershell
  git add src/main/java/com/watchwise/watchwise_api/common/tmdb src/main/java/com/watchwise/watchwise_api/content src/main/java/com/watchwise/watchwise_api/diaryentry/repository src/test/java/com/watchwise/watchwise_api/content
  git commit -m "feat(content): add page metadata and stats"
  ```

  Confirm that the commit has no `Co-Authored-By` trailer or other self-attribution.

---

### Task 3: Build the viewer-state read model

**Files:**
- Create: `ContentViewerStateService`, `ContentViewerStateServiceImpl`, `ContentCoordinate` and tests.
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/watchlist/repository/WatchlistEntryRepository.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/dropped/repository/DroppedEntryRepository.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListItemRepository.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/content/service/ContentStateResolver.java` — expor a resolução de progresso reutilizável.

**Interfaces:**
- Consumes: viewer id, existing content references, DiaryEntry, watchlist, dropped, list memberships, schedules and watched episode coordinates.
- Produces: root `ContentViewerStateDTO` and coordinate-based batch state lookup for Task 4.

- [ ] **Step 1: Lock viewer-state semantics in tests**

  Select the latest DiaryEntry by `watchNumber DESC`, `createdAt DESC`, `id DESC`; count all viewer diary rows as `playsCount`; mark movie/episode watched when at least one row exists; reuse `ContentStateResolver` for series/season. Watchlist, dropped and list memberships are viewer-owned only.

- [ ] **Step 2: Add one batch query per source**

  Query diary state, watchlist rows, dropped rows and list memberships in batches. An unreferenced coordinate returns zero/empty state without inserting a row.

- [ ] **Step 3: Implement the state service**

  Expose `resolve(UUID viewerId, Collection<ContentCoordinate> coordinates, Map<ContentCoordinate, ContentSchedule> schedulesByCoordinate)`. The caller must provide schedules for SERIES/SEASON aggregate progress; keep all state viewer-scoped and do not hide an incomplete schedule behind a convenience method.

- [ ] **Step 4: Verify performance and privacy**

  Test no-write reads, partial progress, rewatches, private viewer isolation, list membership and batch query counts.

- [ ] **Step 5: Run focused tests and commit**

  ```powershell
  .\mvnw.cmd test "-Dtest=ContentViewerStateServiceImplTest,DiaryEntryRepositoryTest,WatchlistEntryRepositoryTest,DroppedEntryRepositoryTest,UserListItemRepositoryTest"
  git add src/main/java/com/watchwise/watchwise_api/content src/main/java/com/watchwise/watchwise_api/diaryentry/repository src/main/java/com/watchwise/watchwise_api/watchlist/repository src/main/java/com/watchwise/watchwise_api/dropped/repository src/main/java/com/watchwise/watchwise_api/userlist/repository src/test/java/com/watchwise/watchwise_api/content
  git commit -m "feat(content): add viewer state"
  ```

  Confirm that the commit has no `Co-Authored-By` trailer or other self-attribution.

---

### Task 4: Add coordinate-based child cards

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/ContentCoordinate.java` if not already created by Task 3.
- Modify: `src/main/java/com/watchwise/watchwise_api/content/repository/ContentRepository.java`.
- Test: `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentChildCardAssemblerTest.java`.
- Test: repository integration tests for natural-key content lookups.

**Interfaces:**
- Consumes: `ContentDetailsDTO` season/episode summaries, `ContentCoordinate`, `ContentStatsResponseDTO`, and `ContentViewerStateService`.
- Produces: `ContentChildCardDTO`, `ContentPageSectionsDTO`, and `ContentNavigationDTO` without TMDB calls or write-on-read behavior.

- [ ] **Step 1: Add natural-key lookups**

  Resolve existing UUIDs by `(tmdbId, type)` or `(seriesTmdbId, seasonNumber, episodeNumber, type)` in one batch. A missing reference remains nullable.

- [ ] **Step 2: Assemble type-specific cards**

  Map Series seasons and three recent episodes, Season episodes and parent seasons, and Episode previous/next coordinates. Reuse the existing batch stats endpoint for referenced children and coordinate-based local queries for unreferenced children.

- [ ] **Step 3: Verify no-write and no-N+1 behavior**

  Test that an unreferenced child has a null UUID, valid coordinates, zero stats and viewer state without creating a `Content` row or making an additional TMDB call per child.

- [ ] **Step 4: Run focused tests and commit**

  ```powershell
  .\mvnw.cmd test "-Dtest=ContentChildCardAssemblerTest,ContentRepositoryTest"
  git add src/main/java/com/watchwise/watchwise_api/content src/test/java/com/watchwise/watchwise_api/content
  git commit -m "feat(content): add coordinate child cards"
  ```

  Confirm that the commit has no `Co-Authored-By` trailer or other self-attribution.

---

### Task 5: Implement the page aggregator

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentPageServiceImpl.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/content/controller/ContentController.java` — wire the route and service bean now that the implementation exists.
- Modify: `docs/context/openapi.yaml` — add the route and responses.
- Test: `src/test/java/com/watchwise/watchwise_api/content/controller/ContentControllerTest.java`.
- Test: `src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentPageServiceImplTest.java`.
- Test: `src/test/java/com/watchwise/watchwise_api/content/controller/ContentControllerIntegrationTest.java`.

**Interfaces:**
- Consumes: `ContentDetailsService`, page metadata/stats services, viewer-state service and child sections from Tasks 3 and 4.
- Produces: complete `ContentPageDTO` for MOVIE, SERIES, SEASON and EPISODE.

- [ ] **Step 1: Implement MOVIE assembly**

  Return existing details, page metadata, root stats, visible review count and viewer state. Leave child lists empty.

- [ ] **Step 2: Implement SERIES assembly**

  Return all regular season cards and exactly the three most recently aired episode cards. Do not fetch additional TMDB data per card.

- [ ] **Step 3: Implement SEASON assembly**

  Return parent season cards, all episode cards in TMDB order, episode stats/state and previous/next navigation.

- [ ] **Step 4: Implement EPISODE assembly**

  Return parent title/year, series/season/episode coordinates, season poster fallback and previous/next navigation. Do not present inherited series crew as episode-specific credits.

- [ ] **Step 5: Add no-write integration tests**

  With Testcontainers, verify that opening a page with no child references does not change the `contents` count, that an existing child id is returned, and that 401, 404, TMDB-unavailable and viewer-poster behavior match existing content routes.

- [ ] **Step 6: Run and commit**

  ```powershell
  .\mvnw.cmd test "-Dtest=ContentPageServiceImplTest,ContentControllerIntegrationTest,ContentControllerTest"
  git add src/main/java/com/watchwise/watchwise_api/content src/test/java/com/watchwise/watchwise_api/content
  git commit -m "feat(content): add content page endpoint"
  ```

  Confirm that the commit has no `Co-Authored-By` trailer or other self-attribution.

---

### Task 6: Add only the social capabilities required by the screen

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/ContentReviewSort.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/dto/ContentReviewResponseDTO.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryController.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/service/DiaryEntryService.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImpl.java`.
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java`.
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImplTest.java`.
- Test: `src/test/java/com/watchwise/watchwise_api/diaryentry/controller/DiaryEntryControllerIntegrationTest.java`.

**Interfaces:**
- Consumes: existing visibility-aware review union query and `UserPreviewDTO`.
- Produces: review author data, stable `POPULAR`/`RECENT`/`FOLLOWING` ordering and a count matching the visible review query.

- [ ] **Step 1: Add reviewer preview without a migration**

  Add `UserPreviewDTO reviewer` to `ContentReviewResponseDTO`. Map the already-loaded author; never require one `/users/{id}` request per review.

- [ ] **Step 2: Add server-side review sorting**

  `POPULAR` orders by likes descending, then creation date and id; `RECENT` orders by creation date and id; `FOLLOWING` includes only accepted followed authors. Preserve the existing visibility predicate.

- [ ] **Step 3: Reuse the same count predicate for the page header**

  `ContentPageDTO.visibleReviewsCount` must count the same visible DIARY/DROPPED review union returned by the endpoint. Keep `ContentStatsResponseDTO.reviewsCount` unchanged for existing consumers.

- [ ] **Step 4: Verify and commit**

  ```powershell
  .\mvnw.cmd test "-Dtest=DiaryEntryServiceImplTest,DiaryEntryControllerIntegrationTest,CommentServiceImplTest"
  git add src/main/java/com/watchwise/watchwise_api/diaryentry src/test/java/com/watchwise/watchwise_api/diaryentry src/test/java/com/watchwise/watchwise_api/comment
  git commit -m "feat(content): enrich content reviews"
  ```

  Confirm that the commit has no `Co-Authored-By` trailer or other self-attribution.

---

### Task 7: Synchronize documentation and verify the contract

**Files:**
- Modify: `docs/context/openapi.yaml`.
- Modify: `docs/context/business-rules.md`.
- Modify: `docs/context/progress.md`.
- Modify: `docs/context/telas-reqs.md`.

- [ ] **Step 1: Document `/contents/{contentId}/page`**

  Document per-type sections, nullable child ids, coordinate fallback, no-write reads, page-only stats/metadata, authentication and errors. Keep generic details/stats schemas unchanged.

- [ ] **Step 2: Document business semantics**

  Record latest viewer entry selection, watchlist/dropped/list membership, series/season partial progress, public page rating distribution, review visibility and `FOLLOWING` semantics. Do not document review spoiler persistence because it is outside this plan.

- [ ] **Step 3: Update progress chronologically**

  Append only shipped behavior under the current date in `docs/context/progress.md`; do not add future-work sections.

- [ ] **Step 4: Run the complete suite and audit**

  ```powershell
  git diff --check
  .\mvnw.cmd test
  git status --short
  ```

  Docker must be running. Verify no page read creates Content rows, no child causes an N+1 TMDB call, no private state leaks, and all new binding failures use `ApiError`.
