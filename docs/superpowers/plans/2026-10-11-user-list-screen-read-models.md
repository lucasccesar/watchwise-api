# User List Screen Read Models Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adicionar os read models, filtros, ordenações e o fluxo de seleção de conteúdo necessários para montar My Lists, Community Lists e Create List sem chamadas individuais de detalhes.

**Architecture:** Manter os endpoints de domínio existentes e adicionar três endpoints /view para as telas. A listagem visual será paginada no banco, enriquecerá o dono e os previews em lote e reutilizará ContentCardAssembler com BASIC_METADATA. A seleção de temporadas e episódios será extraída dos jogos para serviços de conteúdo compartilhados, mantendo as regras específicas dos jogos em seus próprios adaptadores.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL, Flyway, MapStruct, JUnit 5, Mockito, MockMvc, Testcontainers e Maven Wrapper.

## Global Constraints

- O tipo de Create List é filtro de busca; não adicionar coluna nem propriedade persistida para ele.
- Não usar ContentDetailsDTO para previews ou listagens.
- Usar ContentCardDTO e ContentCardAssembler para cards visuais.
- Os serviços de listagem retornam Page<T>; os controllers usam PageResponseDTO.of(page).
- Aplicar filtros e ordenação antes da paginação.
- A fonte ALL deve unir listas próprias e curtidas sem duplicatas.
- O modo DISCOVER retorna somente listas PUBLIC.
- Criar UserListViewItemType com MOVIE, SERIES, SEASON, EPISODE e LIST; não adicionar LIST a ContentType.
- Community Lists ordena por name, likesCount, itemsCount ou commentsCount.
- rank e updatedAt são aceitos somente para a fonte OWNED.
- LIKED preserva a semântica atual de auto-visão do endpoint de listas curtidas.
- A seleção de episódios para listas pode incluir episódios futuros; o jogo continua filtrando episódios lançados.
- seasonNumber aceita zero para Specials; episodeNumber continua positivo.
- GET /users/me/liked-lists permanece como compatibilidade e não muda seu contrato nesta etapa.
- Importação de arquivos não faz parte desta implementação.
- Não alterar regras existentes de visibilidade, CSRF, comentários, likes, aninhamento ou mutações de lista.
- Atualizar docs/context/openapi.yaml, docs/context/business-rules.md, docs/context/progress.md, docs/telas-reqs-definitivo.md e o inventário de rotas do AGENTS.md quando a implementação chegar.
- Documentação permanece sem commit conforme AGENTS.md; commits de código e testes usam Conventional Commits sem Co-Authored-By.
- Usar TDD: teste falhando, implementação mínima, teste focado verde, revisão independente e verificação final.
- Usar .\mvnw.cmd no Windows e manter o checkout original intacto se a execução usar worktree.

## File Map

- Create: src/main/java/com/watchwise/watchwise_api/content/service/TmdbSeriesHierarchyService.java
- Create: src/main/java/com/watchwise/watchwise_api/content/service/impl/TmdbSeriesHierarchyServiceImpl.java
- Create: src/main/java/com/watchwise/watchwise_api/content/service/ContentSelectionService.java
- Create: src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentSelectionServiceImpl.java
- Create: src/main/java/com/watchwise/watchwise_api/content/dto/ContentSeasonSelectionDTO.java
- Create: src/main/java/com/watchwise/watchwise_api/content/dto/ContentEpisodeSelectionDTO.java
- Create: src/main/java/com/watchwise/watchwise_api/content/controller/ContentSelectionController.java
- Create: src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListViewSource.java
- Create: src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListViewItemType.java
- Create: src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListViewQueryMode.java
- Create: src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListViewSort.java
- Create: src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListCardDTO.java
- Create: src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListViewRequest.java
- Create: src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListViewQuery.java
- Create: src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListViewRepository.java
- Create: src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListViewRepositoryImpl.java
- Create: src/main/java/com/watchwise/watchwise_api/userlist/service/UserListViewService.java
- Create: src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListViewAssembler.java — pacote interno, sem interface pública própria.
- Create: src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListViewServiceImpl.java
- Modify: src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListItemRepository.java
- Create: src/main/java/com/watchwise/watchwise_api/common/search/SearchTextNormalizer.java
- Modify: src/main/java/com/watchwise/watchwise_api/search/service/impl/SearchServiceImpl.java
- Modify: src/main/java/com/watchwise/watchwise_api/userlist/controller/UserListController.java
- Create: src/main/resources/db/migration/V69__add-user-list-view-query-indexes.sql
- Test: src/test/java/com/watchwise/watchwise_api/content/service/impl/TmdbSeriesHierarchyServiceImplTest.java.
- Test: src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentSelectionServiceImplTest.java.
- Test: src/test/java/com/watchwise/watchwise_api/content/controller/ContentSelectionControllerTest.java.
- Test: src/test/java/com/watchwise/watchwise_api/content/controller/ContentSelectionControllerIntegrationTest.java.
- Test: src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImplTest.java.
- Test: src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListViewAssemblerTest.java.
- Test: src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListViewServiceImplTest.java.
- Test: src/test/java/com/watchwise/watchwise_api/userlist/repository/UserListViewRepositoryTest.java.
- Test: src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerTest.java.
- Test: src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListViewControllerIntegrationTest.java.
- Test: src/test/java/com/watchwise/watchwise_api/search/service/impl/SearchServiceImplTest.java.
- Modify: docs/context/openapi.yaml, docs/context/business-rules.md, docs/context/progress.md, docs/telas-reqs-definitivo.md and AGENTS.md.

### Task 1: Extract the shared series hierarchy lookup

**Files:**

- Create the two hierarchy services and the two selection DTOs listed in the File Map.
- Modify: src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImpl.java
- Test: TmdbSeriesHierarchyServiceImplTest, ContentSelectionServiceImplTest and DailyGameSearchServiceImplTest.

**Interfaces:**

~~~
List<ContentSeasonSelectionDTO> listSeasons(
        String seriesTmdbId, boolean includeSpecials, String language);

List<ContentEpisodeSelectionDTO> listEpisodes(
        String seriesTmdbId, int seasonNumber, boolean includeUnreleased, String language);

List<ContentSeasonSelectionDTO> listSeasons(
        UUID viewerId, String seriesTmdbId, boolean includeSpecials);

List<ContentEpisodeSelectionDTO> listEpisodes(
        UUID viewerId, String seriesTmdbId, int seasonNumber, boolean includeUnreleased);
~~~

ContentSeasonSelectionDTO contains seriesTmdbId, seasonNumber, name, episodeCount, posterUrl and releaseDate. ContentEpisodeSelectionDTO contains seriesTmdbId, seasonNumber, episodeNumber, name, stillUrl and airDate.

- [ ] **Step 1: Write the failing hierarchy tests**

Cover Specials filtering, future-episode filtering, seasonNumber=0, negative season numbers, zero or negative episode numbers, composite coordinates, TMDB unavailable, not-found responses and complete image URLs built by TmdbImageUrlBuilder. Use a fixed Clock and stub only TmdbClient and UserRepository.

- [ ] **Step 2: Run the focused tests**

~~~powershell
.\mvnw.cmd test "-Dtest=TmdbSeriesHierarchyServiceImplTest,ContentSelectionServiceImplTest"
~~~

Expected: compilation failure because the shared services and DTOs do not exist.

- [ ] **Step 3: Implement the low-level and viewer-aware services**

Move the TMDB normalization currently inside DailyGameSearchServiceImpl into TmdbSeriesHierarchyServiceImpl. Accept seasonNumber=0 for the composite season route; keep seriesTmdbId and episodeNumber positive. ContentSelectionServiceImpl resolves the viewer preferred language, falling back to the existing independent-language constant.

- [ ] **Step 4: Adapt Daily Games**

Make DailyGameSearchServiceImpl call the low-level service with independent language, includeSpecials=false and includeUnreleased=false. Map to the existing game DTOs and preserve all game routes and semantics.

- [ ] **Step 5: Run the green tests and commit**

~~~powershell
.\mvnw.cmd test "-Dtest=TmdbSeriesHierarchyServiceImplTest,ContentSelectionServiceImplTest,DailyGameSearchServiceImplTest"
git add src/main/java/com/watchwise/watchwise_api/content/service/TmdbSeriesHierarchyService.java src/main/java/com/watchwise/watchwise_api/content/service/impl/TmdbSeriesHierarchyServiceImpl.java src/main/java/com/watchwise/watchwise_api/content/service/ContentSelectionService.java src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentSelectionServiceImpl.java src/main/java/com/watchwise/watchwise_api/content/dto/ContentSeasonSelectionDTO.java src/main/java/com/watchwise/watchwise_api/content/dto/ContentEpisodeSelectionDTO.java src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImpl.java src/test/java/com/watchwise/watchwise_api/content/service/impl/TmdbSeriesHierarchyServiceImplTest.java src/test/java/com/watchwise/watchwise_api/content/service/impl/ContentSelectionServiceImplTest.java src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImplTest.java
git commit -m "refactor(content): share series hierarchy lookups"
~~~

Confirm after the commit that no Co-Authored-By trailer or self-attribution exists.

### Task 2: Expose contextual content selection for Create List

**Files:**

- Create: src/main/java/com/watchwise/watchwise_api/content/controller/ContentSelectionController.java
- Test: ContentSelectionControllerTest and ContentSelectionControllerIntegrationTest.

**Interfaces:**

~~~text
GET /contents/selection/series/{seriesTmdbId}/seasons?includeSpecials=false
GET /contents/selection/series/{seriesTmdbId}/seasons/{seasonNumber}/episodes?includeUnreleased=true
~~~

- [ ] **Step 1: Write controller tests**

Test authenticated forwarding, default booleans, invalid identifiers, invalid booleans and seasonNumber=0.

- [ ] **Step 2: Run the red controller test**

~~~powershell
.\mvnw.cmd test "-Dtest=ContentSelectionControllerTest"
~~~

- [ ] **Step 3: Implement the controller**

Use ContentSelectionService, require authentication through existing security, apply a distinct content-selection rate-limit key and never create Content references in this controller.

- [ ] **Step 4: Add integration tests and run them**

Cover season and episode success, future episodes, Specials, 401, 400, 502, ApiError shape and rate limiting.

~~~powershell
.\mvnw.cmd test "-Dtest=ContentSelectionControllerTest,ContentSelectionControllerIntegrationTest"
~~~

- [ ] **Step 5: Commit**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/content/controller/ContentSelectionController.java src/test/java/com/watchwise/watchwise_api/content/controller/ContentSelectionControllerTest.java src/test/java/com/watchwise/watchwise_api/content/controller/ContentSelectionControllerIntegrationTest.java
git commit -m "feat(content): add contextual selection endpoints"
~~~

Confirm after the commit that no Co-Authored-By trailer or self-attribution exists.

### Task 3: Define the list visual read model and assembler

**Files:**

- Create: UserListViewSource, UserListViewItemType, UserListViewQueryMode, UserListViewSort and UserListCardDTO in the userlist DTO package.
- Create: package-private UserListViewAssembler in the userlist service implementation package.
- Test: UserListViewAssemblerTest.

**Interfaces:**

UserListViewItemType is MOVIE, SERIES, SEASON, EPISODE or LIST. UserListViewQueryMode is MY, USER or DISCOVER. UserListViewSort is NAME, LIKES_COUNT, ITEMS_COUNT, COMMENTS_COUNT, UPDATED_AT or RANK. Implement UserListViewSort.fromApiValue(String) with explicit mapping from name, likesCount, itemsCount, commentsCount, updatedAt and rank; do not bind camelCase query values directly to enum constant names.

~~~java
record UserListCardDTO(
    UUID id,
    UserPreviewDTO user,
    String name,
    String description,
    UserListVisibility visibility,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    List<ContentCardDTO> previewCards,
    List<UserListPreviewDTO> nestedListPreviews,
    long nestedListsCount,
    Integer likesCount,
    Boolean likedByMe,
    long itemsCount,
    long commentsCount,
    Integer rank,
    UserListItemScope itemScope,
    Boolean ownedByMe
)
~~~

UserListViewAssembler is internal to the module. Its interface accepts one page of UserList entities plus batch-loaded owner, preview, metric and liked-state maps, then returns cards in the page order. No controller or repository calls it directly.

- [ ] **Step 1: Write DTO and assembler tests**

Assert immutable collections, null-to-empty previews, exact JSON names, owner mapping, five-card cap, five nested-preview cap, original position order, omitted invisible children, deduplicated ContentCardSpec coordinates and BASIC_METADATA-only assembly. Keep the list page capped at ten lists. A full page can contain at most fifty content-card positions (ten lists times five cards); repeated coordinates are sent to ContentCardAssembler only once, with one assembler invocation.

- [ ] **Step 2: Run the red tests**

~~~powershell
.\mvnw.cmd test "-Dtest=UserListViewAssemblerTest"
~~~

Expected: compilation failure because the visual DTOs and assembler do not exist.

- [ ] **Step 3: Implement the DTOs and assembler**

Use ContentCardAssembler once per page for deduplicated basic metadata. Do not load watchedPercentage or totalRuntimeMinutes for overview cards. Map UserPreviewDTO, UserListPreviewDTO, likedByMe and ownedByMe from server-side values.

- [ ] **Step 4: Run the green tests and commit**

~~~powershell
.\mvnw.cmd test "-Dtest=UserListViewAssemblerTest"
git add src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListViewSource.java src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListViewItemType.java src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListViewQueryMode.java src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListViewSort.java src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListCardDTO.java src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListViewAssembler.java src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListViewAssemblerTest.java
git commit -m "feat(userlist): add visual list read model"
~~~

Confirm after the commit that no Co-Authored-By trailer or self-attribution exists.

### Task 4: Implement the paginated database query

**Files:**

- Create: UserListViewRequest and UserListViewQuery in the userlist DTO package.
- Create: UserListViewRepository and UserListViewRepositoryImpl in the userlist repository package.
- Create: UserListViewService and UserListViewServiceImpl in the userlist service packages.
- Modify: UserListItemRepository.
- Create: common/search/SearchTextNormalizer.java.
- Modify: SearchServiceImpl and SearchServiceImplTest.
- Create: V69__add-user-list-view-query-indexes.sql.
- Test: UserListViewRepositoryTest and UserListViewServiceImplTest.

**Interfaces:**

~~~java
record UserListViewQuery(
    UUID viewerId,
    UUID ownerId,
    UserListViewQueryMode mode,
    UserListViewSource source,
    String query,
    UserListViewItemType itemType,
    String genre,
    UserListViewSort sortBy,
    Sort.Direction direction,
    Pageable pageable
)

record UserListViewRequest(
    UUID viewerId,
    UUID ownerId,
    UserListViewQueryMode mode,
    UserListViewSource source,
    String query,
    UserListViewItemType itemType,
    String genre,
    String sortBy,
    String sortDirection,
    Integer page,
    Integer size
)

Page<UserList> findPage(UserListViewQuery query);

Page<UserListCardDTO> getView(UserListViewRequest request);
~~~

- [ ] **Step 1: Write source, visibility and service-validation tests**

Cover MY + OWNED, MY + LIKED, MY + ALL deduplication, USER profile/list visibility, DISCOVER public-only behavior and invalid mode/owner/source combinations in UserListViewRepositoryTest. In UserListViewServiceImplTest, cover the same request validation, source defaulting and PageRequestFactory translation before calling the repository.

- [ ] **Step 2: Write filter and order tests**

Cover MOVIE, SERIES, SEASON, EPISODE, LIST, case-insensitive direct genre, pre-pagination filtering, all four Community sorts, deterministic UUID tie-breaking, OWNED rank ordering, LIKED Like.createdAt and Like.id ordering, default ordering for ALL/DISCOVER, null rank placement and rejection of UPDATED_AT/RANK outside OWNED.

- [ ] **Step 3: Run the red repository and service tests**

~~~powershell
.\mvnw.cmd test "-Dtest=UserListViewRepositoryTest,UserListViewServiceImplTest"
~~~

Expected: compilation failure because the request, custom repository and view service do not exist.

- [ ] **Step 4: Implement the query safely**

Use parameterized SQL or Criteria API. Use EXISTS for item and genre filters and a matching count query. Map UserListViewSort to a fixed whitelist; never concatenate raw request parameters. Use the explicit camelCase conversion before the query. Validate sortDirection before calling PageRequestFactory even when sortBy is null, because PageRequestFactory intentionally skips that validation when no sort field is supplied. Use UserListViewQueryMode to enforce MY, USER and DISCOVER semantics before pagination.

Use a distinct candidate set for MY + ALL. For MY + LIKED, order the default query by Like.createdAt DESC and Like.id DESC, preserving the legacy liked-list contract. For USER, reuse the accepted-follow/profile barrier. For DISCOVER, force PUBLIC and reject owner/source. An omitted or whitespace-only q must not add a name predicate.

- [ ] **Step 5: Implement nested previews in UserListItemRepository**

Add a UserListItemRepository projection query over user_list_items and child user_lists ordered by parent position. The projection contains parent list ID, child list ID, child owner fields, child name and child visibility. UserListViewServiceImpl applies the existing visibility rule before mapping to UserListPreviewDTO and caps five visible children per parent. Do not expose a private child's name, owner or visibility.

- [ ] **Step 6: Extract search normalization**

Create SearchTextNormalizer with optional-query handling, trim, maximum length 100 and escaping for backslash, percent and underscore. Omitted or whitespace-only q produces no predicate in list discovery; the existing global SearchService validation remains unchanged. Replace only the private escaping logic in SearchServiceImpl and preserve its current contract with regression tests.

- [ ] **Step 7: Add only missing indexes**

Create V69__add-user-list-view-query-indexes.sql with:

~~~sql
CREATE INDEX idx_user_lists_user_updated_at_id
    ON user_lists (user_id, updated_at DESC, id DESC);

CREATE INDEX idx_user_lists_user_rank_id
    ON user_lists (user_id, rank ASC, id ASC);

CREATE INDEX idx_user_lists_public_discovery_order
    ON user_lists (visibility, likes_count DESC, created_at DESC, id DESC);
~~~

Do not duplicate existing trigram, likes or user-list-item indexes. Run EXPLAIN for the genre predicate before adding a genres index; add one only if the chosen predicate can use it.

- [ ] **Step 8: Implement the single service entry point**

Implement UserListViewService.getView(UserListViewRequest request). It validates mode/source/sort combinations, converts sortBy with UserListViewSort.fromApiValue, validates sortDirection before PageRequestFactory, normalizes q and genre, builds UserListViewQuery, calls findPage once, loads overview-only batches and delegates card construction to UserListViewAssembler. Return page.map or an equivalent PageImpl that preserves the repository page metadata.

- [ ] **Step 9: Run the repository, service and search suites and commit**

~~~powershell
.\mvnw.cmd test "-Dtest=UserListViewRepositoryTest,UserListViewServiceImplTest,SearchServiceImplTest"
git add src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListViewRequest.java src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListViewQuery.java src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListViewRepository.java src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListViewRepositoryImpl.java src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListItemRepository.java src/main/java/com/watchwise/watchwise_api/userlist/service/UserListViewService.java src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListViewServiceImpl.java src/main/java/com/watchwise/watchwise_api/common/search/SearchTextNormalizer.java src/main/java/com/watchwise/watchwise_api/search/service/impl/SearchServiceImpl.java src/main/resources/db/migration/V69__add-user-list-view-query-indexes.sql src/test/java/com/watchwise/watchwise_api/userlist/repository/UserListViewRepositoryTest.java src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListViewServiceImplTest.java src/test/java/com/watchwise/watchwise_api/search/service/impl/SearchServiceImplTest.java
git commit -m "feat(userlist): add filtered list view queries"
~~~

Confirm after the commit that no Co-Authored-By trailer or self-attribution exists.

### Task 5: Expose My Lists and Community Lists visual routes

**Files:**

- Modify: UserListController.java.
- Test: UserListControllerTest.
- Create: UserListViewControllerIntegrationTest.

**Interfaces:**

~~~text
GET /users/me/lists/view
GET /users/{userId}/lists/view
GET /lists/discover/view
~~~

Each route returns ResponseEntity<PageResponseDTO<UserListCardDTO>>.

- [ ] **Step 1: Write controller tests**

Cover construction of UserListViewRequest for My Lists with source=ALL, itemType=SERIES, genre=Drama, sortBy=likesCount and sortDirection=desc; Community q/type/genre/sort/page; owner route target ID; current-user resolution; omitted source; and delegation only through UserListViewService.getView(request).

- [ ] **Step 2: Implement the routes**

Convert the external itemType enum directly, keep sortBy as text for UserListViewService to map and validate, then return PageResponseDTO.of(page). Keep all raw list endpoints unchanged.

The controller sets only request mode and route-derived identity. UserListViewService owns source defaults, mode validation, sort conversion, direction validation and pagination rules. This prevents the three routes from drifting apart.

- [ ] **Step 3: Keep enrichment behind the service seam**

Verify through controller mocks that no repository, item service or ContentCardAssembler dependency is added to UserListController. The controller calls only UserListViewService.getView(request); overview-only enrichment stays in Task 4.

- [ ] **Step 4: Add integration tests**

Cover source=ALL deduplication, every item type and genre before pagination, Community name search with q omitted and whitespace-only, all four Community sorts in both directions, owner/avatar/cards, public-only discovery, nested private-list omission, 401, invalid enum/page/size/source/sortDirection ApiError responses, partial card status on TMDB unavailability and the complete PageResponseDTO envelope.

- [ ] **Step 5: Run the focused HTTP suite and commit**

~~~powershell
.\mvnw.cmd test "-Dtest=UserListControllerTest,UserListViewControllerIntegrationTest"
git add src/main/java/com/watchwise/watchwise_api/userlist/controller/UserListController.java src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerTest.java src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListViewControllerIntegrationTest.java
git commit -m "feat(userlist): expose screen list views"
~~~

Confirm after the commit that no Co-Authored-By trailer or self-attribution exists.

### Task 6: Synchronize API, business rules and screen requirements

**Files:**

- Modify: docs/context/openapi.yaml
- Modify: docs/context/business-rules.md
- Modify: docs/context/progress.md
- Modify: docs/telas-reqs-definitivo.md
- Modify: AGENTS.md

**Interfaces:**

- Consumes: verified behavior from Tasks 1–5.
- Produces: synchronized documentation without changing runtime behavior.

- [ ] **Step 1: Document content selection**

Add both contextual selection routes with query defaults, response DTOs, authentication, validation, season 0 behavior, image URL semantics and TMDB-unavailable errors.

- [ ] **Step 2: Document the visual list routes**

Add the three /view routes with PageResponseDTO, MY/USER/DISCOVER modes, source/itemType values including LIST, q/genre validation, pagination, sort rules and visibility behavior. Document ContentCardDTO previews and the legacy/raw UserListResponseDTO contract.

- [ ] **Step 3: Document business rules**

Add source union/deduplication, mode validation, direct-content filters, rank/updatedAt restrictions, public-only discovery, owner metadata, nested-preview privacy, partial cards and the distinction between game released-episode filtering and list future-episode selection.

- [ ] **Step 4: Update screen requirements**

In docs/telas-reqs-definitivo.md, update My Lists, Community Lists and Create List with the /view endpoints, source filter, server-side type/genre filters, four Community sort options, owner/avatar/cards, contextual season/episode selection and import as future scope.

- [ ] **Step 5: Update route inventory and progress**

Update Lists and Content route groups in AGENTS.md. Append only implemented behavior to docs/context/progress.md.

- [ ] **Step 6: Validate documentation**

~~~powershell
rg -n "lists/view|discover/view|contents/selection|UserListCardDTO|UserListViewItemType|UserListViewQueryMode|itemType|source=OWNED|includeUnreleased" docs/context/openapi.yaml docs/context/business-rules.md docs/telas-reqs-definitivo.md AGENTS.md
git diff --check -- docs/context/openapi.yaml docs/context/business-rules.md docs/context/progress.md docs/telas-reqs-definitivo.md AGENTS.md
~~~

Leave documentation changes uncommitted.

### Task 7: Independent review and final verification

**Files:**

- Verify all Java, migration and test files from Tasks 1–5.
- Verify documentation from Task 6.

- [ ] **Step 1: Run focused regression tests**

~~~powershell
.\mvnw.cmd test "-Dtest=TmdbSeriesHierarchyServiceImplTest,ContentSelectionServiceImplTest,DailyGameSearchServiceImplTest,ContentSelectionControllerTest,ContentSelectionControllerIntegrationTest,UserListViewAssemblerTest,UserListViewServiceImplTest,UserListViewRepositoryTest,SearchServiceImplTest,UserListControllerTest,UserListViewControllerIntegrationTest"
~~~

Expected: zero failures and zero errors. Docker/Testcontainers must be running for repository and integration tests.

- [ ] **Step 2: Run the complete suite**

~~~powershell
.\mvnw.cmd clean test
~~~

Expected: BUILD SUCCESS and zero failed or errored tests.

- [ ] **Step 3: Run the independent review**

Check that no frontend-only pagination or filtering remains, no raw SQL parameter is concatenated, ALL cannot duplicate a list, private/follower-only lists cannot leak through count queries or page boundaries, ContentDetailsDTO is not used by list views, card lookup is deduplicated and batched, game behavior remains released-only, list selection supports future episodes and season 0, owner/avatar privacy matches existing rules, q/genre normalization is tested, all changed routes have OpenAPI entries and HTTP tests, and new controllers use PageResponseDTO.

- [ ] **Step 4: Inspect the final diff**

~~~powershell
git diff --check
git status --short
git diff --stat
~~~

Confirm that only intended code, tests and migration files are committed and that documentation remains worktree-only.

- [ ] **Step 5: Request review before integration**

Use the repository's independent review workflow. Do not push. If a code commit is created after review, inspect its message and explicitly confirm that it contains no Co-Authored-By trailer or self-attribution.

