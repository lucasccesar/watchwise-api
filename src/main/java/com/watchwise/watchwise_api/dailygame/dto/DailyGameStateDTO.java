package com.watchwise.watchwise_api.dailygame.dto;

import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;

import java.time.LocalDateTime;
import java.util.List;

public record DailyGameStateDTO(
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
        DailyGameAnswerDTO answer,
        List<String> visibleImageUrls,
        List<String> imageUrls,
        List<DailyGameAttemptDTO> attempts,
        DailyGameFilmographyStateDTO filmography) {

    public DailyGameStateDTO(
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
        this(gameType, targetKind, maxAttempts, attemptsUsed, attemptsRemaining, status, imageUrl, hints,
                score, completedAt, answer, null, null);
    }

    public DailyGameStateDTO(
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
            DailyGameAnswerDTO answer,
            List<String> visibleImageUrls,
            List<String> imageUrls) {
        this(gameType, targetKind, maxAttempts, attemptsUsed, attemptsRemaining, status, imageUrl, hints,
                score, completedAt, answer, visibleImageUrls, imageUrls, null, null);
    }
}
