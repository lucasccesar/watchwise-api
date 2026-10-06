package com.watchwise.watchwise_api.summary.service;

public record EpisodeRatingsMetadata(
        String seriesTmdbId,
        String title,
        Integer releaseYear,
        String posterPath,
        String backdropPath) {
}
