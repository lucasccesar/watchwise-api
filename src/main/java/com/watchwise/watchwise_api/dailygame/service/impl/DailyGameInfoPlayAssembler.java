package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptViewDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameInfoFeedbackDTO;

final class DailyGameInfoPlayAssembler {
    DailyGameAttemptViewDTO assemble(
            DailyGameAttemptViewDTO attempt, DailyGameInfoFeedbackDTO infoFeedback) {
        if (attempt == null) return null;
        return new DailyGameAttemptViewDTO(attempt.attemptNumber(), attempt.candidate(), attempt.outcome(),
                infoFeedback);
    }
}
