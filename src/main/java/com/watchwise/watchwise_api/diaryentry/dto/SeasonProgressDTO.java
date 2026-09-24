package com.watchwise.watchwise_api.diaryentry.dto;

public record SeasonProgressDTO(
        Integer seasonNumber,
        Long watchedEpisodeCount,
        Integer totalEpisodeCount,
        Double watchedPercentage,
        Long watchedRuntimeMinutes,
        Long remainingEpisodeCount,
        Long remainingRuntimeMinutes) {

    public SeasonProgressDTO(
            Integer seasonNumber,
            Long watchedEpisodeCount,
            Integer totalEpisodeCount,
            Double watchedPercentage) {
        this(seasonNumber, watchedEpisodeCount, totalEpisodeCount, watchedPercentage, null, null, null);
    }

    public Integer releasedEpisodeCount() {
        return totalEpisodeCount;
    }
}
