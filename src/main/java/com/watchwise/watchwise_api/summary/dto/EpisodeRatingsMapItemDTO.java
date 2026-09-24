package com.watchwise.watchwise_api.summary.dto;

public record EpisodeRatingsMapItemDTO(
        String seriesTmdbId,
        Long watchedEpisodeCount,
        Integer totalEpisodeCount) {
}
