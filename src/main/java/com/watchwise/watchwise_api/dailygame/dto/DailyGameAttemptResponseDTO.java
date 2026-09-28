package com.watchwise.watchwise_api.dailygame.dto;

import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;

import java.time.LocalDateTime;
import java.util.List;

public record DailyGameAttemptResponseDTO(
        DailyGameType gameType,
        DailyGameTargetKind targetKind,
        int maxAttempts,
        int attemptsUsed,
        int attemptsRemaining,
        DailyGameViewStatus status,
        String imageUrl,
        List<DailyGameHintDTO> hints,
        int score,
        LocalDateTime completedAt,
        DailyGameAnswerDTO answer) {
}
