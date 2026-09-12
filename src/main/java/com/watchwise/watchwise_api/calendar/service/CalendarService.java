package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.calendar.dto.CalendarResponseDTO;

import java.time.YearMonth;
import java.util.UUID;

public interface CalendarService {

    CalendarResponseDTO getMonth(UUID userId, YearMonth month);
}
