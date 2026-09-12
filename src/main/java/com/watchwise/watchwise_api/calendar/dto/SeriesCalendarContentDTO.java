package com.watchwise.watchwise_api.calendar.dto;

public record SeriesCalendarContentDTO(
        String seriesTmdbId,
        Integer episodeCount,
        String title,
        String posterPath) implements CalendarEventContentDTO {
}
