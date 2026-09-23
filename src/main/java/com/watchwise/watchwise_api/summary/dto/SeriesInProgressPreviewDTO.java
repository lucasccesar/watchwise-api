package com.watchwise.watchwise_api.summary.dto;

import java.time.LocalDate;

public record SeriesInProgressPreviewDTO(
        String seriesTmdbId,
        Integer maxSeasonNumber,
        Integer maxEpisodeNumber,
        LocalDate lastWatchedDate,
        Long watchedEpisodeCount,
        Integer totalEpisodeCount,
        Double watchedPercentage) {
}
