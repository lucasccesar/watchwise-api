package com.watchwise.watchwise_api.content.dto;

import java.util.List;
import java.util.UUID;

public record ContentPageStatsDTO(
        UUID contentId,
        Double averageScore,
        long ratingsCount,
        List<RatingDistributionDTO> ratingsDistribution,
        long playsCount,
        long commentsCount) {

    public ContentPageStatsDTO {
        ratingsDistribution = ratingsDistribution == null ? List.of() : List.copyOf(ratingsDistribution);
    }
}
