package com.watchwise.watchwise_api.dailygame.dto;

import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DailyGamePlayViewDTO(
        LocalDate challengeDate, Instant nextResetAt, DailyGameType gameType,
        DailyGameTargetKind targetKind, int maxAttempts, int attemptsUsed,
        int attemptsRemaining, DailyGameViewStatus status, int score, int potentialScore,
        List<DailyGameMediaRefDTO> media, List<DailyGameAttemptViewDTO> attempts,
        DailyGameAnswerPreviewDTO answer, boolean canAttempt, boolean canGiveUp,
        boolean shareOnCompletion, boolean sharedToFeed,
        DailyGameFilmographyPlayStateDTO filmography) {

    public DailyGamePlayViewDTO(
            LocalDate challengeDate, Instant nextResetAt, DailyGameType gameType,
            DailyGameTargetKind targetKind, int maxAttempts, int attemptsUsed,
            int attemptsRemaining, DailyGameViewStatus status, int score, int potentialScore,
            List<DailyGameMediaRefDTO> media, List<DailyGameAttemptViewDTO> attempts,
            DailyGameAnswerPreviewDTO answer, boolean canAttempt, boolean canGiveUp,
            boolean shareOnCompletion, boolean sharedToFeed) {
        this(challengeDate, nextResetAt, gameType, targetKind, maxAttempts, attemptsUsed,
                attemptsRemaining, status, score, potentialScore, media, attempts, answer,
                canAttempt, canGiveUp, shareOnCompletion, sharedToFeed, null);
    }
}
