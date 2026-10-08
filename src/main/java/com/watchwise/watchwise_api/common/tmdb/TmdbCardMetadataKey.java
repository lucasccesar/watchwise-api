package com.watchwise.watchwise_api.common.tmdb;

public record TmdbCardMetadataKey(
        Type type,
        String tmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber) {

    public enum Type {
        MOVIE,
        SERIES,
        SEASON,
        EPISODE
    }

    String cacheKey(String language) {
        return type + "|" + tmdbId + "|" + seriesTmdbId + "|" + seasonNumber + "|" + episodeNumber + "|" + language;
    }
}
