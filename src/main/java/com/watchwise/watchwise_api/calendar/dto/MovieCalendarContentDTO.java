package com.watchwise.watchwise_api.calendar.dto;

public record MovieCalendarContentDTO(
        String tmdbId,
        String title,
        String posterPath) implements CalendarEventContentDTO {
}
