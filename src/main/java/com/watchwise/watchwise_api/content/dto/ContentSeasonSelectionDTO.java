package com.watchwise.watchwise_api.content.dto;

import java.time.LocalDate;

public record ContentSeasonSelectionDTO(
        String seriesTmdbId,
        int seasonNumber,
        String name,
        Integer episodeCount,
        String posterUrl,
        LocalDate releaseDate) {
}
