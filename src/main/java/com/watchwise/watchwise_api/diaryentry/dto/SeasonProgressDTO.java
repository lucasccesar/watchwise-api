package com.watchwise.watchwise_api.diaryentry.dto;

public record SeasonProgressDTO(
        Integer seasonNumber,
        Long watchedEpisodeCount,
        Integer totalEpisodeCount,
        Double watchedPercentage) {
}
