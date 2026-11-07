# Daily Games Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar os oito jogos diários globais do Watchwise, com desafios congelados por data GMT, tentativas agregadas por usuário, busca de candidatos, histórico, nove rankings, geração idempotente via TMDB e documentação sincronizada.

**Architecture:** Criar um pacote `dailygame` isolado, com `DailyChallenge` e `DailyChallengeHint` representando snapshots públicos do desafio e `UserDailyGameResult` representando somente o estado agregado do usuário. A geração será uma camada de estratégias por modalidade, protegida por lock advisory transacional do PostgreSQL e usando o `TmdbClient` com idioma fixo para manter o desafio igual para todos; a leitura e a submissão ficarão em serviços transacionais separados, com lock pessimista no resultado.

**Tech Stack:** Spring Boot 4.1, Java 21, Spring MVC, Spring Data JPA, PostgreSQL 16, Flyway, Caffeine, `TmdbClient`/`RestClient`, Lombok, records DTO, Testcontainers, MockMvc e Mockito.

## Global Constraints

- A data do desafio deve ser calculada em `GMT` (`UTC+00:00`), com virada às `00:00 GMT`.
- Todos os usuários recebem os mesmos oito desafios do dia.
- Somente um palpite confirmado consome tentativa; pesquisar ou digitar não consome.
- A pontuação do acerto na tentativa `n` é `maxAttempts - n + 1`.
- Um jogo concluído sem acerto vale zero.
- Depois de acertar ou esgotar as tentativas, a modalidade fica bloqueada para aquele usuário.
- Não são armazenados palpites individuais nem sua sequência.
- Desafios anteriores são somente leitura e a resposta correta fica disponível no histórico.
- A não repetição vale pela combinação `modalidade + conteúdo`.
- Se o pool elegível acabar, o sistema não repete conteúdo automaticamente; a geração sinaliza falha operacional.
- O desafio não cria uma linha em `contents`; pessoas e episódios continuam sendo referências externas congeladas no snapshot.
- Se o desafio do dia não estiver pronto, os endpoints não devolvem conjunto parcial: retornam `503` com `ApiError`.
- O resultado do usuário deve ser bloqueado para atualização durante a avaliação do palpite.
- A criação concorrente do primeiro resultado deve depender da restrição única e recuperar o registro vencedor da corrida.
- A geração usa lock no banco, não apenas `synchronized` local.
- A resposta correta e as pistas futuras nunca devem aparecer em DTOs de jogo aberto.
- `attemptsUsed` é sempre calculado e persistido pelo backend; nenhum contador do cliente é aceito.
- Toda exceção de TMDB, geração, concorrência ou validação deve terminar em `ApiError` pelo `GlobalExceptionHandler`.
- Geração e validação usam `TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE` (`en-US`), porque snapshots globais não podem variar pela preferência de idioma do usuário.
- A classificação etária dos jogos de informação usa a região fixa `BR`, conforme a especificação aprovada.
- Os testes de repositório e integração usam Testcontainers com `postgres:16-alpine`; o Docker precisa estar disponível.
- A alteração existente em `.gitignore` não faz parte desta feature e deve permanecer intocada.

## File Map

### Arquivos a criar

- `src/main/resources/db/migration/V59__create-daily-games-tables.sql`: tabelas, constraints e índices dos desafios, pistas e resultados.
- `src/main/java/com/watchwise/watchwise_api/common/transaction/AdvisoryLock.java`: contrato reutilizável para lock advisory transacional.
- `src/main/java/com/watchwise/watchwise_api/common/transaction/PostgresAdvisoryLock.java`: implementação via `pg_advisory_xact_lock(hashtext(?))`.
- `src/main/java/com/watchwise/watchwise_api/dailygame/entity/DailyChallenge.java`: snapshot persistido do desafio público.
- `src/main/java/com/watchwise/watchwise_api/dailygame/entity/DailyChallengeHint.java`: pista congelada e ordenada.
- `src/main/java/com/watchwise/watchwise_api/dailygame/entity/UserDailyGameResult.java`: estado agregado do usuário.
- `src/main/java/com/watchwise/watchwise_api/dailygame/entity/DailyGameType.java`: oito modalidades e suas tentativas máximas.
- `src/main/java/com/watchwise/watchwise_api/dailygame/entity/DailyGameTargetKind.java`: `MOVIE`, `SERIES`, `PERSON` e `EPISODE`.
- `src/main/java/com/watchwise/watchwise_api/dailygame/entity/DailyGameResultStatus.java`: `IN_PROGRESS`, `COMPLETED` e `FAILED`.
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameViewStatus.java`: inclui `NOT_PLAYED` para respostas sem linha de resultado.
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAttemptRequest.java`: IDs opcionais de filme, série, pessoa ou episódio composto.
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAnswerDTO.java`: resposta congelada, exposta somente após conclusão.
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameHintDTO.java`: pista liberada.
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameStateDTO.java`: estado de uma modalidade no dia.
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameTodayResponseDTO.java`: envelope de `/games/today`.
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAttemptResponseDTO.java`: resultado de um palpite.
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameHistoryDTO.java`: item de histórico, inclusive `NOT_PLAYED`.
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameSearchResultDTO.java`: candidato de busca com os campos próprios do alvo.
- `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameRankingEntryDTO.java`: linha de ranking com posição, pontos e tentativas.
- `src/main/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameController.java`: endpoints autenticados de jogos, busca, histórico e ranking.
- `src/main/java/com/watchwise/watchwise_api/dailygame/repository/DailyChallengeRepository.java`: leituras e verificações de unicidade dos desafios.
- `src/main/java/com/watchwise/watchwise_api/dailygame/repository/DailyChallengeHintRepository.java`: leitura em lote das pistas.
- `src/main/java/com/watchwise/watchwise_api/dailygame/repository/UserDailyGameResultRepository.java`: lock, criação concorrente, histórico e rankings.
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameService.java`: contrato de estado diário e submissão.
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameCandidateIdentity.java`: identidade normalizada de um candidato validado.
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameCandidateValidator.java`: validação de tipo, existência e coordenadas antes de consumir tentativa.
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameSearchService.java`: contrato das três buscas.
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameRankingService.java`: contrato dos rankings.
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyChallengeGenerationService.java`: contrato do job idempotente.
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImpl.java`: leitura e avaliação transacional.
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImpl.java`: busca restrita por modalidade.
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameRankingServiceImpl.java`: montagem paginada dos rankings.
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeGenerationServiceImpl.java`: lock por data, retries de candidatos e persistência.
- `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java`: projeção segura de entidades para respostas.
- `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeCandidate.java`: snapshot intermediário validado.
- `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeGenerator.java`: estratégia de uma modalidade.
- `src/main/java/com/watchwise/watchwise_api/dailygame/generation/MovieByPosterGenerator.java`.
- `src/main/java/com/watchwise/watchwise_api/dailygame/generation/SeriesByPosterGenerator.java`.
- `src/main/java/com/watchwise/watchwise_api/dailygame/generation/PersonByFaceGenerator.java`.
- `src/main/java/com/watchwise/watchwise_api/dailygame/generation/EpisodeByFrameGenerator.java`.
- `src/main/java/com/watchwise/watchwise_api/dailygame/generation/MovieByInfoGenerator.java`.
- `src/main/java/com/watchwise/watchwise_api/dailygame/generation/SeriesByInfoGenerator.java`.
- `src/main/java/com/watchwise/watchwise_api/dailygame/generation/ActorByMovieFilmographyGenerator.java`.
- `src/main/java/com/watchwise/watchwise_api/dailygame/generation/ActorBySeriesFilmographyGenerator.java`.
- `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeSnapshotAssembler.java`: ordenação de pistas e composição de `answerKey`.
- `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyGameGenerationJob.java`: execução periódica para hoje e amanhã GMT.
- `src/main/java/com/watchwise/watchwise_api/common/exception/DailyGamesUnavailableException.java`: exceção pública para `503`.
- `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbNetwork.java`, `TmdbTvContentRatings.java` e `TmdbTvContentRating.java`: modelos adicionais necessários aos snapshots de informação.
- `src/test/java/com/watchwise/watchwise_api/common/transaction/PostgresAdvisoryLockTest.java`.
- `src/test/java/com/watchwise/watchwise_api/dailygame/repository/DailyGameRepositoryTest.java`.
- `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImplTest.java`.
- `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImplTest.java`.
- `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameRankingServiceImplTest.java`.
- `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeGenerationServiceImplTest.java`.
- `src/test/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeGeneratorTest.java`.
- `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerIntegrationTest.java`.
- `src/test/java/com/watchwise/watchwise_api/dailygame/tracking/DailyGameGenerationJobTest.java`.

### Arquivos a modificar

- `src/main/java/com/watchwise/watchwise_api/calendar/repository/CalendarScheduleSnapshotStore.java`: reutilizar o lock advisory comum.
- `src/main/java/com/watchwise/watchwise_api/calendar/repository/CalendarScheduleIdentityLock.java` e `PostgresCalendarScheduleIdentityLock.java`: remover a duplicação depois de migrar o calendário para o contrato comum.
- `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClient.java`: endpoints de popular/top rated, certificações, ratings de séries e busca de episódios por detalhes cacheados.
- `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCacheConfig.java`: caches bounded e TTL para as novas respostas TMDB.
- `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbImageUrlBuilder.java`: URL para still/frame, preservando o padrão de montagem centralizado.
- `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbTvFullDetails.java`: mapear redes, sem alterar os DTOs menores usados pelo tracking.
- `src/main/resources/application-dev.properties` e `src/main/resources/application-prod.properties`: cron, região de classificação, limites de geração, TTL e rate limits.
- `src/main/java/com/watchwise/watchwise_api/common/exception/GlobalExceptionHandler.java`: converter indisponibilidade dos jogos para `503 ApiError`.
- `src/main/java/com/watchwise/watchwise_api/common/exception/GlobalExceptionHandlerTest.java` e `GlobalExceptionHandlerIntegrationTest.java`: verificar o formato de erro.
- `docs/context/openapi.yaml`: tag `Games`, nove rotas de consulta mais rotas de busca e envio, parâmetros e schemas.
- `docs/context/database-schema.md` e `docs/context/database-schema.html`: registrar as três tabelas e suas constraints.
- `docs/context/business-rules.md`: registrar apenas regras realmente implementadas.
- `docs/context/development-stages.md`: registrar a nova etapa de Daily Games depois das dependências existentes.
- `docs/context/progress.md`: adicionar a entrega de 2026-09-27 somente após o código e os testes existirem.

## Contract Shapes Locked for Implementation

Os records abaixo fixam os nomes e a semântica da API antes do código. Campos de identidade que não se aplicam ao `targetKind` devem ser `null`; o serviço rejeita combinações extras no request de tentativa.

```java
public record DailyGameAttemptRequest(
        @Pattern(regexp = "^[1-9]\\d{0,19}$") String tmdbId,
        @Pattern(regexp = "^[1-9]\\d{0,19}$") String personTmdbId,
        @Pattern(regexp = "^[1-9]\\d{0,19}$") String seriesTmdbId,
        @Min(1) Integer seasonNumber,
        @Min(1) Integer episodeNumber) {}

public record DailyGameHintDTO(int position, String hintType, String hintValue) {}

public record DailyGameAnswerDTO(
        DailyGameTargetKind targetKind,
        String tmdbId,
        String personTmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        String title,
        String imageUrl) {}

public record DailyGameStateDTO(
        DailyGameType gameType,
        DailyGameTargetKind targetKind,
        int maxAttempts,
        int attemptsUsed,
        int attemptsRemaining,
        DailyGameViewStatus status,
        String imageUrl,
        List<DailyGameHintDTO> hints,
        DailyGameAnswerDTO answer) {}

public record DailyGameTodayResponseDTO(
        LocalDate challengeDate,
        List<DailyGameStateDTO> games) {}

public record DailyGameAttemptResponseDTO(
        DailyGameType gameType,
        boolean correct,
        DailyGameViewStatus status,
        int attemptsUsed,
        int attemptsRemaining,
        int score,
        LocalDateTime completedAt,
        List<DailyGameHintDTO> hints,
        DailyGameAnswerDTO answer) {}

public record DailyGameHistoryDTO(
        LocalDate challengeDate,
        DailyGameType gameType,
        DailyGameTargetKind targetKind,
        int maxAttempts,
        DailyGameViewStatus status,
        int attemptsUsed,
        int score,
        LocalDateTime completedAt,
        DailyGameAnswerDTO answer) {}

public record DailyGameSearchResultDTO(
        DailyGameTargetKind targetKind,
        String tmdbId,
        String personTmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        String title,
        String imageUrl,
        LocalDate date) {}

public record DailyGameRankingEntryDTO(
        long rank,
        UUID userId,
        String username,
        String profilePicture,
        long score,
        long attemptsUsed) {}
```

`PageResponseDTO<DailyGameSearchResultDTO>` é usado nas três buscas e `PageResponseDTO<DailyGameHistoryDTO>`/`PageResponseDTO<DailyGameRankingEntryDTO>` nas leituras paginadas. O endpoint de submissão retorna `200 OK`; nenhum endpoint de jogo retorna `201`.

---

### Task 1: Centralizar o lock advisory e fixar a fronteira de tempo GMT

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/common/transaction/AdvisoryLock.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/transaction/PostgresAdvisoryLock.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/calendar/repository/CalendarScheduleSnapshotStore.java`
- Delete: `src/main/java/com/watchwise/watchwise_api/calendar/repository/CalendarScheduleIdentityLock.java`
- Delete: `src/main/java/com/watchwise/watchwise_api/calendar/repository/PostgresCalendarScheduleIdentityLock.java`
- Test: `src/test/java/com/watchwise/watchwise_api/common/transaction/PostgresAdvisoryLockTest.java`

**Interfaces:**
- Produces `AdvisoryLock.lock(String identity)`, executado dentro de uma transação que mantém `pg_advisory_xact_lock` até o commit ou rollback.
- O calendário passa a injetar `AdvisoryLock`; Daily Games usará a mesma implementação com a chave `daily-games|yyyy-MM-dd`.

- [ ] **Step 1: Escrever o teste de integração do lock**

Use um `PostgreSQLContainer<?>` e duas transações em threads separadas. A segunda chamada para a mesma identidade deve bloquear até a primeira transação terminar; identidades diferentes não devem bloquear entre si.

```java
@Test
void sameIdentityMustSerializeAcrossConnections() throws Exception {
    // Executar duas transações reais com JdbcTemplate e uma barreira CountDownLatch.
    // Verificar que a segunda só completa depois do rollback/commit da primeira.
}
```

- [ ] **Step 2: Rodar o teste para confirmar a falha**

Run: `mvnw.cmd test "-Dtest=PostgresAdvisoryLockTest"`

Expected: FAIL porque o contrato comum ainda não existe.

- [ ] **Step 3: Implementar o lock e migrar o calendário**

```java
@FunctionalInterface
public interface AdvisoryLock {
    void lock(String identity);
}

@Repository
@RequiredArgsConstructor
class PostgresAdvisoryLock implements AdvisoryLock {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void lock(String identity) {
        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtext(?))",
                String.class,
                identity);
    }
}
```

Substituir o tipo injetado no `CalendarScheduleSnapshotStore`, remover as duas classes específicas de calendário e manter as chamadas `lock("movie|...")` e `lock("series|...")` sem mudar suas identidades.

- [ ] **Step 4: Rodar o teste para confirmar a passagem**

Run: `mvnw.cmd test "-Dtest=PostgresAdvisoryLockTest,CalendarScheduleRefreshJobTest"`

Expected: PASS; o calendário continua compilando e o lock é transacional.

- [ ] **Step 5: Commitar o bloco de código**

```bash
git add src/main/java/com/watchwise/watchwise_api/common/transaction src/main/java/com/watchwise/watchwise_api/calendar/repository src/test/java/com/watchwise/watchwise_api/common/transaction/PostgresAdvisoryLockTest.java
git commit -m "refactor(transaction): centralize advisory lock"
```

### Task 2: Criar o schema Flyway, entidades e repositórios

**Files:**
- Create: `src/main/resources/db/migration/V59__create-daily-games-tables.sql`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/entity/DailyChallenge.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/entity/DailyChallengeHint.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/entity/UserDailyGameResult.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/entity/DailyGameType.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/entity/DailyGameTargetKind.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/entity/DailyGameResultStatus.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/repository/DailyChallengeRepository.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/repository/DailyChallengeHintRepository.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/repository/UserDailyGameResultRepository.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/repository/DailyGameRepositoryTest.java`

**Interfaces:**
- `DailyGameType.maxAttempts()` retorna `6` para `MOVIE_BY_POSTER`, `SERIES_BY_POSTER` e `PERSON_BY_FACE`, e `10` para as outras cinco modalidades.
- `DailyChallengeRepository.findByChallengeDateAndGameType(LocalDate, DailyGameType)` é a leitura única do desafio do dia por modalidade.
- `UserDailyGameResultRepository.findByUserIdAndDailyChallengeIdForUpdate(UUID, UUID)` usa `@Lock(PESSIMISTIC_WRITE)`.
- `UserDailyGameResultRepository.insertIfAbsent(...)` usa `INSERT ... ON CONFLICT (user_id, daily_challenge_id) DO NOTHING`; depois dele, o serviço sempre relê a linha com lock.

- [ ] **Step 1: Escrever a migration e o teste de schema**

A migration deve criar `daily_challenges`, `daily_challenge_hints` e `user_daily_game_results` com os campos da especificação. O snapshot heterogêneo será `JSONB`, mas as identidades usadas para validação permanecem colunas relacionais:

```sql
CREATE TABLE daily_challenges (
    id UUID PRIMARY KEY,
    challenge_date DATE NOT NULL,
    game_type VARCHAR(40) NOT NULL,
    target_kind VARCHAR(10) NOT NULL,
    target_tmdb_id VARCHAR(20),
    series_tmdb_id VARCHAR(20),
    season_number INTEGER,
    episode_number INTEGER,
    answer_key VARCHAR(256) NOT NULL,
    source_tmdb_id VARCHAR(20),
    image_path VARCHAR(500) NOT NULL,
    answer_snapshot JSONB NOT NULL,
    display_snapshot JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_daily_challenges_date_game UNIQUE (challenge_date, game_type),
    CONSTRAINT uq_daily_challenges_game_answer UNIQUE (game_type, answer_key)
);

CREATE TABLE daily_challenge_hints (
    id UUID PRIMARY KEY,
    daily_challenge_id UUID NOT NULL REFERENCES daily_challenges(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    hint_type VARCHAR(50) NOT NULL,
    hint_value TEXT NOT NULL,
    CONSTRAINT uq_daily_challenge_hints_position UNIQUE (daily_challenge_id, position),
    CONSTRAINT ck_daily_challenge_hints_positive_position CHECK (position > 0)
);

CREATE TABLE user_daily_game_results (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    daily_challenge_id UUID NOT NULL REFERENCES daily_challenges(id) ON DELETE CASCADE,
    attempts_used INTEGER NOT NULL DEFAULT 0,
    score INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_user_daily_game_results_user_challenge UNIQUE (user_id, daily_challenge_id),
    CONSTRAINT ck_user_daily_game_results_non_negative CHECK (attempts_used >= 0 AND score >= 0),
    CONSTRAINT ck_user_daily_game_results_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);
```

Adicionar checks de tipo e coordenadas na mesma migration: filmes e séries usam `target_tmdb_id`, pessoa usa `target_tmdb_id`, e episódios usam somente `series_tmdb_id + season_number + episode_number`, todos positivos quando preenchidos. Criar índices por `(challenge_date, game_type)`, `(game_type, answer_key)`, `(daily_challenge_id, score, attempts_used)`, `(user_id, daily_challenge_id)` e `(user_id, status, daily_challenge_id)`.

- [ ] **Step 2: Rodar o teste para confirmar a falha**

Run: `mvnw.cmd test "-Dtest=DailyGameRepositoryTest"`

Expected: FAIL porque a migration e as entidades ainda não existem.

- [ ] **Step 3: Implementar entidades e queries**

Mapear os dois snapshots com Hibernate JSON:

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "answer_snapshot", columnDefinition = "jsonb", nullable = false)
private JsonNode answerSnapshot;
```

Manter as pistas fora de uma coleção JPA obrigatória para evitar carregamento involuntário; `DailyChallengeHintRepository` fará `findByDailyChallengeIdOrderByPositionAsc`. A query de criação concorrente deve receber um UUID novo, `userId`, `challengeId` e `LocalDateTime now`, e retornar o número de linhas afetadas. A query com lock deve carregar somente o resultado daquele usuário e desafio.

- [ ] **Step 4: Testar constraints e queries reais**

O teste Testcontainers deve cobrir: uma linha por `(challenge_date, game_type)`, rejeição de `(game_type, answer_key)` repetido, permissão do mesmo `answer_key` em modalidades diferentes, cascata de pistas ao excluir o desafio, unicidade do resultado, criação `ON CONFLICT DO NOTHING`, leitura ordenada das pistas e lock pessimista.

Run: `mvnw.cmd test "-Dtest=DailyGameRepositoryTest"`

Expected: PASS com Flyway validando o schema inteiro.

- [ ] **Step 5: Commitar o bloco de código**

```bash
git add src/main/resources/db/migration/V59__create-daily-games-tables.sql src/main/java/com/watchwise/watchwise_api/dailygame/entity src/main/java/com/watchwise/watchwise_api/dailygame/repository src/test/java/com/watchwise/watchwise_api/dailygame/repository/DailyGameRepositoryTest.java
git commit -m "feat(daily-game): persist daily game state"
```

### Task 3: Estender o adaptador TMDB para a geração, informação e busca

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbNetwork.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbTvContentRatings.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbTvContentRating.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClient.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCacheConfig.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbTvFullDetails.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbImageUrlBuilder.java`
- Modify: `src/main/resources/application-dev.properties`
- Modify: `src/main/resources/application-prod.properties`
- Test: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientCachingTest.java`

**Interfaces:**
- `TmdbClient.getPopularMovies(int page, String language)` e `getTopRatedMovies(int page, String language)` retornam `TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>>`.
- `TmdbClient.getPopularSeries(int page, String language)` e `getTopRatedSeries(int page, String language)` retornam `TmdbLookupResult<TmdbSearchPage<TmdbTvSearchResult>>`.
- `TmdbClient.getTvContentRatings(String tmdbId, String language)` retorna `TmdbLookupResult<TmdbTvContentRatings>`.
- Os métodos novos mantêm o comportamento de retry único, não cacheiam `Unavailable` e convertem `404` para `NotFound`, conforme `TmdbClient.callWithRetry`.

- [ ] **Step 1: Escrever testes HTTP e de cache**

Usar `MockRestServiceServer` para conferir caminho, página, idioma, `include_adult=false`, mapeamento de redes e ratings, além de duas chamadas idênticas resultarem em uma chamada remota.

```java
@Test
void popularMoviesMustUseTheRequestedPageAndLanguage() {
    // Expect GET /movie/popular?page=7&language=en-US&include_adult=false.
    // Devolver TmdbSearchPage e verificar o id retornado.
}
```

- [ ] **Step 2: Rodar os testes para confirmar a falha**

Run: `mvnw.cmd test "-Dtest=TmdbClientTest,TmdbClientCachingTest"`

Expected: FAIL porque os métodos e beans de cache não existem.

- [ ] **Step 3: Implementar os modelos e métodos**

Reutilizar `TmdbSearchPage` e os modelos de busca existentes para os endpoints paginados. Acrescentar à resposta de TV somente os campos necessários para a geração, incluindo `networks`; manter `TmdbTvDetails` inalterado para não acoplar tracking a um contrato maior. Usar caches separados com TTL configurável e tamanho máximo finito para popular, top rated e ratings.

Adicionar propriedades com valores dev explícitos e equivalentes comentados no profile prod:

```properties
app.daily-games.generation.cron=0 */10 * * * *
app.daily-games.rating-region=BR
app.daily-games.generation-max-candidates=100
app.daily-games.search-cache-ttl-minutes=10
app.daily-games.search-cache-max-size=10000
app.rate-limit.daily-game-search.max-requests=30
app.rate-limit.daily-game-search.window-minutes=5
app.rate-limit.daily-game-attempt.max-requests=20
app.rate-limit.daily-game-attempt.window-minutes=5
```

- [ ] **Step 4: Rodar os testes para confirmar a passagem**

Run: `mvnw.cmd test "-Dtest=TmdbClientTest,TmdbClientCachingTest,TmdbCacheConfigTest"`

Expected: PASS; indisponibilidade não fica cacheada e os métodos antigos continuam com os mesmos contratos.

- [ ] **Step 5: Commitar o bloco de código**

```bash
git add src/main/java/com/watchwise/watchwise_api/common/tmdb src/main/resources/application-dev.properties src/main/resources/application-prod.properties src/test/java/com/watchwise/watchwise_api/common/tmdb
git commit -m "feat(tmdb): add daily game lookups"
```

### Task 4: Implementar as oito estratégias e o job idempotente de geração

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeCandidate.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeGenerator.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/MovieByPosterGenerator.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/SeriesByPosterGenerator.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/PersonByFaceGenerator.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/EpisodeByFrameGenerator.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/MovieByInfoGenerator.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/SeriesByInfoGenerator.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/ActorByMovieFilmographyGenerator.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/ActorBySeriesFilmographyGenerator.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeSnapshotAssembler.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeGenerationServiceImpl.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyGameGenerationJob.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeGeneratorTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeGenerationServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/tracking/DailyGameGenerationJobTest.java`

**Interfaces:**
- `DailyChallengeGenerator.generate(LocalDate challengeDate)` retorna `Optional<DailyChallengeCandidate>`; ausência significa falha operacional sem persistir item incompleto.
- `DailyChallengeCandidate` carrega `DailyGameType`, `DailyGameTargetKind`, coordenadas do alvo, `sourceTmdbId`, `answerKey`, `imagePath`, `answerSnapshot`, `displaySnapshot` e uma lista ordenada de `HintSnapshot`.
- `DailyChallengeGenerationService.ensureGenerated(LocalDate challengeDate)` é `@Transactional`, adquire `AdvisoryLock.lock("daily-games|" + challengeDate)`, verifica as oito modalidades e insere somente as ausentes.
- `DailyGameGenerationJob.run()` calcula `LocalDate.now(clock)` e chama `ensureGenerated(today)` e `ensureGenerated(today.plusDays(1))`; o job captura e registra falhas por data sem derrubar o scheduler.

- [ ] **Step 1: Escrever testes de estratégia com fixtures TMDB**

Cobrir cada regra sem chamar a internet: páginas aleatórias entre 1 e 50, item aleatório do retorno, descarte sem imagem, descarte sem metadado necessário, `answerKey` correto e snapshot sem depender do idioma/região do usuário.

```java
@Test
void episodeGeneratorMustUseCompositeIdentityAndSkipSpecialsAndFutureEpisodes() {
    // Fixture com season 0, episódio futuro, episódio sem still e episódio válido.
    // Esperar somente seriesTmdbId + seasonNumber + episodeNumber do episódio válido.
}
```

- [ ] **Step 2: Rodar os testes para confirmar a falha**

Run: `mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyChallengeGenerationServiceImplTest,DailyGameGenerationJobTest"`

Expected: FAIL porque as estratégias e o serviço ainda não existem.

- [ ] **Step 3: Implementar o contrato de candidato e as estratégias**

Regras obrigatórias por estratégia:

1. `MOVIE_BY_POSTER`: `/movie/popular?page=x`, `poster_path` obrigatório, alvo `MOVIE`.
2. `SERIES_BY_POSTER`: `/tv/popular?page=x`, `poster_path` obrigatório, alvo `SERIES`.
3. `PERSON_BY_FACE`: combinar elencos de filmes e séries populares escolhidos independentemente, deduplicar por `personTmdbId` e exigir `profile_path`.
4. `EPISODE_BY_FRAME`: série popular, temporada regular positiva, episódio lançado, `still_path` obrigatório e identidade composta; nunca usar o ID isolado do episódio.
5. `MOVIE_BY_INFO`: filme de `/movie/popular` com detalhes e pistas em ordem plataforma, gêneros, ano, classificação, diretor, elenco, produtoras e bilheteria.
6. `SERIES_BY_INFO`: série de `/tv/popular` com pistas em ordem plataforma, gêneros, ano de estreia, classificação, criador, elenco, redes/produtoras e quantidade de temporadas/episódios; não incluir bilheteria.
7. `ACTOR_BY_MOVIE_FILMOGRAPHY`: filme de `/movie/top_rated`, créditos, ator de elenco elegível com foto; resposta é a pessoa e `sourceTmdbId` é o filme.
8. `ACTOR_BY_SERIES_FILMOGRAPHY`: série de `/tv/top_rated`, créditos, ator de elenco elegível com foto; resposta é a pessoa e `sourceTmdbId` é a série.

O snapshot deve omitir campos ausentes, nunca preenchê-los com texto inventado e rejeitar candidato sem os metadados mínimos para a modalidade. A classificação será extraída da região `BR`; uma classificação ausente é omitida. A sequência de pistas de informação é igual para todos os usuários e fica em `daily_challenge_hints`.

- [ ] **Step 4: Implementar persistência sob lock e retry limitado**

Dentro da transação, para cada modalidade:

```java
if (challengeRepository.existsByChallengeDateAndGameType(date, type)) {
    return;
}

for (int attempt = 0; attempt < generationMaxCandidates; attempt++) {
    Optional<DailyChallengeCandidate> candidate = generator.generate(date);
    if (candidate.isEmpty() || challengeRepository.existsByGameTypeAndAnswerKey(type, candidate.get().answerKey())) {
        continue;
    }
    persistChallengeAndHints(candidate.get(), date);
    return;
}

log.error("Daily game generation exhausted eligible candidates for {} on {}", type, date);
```

O `saveAndFlush` deve acontecer somente depois de todas as validações do snapshot. Uma falha de TMDB, item incompleto ou pool esgotado deixa a modalidade ausente; não usar fallback que repita conteúdo. Uma falha de persistência deve abortar a transação da data para que nenhum estado parcialmente confirmado pareça completo, e o próximo ciclo tenta novamente.

- [ ] **Step 5: Implementar o job e testar datas GMT**

Usar `Clock` injetado. Com `Clock.fixed(Instant.parse("2026-09-27T23:59:59Z"), ZoneOffset.UTC)`, o job deve gerar `2026-09-28` como próximo dia; com `2026-09-28T00:00:00Z`, deve tratar `2026-09-28` como hoje. Testar que duas chamadas do job não inserem duplicatas e que o mesmo dia fica completo somente com oito desafios.

- [ ] **Step 6: Rodar os testes para confirmar a passagem**

Run: `mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyChallengeGenerationServiceImplTest,DailyGameGenerationJobTest"`

Expected: PASS com geração idempotente, retries limitados, ausência de conteúdo incompleto e virada GMT exata.

- [ ] **Step 7: Commitar o bloco de código**

```bash
git add src/main/java/com/watchwise/watchwise_api/dailygame/generation src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeGenerationServiceImpl.java src/main/resources/application-dev.properties src/main/resources/application-prod.properties src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(daily-game): generate frozen challenges"
```

### Task 5: Implementar leitura de hoje, submissão transacional e respostas seguras

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameService.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameCandidateIdentity.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameCandidateValidator.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImpl.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAttemptRequest.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAnswerDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameHintDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameStateDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameTodayResponseDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAttemptResponseDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameViewStatus.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/exception/DailyGamesUnavailableException.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/common/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- `DailyGameService.getToday(UUID userId)` retorna `DailyGameTodayResponseDTO` e exige exatamente oito desafios da data GMT atual.
- `DailyGameService.submitAttempt(UUID userId, DailyGameType gameType, DailyGameAttemptRequest request)` avalia somente o desafio atual daquela modalidade.
- `DailyGameCandidateValidator.validate(DailyGameType gameType, DailyGameAttemptRequest request)` retorna `DailyGameCandidateIdentity` somente depois de conferir a forma do request, o tipo remoto e a existência do alvo.
- `DailyGameCandidateIdentity.answerKey()` normaliza a identidade para `MOVIE:<tmdbId>`, `SERIES:<tmdbId>`, `PERSON:<personTmdbId>` ou `EPISODE:<seriesTmdbId>:<seasonNumber>:<episodeNumber>`.
- `DailyGamesUnavailableException` retorna `503` e mensagem genérica de indisponibilidade; nunca expõe exceção TMDB, SQL ou stack trace.

- [ ] **Step 1: Escrever os testes unitários do estado e da pontuação**

Cobrir: usuário sem resultado (`NOT_PLAYED`), primeira tentativa criando resultado `IN_PROGRESS`, acerto na tentativa 1 com 6/10, acerto na tentativa final com 1, erro intermediário, erro final com `FAILED` e 0, tentativa após conclusão com `409`, challenge ausente com `503`, pistas futuras omitidas, resposta omitida enquanto aberto e resposta revelada após conclusão.

```java
@Test
void correctGuessOnFirstAttemptGetsMaximumScore() {
    // Challenge MOVIE_BY_POSTER has maxAttempts=6.
    // submit the matching tmdbId and assert score=6, attemptsUsed=1, status=COMPLETED.
}
```

- [ ] **Step 2: Rodar os testes para confirmar a falha**

Run: `mvnw.cmd test "-Dtest=DailyGameServiceImplTest"`

Expected: FAIL porque o serviço e os DTOs ainda não existem.

- [ ] **Step 3: Implementar `getToday` com carregamento em lote**

Consultar os oito desafios por data e carregar todos os resultados do usuário em uma única query. Montar um estado sintético `NOT_PLAYED` quando não existe linha. Para jogos de informação, deixar a primeira pista disponível e liberar a posição `attemptsUsed + 1` após cada erro, limitado ao número de pistas; após conclusão, retornar todas as pistas. Para jogos visuais, expor a imagem desde o início. Enquanto o estado for aberto, `answer` deve ser `null` e nenhuma pista além da posição desbloqueada pode sair do assembler.

- [ ] **Step 4: Implementar `submitAttempt` como transação atômica**

O fluxo transacional deve ser:

```java
@Transactional
public DailyGameAttemptResponseDTO submitAttempt(UUID userId, DailyGameType type, DailyGameAttemptRequest request) {
    LocalDate today = LocalDate.now(clock);
    DailyChallenge challenge = challengeRepository.findByChallengeDateAndGameType(today, type)
            .orElseThrow(DailyGamesUnavailableException::new);
    resultRepository.insertIfAbsent(UUID.randomUUID(), userId, challenge.getId(), now);
    UserDailyGameResult result = resultRepository
            .findByUserIdAndDailyChallengeIdForUpdate(userId, challenge.getId())
            .orElseThrow(() -> new IllegalStateException("Daily game result was not created"));
    assertOpenAndHasAttempts(result, type);
    DailyGameCandidateIdentity candidate = candidateValidator.validate(type, request);
    int attemptNumber = result.getAttemptsUsed() + 1;
    result.setAttemptsUsed(attemptNumber);
    applyOutcome(result, challenge, candidate, attemptNumber, type.maxAttempts(), now);
    return assembler.toAttemptResponse(challenge, result);
}
```

O `candidateValidator` deve exigir exatamente os IDs compatíveis com a modalidade, rejeitar campos extras, confirmar a existência/tipo no TMDB e validar o episódio pela tripla `seriesTmdbId + seasonNumber + episodeNumber`. A validação inválida retorna `400` sem incrementar tentativas; um candidato válido, porém incorreto, incrementa exatamente uma vez. O `ON CONFLICT DO NOTHING` seguido de `FOR UPDATE` garante que chamadas concorrentes do primeiro palpite compartilhem uma única linha e serializem o contador.

Implementar a identidade normalizada sem comparar texto livre:

```java
public record DailyGameCandidateIdentity(
        DailyGameTargetKind targetKind,
        String tmdbId,
        String personTmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber) {

    public String answerKey() {
        return switch (targetKind) {
            case MOVIE -> "MOVIE:" + tmdbId;
            case SERIES -> "SERIES:" + tmdbId;
            case PERSON -> "PERSON:" + personTmdbId;
            case EPISODE -> "EPISODE:" + seriesTmdbId + ":" + seasonNumber + ":" + episodeNumber;
        };
    }
}
```

O validator deve usar `getMovieFullDetails`, `getTvFullDetails`, `getPersonDetails` ou `getEpisodeFullDetails` conforme o alvo; `NotFound` vira `BadRequestException` para o candidato enviado e `Unavailable` vira `TmdbUnavailableException`, sem alterar o resultado persistido.

- [ ] **Step 5: Implementar a resposta `503` no handler**

Adicionar um handler explícito antes do catch-all:

```java
@ExceptionHandler(DailyGamesUnavailableException.class)
ResponseEntity<ApiError> handleDailyGamesUnavailable(
        DailyGamesUnavailableException ex, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ApiError(LocalDateTime.now(), 503, "Service Unavailable",
                    "Daily games are temporarily unavailable", request.getRequestURI()));
}
```

- [ ] **Step 6: Rodar os testes para confirmar a passagem**

Run: `mvnw.cmd test "-Dtest=DailyGameServiceImplTest,GlobalExceptionHandlerTest"`

Expected: PASS; nenhuma resposta aberta contém `answerSnapshot`, resposta correta ou pistas futuras.

- [ ] **Step 7: Commitar o bloco de código**

```bash
git add src/main/java/com/watchwise/watchwise_api/dailygame/service src/main/java/com/watchwise/watchwise_api/dailygame/dto src/main/java/com/watchwise/watchwise_api/common/exception src/test/java/com/watchwise/watchwise_api/dailygame/service src/test/java/com/watchwise/watchwise_api/common/exception/GlobalExceptionHandlerTest.java
git commit -m "feat(daily-game): evaluate attempts safely"
```

### Task 6: Implementar busca de candidatos sem consumir tentativas

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameSearchService.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImpl.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameSearchResultDTO.java`
- Modify: `src/main/resources/application-dev.properties`
- Modify: `src/main/resources/application-prod.properties`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImplTest.java`

**Interfaces:**
- `Page<DailyGameSearchResultDTO> search(UUID userId, DailyGameType type, String query, Integer page, Integer size)` aceita filmes, séries, pessoas e atores, mas não aceita `EPISODE_BY_FRAME` na rota genérica.
- `Page<DailyGameSearchResultDTO> searchEpisodeSeries(UUID userId, String query, Integer page, Integer size)` retorna somente séries.
- `Page<DailyGameSearchResultDTO> searchEpisodes(UUID userId, String seriesTmdbId, String query, Integer page, Integer size)` retorna somente episódios regulares lançados daquela série.

- [ ] **Step 1: Escrever testes de tipo, paginação e ausência de efeitos**

Mockar `TmdbClient` e afirmar que `MOVIE_BY_POSTER`/`MOVIE_BY_INFO` chamam somente busca de filme, `SERIES_*` somente busca de série, modalidades de pessoa chamam busca de pessoa e nenhum caminho chama repositório de resultados ou altera `attemptsUsed`. Para episódios, testar as duas etapas, exclusão da temporada 0, exclusão de episódios futuros e retorno da identidade composta.

- [ ] **Step 2: Rodar o teste para confirmar a falha**

Run: `mvnw.cmd test "-Dtest=DailyGameSearchServiceImplTest"`

Expected: FAIL porque o serviço ainda não existe.

- [ ] **Step 3: Implementar busca e rate limit**

Normalizar `q` com trim, rejeitar vazio, limitar o tamanho e usar `PageRequestFactory` com o teto definido para busca. Passar `en-US` para que os nomes retornados coincidam com os snapshots globais. Para a segunda etapa de episódios, carregar somente temporadas regulares e já lançadas, buscar seus detalhes cacheados, filtrar `episode.name` por texto case-insensitive e montar `seriesTmdbId`, `seasonNumber` e `episodeNumber`.

Usar `RequestThrottler` com chave `daily-game-search|<userId>` antes de qualquer chamada externa. `TmdbUnavailableException` deve propagar para `502`; resultado vazio válido é `200` vazio. A busca jamais cria `UserDailyGameResult`.

- [ ] **Step 4: Rodar os testes para confirmar a passagem**

Run: `mvnw.cmd test "-Dtest=DailyGameSearchServiceImplTest"`

Expected: PASS; tipos misturados, IDs inválidos e indisponibilidade externa obedecem ao contrato sem consumir tentativas.

- [ ] **Step 5: Commitar o bloco de código**

```bash
git add src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameSearchService.java src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImpl.java src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameSearchResultDTO.java src/main/resources/application-dev.properties src/main/resources/application-prod.properties src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameSearchServiceImplTest.java
git commit -m "feat(daily-game): add candidate search"
```

### Task 7: Implementar histórico e os nove rankings agregados

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameRankingService.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameRankingServiceImpl.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameHistoryDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameRankingEntryDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/repository/DailyChallengeRepository.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/repository/UserDailyGameResultRepository.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameRankingServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/repository/DailyGameRepositoryTest.java`

**Interfaces:**
- `Page<DailyGameHistoryDTO> getHistory(UUID userId, DailyGameType type, Integer page, Integer size)` filtra desafios anteriores à data GMT atual; `type == null` retorna todas as modalidades.
- `Page<DailyGameRankingEntryDTO> getGeneralRanking(Integer page, Integer size)` soma pontos e tentativas de todos os resultados finais.
- `Page<DailyGameRankingEntryDTO> getRanking(DailyGameType type, Integer page, Integer size)` agrega somente a modalidade solicitada.

- [ ] **Step 1: Escrever queries e testes de agregação**

Usar resultados `COMPLETED` e `FAILED`; excluir `IN_PROGRESS`. O ranking geral deve somar `score` e `attempts_used` por usuário. O ranking específico deve agrupar por usuário e `game_type`. A posição usa `RANK() OVER (ORDER BY total_score DESC, total_attempts ASC)`, portanto usuários com os mesmos dois valores compartilham a posição e o próximo posto pula conforme a semântica de ranking.

```sql
RANK() OVER (
    ORDER BY SUM(r.score) DESC, SUM(r.attempts_used) ASC
) AS rank
```

Ordenar depois por `rank`, `username ASC`, `user_id ASC` para paginação determinística sem mudar a posição calculada.

- [ ] **Step 2: Rodar o teste para confirmar a falha**

Run: `mvnw.cmd test "-Dtest=DailyGameRankingServiceImplTest,DailyGameRepositoryTest"`

Expected: FAIL nas queries e DTOs ainda inexistentes.

- [ ] **Step 3: Implementar histórico e rankings paginados**

Histórico deve montar o resultado do usuário em lote, devolver `NOT_PLAYED` quando a linha não existe e sempre revelar o `answer` de desafios anteriores. O histórico do dia atual não deve duplicar `/games/today`. Para impedir respostas sem limite, aceitar `page`/`size` opcionais seguindo `PageResponseDTO`; limitar `size` a 100 e documentar esse detalhe no OpenAPI sem alterar o cálculo dos rankings.

As queries nativas de ranking devem incluir `countQuery` para `Page`, `JOIN users`, `SUM(score)`, `SUM(attempts_used)` e a janela `RANK()`. Não materializar totais em uma tabela nova; a especificação exige agregação na primeira versão.

- [ ] **Step 4: Rodar os testes para confirmar a passagem**

Run: `mvnw.cmd test "-Dtest=DailyGameRankingServiceImplTest,DailyGameRepositoryTest"`

Expected: PASS para ranking geral, oito rankings específicos, pontuação bruta, desempate por tentativas, empates e paginação.

- [ ] **Step 5: Commitar o bloco de código**

```bash
git add src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameRankingService.java src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameRankingServiceImpl.java src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameHistoryDTO.java src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameRankingEntryDTO.java src/main/java/com/watchwise/watchwise_api/dailygame/repository src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(daily-game): add history and rankings"
```

### Task 8: Expor os endpoints, CSRF, rate limits e integração HTTP

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameController.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/config/SecurityConfig.java` only if a matcher específico for throttling exigir registro adicional.
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerIntegrationTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/common/exception/GlobalExceptionHandlerIntegrationTest.java`

**Interfaces:**
- Rotas públicas do recurso, todas autenticadas pelo `anyRequest().authenticated()` existente:
  - `GET /games/today`
  - `GET /games/{gameType}/search?q=&page=&size=`
  - `GET /games/EPISODE_BY_FRAME/search/series?q=&page=&size=`
  - `GET /games/EPISODE_BY_FRAME/search/episodes?seriesTmdbId=&q=&page=&size=`
  - `POST /games/{gameType}/attempt`
  - `GET /games/history?page=&size=`
  - `GET /games/{gameType}/history?page=&size=`
  - `GET /games/rankings/general?page=&size=`
  - `GET /games/{gameType}/ranking?page=&size=`

- [ ] **Step 1: Escrever os testes MockMvc**

Testar `200` de hoje para usuário autenticado, `401` sem cookie, `403` sem CSRF no POST, `200` de busca sem consumir resultado, `200` para submissão aceita, `400` para enum/ID/body incompatível, `409` para jogo concluído, `503` para dia incompleto, `502` para TMDB indisponível, histórico com `NOT_PLAYED`, resposta correta no histórico e ranking paginado.

As chamadas concorrentes devem ser exercitadas com dois requests autenticados para o mesmo usuário/desafio e depois uma leitura direta do banco deve confirmar `attempts_used = 1`, não dois.

- [ ] **Step 2: Rodar o teste para confirmar a falha**

Run: `mvnw.cmd test "-Dtest=DailyGameControllerIntegrationTest"`

Expected: FAIL porque o controller e o wiring ainda não existem.

- [ ] **Step 3: Implementar o controller**

Extrair o principal do `SecurityContextHolder` como os controllers existentes. Converter `Page` para `PageResponseDTO`. Usar `@Valid` no request de tentativa, mas manter a validação de campos condicionais no serviço, pois a modalidade vem do path. Aplicar `RequestThrottler` para busca e submissão com as chaves configuradas; o rate limit deve acontecer antes de chamadas TMDB e não deve alterar o contador do jogo.

```java
@PostMapping("/{gameType}/attempt")
public ResponseEntity<DailyGameAttemptResponseDTO> submitAttempt(
        @PathVariable DailyGameType gameType,
        @Valid @RequestBody DailyGameAttemptRequest request) {
    return ResponseEntity.ok(service.submitAttempt(currentUserId(), gameType, request));
}
```

Usar `@GetMapping("/{gameType}/search")` somente para modalidades não episódicas e rotas literais para as duas etapas de episódio. O binding de enum e UUID deve continuar passando por `GlobalExceptionHandler`, nunca pelo body `ProblemDetail` do Spring.

- [ ] **Step 4: Rodar testes de autenticação, CSRF e errors**

Run: `mvnw.cmd test "-Dtest=DailyGameControllerIntegrationTest,GlobalExceptionHandlerIntegrationTest,SecurityConfigIntegrationTest"`

Expected: PASS com cookies JWT, CSRF, rate limit, `ApiError` uniforme e sem vazamento do snapshot privado.

- [ ] **Step 5: Commitar o bloco de código**

```bash
git add src/main/java/com/watchwise/watchwise_api/dailygame/controller src/main/java/com/watchwise/watchwise_api/common/config src/test/java/com/watchwise/watchwise_api/dailygame/controller src/test/java/com/watchwise/watchwise_api/common/exception/GlobalExceptionHandlerIntegrationTest.java
git commit -m "feat(daily-game): expose game endpoints"
```

### Task 9: Atualizar o contrato e a documentação de contexto

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/database-schema.md`
- Modify: `docs/context/database-schema.html`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/development-stages.md`
- Modify: `docs/context/progress.md`

- [ ] **Step 1: Atualizar o OpenAPI com o contrato implementado**

Adicionar a tag `Games`, os nove endpoints principais, as três buscas, schemas de request/response e os códigos `400`, `401`, `403`, `409`, `429`, `502` e `503` aplicáveis. Documentar que:

- `gameType` é o enum das oito modalidades e não é aceito no body como autoridade;
- `GET /games/today` exige exatamente oito itens e omite `answer`/pistas futuras enquanto o jogo está aberto;
- `POST /games/{gameType}/attempt` recebe IDs, não texto, e não armazena palpites;
- episódio usa a identidade composta `seriesTmdbId + seasonNumber + episodeNumber`;
- histórico é somente leitura, inclui `NOT_PLAYED` e revela a resposta de qualquer desafio anterior;
- rankings são acumulados, brutos, agregados sob demanda e desempatados pela soma de tentativas;
- `page` é 1-based no HTTP, `size` tem máximo 100 para histórico e ranking, e o envelope é `PageResponseDTO`.

- [ ] **Step 2: Atualizar o modelo lógico**

Registrar as três tabelas, FKs, índices, unicidades, checks de coordenadas, JSONB dos snapshots e a decisão de não criar `Content` para desafio, pessoa ou episódio. Refletir também a remoção do lock específico do calendário se o diagrama tiver esse detalhe de infraestrutura.

- [ ] **Step 3: Atualizar as regras de negócio**

Adicionar uma seção `Daily Games` apontando para as classes implementadas e registrar somente fatos verificáveis: data UTC, snapshot global, tentativas confirmadas, pontuação, bloqueio pós-conclusão, pistas congeladas, ausência sintética `NOT_PLAYED`, validação de candidatos, concorrência com `ON CONFLICT` + `FOR UPDATE`, lock advisory da geração, retries/cache TMDB, ausência de fallback repetido e resposta `503` sem conjunto completo.

- [ ] **Step 4: Atualizar o roteiro e o progresso**

Adicionar em `development-stages.md` a etapa Daily Games depois de User/Content e das agregações já implementadas, registrando suas dependências externas e seus testes. Em `progress.md`, criar a seção `## 2026-09-27 — Jogos diários` somente descrevendo o que realmente tiver sido implementado e verificado; não adicionar próximos passos.

- [ ] **Step 5: Validar documentação sem criar commit de docs**

Executar uma busca cruzada para garantir que todos os paths do controller aparecem no OpenAPI, todas as três tabelas aparecem no modelo lógico e toda regra especial do `business-rules.md` aponta para uma classe existente. Conforme `AGENTS.md`, manter as alterações em `docs/` fora do commit de código.

### Task 10: Verificação final, auditoria de lacunas e entrega

**Files:**
- Verify: todos os arquivos Java, migration, configuração, testes e documentos listados nas tarefas anteriores.
- Verify: `git status --short` para preservar alterações preexistentes, em especial `.gitignore`.

- [ ] **Step 1: Rodar os testes focados**

```bash
mvnw.cmd test "-Dtest=PostgresAdvisoryLockTest,DailyGameRepositoryTest,DailyChallengeGeneratorTest,DailyChallengeGenerationServiceImplTest,DailyGameServiceImplTest,DailyGameSearchServiceImplTest,DailyGameRankingServiceImplTest,DailyGameControllerIntegrationTest,DailyGameGenerationJobTest"
```

Expected: PASS; se Testcontainers estiver indisponível, registrar a limitação em vez de afirmar que a suíte passou.

- [ ] **Step 2: Rodar a suíte Maven completa**

```bash
mvnw.cmd test
```

Expected: todos os testes existentes e novos passam, Flyway sobe em banco limpo e `ddl-auto=validate` não acusa divergência.

- [ ] **Step 3: Verificar a migration e o contrato**

Conferir que V59 é a próxima migration, que constraints têm nomes estáveis, que nenhuma coluna da feature é client-supplied indevidamente e que o OpenAPI não declara endpoint diferente do controller.

- [ ] **Step 4: Executar a auditoria de loopholes**

Confirmar explicitamente:

- duas instâncias não geram o mesmo dia;
- duas submissões não incrementam a mesma linha duas vezes;
- primeiro resultado concorrente usa a linha vencedora sem `UnexpectedRollbackException`;
- falha TMDB não gera desafio incompleto nem resposta parcial;
- um ID de filme não pode ser enviado a jogo de pessoa, e um ID isolado de episódio nunca decide acerto;
- tentativa inválida não consome contador;
- tentativas válidas erradas não gravam payload do palpite;
- resultados `IN_PROGRESS` não entram em ranking;
- `FAILED` entra com score zero e suas tentativas entram no desempate;
- ranking com pontuação e tentativas iguais preserva a mesma posição;
- usuário sem resultado não aparece como participante do ranking, mas aparece como `NOT_PLAYED` no histórico;
- answer snapshot e pistas futuras nunca atravessam o assembler de estado aberto;
- exceções de binding, TMDB, indisponibilidade, concorrência e falha inesperada continuam no formato `ApiError`.

- [ ] **Step 5: Rodar verificação antes de afirmar conclusão**

Executar `git diff --check`, `git status --short` e reler as seções novas do OpenAPI, schema, regras de negócio e progresso. Só depois afirmar que o código está concluído; não executar `git push` sem autorização explícita.

## Self-Review Against the Approved Spec

| Requisito da especificação | Tarefa que cobre | Evidência exigida |
| --- | --- | --- |
| oito modalidades, tentativas 6/10 e máximo 68 | Tasks 2 e 4 | enum, testes de score e snapshot completo |
| data e virada GMT | Tasks 1, 4 e 5 | `Clock.fixed` em `23:59:59Z` e `00:00:00Z` |
| snapshot global sem `Content` | Tasks 2, 4 e 9 | migration sem FK para `contents`, docs e teste de usuários diferentes |
| pistas congeladas e progressivas | Tasks 2, 4 e 5 | tabela filha, assembler seguro e testes de exposição |
| geração idempotente e sem repetição silenciosa | Tasks 1 e 4 | advisory lock, unicidades e pool exaurido sem insert |
| TMDB retry/cache e falha operacional | Task 3 e 4 | testes de client, cache e job |
| busca por modalidade e episódio em duas etapas | Task 6 | testes de tipo, temporadas, episódios e zero consumo |
| submissão por IDs validados | Task 5 | validator por target kind e identidade composta |
| histórico somente leitura | Task 7 | `NOT_PLAYED`, resposta passada e ausência de mutação |
| ranking geral e oito específicos | Task 7 | queries agregadas e ranking com empate |
| locks e primeira criação concorrente | Tasks 1, 2 e 5 | `pg_advisory_xact_lock`, `ON CONFLICT`, `FOR UPDATE`, Testcontainers |
| segurança, CSRF e erros uniformes | Tasks 5 e 8 | `ApiError`, `401`, `403`, `409`, `502`, `503` |
| Flyway, OpenAPI e docs de contexto | Tasks 2, 8, 9 e 10 | V59, contrato cruzado e docs worktree-only |
| fora do escopo | Tasks 4, 7 e 9 | nenhum replay, nenhum ranking diário, nenhuma tabela materializada, nenhuma tabela de palpites, nenhum painel admin ou notificação |

The plan deliberately fixes two implementation details that the approved design leaves open without changing its product semantics: heterogeneous frozen snapshots use JSONB while identity fields remain relational, and history/ranking reads accept bounded pagination through the existing `PageResponseDTO` convention. Both choices must remain documented in the OpenAPI and logical schema.
