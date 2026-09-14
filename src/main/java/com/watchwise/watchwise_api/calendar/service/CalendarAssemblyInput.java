package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.calendar.dto.CalendarSource;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;

import java.time.Clock;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CalendarAssemblyInput(
        YearMonth month,
        Clock clock,
        List<CalendarScheduleSnapshot> snapshots,
        Map<CalendarScheduleKey, Set<CalendarSource>> sourcesByKey,
        Set<WatchedCalendarKey> watchedKeys,
        String region,
        String preferredLanguage,
        Completeness completeness) {

    public CalendarAssemblyInput {
        Objects.requireNonNull(month, "month is required");
        Objects.requireNonNull(clock, "clock is required");
        Objects.requireNonNull(snapshots, "snapshots are required");
        Objects.requireNonNull(sourcesByKey, "sourcesByKey is required");
        Objects.requireNonNull(watchedKeys, "watchedKeys are required");
        Objects.requireNonNull(region, "region is required");
        Objects.requireNonNull(preferredLanguage, "preferredLanguage is required");
        Objects.requireNonNull(completeness, "completeness is required");

        snapshots = List.copyOf(snapshots);
        sourcesByKey = copySources(sourcesByKey);
        watchedKeys = Set.copyOf(watchedKeys);
    }

    private static Map<CalendarScheduleKey, Set<CalendarSource>> copySources(
            Map<CalendarScheduleKey, Set<CalendarSource>> sourcesByKey) {
        Map<CalendarScheduleKey, Set<CalendarSource>> copiedSources = new LinkedHashMap<>();
        sourcesByKey.forEach((key, sources) -> copiedSources.put(
                Objects.requireNonNull(key, "calendar schedule key is required"),
                Set.copyOf(Objects.requireNonNull(sources, "calendar sources are required"))));
        return Map.copyOf(copiedSources);
    }

    public record Completeness(
            Set<CompleteSeasonKey> completeSeasonKeys,
            Set<CalendarScheduleKey> completeSeriesKeys) {

        public Completeness {
            Objects.requireNonNull(completeSeasonKeys, "completeSeasonKeys are required");
            Objects.requireNonNull(completeSeriesKeys, "completeSeriesKeys are required");
            completeSeasonKeys = Set.copyOf(completeSeasonKeys);
            completeSeriesKeys = Set.copyOf(completeSeriesKeys);
        }

        public static Completeness empty() {
            return new Completeness(Set.of(), Set.of());
        }
    }

    public record CompleteSeasonKey(String seriesTmdbId, int seasonNumber) {

        public CompleteSeasonKey {
            if (seriesTmdbId == null || seriesTmdbId.isBlank()) {
                throw new IllegalArgumentException("Complete season keys require a series TMDB ID");
            }
            if (seasonNumber <= 0) {
                throw new IllegalArgumentException("Complete season keys require a positive season number");
            }
        }
    }
}
