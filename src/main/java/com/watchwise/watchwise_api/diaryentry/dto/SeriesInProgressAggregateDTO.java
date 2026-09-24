package com.watchwise.watchwise_api.diaryentry.dto;

public record SeriesInProgressAggregateDTO(
        long totalSeriesCount,
        long watchedEpisodeCount,
        long totalReleasedEpisodeCount,
        long remainingEpisodeCount,
        Long remainingRuntimeMinutes) {

    public long seriesCount() {
        return totalSeriesCount;
    }

    public long releasedEpisodeCount() {
        return totalReleasedEpisodeCount;
    }
}
