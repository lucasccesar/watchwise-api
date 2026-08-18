package com.watchwise.watchwise_api.dropped.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DroppedEntryRepository extends JpaRepository<DroppedEntry, UUID> {

    Optional<DroppedEntry> findByUserIdAndTypeAndContentId(UUID userId, ContentType type, UUID contentId);

    boolean existsByUserIdAndTypeAndContentId(UUID userId, ContentType type, UUID contentId);

    Page<DroppedEntry> findByUserIdAndTypeOrderByCreatedAtDesc(UUID userId, ContentType type, Pageable pageable);

}