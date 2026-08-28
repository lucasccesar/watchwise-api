package com.watchwise.watchwise_api.content.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ContentRefCreationDTO(
        String tmdbId,
        @NotNull ContentType type,
        String seriesTmdbId,
        @PositiveOrZero Integer seasonNumber,
        @Positive Integer episodeNumber,
        Boolean isSeasonFinale,
        Boolean isSeriesFinale,
        @Positive @Max(1200) Integer runtimeMinutes,
        @Valid List<@NotBlank @Size(max = 50) String> genres,
        @Min(1870) @Max(2100) Integer releaseYear,
        @Valid List<@NotBlank @Size(min = 2, max = 2) String> countries
) {
    public ContentRefCreationDTO(String tmdbId, ContentType type, String seriesTmdbId, Integer seasonNumber,
            Integer episodeNumber, Boolean isSeasonFinale, Boolean isSeriesFinale) {
        this(tmdbId, type, seriesTmdbId, seasonNumber, episodeNumber, isSeasonFinale, isSeriesFinale, null, null,
                null, null);
    }

    public ContentRefCreationDTO(String tmdbId, ContentType type, String seriesTmdbId, Integer seasonNumber,
            Integer episodeNumber, Boolean isSeasonFinale, Boolean isSeriesFinale, Integer runtimeMinutes,
            List<String> genres) {
        this(tmdbId, type, seriesTmdbId, seasonNumber, episodeNumber, isSeasonFinale, isSeriesFinale, runtimeMinutes,
                genres, null, null);
    }
}
