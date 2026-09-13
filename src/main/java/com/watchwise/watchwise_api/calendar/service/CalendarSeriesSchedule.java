package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One TV lookup's regular-season schedule payload and its evidence of completeness.
 */
public record CalendarSeriesSchedule(
        CalendarScheduleKey key,
        List<Season> seasons,
        Map<Integer, Integer> expectedEpisodeCountsBySeason,
        int totalRegularEpisodeCount) {

    public CalendarSeriesSchedule {
        Objects.requireNonNull(key, "key is required");
        if (key.type() != com.watchwise.watchwise_api.content.entity.ContentType.SERIES) {
            throw new IllegalArgumentException("Series schedules require a series key");
        }
        seasons = List.copyOf(seasons);
        expectedEpisodeCountsBySeason = Map.copyOf(new LinkedHashMap<>(expectedEpisodeCountsBySeason));
        if (totalRegularEpisodeCount < 0) {
            throw new IllegalArgumentException("Total regular episode count cannot be negative");
        }
    }

    public boolean hasRemoteResults() {
        return seasons.stream().anyMatch(season -> season.origin() == TmdbLookupOrigin.REMOTE);
    }

    public record Season(CalendarSeasonSchedule schedule, int expectedEpisodeCount, TmdbLookupOrigin origin) {

        public Season {
            Objects.requireNonNull(schedule, "schedule is required");
            Objects.requireNonNull(origin, "origin is required");
        }

        public boolean isFullyRepresented() {
            return expectedEpisodeCount >= 0
                    && schedule.episodes().size() == expectedEpisodeCount
                    && schedule.episodeCoordinates().stream().allMatch(number -> number != null && number > 0)
                    && schedule.episodeCoordinates().stream().distinct().count() == schedule.episodes().size();
        }
    }
}
