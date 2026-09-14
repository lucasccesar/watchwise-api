package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Shared schedule facts available for one user's active keys and one exact locale.
 */
public record CalendarScheduleReadModel(
        List<CalendarScheduleSnapshot> snapshots,
        CalendarAssemblyInput.Completeness completeness,
        Set<CalendarScheduleKey> negativeSeriesKeys) {

    public CalendarScheduleReadModel(
            List<CalendarScheduleSnapshot> snapshots,
            CalendarAssemblyInput.Completeness completeness) {
        this(snapshots, completeness, Set.of());
    }

    public CalendarScheduleReadModel {
        Objects.requireNonNull(snapshots, "snapshots are required");
        Objects.requireNonNull(completeness, "completeness is required");
        Objects.requireNonNull(negativeSeriesKeys, "negativeSeriesKeys are required");
        snapshots = List.copyOf(snapshots);
        negativeSeriesKeys = Set.copyOf(negativeSeriesKeys);
    }
}
