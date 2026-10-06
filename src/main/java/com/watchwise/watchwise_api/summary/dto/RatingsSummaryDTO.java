package com.watchwise.watchwise_api.summary.dto;

public record RatingsSummaryDTO(
        long totalRatings,
        Double averageScore
) {
}
