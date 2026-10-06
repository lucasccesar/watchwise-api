package com.watchwise.watchwise_api.summary.service;

import java.util.List;

public record EpisodeRatingsMetadata(
        String seriesTmdbId,
        String title,
        Integer releaseYear,
        String posterPath,
        String backdropPath,
        List<Integer> regularSeasonNumbers) {
    public EpisodeRatingsMetadata {
        regularSeasonNumbers = List.copyOf(regularSeasonNumbers);
    }

    public EpisodeRatingsMetadata(String seriesTmdbId, String title, Integer releaseYear,
            String posterPath, String backdropPath) {
        this(seriesTmdbId, title, releaseYear, posterPath, backdropPath, List.of());
    }
}
