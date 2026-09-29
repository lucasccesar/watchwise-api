package com.watchwise.watchwise_api.dailygame.dto;

import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;

import java.time.LocalDate;

public record DailyGameResultPreviewDTO(
        LocalDate challengeDate,
        DailyGameType gameType,
        DailyGameTargetKind targetKind,
        int maxAttempts,
        DailyGameViewStatus status,
        int attemptsUsed,
        int score,
        DailyGameAnswerDTO answer) {
}
