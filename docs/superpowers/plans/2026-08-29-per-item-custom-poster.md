# Per-item custom poster (Top5Entry, UserListItem) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing `customPosterUrl` field (already on `DiaryEntry`) to `Top5Entry` and `UserListItem`, so a user can set a per-item alternate poster when they add a movie/series to their Top 5 or to a list — matching what already works for logging (`DiaryEntry`).

**Architecture:** Same shape as `DiaryEntry.customPosterUrl` in every layer: nullable `VARCHAR(2048)` column, `@Size(max = 2048) @URL` validated DTO field, MapStruct auto-maps it by name (no explicit `@Mapping` needed). `Top5Entry` gets a brand-new `PATCH /users/me/top5/{type}/{top5EntryId}` endpoint (it previously had no update operation at all). `UserListItem` rejects `customPosterUrl` on nested-list items (`childList != null`) with `400`, both on create and on patch.

**Tech Stack:** Spring Boot 4.1 / Java 21, Spring Data JPA, MapStruct, Flyway, JUnit 5 + Mockito + AssertJ, Testcontainers (Postgres) for integration tests.

## Global Constraints

- Validation for every new `customPosterUrl` field must be exactly `@Size(max = 2048)` + `org.hibernate.validator.constraints.URL`, matching `DiaryEntryCreationDTO.customPosterUrl` — no format/length deviation.
- `null` means "leave unchanged" on every PATCH (`Top5EntryPatchDTO`, `UserListItemPatchDTO`) — matches `DiaryEntryUpdateDTO.customPosterUrl` semantics. There is no way to clear a poster back to null once set (same limitation `DiaryEntry` already has).
- Every record DTO field addition in this plan MUST add a backward-compatible overloaded constructor delegating to the new canonical constructor with `customPosterUrl = null`, exactly like `DiaryEntryCreationDTO`/`DiaryEntryResponseDTO` already do. This is not optional — the existing test suites construct these DTOs positionally in 15-40+ places each, and skipping the overload breaks all of them.
- No code comments (repo convention) — every code block below is comment-free on purpose; keep it that way.
- No DB `CHECK` constraint for the "content-only" restriction on `user_list_items.custom_poster_url` — enforced only in `UserListItemServiceImpl`, mirroring how `DiaryEntry.watchedInTheater`'s MOVIE-only rule is application-level, not a DB constraint.

---

### Task 1: Migration + entity fields (Top5Entry, UserListItem)

**Files:**
- Create: `src/main/resources/db/migration/V36__add-custom-poster-url-to-top5-entries-and-user-list-items.sql`
- Modify: `src/main/java/com/watchwise/watchwise_api/top5entry/entity/Top5Entry.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/entity/UserListItem.java`

**Interfaces:**
- Produces: `Top5Entry.getCustomPosterUrl()/setCustomPosterUrl(String)`, `UserListItem.getCustomPosterUrl()/setCustomPosterUrl(String)` — consumed by Task 2 (Top5Entry DTOs/service) and Task 4 (UserListItem DTOs/service).

- [ ] **Step 1: Create the migration**

```sql
ALTER TABLE top5_entries ADD COLUMN custom_poster_url VARCHAR(2048);
ALTER TABLE user_list_items ADD COLUMN custom_poster_url VARCHAR(2048);
```

- [ ] **Step 2: Add the field to `Top5Entry`**

In `src/main/java/com/watchwise/watchwise_api/top5entry/entity/Top5Entry.java`, add after the `position` field (before `createdAt`):

```java
    @Column(name = "custom_poster_url", length = 2048)
    @Setter
    private String customPosterUrl;
```

- [ ] **Step 3: Add the field to `UserListItem`**

In `src/main/java/com/watchwise/watchwise_api/userlist/entity/UserListItem.java`, add after the `description` field (before `createdAt`):

```java
    @Column(name = "custom_poster_url", length = 2048)
    @Setter
    private String customPosterUrl;
```

- [ ] **Step 4: Verify the project still compiles and existing tests pass**

Run: `mvnw.cmd test "-Dtest=Top5EntryRepositoryTest,UserListItemRepositoryTest"`
Expected: PASS (Hibernate's `ddl-auto=validate` confirms the new columns match the entities; no existing test references the new field yet, so nothing else should change).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V36__add-custom-poster-url-to-top5-entries-and-user-list-items.sql src/main/java/com/watchwise/watchwise_api/top5entry/entity/Top5Entry.java src/main/java/com/watchwise/watchwise_api/userlist/entity/UserListItem.java
git commit -m "feat(top5,lists): add custom_poster_url column to top5_entries and user_list_items"
```

---

### Task 2: Top5Entry — DTOs, service, controller, unit tests

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/top5entry/dto/Top5EntryPatchDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/top5entry/dto/Top5EntryCreationDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/top5entry/dto/Top5EntryResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/top5entry/service/Top5EntryService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/top5entry/service/impl/Top5EntryServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/top5entry/controller/Top5EntryController.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/top5entry/service/impl/Top5EntryServiceImplTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/top5entry/controller/Top5EntryControllerTest.java`

**Interfaces:**
- Consumes: `Top5Entry.getCustomPosterUrl()/setCustomPosterUrl(String)` (Task 1).
- Produces: `Top5EntryService.updateEntry(UUID userId, ContentType type, UUID top5EntryId, Top5EntryPatchDTO top5EntryPatchDTO): Top5EntryResponseDTO`, `Top5EntryController` `PATCH /users/me/top5/{type}/{top5EntryId}` — consumed by Task 3 (integration tests).

- [ ] **Step 1: Write the failing/updated unit tests**

In `Top5EntryServiceImplTest`, add this test right after `shouldInsertAtPositionOneWhenListIsEmpty` (still inside the `// ---------- insertEntry ----------` section):

```java
    @Test
    @DisplayName("[insertEntry] Should Persist CustomPosterUrl - When Provided")
    void shouldPersistCustomPosterUrlWhenProvided() {
        Top5Entry savedEntry = buildEntry(lucas, fightClub, ContentType.MOVIE, 1);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(top5EntryRepository.save(any(Top5Entry.class))).thenReturn(savedEntry);
        when(top5EntryMapper.top5EntryToResponseDto(savedEntry)).thenReturn(buildResponseDto(savedEntry));

        top5EntryService.insertEntry(lucasId, ContentType.MOVIE,
                new Top5EntryCreationDTO(fightClub.getTmdbId(), null, "https://example.com/poster.png"));

        verify(top5EntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getCustomPosterUrl()).isEqualTo("https://example.com/poster.png");
    }
```

Add a new section right after the `// ---------- removeEntry ----------` section's last test (`shouldThrowNotFoundExceptionWhenEntryTypeDoesNotMatchPathType`) and before `// ---------- helpers ----------`:

```java
    // ---------- updateEntry ----------

    @Test
    @DisplayName("[updateEntry] Should Update CustomPosterUrl - When A Different Value Is Provided")
    void shouldUpdateCustomPosterUrlWhenADifferentValueIsProvided() {
        Top5Entry entry = buildEntry(lucas, fightClub, ContentType.MOVIE, 1);
        entry.setCustomPosterUrl("https://example.com/old.png");
        when(top5EntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(top5EntryRepository.save(any(Top5Entry.class))).thenReturn(entry);
        when(top5EntryMapper.top5EntryToResponseDto(entry)).thenReturn(buildResponseDto(entry));

        top5EntryService.updateEntry(lucasId, ContentType.MOVIE, entry.getId(),
                new Top5EntryPatchDTO("https://example.com/new.png"));

        verify(top5EntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getCustomPosterUrl()).isEqualTo("https://example.com/new.png");
        verify(top5EntryRepository, times(1)).flush();
    }

    @Test
    @DisplayName("[updateEntry] Should Not Save - When CustomPosterUrl Is Null")
    void shouldNotSaveWhenCustomPosterUrlIsNullOnUpdate() {
        Top5Entry entry = buildEntry(lucas, fightClub, ContentType.MOVIE, 1);
        entry.setCustomPosterUrl("https://example.com/old.png");
        when(top5EntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(top5EntryMapper.top5EntryToResponseDto(entry)).thenReturn(buildResponseDto(entry));

        top5EntryService.updateEntry(lucasId, ContentType.MOVIE, entry.getId(), new Top5EntryPatchDTO(null));

        verify(top5EntryRepository, never()).save(any());
        verify(top5EntryRepository, never()).flush();
        assertThat(entry.getCustomPosterUrl()).isEqualTo("https://example.com/old.png");
    }

    @Test
    @DisplayName("[updateEntry] Should Throw BadRequestException - When Type Is Season")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonOnUpdate() {
        assertThatThrownBy(() -> top5EntryService.updateEntry(
                lucasId, ContentType.SEASON, UUID.randomUUID(), new Top5EntryPatchDTO("https://example.com/x.png")))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(top5EntryRepository);
    }

    @Test
    @DisplayName("[updateEntry] Should Throw NotFoundException - When Entry Does Not Exist")
    void shouldThrowNotFoundExceptionWhenEntryDoesNotExistOnUpdate() {
        UUID missingId = UUID.randomUUID();
        when(top5EntryRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> top5EntryService.updateEntry(
                lucasId, ContentType.MOVIE, missingId, new Top5EntryPatchDTO("https://example.com/x.png")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Top 5 entry not found");
    }

    @Test
    @DisplayName("[updateEntry] Should Throw NotFoundException - When Entry Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenEntryBelongsToADifferentUserOnUpdate() {
        User marina = User.builder()
                .id(marinaId)
                .username("marina")
                .email("marina@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Top5Entry marinasEntry = buildEntry(marina, fightClub, ContentType.MOVIE, 1);
        when(top5EntryRepository.findById(marinasEntry.getId())).thenReturn(Optional.of(marinasEntry));

        assertThatThrownBy(() -> top5EntryService.updateEntry(
                lucasId, ContentType.MOVIE, marinasEntry.getId(), new Top5EntryPatchDTO("https://example.com/x.png")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Top 5 entry not found");
    }

    @Test
    @DisplayName("[updateEntry] Should Throw NotFoundException - When Entry Type Does Not Match Path Type")
    void shouldThrowNotFoundExceptionWhenEntryTypeDoesNotMatchPathTypeOnUpdate() {
        Top5Entry movieEntry = buildEntry(lucas, fightClub, ContentType.MOVIE, 1);
        when(top5EntryRepository.findById(movieEntry.getId())).thenReturn(Optional.of(movieEntry));

        assertThatThrownBy(() -> top5EntryService.updateEntry(
                lucasId, ContentType.SERIES, movieEntry.getId(), new Top5EntryPatchDTO("https://example.com/x.png")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Top 5 entry not found");
    }
```

Add `import com.watchwise.watchwise_api.top5entry.dto.Top5EntryPatchDTO;` to the test file's imports.

In `Top5EntryControllerTest`, add after `shouldResolveTheCurrentUserIdFromTheSecurityContextWhenInsertingEntry` (still before the `// removeEntry` tests, or anywhere after the insertEntry tests):

```java
    @Test
    @DisplayName("[updateEntry] Should Return Ok With The Service Result - When Called")
    void shouldReturnOkWithTheServiceResultWhenUpdatingEntry() {
        UUID top5EntryId = UUID.randomUUID();
        Top5EntryPatchDTO patchDTO = new Top5EntryPatchDTO("https://example.com/new.png");
        Top5EntryResponseDTO dto = buildResponseDto();
        when(top5EntryService.updateEntry(currentUserId, ContentType.MOVIE, top5EntryId, patchDTO)).thenReturn(dto);

        ResponseEntity<Top5EntryResponseDTO> result = top5EntryController.updateEntry(MovieOrSeriesType.MOVIE, top5EntryId, patchDTO);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[updateEntry] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenUpdatingEntry() {
        UUID top5EntryId = UUID.randomUUID();
        Top5EntryPatchDTO patchDTO = new Top5EntryPatchDTO("https://example.com/new.png");
        when(top5EntryService.updateEntry(currentUserId, ContentType.MOVIE, top5EntryId, patchDTO)).thenReturn(buildResponseDto());

        top5EntryController.updateEntry(MovieOrSeriesType.MOVIE, top5EntryId, patchDTO);

        verify(top5EntryService).updateEntry(currentUserId, ContentType.MOVIE, top5EntryId, patchDTO);
    }
```

Add `import com.watchwise.watchwise_api.top5entry.dto.Top5EntryPatchDTO;` to that test file's imports.

- [ ] **Step 2: Run the tests to confirm they fail to compile**

Run: `mvnw.cmd test "-Dtest=Top5EntryServiceImplTest,Top5EntryControllerTest"`
Expected: COMPILE FAILURE — `Top5EntryPatchDTO` doesn't exist yet, `Top5EntryCreationDTO`'s 3-arg constructor doesn't exist yet, `Top5EntryService.updateEntry`/`Top5EntryController.updateEntry` don't exist yet.

- [ ] **Step 3: Create `Top5EntryPatchDTO`**

```java
package com.watchwise.watchwise_api.top5entry.dto;

import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record Top5EntryPatchDTO(
        @Size(max = 2048) @URL String customPosterUrl
) {
}
```

- [ ] **Step 4: Update `Top5EntryCreationDTO`**

```java
package com.watchwise.watchwise_api.top5entry.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record Top5EntryCreationDTO(
        @NotBlank String tmdbId,
        @Min(1) @Max(5) Integer position,
        @Size(max = 2048) @URL String customPosterUrl
) {
    public Top5EntryCreationDTO(String tmdbId, Integer position) {
        this(tmdbId, position, null);
    }
}
```

- [ ] **Step 5: Update `Top5EntryResponseDTO`**

```java
package com.watchwise.watchwise_api.top5entry.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;

import java.time.LocalDateTime;
import java.util.UUID;

public record Top5EntryResponseDTO(
        UUID id,
        ContentType type,
        ContentRefDTO content,
        Integer position,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String customPosterUrl
) {
    public Top5EntryResponseDTO(UUID id, ContentType type, ContentRefDTO content, Integer position,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, type, content, position, createdAt, updatedAt, null);
    }
}
```

- [ ] **Step 6: Add `updateEntry` to `Top5EntryService`**

```java
    Top5EntryResponseDTO updateEntry(UUID userId, ContentType type, UUID top5EntryId, Top5EntryPatchDTO top5EntryPatchDTO);
```

Add `import com.watchwise.watchwise_api.top5entry.dto.Top5EntryPatchDTO;`.

- [ ] **Step 7: Implement `updateEntry` in `Top5EntryServiceImpl`, extract `findOwnedEntry`, and set `customPosterUrl` on insert**

In `insertEntry`, change the builder call to include the new field:

```java
        Top5Entry newEntry = Top5Entry.builder()
                .user(user)
                .content(content)
                .type(type)
                .position(finalPosition)
                .customPosterUrl(top5EntryCreationDTO.customPosterUrl())
                .createdAt(now)
                .updatedAt(now)
                .build();
```

Replace the body of `removeEntry` and add `updateEntry` + the shared `findOwnedEntry` helper:

```java
    @Override
    @Transactional
    public void removeEntry(UUID userId, ContentType type, UUID top5EntryId) {
        validateType(type);

        Top5Entry entry = findOwnedEntry(userId, type, top5EntryId);

        int removedPosition = entry.getPosition();
        top5EntryRepository.delete(entry);
        top5EntryRepository.flush();

        List<Top5Entry> toShift = top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(userId, type).stream()
                .filter(remaining -> remaining.getPosition() > removedPosition)
                .toList();

        for (Top5Entry remaining : toShift) {
            remaining.setPosition(remaining.getPosition() - 1);
            top5EntryRepository.save(remaining);
            top5EntryRepository.flush();
        }
    }

    @Override
    @Transactional
    public Top5EntryResponseDTO updateEntry(UUID userId, ContentType type, UUID top5EntryId, Top5EntryPatchDTO top5EntryPatchDTO) {
        validateType(type);

        Top5Entry entry = findOwnedEntry(userId, type, top5EntryId);

        if (top5EntryPatchDTO.customPosterUrl() != null) {
            entry.setCustomPosterUrl(top5EntryPatchDTO.customPosterUrl());
            entry.setUpdatedAt(LocalDateTime.now());
            top5EntryRepository.save(entry);
            top5EntryRepository.flush();
        }

        return top5EntryMapper.top5EntryToResponseDto(entry);
    }

    private Top5Entry findOwnedEntry(UUID userId, ContentType type, UUID top5EntryId) {
        Top5Entry entry = top5EntryRepository.findById(top5EntryId)
                .orElseThrow(() -> new NotFoundException("Top 5 entry not found"));

        if (!entry.getUser().getId().equals(userId) || entry.getType() != type) {
            throw new NotFoundException("Top 5 entry not found");
        }

        return entry;
    }
```

Remove the now-duplicated inline lookup that used to live at the top of `removeEntry` (the `top5EntryRepository.findById(...)` + ownership/type check) since `findOwnedEntry` replaces it. Add `import com.watchwise.watchwise_api.top5entry.dto.Top5EntryPatchDTO;`.

- [ ] **Step 8: Add the PATCH endpoint to `Top5EntryController`**

```java
    @PatchMapping("/me/top5/{type}/{top5EntryId}")
    public ResponseEntity<Top5EntryResponseDTO> updateEntry(
            @PathVariable MovieOrSeriesType type,
            @PathVariable UUID top5EntryId,
            @Valid @RequestBody Top5EntryPatchDTO top5EntryPatchDTO
    ) {
        Top5EntryResponseDTO updated = top5EntryService.updateEntry(getCurrentUserId(), type.toContentType(), top5EntryId, top5EntryPatchDTO);
        return ResponseEntity.ok(updated);
    }
```

Add `import com.watchwise.watchwise_api.top5entry.dto.Top5EntryPatchDTO;`.

- [ ] **Step 9: Run the tests to confirm they pass**

Run: `mvnw.cmd test "-Dtest=Top5EntryServiceImplTest,Top5EntryControllerTest"`
Expected: PASS — all existing tests (including the ~15 call sites using the old 2-arg `Top5EntryCreationDTO` and the 6-arg `Top5EntryResponseDTO` constructors) still compile and pass because of the backward-compatible overloaded constructors.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/watchwise/watchwise_api/top5entry src/test/java/com/watchwise/watchwise_api/top5entry/service/impl/Top5EntryServiceImplTest.java src/test/java/com/watchwise/watchwise_api/top5entry/controller/Top5EntryControllerTest.java
git commit -m "feat(top5): add customPosterUrl on insert and a new PATCH endpoint to edit it"
```

---

### Task 3: Top5Entry — integration tests for the new PATCH endpoint

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/top5entry/controller/Top5EntryControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `PATCH /users/me/top5/{type}/{top5EntryId}` (Task 2).

- [ ] **Step 1: Write the failing tests**

Add `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;` to the imports.

Add these two helpers right after `removeRequest`:

```java
    private MockHttpServletRequestBuilder updateRequest(RegisteredUser actor, ContentType type, UUID top5EntryId, String body) {
        return patch("/users/me/top5/" + type + "/" + top5EntryId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String posterPatchBody(String customPosterUrl) {
        return """
                {
                    "customPosterUrl": "%s"
                }
                """.formatted(customPosterUrl);
    }
```

Add this section at the end of the file, right before the closing `}`:

```java
    // ---------- PATCH /users/me/top5/{type}/{top5EntryId} ----------

    @Test
    @DisplayName("[updateEntry] Should Return Ok And Persist The New Poster - When Entry Exists")
    void shouldReturnOkAndPersistTheNewPosterWhenEntryExists() throws Exception {
        RegisteredUser user = registerUser("updatetop5ok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Top5Entry entry = persistEntry(entity, persistContent("550", ContentType.MOVIE), ContentType.MOVIE, 1);

        mockMvc.perform(updateRequest(user, ContentType.MOVIE, entry.getId(), posterPatchBody("https://example.com/new.png")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customPosterUrl").value("https://example.com/new.png"));

        assertThat(top5EntryRepository.findById(entry.getId()).orElseThrow().getCustomPosterUrl())
                .isEqualTo("https://example.com/new.png");
    }

    @Test
    @DisplayName("[updateEntry] Should Return BadRequest - When CustomPosterUrl Is Not A Valid Url")
    void shouldReturnBadRequestWhenCustomPosterUrlIsNotAValidUrl() throws Exception {
        RegisteredUser user = registerUser("updatetop5invalidurl");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Top5Entry entry = persistEntry(entity, persistContent("550", ContentType.MOVIE), ContentType.MOVIE, 1);

        mockMvc.perform(updateRequest(user, ContentType.MOVIE, entry.getId(), posterPatchBody("not-a-url")))
                .andExpect(status().isBadRequest());

        assertThat(top5EntryRepository.findById(entry.getId()).orElseThrow().getCustomPosterUrl()).isNull();
    }

    @Test
    @DisplayName("[updateEntry] Should Return NotFound - When Entry Does Not Exist")
    void shouldReturnNotFoundWhenEntryDoesNotExistOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updatetop5notfound");

        mockMvc.perform(updateRequest(user, ContentType.MOVIE, UUID.randomUUID(), posterPatchBody("https://example.com/x.png")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Top 5 entry not found"));
    }

    @Test
    @DisplayName("[updateEntry] Should Return NotFound - When Entry Belongs To A Different User")
    void shouldReturnNotFoundWhenEntryBelongsToADifferentUserOnUpdate() throws Exception {
        RegisteredUser owner = registerUser("updatetop5owner");
        RegisteredUser intruder = registerUser("updatetop5intruder");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        Top5Entry entry = persistEntry(ownerEntity, persistContent("550", ContentType.MOVIE), ContentType.MOVIE, 1);

        mockMvc.perform(updateRequest(intruder, ContentType.MOVIE, entry.getId(), posterPatchBody("https://example.com/x.png")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Top 5 entry not found"));

        assertThat(top5EntryRepository.findById(entry.getId()).orElseThrow().getCustomPosterUrl()).isNull();
    }

    @Test
    @DisplayName("[updateEntry] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForUpdate() throws Exception {
        RegisteredUser user = registerUser("updatetop5noauth");

        mockMvc.perform(patch("/users/me/top5/MOVIE/" + UUID.randomUUID())
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(posterPatchBody("https://example.com/x.png")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[updateEntry] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForUpdate() throws Exception {
        RegisteredUser user = registerUser("updatetop5nocsrf");

        mockMvc.perform(patch("/users/me/top5/MOVIE/" + UUID.randomUUID())
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(posterPatchBody("https://example.com/x.png")))
                .andExpect(status().isForbidden());
    }
```

- [ ] **Step 2: Run the tests to confirm they fail**

Run: `mvnw.cmd test "-Dtest=Top5EntryControllerIntegrationTest"`
Expected: FAIL — `PATCH /users/me/top5/{type}/{top5EntryId}` doesn't exist without Task 2 (if run standalone against a branch without Task 2, this fails with 404/405). Since Task 2 is already done by this point, this step should actually PASS immediately — run it anyway to confirm.

- [ ] **Step 3: Run the tests to confirm they pass**

Run: `mvnw.cmd test "-Dtest=Top5EntryControllerIntegrationTest"`
Expected: PASS (requires Docker running for Testcontainers).

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/watchwise/watchwise_api/top5entry/controller/Top5EntryControllerIntegrationTest.java
git commit -m "test(top5): add integration coverage for the new PATCH poster endpoint"
```

---

### Task 4: UserListItem — DTOs, service guards, unit tests

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemCreationDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemPatchDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemServiceImpl.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemServiceImplTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/userlist/mapper/UserListItemMapperTest.java`

**Interfaces:**
- Consumes: `UserListItem.getCustomPosterUrl()/setCustomPosterUrl(String)` (Task 1).
- Produces: `UserListItemCreationDTO`/`UserListItemPatchDTO`/`UserListItemResponseDTO` with `customPosterUrl`, `UserListItemServiceImpl.addItem`/`updateItem` enforcing the content-only guard — consumed by Task 5 (integration tests).

- [ ] **Step 1: Write the failing/updated unit tests**

In `UserListItemServiceImplTest`, add right after `shouldInsertAtPositionOneWhenListIsEmpty` (still inside the `addItem` tests, before `shouldInsertAtNextFreePositionWhenListAlreadyHasItemsAndNoPositionGiven`):

```java
    @Test
    @DisplayName("[addItem] Should Persist CustomPosterUrl - When Content Item Provides One")
    void shouldPersistCustomPosterUrlWhenContentItemProvidesOne() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.countByUserListId(listId)).thenReturn(0L);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userListItemService.addItem(lucasId, listId,
                new UserListItemCreationDTO(contentRefCreation("550"), null, null, null, "https://example.com/poster.png"));

        verify(userListItemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getCustomPosterUrl()).isEqualTo("https://example.com/poster.png");
    }

    @Test
    @DisplayName("[addItem] Should Throw BadRequestException - When CustomPosterUrl Is Provided With ChildListId")
    void shouldThrowBadRequestExceptionWhenCustomPosterUrlIsProvidedWithChildListId() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));

        assertThatThrownBy(() -> userListItemService.addItem(lucasId, listId,
                new UserListItemCreationDTO(null, UUID.randomUUID(), null, null, "https://example.com/poster.png")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("customPosterUrl is only allowed on content items");

        verifyNoInteractions(userListItemRepository, contentService);
    }
```

Add right after `shouldChangeDescriptionWhenADifferentValueIsProvided` (still inside the `// ---------- updateItem ----------` section):

```java
    @Test
    @DisplayName("[updateItem] Should Change CustomPosterUrl - When A Different Value Is Provided")
    void shouldChangeCustomPosterUrlWhenADifferentValueIsProvided() {
        UserListItem item = buildContentItem(scifi, fightClub, 1);
        item.setCustomPosterUrl("https://example.com/old.png");
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userListItemService.updateItem(lucasId, listId, item.getId(),
                new UserListItemPatchDTO(null, null, "https://example.com/new.png"));

        verify(userListItemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getCustomPosterUrl()).isEqualTo("https://example.com/new.png");
    }

    @Test
    @DisplayName("[updateItem] Should Not Save - When Same CustomPosterUrl Value Is Provided")
    void shouldNotSaveWhenSameCustomPosterUrlValueIsProvided() {
        UserListItem item = buildContentItem(scifi, fightClub, 1);
        item.setCustomPosterUrl("https://example.com/same.png");
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        userListItemService.updateItem(lucasId, listId, item.getId(),
                new UserListItemPatchDTO(null, null, "https://example.com/same.png"));

        verify(userListItemRepository, never()).save(any());
        verify(userListItemRepository, never()).flush();
    }

    @Test
    @DisplayName("[updateItem] Should Throw BadRequestException - When CustomPosterUrl Is Provided Against A ChildList Item")
    void shouldThrowBadRequestExceptionWhenCustomPosterUrlIsProvidedAgainstAChildListItem() {
        UserList childList = buildUserList(UUID.randomUUID(), lucas, "Nested", UserListVisibility.PUBLIC);
        UserListItem item = buildChildListItem(scifi, childList, 1);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> userListItemService.updateItem(lucasId, listId, item.getId(),
                new UserListItemPatchDTO(null, null, "https://example.com/x.png")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("customPosterUrl is only allowed on content items");

        verify(userListItemRepository, never()).save(any());
    }
```

In `UserListItemMapperTest`, update `shouldMapAContentItemWhenChildListIsNull` to add `.customPosterUrl("https://example.com/poster.png")` to the `UserListItem.builder()` chain (right after `.description("Best plot twist")`), and add this assertion right after `assertThat(result.description()).isEqualTo("Best plot twist");`:

```java
        assertThat(result.customPosterUrl()).isEqualTo("https://example.com/poster.png");
```

And add this assertion to `shouldMapANestedListItemWhenContentIsNull`, right after `assertThat(result.description()).isNull();`:

```java
        assertThat(result.customPosterUrl()).isNull();
```

- [ ] **Step 2: Run the tests to confirm they fail to compile**

Run: `mvnw.cmd test "-Dtest=UserListItemServiceImplTest,UserListItemMapperTest"`
Expected: COMPILE FAILURE — `UserListItemCreationDTO`/`UserListItemPatchDTO`'s 5-arg/3-arg constructors and `UserListItem.setCustomPosterUrl`/`getCustomPosterUrl` (already added in Task 1) don't have DTO support yet; `result.customPosterUrl()` doesn't exist on `UserListItemResponseDTO` yet.

- [ ] **Step 3: Update `UserListItemCreationDTO`**

```java
package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.util.UUID;

public record UserListItemCreationDTO(
        @Valid ContentRefCreationDTO content,
        UUID childListId,
        @Min(1) Integer position,
        @Size(max = 400) String description,
        @Size(max = 2048) @URL String customPosterUrl
) {
    public UserListItemCreationDTO(ContentRefCreationDTO content, UUID childListId, Integer position, String description) {
        this(content, childListId, position, description, null);
    }
}
```

- [ ] **Step 4: Update `UserListItemPatchDTO`**

```java
package com.watchwise.watchwise_api.userlist.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record UserListItemPatchDTO(
        @Min(1) Integer position,
        @Size(max = 400) String description,
        @Size(max = 2048) @URL String customPosterUrl
) {
    public UserListItemPatchDTO(Integer position, String description) {
        this(position, description, null);
    }
}
```

- [ ] **Step 5: Update `UserListItemResponseDTO`**

```java
package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserListItemResponseDTO(
        UUID id,
        ContentRefDTO content,
        UserListPreviewDTO childList,
        Integer position,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String customPosterUrl
) {
    public UserListItemResponseDTO(UUID id, ContentRefDTO content, UserListPreviewDTO childList, Integer position,
            String description, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, content, childList, position, description, createdAt, updatedAt, null);
    }
}
```

- [ ] **Step 6: Implement the guards and field wiring in `UserListItemServiceImpl`**

Update `validateExactlyOneTarget`:

```java
    private void validateExactlyOneTarget(UserListItemCreationDTO userListItemCreationDTO) {
        boolean hasContent = userListItemCreationDTO.content() != null;
        boolean hasChildList = userListItemCreationDTO.childListId() != null;

        if (hasContent == hasChildList) {
            throw new BadRequestException("Exactly one of content or childListId must be provided");
        }

        if (hasChildList && userListItemCreationDTO.customPosterUrl() != null) {
            throw new BadRequestException("customPosterUrl is only allowed on content items");
        }
    }
```

Update the `addItem` branch that resolves `content` to also set `customPosterUrl`:

```java
        if (userListItemCreationDTO.content() != null) {
            assertListIsNotLockedAsListOfLists(listId);
            ContentRefDTO contentRef = contentService.getOrCreateReference(userListItemCreationDTO.content());
            builder.content(contentRepository.getReferenceById(contentRef.id()));
            builder.customPosterUrl(userListItemCreationDTO.customPosterUrl());
        } else {
            assertListIsNotLockedAsContentList(listId);
            builder.childList(resolveChildList(userId, listId, userListItemCreationDTO.childListId()));
        }
```

Update `updateItem`:

```java
    @Override
    @Transactional
    public UserListItemResponseDTO updateItem(UUID userId, UUID listId, UUID itemId, UserListItemPatchDTO userListItemPatchDTO) {
        findOwnedList(userId, listId);
        UserListItem item = findOwnedItem(listId, itemId);

        if (userListItemPatchDTO.customPosterUrl() != null && item.getChildList() != null) {
            throw new BadRequestException("customPosterUrl is only allowed on content items");
        }

        boolean descriptionChanged = userListItemPatchDTO.description() != null
                && !userListItemPatchDTO.description().equals(item.getDescription());
        boolean positionChanged = userListItemPatchDTO.position() != null
                && !userListItemPatchDTO.position().equals(item.getPosition());
        boolean customPosterUrlChanged = userListItemPatchDTO.customPosterUrl() != null
                && !userListItemPatchDTO.customPosterUrl().equals(item.getCustomPosterUrl());

        if (!descriptionChanged && !positionChanged && !customPosterUrlChanged) {
            return userListItemMapper.userListItemToResponseDto(item);
        }

        long currentCount = positionChanged ? userListItemRepository.countByUserListId(listId) : 0;
        if (positionChanged && userListItemPatchDTO.position() > currentCount) {
            throw new BadRequestException("position cannot be greater than " + currentCount + ", the last position in the list");
        }

        if (descriptionChanged) {
            item.setDescription(userListItemPatchDTO.description());
        }
        if (customPosterUrlChanged) {
            item.setCustomPosterUrl(userListItemPatchDTO.customPosterUrl());
        }
        item.setUpdatedAt(LocalDateTime.now());

        if (positionChanged) {
            try {
                item = performMove(item, item.getPosition(), userListItemPatchDTO.position(), currentCount);
            } catch (DataIntegrityViolationException e) {
                throw new ConflictException("List item could not be reordered due to a concurrent update");
            }
        } else {
            item = userListItemRepository.save(item);
            userListItemRepository.flush();
        }

        return userListItemMapper.userListItemToResponseDto(item);
    }
```

Update `toVisibilityScopedResponseDto` to carry `customPosterUrl` through explicitly (it will always be null for a masked `childList` item, since `customPosterUrl` can never be set on one, but pass it through explicitly rather than relying on the DTO's legacy-constructor default):

```java
    private UserListItemResponseDTO toVisibilityScopedResponseDto(UUID viewerId, UserListItem item) {
        UserListItemResponseDTO dto = userListItemMapper.userListItemToResponseDto(item);

        if (item.getChildList() != null && !isVisibleTo(viewerId, item.getChildList())) {
            return new UserListItemResponseDTO(
                    dto.id(), dto.content(), null, dto.position(), dto.description(), dto.createdAt(), dto.updatedAt(),
                    dto.customPosterUrl());
        }

        return dto;
    }
```

- [ ] **Step 7: Run the tests to confirm they pass**

Run: `mvnw.cmd test "-Dtest=UserListItemServiceImplTest,UserListItemMapperTest,UserListItemControllerTest"`
Expected: PASS — including the 20+ existing call sites across all three files that use the old 4-arg `UserListItemCreationDTO`, 2-arg `UserListItemPatchDTO`, and 7-arg `UserListItemResponseDTO` constructors, all preserved via the backward-compatible overloads.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemCreationDTO.java src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemPatchDTO.java src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListItemResponseDTO.java src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemServiceImpl.java src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemServiceImplTest.java src/test/java/com/watchwise/watchwise_api/userlist/mapper/UserListItemMapperTest.java
git commit -m "feat(lists): add customPosterUrl to list items, restricted to content items"
```

---

### Task 5: UserListItem — integration tests

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListItemControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `POST /lists/{listId}/items`, `PATCH /lists/{listId}/items/{itemId}` with `customPosterUrl` (Task 4).

- [ ] **Step 1: Write the failing tests**

Add these two overloads right after the existing `contentItemBody`/`patchItemBody` methods (keep the existing 3-arg/2-arg versions untouched — every existing call site keeps using them):

```java
    private String contentItemBody(String tmdbId, Integer position, String description, String customPosterUrl) {
        String positionField = position == null ? "null" : String.valueOf(position);
        String descriptionField = description == null ? "null" : "\"" + description + "\"";
        String posterField = customPosterUrl == null ? "null" : "\"" + customPosterUrl + "\"";
        return """
                {
                    "content": { "tmdbId": "%s", "type": "MOVIE" },
                    "position": %s,
                    "description": %s,
                    "customPosterUrl": %s
                }
                """.formatted(tmdbId, positionField, descriptionField, posterField);
    }

    private String patchItemBody(Integer position, String description, String customPosterUrl) {
        String positionField = position == null ? "null" : String.valueOf(position);
        String descriptionField = description == null ? "null" : "\"" + description + "\"";
        String posterField = customPosterUrl == null ? "null" : "\"" + customPosterUrl + "\"";
        return """
                {
                    "position": %s,
                    "description": %s,
                    "customPosterUrl": %s
                }
                """.formatted(positionField, descriptionField, posterField);
    }

    private String childListItemBodyWithPoster(UUID childListId, String customPosterUrl) {
        return """
                {
                    "childListId": "%s",
                    "customPosterUrl": "%s"
                }
                """.formatted(childListId, customPosterUrl);
    }
```

Add these tests at the end of the file, right before the closing `}`:

```java
    // ---------- customPosterUrl ----------

    @Test
    @DisplayName("[addItem] Should Persist CustomPosterUrl - When Content Item Provides One")
    void shouldPersistCustomPosterUrlWhenContentItemProvidesOne() throws Exception {
        RegisteredUser user = registerUser("additemposter");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);

        mockMvc.perform(addItemRequest(user, list.getId(), contentItemBody("550", null, null, "https://example.com/poster.png")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customPosterUrl").value("https://example.com/poster.png"));

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId()).get(0).getCustomPosterUrl())
                .isEqualTo("https://example.com/poster.png");
    }

    @Test
    @DisplayName("[addItem] Should Return BadRequest - When CustomPosterUrl Is Provided With ChildListId")
    void shouldReturnBadRequestWhenCustomPosterUrlIsProvidedWithChildListId() throws Exception {
        RegisteredUser user = registerUser("additemposterchild");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList parent = persistList(entity, "Parent", true);
        UserList child = persistList(entity, "Child", true);

        mockMvc.perform(addItemRequest(user, parent.getId(), childListItemBodyWithPoster(child.getId(), "https://example.com/x.png")))
                .andExpect(status().isBadRequest());

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(parent.getId())).isEmpty();
    }

    @Test
    @DisplayName("[updateItem] Should Change CustomPosterUrl - When A Different Value Is Provided")
    void shouldChangeCustomPosterUrlWhenADifferentValueIsProvidedOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updateitemposter");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        UserListItem item = persistContentItem(list, persistContent("550"), 1);

        mockMvc.perform(updateItemRequest(user, list.getId(), item.getId(), patchItemBody(null, null, "https://example.com/new.png")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customPosterUrl").value("https://example.com/new.png"));

        assertThat(userListItemRepository.findById(item.getId()).orElseThrow().getCustomPosterUrl())
                .isEqualTo("https://example.com/new.png");
    }

    @Test
    @DisplayName("[updateItem] Should Return BadRequest - When CustomPosterUrl Is Provided Against A ChildList Item")
    void shouldReturnBadRequestWhenCustomPosterUrlIsProvidedAgainstAChildListItemOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updateitemposterchild");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList parent = persistList(entity, "Parent", true);
        UserList child = persistList(entity, "Child", true);
        UserListItem item = persistChildListItem(parent, child, 1);

        mockMvc.perform(updateItemRequest(user, parent.getId(), item.getId(), patchItemBody(null, null, "https://example.com/x.png")))
                .andExpect(status().isBadRequest());

        assertThat(userListItemRepository.findById(item.getId()).orElseThrow().getCustomPosterUrl()).isNull();
    }
```

- [ ] **Step 2: Run the tests**

Run: `mvnw.cmd test "-Dtest=UserListItemControllerIntegrationTest"`
Expected: PASS (requires Docker running for Testcontainers).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListItemControllerIntegrationTest.java
git commit -m "test(lists): add integration coverage for customPosterUrl on list items"
```

---

### Task 6: Docs sync (openapi.yaml, database-schema.html, business-rules.md, business-rules-summary.md, progress.md)

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/database-schema.html`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/business-rules-summary.md`
- Modify: `docs/context/progress.md`

**Interfaces:**
- None — documentation only, no code interfaces produced or consumed. This task has no automated test cycle; verify by reading the diff.

- [ ] **Step 1: Update `openapi.yaml` — `Top5Entry` schema**

In the `Top5Entry` schema (properties: `id, type, content, position, createdAt, updatedAt`), add after `position`:

```yaml
        customPosterUrl: { type: string, format: uri, nullable: true }
```

- [ ] **Step 2: Update `openapi.yaml` — `POST /users/me/top5/{type}` request body**

In the inline request body schema (`tmdbId`, `position`), add:

```yaml
                customPosterUrl: { type: string, format: uri, nullable: true }
```

- [ ] **Step 3: Update `openapi.yaml` — new `Top5EntryPatch` schema and PATCH path**

Add a new schema near `Top5Entry`:

```yaml
    Top5EntryPatch:
      type: object
      description: >
        The only editable field on an existing Top 5 entry — there is still no "move" operation,
        matching insertEntry's existing insert/remove-only model. null means "leave unchanged".
      properties:
        customPosterUrl: { type: string, format: uri, nullable: true }
```

Add a `patch` operation under `/users/me/top5/{type}/{top5EntryId}`, alongside the existing `delete`:

```yaml
    patch:
      tags: [Top5]
      summary: Editar o poster customizado de uma entrada do Top 5 do usuário autenticado
      description: >
        Único campo editável de uma entrada já existente — não existe operação de "mover", só
        inserir/remover (ver POST/DELETE acima). null em customPosterUrl significa "não alterar".
      parameters:
        - $ref: "#/components/parameters/top5Type"
        - name: top5EntryId
          in: path
          required: true
          schema: { type: string, format: uuid }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/Top5EntryPatch" }
      responses:
        "200":
          description: Atualizado
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Top5Entry" }
        "400": { description: "top5EntryId não é um UUID válido, ou customPosterUrl inválido (mais de 2048 caracteres ou não é uma URL)" }
        "403": { description: Cookie CSRF ausente ou inválido }
        "404": { description: "Entrada não encontrada, não pertence ao usuário autenticado, ou não é desse type" }
```

- [ ] **Step 4: Update `openapi.yaml` — `UserListItem` schema**

In the `UserListItem` schema (properties: `id, content, childList, position, description, createdAt, updatedAt`), add after `description`:

```yaml
        customPosterUrl: { type: string, format: uri, nullable: true }
```

- [ ] **Step 5: Update `openapi.yaml` — `POST /lists/{listId}/items` request body**

In the inline request body schema (`content`, `childListId`, `position`, `description`), add:

```yaml
                customPosterUrl: { type: string, format: uri, nullable: true }
```

Update that operation's `description` to mention the restriction: append a sentence — `customPosterUrl só é aceito quando content é informado — 400 se vier junto com childListId.` And extend the `"400"` response description to include: `, ou customPosterUrl informado junto com childListId`.

- [ ] **Step 6: Update `openapi.yaml` — `PATCH /lists/{listId}/items/{itemId}` request body**

In the inline request body schema (`position`, `description`), add:

```yaml
                customPosterUrl: { type: string, format: uri, nullable: true }
```

Update that operation's `description` to append: `customPosterUrl só é aceito quando o item é de content — 400 se o item alvo for de lista aninhada.` And extend the `"400"` response description to include: `, ou customPosterUrl informado contra um item de lista aninhada`.

- [ ] **Step 7: Update `database-schema.html`**

In the `TOP5` mermaid block, add `string poster_personalizado` right after `int posicao`:

```
  TOP5 {
    int id PK
    int usuario_id FK
    int conteudo_id FK
    string tipo
    int posicao
    string poster_personalizado
    timestamp created_at
    timestamp updated_at
  }
```

In the `ITEM_LISTA` mermaid block, add `string poster_personalizado` right after `string descricao`:

```
  ITEM_LISTA {
    int id PK
    int lista_id FK
    int conteudo_id FK
    int lista_filha_id FK
    int posicao
    string descricao
    string poster_personalizado
    timestamp created_at
    timestamp updated_at
  }
```

- [ ] **Step 8: Update `business-rules.md` — `Top5Entry` section**

Add a new bullet at the end of the `## Top5Entry` section (after the `shiftUpFrom`/`removeEntry` bullet, before `## WatchlistEntry`):

```markdown
- **`customPosterUrl` é o único campo editável numa entrada já existente** — igual a `DiaryEntry.
  customPosterUrl` (mesma validação `@Size(max = 2048) @URL`), aceito tanto no `POST` (`insertEntry`)
  quanto num novo `PATCH /users/me/top5/{type}/{top5EntryId}` (`updateEntry`, adicionado em
  2026-08-29) — antes desse endpoint, Top5Entry só tinha inserir/remover, sem nenhuma operação de
  update; `null` no patch significa "não alterar", sem forma de limpar um poster já definido de volta
  pra `null`, mesma limitação que `DiaryEntry` já tem.
```

- [ ] **Step 9: Update `business-rules.md` — `UserListItem` section**

Add a new bullet at the end of the `## UserListItem` section (after the "Sem posição duplicada" bullet):

```markdown
- **`customPosterUrl` só é permitido em item de `content`, nunca em item de `childList`** —
  `validateExactlyOneTarget` rejeita (`400`) `customPosterUrl` informado junto com `childListId` na
  criação; `updateItem` rejeita (`400`) `customPosterUrl` informado contra um item cujo
  `getChildList() != null` no patch. Uma lista aninhada não tem poster próprio (adicionado em
  2026-08-29, mesma validação `@Size(max = 2048) @URL` de `DiaryEntry.customPosterUrl`).
```

- [ ] **Step 10: Update `business-rules-summary.md`**

Add to the `## Top5Entry` section:

```markdown
- `customPosterUrl` é o único campo editável numa entrada já existente — aceito no POST e num novo PATCH `/users/me/top5/{type}/{top5EntryId}` (2026-08-29); antes só existia inserir/remover.
```

Add to the `## UserListItem` section:

```markdown
- `customPosterUrl` só é permitido em item de content, nunca de childList (400 nos dois casos) — uma lista aninhada não tem poster próprio (2026-08-29).
```

- [ ] **Step 11: Append to `progress.md`**

Add a new day section at the end of the file (check first whether an entry already numbered `(5)` for `2026-08-29` doesn't already exist — the existing entries go up to `(4)`):

```markdown

## 2026-08-29 (5) — Poster customizado no Top 5 e em itens de lista

Estendido o `customPosterUrl` que já existia só em `DiaryEntry` pras outras duas telas onde o usuário
escolhe um conteúdo: Top 5 e listas. `Top5Entry` ganhou o campo no `POST /users/me/top5/{type}`
(existente) e um `PATCH /users/me/top5/{type}/{top5EntryId}` novo — antes desse endpoint, Top5Entry só
tinha inserir/remover, nenhuma operação de update. `UserListItem` ganhou o campo em
`POST /lists/{listId}/items` e `PATCH /lists/{listId}/items/{itemId}`, restrito a item de `content`:
informar `customPosterUrl` junto com `childListId` (criação) ou contra um item de lista aninhada
(patch) devolve `400`, já que uma lista aninhada não tem poster próprio. Mesma validação em todo lugar
(`@Size(max = 2048) @URL`, nullable), migration `V36` adicionando a coluna `custom_poster_url` em
`top5_entries` e `user_list_items`. `openapi.yaml`, `database-schema.html`, `business-rules.md`/
`-summary.md` atualizados junto.
```

- [ ] **Step 12: Commit**

```bash
git add docs/context/openapi.yaml docs/context/database-schema.html docs/context/business-rules.md docs/context/business-rules-summary.md docs/context/progress.md
git commit -m "docs: sync openapi and business rules for per-item custom poster"
```
