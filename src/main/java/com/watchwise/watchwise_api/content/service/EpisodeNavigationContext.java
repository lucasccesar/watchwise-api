package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.EpisodeSummaryDTO;

public record EpisodeNavigationContext(
        Integer seasonEpisodeCount,
        EpisodeSummaryDTO previousEpisode,
        EpisodeSummaryDTO nextEpisode) {
}
