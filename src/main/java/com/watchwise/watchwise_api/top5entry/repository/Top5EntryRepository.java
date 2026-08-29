package com.watchwise.watchwise_api.top5entry.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface Top5EntryRepository extends JpaRepository<Top5Entry, UUID> {

    // --- Feed (GET /feed) ---

    @Query("""
            SELECT t FROM Top5Entry t JOIN FETCH t.user
            WHERE t.user.id IN :userIds
            AND (
                CAST(:cursorCreatedAt AS timestamp) IS NULL
                OR t.createdAt < :cursorCreatedAt
                OR (t.createdAt = :cursorCreatedAt AND (:cursorId IS NULL OR t.id < :cursorId))
            )
            ORDER BY t.createdAt DESC, t.id DESC
            """)
    List<Top5Entry> findFeedCandidates(
            @Param("userIds") Collection<UUID> userIds,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    List<Top5Entry> findByUserIdAndTypeOrderByPositionAsc(UUID userId, ContentType type);

    @Query("""
            SELECT t FROM Top5Entry t JOIN FETCH t.content
            WHERE t.user.id = :userId AND t.type = :type
            ORDER BY t.position ASC
            """)
    List<Top5Entry> findByUserIdAndTypeWithContentOrderByPositionAsc(
            @Param("userId") UUID userId, @Param("type") ContentType type);

}
