package com.watchwise.watchwise_api.dailygame.dto;

public record DailyGameGuessFeedbackDTO(
        boolean seriesCorrect,
        boolean seasonCorrect,
        boolean episodeCorrect,
        boolean exactMatch) {
}
