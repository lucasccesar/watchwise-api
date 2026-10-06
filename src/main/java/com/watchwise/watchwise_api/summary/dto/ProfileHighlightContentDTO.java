package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.util.UUID;

public record ProfileHighlightContentDTO(
        ContentType type,
        UUID contentId,
        String tmdbId,
        String seriesTmdbId,
        String title,
        Integer releaseYear,
        String posterPath,
        String customPosterUrl
) {
}
