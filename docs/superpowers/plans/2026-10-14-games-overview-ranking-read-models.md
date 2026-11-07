# Games Overview and Ranking Visual Read Models Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Expor read models visuais para as telas Games e Games Ranking, incluindo o resumo diário seguro, pontuação correta de 64 pontos e posição detalhada do usuário no ranking.

**Architecture:** Manter as rotas raw de jogos, tentativas, histórico e ranking sem alteração. Adicionar uma rota resumida para os oito cards de Games e uma rota visual de ranking que pagina a tabela, traz o resumo do viewer e calcula as métricas que o design exige em consultas agregadas limitadas. Não criar snapshots diários nem novas tabelas: o ranking permanece calculado a partir de resultados terminais no momento da leitura.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, PostgreSQL native queries, Maven, JUnit 5, Mockito e Testcontainers.

## Global Constraints

- Preservar todos os contratos atuais em GET /games/today, GET /games/{gameType}/today, GET /games/rankings/general e GET /games/{gameType}/ranking.
- Adicionar somente GET /games/today/view e GET /games/rankings/view.
- Games usa dia GMT; nextResetAt deve ser calculado para a próxima meia-noite UTC.
- O Clock configurado já é UTC; usar o mesmo Clock da feature para data atual e para nextResetAt.
- A pontuação máxima do dia é a soma atual de DailyGameType.maxAttempts: 64. Não mudar as regras de score para reproduzir os 68 pontos do protótipo.
- A leitura de overview não pode retornar answer, hints, attempts, filmography, imageUrls completos ou qualquer dado que revele o desafio.
- A leitura visual de ranking deve ser autenticada, paginar antes de montar a resposta e aplicar tamanho máximo de 20.
- page aceita a convenção atual do projeto; a página devolvida no envelope é 1-based, como PageResponseDTO.
- Resultados de ranking consideram somente UserDailyGameResult com status COMPLETED ou FAILED, como as rotas raw atuais.
- solvedPercentage significa resultados COMPLETED dividido por resultados terminais, arredondado ao inteiro mais próximo.
- averageAttempts usa resultados terminais e é arredondado a uma casa decimal no backend.
- daysPlayed conta challengeDate distintos; gamesPlayed conta resultados terminais.
- O top-200 usa rank <= 200. Quando o viewer não está nele, pointsToTop200 é top200CutoffScore - viewer.score + 1.
- Consultas devem ser agregadas e em lote; não buscar um User por linha de ranking.
- Não criar ou atualizar Content, DailyChallenge ou UserDailyGameResult durante nenhuma leitura.
- Atualizar openapi.yaml, progress.md, business-rules.md e telas-reqs-definitivo.md; documentação fica sem commit, salvo autorização explícita.
- Cada commit segue Conventional Commits e não inclui Co-Authored-By nem outra autoatribuição.

---

## File structure

- Create: src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameOverviewCardDTO.java — estado mínimo de um card da tela Games.
- Create: src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGamesOverviewDTO.java — envelope diário com reset e totais.
- Create: src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameRankingViewEntryDTO.java — linha visual do ranking.
- Create: src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameRankingViewerDTO.java — resumo do viewer.
- Create: src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameRankingViewDTO.java — envelope paginado do ranking visual.
- Create: src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameRankingViewService.java — seam do ranking visual.
- Create: src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameRankingViewServiceImpl.java — paginação, viewer e cutoff.
- Modify: src/main/java/com/watchwise/watchwise_api/dailygame/repository/UserDailyGameResultRepository.java — projeções e consultas agregadas do ranking visual.
- Modify: src/main/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameController.java — duas rotas visuais.
- Modify: src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameService.java — expor getTodayOverview.
- Modify: src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImpl.java — reutilizar o carregamento diário existente para a visão resumida.
- Modify: src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java — montar os cards seguros do overview sem duplicar a regra de redaction de imagem.
- Create/Modify: testes de DTO, service, repository e controller do pacote dailygame.
- Modify: docs/context/openapi.yaml, docs/context/progress.md, docs/context/business-rules.md, docs/telas-reqs-definitivo.md.

## Public contracts

~~~java
public record DailyGameOverviewCardDTO(
        DailyGameType gameType,
        DailyGameTargetKind targetKind,
        int maxAttempts,
        int attemptsUsed,
        int attemptsRemaining,
        DailyGameViewStatus status,
        int score,
        String imageUrl,
        List<String> visibleImageUrls
) {}

public record DailyGamesOverviewDTO(
        LocalDate date,
        Instant nextResetAt,
        int totalMaxScore,
        int currentScore,
        List<DailyGameOverviewCardDTO> games
) {}

public record DailyGameRankingViewEntryDTO(
        long rank,
        UUID userId,
        String name,
        String username,
        String profilePicture,
        long score,
        long daysPlayed,
        long gamesPlayed,
        int solvedPercentage,
        BigDecimal averageAttempts
) {}

public record DailyGameRankingViewerDTO(
        UUID userId,
        String name,
        String username,
        String profilePicture,
        Long rank,
        long score,
        long daysPlayed,
        long gamesPlayed,
        int solvedPercentage,
        BigDecimal averageAttempts,
        boolean inTop200,
        Long top200CutoffScore,
        Long pointsToTop200
) {}

public record DailyGameRankingViewDTO(
        List<DailyGameRankingViewEntryDTO> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        DailyGameType gameType,
        DailyGameRankingViewerDTO viewer
) {}
~~~

### Task 1: Add Games overview contracts through the existing DailyGame module

**Files:**
- Create: src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameOverviewCardDTO.java
- Create: src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGamesOverviewDTO.java
- Modify: src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameService.java
- Modify: src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImpl.java
- Modify: src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java
- Modify: src/main/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameController.java
- Test: src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImplTest.java
- Test: src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerIntegrationTest.java

**Consumes:** DailyChallengeRepository.findByChallengeDateOrderByGameTypeAsc, UserDailyGameResultRepository.findByUserIdAndDailyChallengeIdIn, DailyGameType, Clock and DailyGamesUnavailableException.

**Produces:**
~~~java
public interface DailyGameService {
    DailyGamesOverviewDTO getTodayOverview(UUID userId);
}
~~~

HTTP:
~~~http
GET /games/today/view
~~~

- [ ] **Step 1: Write failing service tests**

Cover these cases:

~~~java
DailyGamesOverviewDTO overview = service.getTodayOverview(userId);
// eight cards ordered by DailyGameType
// totalMaxScore == 64
// currentScore is the sum of the viewer terminal scores
// nextResetAt is the next midnight in UTC
// no answer, hints, attempts, filmography or full imageUrls appear in the DTO
~~~

Also cover an incomplete daily set returning DailyGamesUnavailableException and an IN_PROGRESS result preserving attemptsUsed, attemptsRemaining and only the safe current image fields.

- [ ] **Step 2: Run the focused test to verify it fails**

Run:
~~~powershell
.\mvnw.cmd test "-Dtest=DailyGameServiceImplTest"
~~~

Expected: compilation failure because the overview DTOs and service do not exist.

- [ ] **Step 3: Implement the overview inside DailyGameServiceImpl**

Extract one private day loader in DailyGameServiceImpl that validates the eight challenges and loads the viewer results once. Reuse that loader from getDay and getTodayOverview. Add a public DailyChallengeResponseAssembler method that maps challenges and results to DailyGameOverviewCardDTO using its existing image-redaction logic. The overview must not query DailyChallengeHintRepository or decode attempt details. Compute currentScore by summing card scores and totalMaxScore by summing maxAttempts. Set nextResetAt to date.plusDays(1) at 00:00 UTC.

- [ ] **Step 4: Add the controller route**

Inject the existing DailyGameService into DailyGameController and return DailyGamesOverviewDTO from GET /games/today/view. Reuse currentUserId and the existing authentication behavior. Do not add CSRF requirements to this GET route.

- [ ] **Step 5: Write and run integration tests**

Verify authenticated access returns eight cards, 64 totalMaxScore, a UTC reset timestamp and no prohibited detailed fields. Verify no access token returns the established 401 ApiError and an incomplete day returns 503.

Run:
~~~powershell
.\mvnw.cmd test "-Dtest=DailyGameServiceImplTest,DailyGameControllerIntegrationTest,DailyGameControllerTest"
~~~

Expected: PASS.

- [ ] **Step 6: Review security and payload shape**

Confirm that MOVIE_BY_INFO, SERIES_BY_INFO and both actor-filmography cards have no answer-bearing image before completion. Confirm the response includes no DailyGameAttemptDTO, DailyGameHintDTO or DailyGameFilmographyStateDTO.

- [ ] **Step 7: Commit the overview read model**

Run:
~~~powershell
git add src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(dailygame): add games overview view"
git log -1 --format=%B
~~~

Expected: the commit message has no Co-Authored-By trailer or other self-attribution.

### Task 2: Add ranking aggregate projections

**Files:**
- Modify: src/main/java/com/watchwise/watchwise_api/dailygame/repository/UserDailyGameResultRepository.java
- Test: src/test/java/com/watchwise/watchwise_api/dailygame/repository/DailyGameRepositoryTest.java

**Consumes:** user_daily_game_results, daily_challenges and users tables.

**Produces:**
~~~java
interface DailyGameRankingViewProjection {
    Long getRank();
    UUID getUserId();
    String getName();
    String getUsername();
    String getProfilePicture();
    Long getScore();
    Long getDaysPlayed();
    Long getGamesPlayed();
    Long getSolvedGames();
    Long getAttemptsUsed();
    BigDecimal getAverageAttempts();
}

Page<DailyGameRankingViewProjection> findRankingViewByGameType(
        @Param("gameType") String gameType,
        Pageable pageable
);

Optional<DailyGameRankingViewProjection> findRankingViewerByGameType(
        @Param("gameType") String gameType,
        @Param("userId") UUID userId
);

Optional<Long> findTop200CutoffScoreByGameType(
        @Param("gameType") String gameType,
        @Param("topLimit") int topLimit
);
~~~

- [ ] **Step 1: Write failing repository tests**

Persist terminal COMPLETED and FAILED results spanning distinct dates and multiple game types. Assert:

- general ranking returns score, daysPlayed, gamesPlayed, solvedGames, attemptsUsed and averageAttempts;
- per-game ranking excludes other game types;
- name, username and profilePicture come from the row owner;
- viewer projection returns rank even when the viewer is not in the requested page;
- cutoff is the lowest score among rows whose rank is at most 200;
- users with only IN_PROGRESS results are absent from ranking projections.

- [ ] **Step 2: Run the repository test to verify it fails**

Run:
~~~powershell
.\mvnw.cmd test "-Dtest=DailyGameRepositoryTest"
~~~

Expected: compilation failure because the visual ranking projections and methods do not exist.

- [ ] **Step 3: Implement the native aggregate queries**

Use one ranked common table expression for each projection. Its inner aggregate must select user ID, User.name, username, profile picture, SUM(score), COUNT(*), COUNT(DISTINCT challenge_date), SUM(attempts_used), COUNT of COMPLETED statuses and AVG(attempts_used). Its RANK window ordering must remain SUM(score) DESC then SUM(attempts_used) ASC, matching the raw ranking policy. Filter terminal statuses before aggregation and filter gameType only when the parameter is non-null.

Do not modify findRankingByGameType or DailyGameRankingProjection; raw endpoints retain their exact DTO and ordering contract.

- [ ] **Step 4: Run repository tests**

Run:
~~~powershell
.\mvnw.cmd test "-Dtest=DailyGameRepositoryTest,DailyGameRankingServiceImplTest"
~~~

Expected: PASS.

- [ ] **Step 5: Review ranking edge cases**

Verify rank ties preserve PostgreSQL RANK semantics. Verify a missing cutoff is represented as null when fewer than 200 ranked users exist. Verify no query joins unbounded child collections.

- [ ] **Step 6: Commit aggregate projections**

Run:
~~~powershell
git add src/main/java/com/watchwise/watchwise_api/dailygame/repository src/test/java/com/watchwise/watchwise_api/dailygame/repository src/test/java/com/watchwise/watchwise_api/dailygame/service/impl
git commit -m "feat(dailygame): add ranking view aggregates"
git log -1 --format=%B
~~~

Expected: the commit message has no Co-Authored-By trailer or other self-attribution.

### Task 3: Add the ranking view service and endpoint

**Files:**
- Create: src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameRankingViewEntryDTO.java
- Create: src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameRankingViewerDTO.java
- Create: src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameRankingViewDTO.java
- Create: src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameRankingViewService.java
- Create: src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameRankingViewServiceImpl.java
- Modify: src/main/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameController.java
- Test: src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameRankingViewServiceImplTest.java
- Test: src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerIntegrationTest.java

**Consumes:** the three Task 2 repository projections, UserRepository and PageRequestFactory.

**Produces:**
~~~java
public interface DailyGameRankingViewService {
    DailyGameRankingViewDTO getRankingView(
            UUID viewerId,
            DailyGameType gameType,
            Integer page,
            Integer size
    );
}
~~~

HTTP:
~~~http
GET /games/rankings/view?gameType={optional DailyGameType}&page={project page convention}&size={1..20}
~~~

- [ ] **Step 1: Write failing service tests**

Cover:

- null gameType returns general ranking;
- a selected gameType returns only that mode;
- visual page size is capped at 20;
- viewer outside the loaded page still receives rank and metrics;
- viewer with no terminal result returns rank null, zeros for score/daysPlayed/gamesPlayed, solvedPercentage 0, averageAttempts 0.0 and inTop200 false; when at least 200 players are ranked, it still returns top200CutoffScore and pointsToTop200;
- viewer outside the top 200 receives top200CutoffScore and pointsToTop200;
- viewer within the top 200 returns inTop200 true and null pointsToTop200;
- solvedPercentage rounds completed divided by terminal games;
- averageAttempts rounds to one decimal;
- content preserves repository ranking order and totalElements is the total player count.

- [ ] **Step 2: Run the focused test to verify it fails**

Run:
~~~powershell
.\mvnw.cmd test "-Dtest=DailyGameRankingViewServiceImplTest"
~~~

Expected: compilation failure because ranking view DTOs and service do not exist.

- [ ] **Step 3: Implement DailyGameRankingViewServiceImpl**

Create PageRequest with pageRequestFactory.build(page, size, 20). Fetch the page and the viewer projection through the Task 2 repository methods. Map projection values to visual DTOs, using User.name with username as a fallback only when name is null or blank. Calculate solvedPercentage as round(100.0 * solvedGames / gamesPlayed). Calculate averageAttempts at one decimal using HALF_UP. Query the top-200 cutoff when the viewer has no rank or has rank greater than 200. Use null, not zero, for cutoff and pointsToTop200 when fewer than 200 ranked users exist or when the viewer is already in the top 200.

- [ ] **Step 4: Add the controller route**

Expose GET /games/rankings/view in DailyGameController. Bind optional gameType as DailyGameType and use currentUserId. Keep GET /games/rankings/general and GET /games/{gameType}/ranking unchanged.

- [ ] **Step 5: Run focused service and HTTP tests**

Run:
~~~powershell
.\mvnw.cmd test "-Dtest=DailyGameRankingViewServiceImplTest,DailyGameControllerIntegrationTest,DailyGameControllerTest,DailyGameRankingServiceImplTest"
~~~

Expected: PASS.

- [ ] **Step 6: Review authorization and query count**

Verify the route returns only the authenticated viewer summary. Verify the ranked list uses page query plus bounded viewer and optional cutoff aggregates, never one query per row. Verify profile names are already selected by the aggregate query.

- [ ] **Step 7: Commit the ranking view**

Run:
~~~powershell
git add src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(dailygame): add ranking view"
git log -1 --format=%B
~~~

Expected: the commit message has no Co-Authored-By trailer or other self-attribution.

### Task 4: Synchronize API and screen documentation

**Files:**
- Modify: docs/context/openapi.yaml
- Modify: docs/context/progress.md
- Modify: docs/context/business-rules.md
- Modify: docs/telas-reqs-definitivo.md

**Consumes:** contracts from Tasks 1-3.

**Produces:** current API documentation and screen-loading guidance.

- [ ] **Step 1: Add OpenAPI routes and schemas**

Document GET /games/today/view and GET /games/rankings/view. Specify cookie authentication, 20-item visual cap, 1-based response page, optional gameType, 64 total daily points, UTC nextResetAt and all overview/ranking DTO schemas. State that the raw game and ranking routes remain available.

- [ ] **Step 2: Document implemented business rules**

Add daily-game rules covering the safe overview projection, no answer leakage, the 64-point source of truth, terminal-result ranking scope, percentage/average formulas, top-200 cutoff behavior and viewer summary isolation.

- [ ] **Step 3: Update definitive screen requirements**

Add Games and Games Ranking sections. Document the two view routes, enum-to-card mapping, the use of nextResetAt, 64-point maximum, pagination, viewer row, cutoff state and PARTIAL/UNAVAILABLE behavior. Update the prototype expectation from 68 to 64 points and replace the claim that ranking updates at midnight with wording that it is calculated from terminal results at read time.

- [ ] **Step 4: Update chronological progress**

Append a 2026-10-14 entry only after code is implemented, describing overview/ranking visual reads and their tests.

- [ ] **Step 5: Validate documentation**

Run:
~~~powershell
python -c "import yaml; yaml.safe_load(open('docs/context/openapi.yaml', encoding='utf-8')); print('openapi yaml: OK')"
rg -n "/games/today/view|/games/rankings/view|DailyGamesOverviewDTO|DailyGameRankingViewDTO|64" docs/context/openapi.yaml docs/context/progress.md docs/context/business-rules.md docs/telas-reqs-definitivo.md
git diff --check
~~~

Expected: valid YAML, all four documents mention final contracts, and no whitespace errors.

- [ ] **Step 6: Keep documentation uncommitted**

Do not add docs files to code commits unless the user explicitly asks for a documentation commit.

### Task 5: Independent review and verification

**Files:**
- Review: all production and test files changed in Tasks 1-4.

**Consumes:** complete overview and ranking visual read models.

**Produces:** final review report and verification evidence.

- [ ] **Step 1: Run the Games-focused suite**

Run:
~~~powershell
.\mvnw.cmd test "-Dtest=DailyGameRankingViewServiceImplTest,DailyGameControllerIntegrationTest,DailyGameControllerTest,DailyGameRepositoryTest,DailyGameRankingServiceImplTest,DailyGameServiceImplTest"
~~~

Expected: PASS.

- [ ] **Step 2: Run the complete suite**

Run:
~~~powershell
.\mvnw.cmd test
~~~

Expected: PASS. If Surefire completes reports but its fork shutdown times out, record the exact class/test totals and the timeout rather than calling the Maven command successful.

- [ ] **Step 3: Independently inspect regressions**

Verify:

- raw game, attempt, give-up, history and ranking routes retain their contracts;
- overview never exposes answers or hidden challenge media;
- the viewer ranking is recipient-scoped and is available outside the visible ranking page;
- no ranking query produces N+1 user lookups;
- 64, not 68, is the only maximum daily score exposed by the new view;
- invalid enum, pagination and authentication errors use ApiError;
- changed commits have no Co-Authored-By trailer.

- [ ] **Step 4: Commit a corrective change only if review finds a defect**

Use a focused Conventional Commit and immediately inspect git log -1 --format=%B to confirm no Co-Authored-By trailer.

- [ ] **Step 5: Report verification evidence**

Report endpoints, score convention, query behavior, tests and any environment-specific Surefire shutdown limitation. Do not push unless separately authorized.

