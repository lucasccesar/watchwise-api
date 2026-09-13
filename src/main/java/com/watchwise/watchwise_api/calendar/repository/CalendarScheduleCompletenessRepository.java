package com.watchwise.watchwise_api.calendar.repository;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CalendarScheduleCompletenessRepository extends JpaRepository<CalendarScheduleCompleteness, UUID> {

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

    List<CalendarScheduleCompleteness> findByRegionAndLanguage(String region, String language);
}
