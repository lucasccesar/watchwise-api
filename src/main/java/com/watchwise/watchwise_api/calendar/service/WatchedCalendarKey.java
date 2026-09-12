package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.content.entity.ContentType;

public record WatchedCalendarKey(
        ContentType type,
        String tmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber) {

    public WatchedCalendarKey {
        if (type != ContentType.MOVIE && type != ContentType.EPISODE) {
            throw new IllegalArgumentException("Watched calendar keys require MOVIE or EPISODE content");
        }

        if (type == ContentType.MOVIE) {
            if (isBlank(tmdbId)
                    || seriesTmdbId != null
                    || seasonNumber != null
                    || episodeNumber != null) {
                throw new IllegalArgumentException("Movie keys require only a TMDB ID");
            }
        } else {
            if (tmdbId != null
                    || isBlank(seriesTmdbId)
                    || seasonNumber == null
                    || seasonNumber <= 0
                    || episodeNumber == null
                    || episodeNumber <= 0) {
                throw new IllegalArgumentException(
                        "Episode keys require a series TMDB ID and positive season and episode numbers");
            }
        }
    }

    public static WatchedCalendarKey movie(String tmdbId) {
        return new WatchedCalendarKey(ContentType.MOVIE, tmdbId, null, null, null);
    }

    public static WatchedCalendarKey episode(String seriesTmdbId, int seasonNumber, int episodeNumber) {
        return new WatchedCalendarKey(ContentType.EPISODE, null, seriesTmdbId, seasonNumber, episodeNumber);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
