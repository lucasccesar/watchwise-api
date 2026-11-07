# Follow Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Notify users when they receive a private follow request, gain a follower through a public profile, or have a request accepted.

**Architecture:** FollowerServiceImpl owns each relationship transition and calls a focused FollowNotificationService to persist individual Notification rows. The existing table and GET /notifications endpoint are reused; Flyway adds a third valid notification shape with an actor but no content or social target. Relationship changes and notifications share a transaction, with a shared lock for a stable profile-visibility decision and exclusive locks for pending-request changes.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL/Flyway, MapStruct, JUnit 5, Mockito, Testcontainers, Maven.

## Global Constraints

- No route, table, DTO field, or NotificationTargetType.USER is added.
- Add FOLLOW_REQUEST, NEW_FOLLOWER, and FOLLOW_REQUEST_ACCEPTED to NotificationType.
- Follow notifications are individual; LIKE_RECEIVED and COMMENT_RECEIVED remain the only aggregates.
- Follow rows have null content, personTmdbId, targetType, and targetId. latestActor is set when created and may later become null through the existing ON DELETE SET NULL FK.
- Follow notifications use the existing retention: seven days when read and thirty days when unread.
- Automatic acceptance keeps the existing all-or-nothing transaction but writes its notification rows with saveAll, allowing Hibernate's configured JDBC batch size of 25 to reduce insert round-trips.
- Create V70__add-follow-notifications.sql. Do not edit V62__add-social-notifications.sql.
- Preserve duplicate-follow 409 and missing/non-pending request 404 behavior.
- Update docs/context/openapi.yaml, database-schema.md, business-rules.md, and the 2026-10-13 entry in progress.md. Leave those documentation files uncommitted.
- Code commits use Conventional Commits with no Co-Authored-By or self-attribution. Never push without explicit approval.

## File Map

| File | Responsibility |
| --- | --- |
| src/main/resources/db/migration/V70__add-follow-notifications.sql | Third notification shape and retention index predicate. |
| notification/entity/NotificationType.java | Three new event types. |
| notification/mapper/NotificationMapper.java | Maps latestActor without requiring targetType. |
| notification/repository/NotificationRepository.java | Deletes expired follow notifications. |
| notification/service/impl/ContentTrackingServiceImpl.java | Keeps follow notification types out of the content-tracking switch. |
| notification/service/FollowNotificationService.java | Interface used by follow transitions. |
| notification/service/impl/FollowNotificationServiceImpl.java | Builds messages, timestamps, and rows. |
| follower/repository/FollowerRepository.java | Locks pending requests and loads bulk pending requests. |
| user/repository/UserRepository.java | Takes a shared lock while reading the followed profile's visibility. |
| follower/service/FollowerService.java and impl/FollowerServiceImpl.java | Atomic follow and automatic-accept behavior. |
| user/service/impl/UserServiceImpl.java | Flushes a public-profile change before automatic acceptance. |
| src/test/java/.../notification/service/impl/FollowNotificationServiceImplTest.java | Unit coverage for direct notification construction. |
| src/test/java/.../follower, user, notification tests | Service, repository, HTTP, retention, and concurrency regressions. |
| docs/context files | Contract, data-model, rule, and progress synchronization. |

### Task 1: Extend Notification persistence and mapping

**Files:**
- Create: src/main/resources/db/migration/V70__add-follow-notifications.sql
- Modify: src/main/java/com/watchwise/watchwise_api/notification/entity/NotificationType.java
- Modify: src/main/java/com/watchwise/watchwise_api/notification/mapper/NotificationMapper.java
- Modify: src/main/java/com/watchwise/watchwise_api/notification/repository/NotificationRepository.java
- Modify: src/main/java/com/watchwise/watchwise_api/notification/service/impl/ContentTrackingServiceImpl.java
- Test: src/test/java/com/watchwise/watchwise_api/notification/repository/NotificationRepositoryTest.java
- Test: src/test/java/com/watchwise/watchwise_api/notification/controller/NotificationControllerIntegrationTest.java

**Interfaces:** Produces three actor-backed types using Notification.user as recipient and Notification.latestActor as actor.

- [ ] **Step 1: Write failing persistence and response tests**

In NotificationRepositoryTest, persist a second user and add a valid follow notification plus an invalid follow notification carrying content.

    Notification saved = notificationRepository.saveAndFlush(Notification.builder()
            .user(lucas).latestActor(marina)
            .type(NotificationType.FOLLOW_REQUEST)
            .message("marina requested to follow you")
            .isRead(false).createdAt(now).updatedAt(now).build());

    assertThat(saved.getContent()).isNull();
    assertThat(saved.getTargetType()).isNull();
    assertThat(saved.getTargetId()).isNull();
    assertThat(saved.getLatestActor()).isSameAs(marina);

Expect DataIntegrityViolationException from a FOLLOW_REQUEST with content set. In NotificationControllerIntegrationTest, assert latestActor.id and latestActor.username are returned, while content, targetType, targetId, and interactionCount are null.

- [ ] **Step 2: Run tests to confirm red**

Run: .\mvnw.cmd test "-Dtest=NotificationRepositoryTest,NotificationControllerIntegrationTest"

Expected: compilation fails for the new enums or the existing check constraint rejects the row.

- [ ] **Step 3: Add enum, migration, mapper, and cleanup predicate**

Append these enum values:

    FOLLOW_REQUEST,
    NEW_FOLLOWER,
    FOLLOW_REQUEST_ACCEPTED

V70 drops and recreates ck_notifications_social_shape with these three branches:

    (type IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED')
     AND content_id IS NULL AND target_type IS NOT NULL
     AND target_id IS NOT NULL AND interaction_count > 0)
    OR
    (type IN ('FOLLOW_REQUEST', 'NEW_FOLLOWER', 'FOLLOW_REQUEST_ACCEPTED')
     AND content_id IS NULL AND target_type IS NULL
     AND target_id IS NULL AND interaction_count = 1)
    OR
    (type NOT IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED',
                  'FOLLOW_REQUEST', 'NEW_FOLLOWER', 'FOLLOW_REQUEST_ACCEPTED')
     AND content_id IS NOT NULL AND target_type IS NULL AND target_id IS NULL)

Recreate idx_notifications_social_retention with all five social/follow types. Do not require actor_user_id in the check because the current FK deliberately clears it when its user is deleted.

Update the mapper condition:

    @Mapping(target = "latestActor", conditionExpression = "java(notification.getLatestActor() != null)")

Extend NotificationRepository.deleteExpiredSocialNotifications with FOLLOW_REQUEST, NEW_FOLLOWER, and FOLLOW_REQUEST_ACCEPTED. Keep its batching behavior and leave the aggregate unique index restricted to likes/comments. Update the exhaustive content-tracking switch with an explicit unsupported branch for the three follow types; they are written only by FollowNotificationService.

- [ ] **Step 4: Run tests to confirm green and commit**

Run: .\mvnw.cmd test "-Dtest=NotificationRepositoryTest,NotificationControllerIntegrationTest,NotificationCleanupServiceImplTest"

Expected: valid follow rows persist, actor previews serialize, system notifications remain outside cleanup.

    git add src/main/resources/db/migration/V70__add-follow-notifications.sql src/main/java/com/watchwise/watchwise_api/notification/entity/NotificationType.java src/main/java/com/watchwise/watchwise_api/notification/mapper/NotificationMapper.java src/main/java/com/watchwise/watchwise_api/notification/repository/NotificationRepository.java src/test/java/com/watchwise/watchwise_api/notification/repository/NotificationRepositoryTest.java src/test/java/com/watchwise/watchwise_api/notification/controller/NotificationControllerIntegrationTest.java
    git commit -m "feat(notification): add follow notification types"

Confirm no Co-Authored-By trailer or self-attribution.

### Task 2: Create the dedicated follow notification module

**Files:**
- Create: src/main/java/com/watchwise/watchwise_api/notification/service/FollowNotificationService.java
- Create: src/main/java/com/watchwise/watchwise_api/notification/service/impl/FollowNotificationServiceImpl.java
- Test: src/test/java/com/watchwise/watchwise_api/notification/service/impl/FollowNotificationServiceImplTest.java

**Interfaces:**

    void notifyFollowRequested(User requester, User profileOwner);
    void notifyNewFollower(User follower, User profileOwner);
    void notifyFollowRequestsAccepted(User profileOwner, Collection<User> requesters);

- [ ] **Step 1: Write failing unit tests with a fixed Clock**

Capture saved notifications for the first two methods. For accepted requests, pass one and then two requesters, asserting one notification per requester in a single saveAll call. Assert recipient, latestActor, type, message, false isRead, timestamps, and null content/target fields. Use these messages:

    lucas requested to follow you
    lucas started following you
    marina accepted your follow request

- [ ] **Step 2: Run the test to confirm red**

Run: .\mvnw.cmd test "-Dtest=FollowNotificationServiceImplTest"

Expected: the module does not yet compile.

- [ ] **Step 3: Implement the module**

Use NotificationRepository and Clock. Keep callers unaware of persistence details; the plural accepted method is the only bulk interface exposed because automatic profile publication is its real second caller:

    private void create(User recipient, User actor, NotificationType type, String message) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        notificationRepository.save(Notification.builder()
                .user(recipient).latestActor(actor).type(type).message(message)
                .isRead(false).createdAt(now).updatedAt(now).build());
    }

    private void createAll(User owner, Collection<User> requesters) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        notificationRepository.saveAll(requesters.stream()
                .map(requester -> Notification.builder()
                        .user(requester).latestActor(owner)
                        .type(NotificationType.FOLLOW_REQUEST_ACCEPTED)
                        .message(owner.getUsername() + " accepted your follow request")
                        .isRead(false).createdAt(now).updatedAt(now).build())
                .toList());
    }

Each public method provides the correct actor, recipient, event type, and username-based message. Do not populate content, person, target, or interaction count.

- [ ] **Step 4: Run the test to confirm green and commit**

Run: .\mvnw.cmd test "-Dtest=FollowNotificationServiceImplTest"

Expected: one correctly shaped individual row is saved per event.

    git add src/main/java/com/watchwise/watchwise_api/notification/service/FollowNotificationService.java src/main/java/com/watchwise/watchwise_api/notification/service/impl/FollowNotificationServiceImpl.java src/test/java/com/watchwise/watchwise_api/notification/service/impl/FollowNotificationServiceImplTest.java
    git commit -m "feat(notification): create follow notification service"

Confirm no Co-Authored-By trailer or self-attribution.

### Task 3: Make direct follow mutations atomic and locked

**Files:**
- Modify: src/main/java/com/watchwise/watchwise_api/user/repository/UserRepository.java
- Modify: src/main/java/com/watchwise/watchwise_api/follower/repository/FollowerRepository.java
- Modify: src/main/java/com/watchwise/watchwise_api/follower/service/impl/FollowerServiceImpl.java
- Test: src/test/java/com/watchwise/watchwise_api/follower/service/impl/FollowerServiceImplTest.java
- Test: src/test/java/com/watchwise/watchwise_api/follower/repository/FollowerRepositoryTest.java
- Test: src/test/java/com/watchwise/watchwise_api/user/repository/UserRepositoryTest.java

**Interfaces:**

    Optional<User> findByIdForFollowStatus(UUID id);
    Optional<Follower> findPendingByFollowerIdAndFollowedIdForUpdate(UUID followerId, UUID followedId);

- [ ] **Step 1: Write failing transition tests**

For a public profile, verify saveAndFlush happens before notifyNewFollower. For a private profile, verify notifyFollowRequested. For a locked pending request, verify acceptance calls notifyFollowRequestsAccepted with a singleton requester collection. Conflict, missing request, and rejection must not call the new module.

    followerService.followUser(followerId, followedId);
    verify(followerRepository).saveAndFlush(any(Follower.class));
    verify(followNotificationService).notifyNewFollower(followerUser, followedUser);

- [ ] **Step 2: Run tests to confirm red**

Run: .\mvnw.cmd test "-Dtest=FollowerServiceImplTest,FollowerRepositoryTest"

Expected: dependency, saveAndFlush, and lock methods are missing.

- [ ] **Step 3: Add lock queries**

In UserRepository:

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForFollowStatus(@Param("id") UUID id);

In FollowerRepository:

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT f FROM Follower f
            WHERE f.follower.id = :followerId AND f.followed.id = :followedId
              AND f.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.PENDING
            """)
    Optional<Follower> findPendingByFollowerIdAndFollowedIdForUpdate(
            @Param("followerId") UUID followerId, @Param("followedId") UUID followedId);

Repository tests prove the second query finds a pending row and excludes an accepted row. UserRepositoryTest is extended in Task 5 to prove the shared profile lock blocks a competing visibility update until the follow-status read completes.

- [ ] **Step 4: Implement transactional follow transitions**

Inject FollowNotificationService. Annotate followUser, acceptFollowRequest, and rejectFollowRequest with Transactional. Load the followed user with findByIdForFollowStatus. A shared lock permits many follows for the same profile while blocking a conflicting visibility update. Preserve existing validations and map the unique violation before notification creation.

    Follower saved = followerRepository.saveAndFlush(newFollow);
    if (saved.getStatus() == FollowStatus.PENDING) {
        followNotificationService.notifyFollowRequested(follower, followed);
    } else {
        followNotificationService.notifyNewFollower(follower, followed);
    }

Use the locked pending lookup for both accept and reject. Acceptance saves ACCEPTED then calls notifyFollowRequestsAccepted with List.of(follow.getFollower()); rejection deletes and emits no notification. The profile lock makes a concurrent privacy flip either accept an already-created pending row or make the follow observe a public profile.

- [ ] **Step 5: Run tests to confirm green and commit**

Run: .\mvnw.cmd test "-Dtest=FollowerServiceImplTest,FollowerRepositoryTest"

Expected: every valid direct action creates only its intended notification.

    git add src/main/java/com/watchwise/watchwise_api/user/repository/UserRepository.java src/main/java/com/watchwise/watchwise_api/follower/repository/FollowerRepository.java src/main/java/com/watchwise/watchwise_api/follower/service/impl/FollowerServiceImpl.java src/test/java/com/watchwise/watchwise_api/follower/service/impl/FollowerServiceImplTest.java src/test/java/com/watchwise/watchwise_api/follower/repository/FollowerRepositoryTest.java
    git commit -m "feat(follower): notify follow transitions"

Confirm no Co-Authored-By trailer or self-attribution.

### Task 4: Notify automatic acceptance when a profile becomes public

**Files:**
- Modify: src/main/java/com/watchwise/watchwise_api/follower/service/FollowerService.java
- Modify: src/main/java/com/watchwise/watchwise_api/follower/repository/FollowerRepository.java
- Modify: src/main/java/com/watchwise/watchwise_api/follower/service/impl/FollowerServiceImpl.java
- Modify: src/main/java/com/watchwise/watchwise_api/user/service/impl/UserServiceImpl.java
- Test: src/test/java/com/watchwise/watchwise_api/follower/service/impl/FollowerServiceImplTest.java
- Test: src/test/java/com/watchwise/watchwise_api/follower/repository/FollowerRepositoryTest.java
- Test: src/test/java/com/watchwise/watchwise_api/user/service/UserServiceImplTest.java

**Interfaces:**

void acceptAllPendingFollowRequestsFor(User followed);
List<Follower> findPendingByFollowedIdForUpdate(UUID followedId);

- [ ] **Step 1: Write failing bulk-accept tests**

With two pending requests and one accepted relationship, verify only pending rows become ACCEPTED and both requesters are passed together to one notifyFollowRequestsAccepted call. In UserServiceImplTest, require profile flush before bulk acceptance:

    InOrder order = inOrder(userRepository, followerService);
    order.verify(userRepository).saveAndFlush(savedUser);
    order.verify(followerService).acceptAllPendingFollowRequestsFor(savedUser);

- [ ] **Step 2: Run tests to confirm red**

Run: .\mvnw.cmd test "-Dtest=FollowerServiceImplTest,FollowerRepositoryTest,UserServiceImplTest"

Expected: UUID-only bulk acceptance and JPQL update cannot provide requesters to the notification module.

- [ ] **Step 3: Replace the bulk update with locked rows**

Replace the existing update query with:

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT f FROM Follower f JOIN FETCH f.follower
            WHERE f.followed.id = :followedId
              AND f.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.PENDING
            """)
    List<Follower> findPendingByFollowedIdForUpdate(@Param("followedId") UUID followedId);

Change the service contract to accept the profile owner and implement:

    @Transactional
    public void acceptAllPendingFollowRequestsFor(User followed) {
        List<Follower> pending = followerRepository.findPendingByFollowedIdForUpdate(followed.getId());
        pending.forEach(follow -> {
            follow.setStatus(FollowStatus.ACCEPTED);
        });
        followerRepository.saveAll(pending);
        followNotificationService.notifyFollowRequestsAccepted(followed,
                pending.stream().map(Follower::getFollower).toList());
    }

- [ ] **Step 4: Flush profile change before automatic acceptance**

Remove the side effect from applyPatch. In updateUser, calculate becamePublic before applying the patch, save/flush, then accept pending requests before computing response statistics:

    boolean becamePublic = Boolean.TRUE.equals(patchUserDTO.isProfilePublic())
            && !Boolean.TRUE.equals(user.getIsProfilePublic());
    boolean touchesCredentials = applyPatch(user, patchUserDTO);
    user.setUpdatedAt(LocalDateTime.now());
    User saved = userRepository.saveAndFlush(user);
    if (becamePublic) followerService.acceptAllPendingFollowRequestsFor(saved);
    UserResponseDTO response = toUserResponseDto(saved, computeProfileStats(saved.getId()));

- [ ] **Step 5: Run tests to confirm green and commit**

Run: .\mvnw.cmd test "-Dtest=FollowerServiceImplTest,FollowerRepositoryTest,UserServiceImplTest"

Expected: profile visibility, accepted rows, confirmation notifications, and follower counts are consistent in one transaction.

    git add src/main/java/com/watchwise/watchwise_api/follower/service/FollowerService.java src/main/java/com/watchwise/watchwise_api/follower/repository/FollowerRepository.java src/main/java/com/watchwise/watchwise_api/follower/service/impl/FollowerServiceImpl.java src/main/java/com/watchwise/watchwise_api/user/service/impl/UserServiceImpl.java src/test/java/com/watchwise/watchwise_api/follower/service/impl/FollowerServiceImplTest.java src/test/java/com/watchwise/watchwise_api/follower/repository/FollowerRepositoryTest.java src/test/java/com/watchwise/watchwise_api/user/service/UserServiceImplTest.java
    git commit -m "feat(follower): notify automatic request accepts"

Confirm no Co-Authored-By trailer or self-attribution.

### Task 5: Validate HTTP flows, retention, and concurrent acceptance

**Files:**
- Modify: src/test/java/com/watchwise/watchwise_api/follower/controller/FollowerControllerIntegrationTest.java
- Modify: src/test/java/com/watchwise/watchwise_api/user/controller/UserControllerIntegrationTest.java
- Modify: src/test/java/com/watchwise/watchwise_api/notification/repository/NotificationRepositoryTest.java
- Modify: src/test/java/com/watchwise/watchwise_api/notification/service/impl/NotificationCleanupServiceImplTest.java
- Modify: src/test/java/com/watchwise/watchwise_api/follower/repository/FollowerRepositoryTest.java
- Modify: src/test/java/com/watchwise/watchwise_api/user/repository/UserRepositoryTest.java

- [ ] **Step 1: Write HTTP tests**

Autowire NotificationRepository in FollowerControllerIntegrationTest. Verify public follow creates NEW_FOLLOWER for the profile owner; private follow creates FOLLOW_REQUEST; manual acceptance creates FOLLOW_REQUEST_ACCEPTED only for requester. GET /notifications for each recipient must expose the other user in latestActor.

    mockMvc.perform(postFollowRequest(follower, publicTarget.id())).andExpect(status().isOk());
    assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(publicTarget.id(), PageRequest.of(0, 10))
            .getContent()).extracting(Notification::getType)
            .containsExactly(NotificationType.NEW_FOLLOWER);

In UserControllerIntegrationTest, create a pending request, make the owner public, then assert relationship ACCEPTED and one notification for requester.

- [ ] **Step 2: Add retention and deterministic lock tests**

Extend NotificationRepositoryTest so the three types expire at the existing read/unread cutoffs and RELEASE plus FOLLOWED_PERSON_NEW_CREDIT remain. Keep one sequential HTTP assertion that the second accept returns 404 and only one confirmation exists. Prove both locks with independently committed transactions coordinated by latches, rather than relying on scheduler timing between two MockMvc requests.

    CountDownLatch firstLocked = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    Future<Void> first = executor.submit(() -> inTransaction(() -> {
        followerRepository.findPendingByFollowerIdAndFollowedIdForUpdate(requesterId, ownerId);
        firstLocked.countDown();
        releaseFirst.await();
    }));
    assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();
    Future<Void> second = executor.submit(() -> inTransaction(() ->
        followerRepository.findPendingByFollowerIdAndFollowedIdForUpdate(requesterId, ownerId)));
    assertThat(second.isDone()).isFalse();
    releaseFirst.countDown();
    first.get();
    second.get();

Implement inTransaction with the injected PlatformTransactionManager or TransactionTemplate, always shut down the executor in finally, and assert the second transaction completes only after the first releases its row lock.

In UserRepositoryTest, use the same pattern to prove that a shared visibility read blocks a profile update:

    Future<Void> reader = executor.submit(() -> inTransaction(() -> {
        userRepository.findByIdForFollowStatus(ownerId);
        firstLocked.countDown();
        releaseFirst.await();
    }));
    assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();
    Future<Void> writer = executor.submit(() -> inTransaction(() -> {
        User owner = userRepository.findById(ownerId).orElseThrow();
        owner.setIsProfilePublic(true);
        userRepository.saveAndFlush(owner);
    }));
    assertThat(writer.isDone()).isFalse();
    releaseFirst.countDown();
    reader.get();
    writer.get();

- [ ] **Step 3: Run focused regression and commit**

Run: .\mvnw.cmd test "-Dtest=FollowNotificationServiceImplTest,FollowerServiceImplTest,FollowerRepositoryTest,FollowerControllerIntegrationTest,UserServiceImplTest,UserControllerIntegrationTest,NotificationRepositoryTest,NotificationControllerIntegrationTest,NotificationCleanupServiceImplTest"

Expected: recipients, actor preview, retention, sequential duplicate acceptance, and real PostgreSQL lock behavior are correct; no production endpoint or test hook was added.

    git add src/test/java/com/watchwise/watchwise_api/follower/controller/FollowerControllerIntegrationTest.java src/test/java/com/watchwise/watchwise_api/user/controller/UserControllerIntegrationTest.java src/test/java/com/watchwise/watchwise_api/notification/repository/NotificationRepositoryTest.java src/test/java/com/watchwise/watchwise_api/notification/service/impl/NotificationCleanupServiceImplTest.java src/test/java/com/watchwise/watchwise_api/follower/repository/FollowerRepositoryTest.java src/test/java/com/watchwise/watchwise_api/user/repository/UserRepositoryTest.java
    git commit -m "test(notification): cover follow notification flows"

Confirm no Co-Authored-By trailer or self-attribution.

### Task 6: Synchronize API and domain documentation

**Files:**
- Modify: docs/context/openapi.yaml
- Modify: docs/context/database-schema.md
- Modify: docs/context/business-rules.md
- Modify: docs/context/progress.md

- [ ] **Step 1: Update OpenAPI and schema**

Add the three enum values. Document that follow rows have latestActor but null content, personTmdbId, targetType, targetId, and interactionCount. Document the third database shape, actor deletion behavior, retention index, and that only likes/comments aggregate.

- [ ] **Step 2: Update rules and progress**

Record the three delivery events, no notification for reject/unfollow, automatic acceptance on a private-to-public transition, retention, and locks. Append only shipped work under 2026-10-13; do not add next steps.

- [ ] **Step 3: Run final verification**

Run:

    rg -n "FOLLOW_REQUEST|NEW_FOLLOWER|FOLLOW_REQUEST_ACCEPTED" src/main/java src/main/resources/db/migration docs/context
    .\mvnw.cmd test
    git status --short

Expected: all types appear in code, migration, and documentation; all tests pass; the four documentation files remain worktree-only.

## Plan Self-Review

- Covers public follow, private request, manual accept, and automatic accept on profile publication.
- Covers self-follow, duplicate follow, missing/rejected request, actor deletion, privacy race, deterministic lock serialization, exact retention cutoffs, and preservation of system notifications.
- Keeps a small notification-module interface while callers express only domain transitions.
