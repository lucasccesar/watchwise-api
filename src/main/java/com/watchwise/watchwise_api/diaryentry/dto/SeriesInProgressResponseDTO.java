package com.watchwise.watchwise_api.diaryentry.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.watchwise.watchwise_api.seriesprogress.dto.ProgressEpisodeDTO;

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
        String customPosterUrl,
        String seriesTitle,
        String seriesPosterPath,
        String lastWatchedEpisodeTitle,
        ProgressEpisodeDTO nextEpisode,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<SeasonProgressDTO> completedSeasonProgress) {

    public SeriesInProgressResponseDTO {
        seasonProgress = List.copyOf(seasonProgress);
        completedSeasonProgress = List.copyOf(completedSeasonProgress);
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
                null, null, null, null, null, null, null, null, null, List.of());
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
                remainingRuntimeMinutes, null, null, null, null, null, List.of());
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
            Long remainingRuntimeMinutes,
            String customPosterUrl,
            String seriesTitle,
            String seriesPosterPath,
            String lastWatchedEpisodeTitle,
            ProgressEpisodeDTO nextEpisode) {
        this(seriesTmdbId, maxSeasonNumber, maxEpisodeNumber, lastWatchedDate,
                watchedEpisodeCount, totalEpisodeCount, watchedPercentage, seasonProgress,
                lastWatchedSeasonNumber, lastWatchedEpisodeNumber, watchedRuntimeMinutes,
                totalReleasedEpisodeCount, totalKnownRuntime, lastReleasedEpisodeDate,
                remainingEpisodeCount, remainingRuntimeMinutes, customPosterUrl, seriesTitle,
                seriesPosterPath, lastWatchedEpisodeTitle, nextEpisode, List.of());
    }

    public SeriesInProgressResponseDTO withPresentation(
            String seriesTitle,
            String seriesPosterPath,
            String lastWatchedEpisodeTitle,
            ProgressEpisodeDTO nextEpisode) {
        return new SeriesInProgressResponseDTO(
                seriesTmdbId, maxSeasonNumber, maxEpisodeNumber, lastWatchedDate,
                watchedEpisodeCount, totalEpisodeCount, watchedPercentage, seasonProgress,
                lastWatchedSeasonNumber, lastWatchedEpisodeNumber, watchedRuntimeMinutes,
                totalReleasedEpisodeCount, totalKnownRuntime, lastReleasedEpisodeDate,
                remainingEpisodeCount, remainingRuntimeMinutes, customPosterUrl,
                seriesTitle, seriesPosterPath, lastWatchedEpisodeTitle, nextEpisode,
                completedSeasonProgress);
    }

    public SeriesInProgressResponseDTO withSeasonProgress(List<SeasonProgressDTO> seasonProgress) {
        return new SeriesInProgressResponseDTO(
                seriesTmdbId, maxSeasonNumber, maxEpisodeNumber, lastWatchedDate,
                watchedEpisodeCount, totalEpisodeCount, watchedPercentage, seasonProgress,
                lastWatchedSeasonNumber, lastWatchedEpisodeNumber, watchedRuntimeMinutes,
                totalReleasedEpisodeCount, totalKnownRuntime, lastReleasedEpisodeDate,
                remainingEpisodeCount, remainingRuntimeMinutes, customPosterUrl, seriesTitle,
                seriesPosterPath, lastWatchedEpisodeTitle, nextEpisode, completedSeasonProgress);
    }

    public SeriesInProgressResponseDTO withCompletedSeasonProgress(
            List<SeasonProgressDTO> completedSeasonProgress) {
        return new SeriesInProgressResponseDTO(
                seriesTmdbId, maxSeasonNumber, maxEpisodeNumber, lastWatchedDate,
                watchedEpisodeCount, totalEpisodeCount, watchedPercentage, seasonProgress,
                lastWatchedSeasonNumber, lastWatchedEpisodeNumber, watchedRuntimeMinutes,
                totalReleasedEpisodeCount, totalKnownRuntime, lastReleasedEpisodeDate,
                remainingEpisodeCount, remainingRuntimeMinutes, customPosterUrl, seriesTitle,
                seriesPosterPath, lastWatchedEpisodeTitle, nextEpisode, completedSeasonProgress);
    }
}
