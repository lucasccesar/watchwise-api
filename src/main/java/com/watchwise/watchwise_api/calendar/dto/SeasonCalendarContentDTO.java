package com.watchwise.watchwise_api.calendar.dto;

public record SeasonCalendarContentDTO(
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeCount,
        String title,
        String posterPath) implements CalendarEventContentDTO {
}
