package com.watchwise.watchwise_api.content.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;
import jakarta.validation.constraints.NotNull;

public record ContentRefCreationDTO(
        String tmdbId,
        @NotNull ContentType type,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        Boolean isSeasonFinale,
        Boolean isSeriesFinale
) {
}
