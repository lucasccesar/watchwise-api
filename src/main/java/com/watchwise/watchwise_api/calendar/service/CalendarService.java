package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.calendar.dto.CalendarResponseDTO;
import com.watchwise.watchwise_api.calendar.dto.CalendarEventDTO;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public interface CalendarService {

    CalendarResponseDTO getMonth(UUID userId, YearMonth month);

    List<CalendarEventDTO> getUpcoming(UUID userId, int limit);
}
