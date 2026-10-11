package com.watchwise.watchwise_api.content.dto;

import java.time.LocalDate;

public record ContentEpisodeSelectionDTO(
        String seriesTmdbId,
        int seasonNumber,
        int episodeNumber,
        String name,
        String stillUrl,
        LocalDate airDate) {
}
