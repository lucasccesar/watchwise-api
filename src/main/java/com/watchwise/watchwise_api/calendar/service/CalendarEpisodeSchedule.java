package com.watchwise.watchwise_api.calendar.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record CalendarEpisodeSchedule(
        Integer episodeNumber,
        String title,
        LocalDate releaseDate,
        LocalTime releaseTime,
        String stillPath,
        Instant lastCheckedAt,
        Instant nextCheckAt) {

    public CalendarEpisodeSchedule(
            Integer episodeNumber,
            String title,
            LocalDate releaseDate,
            String stillPath,
            Instant lastCheckedAt,
            Instant nextCheckAt) {
        this(episodeNumber, title, releaseDate, null, stillPath, lastCheckedAt, nextCheckAt);
    }

    public CalendarEpisodeSchedule withCheckTimes(Instant checkedAt, Instant nextCheckAt) {
        return new CalendarEpisodeSchedule(
                episodeNumber, title, releaseDate, releaseTime, stillPath, checkedAt, nextCheckAt);
    }
}
