package com.watchwise.watchwise_api.dailygame.dto;

public record DailyGamePlayActionResponseDTO(
        DailyGamePlayViewDTO state, DailyGameAttemptViewDTO currentAttempt) {}
