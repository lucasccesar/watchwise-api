package com.watchwise.watchwise_api.summary.dto;

public record EpisodeRatingsMapItemDTO(
        String seriesTmdbId,
        Long watchedEpisodeCount,
        Integer totalEpisodeCount,
        String customPosterUrl,
        String title,
        Integer releaseYear,
        String posterPath,
        String backdropPath) {

    public EpisodeRatingsMapItemDTO(
            String seriesTmdbId,
            Long watchedEpisodeCount,
            Integer totalEpisodeCount,
            String customPosterUrl,
            String title,
            Integer releaseYear,
            String posterPath) {
        this(seriesTmdbId, watchedEpisodeCount, totalEpisodeCount, customPosterUrl,
                title, releaseYear, posterPath, null);
    }

    public EpisodeRatingsMapItemDTO(
            String seriesTmdbId,
            Long watchedEpisodeCount,
            Integer totalEpisodeCount,
            String customPosterUrl) {
        this(seriesTmdbId, watchedEpisodeCount, totalEpisodeCount, customPosterUrl, null, null, null, null);
    }

    public EpisodeRatingsMapItemDTO(
            String seriesTmdbId,
            Long watchedEpisodeCount,
            Integer totalEpisodeCount) {
        this(seriesTmdbId, watchedEpisodeCount, totalEpisodeCount, null, null, null, null, null);
    }
}
