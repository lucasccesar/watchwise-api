package com.watchwise.watchwise_api.summary.dto;

import java.time.LocalDate;

public record DailyWatchCountDTO(
        LocalDate watchedDate,
        long count) {
}
