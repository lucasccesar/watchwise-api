package com.watchwise.watchwise_api.summary.dto;

import java.util.List;

public record EpisodeRatingsGridResponseDTO(
        String seriesTmdbId,
        List<EpisodeScoreDTO> episodes,
        String customPosterUrl,
        String title,
        Integer releaseYear,
        String posterPath,
        String backdropPath,
        Double overallAverageScore,
        long overallRatingCount,
        Double tmdbVoteAverage) {

    public EpisodeRatingsGridResponseDTO {
        episodes = List.copyOf(episodes);
    }

    public EpisodeRatingsGridResponseDTO(
            String seriesTmdbId,
            List<EpisodeScoreDTO> episodes,
            String customPosterUrl,
            String title,
            Integer releaseYear,
            String posterPath,
            String backdropPath,
            Double overallAverageScore,
            long overallRatingCount) {
        this(seriesTmdbId, episodes, customPosterUrl, title, releaseYear, posterPath, backdropPath,
                overallAverageScore, overallRatingCount, null);
    }

    public EpisodeRatingsGridResponseDTO(
            String seriesTmdbId,
            List<EpisodeScoreDTO> episodes,
            String customPosterUrl) {
        this(seriesTmdbId, episodes, customPosterUrl, null, null, null, null, null, 0L, null);
    }

    public EpisodeRatingsGridResponseDTO(
            String seriesTmdbId,
            List<EpisodeScoreDTO> episodes) {
        this(seriesTmdbId, episodes, null, null, null, null, null, null, 0L, null);
    }
}
