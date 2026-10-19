package com.watchwise.watchwise_api.search.dto;

import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;

public record SearchListPreviewCardDTO(
        ContentType type,
        String tmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        String title,
        String posterUrl,
        ContentPreviewStatus previewStatus) {
}
