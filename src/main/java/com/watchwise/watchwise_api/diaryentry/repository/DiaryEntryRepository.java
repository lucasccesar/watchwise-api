package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiaryEntryRepository extends JpaRepository<DiaryEntry, UUID> {

    // --- Feed (GET /feed) ---

    @Query("""
            SELECT d FROM DiaryEntry d JOIN FETCH d.content JOIN FETCH d.user
            WHERE d.user.id IN :userIds
            AND d.ignore = false
            AND (
                CAST(:cursorCreatedAt AS timestamp) IS NULL
                OR d.createdAt < :cursorCreatedAt
                OR (d.createdAt = :cursorCreatedAt AND (:cursorId IS NULL OR d.id < :cursorId))
            )
            ORDER BY d.createdAt DESC, d.id DESC
            """)
    List<DiaryEntry> findFeedCandidates(
            @Param("userIds") Collection<UUID> userIds,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

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
            AND (CAST(:watchedDateStart AS date) IS NULL OR d.watchedDate >= :watchedDateStart)
            AND (CAST(:watchedDateEnd AS date) IS NULL OR d.watchedDate <= :watchedDateEnd)
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

    @Query("""
            SELECT COALESCE(SUM(d.content.runtimeMinutes), 0) FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            """)
    long sumRuntimeMinutesByUserIdAndContentType(@Param("userId") UUID userId, @Param("contentType") ContentType contentType);

    @Query("""
            SELECT COALESCE(SUM(d.content.runtimeMinutes), 0) FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            AND d.watchedDate BETWEEN :start AND :end
            """)
    long sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType,
            @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query(value = """
            SELECT genre AS genre, COUNT(DISTINCT c.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            CROSS JOIN LATERAL unnest(c.genres) AS genre
            WHERE d.user_id = :userId
            AND c.type = 'MOVIE'
            GROUP BY genre
            ORDER BY count DESC
            """, nativeQuery = true)
    List<GenreCount> countDistinctTitlesByGenreAndUserIdForMovies(@Param("userId") UUID userId);

    @Query(value = """
            SELECT genre AS genre, COUNT(DISTINCT c.series_tmdb_id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            JOIN contents sc ON sc.tmdb_id = c.series_tmdb_id AND sc.type = 'SERIES'
            CROSS JOIN LATERAL unnest(sc.genres) AS genre
            WHERE d.user_id = :userId
            AND c.type = 'EPISODE'
            GROUP BY genre
            ORDER BY count DESC
            """, nativeQuery = true)
    List<GenreCount> countDistinctTitlesByGenreAndUserIdForSeries(@Param("userId") UUID userId);

    @Query("""
            SELECT d.score AS score, COUNT(d) AS count FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            AND d.score IS NOT NULL
            GROUP BY d.score
            """)
    List<ScoreCount> countByUserIdAndContentTypeGroupByScore(@Param("userId") UUID userId, @Param("contentType") ContentType contentType);

    interface ScoreCount {
        Integer getScore();
        Long getCount();
    }

    @Query("""
            SELECT d FROM DiaryEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            ORDER BY d.createdAt DESC
            """)
    List<DiaryEntry> findTopByUserIdAndContentTypeOrderByCreatedAtDesc(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType, Pageable pageable);

    // --- Month/Year in Review + All Time Stats aggregations ---

    @Query("""
            SELECT MIN(d.watchedDate) FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type IN (com.watchwise.watchwise_api.content.entity.ContentType.MOVIE,
                                    com.watchwise.watchwise_api.content.entity.ContentType.EPISODE)
            """)
    Optional<LocalDate> findMinWatchedDateByUserId(@Param("userId") UUID userId);

    @Query("""
            SELECT d FROM DiaryEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            AND d.watchedDate BETWEEN :start AND :end
            ORDER BY d.watchedDate DESC, d.createdAt DESC
            """)
    List<DiaryEntry> findByUserIdAndContentTypeAndWatchedDateBetweenOrderByWatchedDateDesc(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType,
            @Param("start") LocalDate start, @Param("end") LocalDate end, Pageable pageable);

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
            SELECT COUNT(d) FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            """)
    long countByUserIdAndContentType(@Param("userId") UUID userId, @Param("contentType") ContentType contentType);

    @Query("""
            SELECT MIN(d.watchedDate) FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            AND d.watchedDate BETWEEN :start AND :end
            """)
    Optional<LocalDate> findMinWatchedDateByUserIdAndContentTypeAndWatchedDateBetween(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType,
            @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("""
            SELECT MAX(d.watchedDate) FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            AND d.watchedDate BETWEEN :start AND :end
            """)
    Optional<LocalDate> findMaxWatchedDateByUserIdAndContentTypeAndWatchedDateBetween(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType,
            @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("""
            SELECT d.watchedDate AS watchedDate, COALESCE(SUM(d.content.runtimeMinutes), 0) AS minutes FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            AND d.watchedDate BETWEEN :start AND :end
            GROUP BY d.watchedDate
            ORDER BY d.watchedDate
            """)
    List<DailyMinutes> sumRuntimeMinutesByUserIdAndContentTypeGroupByWatchedDateBetween(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType,
            @Param("start") LocalDate start, @Param("end") LocalDate end);

    interface DailyMinutes {
        LocalDate getWatchedDate();
        Long getMinutes();
    }

    @Query(value = """
            SELECT EXTRACT(ISODOW FROM d.watched_date)::int AS dayOfWeek, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            WHERE d.user_id = :userId
            AND c.type = 'MOVIE'
            AND d.watched_date BETWEEN :start AND :end
            GROUP BY dayOfWeek
            ORDER BY dayOfWeek
            """, nativeQuery = true)
    List<DayOfWeekCount> countByUserIdAndWatchedDateBetweenGroupByDayOfWeekForMovies(
            @Param("userId") UUID userId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query(value = """
            SELECT EXTRACT(ISODOW FROM d.watched_date)::int AS dayOfWeek, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            WHERE d.user_id = :userId
            AND c.type = 'EPISODE'
            AND d.watched_date BETWEEN :start AND :end
            GROUP BY dayOfWeek
            ORDER BY dayOfWeek
            """, nativeQuery = true)
    List<DayOfWeekCount> countByUserIdAndWatchedDateBetweenGroupByDayOfWeekForEpisodes(
            @Param("userId") UUID userId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    interface DayOfWeekCount {
        Integer getDayOfWeek();
        Long getCount();
    }

    @Query(value = """
            SELECT EXTRACT(MONTH FROM d.watched_date)::int AS month, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            WHERE d.user_id = :userId
            AND c.type = 'MOVIE'
            AND d.watched_date BETWEEN :start AND :end
            GROUP BY month
            ORDER BY month
            """, nativeQuery = true)
    List<MonthCount> countByUserIdAndWatchedDateBetweenGroupByMonthForMovies(
            @Param("userId") UUID userId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query(value = """
            SELECT EXTRACT(MONTH FROM d.watched_date)::int AS month, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            WHERE d.user_id = :userId
            AND c.type = 'EPISODE'
            AND d.watched_date BETWEEN :start AND :end
            GROUP BY month
            ORDER BY month
            """, nativeQuery = true)
    List<MonthCount> countByUserIdAndWatchedDateBetweenGroupByMonthForEpisodes(
            @Param("userId") UUID userId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    interface MonthCount {
        Integer getMonth();
        Long getCount();
    }

    @Query(value = """
            SELECT EXTRACT(YEAR FROM d.watched_date)::int AS year, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            WHERE d.user_id = :userId
            AND c.type = 'MOVIE'
            GROUP BY year
            ORDER BY year
            """, nativeQuery = true)
    List<YearCount> countByUserIdGroupByYearForMovies(@Param("userId") UUID userId);

    @Query(value = """
            SELECT EXTRACT(YEAR FROM d.watched_date)::int AS year, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            WHERE d.user_id = :userId
            AND c.type = 'EPISODE'
            GROUP BY year
            ORDER BY year
            """, nativeQuery = true)
    List<YearCount> countByUserIdGroupByYearForEpisodes(@Param("userId") UUID userId);

    interface YearCount {
        Integer getYear();
        Long getCount();
    }

    @Query("""
            SELECT d.score AS score, COUNT(d) AS count FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            AND d.watchedDate BETWEEN :start AND :end
            AND d.score IS NOT NULL
            GROUP BY d.score
            """)
    List<ScoreCount> countByUserIdAndContentTypeAndWatchedDateBetweenGroupByScore(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType,
            @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query(value = """
            SELECT genre AS genre, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            CROSS JOIN LATERAL unnest(c.genres) AS genre
            WHERE d.user_id = :userId
            AND c.type = 'MOVIE'
            AND d.watched_date BETWEEN :start AND :end
            GROUP BY genre
            ORDER BY count DESC
            """, nativeQuery = true)
    List<GenreCount> countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(
            @Param("userId") UUID userId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query(value = """
            SELECT genre AS genre, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            JOIN contents sc ON sc.tmdb_id = c.series_tmdb_id AND sc.type = 'SERIES'
            CROSS JOIN LATERAL unnest(sc.genres) AS genre
            WHERE d.user_id = :userId
            AND c.type = 'EPISODE'
            AND d.watched_date BETWEEN :start AND :end
            GROUP BY genre
            ORDER BY count DESC
            """, nativeQuery = true)
    List<GenreCount> countEntriesByGenreAndUserIdForSeriesAndWatchedDateBetween(
            @Param("userId") UUID userId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query(value = """
            SELECT genre AS genre, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            CROSS JOIN LATERAL unnest(c.genres) AS genre
            WHERE d.user_id = :userId
            AND c.type = 'MOVIE'
            GROUP BY genre
            ORDER BY count DESC
            """, nativeQuery = true)
    List<GenreCount> countEntriesByGenreAndUserIdForMovies(@Param("userId") UUID userId);

    @Query(value = """
            SELECT genre AS genre, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            JOIN contents sc ON sc.tmdb_id = c.series_tmdb_id AND sc.type = 'SERIES'
            CROSS JOIN LATERAL unnest(sc.genres) AS genre
            WHERE d.user_id = :userId
            AND c.type = 'EPISODE'
            GROUP BY genre
            ORDER BY count DESC
            """, nativeQuery = true)
    List<GenreCount> countEntriesByGenreAndUserIdForSeries(@Param("userId") UUID userId);

    @Query("""
            SELECT d FROM DiaryEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            AND d.watchedDate BETWEEN :start AND :end
            AND d.score IS NOT NULL
            ORDER BY d.score DESC, d.watchedDate DESC
            """)
    List<DiaryEntry> findTopRatedByUserIdAndContentTypeAndWatchedDateBetween(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType,
            @Param("start") LocalDate start, @Param("end") LocalDate end, Pageable pageable);

    @Query("""
            SELECT d FROM DiaryEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            AND d.watchedDate BETWEEN :start AND :end
            AND d.score IS NOT NULL
            ORDER BY d.score ASC, d.watchedDate DESC
            """)
    List<DiaryEntry> findBottomRatedByUserIdAndContentTypeAndWatchedDateBetween(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType,
            @Param("start") LocalDate start, @Param("end") LocalDate end, Pageable pageable);

    @Query("""
            SELECT d FROM DiaryEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId
            AND d.content.type IN (com.watchwise.watchwise_api.content.entity.ContentType.MOVIE,
                                    com.watchwise.watchwise_api.content.entity.ContentType.SERIES)
            AND d.score IS NOT NULL
            ORDER BY d.score DESC, d.watchedDate DESC
            """)
    List<DiaryEntry> findTopRatedByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
            SELECT d FROM DiaryEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId
            AND d.content.type IN (com.watchwise.watchwise_api.content.entity.ContentType.MOVIE,
                                    com.watchwise.watchwise_api.content.entity.ContentType.SERIES)
            AND d.score IS NOT NULL
            ORDER BY d.score ASC, d.watchedDate DESC
            """)
    List<DiaryEntry> findBottomRatedByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query(value = """
            SELECT c.series_tmdb_id AS seriesTmdbId, COALESCE(SUM(c.runtime_minutes), 0) AS totalMinutes
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            WHERE d.user_id = :userId
            AND c.type = 'EPISODE'
            AND d.watched_date BETWEEN :start AND :end
            GROUP BY c.series_tmdb_id
            ORDER BY totalMinutes DESC
            """, nativeQuery = true)
    List<SeriesRuntime> sumRuntimeMinutesByUserIdGroupBySeriesTmdbIdAndWatchedDateBetween(
            @Param("userId") UUID userId, @Param("start") LocalDate start, @Param("end") LocalDate end, Pageable pageable);

    interface SeriesRuntime {
        String getSeriesTmdbId();
        Long getTotalMinutes();
    }

    @Query(value = """
            SELECT DISTINCT c.*
            FROM contents c
            JOIN diary_entries d ON d.content_id = c.id
            WHERE d.user_id = :userId
            AND c.type = 'MOVIE'
            AND d.watched_date BETWEEN :start AND :end
            ORDER BY c.runtime_minutes DESC NULLS LAST
            """, nativeQuery = true)
    List<Content> findDistinctMovieContentByUserIdAndWatchedDateBetweenOrderByRuntimeDesc(
            @Param("userId") UUID userId, @Param("start") LocalDate start, @Param("end") LocalDate end, Pageable pageable);

    @Query(value = """
            SELECT c.id AS contentId, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            WHERE d.user_id = :userId
            AND c.type IN ('MOVIE', 'SERIES')
            GROUP BY c.id
            ORDER BY count DESC
            """, nativeQuery = true)
    List<ContentWatchCount> countDiaryEntriesGroupByContentId(@Param("userId") UUID userId, Pageable pageable);

    interface ContentWatchCount {
        UUID getContentId();
        Long getCount();
    }

    @Query(value = """
            SELECT ((CASE WHEN c.type = 'MOVIE' THEN c.release_year ELSE sc.release_year END) / 10) * 10 AS decade,
                   COUNT(DISTINCT CASE WHEN c.type = 'MOVIE' THEN c.id::text ELSE c.series_tmdb_id END) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            LEFT JOIN contents sc ON c.type = 'EPISODE' AND sc.tmdb_id = c.series_tmdb_id AND sc.type = 'SERIES'
            WHERE d.user_id = :userId
            AND c.type IN ('MOVIE', 'EPISODE')
            AND (CASE WHEN c.type = 'MOVIE' THEN c.release_year ELSE sc.release_year END) IS NOT NULL
            GROUP BY decade
            ORDER BY decade
            """, nativeQuery = true)
    List<DecadeCount> countDistinctTitlesByDecadeAndUserId(@Param("userId") UUID userId);

    interface DecadeCount {
        Integer getDecade();
        Long getCount();
    }

    @Query(value = """
            SELECT country AS country,
                   COUNT(DISTINCT CASE WHEN c.type = 'MOVIE' THEN c.id::text ELSE c.series_tmdb_id END) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            LEFT JOIN contents sc ON c.type = 'EPISODE' AND sc.tmdb_id = c.series_tmdb_id AND sc.type = 'SERIES'
            CROSS JOIN LATERAL unnest(CASE WHEN c.type = 'MOVIE' THEN c.countries ELSE sc.countries END) AS country
            WHERE d.user_id = :userId
            AND c.type IN ('MOVIE', 'EPISODE')
            GROUP BY country
            ORDER BY count DESC
            """, nativeQuery = true)
    List<CountryCount> countDistinctTitlesByCountryAndUserId(@Param("userId") UUID userId);

    interface CountryCount {
        String getCountry();
        Long getCount();
    }

    @Query("""
            SELECT d FROM DiaryEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId
            AND d.content.type = com.watchwise.watchwise_api.content.entity.ContentType.EPISODE
            AND d.content.seriesTmdbId = :seriesTmdbId
            """)
    List<DiaryEntry> findEpisodeEntriesBySeriesForUser(
            @Param("userId") UUID userId, @Param("seriesTmdbId") String seriesTmdbId);

    // --- Delete all diary entries for a series, every watchNumber (DELETE /diary/series/{seriesTmdbId}) ---
    // Episode side reuses findEpisodeEntriesBySeriesForUser above (same filter, already unscoped by watchNumber).

    @Query("""
            SELECT de FROM DiaryEntry de
            WHERE de.user.id = :userId
            AND de.content.type = com.watchwise.watchwise_api.content.entity.ContentType.SEASON
            AND de.content.seriesTmdbId = :seriesTmdbId
            """)
    List<DiaryEntry> findAllSeasonEntriesInSeries(
            @Param("userId") UUID userId, @Param("seriesTmdbId") String seriesTmdbId);

    @Query("""
            SELECT de FROM DiaryEntry de
            WHERE de.user.id = :userId
            AND de.content.type = com.watchwise.watchwise_api.content.entity.ContentType.SERIES
            AND de.content.tmdbId = :seriesTmdbId
            """)
    List<DiaryEntry> findAllSeriesEntriesInSeries(
            @Param("userId") UUID userId, @Param("seriesTmdbId") String seriesTmdbId);

    // --- Content stats (aggregated across all users, GET /contents/{contentId}/stats) ---

    @Query("""
            SELECT d.content.id AS contentId, AVG(d.score) AS averageScore, COUNT(d) AS playsCount,
                   SUM(CASE WHEN d.comment IS NOT NULL THEN 1L ELSE 0L END) AS reviewsCount
            FROM DiaryEntry d
            WHERE d.content.id IN :contentIds
            AND d.user.isProfilePublic = true
            GROUP BY d.content.id
            """)
    List<ContentStats> findContentStatsByContentIdIn(@Param("contentIds") Collection<UUID> contentIds);

    interface ContentStats {
        UUID getContentId();
        Double getAverageScore();
        long getPlaysCount();
        long getReviewsCount();
    }

    // --- Reviews scoped by Content, across all users (GET /contents/{contentId}/reviews) ---

    @Query("""
            SELECT d FROM DiaryEntry d JOIN FETCH d.content JOIN FETCH d.user u
            WHERE d.content.id = :contentId
            AND d.comment IS NOT NULL
            AND (u.isProfilePublic = true
                 OR u.id = :viewerId
                 OR EXISTS (
                     SELECT 1 FROM Follower f
                     WHERE f.follower.id = :viewerId AND f.followed.id = u.id
                     AND f.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED
                 ))
            ORDER BY d.createdAt DESC
            """)
    Page<DiaryEntry> findReviewsByContentId(
            @Param("contentId") UUID contentId, @Param("viewerId") UUID viewerId, Pageable pageable);

    // --- Home summary (GET /users/{userId}/summary/home) ---

    @Query("""
            SELECT d.watchedDate AS watchedDate, COUNT(d) AS count FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type IN (com.watchwise.watchwise_api.content.entity.ContentType.MOVIE,
                                    com.watchwise.watchwise_api.content.entity.ContentType.EPISODE)
            AND d.watchedDate BETWEEN :start AND :end
            GROUP BY d.watchedDate
            ORDER BY d.watchedDate
            """)
    List<DailyWatchCount> countByUserIdAndWatchedDateBetween(
            @Param("userId") UUID userId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    interface DailyWatchCount {
        LocalDate getWatchedDate();
        long getCount();
    }

}
