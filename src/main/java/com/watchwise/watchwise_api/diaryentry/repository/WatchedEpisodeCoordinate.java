package com.watchwise.watchwise_api.diaryentry.repository;

public record WatchedEpisodeCoordinate(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {

    public WatchedEpisodeCoordinate {
        if (seriesTmdbId == null || seriesTmdbId.isBlank()) {
            throw new IllegalArgumentException("Watched episode coordinates require a series TMDB ID");
        }
        if (seasonNumber == null || seasonNumber < 0) {
            throw new IllegalArgumentException("Watched episode coordinates require a non-negative season number");
        }
        if (episodeNumber == null || episodeNumber <= 0) {
            throw new IllegalArgumentException("Watched episode coordinates require a positive episode number");
        }
    }
}
