package com.watchwise.watchwise_api.calendar.repository;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CalendarScheduleCompletenessRepository extends JpaRepository<CalendarScheduleCompleteness, UUID> {

    void deleteBySeriesTmdbIdAndRegionAndLanguage(String seriesTmdbId, String region, String language);

    Optional<CalendarScheduleCompleteness> findByGroupTypeAndSeriesTmdbIdAndSeasonNumberAndRegionAndLanguage(
            CalendarScheduleCompleteness.GroupType groupType,
            String seriesTmdbId,
            Integer seasonNumber,
            String region,
            String language);

    default Optional<CalendarScheduleCompleteness> findSeasonIdentity(
            String seriesTmdbId, Integer seasonNumber, String region, String language) {
        return findByGroupTypeAndSeriesTmdbIdAndSeasonNumberAndRegionAndLanguage(
                CalendarScheduleCompleteness.GroupType.SEASON, seriesTmdbId, seasonNumber, region, language);
    }

    default Optional<CalendarScheduleCompleteness> findSeriesIdentity(
            String seriesTmdbId, String region, String language) {
        return findByGroupTypeAndSeriesTmdbIdAndSeasonNumberAndRegionAndLanguage(
                CalendarScheduleCompleteness.GroupType.SERIES, seriesTmdbId, null, region, language);
    }

    @Query("""
            SELECT marker FROM CalendarScheduleCompleteness marker
            WHERE marker.region = :region
              AND marker.language = :language
              AND marker.complete = true
              AND marker.seriesTmdbId IN :seriesTmdbIds
            """)
    List<CalendarScheduleCompleteness> findCompleteForInterest(
            @Param("region") String region,
            @Param("language") String language,
            @Param("seriesTmdbIds") java.util.Collection<String> seriesTmdbIds);
}
