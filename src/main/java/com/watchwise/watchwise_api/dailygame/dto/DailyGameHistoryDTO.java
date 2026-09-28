package com.watchwise.watchwise_api.dailygame.dto;

import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record DailyGameHistoryDTO(
        LocalDate challengeDate,
        DailyGameType gameType,
        DailyGameTargetKind targetKind,
        int maxAttempts,
        int attemptsUsed,
        int attemptsRemaining,
        DailyGameViewStatus status,
        String imageUrl,
        int score,
        LocalDateTime completedAt,
        DailyGameAnswerDTO answer) {
}
