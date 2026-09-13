package com.watchwise.watchwise_api.calendar.repository;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CalendarScheduleSnapshotRepository extends JpaRepository<CalendarScheduleSnapshot, UUID> {

    default Optional<CalendarScheduleSnapshot> findByMovieIdentity(String tmdbId, String region, String language) {
        return findByEventTypeAndTmdbIdAndRegionAndLanguage(
                CalendarScheduleSnapshot.EventType.MOVIE, tmdbId, region, language);
    }

    Optional<CalendarScheduleSnapshot> findByEventTypeAndTmdbIdAndRegionAndLanguage(
            CalendarScheduleSnapshot.EventType eventType, String tmdbId, String region, String language);

    default Optional<CalendarScheduleSnapshot> findByEpisodeIdentity(
            String seriesTmdbId, Integer seasonNumber, Integer episodeNumber, String region, String language) {
        return findByEventTypeAndSeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndRegionAndLanguage(
                CalendarScheduleSnapshot.EventType.EPISODE,
                seriesTmdbId,
                seasonNumber,
                episodeNumber,
                region,
                language);
    }

    Optional<CalendarScheduleSnapshot> findByEventTypeAndSeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndRegionAndLanguage(
            CalendarScheduleSnapshot.EventType eventType,
            String seriesTmdbId,
            Integer seasonNumber,
            Integer episodeNumber,
            String region,
            String language);

    List<CalendarScheduleSnapshot> findByEventTypeAndSeriesTmdbIdAndSeasonNumberAndRegionAndLanguage(
            CalendarScheduleSnapshot.EventType eventType,
            String seriesTmdbId,
            Integer seasonNumber,
            String region,
            String language);

    @Query("""
            SELECT snapshot FROM CalendarScheduleSnapshot snapshot
            WHERE snapshot.region = :region
              AND snapshot.language = :language
              AND snapshot.presentInLastTmdbSnapshot = true
              AND ((snapshot.eventType = 'MOVIE' AND snapshot.tmdbId IN :movieTmdbIds)
                   OR (snapshot.eventType = 'EPISODE' AND snapshot.seriesTmdbId IN :seriesTmdbIds))
            """)
    List<CalendarScheduleSnapshot> findPresentForInterest(
            @Param("region") String region,
            @Param("language") String language,
            @Param("movieTmdbIds") Collection<String> movieTmdbIds,
            @Param("seriesTmdbIds") Collection<String> seriesTmdbIds);

    default List<CalendarScheduleSnapshot> findByReleaseMonth(YearMonth month, String region, String language) {
        return findByReleaseDateAndLocale(month.atDay(1), month.plusMonths(1).atDay(1), region, language);
    }

    @Query("""
            SELECT snapshot FROM CalendarScheduleSnapshot snapshot
            WHERE snapshot.releaseDate >= :monthStart
              AND snapshot.releaseDate < :nextMonthStart
              AND snapshot.region = :region
              AND snapshot.language = :language
            ORDER BY snapshot.releaseDate ASC, snapshot.id ASC
            """)
    List<CalendarScheduleSnapshot> findByReleaseDateAndLocale(
            @Param("monthStart") LocalDate monthStart,
            @Param("nextMonthStart") LocalDate nextMonthStart,
            @Param("region") String region,
            @Param("language") String language);

    @Query("""
            SELECT snapshot FROM CalendarScheduleSnapshot snapshot
            WHERE snapshot.nextCheckAt <= :dueAt
              AND snapshot.region = :region
              AND snapshot.language = :language
              AND ((snapshot.eventType = 'MOVIE' AND snapshot.tmdbId IN :movieTmdbIds)
                   OR (snapshot.eventType = 'EPISODE' AND snapshot.seriesTmdbId IN :seriesTmdbIds))
            ORDER BY snapshot.nextCheckAt ASC, snapshot.id ASC
            """)
    List<CalendarScheduleSnapshot> findDueForInterest(
            @Param("dueAt") LocalDateTime dueAt,
            @Param("region") String region,
            @Param("language") String language,
            @Param("movieTmdbIds") Collection<String> movieTmdbIds,
            @Param("seriesTmdbIds") Collection<String> seriesTmdbIds);
}
