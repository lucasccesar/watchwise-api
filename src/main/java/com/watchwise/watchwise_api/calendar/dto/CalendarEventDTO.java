package com.watchwise.watchwise_api.calendar.dto;

import com.watchwise.watchwise_api.content.dto.ReleaseStatus;
import com.watchwise.watchwise_api.content.dto.WatchStatus;

import java.time.LocalDate;
import java.util.Set;

public record CalendarEventDTO(
        LocalDate date,
        CalendarEventType eventType,
        ReleaseStatus releaseStatus,
        WatchStatus watchStatus,
        Set<CalendarSource> sources,
        CalendarEventContentDTO content) {
}
