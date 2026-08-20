package com.watchwise.watchwise_api.watchlist.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatchlistEntryRepository extends JpaRepository<WatchlistEntry, UUID> {

    long countByUserIdAndType(UUID userId, ContentType type);

    List<WatchlistEntry> findByUserIdAndTypeOrderByPositionAsc(UUID userId, ContentType type);

    @Query("""
            SELECT w FROM WatchlistEntry w JOIN FETCH w.content
            WHERE w.user.id = :userId AND w.type = :type
            ORDER BY w.position ASC
            """)
    Page<WatchlistEntry> findByUserIdAndTypeOrderByPositionAsc(
            @Param("userId") UUID userId, @Param("type") ContentType type, Pageable pageable);

    Optional<WatchlistEntry> findByUserIdAndTypeAndContentId(UUID userId, ContentType type, UUID contentId);

    @Modifying
    @Query("""
            UPDATE WatchlistEntry w SET w.position = w.position + :offset
            WHERE w.user.id = :userId AND w.type = :type
            AND w.position >= :rangeStart AND w.position <= :rangeEnd
            """)
    void parkPositionsInRange(
            @Param("userId") UUID userId, @Param("type") ContentType type,
            @Param("rangeStart") int rangeStart, @Param("rangeEnd") int rangeEnd, @Param("offset") int offset);

    @Modifying
    @Query("""
            UPDATE WatchlistEntry w SET w.position = w.position - :offset + :delta
            WHERE w.user.id = :userId AND w.type = :type
            AND w.position > :offset
            """)
    void settleParkedPositions(
            @Param("userId") UUID userId, @Param("type") ContentType type,
            @Param("offset") int offset, @Param("delta") int delta);

}