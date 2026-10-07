package com.watchwise.watchwise_api.diaryentry.dto;

import java.time.LocalDate;

public record DiaryDaySummaryDTO(
        LocalDate date,
        long plays,
        long totalMinutes
) {
}
