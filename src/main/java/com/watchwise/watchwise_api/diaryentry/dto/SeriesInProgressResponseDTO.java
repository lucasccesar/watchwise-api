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
        List<SeasonProgressDTO> seasonProgress,
        Integer lastWatchedSeasonNumber,
        Integer lastWatchedEpisodeNumber,
        Long watchedRuntimeMinutes,
        Integer totalReleasedEpisodeCount,
        Integer totalKnownRuntime,
        LocalDate lastReleasedEpisodeDate,
        Long remainingEpisodeCount,
        Long remainingRuntimeMinutes,
        String customPosterUrl) {

    public SeriesInProgressResponseDTO {
        seasonProgress = List.copyOf(seasonProgress);
    }

    public SeriesInProgressResponseDTO(
            String seriesTmdbId,
            Integer maxSeasonNumber,
            Integer maxEpisodeNumber,
            LocalDate lastWatchedDate,
            Long watchedEpisodeCount,
            Integer totalEpisodeCount,
            Double watchedPercentage,
            List<SeasonProgressDTO> seasonProgress) {
        this(seriesTmdbId, maxSeasonNumber, maxEpisodeNumber, lastWatchedDate, watchedEpisodeCount,
                totalEpisodeCount, watchedPercentage, seasonProgress, null, null, null, totalEpisodeCount,
                null, null, null, null, null);
    }

    public SeriesInProgressResponseDTO(
            String seriesTmdbId,
            Integer maxSeasonNumber,
            Integer maxEpisodeNumber,
            LocalDate lastWatchedDate,
            Long watchedEpisodeCount,
            Integer totalEpisodeCount,
            Double watchedPercentage,
            List<SeasonProgressDTO> seasonProgress,
            Integer lastWatchedSeasonNumber,
            Integer lastWatchedEpisodeNumber,
            Long watchedRuntimeMinutes,
            Integer totalReleasedEpisodeCount,
            Integer totalKnownRuntime,
            LocalDate lastReleasedEpisodeDate,
            Long remainingEpisodeCount,
            Long remainingRuntimeMinutes) {
        this(seriesTmdbId, maxSeasonNumber, maxEpisodeNumber, lastWatchedDate, watchedEpisodeCount,
                totalEpisodeCount, watchedPercentage, seasonProgress, lastWatchedSeasonNumber,
                lastWatchedEpisodeNumber, watchedRuntimeMinutes, totalReleasedEpisodeCount,
                totalKnownRuntime, lastReleasedEpisodeDate, remainingEpisodeCount,
                remainingRuntimeMinutes, null);
    }

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
