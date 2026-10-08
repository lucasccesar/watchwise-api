package com.watchwise.watchwise_api.calendar.dto;

import java.time.LocalTime;

public record CalendarReleaseContext(
        LocalTime releaseTime,
        String network) {
}
