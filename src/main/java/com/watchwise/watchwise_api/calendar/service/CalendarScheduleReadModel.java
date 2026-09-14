package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;

public record CalendarScheduleReadModel(
        List<CalendarScheduleSnapshot> snapshots,
        CalendarAssemblyInput.Completeness completeness,
        Set<CalendarScheduleKey> negativeSeriesKeys,
        Map<CalendarScheduleKey, LocalDateTime> seriesDiscoveryCheckedAt) {

    public CalendarScheduleReadModel(
            List<CalendarScheduleSnapshot> snapshots,
            CalendarAssemblyInput.Completeness completeness) {
        this(snapshots, completeness, Set.of(), Map.of());
    }

    public CalendarScheduleReadModel(
            List<CalendarScheduleSnapshot> snapshots,
            CalendarAssemblyInput.Completeness completeness,
            Set<CalendarScheduleKey> negativeSeriesKeys) {
        this(snapshots, completeness, negativeSeriesKeys, Map.of());
    }

    public CalendarScheduleReadModel {
        Objects.requireNonNull(snapshots, "snapshots are required");
        Objects.requireNonNull(completeness, "completeness is required");
        Objects.requireNonNull(negativeSeriesKeys, "negativeSeriesKeys are required");
        Objects.requireNonNull(seriesDiscoveryCheckedAt, "seriesDiscoveryCheckedAt is required");
        snapshots = List.copyOf(snapshots);
        negativeSeriesKeys = Set.copyOf(negativeSeriesKeys);
        seriesDiscoveryCheckedAt = Map.copyOf(seriesDiscoveryCheckedAt);
    }
}
