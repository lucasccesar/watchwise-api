package com.watchwise.watchwise_api.content.dto;

public record ContentCardStatsDTO(
        Double averageScore,
        Long playsCount,
        Long reviewsCount,
        Long commentsCount) {
}
