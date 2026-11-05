package com.watchwise.watchwise_api.content.dto;

import java.util.List;
import java.util.UUID;

public record ContentPageStatsDTO(
        UUID contentId,
        Double averageScore,
        long ratingsCount,
        List<RatingDistributionDTO> ratingsDistribution,
        long playsCount,
        long commentsCount,
        Double tmdbVoteAverage) {

    public ContentPageStatsDTO {
        ratingsDistribution = ratingsDistribution == null ? List.of() : List.copyOf(ratingsDistribution);
    }

    public ContentPageStatsDTO(
            UUID contentId,
            Double averageScore,
            long ratingsCount,
            List<RatingDistributionDTO> ratingsDistribution,
            long playsCount,
            long commentsCount) {
        this(contentId, averageScore, ratingsCount, ratingsDistribution, playsCount, commentsCount, null);
    }

    public ContentPageStatsDTO withTmdbVoteAverage(Double value) {
        return new ContentPageStatsDTO(contentId, averageScore, ratingsCount, ratingsDistribution,
                playsCount, commentsCount, value);
    }
}
