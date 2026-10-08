package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;

import java.util.UUID;

public record LongestWatchedItemDTO(
        ContentType type,
        UUID contentId,
        String tmdbId,
        String seriesTmdbId,
        long totalMinutesWatched,
        ContentCardDTO card) {

    public LongestWatchedItemDTO(ContentType type, UUID contentId, String tmdbId,
            String seriesTmdbId, long totalMinutesWatched) {
        this(type, contentId, tmdbId, seriesTmdbId, totalMinutesWatched, null);
    }

    public LongestWatchedItemDTO withCard(ContentCardDTO card) {
        return new LongestWatchedItemDTO(type, contentId, tmdbId, seriesTmdbId, totalMinutesWatched, card);
    }
}
