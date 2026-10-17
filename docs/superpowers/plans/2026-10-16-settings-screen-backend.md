# Settings Screen Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the missing backend capabilities for Settings: one focused read model, block management, block-aware social boundaries, and URL-banner removal.

**Architecture:** `SettingsViewService` is a read-only module used only by `GET /users/me/settings`.
`UserBlock` owns the directional block relationship and exposes a small access module for social
callers. Existing `PATCH /users/me` remains the sole write route for profile-picture/banner URLs,
profile fields, preferences, and credentials; `DELETE /users/me/banner` fills the one gap caused by
PATCH treating null as omitted.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL/Flyway, MapStruct, JUnit 5, Mockito, MockMvc, Testcontainers.

## Global Constraints

- Before code, announce and obtain approval for the new `UserBlock` entity, V72 migration, and routes required by AGENTS.md.
- Preserve `GET/PATCH /users/me`, follow-request routes, `POST /auth/logout`, `POST /auth/logout-all`, and `DELETE /users/me`.
- Profile picture and banner are URLs only. Do not add multipart, storage adapters, object keys, cloud dependencies, or asset migrations.
- Every write is authenticated and CSRF-protected. No view DTO may expose passwords, tokens, roles, or session timestamps.
- Block/unblock are idempotent; self-block is 400; neither operation notifies the target; block removes follow rows in both directions.
- Block and follow mutations for the same unordered user pair acquire the same PostgreSQL advisory transaction lock before checking state. Block persistence and follower cleanup commit or roll back together.
- Either-direction blocks deny follow creation/acceptance and comments/replies on user-owned targets. They hide each user’s comments from the other in SQL before count and pagination.
- Do not expand blocks into global content visibility, historical notification deletion, recommendations, device/session lists, or email-verification flows.
- Settings previews contain at most ten pending requests and ten blocked users; full blocked users remain paginated.
- Documentation is local-only: update `docs/context/`, `docs/telas-reqs-definitivo.md`, and `AGENTS.md`, but do not stage/commit docs.
- Use typed `ApiError` responses, TDD, Conventional Commits without `Co-Authored-By`, and no push.

---

### Task 1: Add the UserBlock domain

**Files:**

- Create: `src/main/resources/db/migration/V72__create-user-blocks.sql`
- Create: `src/main/java/com/watchwise/watchwise_api/userblock/entity/UserBlock.java`
- Create: `src/main/java/com/watchwise/watchwise_api/userblock/repository/UserBlockRepository.java`
- Create: `src/main/java/com/watchwise/watchwise_api/userblock/dto/BlockedUserDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/userblock/service/UserBlockService.java`
- Create: `src/main/java/com/watchwise/watchwise_api/userblock/service/UserBlockAccessService.java`
- Create: `src/main/java/com/watchwise/watchwise_api/userblock/service/impl/UserBlockServiceImpl.java`
- Create: `src/main/java/com/watchwise/watchwise_api/userblock/service/impl/UserBlockAccessServiceImpl.java`
- Create: `src/main/java/com/watchwise/watchwise_api/userblock/controller/UserBlockController.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/follower/repository/FollowerRepository.java`
- Test: `src/test/java/com/watchwise/watchwise_api/userblock/repository/UserBlockRepositoryTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/userblock/service/impl/UserBlockServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/userblock/controller/UserBlockControllerIntegrationTest.java`

**Consumes:** `UserRepository`, `UserMapper`, `FollowerRepository`, `NewTransactionExecutor`, `PageRequestFactory`.

**Produces:** idempotent block commands, a paginated block read, and an either-direction social-access predicate.

- [ ] **Step 1: Write failing repository tests**

Create users A/B/C and assert:

```java
assertThat(repository.existsEitherDirection(userA.getId(), userB.getId())).isTrue();
assertThat(repository.existsEitherDirection(userA.getId(), userC.getId())).isFalse();
assertThat(repository.findByBlockerIdOrderByCreatedAtDescIdDesc(userA.getId(), PageRequest.of(0, 20))
        .getContent()).extracting(block -> block.getBlocked().getId()).containsExactly(userB.getId());
```

Also verify unique and self-block database constraints.

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\mvnw.cmd test "-Dtest=UserBlockRepositoryTest"
```

Expected: compile failure because the domain and V72 do not exist.

- [ ] **Step 3: Implement persistence and access seam**

Create V72:

```sql
CREATE TABLE user_blocks (
  id UUID PRIMARY KEY,
  blocker_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  blocked_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT uq_user_blocks_blocker_id_blocked_id UNIQUE (blocker_id, blocked_id),
  CONSTRAINT ck_user_blocks_no_self_block CHECK (blocker_id <> blocked_id)
);
CREATE INDEX idx_user_blocks_blocker_created_at ON user_blocks (blocker_id, created_at DESC, id DESC);
CREATE INDEX idx_user_blocks_blocked_id ON user_blocks (blocked_id);
```

Define:

```java
public interface UserBlockAccessService {
    boolean isBlockedEitherDirection(UUID firstUserId, UUID secondUserId);
    void assertInteractionAllowed(UUID actorId, UUID counterpartId);
}

public interface UserBlockService {
    void block(UUID blockerId, UUID blockedId);
    void unblock(UUID blockerId, UUID blockedId);
    Page<BlockedUserDTO> getBlockedUsers(UUID blockerId, Integer page, Integer size);
}
```

The repository must have `existsEitherDirection` as one JPQL query and
`findByBlockerIdOrderByCreatedAtDescIdDesc`. `block` validates target existence, rejects self,
acquires the canonical unordered pair advisory lock before checking/inserting, then saves the block and
deletes follower rows A→B and B→A in the same outer transaction. `unblock` is delete-if-present.

- [ ] **Step 4: Add controller tests before implementation**

Require:

```java
mockMvc.perform(post("/users/me/blocked/{userId}", targetId).with(authentication(authentication)))
        .andExpect(status().isNoContent());
mockMvc.perform(delete("/users/me/blocked/{userId}", targetId).with(authentication(authentication)))
        .andExpect(status().isNoContent());
```

Cover 400 self-block, 404 missing target, 401, invalid CSRF, duplicate POST returning 204, and page ordering.

- [ ] **Step 5: Implement controller and verify GREEN**

Add:

```java
@GetMapping("/me/blocked")
@PostMapping("/me/blocked/{userId}")
@DeleteMapping("/me/blocked/{userId}")
```

Run:

```powershell
.\mvnw.cmd test "-Dtest=UserBlockRepositoryTest,UserBlockServiceImplTest,UserBlockControllerIntegrationTest"
```

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/watchwise/watchwise_api/userblock src/main/java/com/watchwise/watchwise_api/follower/repository/FollowerRepository.java src/main/resources/db/migration/V72__create-user-blocks.sql src/test/java/com/watchwise/watchwise_api/userblock
git commit -m "feat(user-block): add block management"
```

### Task 2: Apply block rules to follows and comments

**Files:**

- Modify: `src/main/java/com/watchwise/watchwise_api/follower/service/impl/FollowerServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/comment/repository/CommentRepository.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/comment/service/impl/CommentServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/comment/service/impl/CommentPreviewAssembler.java`
- Test: `src/test/java/com/watchwise/watchwise_api/follower/service/impl/FollowerServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/comment/repository/CommentRepositoryTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/comment/service/impl/CommentServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/comment/controller/CommentControllerIntegrationTest.java`

**Consumes:** `UserBlockAccessService` and current target-visibility checks.

**Produces:** follow/comment enforcement and database-level comment hiding before pagination.

- [ ] **Step 1: Write failing regressions**

```java
when(userBlockAccessService.isBlockedEitherDirection(requesterId, ownerId)).thenReturn(true);
assertThatThrownBy(() -> followerService.followUser(requesterId, ownerId))
        .isInstanceOf(ForbiddenException.class);

Page<CommentResponseDTO> page = commentService.getCommentsForList(viewerId, listId, 1, 20);
assertThat(page.getContent()).extracting(CommentResponseDTO::userId).doesNotContain(blockedAuthorId);
assertThat(page.getTotalElements()).isEqualTo(1L);
```

Cover blocked request acceptance, a reply to a blocked author, and comments on a blocked list, diary,
dropped entry, Pick, and template owner.

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\mvnw.cmd test "-Dtest=FollowerServiceImplTest,CommentRepositoryTest,CommentServiceImplTest,CommentControllerIntegrationTest"
```

- [ ] **Step 3: Implement only the promised boundaries**

Acquire the same canonical unordered pair advisory lock used by `UserBlockServiceImpl` before checking
block state or loading the follow row. Call `userBlockAccessService.assertInteractionAllowed(followerId,
followedId)` inside that protected transaction before follow creation and acceptance. In comment creation,
check target owner and parent-comment author. Do not restrict
top-level comments on global Content because Content has no user owner.

Replace each paginated comment query with explicit `value`/`countQuery` predicates:

```sql
AND NOT EXISTS (
  SELECT 1 FROM user_blocks block
  WHERE (block.blocker_id = :viewerId AND block.blocked_id = comment.user_id)
     OR (block.blocker_id = comment.user_id AND block.blocked_id = :viewerId)
)
```

Pass the same viewer predicate into `CommentPreviewAssembler` count/recent loaders. Do not filter an
already-paged list in memory.

- [ ] **Step 4: Verify GREEN and commit**

Run:

```powershell
.\mvnw.cmd test "-Dtest=FollowerServiceImplTest,CommentRepositoryTest,CommentServiceImplTest,CommentControllerIntegrationTest,FeedServiceImplTest"
git add src/main/java/com/watchwise/watchwise_api/follower src/main/java/com/watchwise/watchwise_api/comment src/test/java/com/watchwise/watchwise_api/follower src/test/java/com/watchwise/watchwise_api/comment
git commit -m "feat(user-block): enforce social boundaries"
```

Confirm tests pass and the commit has no `Co-Authored-By`.

### Task 3: Add the Settings read model

**Files:**

- Create: `src/main/java/com/watchwise/watchwise_api/settings/dto/SettingsViewDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/settings/dto/SettingsProfileDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/settings/dto/SettingsPrivacyDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/settings/dto/SettingsContentPreferencesDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/settings/dto/SettingsAccountDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/settings/dto/SettingsUserPreviewDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/settings/service/SettingsViewService.java`
- Create: `src/main/java/com/watchwise/watchwise_api/settings/service/impl/SettingsViewServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/user/controller/UserController.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/follower/repository/FollowerRepository.java`
- Test: `src/test/java/com/watchwise/watchwise_api/settings/service/impl/SettingsViewServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/user/controller/UserControllerIntegrationTest.java`

**Consumes:** `UserRepository`, `FollowerRepository`, `UserBlockRepository`.

**Produces:** `GET /users/me/settings`; one bounded read with no diary/genre statistic calculation.

- [ ] **Step 1: Write failing DTO/service tests**

```java
public record SettingsViewDTO(
        SettingsProfileDTO profile,
        SettingsPrivacyDTO privacy,
        SettingsContentPreferencesDTO contentPreferences,
        SettingsAccountDTO account) {}

public record SettingsPrivacyDTO(
        long followersCount, long followingCount,
        long pendingFollowRequestsCount, List<SettingsUserPreviewDTO> pendingFollowRequests,
        long blockedUsersCount, List<SettingsUserPreviewDTO> blockedUsers) {}
```

Create eleven pending requesters and eleven blocks. Assert newest-ten previews, full counts of eleven,
no password/role fields, and an empty pending section once the owner becomes public.

- [ ] **Step 2: Verify RED**

```powershell
.\mvnw.cmd test "-Dtest=SettingsViewServiceImplTest,UserControllerIntegrationTest"
```

- [ ] **Step 3: Implement assembly and route**

Use direct follower/block count queries and `PageRequest.of(0, 10, Sort.by(DESC, "createdAt").and(Sort.by(DESC, "id")))`.
Load the user once. Return profile URL fields, visibility, language, region, and email. Query pending
requests only for private profiles. Do not call `UserService.getCurrentUser`.

Add to `UserController`:

```java
@GetMapping("/me/settings")
public ResponseEntity<SettingsViewDTO> getSettings() {
    return ResponseEntity.ok(settingsViewService.getSettings(getCurrentUserId()));
}
```

- [ ] **Step 4: Verify GREEN and commit**

```powershell
.\mvnw.cmd test "-Dtest=SettingsViewServiceImplTest,UserControllerIntegrationTest,UserBlockRepositoryTest"
git add src/main/java/com/watchwise/watchwise_api/settings src/main/java/com/watchwise/watchwise_api/user/controller/UserController.java src/main/java/com/watchwise/watchwise_api/follower/repository/FollowerRepository.java src/test/java/com/watchwise/watchwise_api/settings src/test/java/com/watchwise/watchwise_api/user/controller/UserControllerIntegrationTest.java
git commit -m "feat(settings): add settings view"
```

### Task 4: Add idempotent banner URL removal

**Files:**

- Modify: `src/main/java/com/watchwise/watchwise_api/user/service/UserService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/user/service/impl/UserServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/user/controller/UserController.java`
- Test: `src/test/java/com/watchwise/watchwise_api/user/service/impl/UserServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/user/controller/UserControllerIntegrationTest.java`

**Produces:** `DELETE /users/me/banner`, which clears only the URL banner.

- [ ] **Step 1: Write failing tests**

```java
UserResponseDTO response = userService.removeBanner(userId);
assertThat(response.banner()).isNull();
assertThat(response.profilePicture()).isEqualTo(originalProfilePicture);
```

Cover no-banner idempotency, 401, invalid CSRF, missing owner, and subsequent `GET /users/me`.

- [ ] **Step 2: Verify RED, implement, verify GREEN**

Add:

```java
UserResponseDTO removeBanner(UUID id);

@DeleteMapping("/me/banner")
public ResponseEntity<Void> removeBanner() {
    userService.removeBanner(getCurrentUserId());
    return ResponseEntity.noContent().build();
}
```

The service sets `banner` to null, updates `updatedAt`, saves, and returns the ordinary user response.
It never requires `currentPassword` and never changes `profilePicture`.

Run:

```powershell
.\mvnw.cmd test "-Dtest=UserServiceImplTest,UserControllerIntegrationTest"
```

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/com/watchwise/watchwise_api/user src/test/java/com/watchwise/watchwise_api/user
git commit -m "feat(user): add banner removal"
```

### Task 5: Update local docs and verify the integration

**Files:**

- Modify locally only: `docs/context/openapi.yaml`, `docs/context/database-schema.md`, `docs/context/development-stages.md`, `docs/context/business-rules.md`, `docs/context/progress.md`, `docs/telas-reqs-definitivo.md`, `AGENTS.md`

- [ ] **Step 1: Document**

Document the Settings read, block routes and V72, block effects and pagination rule, banner deletion,
URL-only media policy, retained PATCH profile URL fields, UserBlock mapping/build order, and no-sensitive-data
guarantee. Do not stage documentation.

- [ ] **Step 2: Run focused verification**

```powershell
.\mvnw.cmd test "-Dtest=UserBlockRepositoryTest,UserBlockServiceImplTest,UserBlockControllerIntegrationTest,FollowerServiceImplTest,CommentRepositoryTest,CommentServiceImplTest,CommentControllerIntegrationTest,SettingsViewServiceImplTest,UserServiceImplTest,UserControllerIntegrationTest,GlobalExceptionHandlerTest"
```

- [ ] **Step 3: Run the full suite and audit Git**

```powershell
.\mvnw.cmd test
git log --format=%B origin/main..HEAD | Select-String -Pattern "Co-Authored-By"
git status --short
```

Expected: Maven exit code 0 with zero failures/errors/skips; no attribution trailer; no tracked docs.

## Plan self-review

- The plan removes all storage work because photo/banner are URL values.
- The Settings module is a deep read module: one route hides bounded repository reads and no caller learns
  profile-stat aggregation details.
- UserBlock has one social-access interface, avoiding duplicated block checks across follower/comment code.
- Existing profile and credential PATCH logic is reused rather than duplicated.
- Blocked comments are excluded in SQL before pagination, preserving both page content and totals.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-10-16-settings-screen-backend.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task and review each task independently.
2. **Inline Execution** — Execute tasks in this session with checkpoints.

Which approach?
