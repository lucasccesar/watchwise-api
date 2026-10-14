package com.watchwise.watchwise_api.dailygame.dto;

public record DailyGameAttemptOutcomeDTO(
        boolean correct, Boolean seriesCorrect, Boolean seasonCorrect, Boolean episodeCorrect) {}
