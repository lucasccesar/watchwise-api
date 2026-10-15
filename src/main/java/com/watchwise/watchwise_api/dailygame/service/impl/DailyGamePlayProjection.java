package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptViewDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAnswerPreviewDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyPlayStateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameMediaRefDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameViewStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

record DailyGamePlayProjection(
        LocalDate challengeDate, Instant nextResetAt, DailyGameType gameType,
        DailyGameTargetKind targetKind, int maxAttempts, int attemptsUsed,
        DailyGameViewStatus status, int score, List<DailyGameMediaRefDTO> media,
        List<DailyGameAttemptViewDTO> attempts, DailyGameAnswerPreviewDTO answer,
        boolean shareOnCompletion, boolean sharedToFeed,
        DailyGameFilmographyPlayStateDTO filmography) {

    DailyGamePlayProjection(
            LocalDate challengeDate, Instant nextResetAt, DailyGameType gameType,
            DailyGameTargetKind targetKind, int maxAttempts, int attemptsUsed,
            DailyGameViewStatus status, int score, List<DailyGameMediaRefDTO> media,
            List<DailyGameAttemptViewDTO> attempts, DailyGameAnswerPreviewDTO answer,
            boolean shareOnCompletion, boolean sharedToFeed) {
        this(challengeDate, nextResetAt, gameType, targetKind, maxAttempts, attemptsUsed,
                status, score, media, attempts, answer, shareOnCompletion, sharedToFeed, null);
    }
}
