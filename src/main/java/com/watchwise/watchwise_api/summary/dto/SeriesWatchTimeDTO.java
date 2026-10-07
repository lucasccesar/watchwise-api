package com.watchwise.watchwise_api.summary.dto;

import java.util.UUID;

public record SeriesWatchTimeDTO(
        UUID contentId,
        String seriesTmdbId,
        long totalMinutesWatched) {
}
