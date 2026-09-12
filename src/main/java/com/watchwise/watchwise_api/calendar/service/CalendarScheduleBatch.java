package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;

import java.time.Instant;
import java.util.Objects;

public record CalendarScheduleBatch(
        CalendarScheduleKey key,
        TmdbLookupOrigin origin,
        Instant loadedAt,
        CalendarMovieSchedule movie,
        CalendarSeasonSchedule season) {

    public CalendarScheduleBatch {
        Objects.requireNonNull(key, "key is required");
        Objects.requireNonNull(origin, "origin is required");
        Objects.requireNonNull(loadedAt, "loadedAt is required");
        if ((movie == null) == (season == null)) {
            throw new IllegalArgumentException("A schedule batch must contain exactly one schedule type");
        }
    }

    public static CalendarScheduleBatch movie(
            CalendarScheduleKey key, TmdbLookupOrigin origin, CalendarMovieSchedule movie) {
        return new CalendarScheduleBatch(key, origin, Instant.now(), movie, null);
    }

    public static CalendarScheduleBatch season(
            CalendarScheduleKey key, TmdbLookupOrigin origin, CalendarSeasonSchedule season) {
        return new CalendarScheduleBatch(key, origin, Instant.now(), null, season);
    }
}
