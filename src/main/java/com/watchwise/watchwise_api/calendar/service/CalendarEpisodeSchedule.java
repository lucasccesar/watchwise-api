package com.watchwise.watchwise_api.calendar.service;

import java.time.Instant;
import java.time.LocalDate;

public record CalendarEpisodeSchedule(
        Integer episodeNumber,
        String title,
        LocalDate releaseDate,
        String stillPath,
        Instant lastCheckedAt,
        Instant nextCheckAt) {

    public CalendarEpisodeSchedule withCheckTimes(Instant checkedAt, Instant nextCheckAt) {
        return new CalendarEpisodeSchedule(episodeNumber, title, releaseDate, stillPath, checkedAt, nextCheckAt);
    }
}
