package com.watchwise.watchwise_api.summary.dto;

import java.time.LocalDate;

public record SeriesInProgressPreviewDTO(
        String seriesTmdbId,
        Integer maxSeasonNumber,
        Integer maxEpisodeNumber,
        LocalDate lastWatchedDate,
        Long watchedEpisodeCount,
        Integer totalEpisodeCount,
        Double watchedPercentage,
        String seriesTitle,
        String episodeTitle,
        String stillPath,
        Integer runtimeMinutes,
        java.time.LocalDate releaseDate,
        java.util.UUID contentId,
        Double communityAverageScore,
        Boolean availableToWatch) {

    public SeriesInProgressPreviewDTO(
            String seriesTmdbId,
            Integer maxSeasonNumber,
            Integer maxEpisodeNumber,
            java.time.LocalDate lastWatchedDate,
            Long watchedEpisodeCount,
            Integer totalEpisodeCount,
            Double watchedPercentage) {
        this(seriesTmdbId, maxSeasonNumber, maxEpisodeNumber, lastWatchedDate, watchedEpisodeCount,
                totalEpisodeCount, watchedPercentage, null, null, null, null, null, null, null, null);
    }
}
