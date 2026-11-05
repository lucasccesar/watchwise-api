package com.watchwise.watchwise_api.content.dto;

import java.util.UUID;

public record ContentStatsResponseDTO(
        UUID contentId,
        Double averageScore,
        long playsCount,
        long reviewsCount,
        long commentsCount,
        Double tmdbVoteAverage
) {
    public ContentStatsResponseDTO(
            UUID contentId,
            Double averageScore,
            long playsCount,
            long reviewsCount,
            long commentsCount) {
        this(contentId, averageScore, playsCount, reviewsCount, commentsCount, null);
    }
}
