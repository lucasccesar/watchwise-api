package com.watchwise.watchwise_api.watchlist.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatchlistEntryRepository extends JpaRepository<WatchlistEntry, UUID> {

    List<WatchlistEntry> findByUserIdAndTypeOrderByPositionAsc(UUID userId, ContentType type);

    Page<WatchlistEntry> findByUserIdAndTypeOrderByPositionAsc(UUID userId, ContentType type, Pageable pageable);

    Optional<WatchlistEntry> findByUserIdAndTypeAndContentId(UUID userId, ContentType type, UUID contentId);

}