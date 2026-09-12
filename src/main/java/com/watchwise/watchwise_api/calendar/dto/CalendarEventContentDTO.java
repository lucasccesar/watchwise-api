package com.watchwise.watchwise_api.calendar.dto;

public sealed interface CalendarEventContentDTO
        permits MovieCalendarContentDTO, EpisodeCalendarContentDTO, SeasonCalendarContentDTO, SeriesCalendarContentDTO {
}
