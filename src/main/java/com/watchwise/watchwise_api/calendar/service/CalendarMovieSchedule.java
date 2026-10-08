package com.watchwise.watchwise_api.calendar.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record CalendarMovieSchedule(
        String tmdbId,
        String region,
        String language,
        LocalDate releaseDate,
        LocalTime releaseTime,
        String title,
        String network,
        String posterPath,
        Instant lastCheckedAt,
        Instant nextCheckAt) {

    public CalendarMovieSchedule(
            String tmdbId,
            String region,
            String language,
            LocalDate releaseDate,
            String title,
            String posterPath,
            Instant lastCheckedAt,
            Instant nextCheckAt) {
        this(tmdbId, region, language, releaseDate, null, title, null, posterPath, lastCheckedAt, nextCheckAt);
    }

    public CalendarMovieSchedule withCheckTimes(Instant checkedAt, Instant nextCheckAt) {
        return new CalendarMovieSchedule(
                tmdbId, region, language, releaseDate, releaseTime, title, network, posterPath, checkedAt, nextCheckAt);
    }
}
