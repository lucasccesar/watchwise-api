package com.watchwise.watchwise_api.calendar.service;

import java.time.Instant;
import java.time.LocalDate;

public record CalendarMovieSchedule(
        String tmdbId,
        String region,
        String language,
        LocalDate releaseDate,
        String title,
        String posterPath,
        Instant lastCheckedAt,
        Instant nextCheckAt) {

    public CalendarMovieSchedule withCheckTimes(Instant checkedAt, Instant nextCheckAt) {
        return new CalendarMovieSchedule(tmdbId, region, language, releaseDate, title, posterPath, checkedAt, nextCheckAt);
    }
}
