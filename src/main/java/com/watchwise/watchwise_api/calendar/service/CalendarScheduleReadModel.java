package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;

import java.util.List;
import java.util.Objects;

/**
 * Shared schedule facts available for one user's active keys and one exact locale.
 */
public record CalendarScheduleReadModel(
        List<CalendarScheduleSnapshot> snapshots,
        CalendarAssemblyInput.Completeness completeness) {

    public CalendarScheduleReadModel {
        Objects.requireNonNull(snapshots, "snapshots are required");
        Objects.requireNonNull(completeness, "completeness is required");
        snapshots = List.copyOf(snapshots);
    }
}
