package com.watchwise.watchwise_api.dropped.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
                OR (d.createdAt = :cursorCreatedAt AND (:cursorId IS NULL OR d.id < :cursorId))
            )
            ORDER BY d.createdAt DESC, d.id DESC
            """)
    List<DroppedEntry> findFeedCandidates(
            @Param("userIds") Collection<UUID> userIds,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    Optional<DroppedEntry> findByUserIdAndTypeAndContentId(UUID userId, ContentType type, UUID contentId);

    boolean existsByUserIdAndTypeAndContentId(UUID userId, ContentType type, UUID contentId);

    @Query("""
            SELECT d FROM DroppedEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId AND d.type = :type
            ORDER BY d.createdAt DESC
            """)
    Page<DroppedEntry> findByUserIdAndTypeOrderByCreatedAtDesc(
            @Param("userId") UUID userId, @Param("type") ContentType type, Pageable pageable);

}