package com.watchwise.watchwise_api.dailygame.service;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptRequest;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameStateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameTodayResponseDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;

import java.time.LocalDate;
import java.util.UUID;

public interface DailyGameService {

    DailyGameTodayResponseDTO getToday(UUID userId);

    DailyGameTodayResponseDTO getDay(UUID userId, LocalDate challengeDate);

    DailyGameStateDTO getGame(
            UUID userId, LocalDate challengeDate, DailyGameType gameType, boolean majorRoles);

    DailyGameAttemptResponseDTO submitAttempt(
            UUID userId, DailyGameType gameType, DailyGameAttemptRequest request);

    DailyGameAttemptResponseDTO submitAttempt(
            UUID userId, LocalDate challengeDate, DailyGameType gameType, DailyGameAttemptRequest request);

    DailyGameAttemptResponseDTO giveUp(UUID userId, DailyGameType gameType);

    DailyGameAttemptResponseDTO giveUp(UUID userId, LocalDate challengeDate, DailyGameType gameType);
}
