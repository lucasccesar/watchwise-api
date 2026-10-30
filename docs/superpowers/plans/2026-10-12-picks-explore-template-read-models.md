# Picks Explore and Template Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Complete the read contracts required by Explore Picks, My Picks and Create Template, while keeping reads batched, preserving raw API compatibility and preventing unusable FIXED categories.

**Architecture:** Keep GET /picks-templates and PicksTemplatePreviewAssembler as the single Explore read model. Add additive My Picks and owned-template visual reads; their implementation reuses a deep extension of PickPreviewAssembler plus PicksTemplatePreviewAssembler instead of invoking getPick per row. Fixed-option visuals are delivered only by a new paginated options-view route, so full template reads never resolve unbounded TMDB metadata. A concrete PickTargetPreviewAssembler reuses ContentCardAssembler for content and the existing TmdbClient person-details cache for people.

**Tech Stack:** Spring Boot 4.1, Java 21, Spring Data JPA, PostgreSQL, Caffeine, MapStruct, Maven, Testcontainers, JUnit 5, Mockito, existing TMDB client and ContentCardAssembler.

## Global Constraints

- Before implementation starts, announce and obtain explicit approval for these additive routes:

~~~http
GET /users/me/picks/view
GET /users/me/picks-templates/view
GET /picks-templates/{templateId}/categories/{categoryId}/options/view
~~~

- Keep all current Picks/Template endpoints backward compatible.
- Do not call GET /contents/details?ids= or create Content references on any read.
- Reuse PicksTemplatePreviewAssembler. Do not create another template-card module.
- Use ContentCardAssembler with BASIC_METADATA only for MOVIE, SERIES, EPISODE and person context-content. PERSON never passes through ContentCardAssembler.
- Fix TmdbClient.getPersonDetails to use existing tmdbPersonDetailsCache through cachedLookup. Success and not-found can cache; Unavailable must not.
- The visual options route enriches only the selected final page. GET /picks-templates/{templateId} remains raw with respect to option metadata.
- Create Template reuses /search for MOVIE, SERIES and PERSON, then existing contextual season/episode selection routes. It has no persisted draft, import route or new target-search route.
- OPEN accepts zero options and rejects non-empty options. FIXED requires at least one option in every new/mutated category.
- Existing historical empty FIXED categories remain readable and repairable by adding an option; no migration deletes them.
- Documentation changes are updated with the implementing task but remain worktree-only unless explicitly requested for commit.
- Test methods use should<Behavior>When<Condition> and DisplayName values use [method] Should ... - When ....
- Every task follows red-green-refactor, focused verification and Conventional Commit messages without Co-Authored-By.

---

## File map

### New files

- src/main/java/com/watchwise/watchwise_api/pick/dto/PickPersonPreviewDTO.java — person name, profile image, TMDB ID and preview status.
- src/main/java/com/watchwise/watchwise_api/pick/dto/PickTargetPreviewDTO.java — one content or person visual target plus optional context card.
- src/main/java/com/watchwise/watchwise_api/pick/dto/PickOptionViewDTO.java — raw option target paired with a visual preview.
- src/main/java/com/watchwise/watchwise_api/pick/dto/PickViewCardDTO.java — My Picks card with template context, answer counts, progress and social state.
- src/main/java/com/watchwise/watchwise_api/pick/service/impl/PickTargetPreviewAssembler.java — concrete order-preserving target-preview module with private deduplication.
- src/test/java/com/watchwise/watchwise_api/pick/dto/PickTargetPreviewDTOTest.java
- src/test/java/com/watchwise/watchwise_api/pick/dto/PickViewCardDTOTest.java
- src/test/java/com/watchwise/watchwise_api/pick/service/impl/PickTargetPreviewAssemblerTest.java
- src/test/java/com/watchwise/watchwise_api/pick/controller/PickViewControllerIntegrationTest.java
- src/test/java/com/watchwise/watchwise_api/pickstemplate/controller/PicksTemplateOptionViewControllerIntegrationTest.java

### Existing files to modify

- src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClient.java
- src/main/java/com/watchwise/watchwise_api/pick/repository/PickRepository.java
- src/main/java/com/watchwise/watchwise_api/pick/service/PickService.java
- src/main/java/com/watchwise/watchwise_api/pick/service/impl/PickServiceImpl.java
- src/main/java/com/watchwise/watchwise_api/pick/service/impl/PickPreviewAssembler.java
- src/main/java/com/watchwise/watchwise_api/pick/controller/PickController.java
- src/main/java/com/watchwise/watchwise_api/pickstemplate/repository/PicksTemplateRepository.java
- src/main/java/com/watchwise/watchwise_api/pickstemplate/service/PicksTemplateService.java
- src/main/java/com/watchwise/watchwise_api/pickstemplate/service/impl/PicksTemplateServiceImpl.java
- src/main/java/com/watchwise/watchwise_api/pickstemplate/controller/PicksTemplateController.java
- src/main/java/com/watchwise/watchwise_api/pickstemplate/service/PicksTemplateOptionService.java
- src/main/java/com/watchwise/watchwise_api/pickstemplate/service/impl/PicksTemplateOptionServiceImpl.java
- src/main/java/com/watchwise/watchwise_api/pickstemplate/controller/PicksTemplateOptionController.java
- src/main/java/com/watchwise/watchwise_api/pickstemplate/service/impl/PicksTemplateCategoryServiceImpl.java
- focused Picks, Template and TMDB caching test classes
- docs/context/openapi.yaml, docs/context/business-rules.md, docs/context/progress.md and docs/telas-reqs-definitivo.md

---

### Task 1: Define contracts and approve the route addition

**Files:**

- Create: pick/dto/PickPersonPreviewDTO.java
- Create: pick/dto/PickTargetPreviewDTO.java
- Create: pick/dto/PickOptionViewDTO.java
- Create: pick/dto/PickViewCardDTO.java
- Test: pick/dto/PickTargetPreviewDTOTest.java
- Test: pick/dto/PickViewCardDTOTest.java
- Modify: docs/context/openapi.yaml

**Interfaces:**

~~~java
public record PickPersonPreviewDTO(
        String tmdbId,
        String name,
        String profileUrl,
        ContentPreviewStatus previewStatus) {}

public record PickTargetPreviewDTO(
        ContentCardDTO contentCard,
        PickPersonPreviewDTO person,
        ContentCardDTO contextCard) {}

public record PickOptionViewDTO(
        UUID id,
        PickOptionSearchDTO target,
        PickTargetPreviewDTO preview) {}

public record PickViewCardDTO(
        UUID id,
        PicksTemplatePreviewDTO picksTemplate,
        UserPreviewDTO user,
        PickVisibility visibility,
        LocalDateTime createdAt,
        PickProgress progress,
        long answeredCategoriesCount,
        long totalCategoriesCount,
        List<PickAnsweredCategoryPreviewDTO> answeredCategories,
        Integer likesCount,
        long commentsCount,
        Boolean isLikedByViewer) {}
~~~

- [ ] Step 1: Announce the three route additions and receive explicit confirmation.

- [ ] Step 2: Write failing DTO tests covering one content target, one person target, person context, unavailable person metadata, invalid dual primary targets, immutable lists and five-answer cap.

- [ ] Step 3: Run red:

~~~powershell
.\mvnw.cmd test "-Dtest=PickTargetPreviewDTOTest,PickViewCardDTOTest"
~~~

- [ ] Step 4: Create the DTOs, enforce their invariants and add OpenAPI schema skeletons after route approval.

- [ ] Step 5: Run the same tests green.

- [ ] Step 6: Commit:

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/pick/dto src/test/java/com/watchwise/watchwise_api/pick/dto
git commit -m "feat(pick): define visual picks contracts"
~~~

Confirm no Co-Authored-By trailer.

---

### Task 2: Enforce valid FIXED categories

**Files:**

- Modify: pickstemplate/service/impl/PicksTemplateServiceImpl.java
- Modify: pickstemplate/service/impl/PicksTemplateCategoryServiceImpl.java
- Modify: pickstemplate/service/impl/PicksTemplateOptionServiceImpl.java
- Test: pickstemplate/service/impl/PicksTemplateServiceImplTest.java
- Test: pickstemplate/service/impl/PicksTemplateCategoryServiceImplTest.java
- Test: pickstemplate/service/impl/PicksTemplateOptionServiceImplTest.java
- Modify: OpenAPI, business-rules and progress docs.

**Interfaces:**

- Template creation, category creation and OPEN-to-FIXED patch reject a FIXED category with absent/empty options using BadRequestException.
- OPEN rejects any supplied options.
- Deleting a final FIXED option rejects with ConflictException.
- Adding an option to a pre-existing empty FIXED category remains allowed.

- [ ] Step 1: Write failing tests:

~~~java
void shouldRejectFixedCategoryWithoutOptionsWhenCreatingTemplate()
void shouldRejectFixedCategoryWithoutOptionsWhenAddingCategory()
void shouldRejectChangingOpenCategoryToFixedWithoutOptionsWhenUpdatingCategory()
void shouldRejectDeletingLastFixedOptionWhenCategoryHasNoSelections()
void shouldAllowAddingOptionToHistoricalEmptyFixedCategoryWhenRepairingTemplate()
~~~

- [ ] Step 2: Run red:

~~~powershell
.\mvnw.cmd test "-Dtest=PicksTemplateServiceImplTest,PicksTemplateCategoryServiceImplTest,PicksTemplateOptionServiceImplTest"
~~~

- [ ] Step 3: Implement one shared package-private validator used before template/category writes. In deleteOption, hold the existing lock, count stored options and reject only the final removal.

- [ ] Step 4: Update the 400/409 OpenAPI contracts, business rule and progress entry.

- [ ] Step 5: Run green:

~~~powershell
.\mvnw.cmd test "-Dtest=PicksTemplateServiceImplTest,PicksTemplateCategoryServiceImplTest,PicksTemplateOptionServiceImplTest,PickServiceImplTest,PickSelectionServiceImplTest"
~~~

- [ ] Step 6: Commit:

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/pickstemplate src/test/java/com/watchwise/watchwise_api/pickstemplate
git commit -m "fix(pickstemplate): require fixed category options"
~~~

Confirm no Co-Authored-By trailer.

---

### Task 3: Add paginated target visual previews

**Files:**

- Create: pick/service/impl/PickTargetPreviewAssembler.java
- Modify: common/tmdb/TmdbClient.java
- Modify: pickstemplate/service/PicksTemplateOptionService.java
- Modify: pickstemplate/service/impl/PicksTemplateOptionServiceImpl.java
- Modify: pickstemplate/controller/PicksTemplateOptionController.java
- Test: common/tmdb/TmdbClientCachingTest.java
- Test: pick/service/impl/PickTargetPreviewAssemblerTest.java
- Test: pickstemplate/service/impl/PicksTemplateOptionServiceImplTest.java
- Test: pickstemplate/controller/PicksTemplateOptionViewControllerIntegrationTest.java
- Modify: OpenAPI, business-rules, progress and screen requirements docs.

**Interfaces:**

~~~java
public final class PickTargetPreviewAssembler {
    public List<PickTargetPreviewDTO> assemble(
            List<PickOptionSearchDTO> targets,
            ContentCardContext cardContext);
}

Page<PickOptionViewDTO> getOptionViews(
        UUID viewerId,
        UUID templateId,
        UUID categoryId,
        String query,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer page,
        Integer size);
~~~

- assemble returns one result per input target in the same order.
- Private keys deduplicate content coordinates, context coordinates and person IDs.
- GET /options/view has the same filters, visibility, validation and rate-limit behavior as existing GET /options, but enriches only page content.

- [ ] Step 1: Write failing tests for duplicate content, person plus context, partial/unavailable content, unavailable person metadata, page-first enrichment, no Content creation and cached person details.

- [ ] Step 2: Run red:

~~~powershell
.\mvnw.cmd test "-Dtest=TmdbClientCachingTest,PickTargetPreviewAssemblerTest,PicksTemplateOptionServiceImplTest,PicksTemplateOptionViewControllerIntegrationTest"
~~~

- [ ] Step 3: Replace TmdbClient.getPersonDetails getIfPresent/callWithRetry logic with cachedLookup using tmdbPersonDetailsCache. Preserve retry behavior and do not cache Unavailable.

- [ ] Step 4: Implement PickTargetPreviewAssembler. Use ContentCardAssembler with BASIC_METADATA for content coordinates, reuse the existing bounded metadata executor for deduplicated person calls, convert profile paths with TmdbImageUrlBuilder and retain raw IDs when metadata fails.

- [ ] Step 5: Add GET /picks-templates/{templateId}/categories/{categoryId}/options/view. Reuse searchOptions to obtain the final raw page, assemble only page content, then map it to PickOptionViewDTO.

- [ ] Step 6: Update OpenAPI and requirements. State that unsaved wizard selection continues to use /search and contextual episode selection, while reloaded fixed options use /options/view.

- [ ] Step 7: Run green:

~~~powershell
.\mvnw.cmd test "-Dtest=TmdbClientCachingTest,PickTargetPreviewAssemblerTest,PicksTemplateOptionServiceImplTest,PicksTemplateOptionViewControllerIntegrationTest,ContentCardAssemblerTest"
~~~

- [ ] Step 8: Commit:

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/common/tmdb src/main/java/com/watchwise/watchwise_api/pick src/main/java/com/watchwise/watchwise_api/pickstemplate src/test/java/com/watchwise/watchwise_api/common/tmdb src/test/java/com/watchwise/watchwise_api/pick src/test/java/com/watchwise/watchwise_api/pickstemplate
git commit -m "feat(pick): add visual option previews"
~~~

Confirm no Co-Authored-By trailer.

---

### Task 4: Add My Picks and owned-template visual reads

**Files:**

- Modify: pick/repository/PickRepository.java
- Modify: pick/service/PickService.java
- Modify: pick/service/impl/PickServiceImpl.java
- Modify: pick/service/impl/PickPreviewAssembler.java
- Modify: pick/controller/PickController.java
- Modify: pickstemplate/repository/PicksTemplateRepository.java
- Modify: pickstemplate/service/PicksTemplateService.java
- Modify: pickstemplate/service/impl/PicksTemplateServiceImpl.java
- Modify: pickstemplate/controller/PicksTemplateController.java
- Test: pick/service/impl/PickPreviewAssemblerTest.java
- Test: pick/service/impl/PickServiceImplTest.java
- Test: pick/controller/PickViewControllerIntegrationTest.java
- Test: pickstemplate/controller/PicksTemplateControllerIntegrationTest.java
- Modify: OpenAPI, business-rules, progress and screen requirements docs.

**Interfaces:**

~~~java
Page<PickViewCardDTO> getMyPicksView(UUID viewerId, Integer page, Integer size);

Page<PicksTemplatePreviewDTO> getMyTemplatesView(
        UUID viewerId,
        Integer page,
        Integer size,
        PicksTemplateSort sort);
~~~

~~~http
GET /users/me/picks/view?page=1&size=5
GET /users/me/picks-templates/view?page=1&size=5&sort=RECENT
~~~

- PickViewCardDTO uses one shared batch of raw Pick/category/selection/social/template data.
- Existing PickPreviewAssembler keeps assemblePage for raw PickPreviewDTO and gains assembleViewPage for PickViewCardDTO. Its shared loaded-data representation stays private.
- answeredCategoriesCount counts valid current selections. totalCategoriesCount counts all template categories. The visual answer list is capped at five after ordering.
- Owned templates are queried by creator ID in PostgreSQL, never fetched globally then filtered in Java.

- [ ] Step 1: Write failing tests for owned Pick ordering, pagination, empty pages, one template-preview batch, EMPTY/PARTIAL/COMPLETE progress, valid answer counts and no leakage of another user's data.

- [ ] Step 2: Run red:

~~~powershell
.\mvnw.cmd test "-Dtest=PickServiceImplTest,PickPreviewAssemblerTest,PickViewControllerIntegrationTest,PicksTemplateControllerIntegrationTest"
~~~

- [ ] Step 3: Add repository methods:

~~~java
Page<Pick> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable pageable);
Page<PicksTemplate> findByCreatorIdOrderByCreatedAtDescIdDesc(UUID creatorId, Pageable pageable);
~~~

Add PostgreSQL repository tests for ordering and creator filtering.

- [ ] Step 4: Refactor PickPreviewAssembler into one deep module. Load selections, categories, fixed options, likes, comments and template previews once for the final page. Expose raw and visual adapters from that data. Do not call getPick per card and do not load target visuals for My Picks cards because this screen shows template cover/category labels, not every selected title.

- [ ] Step 5: Wire the two controllers with PageResponseDTO.of and assert ApiError for invalid pagination/auth failures.

- [ ] Step 6: Update docs for personal visual reads, page semantics, template context and raw endpoint compatibility.

- [ ] Step 7: Run green:

~~~powershell
.\mvnw.cmd test "-Dtest=PickServiceImplTest,PickPreviewAssemblerTest,PickViewControllerIntegrationTest,PicksTemplateControllerIntegrationTest,PickControllerIntegrationTest"
~~~

- [ ] Step 8: Commit:

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/pick src/main/java/com/watchwise/watchwise_api/pickstemplate src/test/java/com/watchwise/watchwise_api/pick src/test/java/com/watchwise/watchwise_api/pickstemplate
git commit -m "feat(pick): add visual personal reads"
~~~

Confirm no Co-Authored-By trailer.

---

### Task 5: Verify Explore and Create Template without extra target search

**Files:**

- Modify: pickstemplate controller/service test classes.
- Modify: existing search and content-selection integration tests only when an actual regression is observed.
- Modify: docs/telas-reqs-definitivo.md.

**Interfaces:**

- Explore stays on GET /picks-templates with origin, name, sort, page and size.
- Wizard search uses existing GET /search for MOVIE, SERIES, PERSON and contextual episode selection.
- Wizard holds search-result title/poster/profile locally until one final POST /picks-templates.
- No draft persistence, import endpoint or generic target-search route is added.

- [ ] Step 1: Add Explore tests for POPULAR_WEEK, MOST_PICKED, RECENT, OFFICIAL/COMMUNITY, escaped name search, positive weekly activity, creator/avatar, social counts, isLikedByViewer, myPicksCount and latestMyPickId.

- [ ] Step 2: Add creation-flow tests for MOVIE/SERIES/EPISODE/PERSON targets, optional person context, content eligibility dates, ignored person eligibility, OPEN empty, FIXED populated, FIXED empty rejection and TMDB not-found/unavailable ApiError.

- [ ] Step 3: Run:

~~~powershell
.\mvnw.cmd test "-Dtest=PicksTemplateControllerIntegrationTest,PicksTemplateServiceImplTest,PicksTemplateCategoryServiceImplTest,PicksTemplateOptionServiceImplTest,SearchControllerIntegrationTest,ContentSelectionControllerIntegrationTest"
~~~

- [ ] Step 4: Update screen requirements with the exact route-to-block mapping, local wizard state rule and options-view reload rule.

- [ ] Step 5: Commit:

~~~powershell
git add src/test/java/com/watchwise/watchwise_api/pick src/test/java/com/watchwise/watchwise_api/pickstemplate src/test/java/com/watchwise/watchwise_api/search src/test/java/com/watchwise/watchwise_api/content
git commit -m "test(pick): cover explore and template flows"
~~~

Confirm no Co-Authored-By trailer.

---

### Task 6: Final verification and independent review

**Files:**

- Review every file from Tasks 1–5.
- Review: docs/superpowers/plans/2026-10-12-picks-explore-template-read-models.md.

- [ ] Step 1: Verify docs:

~~~powershell
rg -n "users/me/picks/view|users/me/picks-templates/view|options/view|PickViewCard|PickOptionView|PickTargetPreview|FIXED|OPEN|POPULAR_WEEK" docs/context/openapi.yaml docs/context/business-rules.md docs/telas-reqs-definitivo.md
~~~

- [ ] Step 2: Run focused suite:

~~~powershell
.\mvnw.cmd test "-Dtest=TmdbClientCachingTest,PicksTemplateControllerIntegrationTest,PicksTemplateServiceImplTest,PicksTemplateCategoryServiceImplTest,PicksTemplateOptionServiceImplTest,PicksTemplateOptionViewControllerIntegrationTest,PickControllerIntegrationTest,PickServiceImplTest,PickViewControllerIntegrationTest,PickPreviewAssemblerTest,PickTargetPreviewAssemblerTest,ContentCardAssemblerTest"
~~~

- [ ] Step 3: Run complete suite:

~~~powershell
.\mvnw.cmd clean test "-Dsurefire.exitTimeout=120"
~~~

Expected: zero failures, zero errors and zero skipped tests.

- [ ] Step 4: Review for raw-contract compatibility, page-first enrichment, no N+1 reads, no Content writes, correct person cache semantics, final-page-only option previews, FIXED invariant coverage, pre-pagination visibility, ApiError consistency, parameterized SQL and absence of Co-Authored-By trailers.

- [ ] Step 5: Commit only a related correction discovered during review:

~~~powershell
git status --short
git diff --check
git log --format='%h %s%n%b' -n 12
~~~

Do not push from this plan. Integration requires explicit user authorization.

