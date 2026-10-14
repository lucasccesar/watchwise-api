package com.watchwise.watchwise_api.dailygame.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DailyGameRankingViewEntryDTO(
        long rank,
        UUID userId,
        String name,
        String username,
        String profilePicture,
        long score,
        long daysPlayed,
        long gamesPlayed,
        int solvedPercentage,
        BigDecimal averageAttempts) {
}
