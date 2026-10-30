# Discover Lists Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adicionar `GET /lists/discover` para listar listas públicas ordenadas por curtidas, com no máximo 10 listas por página.

**Architecture:** O caso de uso ficará no `UserListService`, usando uma consulta derivada do `UserListRepository` que filtra `PUBLIC` e ordena pelo contador desnormalizado de curtidas. A montagem de `UserListResponseDTO` será compartilhada pelo `mapToResponseDtoPage` existente, preservando previews, contadores, progresso e `likedByMe` em lote.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL, JUnit 5, Mockito, MockMvc, Testcontainers e Maven Wrapper.

## Global Constraints

- A rota é `GET /lists/discover` e exige autenticação.
- Apenas listas com `visibility = PUBLIC` são elegíveis.
- A ordenação é `likesCount DESC`, depois `createdAt DESC` e `id DESC`.
- `page` usa a paginação existente: omitido ou `0` indica a primeira página; valores positivos são 1-based.
- `size` omitido usa 10; valores de 1 a 9 retornam `400`; valores acima de 10 são limitados a 10.
- O filtro e a paginação devem acontecer no banco, antes da montagem da resposta.
- Não criar entidade, DTO, migration ou nova política global de segurança.
- Atualizar `docs/context/openapi.yaml`, `docs/context/business-rules.md` e `docs/context/progress.md`; documentação permanece fora de commits conforme as regras do repositório.
- Preservar as alterações não relacionadas já presentes no worktree (`.gitignore`, `.m2/`, `.obsidian/` e `.testcontainers.properties`).

## File Map

- Modify `src/main/java/com/watchwise/watchwise_api/userlist/service/UserListService.java`: declarar o caso de uso paginado.
- Modify `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java`: validar o tamanho específico, consultar listas públicas e reutilizar o assembler existente.
- Modify `src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListRepository.java`: expor a consulta paginada ordenada por likes, data e UUID.
- Modify `src/main/java/com/watchwise/watchwise_api/userlist/controller/UserListController.java`: expor `GET /lists/discover` com o envelope de página padrão.
- Modify `src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java`: cobrir delegação, mapeamento e limites de paginação.
- Modify `src/test/java/com/watchwise/watchwise_api/userlist/repository/UserListRepositoryTest.java`: cobrir filtro de visibilidade, ordenação e paginação real no PostgreSQL.
- Modify `src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerTest.java`: cobrir resolução do viewer e envelope HTTP do controller.
- Modify `src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerIntegrationTest.java`: cobrir autenticação, visibilidade, ordenação e teto de 10 itens.
- Modify `docs/context/openapi.yaml`: documentar a rota e seus parâmetros/respostas.
- Modify `docs/context/business-rules.md`: registrar a regra de discover.
- Modify `docs/context/progress.md`: adicionar a entrada cronológica de 2026-10-01.

### Task 1: Add the service and repository behavior with tests first

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/userlist/repository/UserListRepositoryTest.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/service/UserListService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListRepository.java`

**Interfaces:**
- Produces `Page<UserListResponseDTO> UserListService.getDiscoverLists(UUID viewerId, Integer pageNumber, Integer pageSize)`.
- Produces `Page<UserList> UserListRepository.findByVisibilityOrderByLikesCountDescCreatedAtDescIdDesc(UserListVisibility visibility, Pageable pageable)`.

- [ ] **Step 1: Write the failing service tests**

Add a `getDiscoverLists` section to `UserListServiceImplTest`. Cover the successful path with a `PageImpl` containing one public list, stub the existing batch collaborators used by `mapToResponseDtoPage`, call:

```java
Page<UserListResponseDTO> result = userListService.getDiscoverLists(lucasId, 1, 10);
```

Verify that the result contains the mapped DTO and that the repository receives `UserListVisibility.PUBLIC` plus a `PageRequest` with page number `0` and page size `10`:

```java
verify(userListRepository).findByVisibilityOrderByLikesCountDescCreatedAtDescIdDesc(
        eq(UserListVisibility.PUBLIC), pageRequestCaptor.capture());
assertThat(pageRequestCaptor.getValue().getPageNumber()).isZero();
assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(10);
```

Add one test for `pageSize == null` and `pageSize > 10`, asserting that both calls use page size 10, and one test for `pageSize == 9` asserting `BadRequestException` with message `Page size must be greater than or equal to 10` and no repository interaction.

- [ ] **Step 2: Write the failing repository test**

In `UserListRepositoryTest`, persist public lists with different `likesCount` values, a public tie with a later `createdAt`, and a private list with the highest count. Clear the persistence context and call the new repository method:

```java
Page<UserList> result = userListRepository.findByVisibilityOrderByLikesCountDescCreatedAtDescIdDesc(
        UserListVisibility.PUBLIC, PageRequest.of(0, 10));
```

Assert that the private list is absent, public lists are ordered by likes descending, equal-like lists are ordered by creation time descending, and `totalElements` counts only public lists. Add a second assertion using `PageRequest.of(0, 1)` that only one row is returned and `totalPages` reflects the public-list count.

- [ ] **Step 3: Run the tests and verify the RED state**

Run:

```powershell
.\mvnw.cmd test "-Dtest=UserListServiceImplTest,UserListRepositoryTest"
```

Expected: compilation/test failure because the service method and repository method do not exist yet. Do not change the tests to accommodate that failure.

- [ ] **Step 4: Add the service contract and repository query**

Declare the service method in `UserListService`. Add this derived Spring Data method to `UserListRepository`:

```java
Page<UserList> findByVisibilityOrderByLikesCountDescCreatedAtDescIdDesc(
        UserListVisibility visibility, Pageable pageable);
```

The derived method must use the entity property names `visibility`, `likesCount`, `createdAt`, and `id`; do not count rows from `Like` at read time.

- [ ] **Step 5: Implement the minimal service behavior**

Add to `UserListServiceImpl`:

```java
@Override
public Page<UserListResponseDTO> getDiscoverLists(UUID viewerId, Integer pageNumber, Integer pageSize) {
    validateUserListPageSize(pageSize);
    PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize, USER_LIST_PAGE_SIZE);
    Page<UserList> lists = userListRepository
            .findByVisibilityOrderByLikesCountDescCreatedAtDescIdDesc(UserListVisibility.PUBLIC, pageRequest);
    return mapToResponseDtoPage(lists, viewerId, null);
}
```

Keep `validateUserListPageSize` and `USER_LIST_PAGE_SIZE` as the shared list-page rule already used by `getUserLists`. Do not add a second response-mapping implementation.

- [ ] **Step 6: Run the tests and verify the GREEN state**

Run the same focused command:

```powershell
.\mvnw.cmd test "-Dtest=UserListServiceImplTest,UserListRepositoryTest"
```

Expected: the new service and repository tests pass, with no failures in the existing tests from those classes.

- [ ] **Step 7: Commit the code and tests**

Review the diff, stage only the Java production/test files from this task, and create:

```powershell
git add src/main/java/com/watchwise/watchwise_api/userlist/service/UserListService.java src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListRepository.java src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java src/test/java/com/watchwise/watchwise_api/userlist/repository/UserListRepositoryTest.java
git commit -m "feat(userlist): add discover list query"
```

The commit must not include a `Co-Authored-By` trailer or other self-attribution.

### Task 2: Expose the authenticated controller endpoint with tests first

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerTest.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/controller/UserListController.java`

**Interfaces:**
- Consumes `UserListService.getDiscoverLists(UUID, Integer, Integer)` from Task 1.
- Produces `ResponseEntity<PageResponseDTO<UserListResponseDTO>> UserListController.getDiscoverLists(Integer page, Integer size)` mapped to `/lists/discover`.

- [ ] **Step 1: Write the failing controller tests**

Add a test that stubs `userListService.getDiscoverLists(currentUserId, 1, 10)`, calls `userListController.getDiscoverLists(1, 10)`, and asserts `HttpStatus.OK` plus the DTO in `body().content()`. Add a second test that calls the method with `null, null` and verifies the exact viewer ID from `SecurityContextHolder` is passed to the service.

- [ ] **Step 2: Run the controller tests and verify the RED state**

Run:

```powershell
.\mvnw.cmd test "-Dtest=UserListControllerTest"
```

Expected: compilation failure because `UserListController.getDiscoverLists` does not exist yet.

- [ ] **Step 3: Implement the controller route**

Add this method to `UserListController`:

```java
@GetMapping("/lists/discover")
public ResponseEntity<PageResponseDTO<UserListResponseDTO>> getDiscoverLists(
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
) {
    Page<UserListResponseDTO> lists = userListService.getDiscoverLists(getCurrentUserId(), page, size);
    return ResponseEntity.ok(PageResponseDTO.of(lists));
}
```

Place it with the other list-reading endpoints. Do not modify `SecurityConfig`: `anyRequest().authenticated()` already enforces the required access policy.

- [ ] **Step 4: Run the controller tests and verify the GREEN state**

Run `.\mvnw.cmd test "-Dtest=UserListControllerTest"` and verify all tests in the class pass.

- [ ] **Step 5: Commit the controller change**

```powershell
git add src/main/java/com/watchwise/watchwise_api/userlist/controller/UserListController.java src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerTest.java
git commit -m "feat(userlist): expose discover lists endpoint"
```

The commit must not include a `Co-Authored-By` trailer or other self-attribution.

### Task 3: Verify the HTTP contract with PostgreSQL integration tests

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerIntegrationTest.java`

**Interfaces:**
- Consumes `GET /lists/discover` from Task 2 through the real controller, service, repository, mapper and batch readers.

- [ ] **Step 1: Add integration tests before running them**

Add a helper:

```java
private MockHttpServletRequestBuilder getDiscoverListsRequest(RegisteredUser viewer) {
    return get("/lists/discover").cookie(viewer.accessToken());
}
```

Add tests with these exact behaviors:

1. Persist public lists with counts `3` and `10`, a private list with count `100`, and a followers-only list with count `50`; call the route as an authenticated viewer and assert only the two public lists are returned in `10` then `3` order.
2. Persist 11 public lists, set their `likesCount` values, call with `page=1&size=20`, and assert `content.length() == 10`, `totalPages == 2`, and `hasNext == true`, proving the server-side cap.
3. Call `/lists/discover` without an access-token cookie and assert `401`.
4. Call with `size=9` and assert `400` with the project `ApiError` shape.

When setting counters in these tests, update the persisted `UserList.likesCount` through the existing entity setter and repository save so the test verifies the read model used by discover. Do not infer ranking from inserting `Like` rows, because the endpoint intentionally reads the maintained counter.

- [ ] **Step 2: Run the focused integration tests**

Run:

```powershell
.\mvnw.cmd test "-Dtest=UserListControllerIntegrationTest"
```

Expected: all existing and new `UserListControllerIntegrationTest` cases pass. If Docker/Testcontainers is unavailable, record that environmental limitation and run the service/controller unit tests plus the compile check instead; do not weaken the assertions.

- [ ] **Step 3: Commit the integration tests**

```powershell
git add src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerIntegrationTest.java
git commit -m "test(userlist): cover discover lists contract"
```

The commit must not include a `Co-Authored-By` trailer or other self-attribution.

### Task 4: Synchronize the API and business documentation

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

**Interfaces:**
- Documents the implementation from Tasks 1–3 without changing route behavior.

- [ ] **Step 1: Document the endpoint in OpenAPI**

Insert `/lists/discover` in the Lists section before `/lists/{listId}`. Document a `GET` operation with tag `Lists`, the authenticated policy, `page` using the shared parameter, and an inline `size` parameter with description and schema `minimum: 10`, `maximum: 10`, and `default: 10`. Describe the `200` response as `PageMeta` plus `UserList` content, `400` for invalid page/size, and `401` for missing authentication. State that only `PUBLIC` lists are returned and sorting is fixed to likes descending, creation time descending, and UUID descending.

- [ ] **Step 2: Document the business rule**

Under `## UserList` in `business-rules.md`, add a rule pointing to `UserListServiceImpl.getDiscoverLists` and `UserListRepository.findByVisibilityOrderByLikesCountDescCreatedAtDescIdDesc`: discover filters `PUBLIC` in the database, uses the denormalized `likesCount`, applies deterministic tie-breakers, and reuses batched response enrichment for the authenticated viewer. Mention that `FOLLOWERS` and `PRIVATE` lists are excluded regardless of owner profile visibility.

- [ ] **Step 3: Append the chronological progress entry**

Under the existing `## 2026-10-01` entries in `progress.md`, add only what shipped: the authenticated `GET /lists/discover` endpoint, its public-only likes ranking and 10-item page cap, plus the focused/integration test coverage. Do not add a next-steps section.

- [ ] **Step 4: Review documentation and leave it uncommitted**

Run:

```powershell
rg -n "lists/discover|discover.*list|lista.*discover|likesCount DESC" docs/context/openapi.yaml docs/context/business-rules.md docs/context/progress.md
git diff -- docs/context/openapi.yaml docs/context/business-rules.md docs/context/progress.md
```

Keep these documentation changes in the worktree and do not include them in the code commits, as required by `AGENTS.md`.

### Task 5: Run final verification and request review

**Files:**
- No new files. Verify all modified Java files and the documentation diff.

- [ ] **Step 1: Run the complete focused regression command**

Run:

```powershell
.\mvnw.cmd test "-Dtest=UserListServiceImplTest,UserListRepositoryTest,UserListControllerTest,UserListControllerIntegrationTest"
```

Expected: exit code 0 and zero failures/errors. If the integration portion is blocked by Docker, run the two unit-test classes separately and report the integration limitation explicitly.

- [ ] **Step 2: Run the Maven compile/package verification**

Run:

```powershell
.\mvnw.cmd -DskipTests compile
```

Expected: exit code 0, including MapStruct compilation with no unmapped-field errors.

- [ ] **Step 3: Inspect the final diff and status**

Run:

```powershell
git diff --check
git status --short
git diff HEAD~3..HEAD --stat
```

Confirm the feature code is committed, documentation remains uncommitted, unrelated pre-existing worktree changes are untouched, and no `Co-Authored-By` trailer appears in the feature commits.

- [ ] **Step 4: Dispatch the required independent code review**

Use the requesting-code-review workflow with the base commit immediately before Task 1 and the final feature commit as `HEAD`. Give the reviewer this requirement set: authenticated `GET /lists/discover`; only public lists; ordering by `likesCount DESC, createdAt DESC, id DESC`; effective page size 10 with the documented 400/clamp behavior; response assembled through existing batching; no visibility leak; tests and docs synchronized. Fix Critical/Important findings before reporting completion.
