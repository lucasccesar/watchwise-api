package com.watchwise.watchwise_api.calendar.dto;

import java.time.YearMonth;
import java.util.List;

public record CalendarResponseDTO(
        YearMonth month,
        String region,
        List<CalendarEventDTO> events) {
}
