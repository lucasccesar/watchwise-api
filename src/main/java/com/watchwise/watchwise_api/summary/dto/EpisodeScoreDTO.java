package com.watchwise.watchwise_api.summary.dto;

public record EpisodeScoreDTO(
        Integer seasonNumber,
        Integer episodeNumber,
        Integer score,
        Double averageScore,
        long ratingCount) {

    public EpisodeScoreDTO(Integer seasonNumber, Integer episodeNumber, Integer score) {
        this(seasonNumber, episodeNumber, score, null, 0L);
    }
}
