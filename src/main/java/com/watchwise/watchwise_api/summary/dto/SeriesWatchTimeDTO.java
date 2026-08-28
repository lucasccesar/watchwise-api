package com.watchwise.watchwise_api.summary.dto;

public record SeriesWatchTimeDTO(
        String seriesTmdbId,
        long totalMinutesWatched) {
}
