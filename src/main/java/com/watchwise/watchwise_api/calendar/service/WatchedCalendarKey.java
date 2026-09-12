package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.content.entity.ContentType;

public record WatchedCalendarKey(
        ContentType type,
        String tmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber) {

    public static WatchedCalendarKey movie(String tmdbId) {
        return new WatchedCalendarKey(ContentType.MOVIE, tmdbId, null, null, null);
    }

    public static WatchedCalendarKey episode(String seriesTmdbId, int seasonNumber, int episodeNumber) {
        return new WatchedCalendarKey(ContentType.EPISODE, null, seriesTmdbId, seasonNumber, episodeNumber);
    }
}
