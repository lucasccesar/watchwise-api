package com.watchwise.watchwise_api.summary.dto;

public record EpisodeRatingsMapItemDTO(
        String seriesTmdbId,
        Long watchedEpisodeCount,
        Integer totalEpisodeCount,
        String customPosterUrl) {

    public EpisodeRatingsMapItemDTO(
            String seriesTmdbId,
            Long watchedEpisodeCount,
            Integer totalEpisodeCount) {
        this(seriesTmdbId, watchedEpisodeCount, totalEpisodeCount, null);
    }
}
