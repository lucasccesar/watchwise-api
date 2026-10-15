package com.watchwise.watchwise_api.dailygame.service;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptRequest;
import com.watchwise.watchwise_api.dailygame.dto.DailyGamePlayActionResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGamePlayViewDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;

import java.util.UUID;

public interface DailyGamePlayService {
    DailyGamePlayViewDTO readToday(UUID userId, DailyGameType gameType);

    DailyGamePlayViewDTO readToday(UUID userId, DailyGameType gameType, boolean majorRoles);

    DailyGamePlayActionResponseDTO submitToday(
            UUID userId, DailyGameType gameType, DailyGameAttemptRequest request);

    DailyGamePlayActionResponseDTO submitToday(
            UUID userId, DailyGameType gameType, DailyGameAttemptRequest request, boolean majorRoles);

    DailyGamePlayActionResponseDTO giveUpToday(UUID userId, DailyGameType gameType);

    DailyGamePlayActionResponseDTO giveUpToday(UUID userId, DailyGameType gameType, boolean majorRoles);
}
