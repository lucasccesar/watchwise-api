package com.watchwise.watchwise_api.summary.dto;

import java.time.LocalDate;

public record DailyMinutesDTO(
        LocalDate watchedDate,
        long minutesWatched) {
}
