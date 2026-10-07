package com.watchwise.watchwise_api.dropped.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DroppedEntryRepository extends JpaRepository<DroppedEntry, UUID> {

    // --- Feed (GET /feed) ---

    @Query("""
            SELECT d FROM DroppedEntry d JOIN FETCH d.content JOIN FETCH d.user
            WHERE d.user.id IN :userIds
            AND (
                CAST(:cursorCreatedAt AS timestamp) IS NULL
                OR d.createdAt < :cursorCreatedAt
                OR (d.createdAt = :cursorCreatedAt AND :cursorId IS NOT NULL AND d.id < :cursorId)
            )
            ORDER BY d.createdAt DESC, d.id DESC
            """)
    List<DroppedEntry> findFeedCandidates(
            @Param("userIds") Collection<UUID> userIds,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    Optional<DroppedEntry> findByUserIdAndTypeAndContentId(UUID userId, ContentType type, UUID contentId);

    @Query("""
            SELECT d FROM DroppedEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId AND d.content.id IN :contentIds
            AND d.type = d.content.type
            """)
    List<DroppedEntry> findByUserIdAndContentIdInWithContent(
            @Param("userId") UUID userId, @Param("contentIds") Collection<UUID> contentIds);

    @Query("SELECT d FROM DroppedEntry d JOIN FETCH d.user JOIN FETCH d.content WHERE d.id = :id")
    Optional<DroppedEntry> findByIdWithUserAndContent(@Param("id") UUID id);

    @Query("SELECT d FROM DroppedEntry d JOIN FETCH d.user JOIN FETCH d.content WHERE d.id IN :ids")
    List<DroppedEntry> findByIdInWithUserAndContent(@Param("ids") Collection<UUID> ids);

    @Modifying
    @Query("UPDATE DroppedEntry d SET d.likesCount = d.likesCount + 1 WHERE d.id = :id")
    void incrementLikesCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE DroppedEntry d SET d.likesCount = d.likesCount - 1 WHERE d.id = :id AND d.likesCount > 0")
    void decrementLikesCount(@Param("id") UUID id);

    boolean existsByUserIdAndTypeAndContentId(UUID userId, ContentType type, UUID contentId);

    @Query("""
            SELECT d FROM DroppedEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId AND d.type = :type
            ORDER BY d.createdAt DESC, d.id DESC
            """)
    Page<DroppedEntry> findByUserIdAndTypeOrderByCreatedAtDesc(
            @Param("userId") UUID userId, @Param("type") ContentType type, Pageable pageable);

    @Query("""
            SELECT d FROM DroppedEntry d JOIN FETCH d.content JOIN FETCH d.user u
            WHERE d.content.id = :contentId
            AND d.comment IS NOT NULL
            AND (u.isProfilePublic = true
                 OR u.id = :viewerId
                 OR EXISTS (
                     SELECT 1 FROM Follower f
                     WHERE f.follower.id = :viewerId AND f.followed.id = u.id
                     AND f.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED
                 ))
            ORDER BY d.createdAt DESC, d.id DESC
            """)
    Page<DroppedEntry> findReviewsByContentId(
            @Param("contentId") UUID contentId, @Param("viewerId") UUID viewerId, Pageable pageable);

}
