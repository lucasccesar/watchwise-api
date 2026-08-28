package com.watchwise.watchwise_api.diaryentry.dto;

import java.time.LocalDate;

public record SeriesInProgressResponseDTO(
        String seriesTmdbId,
        Integer maxSeasonNumber,
        Integer maxEpisodeNumber,
        LocalDate lastWatchedDate) {
}
