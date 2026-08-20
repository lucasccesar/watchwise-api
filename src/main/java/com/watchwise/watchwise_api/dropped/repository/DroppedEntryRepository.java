package com.watchwise.watchwise_api.dropped.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DroppedEntryRepository extends JpaRepository<DroppedEntry, UUID> {

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