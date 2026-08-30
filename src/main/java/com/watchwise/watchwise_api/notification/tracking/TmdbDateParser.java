package com.watchwise.watchwise_api.notification.tracking;

import org.springframework.util.StringUtils;

import java.time.LocalDate;

public final class TmdbDateParser {

    private TmdbDateParser() {
    }

    public static LocalDate parseDate(String value) {
        return StringUtils.hasText(value) ? LocalDate.parse(value) : null;
    }
}
