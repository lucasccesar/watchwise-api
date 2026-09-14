package com.watchwise.watchwise_api.calendar.controller;

import com.watchwise.watchwise_api.calendar.dto.CalendarResponseDTO;
import com.watchwise.watchwise_api.calendar.service.CalendarService;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class CalendarController {

    private static final DateTimeFormatter MONTH_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM").withResolverStyle(ResolverStyle.STRICT);

    private final CalendarService calendarService;

    @GetMapping("/users/me/calendar")
    public ResponseEntity<CalendarResponseDTO> getMonth(@RequestParam String month) {
        YearMonth requestedMonth = parseMonth(month);
        CalendarResponseDTO response = calendarService.getMonth(getCurrentUserId(), requestedMonth);
        return ResponseEntity.ok(response);
    }

    private YearMonth parseMonth(String month) {
        if (month == null || !month.matches("\\d{4}-\\d{2}")) {
            throw new BadRequestException("month must be in YYYY-MM format");
        }

        try {
            YearMonth parsedMonth = YearMonth.parse(month, MONTH_FORMATTER);
            if (!MONTH_FORMATTER.format(parsedMonth).equals(month)) {
                throw new BadRequestException("month must be in YYYY-MM format");
            }
            return parsedMonth;
        } catch (DateTimeException exception) {
            throw new BadRequestException("month must be in YYYY-MM format");
        }
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
