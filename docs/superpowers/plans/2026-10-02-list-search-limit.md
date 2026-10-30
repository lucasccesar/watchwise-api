# Limite da pesquisa de listas Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fazer a busca local de listas usar exatamente 10 itens por página, retornando menos apenas quando a página não tiver 10 listas disponíveis.

**Architecture:** Manter a paginação geral usada pelos demais tipos de busca e criar uma `PageRequest` específica para listas com página solicitada e tamanho fixo de 10. Usar essa paginação em `type=LIST` e no array `lists` da busca agregada sem tipo, sem alterar visibilidade, ordenação ou enriquecimento.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, JUnit 5, Mockito, Maven Wrapper.

## Global Constraints

- Não alterar a paginação de filmes, séries, pessoas, usuários ou templates.
- Preservar a visibilidade atual das listas e o escape de curingas da busca.
- Seguir o fluxo TDD: teste falhando antes do código de produção.
- Não incluir `Co-Authored-By` ou outra autoatribuição em commits.
- Preservar alterações preexistentes no worktree.

---

### Task 1: Provar o tamanho fixo da busca de listas

**Files:**
- Test: `src/test/java/com/watchwise/watchwise_api/search/service/impl/SearchServiceImplTest.java`

**Interfaces:**
- Consumes: `SearchServiceImpl.search(UUID, String, SearchType, Integer, Integer)` e a captura do `Pageable` enviado a `UserListRepository`.
- Produces: regressão que exige `Pageable.getPageSize() == 10` para busca `LIST`, independentemente do `size` recebido.

- [ ] **Step 1: Escrever o teste falhando**

Alterar o teste existente `shouldClampLocalPageAndPassViewerScopeWhenSearchingLists` para continuar usando página 2 e `size=99`, mas esperar tamanho 10 em vez de 20. Manter a verificação de página zero-based e de `viewerId`/query. Adicionar uma segunda chamada no mesmo teste com `size=1`, capturar os dois `Pageable`s e exigir tamanho 10 em ambos, deixando explícito que o parâmetro não reduz nem aumenta a página de listas.

- [ ] **Step 2: Rodar o teste e confirmar a falha esperada**

Executar:

```powershell
.\mvnw.cmd test "-Dtest=SearchServiceImplTest#shouldClampLocalPageAndPassViewerScopeWhenSearchingLists"
```

Esperado: falha nas asserções de tamanho porque a implementação atual envia tamanho 20 ao repositório.

### Task 2: Fixar a página de listas em 10

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/search/service/impl/SearchServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/search/service/impl/SearchServiceImplTest.java`

**Interfaces:**
- Consumes: a página e o tamanho recebidos em `search`.
- Produces: chamadas a `searchLists` com uma `PageRequest` de tamanho 10 e a mesma página solicitada.

- [ ] **Step 1: Implementar a menor mudança necessária**

Adicionar uma constante privada `LIST_SEARCH_PAGE_SIZE = 10`. Depois de construir a paginação geral, construir:

```java
PageRequest listPageRequest = pageRequestFactory.build(
        pageNumber,
        LIST_SEARCH_PAGE_SIZE,
        LIST_SEARCH_PAGE_SIZE);
```

Usar `listPageRequest` nos dois pontos que pesquisam listas: o ramo `type == null` e o ramo `SearchType.LIST`. Continuar usando `pageRequest` e `resultLimit` para os outros tipos.

- [ ] **Step 2: Rodar o teste específico e confirmar a passagem**

Executar novamente:

```powershell
.\mvnw.cmd test "-Dtest=SearchServiceImplTest#shouldClampLocalPageAndPassViewerScopeWhenSearchingLists"
```

Esperado: PASS.

- [ ] **Step 3: Rodar toda a classe de serviço**

Executar:

```powershell
.\mvnw.cmd test "-Dtest=SearchServiceImplTest"
```

Esperado: todos os testes da classe passam, incluindo as buscas agregadas e os outros tipos.

### Task 3: Sincronizar documentação e validar a mudança completa

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

**Interfaces:**
- Consumes: comportamento validado de `SearchServiceImpl` e o contrato atual de `GET /search`.
- Produces: documentação dizendo que o array `lists` usa no máximo 10 itens por página, sem alterar o contrato dos outros arrays.

- [ ] **Step 1: Atualizar o contrato OpenAPI**

Na descrição do parâmetro `size` de `/search`, substituir a afirmação de que todos os arrays usam 20 por uma descrição que preserve o comportamento dos demais arrays e registre que `lists` usa tamanho fixo 10 por página.

- [ ] **Step 2: Registrar a regra de negócio**

Adicionar a regra na seção de Search de `business-rules.md`, apontando para `SearchServiceImpl.search` e deixando claro que a busca agregada sem `type` também limita `lists` a 10.

- [ ] **Step 3: Registrar o trabalho entregue no progresso**

Adicionar uma entrada na seção cronológica de `2026-10-02` em `progress.md`, sem criar seção de próximos passos.

- [ ] **Step 4: Executar a verificação final da busca**

Executar:

```powershell
.\mvnw.cmd test "-Dtest=SearchServiceImplTest,SearchControllerTest,SearchControllerIntegrationTest,SearchEndToEndIntegrationTest"
```

Esperado: exit code 0 e nenhuma falha. Se os testes Testcontainers exigirem Docker indisponível, registrar o bloqueio com a saída real, sem declarar a suíte como aprovada.

- [ ] **Step 5: Revisar o diff e separar alterações preexistentes**

Executar `git diff --check` e `git diff --stat`; confirmar que somente os arquivos da implementação, testes e documentação da mudança foram alterados pelo trabalho atual. Não remover nem reverter as alterações preexistentes encontradas no início.

- [ ] **Step 6: Criar commit da mudança de código**

Após a verificação, criar um commit local contendo apenas código e testes com:

```powershell
git add src/main/java/com/watchwise/watchwise_api/search/service/impl/SearchServiceImpl.java src/test/java/com/watchwise/watchwise_api/search/service/impl/SearchServiceImplTest.java
git commit -m "feat(search): limit list results"
```

As alterações de documentação permanecem no worktree conforme a convenção do projeto. Depois do commit, verificar explicitamente que não existe trailer `Co-Authored-By`. Não executar `git push` sem autorização explícita.
