package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.util.Objects;

public record ContentScheduleKey(
        ContentType type,
        String tmdbId,
        String seriesTmdbId,
        Integer seasonNumber) {

    public ContentScheduleKey {
        Objects.requireNonNull(type, "type is required");
        switch (type) {
            case MOVIE, SERIES -> {
                requireText(tmdbId, "tmdbId");
                if (seriesTmdbId != null || seasonNumber != null) {
                    throw new IllegalArgumentException("Movie and series schedules cannot carry season identifiers");
                }
            }
            case SEASON -> {
                requireText(seriesTmdbId, "seriesTmdbId");
                if (tmdbId != null || seasonNumber == null || seasonNumber <= 0) {
                    throw new IllegalArgumentException("Season schedules require a series ID and positive season number");
                }
            }
            case EPISODE -> throw new IllegalArgumentException("Episode schedules are not supported");
        }
    }

    public static ContentScheduleKey movie(String tmdbId) {
        return new ContentScheduleKey(ContentType.MOVIE, tmdbId, null, null);
    }

    public static ContentScheduleKey series(String seriesTmdbId) {
        return new ContentScheduleKey(ContentType.SERIES, seriesTmdbId, null, null);
    }

    public static ContentScheduleKey season(String seriesTmdbId, Integer seasonNumber) {
        return new ContentScheduleKey(ContentType.SEASON, null, seriesTmdbId, seasonNumber);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
