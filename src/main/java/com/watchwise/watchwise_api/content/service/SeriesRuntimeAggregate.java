package com.watchwise.watchwise_api.content.service;

public record SeriesRuntimeAggregate(
        Integer totalRuntimeMinutes,
        Integer averageRuntimeMinutes,
        Integer knownEpisodeCount,
        Integer reportedEpisodeCount) {
}
