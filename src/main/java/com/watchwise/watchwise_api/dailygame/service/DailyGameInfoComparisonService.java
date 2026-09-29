package com.watchwise.watchwise_api.dailygame.service;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameInfoFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;

public interface DailyGameInfoComparisonService {

    DailyGameInfoFeedbackDTO compare(DailyChallenge challenge, DailyGameCandidateIdentity candidate);
}
