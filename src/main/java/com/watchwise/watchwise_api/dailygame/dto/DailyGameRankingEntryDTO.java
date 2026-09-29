package com.watchwise.watchwise_api.dailygame.dto;

import java.util.UUID;

public record DailyGameRankingEntryDTO(
        long rank,
        UUID userId,
        String username,
        String profilePicture,
        long score,
        long gamesPlayed) {
}
