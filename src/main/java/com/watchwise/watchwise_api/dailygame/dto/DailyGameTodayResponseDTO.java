package com.watchwise.watchwise_api.dailygame.dto;

import java.time.LocalDate;
import java.util.List;

public record DailyGameTodayResponseDTO(
        LocalDate date,
        List<DailyGameStateDTO> games) {
}
