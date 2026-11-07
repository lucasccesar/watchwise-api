# Daily Game Information And Filmography Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Corrigir os jogos diários de informação e filmografia, ocultando imagens e hints indevidos, projetando filmografias redigidas e reparando snapshots legados incompletos.

**Architecture:** A política de exposição permanecerá centralizada no `DailyChallengeResponseAssembler`, enquanto os geradores continuarão responsáveis por produzir snapshots congelados. Um serviço dedicado de reparo será chamado pelo fluxo transacional de geração somente quando um desafio existente tiver campos incompatíveis com sua modalidade; leituras e tentativas continuarão usando os snapshots persistidos sem fallback remoto.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, PostgreSQL JSONB, TMDB client/cache, JUnit 5, Mockito, AssertJ, Maven Wrapper.

## Global Constraints

- Seguir o fluxo existente `Controller → Service → Repository / Mapper → Entity` e os pacotes `dailygame`.
- Manter `answerKey`, coordenadas TMDB, imagem e data de qualquer desafio existente; o reparo só pode preencher campos ausentes do JSONB.
- Não fazer consultas TMDB durante a montagem das respostas; respostas devem usar snapshots congelados.
- Não exibir título ou nome do alvo secreto em estado aberto; filmografias não reveladas usam `title: null`.
- Não criar hints para `MOVIE_BY_INFO` ou `SERIES_BY_INFO`; hints antigos devem ser filtrados na resposta.
- Não alterar `.gitignore`, `.m2/` ou `.testcontainers.properties`, que já possuem mudanças locais do usuário.
- Atualizar `openapi.yaml`, `business-rules.md` e `progress.md` depois do código; alterações somente em documentação permanecem sem commit conforme `AGENTS.md`.
- Usar commits Conventional Commits, sem `Co-Authored-By`; nunca executar `git push` sem autorização explícita.

## File Map

| Arquivo | Responsabilidade da alteração |
|---|---|
| `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java` | Aplicar a política de ocultação de imagens e hints e projetar a filmografia redigida. |
| `src/main/java/com/watchwise/watchwise_api/dailygame/generation/MovieByInfoGenerator.java` | Gerar snapshots completos sem hints e rejeitar candidatos sem dados comparáveis. |
| `src/main/java/com/watchwise/watchwise_api/dailygame/generation/SeriesByInfoGenerator.java` | Aplicar a mesma regra ao jogo de séries. |
| `src/main/java/com/watchwise/watchwise_api/dailygame/entity/DailyChallenge.java` | Permitir atualização controlada do `answerSnapshot` pelo reparo. |
| `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyChallengeSnapshotRepairService.java` | Interface do reparo idempotente de snapshots existentes. |
| `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeSnapshotRepairServiceImpl.java` | Reconstruir somente metadados ausentes usando os IDs congelados. |
| `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeGenerationServiceImpl.java` | Acionar o reparo quando a modalidade já existir, sem substituir o desafio. |
| `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssemblerTest.java` | Cobrir exposição de imagens, hints e filmografia no estado inicial. |
| `src/test/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeGeneratorTest.java` | Cobrir geração sem hints e snapshots completos dos jogos de informação. |
| `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeSnapshotRepairServiceImplTest.java` | Cobrir reparo, idempotência, preservação da identidade e falhas externas. |
| `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeGenerationServiceImplTest.java` | Verificar que desafios existentes passam pelo reparo sem nova geração. |
| `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameInfoComparisonServiceTest.java` | Manter e ampliar a prova de que snapshots completos produzem feedback comparável. |
| `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameFilmographyServiceTest.java` | Cobrir filmografia completa, títulos ocultos e obras compartilhadas. |
| `docs/context/openapi.yaml` | Documentar a ausência de imagens/hints em partidas abertas desses tipos. |
| `docs/context/business-rules.md` | Registrar a política de exposição e o reparo dos snapshots legados. |
| `docs/context/progress.md` | Acrescentar a entrega na seção cronológica de 2026-09-30. |

---

### Task 1: Lock the open-state exposure contract with failing assembler tests

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssemblerTest.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java`

**Interfaces:**
- Consumes: `DailyChallenge`, `UserDailyGameResult`, `DailyChallengeHint` and the existing `toState` / `toAttemptResponse` overloads.
- Produces: an internal predicate such as `hidesImageUntilTerminal(DailyGameType)` and a response view that returns `null`/empty image fields and no info-game hints while open.

- [ ] **Step 1: Add a failing test for hidden images in the three reported endpoint families.**

Create one parameterized test using `MOVIE_BY_INFO`, `ACTOR_BY_MOVIE_FILMOGRAPHY` and `ACTOR_BY_SERIES_FILMOGRAPHY`. Build each challenge with an image path, call `assembler.toState(challenge, null, legacyHints, true, true)`, and assert:

```java
assertThat(state.imageUrl()).isNull();
assertThat(state.visibleImageUrls()).isEmpty();
```

Use a terminal `UserDailyGameResult` for the same challenge and assert the terminal `answer().imageUrl()` is still populated.

- [ ] **Step 2: Add a failing test for legacy hints in both information games.**

Pass two `DailyChallengeHint` objects to `toState` for `MOVIE_BY_INFO` and `SERIES_BY_INFO`, then assert `state.hints()` is empty both when the result is absent and when it is in progress. Keep a separate assertion that a poster game still returns its allowed hint prefix.

- [ ] **Step 3: Run only the assembler test class.**

Run:

```powershell
.\mvnw.cmd "-Dtest=DailyChallengeResponseAssemblerTest" test
```

Expected: the new tests fail because `view` currently always exposes the challenge image and limits hints generically.

- [ ] **Step 4: Implement the smallest assembler change.**

In `view`, derive `boolean hiddenImageGame` from the game type. For a non-terminal hidden-image game, return `imageUrl = null` and `visibleImageUrls = List.of()`. Keep terminal `answer` creation unchanged so it reveals the frozen answer. Derive `boolean informationGame` and use `List.of()` instead of `allHints` for those types; leave the existing attempt-based prefix logic for other types.

Do not add TMDB calls or alter `imagePaths` used by `EPISODE_BY_FRAME`.

- [ ] **Step 5: Run the focused test class again.**

Run the same Maven command and expect PASS.

- [ ] **Step 6: Commit the response-policy change.**

```powershell
git add src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssemblerTest.java
git commit -m "fix(daily-game): redact open information game media"
```

Immediately inspect the commit message and confirm it has no `Co-Authored-By` trailer or other self-attribution. Do not push.

### Task 2: Generate information snapshots without hints

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeGeneratorTest.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/MovieByInfoGenerator.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/SeriesByInfoGenerator.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeSnapshotAssembler.java` only if a focused helper is needed to inspect comparability.

**Interfaces:**
- Consumes: the existing TMDB full-detail records and `DailyChallengeSnapshotAssembler.movieInfo` / `seriesInfo`.
- Produces: `DailyChallengeCandidate` with `hints().isEmpty()` and all available comparison fields in `answerSnapshot()`.

- [ ] **Step 1: Update the generator tests to express the new contract.**

For the existing movie and series information-generation tests, change assertions that enumerate hint types to `assertThat(candidate.hints()).isEmpty()`. Retain assertions for `answerSnapshot()` fields such as `platforms`, `genres`, `year`, creators, production companies and seasons. Add a test for a full-detail record with only title and poster but no comparable field; assert generation returns `Optional.empty()`.

- [ ] **Step 2: Run the generator tests to establish the red state.**

Run:

```powershell
.\mvnw.cmd "-Dtest=DailyChallengeGeneratorTest" test
```

Expected: current tests fail because the generators still build and persist hint snapshots.

- [ ] **Step 3: Remove hint construction from `MovieByInfoGenerator`.**

Fetch certification as today, call:

```java
DailyChallengeCandidate candidate = snapshotAssembler.movieInfo(movie, imagePath, List.of(), certification);
return hasComparableInfo(candidate.answerSnapshot()) ? Optional.of(candidate) : Optional.empty();
```

`hasComparableInfo` must require a numeric `year` and at least one additional non-empty comparison field among `platforms`, `genres`, `certification`, `director`, `cast`, `productionCompanies` and `revenue`. This prevents a challenge whose every comparison cell is `NO_DATA` without inventing metadata.

- [ ] **Step 4: Apply the same rule to `SeriesByInfoGenerator`.**

Build the candidate with `List.of()` hints and require a numeric `year` plus one additional non-empty field among `platforms`, `genres`, `certification`, `creators`, `cast`, `productionCompanies` and `seasons`. Keep the existing BR certification and TMDB field selection.

- [ ] **Step 5: Run generation, comparison and response tests.**

Run:

```powershell
.\mvnw.cmd "-Dtest=DailyChallengeGeneratorTest,DailyGameInfoComparisonServiceTest,DailyChallengeResponseAssemblerTest" test
```

Expected: PASS, with new candidates containing comparison data but no persisted hints.

- [ ] **Step 6: Commit the generator change.**

```powershell
git add src/main/java/com/watchwise/watchwise_api/dailygame/generation/MovieByInfoGenerator.java src/main/java/com/watchwise/watchwise_api/dailygame/generation/SeriesByInfoGenerator.java src/test/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeGeneratorTest.java
git commit -m "fix(daily-game): remove information game hints"
```

Confirm there is no `Co-Authored-By` trailer or self-attribution. Do not push.

### Task 3: Add an idempotent legacy snapshot repair service

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyChallengeSnapshotRepairService.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeSnapshotRepairServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/entity/DailyChallenge.java`
- Create: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeSnapshotRepairServiceImplTest.java`

**Interfaces:**
- Consumes: `DailyChallenge`, `TmdbClient`, `DailyChallengeSnapshotAssembler`, `DailyGameFilmographyService`.
- Produces:

```java
public interface DailyChallengeSnapshotRepairService {
    boolean repairIfIncomplete(DailyChallenge challenge);
}
```

The boolean is `true` only when the entity was enriched and saved.

- [ ] **Step 1: Add failing repair tests for legacy information snapshots.**

Create movie and series challenges whose snapshots contain only the old base fields (`targetKind`, `tmdbId`/`title`, `imageUrl`, `sourceTmdbId`). Stub the corresponding full-detail and certification lookups. Assert after repair that:

```java
assertThat(challenge.getAnswerSnapshot()).containsEntry("year", 2000);
assertThat(challenge.getAnswerSnapshot()).containsEntry("genres", List.of("Drama"));
assertThat(challenge.getAnswerSnapshot()).doesNotContainKey("hints");
assertThat(challenge.getAnswerKey()).isEqualTo(originalAnswerKey);
assertThat(challenge.getImagePath()).isEqualTo(originalImagePath);
```

Verify `challengeRepository.saveAndFlush(challenge)` is called exactly once.

- [ ] **Step 2: Add failing repair tests for legacy filmography snapshots.**

For each actor game, build a challenge without `filmography`, stub `DailyGameFilmographyService.snapshot`, and assert the persisted snapshot contains the normalized list. Add a second call asserting no second TMDB lookup or save occurs once the key is complete.

- [ ] **Step 3: Add failing tests for incomplete external data.**

Return an unavailable/not-found full-detail lookup and an empty filmography snapshot. Assert the repair returns `false`, does not call `saveAndFlush`, and leaves the original map unchanged.

- [ ] **Step 4: Implement controlled entity mutation.**

Add a setter only for `answerSnapshot` on `DailyChallenge`, preserving the existing protected no-args constructor and builder. The repair service must copy the existing map into a `LinkedHashMap`, merge only usable missing fields, and call `saveAndFlush` only when at least one expected field was added.

- [ ] **Step 5: Implement movie and series enrichment.**

For information games, fetch the already persisted target ID with the same `en-US` language. Use `DailyChallengeSnapshotAssembler.movieInfo` or `seriesInfo` with `List.of()` to produce the canonical field names, then merge only missing/empty keys. Fetch BR certification through the existing TMDB methods before constructing the candidate snapshot. Treat `NotFound` and `Unavailable` as `false` without clearing the old snapshot.

- [ ] **Step 6: Implement filmography enrichment.**

For actor games, call `filmographyService.snapshot(challenge.getTargetTmdbId(), challenge.getGameType())`. If the returned entries are empty, return `false`; otherwise merge them under `filmography`. Never overwrite a non-empty existing filmography.

- [ ] **Step 7: Run the new repair test class.**

Run:

```powershell
.\mvnw.cmd "-Dtest=DailyChallengeSnapshotRepairServiceImplTest" test
```

Expected: PASS.

### Task 4: Hook repair into existing challenge generation without replacing challenges

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeGenerationServiceImpl.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeGenerationServiceImplTest.java`

**Interfaces:**
- Consumes: `DailyChallengeSnapshotRepairService.repairIfIncomplete(DailyChallenge)`.
- Produces: existing `(challenge_date, game_type)` rows are repaired in place; absent rows follow the existing generation path.

- [ ] **Step 1: Add a failing test for an existing incomplete challenge.**

Stub `existsByChallengeDateAndGameType` as `true`, return the legacy challenge from `findByChallengeDateAndGameType`, and verify `snapshotRepairService.repairIfIncomplete(existing)` is called. Verify the generator and `saveAndFlush` for a new challenge are never called for that modality.

- [ ] **Step 2: Add the repair dependency to the generation service and constructor test fixture.**

Inject `DailyChallengeSnapshotRepairService` beside the repositories. Update all test constructors with the mock. Keep the outer `@Transactional` method and advisory-lock order unchanged.

- [ ] **Step 3: Implement the existing-row branch.**

Change `generateMissingModality` to:

```java
if (challengeRepository.existsByChallengeDateAndGameType(challengeDate, gameType)) {
    challengeRepository.findByChallengeDateAndGameType(challengeDate, gameType)
            .ifPresent(snapshotRepairService::repairIfIncomplete);
    return;
}
```

Do not regenerate, delete, or change the answer key of an existing challenge.

- [ ] **Step 4: Run generation and repair tests together.**

Run:

```powershell
.\mvnw.cmd "-Dtest=DailyChallengeGenerationServiceImplTest,DailyChallengeSnapshotRepairServiceImplTest,DailyGameGenerationJobTest" test
```

Expected: PASS. `DailyGameGenerationJobTest` is located at `src/test/java/com/watchwise/watchwise_api/dailygame/tracking/DailyGameGenerationJobTest.java`; the Maven selector uses its class name directly.

- [ ] **Step 5: Commit the repair integration.**

```powershell
git add src/main/java/com/watchwise/watchwise_api/dailygame/entity/DailyChallenge.java src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyChallengeSnapshotRepairService.java src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeSnapshotRepairServiceImpl.java src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeGenerationServiceImpl.java src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeSnapshotRepairServiceImplTest.java src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeGenerationServiceImplTest.java
git commit -m "fix(daily-game): repair legacy challenge snapshots"
```

Confirm there is no `Co-Authored-By` trailer or self-attribution. Do not push.

### Task 5: Prove filmography projection behavior at the service boundary

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssemblerTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameFilmographyServiceTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImplTest.java` only when a service wiring assertion is needed.

**Interfaces:**
- Consumes: the repaired `answerSnapshot.filmography` and persisted `filmographyFeedback` attempt details.
- Produces: regression coverage for initial redacted rows, shared-work reveal, and series major-role reprojection.

- [ ] **Step 1: Add a failing initial-state assembler test.**

Build a movie filmography challenge with three works and no result. Call `toState` and assert three entries are returned, every `title` is `null`, and `year`, `genres` and `posterUrl` are present. Repeat with a series challenge containing major and guest roles and assert the default `majorRoles=true` projection keeps only eligible major roles.

- [ ] **Step 2: Add a failing shared-work reveal test.**

Persist one `DailyGameAttemptDTO` with a `DailyGameFilmographyFeedbackDTO` containing one shared work key. Call `toState(..., true, true)` and assert exactly that work has its title, `revealed=true` and `highlighted=true`; all other works keep `title=null`.

- [ ] **Step 3: Implement only the smallest projection correction required by the tests.**

Ensure `filmographyEntry` validates the source snapshot title before redacting it, rather than discarding a valid work because the output title is intentionally null. Preserve the existing major-role threshold and the distinction between `sharedMajorRoleWorkKeys` and `sharedAllRoleWorkKeys`.

- [ ] **Step 4: Run filmography and assembler tests.**

Run:

```powershell
.\mvnw.cmd "-Dtest=DailyGameFilmographyServiceTest,DailyChallengeResponseAssemblerTest,DailyGameServiceImplTest" test
```

Expected: PASS, with filmography rows present before any guess and titles revealed only for shared works.

### Task 6: Synchronize contract documentation and progress log

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

**Interfaces:**
- Consumes: the final behavior implemented by Tasks 1–5.
- Produces: documentation that describes the actual runtime contract without adding unsupported routes or fields.

- [ ] **Step 1: Update OpenAPI descriptions.**

Document in `DailyGameStateDTO` and `DailyGameAttemptResponseDTO` that open information and filmography games return `imageUrl: null` and empty `visibleImageUrls`; terminal answers may reveal the frozen image. Document that information-game `hints` are empty and comparisons are delivered through `attempts[].infoFeedback` / `currentAttempt.infoFeedback`.

- [ ] **Step 2: Update business rules.**

Extend the Daily Games section with the game-type image policy, the absence of information hints, the initial redacted filmography projection, and the idempotent generation-time enrichment of legacy snapshots. State explicitly that repair preserves answer identity and does not run during response reads.

- [ ] **Step 3: Append the chronological progress entry.**

Under `## 2026-09-30`, record only the implemented behavior: image/hint redaction, complete filmography projection, information snapshot validation and legacy snapshot repair. Do not add a next-steps section.

- [ ] **Step 4: Review documentation against code and leave docs uncommitted.**

Run:

```powershell
git diff --check -- docs/context/openapi.yaml docs/context/business-rules.md docs/context/progress.md
```

Do not create a documentation-only commit.

### Task 7: Full verification and final handoff

**Files:**
- Verify all files from Tasks 1–6.

**Interfaces:**
- Consumes: all implementation and documentation changes.
- Produces: evidence that the focused behavior and complete Maven suite pass without altering the user’s pre-existing unrelated files.

- [ ] **Step 1: Inspect the complete diff and repository status.**

Run:

```powershell
git diff --stat
git diff --check
git status --short
```

Confirm only daily-game source/tests and the required context documentation changed, aside from the pre-existing `.gitignore`, `.m2/` and `.testcontainers.properties` entries.

- [ ] **Step 2: Run the complete test suite.**

Run:

```powershell
.\mvnw.cmd test
```

Expected: exit code 0 with zero failures and zero errors. Repository tests may require Docker; if Docker is unavailable, report the exact failing test group instead of claiming the suite passed.

- [ ] **Step 3: Inspect final commits for attribution and scope.**

Run:

```powershell
git log -3 --format=fuller
git show --stat --oneline HEAD
```

Confirm each new commit uses a Conventional Commit subject and contains no `Co-Authored-By` trailer. Do not push.

- [ ] **Step 4: Report the result.**

Summarize the four user-visible corrections, list the focused and full test commands with their observed results, link the relevant source files and state that the existing challenge rows are repaired on the next generation-job execution.

## Plan Self-Review

- **Spec coverage:** exposure policy is Task 1; no-hint generation and meaningful snapshots are Task 2; legacy information and filmography repair is Task 3; scheduled integration is Task 4; filmography projection is Task 5; synchronized documentation is Task 6; verification is Task 7.
- **Placeholder scan:** the plan contains no `TODO`, `TBD`, “implement later”, or unspecified validation step. Every task names files, method boundaries, tests and commands.
- **Type consistency:** `repairIfIncomplete(DailyChallenge)` is defined in Task 3 and consumed unchanged in Task 4; snapshot fields remain `Map<String, Object>` and filmography remains `List<Map<String, Object>>`; existing assembler overloads are preserved.
- **Scope check:** all work stays inside the daily-game subsystem and its required contract documentation; no unrelated refactor or route is introduced.
