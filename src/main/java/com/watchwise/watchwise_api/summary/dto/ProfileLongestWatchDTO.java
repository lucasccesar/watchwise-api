package com.watchwise.watchwise_api.summary.dto;

public record ProfileLongestWatchDTO(
        ProfileHighlightContentDTO content,
        long totalMinutesWatched,
        Long watchedEpisodeCount
) {
}
