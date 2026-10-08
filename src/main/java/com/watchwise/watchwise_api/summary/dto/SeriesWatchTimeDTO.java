package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;

import java.util.UUID;

public record SeriesWatchTimeDTO(
        UUID contentId,
        String seriesTmdbId,
        long totalMinutesWatched,
        ContentCardDTO card) {

    public SeriesWatchTimeDTO(UUID contentId, String seriesTmdbId, long totalMinutesWatched) {
        this(contentId, seriesTmdbId, totalMinutesWatched, null);
    }

    public SeriesWatchTimeDTO withCard(ContentCardDTO card) {
        return new SeriesWatchTimeDTO(contentId, seriesTmdbId, totalMinutesWatched, card);
    }
}
