package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.util.UUID;

public record HomeContentReferenceDTO(
        UUID id,
        String tmdbId,
        ContentType type,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        Integer runtimeMinutes
) {
}
