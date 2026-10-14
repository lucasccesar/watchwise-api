package com.watchwise.watchwise_api.dailygame.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DailyGameRankingViewerDTO(
        UUID userId,
        String name,
        String username,
        String profilePicture,
        Long rank,
        long score,
        long daysPlayed,
        long gamesPlayed,
        int solvedPercentage,
        BigDecimal averageAttempts,
        boolean inTop200,
        Long top200CutoffScore,
        Long pointsToTop200) {
}
