package com.watchwise.watchwise_api.dailygame.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DailyGamesOverviewDTO(
        LocalDate date,
        Instant nextResetAt,
        int totalMaxScore,
        int currentScore,
        List<DailyGameOverviewCardDTO> games
) {
}
