package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiaryEntryRepository extends JpaRepository<DiaryEntry, UUID> {

    @Modifying
    @Query("UPDATE DiaryEntry d SET d.likesCount = d.likesCount + 1 WHERE d.id = :id")
    void incrementLikesCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE DiaryEntry d SET d.likesCount = d.likesCount - 1 WHERE d.id = :id AND d.likesCount > 0")
    void decrementLikesCount(@Param("id") UUID id);

    @Query("SELECT d FROM DiaryEntry d JOIN FETCH d.content WHERE d.user.id = :userId ORDER BY d.createdAt DESC")
    Page<DiaryEntry> findByUserIdOrderByCreatedAtDesc(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
            SELECT d FROM DiaryEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId
            AND (:type IS NULL OR d.content.type = :type)
            AND (:watchedDateStart IS NULL OR d.watchedDate >= :watchedDateStart)
            AND (:watchedDateEnd IS NULL OR d.watchedDate <= :watchedDateEnd)
            AND (:hasReview IS NULL
                 OR (:hasReview = TRUE AND d.comment IS NOT NULL)
                 OR (:hasReview = FALSE AND d.comment IS NULL))
            ORDER BY d.createdAt DESC
            """)
    Page<DiaryEntry> findByUserIdWithFilters(
            @Param("userId") UUID userId,
            @Param("type") ContentType type,
            @Param("watchedDateStart") LocalDate watchedDateStart,
            @Param("watchedDateEnd") LocalDate watchedDateEnd,
            @Param("hasReview") Boolean hasReview,
            Pageable pageable);

    List<DiaryEntry> findByUserIdAndContentIdAndWatchNumberGreaterThan(UUID userId, UUID contentId, Integer watchNumber);

    @Query("SELECT d FROM DiaryEntry d JOIN FETCH d.user WHERE d.id = :id")
    Optional<DiaryEntry> findByIdWithUser(@Param("id") UUID id);

    @Query("""
            SELECT COALESCE(MAX(de.watchNumber), 0) FROM DiaryEntry de
            WHERE de.user.id = :userId
            AND de.content.id = :contentId
            """)
    int findMaxWatchNumber(@Param("userId") UUID userId, @Param("contentId") UUID contentId);

    Optional<DiaryEntry> findFirstByUserIdAndContentIdAndWatchNumber(UUID userId, UUID contentId, Integer watchNumber);

    @Query("""
            SELECT de.content.episodeNumber AS episodeNumber, COUNT(de) AS count FROM DiaryEntry de
            WHERE de.user.id = :userId
            AND de.content.type = com.watchwise.watchwise_api.content.entity.ContentType.EPISODE
            AND de.content.seriesTmdbId = :seriesTmdbId
            AND de.content.seasonNumber = :seasonNumber
            GROUP BY de.content.episodeNumber
            """)
    List<EpisodeWatchCount> countEntriesByEpisodeNumberInSeason(
            @Param("userId") UUID userId,
            @Param("seriesTmdbId") String seriesTmdbId,
            @Param("seasonNumber") Integer seasonNumber);

    @Query("""
            SELECT de.content.seasonNumber AS seasonNumber, MAX(de.watchNumber) AS maxWatchNumber FROM DiaryEntry de
            WHERE de.user.id = :userId
            AND de.content.type = com.watchwise.watchwise_api.content.entity.ContentType.SEASON
            AND de.content.seriesTmdbId = :seriesTmdbId
            GROUP BY de.content.seasonNumber
            """)
    List<SeasonWatchMax> maxWatchNumberBySeasonInSeries(
            @Param("userId") UUID userId,
            @Param("seriesTmdbId") String seriesTmdbId);

    interface EpisodeWatchCount {
        Integer getEpisodeNumber();
        Long getCount();
    }

    interface SeasonWatchMax {
        Integer getSeasonNumber();
        Integer getMaxWatchNumber();
    }

    @Query("""
            SELECT de FROM DiaryEntry de
            WHERE de.user.id = :userId
            AND de.content.type = com.watchwise.watchwise_api.content.entity.ContentType.EPISODE
            AND de.content.seriesTmdbId = :seriesTmdbId
            AND de.watchNumber = :watchNumber
            """)
    List<DiaryEntry> findEpisodeEntriesInSeriesByWatchNumber(
            @Param("userId") UUID userId, @Param("seriesTmdbId") String seriesTmdbId, @Param("watchNumber") Integer watchNumber);

    @Query("""
            SELECT de FROM DiaryEntry de
            WHERE de.user.id = :userId
            AND de.content.type = com.watchwise.watchwise_api.content.entity.ContentType.SEASON
            AND de.content.seriesTmdbId = :seriesTmdbId
            AND de.watchNumber = :watchNumber
            """)
    List<DiaryEntry> findSeasonEntriesInSeriesByWatchNumber(
            @Param("userId") UUID userId, @Param("seriesTmdbId") String seriesTmdbId, @Param("watchNumber") Integer watchNumber);

    @Query("""
            SELECT de FROM DiaryEntry de
            WHERE de.user.id = :userId
            AND de.content.type = com.watchwise.watchwise_api.content.entity.ContentType.SERIES
            AND de.content.tmdbId = :seriesTmdbId
            AND de.watchNumber = :watchNumber
            """)
    List<DiaryEntry> findSeriesEntriesByWatchNumber(
            @Param("userId") UUID userId, @Param("seriesTmdbId") String seriesTmdbId, @Param("watchNumber") Integer watchNumber);

    @Query("""
            SELECT COALESCE(SUM(d.content.runtimeMinutes), 0) FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type IN (com.watchwise.watchwise_api.content.entity.ContentType.MOVIE,
                                    com.watchwise.watchwise_api.content.entity.ContentType.EPISODE)
            """)
    long sumRuntimeMinutesByUserId(@Param("userId") UUID userId);

    @Query("""
            SELECT COALESCE(SUM(d.content.runtimeMinutes), 0) FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type IN (com.watchwise.watchwise_api.content.entity.ContentType.MOVIE,
                                    com.watchwise.watchwise_api.content.entity.ContentType.EPISODE)
            AND d.watchedDate BETWEEN :start AND :end
            """)
    long sumRuntimeMinutesByUserIdAndWatchedDateBetween(
            @Param("userId") UUID userId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query(value = """
            SELECT genre AS genre,
                   COUNT(DISTINCT CASE WHEN c.type = 'MOVIE' THEN c.id::text ELSE c.series_tmdb_id END) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            LEFT JOIN contents sc ON c.type = 'EPISODE' AND sc.tmdb_id = c.series_tmdb_id AND sc.type = 'SERIES'
            CROSS JOIN LATERAL unnest(CASE WHEN c.type = 'MOVIE' THEN c.genres ELSE sc.genres END) AS genre
            WHERE d.user_id = :userId
            AND c.type IN ('MOVIE', 'EPISODE')
            GROUP BY genre
            ORDER BY count DESC
            """, nativeQuery = true)
    List<GenreCount> countDistinctTitlesByGenreAndUserId(@Param("userId") UUID userId);

    interface GenreCount {
        String getGenre();
        Long getCount();
    }

    @Query(value = """
            WITH episode_entries AS (
                SELECT c.series_tmdb_id AS series_tmdb_id,
                       c.season_number AS season_number,
                       c.episode_number AS episode_number,
                       COALESCE(d.watched_date, d.created_at::date) AS effective_date
                FROM diary_entries d
                JOIN contents c ON c.id = d.content_id
                WHERE d.user_id = :userId
                AND c.type = 'EPISODE'
            ),
            series_agg AS (
                SELECT series_tmdb_id,
                       MAX(season_number) AS max_season_number,
                       MAX(effective_date) AS last_watched_date
                FROM episode_entries
                GROUP BY series_tmdb_id
            ),
            max_episode AS (
                SELECT ee.series_tmdb_id AS series_tmdb_id,
                       MAX(ee.episode_number) AS max_episode_number
                FROM episode_entries ee
                JOIN series_agg sa ON sa.series_tmdb_id = ee.series_tmdb_id AND sa.max_season_number = ee.season_number
                GROUP BY ee.series_tmdb_id
            )
            SELECT sa.series_tmdb_id AS seriesTmdbId,
                   sa.max_season_number AS maxSeasonNumber,
                   me.max_episode_number AS maxEpisodeNumber,
                   sa.last_watched_date AS lastWatchedDate
            FROM series_agg sa
            JOIN max_episode me ON me.series_tmdb_id = sa.series_tmdb_id
            WHERE NOT EXISTS (
                SELECT 1 FROM diary_entries d2
                JOIN contents c2 ON c2.id = d2.content_id
                WHERE d2.user_id = :userId
                AND c2.type = 'SERIES'
                AND c2.tmdb_id = sa.series_tmdb_id
            )
            ORDER BY sa.last_watched_date DESC
            """,
            countQuery = """
            WITH episode_entries AS (
                SELECT c.series_tmdb_id AS series_tmdb_id
                FROM diary_entries d
                JOIN contents c ON c.id = d.content_id
                WHERE d.user_id = :userId
                AND c.type = 'EPISODE'
            ),
            series_agg AS (
                SELECT series_tmdb_id
                FROM episode_entries
                GROUP BY series_tmdb_id
            )
            SELECT COUNT(*)
            FROM series_agg sa
            WHERE NOT EXISTS (
                SELECT 1 FROM diary_entries d2
                JOIN contents c2 ON c2.id = d2.content_id
                WHERE d2.user_id = :userId
                AND c2.type = 'SERIES'
                AND c2.tmdb_id = sa.series_tmdb_id
            )
            """,
            nativeQuery = true)
    Page<SeriesInProgress> findSeriesInProgressByUserId(@Param("userId") UUID userId, Pageable pageable);

    interface SeriesInProgress {
        String getSeriesTmdbId();
        Integer getMaxSeasonNumber();
        Integer getMaxEpisodeNumber();
        LocalDate getLastWatchedDate();
    }

}
