package com.watchwise.watchwise_api.dailygame.dto;

public record DailyGameAttemptViewDTO(
        int attemptNumber, DailyGameCandidateViewDTO candidate,
        DailyGameAttemptOutcomeDTO outcome,
        DailyGameInfoFeedbackDTO infoFeedback) {

    public DailyGameAttemptViewDTO(
            int attemptNumber, DailyGameCandidateViewDTO candidate, DailyGameAttemptOutcomeDTO outcome) {
        this(attemptNumber, candidate, outcome, null);
    }
}
