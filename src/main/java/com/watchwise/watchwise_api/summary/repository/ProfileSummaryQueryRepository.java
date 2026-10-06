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
            WITH ranked AS (
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
            )
            SELECT score AS score, COUNT(*) AS count
            FROM ranked
            WHERE row_number = 1 AND score IS NOT NULL
            GROUP BY score
            ORDER BY score
            """, nativeQuery = true)
    List<ScoreCount> countLatestScoresByUserIdAndContentType(
            @Param("userId") UUID userId, @Param("contentType") String contentType);

    @Query(value = """
            SELECT c.id AS contentId, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            WHERE d.user_id = :userId
              AND d.watch_number > 0
              AND c.type = :contentType
              AND c.id IN :contentIds
            GROUP BY c.id
            """, nativeQuery = true)
    List<ContentWatchCount> countDiaryEntriesByUserIdAndContentIdsAndContentType(
            @Param("userId") UUID userId,
            @Param("contentIds") Collection<UUID> contentIds,
            @Param("contentType") String contentType);

    @Query(value = """
            SELECT c.id AS contentId, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            WHERE d.user_id = :userId
              AND d.watch_number > 0
              AND c.type = :contentType
            GROUP BY c.id
            ORDER BY count DESC, c.id ASC
            """, nativeQuery = true)
    List<ContentWatchCount> countDiaryEntriesGroupByContentType(
            @Param("userId") UUID userId,
            @Param("contentType") String contentType,
            Pageable pageable);

    @Query(value = """
            WITH ranked AS (
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
                  AND c.id IN :contentIds
            )
            SELECT content_id AS contentId, score AS score
            FROM ranked
            WHERE row_number = 1
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
