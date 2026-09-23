package com.watchwise.watchwise_api.diaryentry.dto;

import java.time.LocalDate;
import java.util.List;

public record SeriesInProgressResponseDTO(
        String seriesTmdbId,
        Integer maxSeasonNumber,
        Integer maxEpisodeNumber,
        LocalDate lastWatchedDate,
        Long watchedEpisodeCount,
        Integer totalEpisodeCount,
        Double watchedPercentage,
        List<SeasonProgressDTO> seasonProgress) {

    public SeriesInProgressResponseDTO(
            String seriesTmdbId,
            Integer maxSeasonNumber,
            Integer maxEpisodeNumber,
            LocalDate lastWatchedDate,
            Long watchedEpisodeCount,
            Integer totalEpisodeCount,
            Double watchedPercentage) {
        this(seriesTmdbId, maxSeasonNumber, maxEpisodeNumber, lastWatchedDate,
                watchedEpisodeCount, totalEpisodeCount, watchedPercentage, List.of());
    }
}
