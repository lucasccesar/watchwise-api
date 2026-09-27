package com.watchwise.watchwise_api.dailygame.service;

import java.time.LocalDate;

public interface DailyChallengeGenerationService {

    void ensureGenerated(LocalDate challengeDate);
}
