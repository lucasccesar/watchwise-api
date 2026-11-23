package com.watchwise.watchwise_api.seriesprogress.dto;

import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;

import java.time.LocalDate;
import java.util.List;

public record IndividualSeriesProgressDTO(
        long watchedEpisodeCount,
        Integer totalEpisodeCount,
        Integer totalReleasedEpisodeCount,
        Double watchedPercentage,
        Long remainingEpisodeCount,
        LocalDate lastWatchedDate,
        Integer lastWatchedSeasonNumber,
        List<Season> seasonProgress) {

    public static IndividualSeriesProgressDTO from(SeriesInProgressResponseDTO progress) {
        Integer released = progress.totalReleasedEpisodeCount();
        long watched = progress.watchedEpisodeCount();
        return new IndividualSeriesProgressDTO(
                watched, progress.totalEpisodeCount(), released, progress.watchedPercentage(),
                released == null ? null : Math.max(0L, released.longValue() - watched),
                progress.lastWatchedDate(), progress.lastWatchedSeasonNumber(),
                progress.seasonProgress().stream()
                        .map(season -> new Season(season.seasonNumber(), season.watchedEpisodeCount(),
                                season.totalEpisodeCount()))
                        .toList());
    }

    public record Season(Integer seasonNumber, Long watchedEpisodeCount, Integer totalEpisodeCount) {
    }
}
