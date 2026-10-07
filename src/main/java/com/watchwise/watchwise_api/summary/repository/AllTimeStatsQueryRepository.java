package com.watchwise.watchwise_api.summary.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface AllTimeStatsQueryRepository extends Repository<DiaryEntry, UUID> {

    @Query("""
            SELECT MIN(d.watchedDate) FROM DiaryEntry d
            WHERE d.user.id = :userId
            AND d.content.type = :contentType
            """)
    LocalDate findMinWatchedDateByUserIdAndContentType(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType);

    @Query(value = """
            SELECT EXTRACT(YEAR FROM d.watched_date)::int AS year, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            WHERE d.user_id = :userId
              AND c.type = :contentType
            GROUP BY year
            ORDER BY year
            """, nativeQuery = true)
    List<YearCount> countByUserIdAndContentTypeGroupByYear(
            @Param("userId") UUID userId, @Param("contentType") String contentType);

    @Query(value = """
            SELECT ((c.release_year / 10) * 10) AS decade, COUNT(DISTINCT c.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            WHERE d.user_id = :userId
              AND c.type = 'MOVIE'
              AND c.release_year IS NOT NULL
            GROUP BY decade
            ORDER BY decade
            """, nativeQuery = true)
    List<DecadeCount> countDistinctMoviesByDecade(@Param("userId") UUID userId);

    @Query(value = """
            SELECT ((sc.release_year / 10) * 10) AS decade,
                   COUNT(DISTINCT c.series_tmdb_id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            JOIN contents sc ON sc.tmdb_id = c.series_tmdb_id AND sc.type = 'SERIES'
            WHERE d.user_id = :userId
              AND c.type = 'EPISODE'
              AND sc.release_year IS NOT NULL
            GROUP BY decade
            ORDER BY decade
            """, nativeQuery = true)
    List<DecadeCount> countDistinctSeriesByDecade(@Param("userId") UUID userId);

    @Query(value = """
            SELECT country AS country, COUNT(DISTINCT c.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            CROSS JOIN LATERAL unnest(c.countries) AS country
            WHERE d.user_id = :userId
              AND c.type = 'MOVIE'
            GROUP BY country
            ORDER BY count DESC, country
            """, nativeQuery = true)
    List<CountryCount> countDistinctMoviesByCountry(@Param("userId") UUID userId);

    @Query(value = """
            SELECT country AS country, COUNT(DISTINCT c.series_tmdb_id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            JOIN contents sc ON sc.tmdb_id = c.series_tmdb_id AND sc.type = 'SERIES'
            CROSS JOIN LATERAL unnest(sc.countries) AS country
            WHERE d.user_id = :userId
              AND c.type = 'EPISODE'
            GROUP BY country
            ORDER BY count DESC, country
            """, nativeQuery = true)
    List<CountryCount> countDistinctSeriesByCountry(@Param("userId") UUID userId);

    @Query(value = """
            SELECT c.id AS contentId, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            WHERE d.user_id = :userId
              AND c.type = 'MOVIE'
            GROUP BY c.id
            ORDER BY count DESC, c.id
            """, nativeQuery = true)
    List<ContentWatchCount> countMostLoggedMovies(@Param("userId") UUID userId, Pageable pageable);

    @Query(value = """
            SELECT sc.id AS contentId, COUNT(d.id) AS count
            FROM diary_entries d
            JOIN contents c ON c.id = d.content_id
            JOIN contents sc ON sc.tmdb_id = c.series_tmdb_id AND sc.type = 'SERIES'
            WHERE d.user_id = :userId
              AND c.type = 'EPISODE'
            GROUP BY sc.id
            ORDER BY count DESC, sc.id
            """, nativeQuery = true)
    List<ContentWatchCount> countMostLoggedSeries(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
            SELECT d FROM DiaryEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId
              AND d.content.type = :contentType
              AND d.score IS NOT NULL
            ORDER BY d.score DESC, d.watchedDate DESC, d.id DESC
            """)
    List<DiaryEntry> findTopRatedByUserIdAndContentType(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType, Pageable pageable);

    @Query("""
            SELECT d FROM DiaryEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId
              AND d.content.type = :contentType
              AND d.score IS NOT NULL
            ORDER BY d.score ASC, d.watchedDate DESC, d.id DESC
            """)
    List<DiaryEntry> findBottomRatedByUserIdAndContentType(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType, Pageable pageable);

    @Query("""
            SELECT COUNT(d) FROM DiaryEntry d
            WHERE d.user.id = :userId
              AND d.content.type = com.watchwise.watchwise_api.content.entity.ContentType.MOVIE
              AND d.watchedInTheater = true
            """)
    long countTheaterVisitsByUserId(@Param("userId") UUID userId);

    interface YearCount {
        Integer getYear();
        Long getCount();
    }

    interface DecadeCount {
        Integer getDecade();
        Long getCount();
    }

    interface CountryCount {
        String getCountry();
        Long getCount();
    }

    interface ContentWatchCount {
        UUID getContentId();
        Long getCount();
    }
}
