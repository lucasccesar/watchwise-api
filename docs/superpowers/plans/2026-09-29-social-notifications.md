# Social Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** Add aggregated like/comment notifications for every owned social target and automatically retain read items for 7 days and unread items for 30 days.

**Architecture:** Keep existing TMDB notification rows and read API intact, extend the notification row with optional social metadata, and add a dedicated social notification writer used by the Like and Comment services. A partial unique index identifies one aggregate per recipient, social action, and target; an insert-if-absent plus row lock makes aggregation and read-cycle replacement safe under concurrent requests. A scheduled cleanup job deletes only expired social rows in bounded database batches.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL, Flyway, MapStruct, Lombok, JUnit 5, Mockito, MockMvc, and Testcontainers.

## Global Constraints

- Social like notifications cover comments, diary reviews, dropped reviews, user lists, Picks, and Picks templates.
- Social comment notifications cover user lists, diary reviews, dropped reviews, Picks, and Picks templates; comments on Content have no owner and do not notify.
- Official Picks templates with no creator do not notify.
- The owner's own actions do not create a notification for that owner.
- While unread, repeated actions update one aggregate row and increment its count.
- After the aggregate is read, the next action deletes the old row and creates a new row with a new id, count 1, the latest actor, and isRead false.
- Unlike counts, notification interaction counts are not decremented when a Like is removed.
- Read social notifications expire after 7 days; unread social notifications expire after 30 days, both measured from updatedAt.
- Cleanup affects only LIKE_RECEIVED and COMMENT_RECEIVED rows and runs in bounded batches.
- Existing TMDB and followed-person notification types and behavior remain unchanged.
- Use typed domain exceptions and the project's ApiError handling; do not build controller error bodies manually.
- Preserve the existing unrelated .gitignore worktree change.
- Documentation changes remain worktree-only under the repository convention; code/test commits must not include documentation-only changes.
- Commit messages use one-line Conventional Commits and contain no Co-Authored-By trailer.

## File map

### Persistence and API files

- Create src/main/resources/db/migration/V62__add-social-notifications.sql with nullable content support, social metadata, checks, indexes, and the aggregate unique index.
- Create src/main/java/com/watchwise/watchwise_api/notification/entity/NotificationTargetType.java for the six supported social target kinds.
- Modify NotificationType.java with LIKE_RECEIVED and COMMENT_RECEIVED.
- Modify Notification.java with nullable latest actor, target type/id, and interaction count fields.
- Modify NotificationResponseDTO.java to expose social metadata and the latest actor preview.
- Modify NotificationMapper.java to use ContentMapper and UserMapper while mapping nullable fields.
- Modify NotificationRepository.java with eager read queries, social aggregate locking/insertion, and bounded cleanup deletion.

### Social notification flow

- Create SocialNotificationService.java as the narrow writer interface used by Like and Comment.
- Create SocialNotificationServiceImpl.java for message generation, aggregation, read-cycle replacement, and self-action suppression.
- Modify LikeServiceImpl.java to notify after each successful supported Like target.
- Modify CommentServiceImpl.java to notify after each owner-bearing comment target is persisted.
- Modify CommentRepository.java so the Like path fetches the comment author with its target graph.

### Retention and scheduling

- Create NotificationCleanupService.java.
- Create NotificationCleanupServiceImpl.java with UTC-clock-to-database-time conversion, 7/30-day cutoffs, and batch loops.
- Create NotificationCleanupJob.java using @Scheduled.
- Modify application-dev.properties and application-prod.properties with app.notification.cleanup.cron=0 30 3 * * *.

### Tests

- Modify NotificationServiceImplTest.java for social response mapping and read timestamps.
- Create SocialNotificationServiceImplTest.java for aggregation, replacement, messages, self-actions, and concurrency call ordering.
- Create NotificationCleanupServiceImplTest.java for both retention cutoffs and batch looping.
- Create NotificationCleanupJobTest.java for scheduled delegation.
- Modify LikeServiceImplTest.java for all six Like target branches and notification suppression.
- Modify CommentServiceImplTest.java for all comment target branches, Content exclusion, official template exclusion, and self-action suppression.
- Modify NotificationRepositoryTest.java for the migration constraints, aggregate uniqueness, locking lookup, and cleanup batch query.
- Modify NotificationControllerIntegrationTest.java for the social response contract and ownership filtering.
- Create SocialNotificationIntegrationTest.java for end-to-end Like/Comment aggregation and retention behavior against PostgreSQL.

### Documentation

- Modify docs/context/openapi.yaml with social notification types, nullable content, actor, target metadata, and interaction count.
- Modify docs/context/database-schema.md and docs/context/database-schema.html with the extended notification table and aggregate index/check semantics.
- Modify docs/context/business-rules.md with aggregation, owner resolution, self-action, ownerless-target, read-cycle replacement, and retention rules.
- Append the shipped implementation to the existing 2026-09-29 section of docs/context/progress.md.

---

### Task 1: Extend notification persistence and response mapping

**Files:**
- Create: src/main/resources/db/migration/V62__add-social-notifications.sql
- Create: src/main/java/com/watchwise/watchwise_api/notification/entity/NotificationTargetType.java
- Modify: src/main/java/com/watchwise/watchwise_api/notification/entity/NotificationType.java
- Modify: src/main/java/com/watchwise/watchwise_api/notification/entity/Notification.java
- Modify: src/main/java/com/watchwise/watchwise_api/notification/dto/NotificationResponseDTO.java
- Modify: src/main/java/com/watchwise/watchwise_api/notification/mapper/NotificationMapper.java
- Modify: src/main/java/com/watchwise/watchwise_api/notification/repository/NotificationRepository.java
- Test: src/test/java/com/watchwise/watchwise_api/notification/service/impl/NotificationServiceImplTest.java
- Test: src/test/java/com/watchwise/watchwise_api/notification/repository/NotificationRepositoryTest.java

**Interfaces:**
- Produces NotificationTargetType, NotificationType.LIKE_RECEIVED, NotificationType.COMMENT_RECEIVED, and nullable social fields consumed by later tasks.
- Keeps NotificationResponseDTO compatible with existing system notifications by returning null social fields when targetType is null.

- [ ] Step 1: Add failing mapping assertions.

Extend NotificationServiceImplTest with a social fixture containing a latest actor, targetType DIARY_ENTRY, targetId, and interactionCount 2. Assert that getNotifications maps all four social fields while a legacy RELEASE fixture still maps nullable social fields as null.

- [ ] Step 2: Add failing repository integration cases.

Extend NotificationRepositoryTest with PostgreSQL-backed cases that persist one legacy notification and one social notification, assert that a duplicate social aggregate key fails, and assert that two different target types can reuse the same target UUID without conflict.

- [ ] Step 3: Add the migration.

Create V62__add-social-notifications.sql with this database behavior:

~~~sql
ALTER TABLE notifications
    ALTER COLUMN content_id DROP NOT NULL;

ALTER TABLE notifications
    ADD COLUMN actor_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN target_type VARCHAR(30),
    ADD COLUMN target_id UUID,
    ADD COLUMN interaction_count INTEGER NOT NULL DEFAULT 1;

ALTER TABLE notifications
    ADD CONSTRAINT ck_notifications_social_shape CHECK (
        (type IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED')
            AND content_id IS NULL
            AND target_type IS NOT NULL
            AND target_id IS NOT NULL
            AND interaction_count > 0)
        OR
        (type NOT IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED')
            AND content_id IS NOT NULL
            AND target_type IS NULL
            AND target_id IS NULL)
    );

CREATE UNIQUE INDEX uq_notifications_social_aggregate
    ON notifications (user_id, type, target_type, target_id)
    WHERE type IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED');

CREATE INDEX idx_notifications_social_retention
    ON notifications (type, is_read, updated_at, id)
    WHERE type IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED');
~~~

Use VARCHAR(30) for enum columns and preserve all old rows with interactionCount 1. Do not add a foreign key for the polymorphic target pair.

- [ ] Step 4: Add the Java fields and enums.

Add target values with display labels used by messages: COMMENT("comment"), DIARY_ENTRY("review"), DROPPED_ENTRY("review"), USER_LIST("list"), PICK("Pick"), and PICKS_TEMPLATE("template").

Add to Notification: nullable latestActor mapped to actor_user_id, nullable targetType, nullable targetId, and non-null interactionCount defaulting to 1. Change content to optional and nullable. Keep system notification builders compiling without social fields.

Add UserPreviewDTO latestActor, NotificationTargetType targetType, UUID targetId, and Integer interactionCount to NotificationResponseDTO, appending the social fields after the existing API fields.

- [ ] Step 5: Update MapStruct and repository read methods.

Use both ContentMapper.class and UserMapper.class in NotificationMapper. Add @EntityGraph(attributePaths = {"content", "latestActor"}) to both paged notification queries so mapping does not rely on an open persistence context for nullable lazy associations.

Add these repository contracts for later tasks:

~~~java
@Modifying
@Query(value = "INSERT INTO notifications (id, user_id, type, message, content_id, person_tmdb_id, "
        + "actor_user_id, target_type, target_id, interaction_count, is_read, created_at, updated_at) "
        + "VALUES (:id, :recipientId, :notificationType, :message, NULL, NULL, :actorId, :targetType, "
        + ":targetId, 1, FALSE, :now, :now) "
        + "ON CONFLICT (user_id, type, target_type, target_id) "
        + "WHERE type IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED') DO NOTHING", nativeQuery = true)
int insertSocialAggregateIfAbsent(
        @Param("id") UUID id,
        @Param("recipientId") UUID recipientId,
        @Param("notificationType") String notificationType,
        @Param("message") String message,
        @Param("actorId") UUID actorId,
        @Param("targetType") String targetType,
        @Param("targetId") UUID targetId,
        @Param("now") LocalDateTime now);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT n FROM Notification n WHERE n.user.id = :recipientId AND n.type = :type "
        + "AND n.targetType = :targetType AND n.targetId = :targetId")
Optional<Notification> findSocialAggregateForUpdate(
        @Param("recipientId") UUID recipientId,
        @Param("type") NotificationType type,
        @Param("targetType") NotificationTargetType targetType,
        @Param("targetId") UUID targetId);
~~~

The native insert must set a generated UUID, actor, target, message, count 1, unread state, and both timestamps. The locked lookup must use the exact aggregate key.

- [ ] Step 6: Run the focused tests.

Run:

~~~powershell
.\\mvnw.cmd -Duser.home="D:\\Users\\Lucas C\\Documentos\\codigos\\watchwise-api" -Dmaven.repo.local="C:\\Users\\Lucas C\\.m2\\repository" test -Dtest=NotificationServiceImplTest,NotificationRepositoryTest
~~~

Expected: the new mapping and persistence tests pass, with legacy notification tests unchanged.

- [ ] Step 7: Commit the code and migration.

~~~powershell
git add src/main/java src/main/resources/db/migration src/test/java
git commit -m "feat(notification): add social notification fields"
~~~

After committing, confirm explicitly that the commit has no Co-Authored-By trailer or other self-attribution.

---

### Task 2: Implement the atomic social aggregate writer

**Files:**
- Create: src/main/java/com/watchwise/watchwise_api/notification/service/SocialNotificationService.java
- Create: src/main/java/com/watchwise/watchwise_api/notification/service/impl/SocialNotificationServiceImpl.java
- Test: src/test/java/com/watchwise/watchwise_api/notification/service/impl/SocialNotificationServiceImplTest.java

**Interfaces:**
- Consumes NotificationRepository, UserRepository, NotificationTargetType, and the fields from Task 1.
- Produces notifyLikeReceived(UUID actorId, UUID recipientId, NotificationTargetType targetType, UUID targetId) and notifyCommentReceived(UUID actorId, UUID recipientId, NotificationTargetType targetType, UUID targetId).

- [ ] Step 1: Write the failing service tests.

Cover these exact cases:

~~~java
notifyLikeReceived(actorId, ownerId, DIARY_ENTRY, reviewId);
// no existing row -> one unread row, count 1, message "Joao liked your review"

notifyLikeReceived(mariaId, ownerId, DIARY_ENTRY, reviewId);
// existing unread row -> same id, count 2, message "Maria and 1 more user liked your review"

markExistingNotificationRead();
notifyLikeReceived(pedroId, ownerId, DIARY_ENTRY, reviewId);
// old row is deleted, new id is created, count 1, unread, message "Pedro liked your review"
~~~

Also assert that actor and recipient with the same UUID produce no repository write, and that comments use the target-specific singular/plural grammar. Verify the native insert is attempted before the locked lookup and that an unread existing row is updated with both timestamps.

- [ ] Step 2: Implement the narrow interface and message builder.

The public methods delegate to one private notifyReceived method with NotificationType. Resolve the actor using userRepository.getReferenceById(actorId), use the actor's current username in the message, and skip when actorId equals recipientId. Build messages in English to match existing notification messages:

~~~text
<username> liked your <target>
<username> and <count - 1> more users liked your <target>
<username> commented on your <target>
<username> and <count - 1> more users commented on your <target>
~~~

Use LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()) so cleanup cutoffs and existing LocalDateTime.now() persistence use the same database wall-clock representation. Inject the existing Clock bean.

- [ ] Step 3: Implement first-write, unread-update, and read-replace paths.

Inside the caller's transaction:

1. Execute insertSocialAggregateIfAbsent with a new UUID, count 1, unread state, actor, message, and current timestamps.
2. Load the exact aggregate with findSocialAggregateForUpdate.
3. If the inserted row is the new row, return.
4. If the locked row is unread, increment interactionCount, replace latestActor, regenerate the message, set createdAt, updatedAt, and isRead false, then save it.
5. If the locked row is read, delete it, flush the delete, create a new entity with a new UUID and count 1, and save-and-flush it.

The delete/insert sequence must stay in the same transaction. It gives Pedro a new notification id and prevents the old read aggregate from remaining in the result page.

- [ ] Step 4: Run the service tests.

Run:

~~~powershell
.\\mvnw.cmd -Duser.home="D:\\Users\\Lucas C\\Documentos\\codigos\\watchwise-api" -Dmaven.repo.local="C:\\Users\\Lucas C\\.m2\\repository" test -Dtest=SocialNotificationServiceImplTest
~~~

Expected: all aggregation, replacement, message, and self-action tests pass.

- [ ] Step 5: Commit the writer.

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/notification/service src/test/java/com/watchwise/watchwise_api/notification/service/impl/SocialNotificationServiceImplTest.java
git commit -m "feat(notification): aggregate social interactions"
~~~

After committing, confirm explicitly that the commit has no Co-Authored-By trailer or other self-attribution.

---

### Task 3: Emit notifications from Like flows

**Files:**
- Modify: src/main/java/com/watchwise/watchwise_api/like/service/impl/LikeServiceImpl.java
- Modify: src/main/java/com/watchwise/watchwise_api/comment/repository/CommentRepository.java
- Test: src/test/java/com/watchwise/watchwise_api/like/service/impl/LikeServiceImplTest.java

**Interfaces:**
- Consumes SocialNotificationService from Task 2.
- Produces one social notification only after the Like insert and target counter update succeed.

- [ ] Step 1: Add failing Like service tests.

Inject a mocked SocialNotificationService and assert these mappings:

| Like method | Target type | Recipient |
|---|---|---|
| likeComment | COMMENT | comment.user.id |
| likeDiaryEntry | DIARY_ENTRY | diaryEntry.user.id |
| likeDroppedEntry | DROPPED_ENTRY | droppedEntry.user.id |
| likeList | USER_LIST | list.user.id |
| likePick | PICK | pick.user.id |
| likePicksTemplate | PICKS_TEMPLATE | template.creator.id, when non-null |

Assert that duplicate Like calls, failed visibility checks, and unlike calls never invoke the writer. Assert that a template with null creator completes without a social notification.

- [ ] Step 2: Make the comment target query fetch the author.

Change CommentRepository.findByIdWithTargets from an unfetched root user to JOIN FETCH c.user, while keeping all existing target joins. This prevents the Like flow from depending on lazy loading after the visibility check when it resolves the comment owner.

- [ ] Step 3: Wire each successful Like transaction.

Add SocialNotificationService to LikeServiceImpl. In each newTransactionExecutor.runInNewTransaction lambda, call the writer after saveAndFlush and the likes-count update, using the target owner id resolved from the already loaded target. Do not call it in the early idempotent return or in the DataIntegrityViolationException recovery path.

Keep the writer call inside the same physical transaction so a notification cannot survive a rolled-back Like. Do not add notification behavior to the six unlike methods.

- [ ] Step 4: Run Like unit tests.

Run:

~~~powershell
.\\mvnw.cmd -Duser.home="D:\\Users\\Lucas C\\Documentos\\codigos\\watchwise-api" -Dmaven.repo.local="C:\\Users\\Lucas C\\.m2\\repository" test -Dtest=LikeServiceImplTest
~~~

Expected: existing idempotency/visibility tests and the new six-target notification tests pass.

- [ ] Step 5: Commit the Like integration.

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/like src/main/java/com/watchwise/watchwise_api/comment/repository/CommentRepository.java src/test/java/com/watchwise/watchwise_api/like/service/impl/LikeServiceImplTest.java
git commit -m "feat(notification): notify on received likes"
~~~

After committing, confirm explicitly that the commit has no Co-Authored-By trailer or other self-attribution.

---

### Task 4: Emit notifications from Comment flows

**Files:**
- Modify: src/main/java/com/watchwise/watchwise_api/comment/service/impl/CommentServiceImpl.java
- Test: src/test/java/com/watchwise/watchwise_api/comment/service/impl/CommentServiceImplTest.java

**Interfaces:**
- Consumes SocialNotificationService from Task 2.
- Produces one social notification after each owner-bearing Comment is persisted.

- [ ] Step 1: Add failing Comment service tests.

Inject a mocked SocialNotificationService and assert:

| Comment method | Target type | Recipient |
|---|---|---|
| createCommentOnContent | none | no writer call |
| createCommentOnList | USER_LIST | list.user.id |
| createCommentOnDiaryEntry | DIARY_ENTRY | diaryEntry.user.id |
| createCommentOnDroppedEntry | DROPPED_ENTRY | droppedEntry.user.id |
| createCommentOnPick | PICK | pick.user.id |
| createCommentOnPicksTemplate | PICKS_TEMPLATE | template.creator.id, when non-null |

Assert that comments created by the target owner result in no writer call, a template with null creator results in no writer call, and invalid visibility/target validation prevents persistence and notification.

- [ ] Step 2: Wire owner-bearing comment creation.

Add SocialNotificationService to CommentServiceImpl. Save the comment first, then invoke notifyCommentReceived with the authenticated user id, target owner id, target type, and target id. Keep the call in each existing @Transactional method. Do not notify on content comments and do not add a separate parent-comment notification.

- [ ] Step 3: Run Comment unit tests.

Run:

~~~powershell
.\\mvnw.cmd -Duser.home="D:\\Users\\Lucas C\\Documentos\\codigos\\watchwise-api" -Dmaven.repo.local="C:\\Users\\Lucas C\\.m2\\repository" test -Dtest=CommentServiceImplTest
~~~

Expected: existing target validation, visibility, parent-comment, and deletion tests pass with the new notification assertions.

- [ ] Step 4: Commit the Comment integration.

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/comment/service/impl/CommentServiceImpl.java src/test/java/com/watchwise/watchwise_api/comment/service/impl/CommentServiceImplTest.java
git commit -m "feat(notification): notify on received comments"
~~~

After committing, confirm explicitly that the commit has no Co-Authored-By trailer or other self-attribution.

---

### Task 5: Implement retention cleanup and scheduling

**Files:**
- Create: src/main/java/com/watchwise/watchwise_api/notification/service/NotificationCleanupService.java
- Create: src/main/java/com/watchwise/watchwise_api/notification/service/impl/NotificationCleanupServiceImpl.java
- Create: src/main/java/com/watchwise/watchwise_api/notification/tracking/NotificationCleanupJob.java
- Modify: src/main/java/com/watchwise/watchwise_api/notification/repository/NotificationRepository.java
- Modify: src/main/resources/application-dev.properties
- Modify: src/main/resources/application-prod.properties
- Test: src/test/java/com/watchwise/watchwise_api/notification/service/impl/NotificationCleanupServiceImplTest.java
- Test: src/test/java/com/watchwise/watchwise_api/notification/tracking/NotificationCleanupJobTest.java
- Test: src/test/java/com/watchwise/watchwise_api/notification/repository/NotificationRepositoryTest.java

**Interfaces:**
- Produces NotificationCleanupService.cleanupExpiredSocialNotifications() returning the number of deleted rows.
- The scheduled job delegates only to that service and has no database logic.

- [ ] Step 1: Add failing cleanup service tests.

Use a fixed Clock and verify these strict cutoffs based on one now:

~~~text
read row updatedAt = now - 7 days - 1 second -> delete batch requested
read row updatedAt = now - 7 days -> retain
unread row updatedAt = now - 30 days - 1 second -> delete batch requested
unread row updatedAt = now - 30 days -> retain
~~~

Verify that the service repeats the repository batch call while it returns BATCH_SIZE, sums the returned counts, and runs read and unread cleanup separately. Verify the job calls the service once.

- [ ] Step 2: Add the bounded native delete query.

Add a repository method with a fixed target-type predicate and an ordered CTE limit:

~~~sql
WITH expired AS (
    SELECT id
    FROM notifications
    WHERE type IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED')
      AND is_read = :isRead
      AND updated_at < :cutoff
    ORDER BY updated_at ASC, id ASC
    LIMIT :batchSize
)
DELETE FROM notifications n
USING expired
WHERE n.id = expired.id
~~~

Annotate the repository method with @Modifying and @Transactional(propagation = Propagation.REQUIRES_NEW) so each batch commits separately. Keep existing notification read queries and system rows untouched.

- [ ] Step 3: Implement the service and job.

Use a fixed batch size of 500, calculate now.minusDays(7) for read rows and now.minusDays(30) for unread rows, and loop until a batch returns fewer than 500. Inject the existing Clock, convert its instant to LocalDateTime in ZoneId.systemDefault(), and return the total deleted count. Add @Scheduled(cron = "\${app.notification.cleanup.cron}") to the job.

- [ ] Step 4: Add daily configuration.

Add this property to both active profiles:

~~~properties
app.notification.cleanup.cron=0 30 3 * * *
~~~

Do not enable a second cleanup scheduler or change existing cleanup schedules.

- [ ] Step 5: Run retention and repository tests.

Run:

~~~powershell
.\\mvnw.cmd -Duser.home="D:\\Users\\Lucas C\\Documentos\\codigos\\watchwise-api" -Dmaven.repo.local="C:\\Users\\Lucas C\\.m2\\repository" test -Dtest=NotificationCleanupServiceImplTest,NotificationCleanupJobTest,NotificationRepositoryTest
~~~

Expected: strict boundary behavior, batching, and system-notification isolation pass against the existing repository test setup.

- [ ] Step 6: Commit retention.

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/notification src/test/java/com/watchwise/watchwise_api/notification src/main/resources/application-dev.properties src/main/resources/application-prod.properties
git commit -m "feat(notification): clean expired social notifications"
~~~

After committing, confirm explicitly that the commit has no Co-Authored-By trailer or other self-attribution.

---

### Task 6: Verify the HTTP contract and synchronize project documentation

**Files:**
- Modify: src/test/java/com/watchwise/watchwise_api/notification/controller/NotificationControllerIntegrationTest.java
- Create: src/test/java/com/watchwise/watchwise_api/notification/controller/SocialNotificationIntegrationTest.java
- Modify: docs/context/openapi.yaml
- Modify: docs/context/database-schema.md
- Modify: docs/context/database-schema.html
- Modify: docs/context/business-rules.md
- Modify: docs/context/progress.md

**Interfaces:**
- Verifies the public GET /notifications response without changing routes.
- Documents the exact new enum values and social response fields.

- [ ] Step 1: Add failing integration coverage.

Use the existing MockMvc cookie/CSRF helpers and PostgreSQL Testcontainers fixture to create an owner and three actors, a persisted diary review, and Likes through the real controller. Assert:

1. first Like creates one LIKE_RECEIVED row with count 1 and latest actor;
2. second Like before reading updates the same row to count 2 and the plural message;
3. owner marks it read and a third Like produces a different notification id with count 1 and unread state;
4. a comment target follows the same aggregate behavior;
5. the owner does not receive a notification for a self-Like or self-Comment;
6. system RELEASE notifications still return their content and null social fields;
7. notification results remain isolated by recipient.

Add persistence cases for read/unread retention boundaries and confirm cleanup does not remove a RELEASE row.

- [ ] Step 2: Implement only test fixtures needed by the contract.

Reuse existing repositories and authentication helpers. Build the minimum Content, DiaryEntry, Comment, and Like rows needed for each test. Avoid adding a new endpoint or DTO solely for test setup.

- [ ] Step 3: Update OpenAPI.

Extend Notification.type with LIKE_RECEIVED and COMMENT_RECEIVED. Mark content nullable, add latestActor using UserPreview, add nullable targetType with the six social enum values, add nullable targetId, and add interactionCount. Document that system notifications continue using content and social notifications use the target pair.

- [ ] Step 4: Update schema and business-rule documentation.

Document the new nullable columns, social check constraint, partial unique aggregate index, latest actor relationship, and retention index in both database schema representations. Add a Notification business-rules section covering target owner resolution, self-action suppression, grouping while unread, deletion/recreation after a read cycle, no notification for ownerless Content/comments or official templates, and 7/30-day cleanup. Keep entries pointing to the implementing classes and methods.

- [ ] Step 5: Append the shipped progress entry.

Update the existing 2026-09-29 section in chronological progress.md with only behavior that landed: social notification types and targets, unread aggregation, read-cycle replacement, response metadata, scheduled retention, and tests. Do not add a next-steps section.

- [ ] Step 6: Run focused integration tests.

Run:

~~~powershell
.\\mvnw.cmd -Duser.home="D:\\Users\\Lucas C\\Documentos\\codigos\\watchwise-api" -Dmaven.repo.local="C:\\Users\\Lucas C\\.m2\\repository" test -Dtest=NotificationControllerIntegrationTest,SocialNotificationIntegrationTest,LikeControllerIntegrationTest,CommentControllerIntegrationTest
~~~

Expected: HTTP response fields, authentication/CSRF behavior, Like/Comment side effects, and existing notification behavior pass. If Testcontainers cannot start, report that limitation without claiming the integration suite passed.

- [ ] Step 7: Commit code-only integration changes.

~~~powershell
git add src/main/java src/main/resources src/test/java
git commit -m "test(notification): cover social notification flow"
~~~

Leave docs/ changes uncommitted under the repository convention. Confirm explicitly that the commit has no Co-Authored-By trailer or other self-attribution.

---

## Final verification

- [ ] Run the focused notification/Like/Comment suite after all tasks.
- [ ] Run the full Maven test suite with the repository-approved wrapper command.
- [ ] Inspect git diff --check and git status --short.
- [ ] Confirm the only pre-existing unrelated worktree change remains .gitignore.
- [ ] Confirm no Spring default error-body regression was introduced by the new scheduled or notification paths.
- [ ] Review the five recurring bug categories: exception mapping, concurrency, cross-feature side effects, DTO validation, and sibling endpoint coverage.
- [ ] Before claiming completion, report actual test output and any Docker/Testcontainers limitation.
