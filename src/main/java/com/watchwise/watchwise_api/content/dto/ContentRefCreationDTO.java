package com.watchwise.watchwise_api.content.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record ContentRefCreationDTO(
        String tmdbId,
        @NotNull ContentType type,
        String seriesTmdbId,
        @PositiveOrZero Integer seasonNumber,
        @Positive Integer episodeNumber,
        Boolean isSeasonFinale,
        Boolean isSeriesFinale
) {
}
