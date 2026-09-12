package com.watchwise.watchwise_api.calendar.dto;

public record EpisodeCalendarContentDTO(
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        String title,
        String seriesTitle,
        String posterPath,
        String stillPath) implements CalendarEventContentDTO {
}
