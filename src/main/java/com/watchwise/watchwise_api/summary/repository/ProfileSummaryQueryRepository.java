package com.watchwise.watchwise_api.summary.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Read-only queries that compose more than the DiaryEntry aggregate.
 *
 * <p>Keeping these projections outside DiaryEntryRepository prevents the write
 * domain repository from becoming the owner of Profile analytics.</p>
 */
@org.springframework.stereotype.Repository
public interface ProfileSummaryQueryRepository extends Repository<DiaryEntry, UUID> {

    @Query("""
            SELECT COALESCE(SUM(d.content.runtimeMinutes), 0) FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            """)
    long sumRuntimeMinutesByUserIdAndContentType(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType);

    @Query("""
            SELECT COALESCE(SUM(d.content.runtimeMinutes), 0) FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            AND d.watchedDate BETWEEN :start AND :end
            """)
    long sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType,
            @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("""
            SELECT COUNT(d) FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            """)
    long countByUserIdAndContentType(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType);

    @Query("""
            SELECT COUNT(d) FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            AND d.watchedDate BETWEEN :start AND :end
            """)
    long countByUserIdAndContentTypeAndWatchedDateBetween(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType,
            @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("""
            SELECT d FROM DiaryEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId
            AND d.content.type = com.watchwise.watchwise_api.content.entity.ContentType.EPISODE
            ORDER BY d.createdAt DESC, d.id DESC
            """)
    List<DiaryEntry> findRecentEpisodes(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
            SELECT d FROM DiaryEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId
            AND d.content.type IN :contentTypes
            AND d.comment IS NOT NULL
            AND TRIM(d.comment) <> ''
            ORDER BY d.createdAt DESC, d.id DESC
            """)
    List<DiaryEntry> findRecentReviews(
            @Param("userId") UUID userId,
            @Param("contentTypes") Collection<ContentType> contentTypes,
            Pageable pageable);

    @Query("""
            SELECT d FROM DiaryEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            ORDER BY d.createdAt DESC, d.id DESC
            """)
    List<DiaryEntry> findRecentTopLevelEntries(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType, Pageable pageable);

    @Query(value = """
            SELECT p.id AS projectionId,
                   p.content_id AS contentId,
                   p.watch_number AS watchNumber,
                   p.ordered_at AS orderedAt,
                   rating.score AS score,
                   review.comment AS comment,
                   COUNT(*) OVER (PARTITION BY p.content_id) AS timesWatched
            FROM diary_completion_projections p
            JOIN contents c ON c.id = p.content_id AND c.type = 'SERIES'
            JOIN users owner ON owner.id = p.user_id
            JOIN user_series_history_states state
              ON state.user_id = p.user_id AND state.series_tmdb_id = c.tmdb_id
            JOIN calendar_schedule_completeness marker
              ON marker.series_tmdb_id = c.tmdb_id
             AND marker.group_type = 'SERIES'
             AND marker.season_number IS NULL
             AND marker.region = owner.preferred_region
             AND marker.language = owner.preferred_language
             AND marker.scope_version = p.schedule_version
             AND marker.complete = TRUE
            LEFT JOIN content_ratings rating
              ON rating.user_id = p.user_id AND rating.content_id = p.content_id
            LEFT JOIN LATERAL (
                SELECT candidate.comment
                FROM content_reviews candidate
                WHERE candidate.user_id = p.user_id
                  AND candidate.content_id = p.content_id
                  AND candidate.watch_number = p.watch_number
                  AND candidate.publication_kind = 'WRITTEN_REVIEW'
                  AND candidate.comment IS NOT NULL
                  AND btrim(candidate.comment) <> ''
                ORDER BY candidate.created_at DESC, candidate.id DESC
                LIMIT 1
            ) review ON TRUE
            WHERE p.user_id = :userId
              AND p.content_type = 'SERIES'
              AND p.history_revision = state.history_revision
              AND state.computed_revision = state.history_revision
              AND p.schedule_version = state.schedule_version
              AND p.valid_until > CURRENT_TIMESTAMP
              AND state.valid_until > CURRENT_TIMESTAMP
            ORDER BY p.ordered_at DESC, p.id DESC
            """, nativeQuery = true)
    List<SeriesCompletionActivity> findRecentSeriesCompletionActivities(
            @Param("userId") UUID userId, Pageable pageable);

    interface SeriesCompletionActivity {
        UUID getProjectionId();
        UUID getContentId();
        Integer getWatchNumber();
        java.time.Instant getOrderedAt();
        Integer getScore();
        String getComment();
        Long getTimesWatched();
    }

    @Query(value = """
            SELECT genre AS genre, COUNT(DISTINCT c.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            CROSS JOIN LATERAL unnest(c.genres) AS genre
            WHERE d.user_id = :userId
              AND c.type = 'MOVIE'
            GROUP BY genre
            ORDER BY count DESC, genre ASC
            """, nativeQuery = true)
    List<GenreCount> countDistinctMoviesByGenre(@Param("userId") UUID userId);

    @Query(value = """
            SELECT genre AS genre,
                   COUNT(DISTINCT (c.series_tmdb_id, c.season_number, c.episode_number)) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            JOIN contents sc ON sc.tmdb_id = c.series_tmdb_id AND sc.type = 'SERIES'
            CROSS JOIN LATERAL unnest(sc.genres) AS genre
            WHERE d.user_id = :userId
              AND c.type = 'EPISODE'
            GROUP BY genre
            ORDER BY count DESC, genre ASC
            """, nativeQuery = true)
    List<GenreCount> countDistinctEpisodesByGenre(@Param("userId") UUID userId);

    @Query(value = """
            WITH selected_scores AS (
                SELECT r.score
                FROM content_ratings r
                WHERE r.user_id = :userId
                  AND r.content_type = :contentType
                  AND :contentType IN ('SERIES', 'SEASON')
                UNION ALL
                SELECT ranked.score
                FROM (
                    SELECT c.id AS content_id,
                           d.score,
                           ROW_NUMBER() OVER (
                               PARTITION BY c.id
                               ORDER BY d.watch_number DESC, d.created_at DESC, d.id DESC
                           ) AS row_number
                    FROM diary_entries d
                    JOIN contents c ON c.id = d.content_id
                    WHERE d.user_id = :userId
                      AND d.watch_number > 0
                      AND c.type = :contentType
                ) ranked
                WHERE ranked.row_number = 1
                  AND :contentType NOT IN ('SERIES', 'SEASON')
            )
            SELECT score AS score, COUNT(*) AS count
            FROM selected_scores
            WHERE score IS NOT NULL
            GROUP BY score
            ORDER BY score
            """, nativeQuery = true)
    List<ScoreCount> countLatestScoresByUserIdAndContentType(
            @Param("userId") UUID userId, @Param("contentType") String contentType);

    @Query(value = """
            SELECT ranked.content_id AS contentId, COUNT(*) AS count
            FROM (
                SELECT p.content_id
                FROM diary_completion_projections p
                JOIN contents c ON c.id = p.content_id
                JOIN users owner ON owner.id = p.user_id
                JOIN user_series_history_states state ON state.user_id = p.user_id
                  AND state.series_tmdb_id = CASE WHEN c.type = 'SERIES' THEN c.tmdb_id ELSE c.series_tmdb_id END
                JOIN calendar_schedule_completeness marker
                  ON marker.series_tmdb_id = state.series_tmdb_id
                 AND marker.group_type = CASE WHEN c.type = 'SERIES' THEN 'SERIES' ELSE 'SEASON' END
                 AND marker.season_number IS NOT DISTINCT FROM CASE WHEN c.type = 'SEASON' THEN c.season_number ELSE NULL END
                 AND marker.region = owner.preferred_region
                 AND marker.language = owner.preferred_language
                 AND marker.scope_version = p.schedule_version
                 AND marker.complete = TRUE
                WHERE p.user_id = :userId
                  AND p.content_id IN (:contentIds)
                  AND p.content_type = :contentType
                  AND c.type = p.content_type
                  AND :contentType IN ('SERIES', 'SEASON')
                  AND p.history_revision = state.history_revision
                  AND p.history_revision = state.computed_revision
                  AND (c.type = 'SEASON' OR p.schedule_version = state.schedule_version)
                  AND p.valid_until > CURRENT_TIMESTAMP
                  AND state.valid_until > CURRENT_TIMESTAMP
                UNION ALL
                SELECT c.id AS content_id
                FROM diary_entries d
                JOIN contents c ON c.id = d.content_id
                WHERE d.user_id = :userId
                  AND d.watch_number > 0
                  AND c.type = :contentType
                  AND c.id IN (:contentIds)
                  AND :contentType NOT IN ('SERIES', 'SEASON')
            ) ranked
            GROUP BY ranked.content_id
            """, nativeQuery = true)
    List<ContentWatchCount> countDiaryEntriesByUserIdAndContentIdsAndContentType(
            @Param("userId") UUID userId,
            @Param("contentIds") Collection<UUID> contentIds,
            @Param("contentType") String contentType);

    @Query(value = """
            SELECT ranked.content_id AS contentId, COUNT(*) AS count
            FROM (
                SELECT p.content_id
                FROM diary_completion_projections p
                JOIN contents c ON c.id = p.content_id
                JOIN users owner ON owner.id = p.user_id
                JOIN user_series_history_states state ON state.user_id = p.user_id
                  AND state.series_tmdb_id = CASE WHEN c.type = 'SERIES' THEN c.tmdb_id ELSE c.series_tmdb_id END
                JOIN calendar_schedule_completeness marker
                  ON marker.series_tmdb_id = state.series_tmdb_id
                 AND marker.group_type = CASE WHEN c.type = 'SERIES' THEN 'SERIES' ELSE 'SEASON' END
                 AND marker.season_number IS NOT DISTINCT FROM CASE WHEN c.type = 'SEASON' THEN c.season_number ELSE NULL END
                 AND marker.region = owner.preferred_region
                 AND marker.language = owner.preferred_language
                 AND marker.scope_version = p.schedule_version
                 AND marker.complete = TRUE
                WHERE p.user_id = :userId
                  AND p.content_type = :contentType
                  AND c.type = p.content_type
                  AND :contentType IN ('SERIES', 'SEASON')
                  AND p.history_revision = state.history_revision
                  AND p.history_revision = state.computed_revision
                  AND (c.type = 'SEASON' OR p.schedule_version = state.schedule_version)
                  AND p.valid_until > CURRENT_TIMESTAMP
                  AND state.valid_until > CURRENT_TIMESTAMP
                UNION ALL
                SELECT c.id AS content_id
                FROM diary_entries d
                JOIN contents c ON c.id = d.content_id
                WHERE d.user_id = :userId
                  AND d.watch_number > 0
                  AND c.type = :contentType
                  AND :contentType NOT IN ('SERIES', 'SEASON')
            ) ranked
            GROUP BY ranked.content_id
            ORDER BY count DESC, ranked.content_id ASC
            """, nativeQuery = true)
    List<ContentWatchCount> countDiaryEntriesGroupByContentType(
            @Param("userId") UUID userId,
            @Param("contentType") String contentType,
            Pageable pageable);

    @Query(value = """
            WITH selected_scores AS (
                SELECT r.content_id, r.score
                FROM content_ratings r
                WHERE r.user_id = :userId
                  AND r.content_id IN (:contentIds)
                  AND r.content_type = :contentType
                  AND :contentType IN ('SERIES', 'SEASON')
                UNION ALL
                SELECT ranked.content_id, ranked.score
                FROM (
                    SELECT c.id AS content_id,
                           d.score,
                           ROW_NUMBER() OVER (
                               PARTITION BY c.id
                               ORDER BY d.watch_number DESC, d.created_at DESC, d.id DESC
                           ) AS row_number
                    FROM diary_entries d
                    JOIN contents c ON c.id = d.content_id
                    WHERE d.user_id = :userId
                      AND d.watch_number > 0
                      AND c.type = :contentType
                      AND c.id IN (:contentIds)
                ) ranked
                WHERE ranked.row_number = 1
                  AND :contentType NOT IN ('SERIES', 'SEASON')
            )
            SELECT content_id AS contentId, score AS score
            FROM selected_scores
            """, nativeQuery = true)
    List<LatestContentScore> findLatestScoresByUserIdAndContentIdsAndContentType(
            @Param("userId") UUID userId,
            @Param("contentIds") Collection<UUID> contentIds,
            @Param("contentType") String contentType);

    @Query(value = """
            SELECT DISTINCT c.*
            FROM contents c
            JOIN diary_entries d ON d.content_id = c.id
            WHERE d.user_id = :userId
              AND c.type = 'MOVIE'
            ORDER BY c.runtime_minutes DESC NULLS LAST, c.id ASC
            """, nativeQuery = true)
    List<Content> findLongestMovieContentByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query(value = """
            SELECT c.series_tmdb_id AS seriesTmdbId,
                   COALESCE(SUM(c.runtime_minutes), 0) AS totalMinutes,
                   COUNT(d.id) AS episodeCount
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            WHERE d.user_id = :userId
              AND c.type = 'EPISODE'
            GROUP BY c.series_tmdb_id
            ORDER BY totalMinutes DESC, c.series_tmdb_id ASC
            """, nativeQuery = true)
    List<SeriesRuntime> sumRuntimeMinutesByUserIdGroupBySeriesTmdbId(
            @Param("userId") UUID userId, Pageable pageable);

    interface GenreCount {
        String getGenre();
        Long getCount();
    }

    interface ScoreCount {
        Integer getScore();
        Long getCount();
    }

    interface ContentWatchCount {
        UUID getContentId();
        Long getCount();
    }

    interface LatestContentScore {
        UUID getContentId();
        Integer getScore();
    }

    interface SeriesRuntime {
        String getSeriesTmdbId();
        Long getTotalMinutes();
        Long getEpisodeCount();
    }
}
