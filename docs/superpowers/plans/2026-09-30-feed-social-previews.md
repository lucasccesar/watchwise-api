# Feed Social Previews Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enriquecer `DIARY_ENTRY`, `DROPPED`, `PICK_CREATED` e `PICKS_TEMPLATE_CREATED` no `GET /feed` com `likesCount`, `commentsCount` e até três comentários mais recentes do próprio objeto.

**Architecture:** Manter a hierarquia atual do payload. `DIARY_ENTRY` e `DROPPED` receberão os campos sociais no `FeedItemDTO`; Pick e Picks Template receberão `recentComments` nos previews aninhados, preservando seus contadores existentes. Um `CommentPreviewAssembler` fará contagem, seleção limitada por alvo e mapeamento em lote, ativado somente pelo caminho do feed.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, PostgreSQL, MapStruct, JUnit 5, Mockito, AssertJ e Testcontainers.

## Global Constraints

- Use `CommentResponseDTO` como formato dos comentários embutidos, preservando `likesCount` e `likedByMe` de cada comentário.
- Ordene os comentários por `createdAt DESC, id DESC` e retorne no máximo três por alvo.
- Use consultas em lote; não execute uma consulta por item do feed.
- Para um alvo sem comentários, serialize `commentsCount: 0` e `recentComments: []`.
- Não altere a ordenação dos endpoints paginados existentes de comentários.
- Atualize `openapi.yaml`, `business-rules.md` e `progress.md` junto com o código, mas deixe a documentação fora do commit conforme `AGENTS.md`.
- Preserve a alteração preexistente em `.gitignore`; ela não pertence a esta tarefa.
- Não adicione `Co-Authored-By` ao commit.

## File Map

Create:

- `src/main/java/com/watchwise/watchwise_api/comment/service/CommentPreviewData.java` — resultado interno com contagem e comentários recentes.
- `src/main/java/com/watchwise/watchwise_api/comment/service/impl/CommentPreviewAssembler.java` — montagem em lote para DiaryEntry, DroppedEntry, Pick e PicksTemplate.
- `src/test/java/com/watchwise/watchwise_api/comment/service/impl/CommentPreviewAssemblerTest.java` — regras de agrupamento, limite, ordem e likes do viewer.

Modify:

- `src/main/java/com/watchwise/watchwise_api/comment/repository/CommentRepository.java` — contagens por alvo e consultas limitadas aos três mais recentes por alvo.
- `src/main/java/com/watchwise/watchwise_api/feed/dto/FeedItemDTO.java` — `commentsCount` e `recentComments` para DiaryEntry/Dropped.
- `src/main/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImpl.java` — carregar dados sociais dos quatro tipos de evento sem N+1.
- `src/main/java/com/watchwise/watchwise_api/pick/dto/PickPreviewDTO.java` — `recentComments` no preview do Pick.
- `src/main/java/com/watchwise/watchwise_api/pick/service/impl/PickPreviewAssembler.java` — variante usada pelo feed com dados recentes.
- `src/main/java/com/watchwise/watchwise_api/pickstemplate/dto/PicksTemplatePreviewDTO.java` — `recentComments` no preview do template.
- `src/main/java/com/watchwise/watchwise_api/pickstemplate/service/impl/PicksTemplatePreviewAssembler.java` — variante usada pelo feed com dados recentes.
- `src/test/java/com/watchwise/watchwise_api/comment/repository/CommentRepositoryTest.java` — consultas de contagem e top-three por alvo.
- `src/test/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImplTest.java` — campos sociais de DiaryEntry, Dropped e previews de Picks.
- `src/test/java/com/watchwise/watchwise_api/pick/service/impl/PickPreviewAssemblerTest.java` — recent comments do próprio Pick.
- `src/test/java/com/watchwise/watchwise_api/pickstemplate/service/impl/PicksTemplatePreviewAssemblerTest.java` — recent comments do próprio template.
- `src/test/java/com/watchwise/watchwise_api/feed/controller/FeedControllerIntegrationTest.java` — contrato JSON do feed, incluindo arrays vazios.
- `docs/context/openapi.yaml` — propriedades e descrições de FeedItem, PickPreview e PicksTemplatePreview.
- `docs/context/business-rules.md` — regra social do feed e correção da descrição contraditória de Dropped.
- `docs/context/progress.md` — entrada cronológica do comportamento entregue em 2026-09-30.

---

### Task 1: Add bounded comment-preview repository queries

**Files:**

- Modify: `src/main/java/com/watchwise/watchwise_api/comment/repository/CommentRepository.java`
- Test: `src/test/java/com/watchwise/watchwise_api/comment/repository/CommentRepositoryTest.java`

**Interfaces:**

- Produces batch count methods for diary entries, dropped entries, Picks and Picks Templates.
- Produces batch recent-comment methods that return initialized `Comment.user` values and at most three rows per target.

- [ ] **Step 1: Write failing repository tests for the per-target limit and ordering**

Persist four comments for one target and comments for a second target with controlled timestamps. Assert exactly three rows per target, newest-first, with `id DESC` as the equal-timestamp tie-breaker. Cover target isolation for DiaryEntry, DroppedEntry, Pick and PicksTemplate.

- [ ] **Step 2: Run the repository tests and verify the expected failure**

```powershell
.\mvnw.cmd -Duser.home="D:\Users\Lucas C\Documentos\codigos\watchwise-api" -Dmaven.repo.local="C:\Users\Lucas C\.m2\repository" test "-Dtest=CommentRepositoryTest"
```

Expected: compilation or test failure because the new repository methods do not exist yet. If Testcontainers is unavailable, record that environmental failure and continue unit-level work.

- [ ] **Step 3: Add batch count projections and queries**

Add `DiaryCommentCount`, `DroppedCommentCount`, `PickCommentCount` and `TemplateCommentCount` projections and `countBy...IdIn` queries. Preserve existing single-target count methods.

- [ ] **Step 4: Add bounded recent-comment queries**

Add one native PostgreSQL query per target type using `ROW_NUMBER() OVER (PARTITION BY <target_column> ORDER BY created_at DESC, id DESC)`, filter `row_number <= 3`, restrict target IDs, return all comment columns, and ensure `Comment.user` is initialized before mapping. Keep deterministic target and comment ordering.

- [ ] **Step 5: Run the repository tests and verify they pass**

Run the same `CommentRepositoryTest` command. Expected: PASS when Docker/Testcontainers is available.

- [ ] **Step 6: Commit the repository layer**

```powershell
git add src/main/java/com/watchwise/watchwise_api/comment/repository/CommentRepository.java src/test/java/com/watchwise/watchwise_api/comment/repository/CommentRepositoryTest.java
git commit -m "feat(feed): add bounded comment preview queries"
```

After committing, confirm that the message contains no `Co-Authored-By` trailer or other self-attribution.

### Task 2: Build the shared comment-preview assembler

**Files:**

- Create: `src/main/java/com/watchwise/watchwise_api/comment/service/CommentPreviewData.java`
- Create: `src/main/java/com/watchwise/watchwise_api/comment/service/impl/CommentPreviewAssembler.java`
- Test: `src/test/java/com/watchwise/watchwise_api/comment/service/impl/CommentPreviewAssemblerTest.java`

**Interfaces:**

- Consumes Task 1 repository methods, `LikeService.getLikedCommentIds` and `CommentMapper.commentToResponseDto`.
- Produces `Map<UUID, CommentPreviewData>` for each target type, keyed by target ID.

- [ ] **Step 1: Write failing unit tests for empty input and targets without comments**

Assert that an empty ID collection returns an empty map without repository calls, and that a target with no comments produces `CommentPreviewData(0, List.of())`.

- [ ] **Step 2: Run the focused assembler test and verify it fails**

```powershell
.\mvnw.cmd -Duser.home="D:\Users\Lucas C\Documentos\codigos\watchwise-api" -Dmaven.repo.local="C:\Users\Lucas C\.m2\repository" test "-Dtest=CommentPreviewAssemblerTest"
```

Expected: compilation failure because the assembler and data type do not exist yet.

- [ ] **Step 3: Define the internal preview data type**

Create:

```java
public record CommentPreviewData(
        long commentsCount,
        List<CommentResponseDTO> recentComments
) { }
```

- [ ] **Step 4: Implement batch assembly for the four target types**

Each method short-circuits empty inputs, loads counts and at most three comments in batch, collects returned comment IDs, calls `LikeService.getLikedCommentIds(viewerId, commentIds)` once, maps through `CommentMapper`, groups by target ID, and fills absent targets with zero and an empty list.

- [ ] **Step 5: Add unit tests for mapping, limit, order, target isolation and viewer likes**

Use at least three comments for one target and one for another. Assert no more than three, repository order preserved, `likedByMe` only for IDs returned by `LikeService`, and no cross-target mixing. Cover all four assembly methods.

- [ ] **Step 6: Run the focused assembler test and verify it passes**

Run the `CommentPreviewAssemblerTest` command from Step 2. Expected: PASS.

- [ ] **Step 7: Commit the shared assembler**

```powershell
git add src/main/java/com/watchwise/watchwise_api/comment/service/CommentPreviewData.java src/main/java/com/watchwise/watchwise_api/comment/service/impl/CommentPreviewAssembler.java src/test/java/com/watchwise/watchwise_api/comment/service/impl/CommentPreviewAssemblerTest.java
git commit -m "feat(feed): assemble recent comment previews"
```

After committing, confirm that the message contains no `Co-Authored-By` trailer or other self-attribution.

### Task 3: Extend the feed and nested preview DTOs

**Files:**

- Modify: `src/main/java/com/watchwise/watchwise_api/feed/dto/FeedItemDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/pick/dto/PickPreviewDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/pickstemplate/dto/PicksTemplatePreviewDTO.java`
- Test: `src/test/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/pick/service/impl/PickPreviewAssemblerTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/pickstemplate/service/impl/PicksTemplatePreviewAssemblerTest.java`

**Interfaces:**

- `FeedItemDTO` exposes `Integer commentsCount` and `List<CommentResponseDTO> recentComments` for DiaryEntry/Dropped events.
- The two nested preview records expose `List<CommentResponseDTO> recentComments` beside existing `likesCount` and `commentsCount`.
- Existing non-feed construction paths default recent comments to an empty list; feed-specific construction supplies the assembled comments.

- [ ] **Step 1: Add failing DTO assertions**

Assert that records expose `recentComments`, social previews retain their own list, and existing non-feed construction produces an empty list rather than `null`.

- [ ] **Step 2: Run focused tests and verify the expected failure**

```powershell
.\mvnw.cmd -Duser.home="D:\Users\Lucas C\Documentos\codigos\watchwise-api" -Dmaven.repo.local="C:\Users\Lucas C\.m2\repository" test "-Dtest=FeedServiceImplTest,PickPreviewAssemblerTest,PicksTemplatePreviewAssemblerTest"
```

Expected: compilation failure because the new record components and constructor arguments are absent.

- [ ] **Step 3: Add record components and compatibility constructors**

Add `commentsCount` and `recentComments` to `FeedItemDTO`, and `recentComments` to the two nested preview records. Update call sites and constructors so unsupported feed event types keep root social fields `null`, while supported nested previews use `List.of()` when empty.

- [ ] **Step 4: Run the focused tests and fix compilation-only fallout**

Run the same focused command and update only affected fixtures and assertions; do not implement loading logic in this task.

- [ ] **Step 5: Commit the DTO contract**

```powershell
git add src/main/java/com/watchwise/watchwise_api/feed/dto/FeedItemDTO.java src/main/java/com/watchwise/watchwise_api/pick/dto/PickPreviewDTO.java src/main/java/com/watchwise/watchwise_api/pickstemplate/dto/PicksTemplatePreviewDTO.java src/test/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImplTest.java src/test/java/com/watchwise/watchwise_api/pick/service/impl/PickPreviewAssemblerTest.java src/test/java/com/watchwise/watchwise_api/pickstemplate/service/impl/PicksTemplatePreviewAssemblerTest.java
git commit -m "feat(feed): expose social comment preview fields"
```

After committing, confirm that the message contains no `Co-Authored-By` trailer or other self-attribution.

### Task 4: Integrate previews into FeedService without widening other endpoints

**Files:**

- Modify: `src/main/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/pick/service/impl/PickPreviewAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/pickstemplate/service/impl/PicksTemplatePreviewAssembler.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImplTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/pick/service/impl/PickPreviewAssemblerTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/pickstemplate/service/impl/PicksTemplatePreviewAssemblerTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/feed/controller/FeedControllerIntegrationTest.java`

**Interfaces:**

- FeedService consumes `CommentPreviewAssembler` with the authenticated viewer ID.
- Pick and template preview assemblers expose a feed-specific assembly path that enriches previews while retaining the existing default path for non-feed endpoints.

- [ ] **Step 1: Write failing service tests for each supported event**

Extend `FeedServiceImplTest` so DiaryEntry and DroppedEntry receive their count and own three comments, Top5 keeps social fields `null`, `PICK_CREATED` keeps distinct Pick and template lists, and `PICKS_TEMPLATE_CREATED` receives the template's own list.

- [ ] **Step 2: Run focused feed tests and verify the expected failure**

```powershell
.\mvnw.cmd -Duser.home="D:\Users\Lucas C\Documentos\codigos\watchwise-api" -Dmaven.repo.local="C:\Users\Lucas C\.m2\repository" test "-Dtest=FeedServiceImplTest,PickPreviewAssemblerTest,PicksTemplatePreviewAssemblerTest"
```

Expected: failure because FeedService and feed-specific preview assembly do not consume `CommentPreviewAssembler` yet.

- [ ] **Step 3: Enrich DiaryEntry and Dropped feed items**

Load comment previews for the trimmed DiaryEntry and DroppedEntry ID collections in the existing batch section of `getFeed`. Pass matching `CommentPreviewData` to the mapping methods, keeping the existing batch likes lookup and empty-array behavior.

- [ ] **Step 4: Add feed-specific Pick and Template preview assembly**

Use the shared assembler only in the feed path. Preserve existing counts and liked state, enrich only `recentComments`, and ensure a Pick gets Pick comments while its related template gets template comments. Do not execute recent-comment queries in ordinary Pick or Template listing paths.

- [ ] **Step 5: Run focused tests and verify they pass**

Run the focused command from Step 2. Expected: PASS, including target-isolation assertions.

- [ ] **Step 6: Run feed controller tests**

```powershell
.\mvnw.cmd -Duser.home="D:\Users\Lucas C\Documentos\codigos\watchwise-api" -Dmaven.repo.local="C:\Users\Lucas C\.m2\repository" test "-Dtest=FeedControllerTest,FeedControllerIntegrationTest"
```

Expected: PASS, including `commentsCount: 0`, `recentComments: []` and `null` social fields for `TOP5_UPDATE`.

- [ ] **Step 7: Commit the feed integration**

```powershell
git add src/main/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImpl.java src/main/java/com/watchwise/watchwise_api/pick/service/impl/PickPreviewAssembler.java src/main/java/com/watchwise/watchwise_api/pickstemplate/service/impl/PicksTemplatePreviewAssembler.java src/test/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImplTest.java src/test/java/com/watchwise/watchwise_api/pick/service/impl/PickPreviewAssemblerTest.java src/test/java/com/watchwise/watchwise_api/pickstemplate/service/impl/PicksTemplatePreviewAssemblerTest.java src/test/java/com/watchwise/watchwise_api/feed/controller/FeedControllerIntegrationTest.java
git commit -m "feat(feed): include recent social comments"
```

After committing, confirm that the message contains no `Co-Authored-By` trailer or other self-attribution.

### Task 5: Synchronize API and project documentation

**Files:**

- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

- [ ] **Step 1: Update the OpenAPI contract**

Document `commentsCount` and `recentComments` on `FeedItem`, `PickPreview` and `PicksTemplatePreview`. State which event types populate each field, the three-item newest-first limit, and that entries use `CommentResponseDTO`. Correct the feed description so DiaryEntry, DroppedEntry, Pick and PicksTemplate are social targets while Top5 remains non-social.

- [ ] **Step 2: Update business rules**

Add the bounded recent-comment rule, Pick/template target isolation, empty-array behavior and batch-loading constraint. Rewrite the older contradictory statement that excludes DroppedEntry from social interactions.

- [ ] **Step 3: Append the shipped behavior to progress.md**

Add a `## 2026-09-30 — ...` entry describing counters, three-comment previews, target isolation and batch loading. Do not add a next-steps section.

- [ ] **Step 4: Review the documentation diff**

```powershell
git diff -- docs/context/openapi.yaml docs/context/business-rules.md docs/context/progress.md
```

Expected: only the feed social-preview contract and chronological shipped-progress entry change. Leave documentation uncommitted unless explicitly requested.

### Task 6: Full verification and handoff

- [ ] **Step 1: Run the focused suite**

```powershell
.\mvnw.cmd -Duser.home="D:\Users\Lucas C\Documentos\codigos\watchwise-api" -Dmaven.repo.local="C:\Users\Lucas C\.m2\repository" test "-Dtest=CommentRepositoryTest,CommentPreviewAssemblerTest,FeedServiceImplTest,FeedControllerTest,FeedControllerIntegrationTest,PickPreviewAssemblerTest,PicksTemplatePreviewAssemblerTest"
```

Expected: PASS when Docker/Testcontainers is available; otherwise report exact container-dependent failures.

- [ ] **Step 2: Run the complete test suite**

```powershell
.\mvnw.cmd -Duser.home="D:\Users\Lucas C\Documentos\codigos\watchwise-api" -Dmaven.repo.local="C:\Users\Lucas C\.m2\repository" test
```

Expected: all tests pass, or failures are reported with exact output and separated from Docker failures.

- [ ] **Step 3: Inspect the final worktree**

Run `git status --short` and `git diff --check`. Confirm `.gitignore`'s pre-existing modification is untouched, documentation remains a worktree change, and feature commits contain only code/tests.

- [ ] **Step 4: Report the result**

Summarize the JSON fields, supported event types, actual test outcomes, local commit hashes, and that no push was performed.
