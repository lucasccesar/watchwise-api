package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.diaryentry.entity.ImportedWatchAssertion;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ImportedWatchAssertionRepository extends JpaRepository<ImportedWatchAssertion, UUID> {

    @EntityGraph(attributePaths = "content")
    @Query("""
            SELECT assertion FROM ImportedWatchAssertion assertion
            WHERE assertion.user.id = :userId
              AND ((assertion.content.type = com.watchwise.watchwise_api.content.entity.ContentType.SERIES
                    AND assertion.content.tmdbId = :seriesTmdbId)
                OR (assertion.content.type IN (
                        com.watchwise.watchwise_api.content.entity.ContentType.SEASON,
                        com.watchwise.watchwise_api.content.entity.ContentType.EPISODE)
                    AND assertion.content.seriesTmdbId = :seriesTmdbId))
            """)
    java.util.List<ImportedWatchAssertion> findByUserIdAndSeriesTmdbId(
            @Param("userId") UUID userId,
            @Param("seriesTmdbId") String seriesTmdbId);

    boolean existsByUserIdAndContentId(UUID userId, UUID contentId);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM imported_watch_assertions a
                JOIN contents c ON c.id = a.content_id
                WHERE a.user_id = :userId AND a.coverage = 'UNKNOWN'
                  AND c.type = :type AND c.tmdb_id = :tmdbId
            ) OR (
                :type = 'SERIES' AND EXISTS (
                    SELECT 1
                    FROM imported_watch_assertions a
                    JOIN contents c ON c.id = a.content_id
                    WHERE a.user_id = :userId AND a.coverage = 'UNKNOWN'
                      AND c.type IN ('SEASON', 'EPISODE')
                      AND c.series_tmdb_id = :tmdbId
                )
            )
            """, nativeQuery = true)
    boolean existsUnknownWatchEvidenceForWatchlist(
            @Param("userId") UUID userId,
            @Param("type") String type,
            @Param("tmdbId") String tmdbId);
}
