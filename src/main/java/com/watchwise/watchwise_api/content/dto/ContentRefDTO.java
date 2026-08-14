package com.watchwise.watchwise_api.content.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.time.LocalDateTime;
import java.util.UUID;

public record ContentRefDTO(
        UUID id,
        String tmdbId,
        ContentType type,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        Boolean isSeasonFinale,
        Boolean isSeriesFinale,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
