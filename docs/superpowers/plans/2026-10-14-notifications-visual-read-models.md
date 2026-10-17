# Notifications Visual Read Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Expor uma página visual de notificações que permita ao frontend montar a tela Notifications com cartões de conteúdo, pessoas, alvos sociais e contadores, sem N+1 e sem alterar a rota bruta existente.

**Architecture:** Manter GET /notifications como contrato bruto e adicionar GET /notifications/view como read model para UI. A nova projeção preserva os campos brutos da notificação e acrescenta previews visuais resolvidos em lote: ContentCardDTO para conteúdo, um resolver TMDB de pessoa reutilizável e preview leve para o alvo de interação social. O serviço pagina primeiro, faz as buscas por lote apenas para a página carregada e devolve contadores globais com uma única consulta agregada.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, MapStruct, Caffeine/TMDB client existente, Maven, JUnit 5 e Mockito.

## Global Constraints

- Preservar sem mudança o contrato e o comportamento de GET /notifications e PATCH /notifications/{notificationId}/read.
- Adicionar somente GET /notifications/view; não criar tabela, migração Flyway nem persistir dados TMDB durante leituras.
- Reutilizar ContentCardAssembler e ContentCardContext para conteúdos; não usar endpoint batch contents/details.
- A página visual deve ter tamanho máximo de 20, mesmo que a fábrica global aceite tamanho maior.
- Consultar o banco primeiro para paginar e resolver previews exclusivamente dos itens desta página.
- Resolver conteúdos, pessoas e alvos sociais deduplicados e em lote; não fazer uma consulta por notificação.
- Reutilizar a resolução de pessoa entre Notifications e Picks; o novo módulo concentra cache, paralelismo limitado e o mapeamento de disponibilidade TMDB.
- O preview de alvo social é deliberadamente compacto: não carrega comentários filhos, itens de lista, nem seleções de Pick para renderizar uma notificação.
- Buscar contagens total e não lida em uma única projeção agregada, sem duas consultas count independentes.
- Falha de TMDB ou alvo apagado não pode remover a notificação: devolver o item com ContentPreviewStatus PARTIAL ou UNAVAILABLE.
- Preservar as regras existentes de agregação social, expiração e retenção; este trabalho só projeta os dados já existentes.
- Todas as rotas exigem usuário autenticado e só leem notificações do usuário corrente.
- Atualizar openapi.yaml, progress.md, business-rules.md e telas-reqs-definitivo.md; documentação fica fora dos commits, salvo autorização explícita.
- Todo commit deve usar Conventional Commits e não pode conter Co-Authored-By nem atribuição automática.

---

## File structure

- Create: src/main/java/com/watchwise/watchwise_api/person/dto/PersonPreviewDTO.java — pessoa TMDB renderizável reutilizável.
- Create: src/main/java/com/watchwise/watchwise_api/person/service/PersonPreviewResolver.java — resolve pessoas deduplicadas em lote.
- Create: src/main/java/com/watchwise/watchwise_api/person/service/impl/TmdbPersonPreviewResolver.java — adapter TMDB do resolver.
- Create: src/main/java/com/watchwise/watchwise_api/notification/dto/NotificationTargetPreviewDTO.java — alvo social leve e resiliente.
- Create: src/main/java/com/watchwise/watchwise_api/notification/dto/NotificationViewDTO.java — notificação bruta acrescida de previews visuais.
- Create: src/main/java/com/watchwise/watchwise_api/notification/dto/NotificationPageViewDTO.java — envelope paginado com contagens totais.
- Create: src/main/java/com/watchwise/watchwise_api/notification/service/impl/NotificationTargetKey.java — chave interna deduplicável de alvo social.
- Create: src/main/java/com/watchwise/watchwise_api/notification/service/impl/NotificationTargetPreviewReader.java — busca em lote de alvos sociais.
- Create: src/main/java/com/watchwise/watchwise_api/notification/service/impl/NotificationViewAssembler.java — montagem dos previews de uma página.
- Create: src/main/java/com/watchwise/watchwise_api/notification/repository/NotificationCountsProjection.java — projeção interna de contagens do destinatário.
- Modify: src/main/java/com/watchwise/watchwise_api/notification/repository/NotificationRepository.java — consulta agregada de contagens por destinatário.
- Modify: repositories dos domínios Comment, DiaryEntry, DroppedEntry, UserList, Pick e PicksTemplate — somente consultas em lote com as relações mínimas do preview.
- Modify: src/main/java/com/watchwise/watchwise_api/pick/service/impl/PickTargetPreviewAssembler.java — consumir o resolver compartilhado, sem alterar o JSON público de Picks.
- Modify: src/main/java/com/watchwise/watchwise_api/notification/service/NotificationService.java — contrato getNotificationsView.
- Modify: src/main/java/com/watchwise/watchwise_api/notification/service/impl/NotificationServiceImpl.java — paginação, montagem e contadores.
- Modify: src/main/java/com/watchwise/watchwise_api/notification/controller/NotificationController.java — rota GET /notifications/view.
- Create/Modify: testes unitários e de integração de notification, além dos testes de repositório estritamente necessários.
- Modify: docs/context/openapi.yaml, docs/context/progress.md, docs/context/business-rules.md, docs/telas-reqs-definitivo.md.

## Response contracts

~~~java
public record PersonPreviewDTO(
        String personTmdbId,
        String name,
        String profileUrl,
        ContentPreviewStatus previewStatus
) {}

public record NotificationTargetPreviewDTO(
        NotificationTargetType targetType,
        UUID targetId,
        String title,
        String excerpt,
        Integer score,
        String coverImageUrl,
        ContentCardDTO contentCard,
        ContentPreviewStatus previewStatus
) {}

public record NotificationViewDTO(
        UUID id,
        NotificationType type,
        String message,
        ContentRefDTO content,
        String personTmdbId,
        boolean isRead,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        UserPreviewDTO latestActor,
        NotificationTargetType targetType,
        UUID targetId,
        Integer interactionCount,
        ContentCardDTO contentCard,
        PersonPreviewDTO person,
        NotificationTargetPreviewDTO targetPreview
) {}

public record NotificationPageViewDTO(
        List<NotificationViewDTO> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        long totalNotifications,
        long unreadNotifications
) {}
~~~

### Task 1: Define shared visual contracts

**Files:**
- Create: src/main/java/com/watchwise/watchwise_api/person/dto/PersonPreviewDTO.java
- Create: src/main/java/com/watchwise/watchwise_api/notification/dto/NotificationTargetPreviewDTO.java
- Create: src/main/java/com/watchwise/watchwise_api/notification/dto/NotificationViewDTO.java
- Create: src/main/java/com/watchwise/watchwise_api/notification/dto/NotificationPageViewDTO.java
- Test: src/test/java/com/watchwise/watchwise_api/person/dto/PersonPreviewDTOTest.java
- Test: src/test/java/com/watchwise/watchwise_api/notification/dto/NotificationViewDTOTest.java

**Consumes:** ContentPreviewStatus, ContentCardDTO, ContentRefDTO, UserPreviewDTO, NotificationType and NotificationTargetType already defined in the project.

**Produces:** PersonPreviewDTO used by Notifications and by the existing Picks assembler, plus notification DTOs used by the visual read module.

- [ ] **Step 1: Write the failing DTO contract test**

Cover a system notification with contentCard and person null, a followed-person notification with person non-null, and a social notification with targetPreview non-null. Assert the page wrapper exposes totalNotifications and unreadNotifications independently of filtered totalElements. Assert a template target carries coverImageUrl without abusing contentCard.

- [ ] **Step 2: Run the focused test to verify it fails**

Run: .\mvnw.cmd test "-Dtest=PersonPreviewDTOTest,NotificationViewDTOTest"
Expected: compilation failure because the visual DTOs do not exist.

- [ ] **Step 3: Add the four immutable record DTOs**

Use exactly the fields and types documented in Response contracts. Do not duplicate content metadata fields already owned by ContentCardDTO. PersonPreviewDTO is the shared wire shape; PickPersonPreviewDTO remains unchanged in the Picks contract until a separately versioned API change is approved.

- [ ] **Step 4: Run the focused test to verify it passes**

Run: .\mvnw.cmd test "-Dtest=PersonPreviewDTOTest,NotificationViewDTOTest"
Expected: PASS.

- [ ] **Step 5: Review the contracts**

Verify the raw notification fields remain available on NotificationViewDTO and that no DTO introduces persistence or HTTP concerns.

- [ ] **Step 6: Commit the completed contract**

Run:
~~~powershell
git add src/main/java/com/watchwise/watchwise_api/person/dto src/main/java/com/watchwise/watchwise_api/notification/dto src/test/java/com/watchwise/watchwise_api/person/dto src/test/java/com/watchwise/watchwise_api/notification/dto
git commit -m "feat(notification): define visual preview contracts"
git log -1 --format=%B
~~~

Expected: the message has no Co-Authored-By trailer or other self-attribution.

### Task 2: Extract shared TMDB person preview resolution

**Files:**
- Create: src/main/java/com/watchwise/watchwise_api/person/service/PersonPreviewResolver.java
- Create: src/main/java/com/watchwise/watchwise_api/person/service/impl/TmdbPersonPreviewResolver.java
- Modify: src/main/java/com/watchwise/watchwise_api/pick/service/impl/PickTargetPreviewAssembler.java
- Test: src/test/java/com/watchwise/watchwise_api/person/service/impl/TmdbPersonPreviewResolverTest.java
- Test: src/test/java/com/watchwise/watchwise_api/pick/service/impl/PickTargetPreviewAssemblerTest.java

**Consumes:** TmdbClient, TmdbImageUrlBuilder, the existing contentCardMetadataResolutionExecutor, PersonPreviewDTO and PickPersonPreviewDTO.

**Produces:**
~~~java
public interface PersonPreviewResolver {
    Map<String, PersonPreviewDTO> resolve(Collection<String> personTmdbIds);
}
~~~

- [ ] **Step 1: Write the failing resolver tests**

Cover duplicate person IDs, a found person with photo, a found person without photo, TMDB not-found, and TMDB unavailable. Assert one client lookup per distinct ID, stable input-independent map semantics, and no lookup for null or blank IDs.

- [ ] **Step 2: Run the focused test to verify it fails**

Run: .\mvnw.cmd test "-Dtest=TmdbPersonPreviewResolverTest"
Expected: compilation failure because PersonPreviewResolver does not exist.

- [ ] **Step 3: Implement the resolver**

Normalize IDs by dropping null and blank values, deduplicate in insertion order, resolve each with the existing bounded metadata executor and cached TmdbClient, and convert all result modes into PersonPreviewDTO. A failed remote lookup is data absence for this read model, never an exception that aborts a page.

- [ ] **Step 4: Refactor Picks to reuse the resolver**

Inject PersonPreviewResolver into PickTargetPreviewAssembler. Map its PersonPreviewDTO result to the existing PickPersonPreviewDTO at the Picks seam so current Picks JSON and endpoint contracts remain unchanged. Delete the duplicate direct TMDB person lookup from PickTargetPreviewAssembler. Preserve the existing two-argument test constructor by constructing TmdbPersonPreviewResolver with Runnable::run.

- [ ] **Step 5: Run focused regression tests**

Run: .\mvnw.cmd test "-Dtest=TmdbPersonPreviewResolverTest,PickTargetPreviewAssemblerTest"
Expected: PASS, including the existing Picks person-preview behavior.

- [ ] **Step 6: Commit the shared resolver**

Run:
~~~powershell
git add src/main/java/com/watchwise/watchwise_api/person src/main/java/com/watchwise/watchwise_api/pick/service/impl/PickTargetPreviewAssembler.java src/test/java/com/watchwise/watchwise_api/person src/test/java/com/watchwise/watchwise_api/pick/service/impl/PickTargetPreviewAssemblerTest.java
git commit -m "refactor(person): share TMDB preview resolution"
git log -1 --format=%B
~~~

Expected: the message has no Co-Authored-By trailer or other self-attribution.

### Task 3: Read social targets in batches

**Files:**
- Create: src/main/java/com/watchwise/watchwise_api/notification/service/impl/NotificationTargetKey.java
- Create: src/main/java/com/watchwise/watchwise_api/notification/service/impl/NotificationTargetPreviewReader.java
- Modify: src/main/java/com/watchwise/watchwise_api/comment/repository/CommentRepository.java
- Modify: src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java
- Modify: src/main/java/com/watchwise/watchwise_api/droppedentry/repository/DroppedEntryRepository.java
- Modify: src/main/java/com/watchwise/watchwise_api/userlist/repository/UserListRepository.java
- Modify: src/main/java/com/watchwise/watchwise_api/pick/repository/PickRepository.java
- Modify: src/main/java/com/watchwise/watchwise_api/pickstemplate/repository/PicksTemplateRepository.java
- Test: src/test/java/com/watchwise/watchwise_api/notification/service/impl/NotificationTargetPreviewReaderTest.java
- Test: repository tests for every new JPQL/entity-graph batch query

**Consumes:** NotificationTargetType, ContentCardAssembler, ContentCardContext, entity repositories, and NotificationTargetPreviewDTO.

**Produces:**
~~~java
public record NotificationTargetKey(NotificationTargetType targetType, UUID targetId) {}

public Map<NotificationTargetKey, NotificationTargetPreviewDTO> read(
        Collection<NotificationTargetKey> keys,
        ContentCardContext context
)
~~~

- [ ] **Step 1: Write failing reader tests**

Cover one target of each type:
- COMMENT produces title Comment and its body as excerpt.
- DIARY_ENTRY produces the diary content card, review comment as excerpt and score.
- DROPPED_ENTRY produces its content card and dropped comment as excerpt.
- USER_LIST produces title and description.
- PICK produces the template title and description without loading its selections.
- PICKS_TEMPLATE produces template name, description and cover metadata when available.
- a missing entity yields UNAVAILABLE instead of dropping the key.
- repeated keys produce one repository batch lookup per target type and one content-card assembly for deduplicated content references.

- [ ] **Step 2: Run the focused reader test to verify it fails**

Run: .\mvnw.cmd test "-Dtest=NotificationTargetPreviewReaderTest"
Expected: compilation failure because NotificationTargetKey and NotificationTargetPreviewReader do not exist.

- [ ] **Step 3: Add explicit batch repository methods**

Use methods shaped as findByIdInWith... and ensure each fetches only relations required by the preview:
- Comment: author and text.
- DiaryEntry: user and content.
- DroppedEntry: user and content.
- UserList: owner and list fields.
- Pick: template only. Do not fetch selections because they are not needed for a notification row.
- PicksTemplate: creator, name, description and cover-image fields.

Do not change write queries, visibility rules or existing route semantics.

- [ ] **Step 4: Implement NotificationTargetPreviewReader**

Group incoming keys by NotificationTargetType, deduplicate IDs, call the matching batch repository method once, and map entity results back by NotificationTargetKey. Collect content references only from diary and dropped targets before calling ContentCardAssembler once. A Pick preview contains its template identity, not all selected answers. Return UNAVAILABLE for keys absent from the database. Keep all database work read-only.

- [ ] **Step 5: Run focused unit and repository tests**

Run:
~~~powershell
.\mvnw.cmd test "-Dtest=NotificationTargetPreviewReaderTest,*RepositoryTest"
~~~

Expected: PASS. If broad repository selection is too slow, run each modified repository test class explicitly and record the exact list in the task ledger.

- [ ] **Step 6: Review query shape**

Inspect Hibernate SQL or repository query assertions to confirm no loop invokes findById for individual notification targets. Verify a target belonging to another user is only presented after the notification itself has been scoped to its recipient.

- [ ] **Step 7: Commit the completed batch reader**

Run:
~~~powershell
git add src/main/java/com/watchwise/watchwise_api/comment/repository src/main/java/com/watchwise/watchwise_api/diaryentry/repository src/main/java/com/watchwise/watchwise_api/droppedentry/repository src/main/java/com/watchwise/watchwise_api/userlist/repository src/main/java/com/watchwise/watchwise_api/pick/repository src/main/java/com/watchwise/watchwise_api/pickstemplate/repository src/main/java/com/watchwise/watchwise_api/notification src/test/java/com/watchwise/watchwise_api
git commit -m "feat(notification): load social target previews"
git log -1 --format=%B
~~~

Expected: the message has no Co-Authored-By trailer or other self-attribution.

### Task 4: Assemble visual notification rows

**Files:**
- Create: src/main/java/com/watchwise/watchwise_api/notification/service/impl/NotificationViewAssembler.java
- Modify: src/main/java/com/watchwise/watchwise_api/notification/repository/NotificationRepository.java
- Test: src/test/java/com/watchwise/watchwise_api/notification/service/impl/NotificationViewAssemblerTest.java

**Consumes:** NotificationResponseDTO or Notification entity fields, ContentCardAssembler, PersonPreviewResolver, NotificationTargetPreviewReader, UserRepository and the DTOs from Tasks 1-3.

**Produces:**
~~~java
public List<NotificationViewDTO> assemble(
        List<NotificationResponseDTO> notifications,
        UUID viewerId
)
~~~

And repository projection:
~~~java
public interface NotificationCountsProjection {
    long getTotalNotifications();
    long getUnreadNotifications();
}

@Query("""
        SELECT COUNT(n) AS totalNotifications,
               COALESCE(SUM(CASE WHEN n.isRead = false THEN 1 ELSE 0 END), 0) AS unreadNotifications
        FROM Notification n
        WHERE n.user.id = :userId
        """)
NotificationCountsProjection findCountsByUserId(UUID userId);
~~~

- [ ] **Step 1: Write failing assembler tests**

Cover:
- RELEASE, ANNOUNCED_DATE, CANCELLED, RENEWED and NEW_EPISODE resolve their content into one contentCard map.
- FOLLOWED_PERSON_NEW_CREDIT resolves duplicate personTmdbId values once and exposes name/profile URL.
- LIKE_RECEIVED and COMMENT_RECEIVED attach target previews returned by the reader.
- unavailable TMDB metadata returns a contentCard/person with PARTIAL or UNAVAILABLE and keeps the notification row.
- a repeated content reference is assembled once.
- raw id, type, message, isRead, timestamps, latestActor, targetType, targetId and interactionCount are copied unchanged.

- [ ] **Step 2: Run the focused assembler test to verify it fails**

Run: .\mvnw.cmd test "-Dtest=NotificationViewAssemblerTest"
Expected: compilation failure because NotificationViewAssembler does not exist.

- [ ] **Step 3: Implement NotificationViewAssembler**

Build one ContentCardContext from the recipient's language/region preferences. Deduplicate non-null notification content references and call ContentCardAssembler once. Pass deduplicated followed-person IDs to PersonPreviewResolver once. Collect social NotificationTargetKey values, invoke NotificationTargetPreviewReader once, then produce NotificationViewDTO rows in original sort order.

- [ ] **Step 4: Add count queries and verify repository behavior**

Implement findCountsByUserId on NotificationRepository. It returns total and unread counts in one query scoped only by userId; isRead filtering for the page must not affect it.

- [ ] **Step 5: Run focused tests**

Run:
~~~powershell
.\mvnw.cmd test "-Dtest=NotificationViewAssemblerTest,NotificationRepositoryTest"
~~~

Expected: PASS.

- [ ] **Step 6: Perform an edge-case review**

Verify these cases keep a visible row: deleted social target, deleted followed TMDB person, notification without content, social notification without latestActor, and empty page. Verify the assembler performs no writes and has no pagination responsibility.

- [ ] **Step 7: Commit the assembler**

Run:
~~~powershell
git add src/main/java/com/watchwise/watchwise_api/notification src/test/java/com/watchwise/watchwise_api/notification
git commit -m "feat(notification): assemble visual notification rows"
git log -1 --format=%B
~~~

Expected: the message has no Co-Authored-By trailer or other self-attribution.

### Task 5: Expose the paginated notification view endpoint

**Files:**
- Modify: src/main/java/com/watchwise/watchwise_api/notification/service/NotificationService.java
- Modify: src/main/java/com/watchwise/watchwise_api/notification/service/impl/NotificationServiceImpl.java
- Modify: src/main/java/com/watchwise/watchwise_api/notification/controller/NotificationController.java
- Test: src/test/java/com/watchwise/watchwise_api/notification/service/impl/NotificationServiceImplTest.java
- Test: src/test/java/com/watchwise/watchwise_api/notification/controller/NotificationControllerIntegrationTest.java

**Consumes:** NotificationViewAssembler and NotificationRepository.findCountsByUserId.

**Produces:**
~~~java
NotificationPageViewDTO getNotificationsView(
        UUID authenticatedUserId,
        Boolean isRead,
        int page,
        int size
);
~~~

HTTP:
~~~http
GET /notifications/view?isRead={true|false}&page={project page convention}&size={1..20}
~~~

- [ ] **Step 1: Write failing service and controller tests**

Cover:
- unauthenticated request is rejected using the established security response.
- GET /notifications/view returns only the authenticated user rows.
- optional isRead applies to rows and totalElements but totalNotifications and unreadNotifications remain global for the recipient.
- requested size above 20 returns a page with size 20.
- page 0 and page 1 both preserve the current PageRequestFactory first-page behavior; page 2 maps to the second page.
- empty page still returns valid pagination and global counts.
- GET /notifications remains byte-for-byte compatible in response shape.
- PATCH /notifications/{notificationId}/read still marks an item read.
- follow request accept/decline actions remain usable with latestActor id from a view row.

- [ ] **Step 2: Run the focused tests to verify they fail**

Run:
~~~powershell
.\mvnw.cmd test "-Dtest=NotificationServiceImplTest,NotificationControllerIntegrationTest"
~~~

Expected: failure because getNotificationsView and GET /notifications/view do not exist.

- [ ] **Step 3: Add the service method**

Build the PageRequest with pageRequestFactory.build(page, size, 20), preserving the API's established page convention. Reuse the existing repository method that filters by recipient and optional read state. Map the page content through NotificationViewAssembler. Call findCountsByUserId once for the same authenticated user. Build NotificationPageViewDTO directly from the Page metadata and the aggregate projection.

- [ ] **Step 4: Add the controller route**

Expose GET /notifications/view beside GET /notifications. Use the current authenticated principal mechanism and the existing PageRequestFactory or equivalent parameter binding, then enforce the visual cap in the service. Return HTTP 200 with NotificationPageViewDTO.

- [ ] **Step 5: Run focused endpoint verification**

Run:
~~~powershell
.\mvnw.cmd test "-Dtest=NotificationServiceImplTest,NotificationControllerIntegrationTest,NotificationViewAssemblerTest,NotificationTargetPreviewReaderTest"
~~~

Expected: PASS.

- [ ] **Step 6: Review error and authorization behavior**

Check invalid page/size and invalid isRead binding resolve through the project ApiError handlers. Check target IDs never authorize access by themselves; only the recipient-scoped notification page decides which preview is eligible to render.

- [ ] **Step 7: Commit the endpoint**

Run:
~~~powershell
git add src/main/java/com/watchwise/watchwise_api/notification src/test/java/com/watchwise/watchwise_api/notification
git commit -m "feat(notification): expose visual notification page"
git log -1 --format=%B
~~~

Expected: the message has no Co-Authored-By trailer or other self-attribution.

### Task 6: Synchronize API and screen documentation

**Files:**
- Modify: docs/context/openapi.yaml
- Modify: docs/context/progress.md
- Modify: docs/context/business-rules.md
- Modify: docs/telas-reqs-definitivo.md

**Consumes:** final endpoint, DTOs, status behavior and page-size cap from Tasks 1-5.

**Produces:** accurate route contract and screen integration guidance.

- [ ] **Step 1: Document GET /notifications/view in OpenAPI**

Define isRead, page and size query parameters; document the cap 20 and the established page convention; add schemas for PersonPreviewDTO and the three notification view DTOs. State that totalElements honors isRead while totalNotifications and unreadNotifications are recipient-global.

- [ ] **Step 2: Update business and progress documentation**

Document only implemented behavior: visual reads are recipient-scoped, missing external/local previews remain visible with availability status, and the raw notification route remains unchanged. Append the completed work to the current chronological day in progress.md.

- [ ] **Step 3: Update the definitive screen requirements**

Add Notifications following the existing screen format:
- initial load uses GET /notifications/view.
- tabs pass isRead true or false.
- frontend groups by createdAt, falling back to updatedAt for aggregated social activity display.
- card uses contentCard, person uses person, comments/likes use targetPreview.
- accept/decline uses existing follow-request endpoints with latestActor.id.
- a failed preview displays a neutral unavailable state, never hides the notification.

- [ ] **Step 4: Validate documentation**

Run:
~~~powershell
git diff --check
rg -n "/notifications/view|NotificationPageViewDTO|unreadNotifications" docs/context/openapi.yaml docs/context/progress.md docs/context/business-rules.md docs/telas-reqs-definitivo.md
~~~

Expected: no whitespace errors and all four documentation locations mention the final contract.

- [ ] **Step 5: Keep documentation uncommitted**

Do not include docs files in feature commits unless the user explicitly asks for a documentation commit.

### Task 7: Independent review and full verification

**Files:**
- Review: all changed production and test files from Tasks 1-6.

**Consumes:** completed visual endpoint and documentation.

**Produces:** a review report with resolved findings and verification evidence.

- [ ] **Step 1: Run the notification suite**

Run:
~~~powershell
.\mvnw.cmd test "-Dtest=NotificationServiceImplTest,NotificationControllerIntegrationTest,NotificationViewAssemblerTest,NotificationTargetPreviewReaderTest,NotificationRepositoryTest,TmdbPersonPreviewResolverTest,PickTargetPreviewAssemblerTest"
~~~

Expected: PASS.

- [ ] **Step 2: Run the complete test suite**

Run:
~~~powershell
.\mvnw.cmd test
~~~

Expected: PASS. If Docker-backed repository tests cannot start, record the exact Docker/Testcontainers failure and run every non-container affected test; do not claim full verification.

- [ ] **Step 3: Independently inspect for regressions**

Review these assertions:
- no visual route calls content details in a loop;
- no visual route writes Content, Notification or any target;
- page size cannot exceed 20;
- raw routes and follow request actions retain their previous contract;
- every NotificationType has a safe rendering fallback;
- errors use ApiError rather than a Spring default body;
- no changed commit has Co-Authored-By or self-attribution.

- [ ] **Step 4: Make a final corrective commit only if review finds a code defect**

Use a focused Conventional Commit message matching the correction. Immediately run git log -1 --format=%B and verify no Co-Authored-By trailer or self-attribution.

- [ ] **Step 5: Report completion with evidence**

Report the endpoint, page cap, resilience semantics, tests run and any known external-environment limitation. Do not push unless the user separately authorizes a push.

