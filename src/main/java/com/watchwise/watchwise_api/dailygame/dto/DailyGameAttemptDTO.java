package com.watchwise.watchwise_api.dailygame.dto;

public record DailyGameAttemptDTO(
        int attemptNumber,
        DailyGameCandidateDTO candidate,
        DailyGameGuessFeedbackDTO episodeFeedback,
        DailyGameInfoFeedbackDTO infoFeedback,
        DailyGameFilmographyFeedbackDTO filmographyFeedback) {
}
