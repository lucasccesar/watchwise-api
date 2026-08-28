package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;

public record LongestWatchedItemDTO(
        ContentType type,
        String tmdbId,
        String seriesTmdbId,
        long totalMinutesWatched) {
}
