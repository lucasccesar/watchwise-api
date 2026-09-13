package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Collections;
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
        expectedEpisodeCountsBySeason = Collections.unmodifiableMap(new LinkedHashMap<>(expectedEpisodeCountsBySeason));
        if (expectedEpisodeCountsBySeason.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey() <= 0 || entry.getValue() == null || entry.getValue() < 0)) {
            throw new IllegalArgumentException("Regular seasons require positive numbers and nonnegative expected episode counts");
        }
        int expectedTotal = expectedEpisodeCountsBySeason.values().stream().mapToInt(Integer::intValue).sum();
        if (totalRegularEpisodeCount != expectedTotal) {
            throw new IllegalArgumentException("Total regular episode count must match regular-season expected counts");
        }
        HashSet<Integer> seasonNumbers = new HashSet<>();
        for (Season season : seasons) {
            CalendarSeasonSchedule details = season.schedule();
            if (details.seasonNumber() == null || details.seasonNumber() <= 0
                    || !seasonNumbers.add(details.seasonNumber())
                    || !key.tmdbId().equals(details.seriesTmdbId())
                    || !key.preferredLanguage().equals(details.language())
                    || !key.preferredRegion().equals(details.region())
                    || !expectedEpisodeCountsBySeason.containsKey(details.seasonNumber())
                    || expectedEpisodeCountsBySeason.get(details.seasonNumber()) != season.expectedEpisodeCount()) {
                throw new IllegalArgumentException("Season schedules must match the series identity and expected counts");
            }
            if (details.episodes().stream().anyMatch(episode -> episode.episodeNumber() == null || episode.episodeNumber() <= 0)
                    || details.episodeCoordinates().stream().distinct().count() != details.episodes().size()) {
                throw new IllegalArgumentException("Season schedules require unique positive episode numbers");
            }
        }
    }

    public boolean hasRemoteResults() {
        return seasons.stream().anyMatch(season -> season.origin() == TmdbLookupOrigin.REMOTE);
    }

    public record Season(CalendarSeasonSchedule schedule, int expectedEpisodeCount, TmdbLookupOrigin origin) {

        public Season {
            Objects.requireNonNull(schedule, "schedule is required");
            Objects.requireNonNull(origin, "origin is required");
            if (expectedEpisodeCount < 0) {
                throw new IllegalArgumentException("Expected episode count cannot be negative");
            }
        }

        public boolean isFullyRepresented() {
            return expectedEpisodeCount >= 0
                    && schedule.episodes().size() == expectedEpisodeCount
                    && schedule.episodeCoordinates().stream().allMatch(number -> number != null && number > 0)
                    && schedule.episodeCoordinates().stream().distinct().count() == schedule.episodes().size();
        }
    }
}
