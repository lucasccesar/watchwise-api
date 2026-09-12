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

/**
 * Immutable facts consumed when assembling one calendar month.
 */
public record CalendarAssemblyInput(
        YearMonth month,
        Clock clock,
        List<CalendarScheduleSnapshot> snapshots,
        Map<CalendarScheduleKey, Set<CalendarSource>> sourcesByKey,
        Set<WatchedCalendarKey> watchedKeys,
        String region) {

    public CalendarAssemblyInput {
        Objects.requireNonNull(month, "month is required");
        Objects.requireNonNull(clock, "clock is required");
        Objects.requireNonNull(snapshots, "snapshots are required");
        Objects.requireNonNull(sourcesByKey, "sourcesByKey is required");
        Objects.requireNonNull(watchedKeys, "watchedKeys are required");
        Objects.requireNonNull(region, "region is required");

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
}
