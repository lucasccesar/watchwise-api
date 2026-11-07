# Interações sociais em listas-de-listas Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** Permitir comentários e likes em qualquer \`UserList\` visível, incluindo listas travadas como listas-de-listas.

**Architecture:** Remover somente as validações de tipo que bloqueiam o alvo \`UserList\`. Os fluxos existentes de visibilidade, replies, notificações, idempotência, contadores e estado \`likedByMe\` continuam sendo reutilizados. Não haverá mudança de banco, DTO, mapper ou endpoint.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, JUnit 5, Mockito, MockMvc, PostgreSQL/Testcontainers e Maven Wrapper.

## Global Constraints

- Seguir TDD: cada mudança de comportamento deve ter um teste que falhe antes da implementação.
- Preservar \`PUBLIC\`, \`FOLLOWERS\` e \`PRIVATE\` exatamente como estão hoje.
- Preservar a idempotência dos likes e a atualização transacional de \`likesCount\`.
- Preservar replies somente quando \`parentCommentId\` apontar para um comentário da mesma lista.
- Não criar migration, coluna, DTO, endpoint ou nova regra de visibilidade.
- Atualizar \`openapi.yaml\`, \`business-rules.md\`, \`progress.md\` e \`AGENTS.md\` junto com o código.
- Deixar documentação fora do commit, conforme a convenção do repositório; o commit conterá somente código e testes.
- Usar Conventional Commits sem trailer \`Co-Authored-By\`.

---

### Task 1: Escrever os testes que demonstram a nova regra

**Files:**
- Modify: \`src/test/java/com/watchwise/watchwise_api/comment/service/impl/CommentServiceImplTest.java\`
- Modify: \`src/test/java/com/watchwise/watchwise_api/like/service/impl/LikeServiceImplTest.java\`
- Modify: \`src/test/java/com/watchwise/watchwise_api/comment/controller/CommentControllerIntegrationTest.java\`
- Modify: \`src/test/java/com/watchwise/watchwise_api/like/controller/LikeControllerIntegrationTest.java\`

**Interfaces:**
- Consumes: os endpoints e serviços atuais de comentários e likes em \`UserList\`.
- Produces: testes que exigem sucesso quando a lista possui um \`UserListItem.childListId\`.

- [ ] **Step 1: Alterar o teste unitário de comentário para esperar sucesso**

Converter o teste que espera \`BadRequestException\` em um teste de sucesso, mantendo temporariamente o stub da trava antiga para obter uma falha RED:

\`\`\`java
when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(true);
when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
when(commentMapper.commentToResponseDto(any(Comment.class), eq(false))).thenReturn(responseDto);

CommentResponseDTO result = commentService.createCommentOnList(
        lucasId, listId, new CommentCreationDTO("Nice picks", null, null));

assertThat(result).isEqualTo(responseDto);
verify(commentRepository).save(any(Comment.class));
\`\`\`

- [ ] **Step 2: Alterar o teste unitário de like para esperar sucesso**

Converter o teste que espera \`BadRequestException\` em um teste de sucesso, mantendo temporariamente:

\`\`\`java
when(likeRepository.existsByUserIdAndListId(marinaId, listId)).thenReturn(false);
when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(true);
when(userRepository.getReferenceById(marinaId)).thenReturn(marina);
when(userListRepository.getReferenceById(listId)).thenReturn(scifi);

likeService.likeList(marinaId, listId);

verify(likeRepository).saveAndFlush(any(Like.class));
verify(userListRepository).incrementLikesCount(listId);
\`\`\`

- [ ] **Step 3: Cobrir a leitura de comentários da lista-de-listas**

Adicionar ao bloco \`GET /lists/{listId}/comments\` um caso que persista lista pai, lista filha e comentário na lista pai. A resposta deve conter o comentário e \`totalElements = 1\`.

- [ ] **Step 4: Converter os testes de integração que esperam \`400\`**

No teste de comentário, esperar \`201\`, validar \`$.listId\` e confirmar a persistência. No teste de like, esperar \`204\` e confirmar \`existsByUserIdAndListId\` para a lista que contém a filha.

- [ ] **Step 5: Executar os testes unitários para verificar RED**

Run:

\`\`\`powershell
.\\mvnw.cmd test "-Dtest=CommentServiceImplTest,LikeServiceImplTest"
\`\`\`

Expected: os testes alterados falham porque os guards atuais lançam \`BadRequestException\` para listas-de-listas.

---

### Task 2: Remover os dois bloqueios de elegibilidade social

**Files:**
- Modify: \`src/main/java/com/watchwise/watchwise_api/comment/service/impl/CommentServiceImpl.java\`
- Modify: \`src/main/java/com/watchwise/watchwise_api/like/service/impl/LikeServiceImpl.java\`
- Modify: \`src/test/java/com/watchwise/watchwise_api/comment/service/impl/CommentServiceImplTest.java\`
- Modify: \`src/test/java/com/watchwise/watchwise_api/like/service/impl/LikeServiceImplTest.java\`

**Interfaces:**
- Consumes: testes RED da Task 1.
- Produces: \`UserList\` como alvo social independentemente dos seus itens.

- [ ] **Step 1: Remover o guard de comentários**

Em \`CommentServiceImpl.createCommentOnList\`, remover somente \`assertListAcceptsComments(listId)\`. Remover o método privado, o campo e o import de \`UserListItemRepository\`. Manter visibilidade, resolução de parent e notificação.

- [ ] **Step 2: Remover o guard de likes**

Em \`LikeServiceImpl.likeList\`, remover somente \`assertListAcceptsLikes(listId)\`. Remover o método privado, o campo e o import de \`UserListItemRepository\`. Manter visibilidade, \`NewTransactionExecutor\`, incremento de contador, notificação e recuperação da corrida de unicidade.

- [ ] **Step 3: Limpar os testes unitários**

Remover os mocks/imports e stubs de \`UserListItemRepository\` que só alimentavam os guards. Manter os testes de visibilidade, not-found, idempotência, transação, corrida e contadores.

- [ ] **Step 4: Executar os testes unitários para verificar GREEN**

Run:

\`\`\`powershell
.\\mvnw.cmd test "-Dtest=CommentServiceImplTest,LikeServiceImplTest"
\`\`\`

Expected: PASS, sem stubbing desnecessário e sem regressões nos demais alvos.

---

### Task 3: Sincronizar contrato e documentação

**Files:**
- Modify: \`docs/context/openapi.yaml\`
- Modify: \`docs/context/business-rules.md\`
- Modify: \`docs/context/progress.md\`
- Modify: \`AGENTS.md\`

**Interfaces:**
- Consumes: comportamento GREEN das Tasks 1 e 2.
- Produces: documentação que descreve qualquer \`UserList\` visível como alvo permitido.

- [ ] **Step 1: Atualizar \`openapi.yaml\`**

Em \`/lists/{listId}/comments\`, remover do \`400\` a parte sobre lista-de-listas. Em \`/lists/{listId}/like\`, dizer que qualquer lista visível pode ser curtida e remover a resposta \`400\` específica. No schema \`Comment\`, remover a frase que exclui listas-de-listas.

- [ ] **Step 2: Atualizar \`business-rules.md\`**

Substituir as regras de \`assertListAcceptsComments\` e \`assertListAcceptsLikes\` por uma regra única: comentários e likes de \`UserList\` seguem a visibilidade da lista, sem distinção pelo tipo de itens; replies continuam exigindo o mesmo alvo e likes continuam idempotentes.

- [ ] **Step 3: Atualizar \`progress.md\`**

Adicionar \`## 2026-10-04 — Interações sociais em listas-de-listas\`, registrando os guards removidos e a preservação da visibilidade, idempotência, notificações e contadores. Identificar a regra anterior como substituída sem apagar o histórico.

- [ ] **Step 4: Atualizar \`AGENTS.md\`**

Na tabela de entidades, remover a restrição que dizia que \`Like\` nunca poderia apontar para uma lista-de-listas. Manter a definição de \`Like\` como alvo único entre \`Comment\`, \`DiaryEntry\` e \`UserList\`.

---

### Task 4: Verificar regressões e criar o commit de código

**Files:**
- Test: \`src/test/java/com/watchwise/watchwise_api/comment/controller/CommentControllerIntegrationTest.java\`
- Test: \`src/test/java/com/watchwise/watchwise_api/like/controller/LikeControllerIntegrationTest.java\`
- Test: \`src/test/java/com/watchwise/watchwise_api/comment/service/impl/CommentServiceImplTest.java\`
- Test: \`src/test/java/com/watchwise/watchwise_api/like/service/impl/LikeServiceImplTest.java\`

**Interfaces:**
- Consumes: código e documentação das Tasks 1–3.
- Produces: evidência de sucesso nos endpoints e preservação dos bloqueios de acesso.

- [ ] **Step 1: Executar os testes de integração focados**

Run:

\`\`\`powershell
.\\mvnw.cmd test "-Dtest=CommentControllerIntegrationTest,LikeControllerIntegrationTest"
\`\`\`

Expected: PASS para comentário e like em lista-de-listas, \`403\` para lista privada de terceiro, \`404\` para lista inexistente, \`401\` sem sessão e \`403\` sem CSRF.

- [ ] **Step 2: Executar a suíte completa**

Run:

\`\`\`powershell
.\\mvnw.cmd test
\`\`\`

Expected: PASS. Testes de repositório exigem Docker disponível para o PostgreSQL do Testcontainers.

- [ ] **Step 3: Auditar referências à regra removida**

Run:

\`\`\`powershell
rg -n -i "assertListAcceptsComments|assertListAcceptsLikes|cannot receive comments|cannot receive likes|never accepts comments|não pode receber comentários|não pode ser curtida" AGENTS.md docs/context/openapi.yaml docs/context/business-rules.md src/main src/test
\`\`\`

Expected: nenhuma referência vigente ao bloqueio; registros históricos em \`progress.md\` podem permanecer quando identificarem a decisão anterior como substituída.

- [ ] **Step 4: Validar o diff**

Run:

\`\`\`powershell
git diff --check
git status --short
\`\`\`

Confirmar que documentação permanece fora do commit.

- [ ] **Step 5: Criar o commit de código e testes**

Run:

\`\`\`powershell
git add src/main/java/com/watchwise/watchwise_api/comment/service/impl/CommentServiceImpl.java src/main/java/com/watchwise/watchwise_api/like/service/impl/LikeServiceImpl.java src/test/java/com/watchwise/watchwise_api/comment/service/impl/CommentServiceImplTest.java src/test/java/com/watchwise/watchwise_api/like/service/impl/LikeServiceImplTest.java src/test/java/com/watchwise/watchwise_api/comment/controller/CommentControllerIntegrationTest.java src/test/java/com/watchwise/watchwise_api/like/controller/LikeControllerIntegrationTest.java
git commit -m "feat(userlist): allow social interactions on nested lists"
\`\`\`

Depois do commit, executar \`git show --format=fuller --no-patch HEAD\` e confirmar que não existe trailer \`Co-Authored-By\`. Não executar \`git push\` sem autorização explícita.
