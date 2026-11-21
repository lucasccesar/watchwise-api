package com.watchwise.watchwise_api.summary.dto;

import java.util.List;

public record EpisodeRatingsSummaryDTO(
        long totalRatings,
        Double averageScore,
        List<RatingCountDTO> distribution) {

    public EpisodeRatingsSummaryDTO {
        distribution = distribution == null ? List.of() : List.copyOf(distribution);
    }
}
