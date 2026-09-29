package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @EntityGraph(attributePaths = {"content", "latestActor"})
    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = {"content", "latestActor"})
    Page<Notification> findByUserIdAndIsReadOrderByCreatedAtDesc(UUID userId, boolean isRead, Pageable pageable);

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM Notification n WHERE n.user.id = :recipientId AND n.type = :type "
            + "AND n.targetType = :targetType AND n.targetId = :targetId")
    Optional<Notification> findSocialAggregateForUpdate(
            @Param("recipientId") UUID recipientId,
            @Param("type") NotificationType type,
            @Param("targetType") NotificationTargetType targetType,
            @Param("targetId") UUID targetId);

}
