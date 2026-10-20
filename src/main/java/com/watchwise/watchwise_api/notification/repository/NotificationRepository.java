package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Modifying
    @Query(value = """
            DELETE FROM notifications n
            WHERE (n.user_id = :firstUserId AND n.actor_user_id = :secondUserId)
               OR (n.user_id = :secondUserId AND n.actor_user_id = :firstUserId)
               OR EXISTS (
                    SELECT 1 FROM notification_actors a
                    WHERE a.notification_id = n.id
                      AND ((n.user_id = :firstUserId AND a.actor_user_id = :secondUserId)
                        OR (n.user_id = :secondUserId AND a.actor_user_id = :firstUserId))
               )
               OR (n.user_id = :firstUserId AND n.type IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED')
                   AND n.interaction_count > 1
                   AND NOT EXISTS (SELECT 1 FROM notification_actors a WHERE a.notification_id = n.id))
               OR (n.user_id = :secondUserId AND n.type IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED')
                   AND n.interaction_count > 1
                   AND NOT EXISTS (SELECT 1 FROM notification_actors a WHERE a.notification_id = n.id))
            """, nativeQuery = true)
    int deleteBetweenUsers(@Param("firstUserId") UUID firstUserId, @Param("secondUserId") UUID secondUserId);

    boolean existsByUserIdAndIsReadFalse(UUID userId);

    @Query(value = """
            SELECT n FROM Notification n LEFT JOIN FETCH n.content LEFT JOIN FETCH n.latestActor
            WHERE n.user.id = :userId
              AND (n.latestActor IS NULL OR NOT EXISTS (SELECT b.id FROM UserBlock b WHERE
                    (b.blocker.id = :userId AND b.blocked.id = n.latestActor.id)
                    OR (b.blocker.id = n.latestActor.id AND b.blocked.id = :userId)))
            ORDER BY n.createdAt DESC, n.id DESC
            """,
            countQuery = """
                    SELECT COUNT(n) FROM Notification n
                    WHERE n.user.id = :userId
                      AND (n.latestActor IS NULL OR NOT EXISTS (SELECT b.id FROM UserBlock b WHERE
                            (b.blocker.id = :userId AND b.blocked.id = n.latestActor.id)
                            OR (b.blocker.id = n.latestActor.id AND b.blocked.id = :userId)))
                    """)
    Page<Notification> findByUserIdOrderByCreatedAtDesc(@Param("userId") UUID userId, Pageable pageable);

    @Query(value = """
            SELECT n FROM Notification n LEFT JOIN FETCH n.content LEFT JOIN FETCH n.latestActor
            WHERE n.user.id = :userId AND n.isRead = :isRead
              AND (n.latestActor IS NULL OR NOT EXISTS (SELECT b.id FROM UserBlock b WHERE
                    (b.blocker.id = :userId AND b.blocked.id = n.latestActor.id)
                    OR (b.blocker.id = n.latestActor.id AND b.blocked.id = :userId)))
            ORDER BY n.createdAt DESC, n.id DESC
            """,
            countQuery = """
                    SELECT COUNT(n) FROM Notification n
                    WHERE n.user.id = :userId AND n.isRead = :isRead
                      AND (n.latestActor IS NULL OR NOT EXISTS (SELECT b.id FROM UserBlock b WHERE
                            (b.blocker.id = :userId AND b.blocked.id = n.latestActor.id)
                            OR (b.blocker.id = n.latestActor.id AND b.blocked.id = :userId)))
                    """)
    Page<Notification> findByUserIdAndIsReadOrderByCreatedAtDesc(
            @Param("userId") UUID userId, @Param("isRead") boolean isRead, Pageable pageable);

    @Query("""
            SELECT COUNT(n) AS totalNotifications,
                   COALESCE(SUM(CASE WHEN n.isRead = false THEN 1 ELSE 0 END), 0) AS unreadNotifications
            FROM Notification n
            WHERE n.user.id = :userId
              AND (n.latestActor IS NULL OR NOT EXISTS (SELECT b.id FROM UserBlock b WHERE
                    (b.blocker.id = :userId AND b.blocked.id = n.latestActor.id)
                    OR (b.blocker.id = n.latestActor.id AND b.blocked.id = :userId)))
            """)
    NotificationCountsProjection findCountsByUserId(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM Notification n WHERE n.id = :id")
    Optional<Notification> findByIdForUpdate(@Param("id") UUID id);

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

    @Modifying
    @Query(value = """
            INSERT INTO notification_actors (notification_id, actor_user_id)
            VALUES (:notificationId, :actorId)
            ON CONFLICT (notification_id, actor_user_id) DO NOTHING
            """, nativeQuery = true)
    int insertSocialAggregateActorIfAbsent(
            @Param("notificationId") UUID notificationId,
            @Param("actorId") UUID actorId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM notification_actors WHERE notification_id = :notificationId)",
            nativeQuery = true)
    boolean hasSocialAggregateActors(@Param("notificationId") UUID notificationId);

    @Query(value = "SELECT COUNT(*) FROM notification_actors WHERE notification_id = :notificationId",
            nativeQuery = true)
    long countSocialAggregateActors(@Param("notificationId") UUID notificationId);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            WITH expired AS (
                SELECT id
                FROM notifications
                WHERE type IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED',
                               'FOLLOW_REQUEST', 'NEW_FOLLOWER', 'FOLLOW_REQUEST_ACCEPTED')
                  AND is_read = :isRead
                  AND updated_at < :cutoff
                ORDER BY updated_at ASC, id ASC
                LIMIT :batchSize
            )
            DELETE FROM notifications n
            USING expired
            WHERE n.id = expired.id
            """, nativeQuery = true)
    int deleteExpiredSocialNotifications(
            @Param("isRead") boolean isRead,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("batchSize") int batchSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM Notification n WHERE n.user.id = :recipientId AND n.type = :type "
            + "AND n.targetType = :targetType AND n.targetId = :targetId")
    Optional<Notification> findSocialAggregateForUpdate(
            @Param("recipientId") UUID recipientId,
            @Param("type") NotificationType type,
            @Param("targetType") NotificationTargetType targetType,
            @Param("targetId") UUID targetId);

}
