package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.util.UUID;

public record LongestWatchedItemDTO(
        ContentType type,
        UUID contentId,
        String tmdbId,
        String seriesTmdbId,
        long totalMinutesWatched) {
}
