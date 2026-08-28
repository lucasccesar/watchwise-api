package com.watchwise.watchwise_api.summary.dto;

public record EpisodeScoreDTO(
        Integer seasonNumber,
        Integer episodeNumber,
        Integer score) {
}
