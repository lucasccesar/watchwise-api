package com.watchwise.watchwise_api.watchlist.repository;

import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.content.entity.Content;
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

    // --- Content tracking job (daily TMDB change detection) ---

    @Query("SELECT DISTINCT w.content FROM WatchlistEntry w")
    List<Content> findDistinctTrackedContent();

    @Query("SELECT DISTINCT w.user.id FROM WatchlistEntry w WHERE w.content.id = :contentId")
    List<UUID> findUserIdsByContentId(@Param("contentId") UUID contentId);

    default List<CalendarScheduleKey> findActiveCalendarScheduleKeys() {
        return findActiveCalendarScheduleKeyRows().stream()
                .map(row -> new CalendarScheduleKey(
                        ContentType.valueOf(row.getType()),
                        row.getTmdbId(),
                        row.getPreferredLanguage(),
                        row.getPreferredRegion()))
                .toList();
    }

    @Query(value = """
            SELECT DISTINCT w.type AS type,
                            c.tmdb_id AS "tmdbId",
                            u.preferred_language AS "preferredLanguage",
                            u.preferred_region AS "preferredRegion"
            FROM watchlist_entries w
            JOIN contents c ON c.id = w.content_id
            JOIN users u ON u.id = w.user_id
            WHERE w.type IN ('MOVIE', 'SERIES')
            AND c.type = w.type
            UNION
            SELECT DISTINCT 'SERIES' AS type,
                            episode.series_tmdb_id AS "tmdbId",
                            u.preferred_language AS "preferredLanguage",
                            u.preferred_region AS "preferredRegion"
            FROM diary_entries d
            JOIN contents episode ON episode.id = d.content_id
            JOIN users u ON u.id = d.user_id
            WHERE episode.type = 'EPISODE'
            AND NOT EXISTS (
                SELECT 1
                FROM diary_entries completion
                JOIN contents completed_series ON completed_series.id = completion.content_id
                WHERE completion.user_id = d.user_id
                AND completed_series.type = 'SERIES'
                AND completed_series.tmdb_id = episode.series_tmdb_id
            )
            """, nativeQuery = true)
    List<CalendarScheduleKeyRow> findActiveCalendarScheduleKeyRows();

    interface CalendarScheduleKeyRow {
        String getType();
        String getTmdbId();
        String getPreferredLanguage();
        String getPreferredRegion();
    }

}
