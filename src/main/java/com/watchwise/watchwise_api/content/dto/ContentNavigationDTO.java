package com.watchwise.watchwise_api.content.dto;

public record ContentNavigationDTO(
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        Integer seasonEpisodeCount,
        ContentChildCardDTO previousEpisode,
        ContentChildCardDTO nextEpisode) {
}
