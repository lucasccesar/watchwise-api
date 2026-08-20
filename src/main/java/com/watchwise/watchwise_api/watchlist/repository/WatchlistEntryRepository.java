package com.watchwise.watchwise_api.watchlist.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatchlistEntryRepository extends JpaRepository<WatchlistEntry, UUID> {

    List<WatchlistEntry> findByUserIdAndTypeOrderByPositionAsc(UUID userId, ContentType type);

    @Query("""
            SELECT w FROM WatchlistEntry w JOIN FETCH w.content
            WHERE w.user.id = :userId AND w.type = :type
            ORDER BY w.position ASC
            """)
    Page<WatchlistEntry> findByUserIdAndTypeOrderByPositionAsc(
            @Param("userId") UUID userId, @Param("type") ContentType type, Pageable pageable);

    Optional<WatchlistEntry> findByUserIdAndTypeAndContentId(UUID userId, ContentType type, UUID contentId);

}