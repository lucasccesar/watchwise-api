package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;

import java.time.Instant;
import java.util.Objects;

public record CalendarScheduleBatch(
        CalendarScheduleKey key,
        TmdbLookupOrigin origin,
        Instant loadedAt,
        CalendarMovieSchedule movie,
        CalendarSeasonSchedule season,
        Integer expectedEpisodeCount) {

    public CalendarScheduleBatch(
            CalendarScheduleKey key,
            TmdbLookupOrigin origin,
            Instant loadedAt,
            CalendarMovieSchedule movie,
            CalendarSeasonSchedule season) {
        this(key, origin, loadedAt, movie, season, null);
    }

    public CalendarScheduleBatch {
        Objects.requireNonNull(key, "key is required");
        Objects.requireNonNull(origin, "origin is required");
        Objects.requireNonNull(loadedAt, "loadedAt is required");
        if ((movie == null) == (season == null)) {
            throw new IllegalArgumentException("A schedule batch must contain exactly one schedule type");
        }
        if (expectedEpisodeCount != null && (season == null || expectedEpisodeCount < 0)) {
            throw new IllegalArgumentException("Expected episode count requires a season batch and cannot be negative");
        }
    }

    public static CalendarScheduleBatch movie(
            CalendarScheduleKey key, TmdbLookupOrigin origin, CalendarMovieSchedule movie) {
        return new CalendarScheduleBatch(key, origin, Instant.now(), movie, null);
    }

    public static CalendarScheduleBatch season(
            CalendarScheduleKey key, TmdbLookupOrigin origin, CalendarSeasonSchedule season) {
        return season(key, origin, season, null);
    }

    public static CalendarScheduleBatch season(
            CalendarScheduleKey key,
            TmdbLookupOrigin origin,
            CalendarSeasonSchedule season,
            Integer expectedEpisodeCount) {
        return new CalendarScheduleBatch(key, origin, Instant.now(), null, season, expectedEpisodeCount);
    }
}
