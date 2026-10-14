package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.dailygame.dto.DailyGamePlayViewDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptOutcomeDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptViewDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameViewStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;

import java.util.List;

final class DailyGamePlayAssembler {
    DailyGamePlayViewDTO assemble(DailyGamePlayProjection projection) {
        int attemptsRemaining = Math.max(0, projection.maxAttempts() - projection.attemptsUsed());
        boolean canAttempt = projection.status() == DailyGameViewStatus.NOT_PLAYED
                || projection.status() == DailyGameViewStatus.IN_PROGRESS;
        int potentialScore = canAttempt ? projection.maxAttempts() - projection.attemptsUsed() : 0;
        List<DailyGameAttemptViewDTO> attempts = projection.attempts();
        if (projection.gameType() != DailyGameType.EPISODE_BY_FRAME) {
            attempts = attempts.stream()
                    .map(attempt -> new DailyGameAttemptViewDTO(attempt.attemptNumber(), attempt.candidate(),
                            new DailyGameAttemptOutcomeDTO(attempt.outcome().correct(), null, null, null)))
                    .toList();
        }
        return new DailyGamePlayViewDTO(
                projection.challengeDate(), projection.nextResetAt(), projection.gameType(),
                projection.targetKind(), projection.maxAttempts(), projection.attemptsUsed(),
                attemptsRemaining, projection.status(), projection.score(), potentialScore,
                projection.media(), attempts, canAttempt ? null : projection.answer(),
                canAttempt, canAttempt && attemptsRemaining > 0,
                projection.shareOnCompletion(), projection.sharedToFeed());
    }
}
