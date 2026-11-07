# Picks Template Detail, Answer Template e Pick Detail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox ( - [ ] ) syntax for tracking.

**Goal:** Expor read models visuais para que Template Detail, Answer Template e Pick Detail sejam montadas sem N+1, sem /contents/details?ids= e sem alterar os contratos brutos de Picks.

**Architecture:** Manter GET /picks-templates/{templateId} como contrato estrutural do template e manter os endpoints raw de Picks intactos. Criar contratos /view específicos para listas e detalhe de Picks, reutilizando ContentCardAssembler, PickTargetPreviewAssembler, PicksTemplatePreviewAssembler e carregamentos agrupados de categorias, seleções, opções, comentários e curtidas. O wizard de Answer Template continuará local até o publish; ele usará o options/view já existente para opções persistidas e buscas visuais sob demanda.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL/Testcontainers, Maven Wrapper, MapStruct, JUnit 5, MockMvc e TMDB client/cache existentes.

## Global Constraints

- Seguir o fluxo Controller → Service → ServiceImpl → Repository/Mapper → Entity e a estrutura de pacotes existente.
- Usar records para DTOs e @Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR) para mappers.
- Não alterar nem substituir PickPreviewDTO, PickResponseDTO, PickSelectionDTO ou PickOptionSearchDTO para resolver uma necessidade exclusivamente visual.
- Não chamar /contents/details?ids= para montar cards.
- Não executar POST /contents/reference nem criar Content durante leituras.
- Usar ContentCardAssembler com ContentCardFieldSet.BASIC_METADATA para previews de conteúdo e PickTargetPreviewAssembler para conteúdo, pessoa e contexto.
- Aplicar a visibilidade existente de Picks: dono sempre pode ver seu Pick; outros viewers só podem ver PUBLIC ou FOLLOWERS com seguimento aceito.
- Paginar a consulta antes do enriquecimento visual; deduplicar coordenadas de conteúdo, pessoas e contextos por página.
- Limitar endpoints de listagem visual de Picks a 10 itens por página; valores maiores são reduzidos pelo PageRequestFactory, enquanto page negativa ou size não positivo retorna ApiError 400.
- Limitar somente GET /picks-templates/{templateId}/categories/{categoryId}/options/view a 20 itens por página; a rota raw /options mantém a regra de paginação já publicada.
- Manter estados AVAILABLE, PARTIAL e UNAVAILABLE, preservando as referências brutas quando o TMDB falhar.
- Toda exceção nova deve chegar a ApiError por GlobalExceptionHandler; não retornar ResponseEntity de erro manualmente.
- Usar TDD; cada tarefa termina com testes focados passando antes do commit.
- Usar mvnw.cmd; testes de repositório exigem Docker/Testcontainers ativo.
- Atualizar docs/context/openapi.yaml, docs/context/progress.md, docs/context/business-rules.md e docs/telas-reqs-definitivo.md quando a implementação correspondente estiver concluída.
- Documentação permanece sem commit, conforme AGENTS.md; não fazer push sem autorização explícita.
- Commits devem seguir Conventional Commits e não podem conter Co-Authored-By.

## Decisões de escopo

1. Criar exatamente estas rotas visuais novas:

~~~http
GET /picks-templates/{templateId}/picks/view
GET /picks-templates/{templateId}/my-picks/view
GET /picks/{pickId}/view
~~~

2. Evoluir o payload da rota já visual GET /users/me/picks/view para incluir previews visuais das respostas, sem alterar a rota raw GET /users/{userId}/picks.

3. Não criar GET /picks-templates/{templateId}/view nesta etapa. O PicksTemplateResponseDTO atual já contém os dados usados pelo cabeçalho e pelas categorias; suas opções raw só serão convertidas em cards quando a tela Answer Template usar options/view.

4. Não criar draft persistido. As respostas não publicadas ficam no frontend e o POST /picks-templates/{templateId}/picks continua sendo a publicação definitiva.

5. Todos os contratos visuais usam validação estrutural carregada em lote no campo isValid já publicado. A leitura visual não falha por indisponibilidade temporária do TMDB: preserva o target e usa previewStatus. O contrato raw GET /picks/{pickId} mantém sua validação completa atual e seus erros atuais.

## Mapa de arquivos

### Contratos e documentação

- Criar src/main/java/com/watchwise/watchwise_api/pick/dto/PickAnswerViewDTO.java para uma resposta visual de categoria.
- Criar src/main/java/com/watchwise/watchwise_api/pick/dto/PickTemplatePickViewDTO.java para cada Pick exibido dentro do detalhe de um template, sem repetir o template pai.
- Criar src/main/java/com/watchwise/watchwise_api/pick/dto/PickDetailViewDTO.java para o detalhe completo, incluindo categorias não respondidas.
- Modificar src/main/java/com/watchwise/watchwise_api/pick/dto/PickViewCardDTO.java para usar respostas visuais na rota /users/me/picks/view.
- Modificar docs/context/openapi.yaml com as três rotas novas, o payload visual e as descrições de visibilidade/paginação.
- Modificar docs/telas-reqs-definitivo.md adicionando Template Detail, Answer Template e Pick Detail e corrigindo a afirmação de que os cards de Picks não possuem previews visuais.

### Leitura e montagem

- Modificar src/main/java/com/watchwise/watchwise_api/pick/repository/PickRepository.java com consultas paginadas visuais e fetch join/contagens compatíveis.
- Modificar src/main/java/com/watchwise/watchwise_api/pick/repository/PickSelectionRepository.java para carregar categoria, conteúdo e contexto em lote.
- Modificar src/main/java/com/watchwise/watchwise_api/pick/service/impl/PickPreviewAssembler.java para enriquecer somente contratos visuais.
- Reutilizar src/main/java/com/watchwise/watchwise_api/pick/service/impl/PickTargetPreviewAssembler.java e, se necessário, adicionar uma operação interna que preserve o vínculo entre cada target e seu preview depois da deduplicação.
- Modificar src/main/java/com/watchwise/watchwise_api/pick/service/PickService.java e src/main/java/com/watchwise/watchwise_api/pick/service/impl/PickServiceImpl.java com os novos métodos de leitura.
- Modificar src/main/java/com/watchwise/watchwise_api/pick/controller/PickController.java com as rotas visuais.

### Testes

- Criar src/test/java/com/watchwise/watchwise_api/pick/dto/PickAnswerViewDTOTest.java.
- Criar src/test/java/com/watchwise/watchwise_api/pick/dto/PickDetailViewDTOTest.java.
- Modificar src/test/java/com/watchwise/watchwise_api/pick/dto/PickViewCardDTOTest.java.
- Modificar src/test/java/com/watchwise/watchwise_api/pick/service/impl/PickPreviewAssemblerTest.java.
- Modificar src/test/java/com/watchwise/watchwise_api/pick/service/impl/PickTargetPreviewAssemblerTest.java apenas para cenários necessários ao novo fluxo.
- Modificar src/test/java/com/watchwise/watchwise_api/pick/repository/PickRepositoryTest.java e PickSelectionRepositoryTest.java.
- Modificar src/test/java/com/watchwise/watchwise_api/pick/controller/PickViewControllerIntegrationTest.java.
- Modificar src/test/java/com/watchwise/watchwise_api/pick/controller/PickControllerIntegrationTest.java e PicksSocialControllerIntegrationTest.java quando a cobertura de visibilidade/likes/comentários for compartilhada.
- Modificar src/test/java/com/watchwise/watchwise_api/pickstemplate/controller/PicksTemplateOptionViewControllerIntegrationTest.java para confirmar que Answer Template continua recebendo previews visuais.

---

### Task 1: Fixar os contratos visuais de resposta e detalhe

**Files:**
- Create: src/main/java/com/watchwise/watchwise_api/pick/dto/PickAnswerViewDTO.java
- Create: src/main/java/com/watchwise/watchwise_api/pick/dto/PickTemplatePickViewDTO.java
- Create: src/main/java/com/watchwise/watchwise_api/pick/dto/PickDetailViewDTO.java
- Modify: src/main/java/com/watchwise/watchwise_api/pick/dto/PickViewCardDTO.java
- Test: src/test/java/com/watchwise/watchwise_api/pick/dto/PickAnswerViewDTOTest.java
- Test: src/test/java/com/watchwise/watchwise_api/pick/dto/PickDetailViewDTOTest.java
- Test: src/test/java/com/watchwise/watchwise_api/pick/dto/PickViewCardDTOTest.java

**Interfaces:**
- PickAnswerViewDTO deve expor categoryId, name, group, displayOrder, allowedType, optionMode, target, preview, isAnswered e isValid; target e preview são nulos para categoria não respondida.
- PickTemplatePickViewDTO deve expor id, user, visibility, createdAt, progress, answeredCategoriesCount, totalCategoriesCount, answeredCategories, likesCount, commentsCount e isLikedByViewer.
- PickDetailViewDTO deve expor id, PicksTemplatePreviewDTO picksTemplate, user, visibility, createdAt, updatedAt, progress, answeredCategoriesCount, totalCategoriesCount, answers, likesCount, commentsCount e isLikedByViewer. Não embutir PicksTemplateResponseDTO nem suas opções raw; as categorias necessárias para o detalhe estão em answers.
- PickViewCardDTO deve manter seu papel visual para /users/me/picks/view, mas seus itens de resposta devem ser PickAnswerViewDTO e não PickAnsweredCategoryPreviewDTO. PickAnswerViewDTO preserva todos os nomes JSON já publicados pelo item anterior e acrescenta somente allowedType, optionMode, preview e isAnswered; isValid continua significando validade estrutural em contratos visuais.
- PickPreviewDTO, PickResponseDTO e PickSelectionDTO permanecem sem preview visual.

- [ ] **Step 1: Escrever os testes falhando para os invariantes dos records**

Cobrir:

~~~java
assertThat(new PickAnswerViewDTO(categoryId, "Best movie", group, 1, allowedType,
        optionMode, null, null, false, false).target()).isNull();
assertThat(new PickDetailViewDTO(...).answers()).containsExactly(answered, unanswered);
assertThat(new PickViewCardDTO(...).answeredCategories()).allMatch(answer -> answer.preview() != null);
~~~

Os testes também devem confirmar que listas nulas são normalizadas para listas imutáveis vazias, seguindo o padrão dos DTOs visuais existentes.

- [ ] **Step 2: Executar os testes para confirmar a falha**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PickAnswerViewDTOTest,PickDetailViewDTOTest,PickViewCardDTOTest"
~~~

Expected: FAIL porque os novos records e o tipo visual de resposta ainda não existem.

- [ ] **Step 3: Implementar os records sem lógica de consulta**

Implementar os três records com cópia defensiva das listas. Não adicionar chamadas ao TMDB, repositórios ou regras de autorização nos DTOs.

- [ ] **Step 4: Executar os testes focados**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PickAnswerViewDTOTest,PickDetailViewDTOTest,PickViewCardDTOTest"
~~~

Expected: PASS.

- [ ] **Step 5: Atualizar somente os schemas OpenAPI necessários**

Adicionar os schemas PickAnswerView, PickTemplatePickView e PickDetailView e atualizar PickViewCard para refletir o contrato visual. Não alterar os schemas raw.

- [ ] **Step 6: Commitar o contrato**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/pick/dto src/test/java/com/watchwise/watchwise_api/pick/dto
git commit -m "feat(pick): define visual detail contracts"
~~~

Confirmar imediatamente que o commit não contém trailer Co-Authored-By.

---

### Task 2: Preparar consultas em lote para templates, Picks e seleções

**Files:**
- Modify: src/main/java/com/watchwise/watchwise_api/pick/repository/PickRepository.java
- Modify: src/main/java/com/watchwise/watchwise_api/pick/repository/PickSelectionRepository.java
- Test: src/test/java/com/watchwise/watchwise_api/pick/repository/PickRepositoryTest.java
- Test: src/test/java/com/watchwise/watchwise_api/pick/repository/PickSelectionRepositoryTest.java

**Interfaces:**
- Evoluir as consultas paginadas existentes de Picks visíveis por template em ordem recente e popular.
- Evoluir a consulta paginada existente de Picks próprios de um template.
- Adicionar consulta de detalhe que carregue pick.user, pick.picksTemplate e, quando necessário, template.creator sem consulta individual por Pick.
- A consulta de seleção em lote deve trazer selection.category, selection.content e selection.contextContent.
- Toda consulta paginada com fetch join deve possuir countQuery sem fetch e sem duplicar a contagem.

- [ ] **Step 1: Escrever testes de repositório para visibilidade e ordenação**

Criar cenários com o mesmo template contendo Picks PUBLIC, FOLLOWERS e PRIVATE, além de um follower aceito e um não aceito. Confirmar:

~~~java
assertThat(repository.findVisibleByTemplateRecent(viewerId, templateId, page))
        .extracting(Pick::getId).containsExactly(publicId, followersId, ownPrivateId);
assertThat(repository.findVisibleByTemplatePopular(viewerId, templateId, page).getContent())
        .isSortedAccordingTo(Comparator.comparing(Pick::getLikesCount).reversed());
~~~

Adicionar um teste específico confirmando que a paginação não perde nem duplica Picks quando a consulta usa joins.

- [ ] **Step 2: Executar os testes para confirmar a falha**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PickRepositoryTest,PickSelectionRepositoryTest"
~~~

Expected: FAIL nos métodos de consulta ainda inexistentes ou sem os joins necessários.

- [ ] **Step 3: Implementar consultas agrupadas e fetch joins**

Adicionar os métodos no repositório mantendo as regras atuais de visibilidade. Usar ordenação determinística:

~~~text
RECENT  = createdAt DESC, id DESC
POPULAR = likesCount DESC, createdAt DESC, id DESC
~~~

Não colocar resolução TMDB no repositório.

- [ ] **Step 4: Executar os testes de repositório**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PickRepositoryTest,PickSelectionRepositoryTest"
~~~

Expected: PASS com Docker/Testcontainers ativo.

- [ ] **Step 5: Commitar a camada de leitura persistida**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/pick/repository src/test/java/com/watchwise/watchwise_api/pick/repository
git commit -m "feat(pick): add visual read queries"
~~~

Confirmar imediatamente que o commit não contém trailer Co-Authored-By.

---

### Task 3: Montar previews visuais em lote

**Files:**
- Modify: src/main/java/com/watchwise/watchwise_api/pick/service/impl/PickPreviewAssembler.java
- Modify: src/main/java/com/watchwise/watchwise_api/pick/service/impl/PickTargetPreviewAssembler.java somente se for necessária uma operação indexada por target.
- Modify: src/test/java/com/watchwise/watchwise_api/pick/service/impl/PickPreviewAssemblerTest.java
- Modify: src/test/java/com/watchwise/watchwise_api/pick/service/impl/PickTargetPreviewAssemblerTest.java

**Interfaces:**
- PickPreviewAssembler.assembleViewPage(...) deve produzir PickViewCardDTO com previews visuais.
- Adicionar assembleTemplateViewPage(Page<Pick> picks, UUID viewerId) retornando Page<PickTemplatePickViewDTO>.
- Adicionar assembleDetail(Pick pick, UUID viewerId) retornando PickDetailViewDTO.
- Resolver todos os targets de uma página com uma única montagem agrupada de PickTargetPreviewAssembler.
- A montagem deve usar ContentCardContext com idioma/região do viewer quando disponíveis; sem estado de viewer em cache compartilhado.

- [ ] **Step 1: Escrever testes falhando para conteúdo, pessoa e contexto**

Cobrir:

~~~java
assertThat(result.answeredCategories().get(0).preview().contentCard().title())
        .isEqualTo("Back to the Future");
assertThat(result.answeredCategories().get(1).preview().person().name())
        .isEqualTo("Christopher Lloyd");
assertThat(result.answeredCategories().get(1).preview().contextCard().title())
        .isEqualTo("Back to the Future");
~~~

Adicionar casos de targets repetidos, TMDB PARTIAL e TMDB UNAVAILABLE, verificando que os IDs brutos continuam presentes.

- [ ] **Step 2: Executar o teste para confirmar a falha**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PickPreviewAssemblerTest,PickTargetPreviewAssemblerTest"
~~~

Expected: FAIL porque as respostas visuais ainda não são montadas pelo assembler de Picks.

- [ ] **Step 3: Implementar a resolução agrupada**

No assembler:

1. Carregar categorias, seleções, opções fixas, likes e comentários com as estruturas existentes.
2. Transformar seleções em PickOptionSearchDTO sem perder selection.id.
3. Achatar os targets de todos os Picks da página.
4. Chamar PickTargetPreviewAssembler uma vez para o conjunto da página.
5. Reassociar previews por posição ou chave normalizada de target.
6. Montar PickTemplatePickViewDTO sem repetir PicksTemplatePreviewDTO.
7. Montar PickViewCardDTO com os mesmos dados e o template preview para /users/me/picks/view.
8. Para detalhe, incluir todas as categorias em answers, inclusive as não respondidas.

Cards de listagem devem limitar as respostas exibidas a cinco, em ordem de displayOrder. O detalhe deve manter todas as categorias. Todos os contratos visuais usam a mesma validação estrutural já calculada com as opções carregadas em lote; o detalhe não chama PickTargetService.isValid por seleção nem faz novas consultas TMDB. O campo isValid tem semântica estrutural nesses contratos e não substitui o isValid completo do contrato raw.

- [ ] **Step 4: Executar os testes focados**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PickPreviewAssemblerTest,PickTargetPreviewAssemblerTest,PickViewCardDTOTest"
~~~

Expected: PASS e nenhuma chamada individual por target nos testes de assembler.

- [ ] **Step 5: Commitar a montagem visual**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/pick/service/impl src/test/java/com/watchwise/watchwise_api/pick/service/impl
git commit -m "feat(pick): assemble visual target previews"
~~~

Confirmar imediatamente que o commit não contém trailer Co-Authored-By.

---

### Task 4: Expor as leituras visuais no service e controller

**Files:**
- Modify: src/main/java/com/watchwise/watchwise_api/pick/service/PickService.java
- Modify: src/main/java/com/watchwise/watchwise_api/pick/service/impl/PickServiceImpl.java
- Modify: src/main/java/com/watchwise/watchwise_api/pick/controller/PickController.java
- Test: src/test/java/com/watchwise/watchwise_api/pick/controller/PickViewControllerIntegrationTest.java
- Test: src/test/java/com/watchwise/watchwise_api/pick/controller/PickControllerIntegrationTest.java

**Interfaces:**

Adicionar ao PickService:

~~~java
Page<PickTemplatePickViewDTO> getTemplatePicksView(
        UUID viewerId, UUID templateId, PickSort sort, Integer page, Integer size);

Page<PickTemplatePickViewDTO> getMyPicksView(
        UUID viewerId, UUID templateId, Integer page, Integer size);

PickDetailViewDTO getPickView(UUID viewerId, UUID pickId);
~~~

Manter o método existente getMyPicksView(UUID viewerId, Integer page, Integer size) para a tela global de My Picks, enriquecendo seu retorno sem alterar a rota.

Definir no PickServiceImpl:

~~~java
private static final int MAX_VISUAL_PICK_PAGE_SIZE = 10;
~~~

As três leituras visuais paginadas devem chamar pageRequestFactory.build(page, size, MAX_VISUAL_PICK_PAGE_SIZE). As leituras raw preservam o limite atual.

- [ ] **Step 1: Escrever testes de controller para os contratos novos**

Cobrir:

~~~text
GET /picks-templates/{id}/picks/view?sort=RECENT&page=1&size=10 → 200
GET /picks-templates/{id}/picks/view?sort=POPULAR&page=1&size=10 → 200
GET /picks-templates/{id}/my-picks/view?page=1&size=10 → 200
GET /picks/{id}/view → 200
~~~

Cada teste deve verificar content, page, size, totalElements, totalPages, hasNext, usuário, preview visual, contagens e categorias não respondidas no detalhe.

Adicionar testes para:

- Pick privado de outro usuário retornar 403 ou 404 conforme o contrato existente;
- Pick de follower não aceito não aparecer;
- template inexistente retornar 404;
- template cujo creator foi desvinculado retornar creator nulo sem impedir a leitura de seus Picks;
- sort inválido retornar 400 em ApiError;
- page negativa e size não positivo retornarem 400 em ApiError;
- size maior que 10 ser reduzido para 10 e refletir esse valor em PageResponseDTO;
- comentários e likes refletirem o viewer atual.

- [ ] **Step 2: Executar os testes para confirmar a falha**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PickViewControllerIntegrationTest,PickControllerIntegrationTest"
~~~

Expected: FAIL porque as rotas e métodos do service ainda não existem.

- [ ] **Step 3: Implementar os métodos do service**

Usar PageRequestFactory, as consultas do Task 2 e os métodos do Task 3. Não duplicar a lógica de visibilidade: reaproveitar findVisibleByTemplateRecent, findVisibleByTemplatePopular e assertVisible. Para o detalhe visual, aplicar somente a visibilidade do Pick e a montagem estrutural; não chamar o caminho raw que revalida todos os targets remotamente.

O detalhe deve buscar somente um Pick visível e montar todas as categorias na ordem do template. As rotas GET /users/{userId}/picks e GET /picks-templates/{templateId}/picks permanecem apontando para os DTOs raw.

- [ ] **Step 4: Adicionar as rotas no controller**

Adicionar os três @GetMapping exatamente nestes caminhos:

~~~java
@GetMapping("/picks-templates/{templateId}/picks/view")
@GetMapping("/picks-templates/{templateId}/my-picks/view")
@GetMapping("/picks/{pickId}/view")
~~~

Usar PageResponseDTO.of(page) para páginas e o DTO visual direto para o detalhe.

- [ ] **Step 5: Executar os testes de controller**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PickViewControllerIntegrationTest,PickControllerIntegrationTest"
~~~

Expected: PASS.

- [ ] **Step 6: Commitar os endpoints**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/pick/service src/main/java/com/watchwise/watchwise_api/pick/controller src/test/java/com/watchwise/watchwise_api/pick/controller
git commit -m "feat(pick): expose visual detail reads"
~~~

Confirmar imediatamente que o commit não contém trailer Co-Authored-By.

---

### Task 5: Fechar o fluxo Answer Template sem criar um segundo mecanismo de busca

**Files:**
- Modify: src/main/java/com/watchwise/watchwise_api/pickstemplate/service/impl/PicksTemplateOptionServiceImpl.java.
- Test: src/test/java/com/watchwise/watchwise_api/pickstemplate/controller/PicksTemplateOptionViewControllerIntegrationTest.java
- Test: src/test/java/com/watchwise/watchwise_api/pick/controller/PickControllerIntegrationTest.java
- Test: src/test/java/com/watchwise/watchwise_api/pickstemplate/service/impl/PicksTemplateOptionServiceImplTest.java

**Interfaces:**
- GET /picks-templates/{templateId} continua fornecendo a configuração das categorias.
- GET /picks-templates/{templateId}/categories/{categoryId}/options/view continua sendo a única leitura visual de opções persistidas e de buscas OPEN.
- POST /picks-templates/{templateId}/picks continua aceitando as seleções brutas e a visibilidade.
- Nenhum draft persistido será adicionado.
- Apenas a leitura /options/view aplica MAX_OPTION_VIEW_PAGE_SIZE = 20; a rota raw /options não muda de semântica nem de limite.

- [ ] **Step 1: Escrever testes de regressão do wizard**

Cobrir:

1. Categoria FIXED retorna opção de filme com preview.contentCard.
2. Categoria FIXED de pessoa retorna preview.person e preview.contextCard quando houver contexto.
3. Categoria OPEN retorna resultados visuais com id = null para alvos ainda não persistidos.
4. Publish com uma ou mais categorias envia somente targets válidos.
5. Publish com zero seleções continua retornando 400.
6. Alvo incompatível com allowedType continua retornando 400.
7. Uma requisição /options/view com size=21 retorna uma página de size=20; a mesma consulta raw não é alterada por essa proteção visual.

- [ ] **Step 2: Executar os testes para confirmar a situação atual**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PicksTemplateOptionViewControllerIntegrationTest,PicksTemplateOptionServiceImplTest,PickControllerIntegrationTest"
~~~

Expected: os testes existentes devem passar; os novos testes de teto de página falham até a separação entre paginação raw e visual ser implementada.

- [ ] **Step 3: Corrigir somente lacunas comprovadas**

Separar a construção de PageRequest da leitura raw e da leitura visual, aplicando o teto de 20 somente em getOptionViews. Corrigir apenas a montagem visual, paginação ou validação diretamente relacionada ao fluxo. Não adicionar /contents/details?ids=, não persistir referências na leitura e não duplicar a lógica de PickTargetPreviewAssembler.

- [ ] **Step 4: Reexecutar os testes do fluxo**

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PicksTemplateOptionViewControllerIntegrationTest,PicksTemplateOptionServiceImplTest,PickControllerIntegrationTest"
~~~

Expected: PASS.

- [ ] **Step 5: Commitar somente se houver código novo**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/pickstemplate src/test/java/com/watchwise/watchwise_api/pickstemplate src/test/java/com/watchwise/watchwise_api/pick/controller
git commit -m "fix(pickstemplate): complete visual answer flow"
~~~

Se nenhum código for necessário, não criar commit vazio; registrar no relatório que os contratos atuais já cobriram o fluxo.

Confirmar imediatamente que qualquer commit criado não contém trailer Co-Authored-By.

---

### Task 6: Sincronizar contratos e requisitos das telas

**Files:**
- Modify: docs/context/openapi.yaml
- Modify: docs/context/business-rules.md
- Modify: docs/context/progress.md
- Modify: docs/telas-reqs-definitivo.md

**Interfaces:**
- OpenAPI deve documentar as três rotas /view, os schemas visuais, paginação, sort, visibilidade e erros.
- telas-reqs-definitivo.md deve separar claramente:
  - Template Detail: template atual + Picks visuais paginados;
  - Answer Template: template estrutural + options/view sob demanda + publish único;
  - Pick Detail: detalhe visual completo + comentários carregados separadamente.
- business-rules.md deve registrar somente regras implementadas: visibilidade, categorias não respondidas, limite de cinco respostas nos cards, todas as categorias no detalhe e preservação de previews parciais/indisponíveis.
- progress.md deve registrar o que foi efetivamente implementado na data da execução, sem seção de próximos passos.

- [ ] **Step 1: Atualizar OpenAPI**

Adicionar os paths e schemas usando os nomes definidos no Task 1. Documentar 200 para leituras válidas, 400 para parâmetros inválidos, 401 para ausência de autenticação e 403/404 conforme visibilidade e existência. As leituras visuais preservam PARTIAL/UNAVAILABLE em vez de introduzir um novo 502; o GET raw /picks/{pickId} mantém seu contrato atual de 502 para a revalidação completa.

- [ ] **Step 2: Atualizar requisitos das telas**

Substituir a afirmação anterior de que cards pessoais de Picks não possuem previews visuais. Explicar que o raw permanece referencial e as rotas /view são os read models para a interface.

- [ ] **Step 3: Atualizar regras e progresso**

Apontar as classes reais que implementam cada regra e registrar apenas comportamento entregue.

- [ ] **Step 4: Verificar documentação**

Run:

~~~powershell
rg -n "picks/view|my-picks/view|PickDetailView|PickAnswerView|PickTargetPreview|contents/details|draft" docs/context/openapi.yaml docs/context/business-rules.md docs/context/progress.md docs/telas-reqs-definitivo.md
~~~

Expected: rotas e contratos novos aparecem; nenhuma seção nova manda usar /contents/details?ids= ou draft persistido.

Não criar commit de documentação.

---

### Task 7: Revisão independente e verificação final

**Files:**
- Review: todos os arquivos de código e testes alterados pelas Tasks 1–5.
- Verify: docs/context/openapi.yaml, docs/context/business-rules.md, docs/context/progress.md, docs/telas-reqs-definitivo.md.

- [ ] **Step 1: Revisar o diff por contrato e performance**

Verificar explicitamente:

- raw e /view não foram misturados;
- não há consulta por Pick dentro de um loop de página;
- não há consulta por target duplicado; targets distintos podem exigir resolução TMDB fria, mas uma única vez por coordenada ou pessoa;
- ContentCardAssembler é chamado em lote;
- pessoas e contextos são deduplicados;
- private/followers não vazam em nenhuma rota;
- categorias não respondidas aparecem somente no detalhe;
- erros continuam no formato ApiError;
- PickOptionViewDTO.id continua nulo para busca OPEN;
- nenhuma leitura cria Content.
- leituras visuais de Pick têm teto 10 e options/view tem teto 20 sem alterar rotas raw.

- [ ] **Step 2: Executar todos os testes focados**

~~~powershell
.\mvnw.cmd test "-Dtest=PickAnswerViewDTOTest,PickDetailViewDTOTest,PickViewCardDTOTest,PickPreviewAssemblerTest,PickTargetPreviewAssemblerTest,PickRepositoryTest,PickSelectionRepositoryTest,PickViewControllerIntegrationTest,PickControllerIntegrationTest,PicksTemplateOptionViewControllerIntegrationTest"
~~~

Expected: todos os testes passam.

- [ ] **Step 3: Executar a suíte completa**

~~~powershell
.\mvnw.cmd test
~~~

Expected: BUILD SUCCESS, zero falhas, zero erros e zero testes ignorados inesperadamente.

- [ ] **Step 4: Verificar Git e commits**

~~~powershell
git status --short
git log --oneline --decorate -n 10
git log -n 10 --format=%B | Select-String "Co-Authored-By"
~~~

Expected: código commitado somente em commits relacionados, documentação local não commitada conforme AGENTS.md, nenhum trailer Co-Authored-By, nenhum push executado.

- [ ] **Step 5: Relatar resultado**

Registrar os commits, os comandos de teste, o total de testes e qualquer limitação residual real. Não declarar a tela completamente suportada sem evidência dos testes focados e da suíte completa.

## Critérios de aceite

- Template Detail monta cabeçalho, categorias, Your picks, Community picks, likes, comentários e paginação.
- Answer Template monta categorias OPEN e FIXED, cards de filmes/séries/episódios/pessoas, contexto de pessoa, estados parciais e publicação.
- Pick Detail monta todas as categorias, incluindo não respondidas, com cards visuais e estado do Pick.
- Nenhuma dessas telas depende de /contents/details?ids=.
- Nenhuma leitura grava Content.
- Uma página de Picks não gera consulta ao banco por item e não repete resolução TMDB para a mesma coordenada, pessoa ou contexto.
- Visibilidade e estado do viewer são aplicados no backend.
- Endpoints raw continuam funcionando com seus contratos atuais.
- OpenAPI e docs/telas-reqs-definitivo.md descrevem exatamente os contratos implementados.

