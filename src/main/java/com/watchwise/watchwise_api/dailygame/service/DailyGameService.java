package com.watchwise.watchwise_api.dailygame.service;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptRequest;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameTodayResponseDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;

import java.time.LocalDate;
import java.util.UUID;

public interface DailyGameService {

    DailyGameTodayResponseDTO getToday(UUID userId);

    DailyGameTodayResponseDTO getDay(UUID userId, LocalDate challengeDate);

    DailyGameAttemptResponseDTO submitAttempt(
            UUID userId, DailyGameType gameType, DailyGameAttemptRequest request);

    DailyGameAttemptResponseDTO submitAttempt(
            UUID userId, LocalDate challengeDate, DailyGameType gameType, DailyGameAttemptRequest request);
}
